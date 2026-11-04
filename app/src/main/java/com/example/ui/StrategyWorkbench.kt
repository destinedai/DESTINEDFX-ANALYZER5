package com.example.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.ui.components.*
import com.example.ui.theme.*
import java.util.Locale

@Composable
internal fun StrategyWorkbench(
    selectedStrategyId: String,
    onSelect: (String) -> Unit,
    onOpenAnalyzer: () -> Unit,
    backtestContent: @Composable () -> Unit = {}
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    val strategies = StrategyRegistry.allStrategies
    val selected = strategies.firstOrNull { it.id == selectedStrategyId }
    val visible = strategies.filter { strategy ->
        val matchesSearch = search.isBlank() || strategy.name.contains(search, true) ||
            strategy.id.contains(search, true) || strategy.description.contains(search, true) ||
            strategy.rulesSummary.contains(search, true) || strategy.requiredEvidence.any { it.contains(search, true) }
        val matchesFilter = when (filter) {
            "BOT ELIGIBLE" -> strategy.botExecutionEligible
            "PYTHON CANDIDATES" -> strategy.pythonStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.CANDIDATE_ONLY
            else -> true
        }
        matchesSearch && matchesFilter
    }

    LazyColumn(modifier = Modifier.fillMaxSize().background(DarkBackground).testTag("strategy_list"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            TerminalPageHeader(
                eyebrow = "RULESET REGISTRY · EXECUTION PARITY",
                title = "Strategy workbench",
                subtitle = "Python outputs are candidates only. Bot routing requires a Kotlin strategy and final validation."
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(label = "KOTLIN READY", value = strategies.count { it.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && it.kotlinFinalValidation }.toString(), modifier = Modifier.weight(1f), color = ProfitGreen)
                StatChip(label = "PY CANDIDATE ONLY", value = strategies.count { it.pythonStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.CANDIDATE_ONLY }.toString(),
                    modifier = Modifier.weight(1f), color = WarningAmber)
                StatChip(label = "BOT ELIGIBLE", value = strategies.count { it.botExecutionEligible }.toString(),
                    modifier = Modifier.weight(1f), color = ElectricCyan)
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("CURRENT ANALYZER RULESET", fontSize = 9.sp, color = ElectricCyan, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    Text(selected?.name ?: "No registered strategy selected", fontSize = 18.sp,
                        color = TextPrimary, fontWeight = FontWeight.Black)
                    if (selected != null) {
                        Text(selected.id, modifier = Modifier.testTag("strategy_selected_id"),
                            fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                    Text(selected?.description ?: "Choose a Kotlin-implemented ruleset below.", fontSize = 11.sp,
                        color = TextSecondary, lineHeight = 17.sp)
                    if (selected != null) {
                        Text(selected.rulesSummary, fontSize = 10.sp, color = ElectricCyan,
                            fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        CapabilityLine("KOTLIN", capabilityLabel(selected.kotlinStatus, selected.kotlinFinalValidation), selected.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && selected.kotlinFinalValidation)
                        CapabilityLine("PYTHON", if (selected.pythonVersion == null) "NOT IMPLEMENTED" else "CANDIDATE ONLY · ${selected.pythonVersion}", false)
                        CapabilityLine("BOT CAPABILITY", if (selected.botExecutionEligible) "ELIGIBLE · RUNTIME GATES STILL APPLY" else "NOT ELIGIBLE", selected.botExecutionEligible)
                        Text("REQUIRED EVIDENCE", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace, letterSpacing = .7.sp)
                        selected.requiredEvidence.forEach { evidence ->
                            Text("• $evidence", fontSize = 10.sp, color = TextPrimary, lineHeight = 15.sp)
                        }
                        Text("COVERAGE: ${selected.testCoverage.joinToString(" · ")}", fontSize = 9.sp,
                            color = TextMuted, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    }
                    Button(onClick = onOpenAnalyzer,
                        modifier = Modifier.fillMaxWidth().height(42.dp).testTag("strategy_open_analyzer"), enabled = selected?.isImplemented == true,
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = DarkBackground)) {
                        Text(if (selected?.isImplemented == true) "OPEN ANALYZER" else "KOTLIN PATH REQUIRED", fontSize = 10.sp,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().testTag("strategy_search"),
                singleLine = true,
                label = { Text("Find a strategy") },
                placeholder = { Text("Name, ID, rules or evidence") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor = ElectricCyan,
                    cursorColor = ElectricCyan
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "BOT ELIGIBLE", "PYTHON CANDIDATES").forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        modifier = Modifier.weight(1f).testTag("strategy_filter_${option.lowercase().replace(' ', '_')}"),
                        label = { Text(option, fontSize = 8.sp, maxLines = 1) }
                    )
                }
            }
            Text("${visible.size} OF ${strategies.size} RULESETS", fontSize = 9.sp, color = TextSecondary,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = .8.sp)
        }
        items(visible, key = { it.id }) { strat ->
            val active = strat.id == selectedStrategyId
            val ready = strat.kotlinStatus == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && strat.kotlinFinalValidation
            Card(modifier = Modifier.fillMaxWidth().testTag("strategy_card_${strat.id}")
                .clickable(enabled = ready) { onSelect(strat.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (active) DarkSurfaceVariant else DarkSurface),
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) ElectricCyan else CardBorder)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(strat.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Black)
                            Text(strat.id, fontSize = 9.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                        if (active) Text("ANALYZER", fontSize = 8.sp, color = ElectricCyan,
                            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    }
                    Text(strat.description, fontSize = 10.sp, color = TextSecondary, lineHeight = 15.sp)
                    Text(strat.rulesSummary, fontSize = 9.sp, color = if (ready) ElectricCyan else TextMuted,
                        fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
                    CapabilityLine("KOTLIN", capabilityLabel(strat.kotlinStatus, strat.kotlinFinalValidation), ready)
                    CapabilityLine("PYTHON", if (strat.pythonVersion == null) "NOT IMPLEMENTED" else "CANDIDATE ONLY", false)
                    CapabilityLine("BOT", if (strat.botExecutionEligible) "ELIGIBLE · RUNTIME GATES APPLY" else "NOT ELIGIBLE", strat.botExecutionEligible)
                    Text("EVIDENCE: ${strat.requiredEvidence.joinToString(" · ")}", fontSize = 9.sp,
                        color = TextSecondary, lineHeight = 14.sp)
                }
            }
        }
        if (visible.isEmpty()) item { Text("No strategy matches this search/filter.", color = TextSecondary, fontSize = 11.sp) }
        item { backtestContent() }
    }
}

@Composable
private fun CapabilityLine(label: String, value: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(66.dp), fontSize = 8.sp, color = TextSecondary,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(value, modifier = Modifier.weight(1f), fontSize = 8.sp,
            color = if (enabled) ProfitGreen else WarningAmber,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
    }
}

private fun capabilityLabel(status: com.destinedfx.core.strategy.StrategyCapabilityStatus, finalValidation: Boolean): String = when {
    status == com.destinedfx.core.strategy.StrategyCapabilityStatus.IMPLEMENTED && finalValidation -> "IMPLEMENTED · FINAL VALIDATION PATH"
    status == com.destinedfx.core.strategy.StrategyCapabilityStatus.CANDIDATE_ONLY -> "CANDIDATE ONLY · NOT EXECUTABLE"
    else -> "NOT IMPLEMENTED · NOT EXECUTABLE"
}
