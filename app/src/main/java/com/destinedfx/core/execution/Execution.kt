package com.destinedfx.core.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ExecStatus { PENDING, ACCEPTED, REJECTED, TIMEOUT, DUPLICATE_BLOCKED, DISCONNECTED }
data class ExecResult(val requestId: String, val status: ExecStatus, val orderId: String? = null, val positionId: String? = null, val error: String? = null)

/** Correlates broker replies to requests by requestId. Replaces the old single global "pendingTradeCallback". */
class PendingRequests {
    private val map = ConcurrentHashMap<String, CompletableDeferred<ExecResult>>()
    fun newId(): String = "REQ-" + UUID.randomUUID().toString()
    fun register(id: String): CompletableDeferred<ExecResult> = CompletableDeferred<ExecResult>().also { check(map.putIfAbsent(id, it) == null) { "duplicate requestId" } }
    /** Returns false if the id is unknown (late/foreign reply) - such replies are ignored, never applied to another order. */
    fun complete(id: String, r: ExecResult): Boolean = map.remove(id)?.complete(r) ?: false
    fun failAll(status: ExecStatus, why: String) { map.keys.toList().forEach { id -> map.remove(id)?.complete(ExecResult(id, status, error = why)) } }
    val size: Int get() = map.size
    suspend fun await(id: String, d: CompletableDeferred<ExecResult>, timeoutMs: Long): ExecResult {
        val r = withTimeoutOrNull(timeoutMs) { d.await() }; map.remove(id)
        return r ?: ExecResult(id, ExecStatus.TIMEOUT, error = "No broker confirmation in ${timeoutMs}ms - state UNKNOWN, reconcile before retrying")
    }
}

/** Blocks the same logical order (signal+symbol+direction) being sent twice within [windowMs]. */
class DuplicateGuard(private val windowMs: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val seen = ConcurrentHashMap<String, Long>()
    fun key(signalId: String, symbol: String, isLong: Boolean) = "$signalId|$symbol|${if (isLong) "L" else "S"}"
    /** Atomically claims the key; false = duplicate. */
    fun tryClaim(key: String): Boolean { val now = clock(); seen.entries.removeIf { now - it.value > windowMs }; return seen.putIfAbsent(key, now) == null }
    fun release(key: String) { seen.remove(key) }
    fun isDuplicate(key: String) = seen[key]?.let { clock() - it <= windowMs } ?: false
}

data class BrokerPosition(val id: String, val symbol: String, val isLong: Boolean, val volume: Double, val sl: Double?, val tp: Double?)
data class BrokerOrder(val id: String, val symbol: String)
data class BrokerAccount(val balance: Double, val equity: Double, val margin: Double, val freeMargin: Double)
data class BrokerSnapshot(val account: BrokerAccount, val positions: List<BrokerPosition>, val pendingOrders: List<BrokerOrder>)
data class LocalState(val positions: Map<String, BrokerPosition>, val pendingOrders: Set<String>)
data class Reconciliation(val missingLocally: List<BrokerPosition>, val closedAtBroker: List<String>, val changed: List<BrokerPosition>, val staleOrders: List<String>, val newOrders: List<String>) {
    val hasDiscrepancy get() = missingLocally.isNotEmpty() || closedAtBroker.isNotEmpty() || changed.isNotEmpty() || staleOrders.isNotEmpty() || newOrders.isNotEmpty()
}

/** Broker is the source of truth: the diff is always applied broker -> local. */
object Reconciler {
    fun diff(local: LocalState, broker: BrokerSnapshot): Reconciliation {
        val bp = broker.positions.associateBy { it.id }
        return Reconciliation(
            missingLocally = broker.positions.filter { it.id !in local.positions },
            closedAtBroker = local.positions.keys.filter { it !in bp },
            changed = broker.positions.filter { p -> local.positions[p.id]?.let { it != p } == true },
            staleOrders = local.pendingOrders.filter { id -> broker.pendingOrders.none { it.id == id } },
            newOrders = broker.pendingOrders.map { it.id }.filter { it !in local.pendingOrders })
    }
    fun apply(r: Reconciliation, local: LocalState, broker: BrokerSnapshot): LocalState =
        LocalState(broker.positions.associateBy { it.id }, broker.pendingOrders.map { it.id }.toSet())
}

enum class ReconnectStep { DISCONNECTED, BACKOFF, RECONNECT, AUTHENTICATE, SYNC_ACCOUNT, SYNC_POSITIONS, SYNC_ORDERS, RESUMED }

interface BrokerLink {
    suspend fun connect()
    suspend fun authenticate()
    suspend fun fetchAccount(): BrokerAccount
    suspend fun fetchPositions(): List<BrokerPosition>
    suspend fun fetchOrders(): List<BrokerOrder>
}

/** Disconnected -> backoff -> reconnect -> authenticate -> sync account/positions/orders -> resume. Trading stays disabled until RESUMED. */
class ReconnectSequence(private val link: BrokerLink, private val backoff: com.destinedfx.core.marketdata.ExponentialBackoff,
                        private val sleep: suspend (Long) -> Unit, private val onStep: (ReconnectStep) -> Unit = {}, private val maxAttempts: Int = 10) {
    suspend fun run(): BrokerSnapshot? {
        onStep(ReconnectStep.DISCONNECTED)
        repeat(maxAttempts) {
            onStep(ReconnectStep.BACKOFF); sleep(backoff.nextDelayMs())
            try {
                onStep(ReconnectStep.RECONNECT); link.connect()
                onStep(ReconnectStep.AUTHENTICATE); link.authenticate()
                onStep(ReconnectStep.SYNC_ACCOUNT); val acc = link.fetchAccount()
                onStep(ReconnectStep.SYNC_POSITIONS); val pos = link.fetchPositions()
                onStep(ReconnectStep.SYNC_ORDERS); val ord = link.fetchOrders()
                backoff.reset(); onStep(ReconnectStep.RESUMED)
                return BrokerSnapshot(acc, pos, ord)
            } catch (e: java.io.IOException) { /* retry */ } catch (e: IllegalStateException) { /* retry */ }
        }
        return null
    }
}
