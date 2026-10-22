package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.GoldGradientButton
import com.example.ui.components.GlassCard
import com.example.ui.components.TerminalPageHeader
import com.example.ui.components.TerminalStatusPill
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun AboutDisclosureTab(viewModel: DestinedFXViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TerminalPageHeader(
                eyebrow = "PRODUCT & DISCLOSURE",
                title = "About DestinedFX",
                subtitle = "A rule-based analysis terminal for reviewing market structure, evidence and risk.",
                trailing = { TerminalStatusPill(label = "EDUCATIONAL", tint = WarningAmber) }
            )
        }
        item {
            GlassCard(title = "HOW THE ANALYSIS WORKS") {
                Text(
                    "DestinedFX uses deterministic strategy rules and available market data to surface a possible setup, its evidence and risk checks. The Android app does not promise a result and does not create a qualified trade from missing or invalid data.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
        item {
            GlassCard(title = "IMPORTANT RISK DISCLOSURE") {
                Text(
                    "Trading financial instruments involves substantial risk. You can lose some or all of your investment, and losses may exceed your initial deposit. Past performance does not guarantee future results.",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp
                )
                Text(
                    "This app is for educational and informational purposes only. It is not financial, investment or trading advice. You are responsible for independent decisions and for understanding the risks before placing any trade.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
        item {
            GlassCard(title = "NO OUTCOME GUARANTEE") {
                Text(
                    "Signals, analysis, charts and risk calculations can be incomplete or unavailable. No win rate, profitability or outcome is guaranteed. Verify data freshness and all trade details independently.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
        item {
            GoldGradientButton(
                onClick = { viewModel.selectScreen(DestinedFXScreen.Analyzer) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ShowChart, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("OPEN ANALYZER", fontWeight = FontWeight.Black, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}
