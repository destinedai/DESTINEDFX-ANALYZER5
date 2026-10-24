package com.example.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.example.data.market.QuoteFreshness
import com.example.data.market.classifyQuoteFreshness
import com.example.domain.model.MarketTicker
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay

@Composable
fun QuoteFreshnessIndicator(
    ticker: MarketTicker?,
    connecting: Boolean = false,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val nowMs = produceState(System.currentTimeMillis(), ticker?.lastUpdateMillis, ticker?.kind) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }.value
    val freshness = classifyQuoteFreshness(ticker, nowMs, connecting = connecting)
    val color = when (freshness) {
        QuoteFreshness.LIVE -> ProfitGreen
        QuoteFreshness.CONNECTING -> WarningAmber
        QuoteFreshness.STALE -> WarningAmber
        QuoteFreshness.UNAVAILABLE -> TextMuted
    }
    val age = ticker?.lastUpdateMillis?.let { timestamp ->
        val delta = nowMs - timestamp
        if (timestamp <= 0L || delta < 0L) "time invalid"
        else when {
            delta < 60_000L -> "${delta / 1_000L}s ago"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            else -> "${delta / 3_600_000L}h ago"
        }
    }
    val status = when (freshness) {
        QuoteFreshness.LIVE -> "LIVE"
        QuoteFreshness.STALE -> "STALE"
        QuoteFreshness.CONNECTING -> "CONNECTING"
        QuoteFreshness.UNAVAILABLE -> "UNAVAILABLE"
    }
    val source = ticker?.source?.takeIf { it.isNotBlank() }
    val text = if (compact) {
        listOfNotNull(status, source, age).joinToString(" • ")
    } else {
        listOfNotNull(status, source, age, ticker?.kind).joinToString(" • ")
    }
    Text(text = text, modifier = modifier, color = color, fontSize = 9.sp, maxLines = 2)
}
