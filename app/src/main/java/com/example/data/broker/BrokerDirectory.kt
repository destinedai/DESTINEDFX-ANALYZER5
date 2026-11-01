package com.example.data.broker

/**
 * Broker names are discovery aids only. A name in this list does not mean that
 * the broker has been connected, that a server is configured, or that demo/live
 * execution has been verified. Server names are intentionally not hard-coded:
 * they vary by entity, region, account type and broker migration.
 */
data class BrokerCompany(
    val name: String,
    val servers: List<String> = emptyList()
)

object BrokerDirectory {
    /** Listed broker companies; the managed MT5 terminal remains the authority. */
    val allBrokers: List<BrokerCompany> by lazy {
        listOf(
            "Exness Global", "IC Markets", "FBS Markets Inc.",
            "XM Trading / XM Global", "Pepperstone", "FXTM (ForexTime)",
            "AvaTrade", "IG Markets", "OctaFX Trading", "RoboForex Ltd",
            "Vantage International", "FP Markets", "Alpari International",
            "Tickmill Ltd", "FxPro Financial", "HYCM (Henyep)",
            "LiteFinance Global", "BlackBull Markets", "Admirals (Admiral Markets)",
            "AxiTrader / Axi", "Swissquote Bank", "HF Markets (HFM)",
            "Libertex Group", "MultiBank Group", "Forex.com", "CMC Markets",
            "Coinexx", "Hankotrade", "KOT4X", "Hugo's Way",
            "FTMO / Prop Firms", "MetaQuotes Software", "JustMarkets",
            "Hantec Markets", "Custom Server"
        ).map { BrokerCompany(it) }
    }

    fun searchBrokers(query: String): List<BrokerCompany> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return allBrokers
        return allBrokers.filter { it.name.lowercase().contains(q) }
    }
}
