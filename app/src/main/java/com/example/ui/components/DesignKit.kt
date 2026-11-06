package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.util.Locale

/** Shared visual language for the native terminal surfaces. */
object DesignKit {
    val Background = NavyBackground
    val Surface = DarkSurface
    val ElevatedSurface = SurfaceElevated
    val Primary = GoldPrimary
    val Buy = ProfitGreen
    val Sell = LossRed
    val Info = BlueAccent
    val Stale = WarningAmber
    val Spacing = 8.dp
    val CornerRadius = 12.dp

    object Type {
        val Body = 14.sp
        val Label = 12.sp
        val Section = 16.sp
        val Title = 24.sp
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(CardBorder.copy(alpha = 0.9f), CardBorder.copy(alpha = 0.4f)))
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(DesignKit.Spacing)
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
                            fontSize = DesignKit.Type.Label,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = ElectricCyan,
                            letterSpacing = 0.6.sp
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
fun StatChip(label: String, value: String, modifier: Modifier = Modifier, color: Color = DesignKit.Info) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DesignKit.CornerRadius))
            .background(DarkSurfaceVariant)
            .border(1.dp, CardBorder, RoundedCornerShape(DesignKit.CornerRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(Locale.US), fontSize = DesignKit.Type.Label, fontWeight = FontWeight.Medium, color = TextSecondary)
            Text(value, fontSize = DesignKit.Type.Label, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color)
        }
    }
}

@Composable
fun SignalBadge(state: String, modifier: Modifier = Modifier) {
    val upper = state.uppercase(Locale.US)
    val (badgeColor, badgeText) = when {
        upper.contains("STRONG_BUY") -> DesignKit.Buy to "STRONG BUY"
        upper.contains("BUY") || upper.contains("LONG") -> DesignKit.Buy to "BUY"
        upper.contains("STRONG_SELL") -> DesignKit.Sell to "STRONG SELL"
        upper.contains("SELL") || upper.contains("SHORT") -> DesignKit.Sell to "SELL"
        upper.contains("WAIT") || upper.contains("NEUTRAL") -> DesignKit.Stale to "WAIT"
        upper.contains("INVALIDATED") -> DesignKit.Stale to "INVALIDATED"
        upper.contains("BLOCKED") || upper.contains("HALTED") -> TextMuted to "BLOCKED"
        else -> TextSecondary to (if (state.isBlank()) "ANALYZING" else upper)
    }
    StatusBadge(badgeText, modifier, badgeColor)
}

@Composable
fun StatusBadge(label: String, modifier: Modifier = Modifier, color: Color = DesignKit.Info) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Status: $label" },
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        tonalElevation = 0.dp
    ) {
        Text(
            text = label.uppercase(Locale.US),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = color,
            fontSize = DesignKit.Type.Label,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(Locale.US),
            fontSize = DesignKit.Type.Section,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            letterSpacing = 0.6.sp
        )
        action?.invoke()
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, title: String = "Nothing to show") {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DesignKit.Surface,
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignKit.Spacing)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = DesignKit.Info)
            Text(title, color = TextPrimary, fontSize = DesignKit.Type.Section, fontWeight = FontWeight.Bold)
            Text(message, color = TextSecondary, fontSize = DesignKit.Type.Body)
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Error: $message" },
        color = DesignKit.Sell.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        border = BorderStroke(1.dp, DesignKit.Sell.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignKit.Spacing)
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DesignKit.Sell)
            Text(message, modifier = Modifier.weight(1f), color = TextPrimary, fontSize = DesignKit.Type.Body)
            if (onRetry != null) {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRetry()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = DesignKit.Primary)
                }
            }
        }
    }
}

@Composable
fun SkeletonLoader(modifier: Modifier = Modifier, contentDescription: String = "Loading") {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeleton-alpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DesignKit.CornerRadius))
            .background(TextMuted.copy(alpha = alpha))
            .semantics { this.contentDescription = contentDescription }
    )
}

@Composable
fun DesignChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(DesignKit.CornerRadius))
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        color = if (selected) DesignKit.Primary.copy(alpha = 0.18f) else DesignKit.Surface,
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        border = BorderStroke(1.dp, if (selected) DesignKit.Primary else CardBorder)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (selected) DesignKit.Primary else TextSecondary,
            fontSize = DesignKit.Type.Label,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = DesignKit.Primary,
            contentColor = DarkBackground,
            disabledContainerColor = DesignKit.Primary.copy(alpha = 0.38f)
        )
    ) {
        if (loading) SkeletonLoader(Modifier.size(18.dp), "Loading") else Text(text, fontSize = DesignKit.Type.Label, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptic = LocalHapticFeedback.current
    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        border = BorderStroke(1.dp, if (enabled) DesignKit.Primary else CardBorder)
    ) {
        Text(text, fontSize = DesignKit.Type.Label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PriceText(value: Double, change: Double? = null, modifier: Modifier = Modifier, symbol: String = "") {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignKit.Spacing)) {
        val decimals = if (symbol.contains("JPY") || symbol.contains("XAU")) 2 else if (value >= 1000) 2 else 5
        val formatted = if (value == 0.0) "--" else "%.${decimals}f".format(Locale.US, value)
        Text(formatted, fontSize = DesignKit.Type.Body, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextPrimary)
        if (change != null) {
            val isPositive = change >= 0
            Text("${if (isPositive) "+" else ""}${"%.2f".format(Locale.US, change)}%", fontSize = DesignKit.Type.Label, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = if (isPositive) DesignKit.Buy else DesignKit.Sell)
        }
    }
}

@Composable
fun PulsingDot(connected: Boolean, modifier: Modifier = Modifier) {
    val dotColor = if (connected) DesignKit.Buy else DesignKit.Stale
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(0.35f, 1.0f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    Box(modifier = modifier.size(10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor.copy(alpha = alpha * 0.4f)))
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
    }
}

@Composable
fun TerminalPageHeader(eyebrow: String, title: String, subtitle: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(DesignKit.CornerRadius)),
        shape = RoundedCornerShape(DesignKit.CornerRadius),
        color = Color.Transparent,
        border = BorderStroke(1.dp, CardBorder),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(HeroGoldShade, DarkSurfaceVariant, DarkSurface))).padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesignKit.Spacing)) {
                Text(eyebrow.uppercase(Locale.US), fontSize = DesignKit.Type.Label, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, color = ElectricCyan)
                Text(title, fontSize = DesignKit.Type.Title, fontWeight = FontWeight.Black, color = TextPrimary, lineHeight = 28.sp)
                Text(subtitle, fontSize = DesignKit.Type.Body, color = TextSecondary, lineHeight = 18.sp)
            }
            if (trailing != null) Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

@Composable
fun TerminalStatusPill(label: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "Status: $label" },
        color = tint.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.38f)),
        tonalElevation = 0.dp
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Text(label.uppercase(Locale.US), fontSize = DesignKit.Type.Label, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp, color = tint, maxLines = 1)
        }
    }
}

/** Compatibility wrapper retained for existing screens; new screens should use PrimaryActionButton. */
@Composable
fun GoldGradientButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(DesignKit.CornerRadius)
    val goldBrush = Brush.linearGradient(if (enabled) listOf(GoldPrimary, GoldGlow) else listOf(GoldPrimary.copy(alpha = 0.42f), GoldGlow.copy(alpha = 0.42f)))
    CompositionLocalProvider(LocalContentColor provides DarkBackground) {
        Row(
            modifier = modifier.heightIn(min = 48.dp).clip(shape).background(goldBrush).clickable(enabled = enabled, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
