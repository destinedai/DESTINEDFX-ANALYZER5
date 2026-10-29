package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AnalysisEntity
import com.example.data.AnalysisRepository
import com.example.domain.repository.NewsRepository
import com.example.domain.usecase.SignalGenerationUseCase
import com.example.ui.resolveSelectedStrategyId
import com.example.ui.signalHistoryLabel
import com.example.ui.signalBiasLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AnalysisUiState(
    val selectedSymbol: String    = "EUR/USD",
    val selectedTimeframe: String = "H1",
    val selectedStrategy: String  = "SMC Order Blocks",
    val confidenceScore: Int      = 0,
    val mtfBias: String           = "NEUTRAL",
    val analysisResult: String?   = null,
    val isLoading: Boolean        = false,
    val isNewsBlocked: Boolean    = false,
    val error: String?            = null
)

class AnalysisViewModel(
    private val signal:  SignalGenerationUseCase,
    private val news:    NewsRepository,
    private val history: AnalysisRepository
) : ViewModel() {

    private val _s = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _s.asStateFlow()

    val analysisHistory: StateFlow<List<AnalysisEntity>> = history.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageMime   = MutableStateFlow<String?>(null)
    val selectedImageUri    = MutableStateFlow<String?>(null)

    fun onSymbolChanged(s: String)    { _s.value = _s.value.copy(selectedSymbol=s); checkNews(s) }
    fun onTimeframeChanged(t: String) { _s.value = _s.value.copy(selectedTimeframe=t) }
    fun onStrategyChanged(s: String)  { _s.value = _s.value.copy(selectedStrategy=s) }

    fun onImageSelected(uri: String, b64: String, mime: String) {
        selectedImageUri.value=uri; selectedImageBase64.value=b64; selectedImageMime.value=mime
    }

    fun runAnalysis() = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading=true, error=null)
        try {
            val ui = _s.value
            val strategyId = resolveSelectedStrategyId(ui.selectedStrategy)
            val sig = signal.generateSignal(ui.selectedSymbol, ui.selectedTimeframe, strategyId)
            val signalLabel = signalHistoryLabel(sig.direction)
            val biasLabel = signalBiasLabel(sig.direction)
            val report = buildString {
                appendLine("[DESTINEDFX ANALYSIS -- ${sig.symbol} ${sig.timeframe}]")
                appendLine("Strategy  : ${strategyId}")
                appendLine("Signal    : ${signalLabel}")
                appendLine("State     : ${sig.signalState}")
                appendLine("Executable: ${sig.executable}")
                appendLine("Entry     : ${String.format(java.util.Locale.US, "%.5f", sig.entryPrice)}")
                appendLine("Stop Loss : ${String.format(java.util.Locale.US, "%.5f", sig.stopLoss)}")
                appendLine("Take Prof : ${String.format(java.util.Locale.US, "%.5f", sig.takeProfit)}")
                appendLine("Confluence Score: ${sig.confidenceScore.coerceIn(0, 100)}/100 - rules-based, NOT A GUARANTEED WIN RATE")
                if (sig.blockReasons.isNotEmpty()) {
                    appendLine()
                    appendLine("Blockers:")
                    sig.blockReasons.forEach { appendLine("- $it") }
                }
                appendLine()
                append(sig.rationale)
            }
            history.insertAnalysis(AnalysisEntity(
                symbol = sig.symbol, timeframe = sig.timeframe, strategy = strategyId,
                dateMs = System.currentTimeMillis(), winRateConfidence = sig.confidenceScore.coerceIn(0, 100).toDouble(),
                signal = signalLabel, report = report
            ))
            _s.value = _s.value.copy(
                isLoading = false, confidenceScore = sig.confidenceScore.coerceIn(0, 100),
                mtfBias = biasLabel, analysisResult = report
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _s.value = _s.value.copy(isLoading = false, error = "Analysis failed: ${e.message}")
        }
    }

    private fun checkNews(sym: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(
            isNewsBlocked = news.isHighImpactWindowActive(listOf(sym.take(3), sym.takeLast(3)))
        )
    }
}
