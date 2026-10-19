package com.example.ui

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "1. Analyzer Terminal", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewAnalyzerTerminal() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Scaffold(
                containerColor = DarkBackground,
                topBar = {
                    TopAppBar(
                        title = { Text("DESTINEDFX ANALYZER", color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier.padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SYMBOL: EUR/USD · M15", color = CyanPrimary, style = MaterialTheme.typography.labelMedium)
                            Text("SMC Market Structure Engine", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text("Feed Status: Standby / Awaiting Validation", color = OrangeAccent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "2. Strategies Workstation", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewStrategiesWorkstation() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("STRATEGY LIBRARY (7 Core Rulesets)", color = CyanPrimary, style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Smart Money Concepts (SMC)", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text("BOS, CHoCH, Unmitigated Order Blocks, FVG", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "3. Calendar Destination", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewCalendarDestination() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ECONOMIC CALENDAR & NEWS GATE", color = CyanPrimary, style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("News Gate Status: UNKNOWN (Evaluating...)", color = OrangeAccent, style = MaterialTheme.typography.bodyMedium)
                        Text("Automated execution blocked until verified clear.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "4. Bot Control Center", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewBotDestination() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AUTONOMOUS BOT CENTER", color = CyanPrimary, style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Operating Mode: DISCONNECTED / PAPER", color = OrangeAccent, style = MaterialTheme.typography.bodyMedium)
                        Text("Master Switch: OFF", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Preview(name = "5. Risk Workstation", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewRiskDestination() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RISK ENGINE & POSITION SIZING", color = CyanPrimary, style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Max Risk Ceiling: 1.0%", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("Minimum Reward-to-Risk: 2.0R", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Preview(name = "6. Settings & Connections", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewSettingsDestination() {
    DestinedFXTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SETTINGS & SECURE CREDENTIALS", color = CyanPrimary, style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Session: Guest Trader (Local Terminal)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("Supabase Cloud Sync / Deriv WebSocket API", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
