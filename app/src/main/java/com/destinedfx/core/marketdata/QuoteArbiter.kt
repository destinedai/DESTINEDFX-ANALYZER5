package com.destinedfx.core.marketdata

sealed interface Decision {
    object Accept : Decision
    data class Reject(val reason: String) : Decision
    data class SwitchSource(val reason: String) : Decision
}

object QuoteArbiter {
    fun accept(
        existing: QuoteView?,
        incoming: QuoteView,
        activeSource: String?,
        lastActiveSourceMs: Long,
        pollIntervalMs: Long,
        nowMs: Long
    ): Decision {
        // (5) an invalid quote (bid <= 0, ask < bid, price <= 0) is Reject.
        if (incoming.price <= 0.0) {
            return Decision.Reject("Invalid price <= 0: ${incoming.price}")
        }
        if (incoming.bid != null && incoming.bid <= 0.0) {
            return Decision.Reject("Invalid bid <= 0: ${incoming.bid}")
        }
        if (incoming.bid != null && incoming.ask != null && incoming.ask < incoming.bid) {
            return Decision.Reject("Invalid ask < bid: bid ${incoming.bid} ask ${incoming.ask}")
        }

        // (4) a DAILY_REFERENCE quote is never accepted
        if (incoming.kind == "DAILY_REFERENCE") {
            return Decision.Reject("DAILY_REFERENCE quotes are never accepted")
        }

        // (1) incoming timestamp <= existing timestamp -> Reject
        if (existing != null && incoming.lastUpdateMillis <= existing.lastUpdateMillis) {
            return Decision.Reject("Incoming timestamp (${incoming.lastUpdateMillis}) is not newer than existing (${existing.lastUpdateMillis})")
        }

        // (2) a fresh STREAM quote cannot be replaced by a polled quote.
        if (existing != null && existing.kind == "STREAM" && incoming.kind != "STREAM") {
            val ageOfStream = nowMs - existing.lastUpdateMillis
            if (ageOfStream < 5000L) {
                return Decision.Reject("Cannot replace fresh STREAM quote (age ${ageOfStream}ms < 5000ms) with polled quote")
            }
        }

        // (3) two different streaming providers must not alternate each other's quote.
        // Keep the current stream until it has been silent for 5 s; then a fresh stream
        // from another provider may take over. This keeps quote/candle source identity stable.
        if (existing != null && existing.kind == "STREAM" && incoming.kind == "STREAM" &&
            existing.source != incoming.source
        ) {
            val ageOfStream = nowMs - existing.lastUpdateMillis
            if (ageOfStream < 5000L) {
                return Decision.Reject(
                    "Cannot replace fresh STREAM from ${existing.source} with ${incoming.source} (age ${ageOfStream}ms < 5000ms)"
                )
            }
        }

        // (4) a polled quote is accepted only if its source == activeSource, or the active source has been silent for 3x pollIntervalMs (then SwitchSource)
        if (incoming.kind != "STREAM") {
            if (activeSource != null && incoming.source != activeSource) {
                val silenceDuration = nowMs - lastActiveSourceMs
                val maxSilence = 3 * pollIntervalMs
                return if (silenceDuration > maxSilence) {
                    Decision.SwitchSource("Active source $activeSource silent for ${silenceDuration}ms (threshold ${maxSilence}ms)")
                } else {
                    Decision.Reject("Polled quote source ${incoming.source} does not match active source $activeSource and silence (${silenceDuration}ms) is less than threshold (${maxSilence}ms)")
                }
            }
        }

        return Decision.Accept
    }
}
