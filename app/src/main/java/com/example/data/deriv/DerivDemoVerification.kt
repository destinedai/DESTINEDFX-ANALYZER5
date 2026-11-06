package com.example.data.deriv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val PUBLIC_OPTIONS_WS = "wss://api.derivws.com/trading/v1/options/ws/public"
private const val MAX_EVIDENCE_AGE_MS = 8_000L
private const val MAX_TICK_AGE_MS = 10_000L
private val publicTickRequestIds = AtomicInteger(1)

enum class DerivDemoVerificationState { NOT_RUN, VERIFYING, PASS, FAILED }

data class DerivPublicTickEvidence(
    val symbol: String,
    val quote: Double,
    val providerTimestampMs: Long,
    val receivedAtMs: Long,
    val verificationStartedAtMs: Long,
) {
    fun isFresh(nowMs: Long): Boolean = symbol == "R_100" && quote.isFinite() && quote > 0.0 &&
        verificationStartedAtMs > 0L && providerTimestampMs >= verificationStartedAtMs / 1_000L * 1_000L &&
        receivedAtMs >= verificationStartedAtMs && receivedAtMs <= nowMs &&
        providerTimestampMs <= receivedAtMs && receivedAtMs - providerTimestampMs <= MAX_TICK_AGE_MS
}

data class DerivDemoVerificationResult(
    val selectedAccountId: String,
    val accountId: String,
    val accountType: String,
    val currency: String,
    val balanceValue: Double,
    val balanceReceived: Boolean,
    val balanceAccountId: String,
    val balanceCurrency: String,
    val balanceUpdatedAtMs: Long,
    val portfolioReceived: Boolean,
    val portfolioAccountId: String,
    val portfolioMode: String,
    val portfolioCurrency: String,
    val portfolioRetrievedAtMs: Long,
    val tickEvidence: DerivPublicTickEvidence?,
    val checkedAtMs: Long,
    val failureMessage: String? = null,
    val accountPrefix: String = redactedDerivAccountPrefix(accountId),
) {
    val tickReceived: Boolean get() = tickEvidence?.isFresh(checkedAtMs) == true
    val state: DerivDemoVerificationState
        get() {
            val balanceAge = checkedAtMs - balanceUpdatedAtMs
            val portfolioAge = checkedAtMs - portfolioRetrievedAtMs
            val valid = failureMessage.isNullOrBlank() && selectedAccountId.isNotBlank() &&
                accountId == selectedAccountId && accountType.equals("demo", true) && currency.isNotBlank() &&
                balanceValue.isFinite() && balanceValue >= 0.0 &&
                balanceReceived && balanceAccountId == accountId && balanceCurrency.equals(currency, true) &&
                balanceAge in 0..MAX_EVIDENCE_AGE_MS &&
                portfolioReceived && portfolioAccountId == accountId && portfolioMode.equals("DEMO", true) &&
                portfolioCurrency.equals(currency, true) && portfolioAge in 0..MAX_EVIDENCE_AGE_MS &&
                tickReceived
            return if (valid) DerivDemoVerificationState.PASS else DerivDemoVerificationState.FAILED
        }
}

/** One-shot public tick check, intentionally isolated from the authenticated account socket and its cache. */
suspend fun verifyFreshDerivPublicTick(
    symbol: String = "R_100",
    nowMs: () -> Long = System::currentTimeMillis,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
): DerivPublicTickEvidence = withContext(Dispatchers.IO) {
    require(symbol == "R_100") { "DEMO verification requires the explicit R_100 public tick." }
    val startedAtMs = nowMs()
    val completed = CompletableDeferred<DerivPublicTickEvidence>()
    var socket: WebSocket? = null
    val requestId = publicTickRequestIds.getAndIncrement()
    var subscriptionId: String? = null
    try {
        socket = client.newWebSocket(Request.Builder().url(PUBLIC_OPTIONS_WS).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("ticks", "R_100").put("subscribe", 1).put("req_id", requestId).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val response = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (response.optInt("req_id", -1) != requestId || response.optString("msg_type") != "tick" || response.has("error")) return
                val tick = response.optJSONObject("tick") ?: return
                val epochSeconds = tick.optLong("epoch", 0L)
                val providerMs = if (epochSeconds > 0L && epochSeconds <= Long.MAX_VALUE / 1_000L) epochSeconds * 1_000L else 0L
                val receivedMs = nowMs()
                val quote = tick.optDouble("quote", Double.NaN)
                val evidence = DerivPublicTickEvidence(tick.optString("symbol"), quote, providerMs, receivedMs, startedAtMs)
                subscriptionId = response.optJSONObject("subscription")?.optString("id")?.takeIf { it.isNotBlank() }
                if (evidence.isFresh(receivedMs)) completed.complete(evidence)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                completed.completeExceptionally(t)
            }
        })
        withTimeout(15_000L) { completed.await() }
    } finally {
        try {
            val sub = subscriptionId
            if (sub != null) socket?.send(JSONObject().put("forget", sub).put("req_id", publicTickRequestIds.getAndIncrement()).toString())
            socket?.close(1000, "tick verification complete")
        } catch (_: Exception) {
            socket?.cancel()
        }
    }
}

fun redactedDerivAccountPrefix(accountId: String): String = accountId.trim().take(4).ifBlank { "unverified" }

fun derivDemoVerificationLabel(result: DerivDemoVerificationResult?): String = when (result?.state) {
    null -> "NOT RUN"
    DerivDemoVerificationState.PASS -> "PASS · READ-ONLY DEMO EVIDENCE"
    DerivDemoVerificationState.FAILED -> "FAILED · DEMO NOT VERIFIED"
    DerivDemoVerificationState.NOT_RUN -> "NOT RUN"
    DerivDemoVerificationState.VERIFYING -> "VERIFYING"
}
