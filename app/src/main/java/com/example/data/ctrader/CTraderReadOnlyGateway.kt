package com.example.data.ctrader

import com.example.security.SecretsManager
import com.xtrader.protocol.openapi.v2.ProtoOAAccountAuthReq
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenReq
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenRes
import com.xtrader.protocol.openapi.v2.ProtoOAApplicationAuthReq
import com.xtrader.protocol.openapi.v2.ProtoOASpotEvent
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeSpotsReq
import com.xtrader.protocol.openapi.v2.ProtoOATraderReq
import com.xtrader.protocol.openapi.v2.ProtoOATraderRes
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType
import com.xtrader.protocol.openapi.v2.model.ProtoOATrader
import com.xtrader.protocol.proto.commons.ProtoHeartbeatEvent
import com.xtrader.protocol.proto.commons.ProtoMessage
import com.xtrader.protocol.proto.commons.model.ProtoPayloadType
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
private const val DEMO_HOST = "wss://demo.ctraderapi.com:5036"
private const val LIVE_HOST = "wss://live.ctraderapi.com:5036"

data class CTraderVerifiedAccount(
    val accountId: Long,
    val traderLogin: Long,
    val brokerName: String,
    val isLive: Boolean,
    val balance: Double,
    val leverage: Double,
    val receivedAtMs: Long
)

data class CTraderAccountOption(
    val accountId: Long,
    val traderLogin: Long,
    val brokerName: String,
    val isLive: Boolean
)

data class CTraderQuote(
    val accountId: Long,
    val symbolId: Long,
    val bid: Double?,
    val ask: Double?,
    val providerTimestampMs: Long,
    val receivedAtMs: Long
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        providerTimestampMs > 0L && receivedAtMs <= nowMs && nowMs - receivedAtMs <= 15_000L &&
            listOfNotNull(bid, ask).all { it.isFinite() && it > 0.0 }
}

/**
 * Read-only cTrader Open API session. This class intentionally has no order, amend, or close methods.
 * All transport payloads are generated from Spotware's official protobuf definitions.
 */
class CTraderReadOnlyGateway(
    private val secrets: SecretsManager,
    private val oauth: CTraderOAuthClient,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ProtoMessage>>()
    private val _quotes = MutableStateFlow<CTraderQuote?>(null)
    val quotes: StateFlow<CTraderQuote?> = _quotes
    private var socket: WebSocket? = null
    private var connectedAccountId: Long = 0L
    private var selectedBroker: String = ""
    private var selectedLive: Boolean = false

    suspend fun verify(
        brokerName: String,
        live: Boolean,
        accountId: Long? = null,
        nowMs: () -> Long = System::currentTimeMillis
    ): Result<CTraderVerifiedAccount> = withContext(Dispatchers.IO) {
        selectedBroker = brokerName.trim()
        selectedLive = live
        if (selectedBroker.isBlank()) return@withContext Result.failure(IllegalArgumentException("Broker is required"))
        if (!oauth.accessTokenIsFresh(nowMs())) {
            val refreshed = oauth.refresh().getOrElse { return@withContext Result.failure(IllegalStateException("cTrader authorization is expired; re-authorize the app")) }
            if (refreshed.accessToken.isBlank()) return@withContext Result.failure(IllegalStateException("cTrader authorization did not produce an access token"))
        }
        try {
            val accounts = listAccountsInternal(live)
            val selected = accounts.firstOrNull { it.isLive == selectedLive && (accountId == null || it.accountId == accountId) }
                ?: throw IllegalStateException("No ${if (selectedLive) "LIVE" else "DEMO"} cTrader account was authorised")
            if (selected.brokerName.isBlank() || !selected.brokerName.equals(selectedBroker, ignoreCase = true)) {
                throw IllegalStateException("BLOCKED: cTrader broker identity did not match the selected broker")
            }
            connectedAccountId = selected.accountId
            sendAndAwait(
                ProtoOAPayloadType.PROTO_OA_ACCOUNT_AUTH_REQ_VALUE,
                ProtoOAAccountAuthReq.newBuilder()
                    .setCtidTraderAccountId(connectedAccountId)
                    .setAccessToken(secrets.getCTraderAccessToken())
                    .build(),
                ProtoOAPayloadType.PROTO_OA_ACCOUNT_AUTH_RES_VALUE
            )
            val traderReply = sendAndAwait(
                ProtoOAPayloadType.PROTO_OA_TRADER_REQ_VALUE,
                ProtoOATraderReq.newBuilder().setCtidTraderAccountId(connectedAccountId).build(),
                ProtoOAPayloadType.PROTO_OA_TRADER_RES_VALUE
            )
            val trader = ProtoOATraderRes.parseFrom(traderReply.payload).trader
            val verified = trader.toVerifiedAccount(selectedLive, nowMs())
            if (verified.accountId != connectedAccountId || verified.isLive != selectedLive ||
                !verified.brokerName.equals(selectedBroker, ignoreCase = true)) {
                throw IllegalStateException("BLOCKED: cTrader account identity changed during verification")
            }
            Result.success(verified)
        } catch (e: Exception) {
            close()
            Result.failure(e.safeCTraderFailure())
        }
    }

    suspend fun listAccounts(live: Boolean): Result<List<CTraderAccountOption>> = withContext(Dispatchers.IO) {
        if (!oauth.accessTokenIsFresh()) {
            oauth.refresh().getOrElse { return@withContext Result.failure(IllegalStateException("cTrader authorization is expired; re-authorize the app")) }
        }
        runCatching { listAccountsInternal(live) }.onFailure { close() }
    }

    suspend fun subscribeReadOnlySpot(symbolId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        if (connectedAccountId <= 0L || socket == null) return@withContext Result.failure(IllegalStateException("cTrader account is not freshly verified"))
        runCatching {
            sendAndAwait(
                ProtoOAPayloadType.PROTO_OA_SUBSCRIBE_SPOTS_REQ_VALUE,
                ProtoOASubscribeSpotsReq.newBuilder()
                    .setCtidTraderAccountId(connectedAccountId)
                    .addSymbolId(symbolId)
                    .setSubscribeToSpotTimestamp(true)
                    .build(),
                ProtoOAPayloadType.PROTO_OA_SUBSCRIBE_SPOTS_RES_VALUE
            )
        }.map { Unit }
    }

    fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        socket?.close(1000, "read-only session closed")
        socket?.cancel()
        socket = null
        connectedAccountId = 0L
        _quotes.value = null
    }

    private suspend fun openSocket(live: Boolean) {
        close()
        val opened = CompletableDeferred<Unit>()
        socket = client.newWebSocket(Request.Builder().url(if (live) LIVE_HOST else DEMO_HOST).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrames(bytes.toByteArray())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened.isCompleted) opened.completeExceptionally(IOException("cTrader WebSocket connection failed"))
                pending.values.forEach { it.completeExceptionally(IOException("cTrader WebSocket disconnected")) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                pending.values.forEach { it.completeExceptionally(IOException("cTrader WebSocket closed")) }
            }
        })
        withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
    }

    private suspend fun listAccountsInternal(live: Boolean): List<CTraderAccountOption> {
        openSocket(live)
        sendAndAwait(
            ProtoOAPayloadType.PROTO_OA_APPLICATION_AUTH_REQ_VALUE,
            ProtoOAApplicationAuthReq.newBuilder()
                .setClientId(secrets.getCTraderClientId())
                .setClientSecret(secrets.getCTraderClientSecret())
                .build(),
            ProtoOAPayloadType.PROTO_OA_APPLICATION_AUTH_RES_VALUE
        )
        val reply = sendAndAwait(
            ProtoOAPayloadType.PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_REQ_VALUE,
            ProtoOAGetAccountListByAccessTokenReq.newBuilder().setAccessToken(secrets.getCTraderAccessToken()).build(),
            ProtoOAPayloadType.PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_RES_VALUE
        )
        return ProtoOAGetAccountListByAccessTokenRes.parseFrom(reply.payload).ctidTraderAccountList.map {
            CTraderAccountOption(it.ctidTraderAccountId, it.traderLogin, it.brokerTitleShort, it.isLive)
        }
    }

    private suspend fun sendAndAwait(payloadType: Int, payload: com.google.protobuf.MessageLite, expectedType: Int): ProtoMessage {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ProtoMessage>()
        pending[id] = deferred
        val envelope = ProtoMessage.newBuilder()
            .setPayloadType(payloadType)
            .setPayload(payload.toByteString())
            .setClientMsgId(id)
            .build()
        val bytes = envelope.toByteArray()
        val frame = ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).put(bytes).array()
        if (socket?.send(ByteString.of(*frame)) != true) {
            pending.remove(id)
            throw IOException("cTrader request was not sent")
        }
        val response = try { withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() } } finally { pending.remove(id) }
        if (response.payloadType == ProtoPayloadType.ERROR_RES_VALUE) throw IOException("cTrader returned an error response")
        if (response.payloadType != expectedType) throw IOException("cTrader returned an unexpected response")
        return response
    }

    private fun handleFrames(bytes: ByteArray) {
        var offset = 0
        while (offset + 4 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length <= 0 || length > MAX_FRAME_BYTES || offset + 4 + length > bytes.size) return
            val message = runCatching { ProtoMessage.parseFrom(bytes, offset + 4, length) }.getOrNull() ?: return
            offset += 4 + length
            if (message.payloadType == ProtoPayloadType.HEARTBEAT_EVENT_VALUE) {
                sendHeartbeat()
                continue
            }
            if (message.payloadType == ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE) {
                val spot = runCatching { ProtoOASpotEvent.parseFrom(message.payload) }.getOrNull() ?: continue
                val scale = 100_000.0
                _quotes.value = CTraderQuote(
                    accountId = spot.ctidTraderAccountId,
                    symbolId = spot.symbolId,
                    bid = spot.bid.takeIf { spot.hasBid() }?.div(scale),
                    ask = spot.ask.takeIf { spot.hasAsk() }?.div(scale),
                    providerTimestampMs = spot.timestamp,
                    receivedAtMs = System.currentTimeMillis()
                )
            }
            val id = message.clientMsgId.takeIf { it.isNotBlank() }
            if (id != null) pending[id]?.complete(message)
        }
    }

    private fun sendHeartbeat() {
        val heartbeat = ProtoMessage.newBuilder()
            .setPayloadType(ProtoPayloadType.HEARTBEAT_EVENT_VALUE)
            .setPayload(ProtoHeartbeatEvent.getDefaultInstance().toByteString())
            .build().toByteArray()
        val frame = ByteBuffer.allocate(4 + heartbeat.size).order(ByteOrder.BIG_ENDIAN).putInt(heartbeat.size).put(heartbeat).array()
        socket?.send(ByteString.of(*frame))
    }
}

private fun ProtoOATrader.toVerifiedAccount(isLive: Boolean, nowMs: Long): CTraderVerifiedAccount {
    val digits = moneyDigits
    val divisor = Math.pow(10.0, digits.toDouble())
    return CTraderVerifiedAccount(
        accountId = ctidTraderAccountId,
        traderLogin = traderLogin,
        brokerName = brokerName,
        isLive = isLive,
        balance = balance.toDouble() / divisor,
        leverage = leverageInCents.toDouble() / 100.0,
        receivedAtMs = nowMs
    )
}

private fun Exception.safeCTraderFailure(): Exception = when (this) {
    is IllegalStateException -> this
    else -> IOException("cTrader read-only verification failed")
}
