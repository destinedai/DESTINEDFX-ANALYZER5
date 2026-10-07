package com.destinedfx.core.instrument

import com.destinedfx.core.marketdata.AssetClass
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Broker symbol specification. Every field comes from the broker (MT5 SYMBOL_* properties via the gateway);
 * there is NO default table. If a spec cannot be obtained, [SpecResult.Unavailable] is returned and trading is blocked.
 */
data class InstrumentSpec(
    val symbol: String, val assetClass: AssetClass,
    val digits: Int,                 // SYMBOL_DIGITS
    val tickSize: Double,            // SYMBOL_TRADE_TICK_SIZE
    val tickValue: Double,           // SYMBOL_TRADE_TICK_VALUE, account currency per tick per 1.0 lot
    val contractSize: Double,        // SYMBOL_TRADE_CONTRACT_SIZE
    val volumeMin: Double,           // SYMBOL_VOLUME_MIN
    val volumeMax: Double,           // SYMBOL_VOLUME_MAX
    val volumeStep: Double,          // SYMBOL_VOLUME_STEP
    val stopsLevelPoints: Int = 0,   // SYMBOL_TRADE_STOPS_LEVEL
    val profitCurrency: String? = null, val baseCurrency: String? = null,
    val source: String
) {
    val point: Double get() = Math.pow(10.0, -digits.toDouble())
    /** A "pip" exists only for FX: 10 points on 3/5-digit quotes. Other classes are measured in points/ticks. */
    val pipSize: Double? get() = if (assetClass == AssetClass.FOREX) (if (digits == 3 || digits == 5) point * 10 else point) else null

    fun priceToPoints(d: Double) = abs(d) / point
    fun priceToTicks(d: Double) = abs(d) / tickSize
    fun priceToPips(d: Double): Double? = pipSize?.let { abs(d) / it }
    fun roundToTick(price: Double) = round(price / tickSize) * tickSize
    val minStopDistance: Double get() = stopsLevelPoints * point

    fun validationErrors(): List<String> = buildList {
        if (!(tickSize > 0 && tickSize.isFinite())) add("tickSize invalid")
        if (!(tickValue > 0 && tickValue.isFinite())) add("tickValue invalid")
        if (!(contractSize > 0 && contractSize.isFinite())) add("contractSize invalid")
        if (!(volumeMin > 0 && volumeMax >= volumeMin && volumeStep > 0)) add("volume limits invalid")
        if (digits < 0) add("digits invalid")
    }
}

sealed interface SpecResult {
    data class Ok(val spec: InstrumentSpec) : SpecResult
    data class Unavailable(val reason: String) : SpecResult
}

/** Parses the broker gateway's symbol specification JSON (MetaApi field names). */
object SpecParser {
    /**
     * MetaApi's specification exposes tickSize, contractSize, min/max/step volume, digits, but NOT tickValue.
     * tickValue is therefore either supplied by the gateway (if present) or derived as
     * tickSize * contractSize * rate(profitCurrency -> accountCurrency). The rate must be real (see [conversionRate]).
     */
    fun fromJson(json: JSONObject, assetClass: AssetClass, accountCurrency: String,
                 conversionRate: (from: String, to: String) -> Double?, source: String): SpecResult {
        return try {
            val tickSize = json.getDouble("tickSize")
            val contract = json.getDouble("contractSize")
            val profitCcy = json.optString("profitCurrency").ifBlank { null }
            val tv = when {
                json.has("tickValue") && json.getDouble("tickValue") > 0 -> json.getDouble("tickValue")
                profitCcy == null -> return SpecResult.Unavailable("profitCurrency missing; cannot derive tickValue")
                profitCcy.equals(accountCurrency, true) -> tickSize * contract
                else -> {
                    val r = conversionRate(profitCcy, accountCurrency)
                        ?: return SpecResult.Unavailable("No live conversion rate $profitCcy->$accountCurrency")
                    tickSize * contract * r
                }
            }
            val spec = InstrumentSpec(json.getString("symbol"), assetClass, json.getInt("digits"), tickSize, tv, contract,
                json.getDouble("minVolume"), json.getDouble("maxVolume"), json.getDouble("volumeStep"),
                json.optInt("stopsLevel", 0), profitCcy, json.optString("baseCurrency").ifBlank { null }, source)
            val errs = spec.validationErrors()
            if (errs.isEmpty()) SpecResult.Ok(spec) else SpecResult.Unavailable(errs.joinToString("; "))
        } catch (e: org.json.JSONException) { SpecResult.Unavailable("Malformed specification: ${e.message}") }
    }
}

object VolumeMath {
    /** Round DOWN to the broker step (never up: rounding up would exceed the intended risk). Epsilon guards binary-float error. */
    fun floorToStep(volume: Double, step: Double): Double = floor(volume / step + 1e-9) * step
    fun normalise(volume: Double, step: Double, digits: Int = 8): Double = java.math.BigDecimal(volume).setScale(digits, java.math.RoundingMode.HALF_UP).toDouble()
}

object AssetClassifier {
    fun of(symbol: String): AssetClass { val s = symbol.uppercase().replace("/", "")
        return when {
            s.startsWith("XAU") || s.startsWith("XAG") || s.startsWith("XPT") || s.startsWith("XPD") -> AssetClass.METALS
            s in setOf("SPX500", "NAS100", "DJ30", "US30", "GER40", "UK100", "JP225") -> AssetClass.INDICES
            listOf("BTC", "ETH", "BNB", "SOL", "XRP", "LTC", "ADA", "DOGE").any { s.startsWith(it) } -> AssetClass.CRYPTO
            s in setOf("USOIL", "UKOIL", "WTI", "BRENT", "NGAS") -> AssetClass.COMMODITIES
            else -> AssetClass.FOREX } }
    /** Currencies whose news matters for this symbol (no blanket "USD affects everything" assumption except where the quote currency is USD). */
    fun newsCurrencies(symbol: String): Set<String> { val s = symbol.uppercase().replace("/", "")
        return when (of(symbol)) { AssetClass.FOREX -> if (s.length >= 6) setOf(s.take(3), s.substring(3, 6)) else setOf("USD")
            else -> setOf("USD") } }
}
