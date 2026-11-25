package com.example.data.broker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

enum class BrokerEndpointStatus {
    DNS_TCP_VERIFIED,
    MISMATCH,
    NOT_PUBLICLY_PUBLISHED,
    UNREACHABLE
}

data class BrokerEndpointVerification(
    val status: BrokerEndpointStatus,
    val host: String,
    val port: Int?,
    val resolvedIps: List<String>,
    val sourceUrl: String,
    val detail: String
)

data class OfficialMt5Endpoint(
    val broker: String,
    val serverLabel: String,
    val mode: String,
    val host: String,
    val port: Int,
    val sourceUrl: String
)

object BrokerEndpointVerifier {
    const val HFM_SOURCE = "https://www.hfm.com/int/en/platforms/mt5-how-to-connect"
    const val FBS_SOURCE = "https://fbs.com/fbs-academy/trading-tutorials/how-to-video-guides/three-steps-to-start-trading-on-your-desktop"
    const val EXNESS_SOURCE = "https://get.exness.help/hc/en-us/articles/360012578619-Trading-account-login-and-server-details"
    const val DERIV_SOURCE = "https://deriv.com/help-centre-question/how-to-log-in-to-deriv-mt5-account"

    private val hfmEndpoints = listOf(
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live1", "LIVE", "mt5-global.dcglobalfarm.com", 1951, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Demo", "DEMO", "mt5-europe1.dcglobalfarm.com", 1950, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live3", "LIVE", "mt5-global3.dcglobalfarm.com", 20301, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Demo3", "DEMO", "mt5-global3.dcglobalfarm.com", 40305, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live4", "LIVE", "mt5-global4.dcglobalfarm.com", 20401, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Demo4", "DEMO", "beeksfx-sgp-ga-1.dcglobalfarm.com", 40401, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live5", "LIVE", "mt5-global5.dcglobalfarm.com", 20501, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live7", "LIVE", "mt5-global7.dcglobalfarm.com", 20701, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live8", "LIVE", "mt5-global8.dcglobalfarm.com", 20801, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live9", "LIVE", "mt5-global9.dcglobalfarm.com", 20901, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live10", "LIVE", "mt5-global10.dcglobalfarm.com", 21001, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live11", "LIVE", "mt5-global11.dcglobalfarm.com", 21101, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live12", "LIVE", "mt5-ga-6.dcglobalfarm.com", 21201, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live13", "LIVE", "mt5-ga-5.dcglobalfarm.com", 21301, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live14", "LIVE", "mt5-ga-5.dcglobalfarm.com", 21401, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live15", "LIVE", "mt5-ga-8.dcglobalfarm.com", 21501, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live16", "LIVE", "mt5-ga-7.dcglobalfarm.com", 21601, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live17", "LIVE", "mt5-ga-8.dcglobalfarm.com", 21701, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live18", "LIVE", "mt5-ga-9.dcglobalfarm.com", 21801, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live19", "LIVE", "mt5-ga-9.dcglobalfarm.com", 21901, HFM_SOURCE),
        OfficialMt5Endpoint("HFM", "HFMarketsGlobal-Live20", "LIVE", "mt5-ga-10.dcglobalfarm.com", 22001, HFM_SOURCE)
    )

    private fun normalizeServerLabel(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").removeSuffix(" Server").trim().lowercase()

    fun officialEndpointFor(broker: String, serverLabel: String): OfficialMt5Endpoint? {
        if (!broker.equals("HFM", ignoreCase = true)) return null
        val normalized = normalizeServerLabel(serverLabel)
        return hfmEndpoints.firstOrNull { normalizeServerLabel(it.serverLabel) == normalized }
    }

    fun officialServerReference(broker: String, mode: String): String = when (broker.trim().uppercase()) {
        "HFM" -> if (mode.equals("LIVE", true)) {
            "Official labels include HFMarketsGlobal-Live1/3/4/5 and Live7-Live20; their published dcglobalfarm.com host/ports vary by server."
        } else {
            "HFMarketsGlobal-Demo/ Demo3/Demo4 use separate published dcglobalfarm.com hosts and ports."
        }
        "FBS" -> "FBS-Real is an official example; use the exact account server from FBS Trader Area. No universal DNS host:port map is published."
        "EXNESS" -> "Use the fixed server shown on this trading account card in Exness Personal Area; it is account-specific."
        "DERIV" -> "Use the company and server shown for this account in Deriv Trader's Hub; it varies by Deriv entity."
        else -> "No official MT5 endpoint reference is configured for this broker."
    }

    suspend fun verify(broker: String, mode: String, serverLabel: String): BrokerEndpointVerification =
        withContext(Dispatchers.IO) {
            val normalizedBroker = broker.trim().uppercase()
            val normalizedMode = mode.trim().uppercase()
            val server = serverLabel.trim()
            if (server.isBlank()) {
                return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.MISMATCH, "", null, emptyList(), sourceFor(normalizedBroker),
                    "Enter the exact MT5 server from the broker account before verification."
                )
            }
            if (normalizedBroker != "HFM") {
                val detail = when (normalizedBroker) {
                    "FBS" -> "FBS documents an account-issued server name (FBS-Real is an official example); its public help does not publish a DNS host:port map. No guessed DNS lookup was attempted."
                    "EXNESS" -> "Exness says the server is shown on each account card in Personal Area. The account-specific MT5 server label is not a published universal DNS hostname. No guessed DNS lookup was attempted."
                    "DERIV" -> "Deriv says to use the server and company shown in Trader's Hub. It varies by account entity. No guessed DNS lookup was attempted."
                    else -> "No official public MT5 hostname mapping is available for this broker."
                }
                return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.NOT_PUBLICLY_PUBLISHED, "", null, emptyList(), sourceFor(normalizedBroker), detail
                )
            }

            val endpoint = officialEndpointFor(normalizedBroker, server)
                ?: return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.MISMATCH, server, null, emptyList(), HFM_SOURCE,
                    "This server label is not in HFM's published MT5 endpoint list. No DNS request or broker login was attempted."
                )
            if (endpoint.mode != normalizedMode) {
                return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.MISMATCH, endpoint.host, endpoint.port, emptyList(), endpoint.sourceUrl,
                    "The official server label is for ${endpoint.mode}, but this app profile is $normalizedMode. No connection was attempted."
                )
            }

            val addresses = try {
                InetAddress.getAllByName(endpoint.host).distinctBy { it.hostAddress }
            } catch (_: Exception) {
                emptyList()
            }
            if (addresses.isEmpty()) {
                return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.UNREACHABLE, endpoint.host, endpoint.port, emptyList(), endpoint.sourceUrl,
                    "The official hostname did not resolve from this Android phone. No broker login was attempted."
                )
            }
            if (addresses.any { isNonPublicAddress(it) }) {
                return@withContext BrokerEndpointVerification(
                    BrokerEndpointStatus.MISMATCH, endpoint.host, endpoint.port, addresses.map { it.hostAddress }, endpoint.sourceUrl,
                    "DNS returned a private, loopback or link-local IP for an official public broker endpoint. Connection was blocked."
                )
            }
            val reachable = mutableListOf<String>()
            addresses.forEach { address ->
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, endpoint.port), 2_500)
                        reachable += address.hostAddress
                    }
                }
            }
            if (reachable.isEmpty()) {
                BrokerEndpointVerification(
                    BrokerEndpointStatus.UNREACHABLE, endpoint.host, endpoint.port, addresses.map { it.hostAddress }, endpoint.sourceUrl,
                    "The hostname matched HFM's official list and DNS resolved, but no TCP connection to the published MT5 port succeeded from this phone. This does not prove the endpoint is fake."
                )
            } else {
                BrokerEndpointVerification(
                    BrokerEndpointStatus.DNS_TCP_VERIFIED, endpoint.host, endpoint.port, reachable.distinct(), endpoint.sourceUrl,
                    "The exact server label, published HFM hostname, public DNS IP and TCP port matched HFM's official page. This is a phone-side reachability check, not MT5 account authentication."
                )
            }
        }

    private fun isNonPublicAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress

    private fun sourceFor(broker: String): String = when (broker) {
        "HFM" -> HFM_SOURCE
        "FBS" -> FBS_SOURCE
        "EXNESS" -> EXNESS_SOURCE
        "DERIV" -> DERIV_SOURCE
        else -> ""
    }
}
