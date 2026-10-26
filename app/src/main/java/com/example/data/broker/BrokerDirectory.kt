package com.example.data.broker

data class BrokerCompany(
    val name: String,
    val servers: List<String>
)

object BrokerDirectory {
    val allBrokers: List<BrokerCompany> by lazy {
        listOf(
            BrokerCompany("Exness Global", listOf(
                "Exness-MT5Real", "Exness-MT5Real2", "Exness-MT5Real3", "Exness-MT5Real4", "Exness-MT5Real5",
                "Exness-MT5Real6", "Exness-MT5Real7", "Exness-MT5Real8", "Exness-MT5Real9", "Exness-MT5Real10",
                "Exness-MT5Real11", "Exness-MT5Real12", "Exness-MT5Real14", "Exness-MT5Real15", "Exness-MT5Trial", "Exness-MT5Trial2"
            )),
            BrokerCompany("IC Markets", listOf(
                "ICMarketsSC-Live01", "ICMarketsSC-Live02", "ICMarketsSC-Live03", "ICMarketsSC-Live04", "ICMarketsSC-Live05",
                "ICMarketsSC-Live06", "ICMarketsSC-Live07", "ICMarketsSC-Live08", "ICMarketsSC-Live10", "ICMarketsSC-Live12",
                "ICMarketsSC-Demo01", "ICMarketsSC-Demo02", "ICMarketsEU-Live01", "ICMarketsEU-Demo"
            )),
            BrokerCompany("FBS Markets Inc.", listOf(
                "FBS-Real", "FBS-Real-2", "FBS-Real-3", "FBS-Real-4", "FBS-Demo"
            )),
            BrokerCompany("XM Trading / XM Global", listOf(
                "XMGlobal-MT5", "XMGlobal-MT5 2", "XMGlobal-MT5 3", "XMGlobal-MT5 4", "XMGlobal-MT5 5",
                "XMGlobal-Demo", "XMGlobal-Demo 2"
            )),
            BrokerCompany("Pepperstone", listOf(
                "Pepperstone-MT5-Live01", "Pepperstone-MT5-Live02", "Pepperstone-MT5-Live03",
                "Pepperstone-MT5-Demo01", "Pepperstone-MT5-Demo02"
            )),
            BrokerCompany("Deriv (SVG) LLC", listOf(
                "Deriv-Server", "Deriv-Server-02", "Deriv-Server-03", "Deriv-Demo"
            )),
            BrokerCompany("FXTM (ForexTime)", listOf(
                "FXTM-ECN-Real", "FXTM-Standard-Real", "FXTM-Cent-Real", "FXTM-ECN-Demo"
            )),
            BrokerCompany("AvaTrade", listOf(
                "Ava-Real1", "Ava-Real2", "Ava-Real3", "Ava-Real4", "Ava-Demo"
            )),
            BrokerCompany("OANDA Global", listOf(
                "OANDA-Live-1", "OANDA-Live-2", "OANDA-Live-3", "OANDA-Demo-1", "OANDA-Demo-2"
            )),
            BrokerCompany("IG Markets", listOf(
                "IG-Live", "IG-Live 2", "IG-Demo"
            )),
            BrokerCompany("OctaFX Trading", listOf(
                "OctaFX-Real", "OctaFX-Real2", "OctaFX-Real3", "OctaFX-Demo"
            )),
            BrokerCompany("RoboForex Ltd", listOf(
                "RoboForex-Pro", "RoboForex-ProCent", "RoboForex-ECN", "RoboForex-DemoPro"
            )),
            BrokerCompany("Vantage International", listOf(
                "VantageFX-Live01", "VantageFX-Live02", "VantageFX-Demo"
            )),
            BrokerCompany("FP Markets", listOf(
                "FPMarkets-Live1", "FPMarkets-Live2", "FPMarkets-Demo"
            )),
            BrokerCompany("Alpari International", listOf(
                "Alpari-MT5-Live", "Alpari-MT5-Demo"
            )),
            BrokerCompany("Tickmill Ltd", listOf(
                "Tickmill-Live", "Tickmill-Live02", "Tickmill-Demo"
            )),
            BrokerCompany("FxPro Financial", listOf(
                "FxPro-MT5", "FxPro-MT5-Real02", "FxPro-MT5-Demo"
            )),
            BrokerCompany("HYCM (Henyep)", listOf(
                "HYCM-Live", "HYCM-Demo"
            )),
            BrokerCompany("LiteFinance Global", listOf(
                "LiteFinance-MT5-Live", "LiteFinance-MT5-Demo"
            )),
            BrokerCompany("BlackBull Markets", listOf(
                "BlackBullMarkets-Live", "BlackBullMarkets-Demo"
            )),
            BrokerCompany("Admirals (Admiral Markets)", listOf(
                "AdmiralMarkets-Live1", "AdmiralMarkets-Live2", "AdmiralMarkets-Demo"
            )),
            BrokerCompany("AxiTrader / Axi", listOf(
                "Axi-Live01", "Axi-Live02", "Axi-Demo"
            )),
            BrokerCompany("Swissquote Bank", listOf(
                "Swissquote-Live", "Swissquote-Demo"
            )),
            BrokerCompany("HF Markets (HFM)", listOf(
                "HFMarketsSV-Live", "HFMarketsSA-Live", "HFMarkets-Demo"
            )),
            BrokerCompany("Libertex Group", listOf(
                "Libertex-Live", "Libertex-Demo"
            )),
            BrokerCompany("MultiBank Group", listOf(
                "MultiBank-Live", "MultiBank-Demo"
            )),
            BrokerCompany("Forex.com", listOf(
                "Forex.com-Live", "Forex.com-Demo"
            )),
            BrokerCompany("CMC Markets", listOf(
                "CMCMarkets-Live", "CMCMarkets-Demo"
            )),
            BrokerCompany("Coinexx", listOf(
                "Coinexx-Live", "Coinexx-Demo"
            )),
            BrokerCompany("Hankotrade", listOf(
                "Hankotrade-Live", "Hankotrade-Demo"
            )),
            BrokerCompany("KOT4X", listOf(
                "KOT4X-Live", "KOT4X-Demo"
            )),
            BrokerCompany("Hugo's Way", listOf(
                "HugosWay-Real", "HugosWay-Demo"
            )),
            BrokerCompany("FTMO / Prop Firms", listOf(
                "FTMO-Server", "FTMO-Demo", "FundingPips-Server", "FundedNext-Server", "MFF-Server"
            )),
            BrokerCompany("MetaQuotes Software", listOf(
                "MetaQuotes-Demo", "MetaQuotes-Server"
            )),
            BrokerCompany("JustMarkets", listOf(
                "JustMarkets-Live", "JustMarkets-Live2", "JustMarkets-Demo"
            )),
            BrokerCompany("Hantec Markets", listOf(
                "HantecMarkets-Live", "Hantec-Demo"
            )),
            BrokerCompany("ForexTime (FXTM)", listOf(
                "FXTM-MT5-Live", "FXTM-MT5-Demo"
            )),
            BrokerCompany("Custom Server", listOf(
                "Enter manually..."
            ))
        )
    }

    fun searchBrokers(query: String): List<BrokerCompany> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return allBrokers
        return allBrokers.filter { broker ->
            broker.name.lowercase().contains(q) ||
            broker.servers.any { server -> server.lowercase().contains(q) }
        }
    }
}
