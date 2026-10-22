package com.example.ui.chart

/** The official Advanced Chart widget stays primary; the hosted widget page is tried once on failure. */
internal enum class TradingViewLoadPath {
    OFFICIAL_WIDGET,
    HOSTED_CHART
}

internal enum class TradingViewLoadStatus {
    LOADING,
    DISPLAYED_UNVERIFIED,
    FAILED
}

/** Pure state transitions keep timeouts, callback failures, and retries deterministic and testable. */
internal data class TradingViewLoadState(
    val path: TradingViewLoadPath = TradingViewLoadPath.OFFICIAL_WIDGET,
    val status: TradingViewLoadStatus = TradingViewLoadStatus.LOADING,
    val message: String? = null
) {
    val isLoading: Boolean get() = status == TradingViewLoadStatus.LOADING
    val isTerminal: Boolean get() = status == TradingViewLoadStatus.FAILED

    /** Stop obscuring the embed, while explicitly not claiming that cross-origin candles rendered. */
    fun displayedUnverified(evidence: String): TradingViewLoadState =
        if (isLoading) copy(status = TradingViewLoadStatus.DISPLAYED_UNVERIFIED, message = evidence) else this

    fun fail(reason: String): TradingViewLoadState {
        if (!isLoading) return this
        return when (path) {
            TradingViewLoadPath.OFFICIAL_WIDGET -> TradingViewLoadState(
                path = TradingViewLoadPath.HOSTED_CHART,
                status = TradingViewLoadStatus.LOADING,
                message = "Official widget failed; trying hosted chart. $reason"
            )
            TradingViewLoadPath.HOSTED_CHART -> copy(
                status = TradingViewLoadStatus.FAILED,
                message = listOfNotNull(message, "Hosted chart failed: $reason").joinToString(" ")
            )
        }
    }

    /** A retry always starts cleanly on the official widget with the caller's current selection. */
    fun retry(): TradingViewLoadState = TradingViewLoadState()
}
