package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.trade.TradeRepository
import com.example.domain.model.MarketTicker
import com.example.domain.repository.MT5Repository
import com.example.domain.usecase.GetMarketDataUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DashboardUiState(
    val accountBalance: Double  = 0.0, val totalProfit: Double = 0.0,
    val winRate: Double?        = null, val isLoading: Boolean  = false,
    val selectedSymbol: String  = "EUR/USD"
)

class DashboardViewModel(
    private val market:  GetMarketDataUseCase,
    private val mt5:     MT5Repository,
    private val trades:  TradeRepository
) : ViewModel() {

    private val _s = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _s.asStateFlow()

    val tickers: StateFlow<Map<String, MarketTicker>> = market.getLiveTickers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val tradeHistory = trades.allTrades
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mt5.getAccountBalance().onSuccess { _s.value = _s.value.copy(accountBalance=it) }
        }
        viewModelScope.launch {
            trades.allTrades.collect { list ->
                val closed = list.filter { it.status.equals("CLOSED", ignoreCase = true) }
                val wr = if (closed.size < 30) null else closed.count { it.plAmount > 0 }.toDouble() / closed.size * 100.0
                _s.value = _s.value.copy(totalProfit = closed.sumOf { it.plAmount }, winRate = wr)
            }
        }
    }

    fun selectSymbol(sym: String) { _s.value = _s.value.copy(selectedSymbol=sym) }
}
