package com.destinedfx.core.instrument

class BrokerSymbolResolver(
    private val brokerSymbols: List<String>,
    private val overrides: Map<String, String> = emptyMap()
) {
    fun resolve(appSymbol: String): String? {
        val clean = appSymbol.uppercase().replace("/", "")
        
        // 3. User override map saved in Settings
        if (overrides.containsKey(appSymbol)) return overrides[appSymbol]
        if (overrides.containsKey(clean)) return overrides[clean]

        // 1. Exact match of the symbol without "/"
        if (brokerSymbols.contains(clean)) return clean
        val exactMatch = brokerSymbols.firstOrNull { it.equals(clean, ignoreCase = true) }
        if (exactMatch != null) return exactMatch

        // 2. Match of that string plus a common suffix/prefix
        val candidate = brokerSymbols.firstOrNull { bs ->
            val ubs = bs.uppercase()
            ubs.startsWith(clean) || ubs.endsWith(clean) || ubs.contains(clean)
        }
        if (candidate != null) return candidate

        return null
    }
}
