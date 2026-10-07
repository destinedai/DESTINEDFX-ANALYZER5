package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AnalysisEntity
import com.example.data.AnalysisRepository
import com.example.domain.repository.NewsRepository
import com.example.domain.usecase.SignalGenerationUseCase
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
            val sig = signal.generateSignal(_s.value.selectedSymbol, _s.value.selectedTimeframe)
            val report = buildString {
                appendLine("[DESTINEDFX ANALYSIS -- ${sig.symbol} ${sig.timeframe}]")
                appendLine("Strategy  : ${_s.value.selectedStrategy}")
                appendLine("Signal    : ${sig.direction.name}")
                appendLine("Entry     : ${String.format("%.5f", sig.entryPrice)}")
                appendLine("Stop Loss : ${String.format("%.5f", sig.stopLoss)}")
                appendLine("Take Prof : ${String.format("%.5f", sig.takeProfit)}")
                appendLine("Confluence Score: ${sig.confidenceScore}/100 - rules-based, NOT A GUARANTEED WIN RATE")
                appendLine()
                append(sig.rationale)
            }
            history.insertAnalysis(AnalysisEntity(
                symbol=sig.symbol, timeframe=sig.timeframe, strategy=_s.value.selectedStrategy,
                dateMs=System.currentTimeMillis(), winRateConfidence=sig.confidenceScore.toDouble(),
                signal=if(sig.direction.name=="LONG")"BUY" else "SELL", report=report
            ))
            _s.value = _s.value.copy(
                isLoading=false, confidenceScore=sig.confidenceScore,
                mtfBias=if(sig.direction.name=="LONG")"BULLISH" else "BEARISH",
                analysisResult=report
            )
        } catch (e: Exception) {
            _s.value = _s.value.copy(isLoading=false, error="Analysis failed: ${e.message}")
        }
    }

    private fun checkNews(sym: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(
            isNewsBlocked = news.isHighImpactWindowActive(listOf(sym.take(3), sym.takeLast(3)))
        )
    }
}
