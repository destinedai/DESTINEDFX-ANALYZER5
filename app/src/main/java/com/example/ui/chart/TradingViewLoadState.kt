package com.example.ui.chart

/** The official Advanced Chart widget stays primary; the hosted widget page is tried once on failure. */
internal enum class TradingViewLoadPath {
    OFFICIAL_WIDGET,
    HOSTED_CHART
}

internal enum class TradingViewLoadStatus {
    CONNECTING,
    LOADING,
    CONNECTED,
    RECONNECTING,
    CONNECTION_LOST,
    DATA_STALE,
    DATA_UNAVAILABLE,
    DISPLAYED_UNVERIFIED,
    FAILED
}

/** Pure state transitions keep timeouts, callback failures, and retries deterministic and testable. */
internal data class TradingViewLoadState(
    val path: TradingViewLoadPath = TradingViewLoadPath.OFFICIAL_WIDGET,
    val status: TradingViewLoadStatus = TradingViewLoadStatus.LOADING,
    val message: String? = null
) {
    val isLoading: Boolean get() = status == TradingViewLoadStatus.CONNECTING ||
        status == TradingViewLoadStatus.LOADING || status == TradingViewLoadStatus.RECONNECTING
    val isTerminal: Boolean get() = status == TradingViewLoadStatus.FAILED ||
        status == TradingViewLoadStatus.DATA_UNAVAILABLE

    val isRetryable: Boolean get() = status == TradingViewLoadStatus.CONNECTION_LOST ||
        status == TradingViewLoadStatus.DATA_STALE || status == TradingViewLoadStatus.DATA_UNAVAILABLE ||
        status == TradingViewLoadStatus.FAILED

    fun connecting(): TradingViewLoadState = copy(status = TradingViewLoadStatus.CONNECTING, message = null)

    fun connected(evidence: String): TradingViewLoadState = copy(
        status = TradingViewLoadStatus.CONNECTED,
        message = evidence
    )

    fun reconnecting(reason: String = "Connection interrupted — reconnecting to market data..."): TradingViewLoadState =
        copy(status = TradingViewLoadStatus.RECONNECTING, message = reason)

    fun connectionLost(reason: String = "Connection interrupted — reconnecting to market data..."): TradingViewLoadState =
        copy(status = TradingViewLoadStatus.CONNECTION_LOST, message = reason)

    fun dataStale(reason: String): TradingViewLoadState =
        copy(status = TradingViewLoadStatus.DATA_STALE, message = reason)

    fun dataUnavailable(reason: String): TradingViewLoadState =
        copy(status = TradingViewLoadStatus.DATA_UNAVAILABLE, message = reason)

    /** Stop obscuring the embed, while explicitly not claiming that cross-origin candles rendered. */
    fun displayedUnverified(evidence: String): TradingViewLoadState {
        if (status == TradingViewLoadStatus.DISPLAYED_UNVERIFIED) return this
        return copy(status = TradingViewLoadStatus.DISPLAYED_UNVERIFIED, message = evidence)
    }

    fun fail(reason: String): TradingViewLoadState {
        if (status == TradingViewLoadStatus.DISPLAYED_UNVERIFIED) return this
        if (status == TradingViewLoadStatus.FAILED) return this
        return when (path) {
            TradingViewLoadPath.OFFICIAL_WIDGET -> TradingViewLoadState(
                path = TradingViewLoadPath.HOSTED_CHART,
                status = TradingViewLoadStatus.LOADING,
                message = "Official widget failed; trying hosted chart. $reason"
            )
            TradingViewLoadPath.HOSTED_CHART -> copy(
                status = TradingViewLoadStatus.FAILED,
                message = listOfNotNull(message, reason).joinToString(" ")
            )
        }
    }

    /** A retry always starts cleanly on the official widget with the caller's current selection. */
    fun retry(): TradingViewLoadState = TradingViewLoadState(
        path = TradingViewLoadPath.OFFICIAL_WIDGET,
        status = TradingViewLoadStatus.LOADING,
        message = null
    )
}
