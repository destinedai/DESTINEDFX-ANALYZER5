package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.util.Locale

/**
 * Reusable Modern Trading Terminal Design Kit (livetrading.ai aesthetic).
 * Provides GlassCard, StatChip, SignalBadge, SectionHeader, PriceText, and PulsingDot.
 */

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    CardBorder.copy(alpha = 0.9f),
                    CardBorder.copy(alpha = 0.4f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null || action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (title != null) {
                        Text(
                            text = title.uppercase(Locale.US),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = ElectricCyan,
                            letterSpacing = 0.8.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = ElectricCyan
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label.uppercase(Locale.US),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                letterSpacing = 0.4.sp
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
    }
}

@Composable
fun SignalBadge(
    state: String,
    modifier: Modifier = Modifier
) {
    val upper = state.uppercase(Locale.US)
    val (badgeColor, badgeText) = when {
        upper.contains("STRONG_BUY") -> ProfitGreen to "STRONG BUY"
        upper.contains("BUY") || upper.contains("LONG") -> ProfitGreen to "BUY"
        upper.contains("STRONG_SELL") -> LossRed to "STRONG SELL"
        upper.contains("SELL") || upper.contains("SHORT") -> LossRed to "SELL"
        upper.contains("WAIT") || upper.contains("NEUTRAL") -> WarningAmber to "WAIT"
        upper.contains("INVALIDATED") -> WarningAmber to "INVALIDATED"
        upper.contains("BLOCKED") || upper.contains("HALTED") -> TextMuted to "BLOCKED"
        else -> TextSecondary to (if (state.isBlank()) "ANALYZING" else state.uppercase(Locale.US))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor.copy(alpha = 0.14f))
            .border(1.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badgeText,
            color = badgeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(Locale.US),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            letterSpacing = 0.8.sp
        )
        action?.invoke()
    }
}

@Composable
fun PriceText(
    value: Double,
    change: Double? = null,
    modifier: Modifier = Modifier,
    symbol: String = ""
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val decimals = if (symbol.contains("JPY") || symbol.contains("XAU")) 2 else if (value >= 1000) 2 else 5
        val formatted = if (value == 0.0) "--" else "%.${decimals}f".format(Locale.US, value)

        Text(
            text = formatted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )

        if (change != null) {
            val isPositive = change >= 0
            val changeColor = if (isPositive) ProfitGreen else LossRed
            val changeSign = if (isPositive) "+" else ""
            Text(
                text = "$changeSign${"%.2f".format(Locale.US, change)}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = changeColor
            )
        }
    }
}

@Composable
fun PulsingDot(
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (connected) ProfitGreen else WarningAmber

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier.size(10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer halo
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = alpha * 0.4f))
        )
        // Core dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

/** Premium header shared by the actual production workspaces. */
@Composable
fun TerminalPageHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, CardBorder),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = eyebrow.uppercase(Locale.US),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.1.sp,
                    color = ElectricCyan
                )
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = TextPrimary,
                    lineHeight = 24.sp
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }
            if (trailing != null) {
                Box(contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

