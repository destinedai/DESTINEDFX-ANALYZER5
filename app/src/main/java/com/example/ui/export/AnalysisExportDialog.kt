package com.example.ui.export

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.DynamicAnalysisInfo
import com.example.ui.theme.*
import com.example.util.AnalysisExportManager

/**
 * Reusable card to embed in the Signals tab and Analytics deck for quick export.
 */
@Composable
fun AnalysisExportCard(
    onExportPdf: () -> Unit,
    onExportImage: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "EXPORT ANALYSIS DOSSIER",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyanPrimary,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyanPrimary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SAVE TO PHONE",
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }
            }

            Text(
                text = "Export complete algorithmic analysis, entry/SL/TP coordinates, and pure mathematical justification as PDF or PNG to your phone storage without guesswork.",
                fontSize = 8.5.sp,
                color = TextSecondary,
                style = TextStyle(lineHeight = 12.sp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF192538)),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PDF DOSSIER",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                }

                Button(
                    onClick = onExportImage,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF142E25)),
                    border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = ProfitGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "IMAGE (PNG)",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = ProfitGreen
                    )
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(DarkSurfaceVariant)
                        .border(1.dp, TabBorder, RoundedCornerShape(4.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Modal selection dialog to pick format and options for export.
 */
@Composable
fun AnalysisExportSelectionDialog(
    analysis: DynamicAnalysisInfo,
    onDismiss: () -> Unit,
    onExportPdf: () -> Unit,
    onExportImage: () -> Unit,
    isExporting: Boolean
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "EXPORT MARKET ANALYSIS",
                            color = CyanPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "${analysis.symbol} • ${analysis.direction} • Rules-Based Algorithmic",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Divider(color = TabBorder, thickness = 1.dp)

                if (isExporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = CyanPrimary, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Generating export file...",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Encoding vector graphics and mathematical rationale...",
                            color = TextSecondary,
                            fontSize = 9.sp
                        )
                    }
                } else {
                    // Option 1: PDF Dossier
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExportPdf() },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131A26)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CyanPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PDF Analysis Dossier",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Complete rules-based report with entry/SL/TP coordinates, algorithmic justification, and MTF alignment.",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    style = TextStyle(lineHeight = 12.sp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Saved to: Downloads/DestinedFX",
                                    color = CyanPrimary,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = CyanPrimary
                            )
                        }
                    }

                    // Option 2: High-Res PNG Image
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExportImage() },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10241E)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ProfitGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = ProfitGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Infographic Image (PNG)",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Razor-sharp terminal graphic card with trade levels, profit zones, and visual badges.",
                                    color = TextSecondary,
                                    fontSize = 9.sp,
                                    style = TextStyle(lineHeight = 12.sp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Saved to: Pictures/DestinedFX",
                                    color = ProfitGreen,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = ProfitGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Result dialog shown when file has been exported successfully to phone storage.
 */
@Composable
fun AnalysisExportResultDialog(
    result: AnalysisExportManager.ExportResult,
    onDismiss: () -> Unit,
    onOpenFile: () -> Unit,
    onShareFile: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            border = BorderStroke(1.dp, if (result.success) ProfitGreen else RedAccent)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (result.success) ProfitGreen.copy(alpha = 0.15f)
                            else RedAccent.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (result.success) ProfitGreen else RedAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = if (result.success) "SAVED TO PHONE!" else "EXPORT FAILED",
                    color = if (result.success) ProfitGreen else RedAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )

                if (result.success) {
                    Text(
                        text = "Your analysis has been saved to your phone storage and is ready to view or share.",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkSurfaceVariant)
                            .border(1.dp, TabBorder, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                text = "FILE NAME:",
                                fontSize = 8.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = result.fileName,
                                fontSize = 10.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "LOCATION:",
                                fontSize = 8.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = result.publicPath,
                                fontSize = 9.sp,
                                color = CyanPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenFile,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "OPEN FILE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                        }

                        Button(
                            onClick = onShareFile,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C283C)),
                            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SHARE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    }
                } else {
                    Text(
                        text = result.errorMessage ?: "An unexpected error occurred during file generation.",
                        fontSize = 10.sp,
                        color = RedAccent,
                        textAlign = TextAlign.Center
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "CLOSE",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
