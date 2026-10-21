package com.destinedfx.core.broker

/**
 * Authoritative symbol resolver for Deriv.
 * Every supported instrument has an explicit canonical mapping.
 * Prohibits any default fallback like "R_100".
 * Returns null if the instrument is unsupported.
 */
object DerivInstrumentResolver {

    private val MAPPING: Map<String, String> = mapOf(
        // Forex Majors & Minors
        "EURUSD" to "frxEURUSD",
        "GBPUSD" to "frxGBPUSD",
        "USDJPY" to "frxUSDJPY",
        "USDCHF" to "frxUSDCHF",
        "AUDUSD" to "frxAUDUSD",
        "USDCAD" to "frxUSDCAD",
        "NZDUSD" to "frxNZDUSD",
        "EURGBP" to "frxEURGBP",
        "EURJPY" to "frxEURJPY",
        "GBPJPY" to "frxGBPJPY",
        "AUDJPY" to "frxAUDJPY",
        "EURAUD" to "frxEURAUD",
        "EURCHF" to "frxEURCHF",
        "GBPCHF" to "frxGBPCHF",

        // Metals & Commodities
        "XAUUSD" to "frxXAUUSD",
        "XAGUSD" to "frxXAGUSD",
        "GOLD" to "frxXAUUSD",
        "SILVER" to "frxXAGUSD",
        "USOIL" to "frxOILUSD",

        // Cryptocurrencies
        "BTCUSD" to "cryBTCUSD",
        "ETHUSD" to "cryETHUSD",

        // Synthetics / Deriv Volatility Indices
        "R_10" to "R_10",
        "R_25" to "R_25",
        "R_50" to "R_50",
        "R_75" to "R_75",
        "R_100" to "R_100",
        "1HZ10V" to "1HZ10V",
        "1HZ25V" to "1HZ25V",
        "1HZ50V" to "1HZ50V",
        "1HZ75V" to "1HZ75V",
        "1HZ100V" to "1HZ100V",
        "BOOM500" to "BOOM500",
        "BOOM1000" to "BOOM1000",
        "CRASH500" to "CRASH500",
        "CRASH1000" to "CRASH1000",
        "STEP" to "stpRNG"
    )

    private val dynamicActiveSymbols = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dynamicAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerActiveSymbols(symbols: Collection<String>) {
        dynamicActiveSymbols.addAll(symbols.map { it.trim() })
    }

    /**
     * Register a canonical UI symbol against the exact broker code returned by Deriv active_symbols.
     * The discovered code is used for feed requests; it is not treated as proof the instrument can
     * be traded through a different broker.
     */
    fun registerAlias(canonicalSymbol: String, derivSymbol: String) {
        val key = canonicalSymbol.trim().uppercase()
            .replace("/", "")
            .replace(" ", "")
            .replace("_", "")
        val value = derivSymbol.trim()
        if (key.isNotBlank() && value.isNotBlank()) dynamicAliases[key] = value
    }

    fun getKnownSymbols(): Set<String> = MAPPING.keys + dynamicActiveSymbols

    fun resolve(symbol: String): String? {
        val clean = symbol.trim().uppercase().replace("/", "").replace(" ", "").replace("_", "")
        dynamicAliases[clean]?.let { return it }
        for ((k, v) in MAPPING) {
            val normK = k.uppercase().replace("/", "").replace(" ", "").replace("_", "")
            if (normK == clean) return v
        }
        if (MAPPING.values.contains(symbol.trim())) {
            return symbol.trim()
        }
        val dynamicMatch = dynamicActiveSymbols.firstOrNull { 
            it.equals(symbol.trim(), ignoreCase = true) || 
            it.replace("/", "").replace("_", "").equals(clean, ignoreCase = true)
        }
        if (dynamicMatch != null) return dynamicMatch
        return null
    }

    fun isSupported(symbol: String): Boolean = resolve(symbol) != null
}
