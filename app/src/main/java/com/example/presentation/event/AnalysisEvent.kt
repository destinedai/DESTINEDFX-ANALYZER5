package com.example.presentation.event

sealed class AnalysisEvent {
    data class SymbolChanged(val symbol: String) : AnalysisEvent()
    data class TimeframeChanged(val timeframe: String) : AnalysisEvent()
    data class StrategyChanged(val strategy: String) : AnalysisEvent()
    data class ImageSelected(val uri: String) : AnalysisEvent()
    object ImageCleared : AnalysisEvent()
    object AnalyzeClicked : AnalysisEvent()
}
