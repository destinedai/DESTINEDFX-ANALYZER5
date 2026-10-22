package com.example.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.GoldGradientButton
import com.example.ui.components.GlassCard
import com.example.ui.components.TerminalPageHeader
import com.example.ui.theme.LossRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ContactWorkstationTab() {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TerminalPageHeader(
                eyebrow = "CONTACT",
                title = "Get in touch",
                subtitle = "Questions or feedback? Reach out to the DestinedFX team."
            )
        }
        item {
            GlassCard(title = "SUPPORT EMAIL") {
                SelectionContainer {
                    Text(
                        "hello@destinedfx.app",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "The address is published on the DestinedFX website. Tapping below opens an email draft; nothing is sent until you review and send it in your email app.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                GoldGradientButton(
                    onClick = {
                        launchError = try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:hello@destinedfx.app?subject=DestinedFX%20support")
                                )
                            )
                            null
                        } catch (_: ActivityNotFoundException) {
                            "No email app is available on this device. You can select and copy the address above."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("OPEN EMAIL APP", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
                launchError?.let { message ->
                    Text(message, color = LossRed, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
