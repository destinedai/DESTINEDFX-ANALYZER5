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
@Preview(showBackground = true, widthDp = 360, heightDp = 740)
@Composable
fun PreviewDestinedFXApp() {
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
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("FLAGSHIP TERMINAL & TRADINGVIEW", color = CyanPrimary, style = MaterialTheme.typography.labelMedium)
                            Text("EUR/USD · M15 Institutional SMC Engine", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                            Text("Status: Live Feed Connected (WebSocket)", color = Color(0xFF00FF99), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
