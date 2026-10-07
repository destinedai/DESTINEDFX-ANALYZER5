package com.destinedfx.core.ai

import com.destinedfx.core.analysis.SignalReport

/**
 * AI is an EXPLAINER only. It receives a verified, structured snapshot and returns TEXT. There is deliberately no API through which
 * AI text can change a signal state, SL/TP, lot size or approval: TradeValidationPipeline does not accept AI output.
 */
interface AiExplainer { suspend fun explain(structuredPrompt: String): Result<String> }

object AiPrompt {
    fun build(r: SignalReport): String = buildString {
        appendLine("You are an analyst assistant. Explain ONLY the verified data below. Do not invent prices, levels, news or probabilities.")
        appendLine("Do not give trade instructions, lot sizes or guarantees. State conflicts and uncertainty plainly.")
        appendLine("Symbol=${r.symbol} TF=${r.timeframe.label} State=${r.state} ConfluenceScore=${r.confluenceScore}/100 (rules-based, not a win probability)")
        appendLine("MTF=${r.mtf?.summary}")
        r.components.forEach { appendLine("${it.name}: ${"%.1f".format(it.earned)}/${it.available} :: ${it.notes.joinToString("; ")}") }
        r.plan?.let { appendLine("Plan entry=${it.entry} SL=${it.stopLoss} primaryTP=${it.primary.price} RR=${"%.2f".format(it.primary.rr)}") }
        r.reasons.take(10).forEach { appendLine("Note: $it") }
        r.patterns.forEach { appendLine("Pattern: ${it.name} ${it.state}") }
    }
    /** Strip anything that looks like instructions to trade from AI output before display. */
    fun sanitize(text: String): String = text.lines().filterNot { Regex("(?i)\\b(enter now|place (a )?(buy|sell)|use \\d+(\\.\\d+)? lots?|guaranteed|100% )").containsMatchIn(it) }.joinToString("\n")
}
