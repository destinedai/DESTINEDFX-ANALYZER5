package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.engine.QuantitativeEngine
import com.example.domain.model.RiskProfile
import com.example.domain.repository.RiskRepository
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RiskUiState(
    val maxDrawdownLimit: Double  = 3.0, val currentDrawdown: Double  = 0.0,
    val fixedRiskPerTrade: Double = 1.0, val isNfpShieldActive: Boolean = true,
    val themeMode: String         = "System"
)

class RiskViewModel(
    private val risk: RiskRepository, private val secrets: SecretsManager
) : ViewModel() {

    private val _s = MutableStateFlow(RiskUiState())
    val state: StateFlow<RiskUiState> = _s

    val themeMode       = MutableStateFlow("System")
    val telegramEnabled = MutableStateFlow(false)
    val whatsappEnabled = MutableStateFlow(false)
    val nfpShieldActive = MutableStateFlow(true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = risk.getRiskProfile()
                _s.value = RiskUiState(
                    maxDrawdownLimit=p.maxDailyDrawdownPercentage,
                    currentDrawdown=p.currentDailyDrawdown,
                    fixedRiskPerTrade=p.fixedRiskPercentage
                )
                telegramEnabled.value = secrets.getTelegramToken().isNotBlank()
                whatsappEnabled.value = secrets.getWhatsAppApiKey().isNotBlank()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("RiskViewModel", "Failed to load risk profile: ${e.message}")
            }
        }
    }

    fun updateRiskPercent(v: Double) { _s.value = _s.value.copy(fixedRiskPerTrade=v.coerceIn(0.1,1.0)); persist() }
    fun updateMaxDrawdown(v: Double) { _s.value = _s.value.copy(maxDrawdownLimit=v.coerceIn(1.0,10.0)); persist() }
    fun toggleNfpShield() { nfpShieldActive.value=!nfpShieldActive.value; _s.value=_s.value.copy(isNfpShieldActive=nfpShieldActive.value) }
    fun setTheme(m: String) { themeMode.value=m; _s.value=_s.value.copy(themeMode=m) }
    fun calculateLotSize(bal: Double, entry: Double, sl: Double, rPct: Double, symbol: String = "") =
        QuantitativeEngine.calculateDynamicLotSize(bal, entry, sl, rPct, symbol = symbol)

    fun saveTelegramCredentials(token: String, chatId: String) {
        secrets.saveTelegramToken(token); secrets.saveTelegramChatId(chatId)
        telegramEnabled.value=token.isNotBlank()
    }

    fun saveWhatsAppCredentials(apiKey: String, phone: String) {
        secrets.saveWhatsAppApiKey(apiKey); secrets.saveWhatsAppPhone(phone)
        whatsappEnabled.value=apiKey.isNotBlank()
    }

    private fun persist() = viewModelScope.launch(Dispatchers.IO) {
        // Risk controls are only a subset of the persisted profile. Preserve the configured
        // account balance, live drawdown measurements, minimum RR, and other fields rather
        // than replacing them with placeholders whenever a slider changes.
        val ui = _s.value
        val current = risk.getRiskProfile()
        risk.updateRiskProfile(current.copy(
            fixedRiskPercentage = ui.fixedRiskPerTrade.coerceIn(0.1, 1.0),
            maxDailyDrawdownPercentage = ui.maxDrawdownLimit.coerceIn(1.0, 10.0),
            maxWeeklyDrawdownPercentage = ui.maxDrawdownLimit.coerceIn(1.0, 10.0) * 2.0,
            isTradingHalted = ui.currentDrawdown >= ui.maxDrawdownLimit
        ))
    }
}
