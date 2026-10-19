package com.example.data.market

data class SymbolInfo(
    val canonicalSymbol: String, // e.g. "EUR/USD"
    val oandaInstrument: String, // e.g. "EUR_USD"
    val pipPrecision: Int, // decimal places
    val pipSize: Double // e.g. 0.0001 or 0.01
)

object SymbolRegistry {
    private val registry = mapOf(
        "EUR/USD" to SymbolInfo("EUR/USD", "EUR_USD", 5, 0.0001),
        "GBP/USD" to SymbolInfo("GBP/USD", "GBP_USD", 5, 0.0001),
        "USD/JPY" to SymbolInfo("USD/JPY", "USD_JPY", 3, 0.01),
        "USD/CHF" to SymbolInfo("USD/CHF", "USD_CHF", 5, 0.0001),
        "AUD/USD" to SymbolInfo("AUD/USD", "AUD_USD", 5, 0.0001),
        "USD/CAD" to SymbolInfo("USD/CAD", "USD_CAD", 5, 0.0001),
        "NZD/USD" to SymbolInfo("NZD/USD", "NZD_USD", 5, 0.0001),
        "EUR/GBP" to SymbolInfo("EUR/GBP", "EUR_GBP", 5, 0.0001),
        "EUR/JPY" to SymbolInfo("EUR/JPY", "EUR_JPY", 3, 0.01),
        "GBP/JPY" to SymbolInfo("GBP/JPY", "GBP_JPY", 3, 0.01),
        "XAU/USD" to SymbolInfo("XAU/USD", "XAU_USD", 2, 0.1),
        "XAG/USD" to SymbolInfo("XAG/USD", "XAG_USD", 3, 0.01),
        "SPX500" to SymbolInfo("SPX500", "SPX500_USD", 2, 1.0),
        "NAS100" to SymbolInfo("NAS100", "NAS100_USD", 2, 1.0),
        "DJ30" to SymbolInfo("DJ30", "US30_USD", 2, 1.0),
        "BTC/USD" to SymbolInfo("BTC/USD", "BTC_USD", 2, 1.0),
        "ETH/USD" to SymbolInfo("ETH/USD", "ETH_USD", 2, 1.0),
        "USOIL" to SymbolInfo("USOIL", "WTICO_USD", 2, 0.01),
        "EUR/AUD" to SymbolInfo("EUR/AUD", "EUR_AUD", 5, 0.0001),
        "GBP/AUD" to SymbolInfo("GBP/AUD", "GBP_AUD", 5, 0.0001),
        "AUD/JPY" to SymbolInfo("AUD/JPY", "AUD_JPY", 3, 0.01),
        "CAD/JPY" to SymbolInfo("CAD/JPY", "CAD_JPY", 3, 0.01),
        "CHF/JPY" to SymbolInfo("CHF/JPY", "CHF_JPY", 3, 0.01),
        "USD/TRY" to SymbolInfo("USD/TRY", "USD_TRY", 5, 0.0001),
        "USD/ZAR" to SymbolInfo("USD/ZAR", "USD_ZAR", 5, 0.0001),
        "USD/MXN" to SymbolInfo("USD/MXN", "USD_MXN", 5, 0.0001),
        "USD/SGD" to SymbolInfo("USD/SGD", "USD_SGD", 5, 0.0001),
        "EUR/TRY" to SymbolInfo("EUR/TRY", "EUR_TRY", 5, 0.0001),
        "GBP/NZD" to SymbolInfo("GBP/NZD", "GBP_NZD", 5, 0.0001),
        "XPT/USD" to SymbolInfo("XPT/USD", "XPT_USD", 2, 0.1),
        "XPD/USD" to SymbolInfo("XPD/USD", "XPD_USD", 2, 0.1),
        "FTSE100" to SymbolInfo("FTSE100", "UK100_GBP", 2, 1.0),
        "DAX40" to SymbolInfo("DAX40", "DE30_EUR", 2, 1.0),
        "BNB/USD" to SymbolInfo("BNB/USD", "", 2, 0.1),
        "SOL/USD" to SymbolInfo("SOL/USD", "", 2, 0.01),
        "XRP/USD" to SymbolInfo("XRP/USD", "", 4, 0.0001),
        "UKOIL" to SymbolInfo("UKOIL", "BCO_USD", 2, 0.01),
        "NATGAS" to SymbolInfo("NATGAS", "NATGAS_USD", 3, 0.001),
        "WHEAT" to SymbolInfo("WHEAT", "WHEAT_USD", 2, 0.01),
        "CORN" to SymbolInfo("CORN", "CORN_USD", 2, 0.01),
        "SOYBEANS" to SymbolInfo("SOYBEANS", "SOYBN_USD", 2, 0.01),
        "COFFEE" to SymbolInfo("COFFEE", "COFFEE_USD", 2, 0.01)
    )

    private val backgroundSymbols = listOf(
        "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD",
        "USD/CAD", "NZD/USD", "EUR/GBP", "EUR/JPY", "GBP/JPY",
        "XAU/USD", "XAG/USD", "SPX500", "NAS100", "DJ30",
        "BTC/USD", "ETH/USD", "BNB/USD", "SOL/USD", "XRP/USD"
    )

    fun getBackgroundSymbols(): List<String> = backgroundSymbols

    fun get(canonical: String): SymbolInfo {
        return registry[canonical] ?: registry.entries.firstOrNull { it.key.equals(canonical, true) }?.value
            ?: SymbolInfo(canonical, canonical.replace("/", "_"), 5, 0.0001)
    }

    fun getAllCanonical(): List<String> = registry.keys.toList()

    fun mapOandaGranularity(timeframe: String): String {
        return when (timeframe.uppercase()) {
            "M1", "1" -> "M1"
            "M5", "5" -> "M5"
            "M15", "15" -> "M15"
            "M30", "30" -> "M30"
            "H1", "60" -> "H1"
            "H4", "240" -> "H4"
            "D1", "D" -> "D"
            "W1", "W" -> "W"
            "MN", "M" -> "M"
            else -> "H1"
        }
    }


}
