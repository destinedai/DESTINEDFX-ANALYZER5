package com.example.data.market

object BinanceSymbols {
    fun toBinance(symbol: String): String? {
        return when (symbol.uppercase().replace("/", "")) {
            "BTCUSD", "BTCUSDT" -> "BTCUSDT"
            "ETHUSD", "ETHUSDT" -> "ETHUSDT"
            "BNBUSD", "BNBUSDT" -> "BNBUSDT"
            "SOLUSD", "SOLUSDT" -> "SOLUSDT"
            "XRPUSD", "XRPUSDT" -> "XRPUSDT"
            "LTCUSD", "LTCUSDT" -> "LTCUSDT"
            "ADAUSD", "ADAUSDT" -> "ADAUSDT"
            "DOGEUSD", "DOGEUSDT" -> "DOGEUSDT"
            else -> null
        }
    }
}
