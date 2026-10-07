package com.destinedfx.core.instrument

import java.util.concurrent.ConcurrentHashMap

/** Holds broker-supplied specs only. Never pre-populated. Missing spec => sizing/trading unavailable. */
object SpecRegistry {
    private val map = ConcurrentHashMap<String, InstrumentSpec>()
    private fun k(s: String) = s.uppercase().replace("/", "")
    fun put(spec: InstrumentSpec) { map[k(spec.symbol)] = spec }
    fun get(symbol: String): InstrumentSpec? = map[k(symbol)]
    fun clear() = map.clear()
}
