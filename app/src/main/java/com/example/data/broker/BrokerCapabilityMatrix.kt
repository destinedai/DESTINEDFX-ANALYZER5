package com.example.data.broker

/** Evidence status for a broker capability; a listed broker is never proof of readiness. */
enum class CapabilityStatus {
    IMPLEMENTED,
    NOT_IMPLEMENTED,
    NOT_VERIFIED,
    BLOCKED
}

data class BrokerCapability(
    val operation: String,
    val status: CapabilityStatus,
    val evidence: String
)

data class BrokerCapabilityProfile(
    val broker: BrokerType,
    val capabilities: List<BrokerCapability>
) {
    fun capability(operation: String): BrokerCapability? =
        capabilities.firstOrNull { it.operation == operation }
}

/**
 * Static, truthful capability disclosure. Runtime account and broker evidence must still be
 * collected before an operation is authorized.
 */
object BrokerCapabilityMatrix {
    private fun cap(operation: String, status: CapabilityStatus, evidence: String) =
        BrokerCapability(operation, status, evidence)

    val profiles: List<BrokerCapabilityProfile> = listOf(
        BrokerCapabilityProfile(
            BrokerType.DERIV,
            listOf(
                cap("market_data", CapabilityStatus.IMPLEMENTED, "Deriv WebSocket gateway and source validation exist."),
                cap("authentication", CapabilityStatus.IMPLEMENTED, "Account authorization path exists; account acceptance is not verified here."),
                cap("account_verification", CapabilityStatus.NOT_VERIFIED, "Requires a genuine authenticated DEMO/REAL account response."),
                cap("demo_orders", CapabilityStatus.NOT_VERIFIED, "Multiplier proposal/purchase acceptance and reconciliation remain unverified."),
                cap("live_orders", CapabilityStatus.BLOCKED, "No LIVE authorization is granted by this application state."),
                cap("stop_close", CapabilityStatus.NOT_VERIFIED, "Sell/close route exists; broker-confirmed acceptance and recovery are unverified."),
                cap("trade_history", CapabilityStatus.NOT_VERIFIED, "Broker history reconciliation is not verified in this environment."),
                cap("reconciliation", CapabilityStatus.NOT_VERIFIED, "Requires broker-confirmed transaction and contract evidence."),
            ),
        ),
        BrokerCapabilityProfile(
            BrokerType.MT5,
            listOf(
                cap("market_data", CapabilityStatus.NOT_VERIFIED, "Managed terminal/provider state is required."),
                cap("authentication", CapabilityStatus.NOT_VERIFIED, "Depends on the authenticated bridge and worker."),
                cap("account_verification", CapabilityStatus.NOT_VERIFIED, "Requires exact terminal login, server, mode and account identity."),
                cap("demo_orders", CapabilityStatus.NOT_VERIFIED, "Requires a genuine MT5 terminal, EA, worker and broker DEMO response."),
                cap("live_orders", CapabilityStatus.BLOCKED, "LIVE remains separately gated and unapproved."),
                cap("stop_close", CapabilityStatus.NOT_VERIFIED, "Bridge commands exist; broker/EA confirmation and recovery are unverified."),
                cap("trade_history", CapabilityStatus.NOT_VERIFIED, "Requires terminal-reported history evidence."),
                cap("reconciliation", CapabilityStatus.NOT_VERIFIED, "Requires end-to-end order/fill reconciliation."),
            ),
        ),
        BrokerCapabilityProfile(
            BrokerType.FBS,
            listOf(
                cap("market_data", CapabilityStatus.NOT_VERIFIED, "Uses the managed MT5 terminal route; provider feed is not verified here."),
                cap("authentication", CapabilityStatus.NOT_VERIFIED, "Requires a genuine FBS MT5 terminal login and fresh EA heartbeat."),
                cap("account_verification", CapabilityStatus.NOT_VERIFIED, "Requires exact FBS account, server and company identity evidence."),
                cap("demo_orders", CapabilityStatus.NOT_VERIFIED, "Requires a genuine FBS DEMO terminal and provider-side reconciliation."),
                cap("live_orders", CapabilityStatus.BLOCKED, "LIVE execution is not enabled by this application."),
                cap("stop_close", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed managed-terminal state."),
                cap("trade_history", CapabilityStatus.NOT_VERIFIED, "Requires terminal-reported history evidence."),
                cap("reconciliation", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed order and fill evidence."),
            ),
        ),
        BrokerCapabilityProfile(
            BrokerType.EXNESS,
            listOf(
                cap("market_data", CapabilityStatus.NOT_VERIFIED, "Uses the managed MT5 terminal route; provider feed is not verified here."),
                cap("authentication", CapabilityStatus.NOT_VERIFIED, "Requires a genuine Exness MT5 terminal login and fresh EA heartbeat."),
                cap("account_verification", CapabilityStatus.NOT_VERIFIED, "Requires exact Exness account, server and company identity evidence."),
                cap("demo_orders", CapabilityStatus.NOT_VERIFIED, "Requires a genuine Exness DEMO terminal and provider-side reconciliation."),
                cap("live_orders", CapabilityStatus.BLOCKED, "LIVE execution is not enabled by this application."),
                cap("stop_close", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed managed-terminal state."),
                cap("trade_history", CapabilityStatus.NOT_VERIFIED, "Requires terminal-reported history evidence."),
                cap("reconciliation", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed order and fill evidence."),
            ),
        ),
        BrokerCapabilityProfile(
            BrokerType.HFM,
            listOf(
                cap("market_data", CapabilityStatus.NOT_VERIFIED, "Uses the managed MT5 terminal route; provider feed is not verified here."),
                cap("authentication", CapabilityStatus.NOT_VERIFIED, "Requires a genuine HFM MT5 terminal login and fresh EA heartbeat."),
                cap("account_verification", CapabilityStatus.NOT_VERIFIED, "Requires exact HFM account, server and company identity evidence."),
                cap("demo_orders", CapabilityStatus.NOT_VERIFIED, "Requires a genuine HFM DEMO terminal and provider-side reconciliation."),
                cap("live_orders", CapabilityStatus.BLOCKED, "LIVE execution is not enabled by this application."),
                cap("stop_close", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed managed-terminal state."),
                cap("trade_history", CapabilityStatus.NOT_VERIFIED, "Requires terminal-reported history evidence."),
                cap("reconciliation", CapabilityStatus.NOT_VERIFIED, "Requires provider-confirmed order and fill evidence."),
            ),
        ),
    )

    fun forBroker(broker: BrokerType): BrokerCapabilityProfile =
        profiles.first { it.broker == broker }
}
