package com.example.data

import com.example.data.trade.MT5RemoteBridgeClient
import com.example.security.SecretsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends phone-configured Telegram alerts only when steady notifications are enabled.
 * Account and trade event data is read from fresh, broker-reported MT5 Bridge snapshots.
 */
object TelegramSteadyMessages {
    private data class ProfileState(
        var bridgeReachable: Boolean? = null,
        var zeroBalance: Boolean = false,
        var previousPositions: Map<String, JSONObject>? = null,
        var previousDealTickets: Set<String>? = null,
        val pendingClosedPositionIds: MutableSet<String> = mutableSetOf(),
        val notifiedClosedPositionIds: MutableSet<String> = mutableSetOf(),
    )

    private val states = ConcurrentHashMap<String, ProfileState>()
    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    /** Fire-and-forget by design: notification failures never interrupt broker or trading flows. */
    fun send(secrets: SecretsManager, message: String) {
        if (message.isBlank() || !secrets.isSecureStorageAvailable ||
            !secrets.getTelegramNotificationsEnabled()
        ) return

        val token = secrets.getTelegramToken().trim()
        val chatId = secrets.getTelegramChatId().trim()
        if (token.isBlank() || chatId.isBlank()) return

        senderScope.launch {
            NotificationService(httpClient).sendTelegramMessageDetailed(token, chatId, message.take(4096))
        }
    }

    /**
     * Tracks changes only after complete, fresh, identity-verified MT5 snapshots. The first
     * complete snapshot is a baseline so old positions/deals do not cause retrospective alerts.
     */
    fun observeSnapshot(
        secrets: SecretsManager,
        profileKey: String,
        selectedMode: String,
        snapshot: MT5RemoteBridgeClient.Snapshot?,
    ) {
        if (profileKey.isBlank()) return
        val stateKey = "${profileKey.uppercase(Locale.US)}|${selectedMode.uppercase(Locale.US)}"
        val state = states.computeIfAbsent(stateKey) { ProfileState() }
        val bridgeReachable = snapshot?.let {
            it.terminalOnline && it.heartbeatFresh && it.terminalConnected
        } == true
        val healthy = snapshot?.let {
            it.connected && bridgeReachable && it.accountVerified && it.brokerIdentityVerified
        } == true
        val zeroBalance = snapshot?.connectionWarning == "Balance unavailable - DISCONNECTED"

        val events = synchronized(state) {
            val emitted = mutableListOf<String>()
            if (zeroBalance && !state.zeroBalance) {
                emitted += "Balance unavailable - DISCONNECTED"
            } else if (state.bridgeReachable == true && !bridgeReachable && !zeroBalance) {
                emitted += "Bridge DISCONNECTED - Chart frozen"
            }

            if (snapshot != null && healthy && snapshot.snapshotsValid) {
                val positions = snapshot.positions.orEmpty().mapNotNull { row ->
                    val positionId = row.optString("position_id").takeIf(::isIdentifier)
                        ?: row.optString("ticket").takeIf(::isIdentifier)
                        ?: return@mapNotNull null
                    positionId to row
                }.toMap()
                val deals = snapshot.closedDeals.orEmpty()
                val dealTickets = deals.mapNotNull { row ->
                    row.optString("ticket").takeIf(::isIdentifier)
                }.toSet()

                val oldPositions = state.previousPositions
                val oldDealTickets = state.previousDealTickets
                val newDeals = if (oldDealTickets == null) emptyList() else deals.filter { deal ->
                    val ticket = deal.optString("ticket")
                    isIdentifier(ticket) && ticket !in oldDealTickets
                }
                newDeals.forEach { deal ->
                    deal.optString("position_id").takeIf(::isIdentifier)?.let {
                        state.pendingClosedPositionIds += it
                    }
                }

                // A position is closed only once absent from a complete fresh snapshot and backed
                // by its broker-reported exit deal(s). Partial exits remain pending until flat.
                val closedCandidates = (oldPositions?.keys.orEmpty() - positions.keys) +
                    state.pendingClosedPositionIds.filter { it !in positions.keys }
                closedCandidates.forEach { positionId ->
                    if (positionId in state.notifiedClosedPositionIds) return@forEach
                    val matchingDeals = deals.filter { it.optString("position_id") == positionId }
                    if (matchingDeals.isEmpty()) return@forEach
                    val profitValues = matchingDeals.mapNotNull { finiteNumber(it, "profit") }
                    if (profitValues.isEmpty()) return@forEach
                    val totalProfit = profitValues.sum()
                    if (!totalProfit.isFinite()) return@forEach
                    val balance = snapshot.balance?.takeIf { it.isFinite() }
                    val mode = snapshot.actualAccountMode.ifBlank { selectedMode }.uppercase(Locale.US)
                    emitted += "$mode Order Closed - Profit: ${format(totalProfit, 2)}, New Balance: ${balance?.let { format(it, 2) } ?: "UNAVAILABLE"}"
                    state.notifiedClosedPositionIds += positionId
                    state.pendingClosedPositionIds.remove(positionId)
                }

                // New positions are reported only when a prior complete snapshot exists.
                if (oldPositions != null) {
                    positions.filterKeys { it !in oldPositions }.values.forEach { row ->
                        val symbol = row.optString("symbol").trim()
                        val direction = row.optString("direction").uppercase(Locale.US)
                        val volume = finiteNumber(row, "volume")
                        val price = finiteNumber(row, "open_price")
                        if (symbol.isBlank() || direction !in setOf("BUY", "SELL") ||
                            volume == null || volume <= 0.0 || price == null || price <= 0.0
                        ) return@forEach
                        val quoteDigits = snapshot.brokerQuotes
                            ?.firstOrNull { it.symbol == symbol }
                            ?.digits
                            ?.coerceIn(0, 10)
                        val priceText = quoteDigits?.let { format(price, it) } ?: formatCompactPrice(price)
                        val mode = snapshot.actualAccountMode.ifBlank { selectedMode }.uppercase(Locale.US)
                        emitted += "$mode $direction ${formatVolume(volume)} $symbol @ $priceText"
                    }
                }

                state.previousPositions = positions
                state.previousDealTickets = dealTickets
            }

            state.bridgeReachable = bridgeReachable
            state.zeroBalance = zeroBalance
            emitted
        }

        events.forEach { send(secrets, it) }
    }

    private fun isIdentifier(value: String): Boolean =
        value.isNotBlank() && value != "null" && value.all { it.isDigit() }

    private fun finiteNumber(row: JSONObject, key: String): Double? =
        row.optDouble(key, Double.NaN).takeIf { it.isFinite() }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun formatCompactPrice(value: Double): String =
        String.format(Locale.US, "%.10f", value).trimEnd('0').trimEnd('.')

    private fun formatVolume(value: Double): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}
