package com.destinedfx.core.marketdata

import kotlin.random.Random

class ExponentialBackoff(private val baseMs: Long = 1_000, private val maxMs: Long = 60_000,
                         private val jitter: Double = 0.2, private val random: Random = Random.Default) {
    private var attempt = 0
    fun nextDelayMs(): Long {
        val exp = minOf(maxMs.toDouble(), baseMs * Math.pow(2.0, attempt.coerceAtMost(20).toDouble()))
        attempt++
        return (exp + exp * jitter * (random.nextDouble() * 2 - 1)).toLong().coerceIn(1, maxMs)
    }
    fun reset() { attempt = 0 }
    val attempts: Int get() = attempt
}
