package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.GoldGlow
import com.example.ui.theme.GoldPrimary
import com.example.ui.theme.ProfitGreen
import kotlinx.coroutines.delay

/** Short, deterministic intro: no network, no auth, and no blocking work. */
@Composable
fun BrandWelcome(onFinished: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "brand_motion")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "brand_glow"
    )
    val sweep by transition.animateFloat(
        initialValue = -0.08f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "chart_sweep"
    )

    LaunchedEffect(Unit) {
        delay(950)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF142A3C), DarkBackground, DarkBackground),
                    radius = 900f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().alpha(0.55f)) {
            val columns = 8
            val rows = 12
            for (i in 1 until columns) {
                val x = size.width * i / columns
                drawLine(Color(0x2233A69A), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height))
            }
            for (i in 1 until rows) {
                val y = size.height * i / rows
                drawLine(Color(0x2233A69A), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(GoldPrimary.copy(alpha = 0.08f + glow * 0.08f))
                )
                Image(
                    painter = painterResource(R.drawable.golden_d_emblem),
                    contentDescription = "DestinedFX radiant emblem",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(82.dp)
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "DESTINEDFX",
                color = Color.White,
                fontSize = 28.sp,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "MARKET INTELLIGENCE TERMINAL",
                color = GoldGlow.copy(alpha = 0.9f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(24.dp))
            Canvas(modifier = Modifier.size(286.dp, 92.dp)) {
                val points = listOf(0.02f to 0.66f, 0.12f to 0.48f, 0.21f to 0.57f, 0.31f to 0.38f, 0.40f to 0.53f, 0.50f to 0.31f, 0.59f to 0.43f, 0.69f to 0.24f, 0.79f to 0.35f, 0.89f to 0.16f, 0.98f to 0.27f)
                val path = Path()
                points.forEachIndexed { index, (x, y) ->
                    val px = x * size.width
                    val py = y * size.height
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, GoldPrimary.copy(alpha = 0.25f), style = Stroke(width = 10f, cap = StrokeCap.Round))
                drawPath(path, GoldGlow.copy(alpha = 0.85f), style = Stroke(width = 3f, cap = StrokeCap.Round))
                points.forEachIndexed { index, (x, y) ->
                    if (index % 2 == 0) {
                        val px = x * size.width
                        val py = y * size.height
                        val bullish = index % 4 == 0
                        drawLine(if (bullish) ProfitGreen else Color(0xFFE06B67), androidx.compose.ui.geometry.Offset(px, py - 11f), androidx.compose.ui.geometry.Offset(px, py + 11f), 2f)
                        drawLine(if (bullish) ProfitGreen else Color(0xFFE06B67), androidx.compose.ui.geometry.Offset(px - 5f, py), androidx.compose.ui.geometry.Offset(px + 5f, py), 6f)
                    }
                }
                drawCircle(GoldGlow.copy(alpha = 0.8f), radius = 5f, center = androidx.compose.ui.geometry.Offset(sweep * size.width, size.height * 0.30f))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "PRECISION • DISCIPLINE • CLARITY",
                color = ProfitGreen.copy(alpha = 0.9f),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
