package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class AnalyzerViewModel : ViewModel() {
    var pageLoaded by mutableStateOf(false)
        private set

    var chartRendered by mutableStateOf(false)
        private set

    var priceAvailable by mutableStateOf(false)
        private set

    var priceFresh by mutableStateOf(false)
        private set

    val chartLoaded: Boolean
        get() = pageLoaded && chartRendered

    fun onChartPageStarted() {
        pageLoaded = false
        chartRendered = false
    }

    fun onChartPageLoaded() {
        pageLoaded = true
    }

    fun onChartRendered(rendered: Boolean) {
        chartRendered = pageLoaded && rendered
    }

    fun onChartPageFailed() {
        pageLoaded = false
        chartRendered = false
    }

    fun onChartUnavailable() {
        pageLoaded = false
        chartRendered = false
    }

    fun updatePriceState(available: Boolean, fresh: Boolean) {
        priceAvailable = available
        priceFresh = available && fresh
    }
}

