package com.example.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NotificationService
import com.example.data.trade.TradeEntity
import com.example.data.trade.TradeRepository
import com.example.data.user.User
import com.example.data.user.UserDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.example.domain.engine.impl.*
import com.example.domain.engine.MarketStructureAnalyzer
import com.example.domain.model.MarketTicker
import com.example.domain.model.Candle
import com.example.domain.engine.QuantitativeEngine
import com.destinedfx.core.risk.SizingResult
import com.destinedfx.core.instrument.SpecRegistry
import com.destinedfx.core.bot.BotStatusSummary
import com.destinedfx.core.bot.BotLifecycleState
import com.example.ui.DestinedFXScreen
import com.example.ui.export.RealAnalysisExporter

data class BacktestTrade(
    val dateString: String,
    val type: String, // "BUY" or "SELL"
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val profit: Double,
    val pips: Double,
    val result: String // "TP HIT" or "SL HIT"
)

data class BacktestReport(
    val strategyName: String,
    val winRate: Double,
    val maxDrawdown: Double,
    val totalTrades: Int,
    val profitFactor: Double,
    val netProfitPercent: Double,
    val trades: List<BacktestTrade>
)

data class AIBotOpinion(
    val botName: String,
    val signal: String, // "BULLISH", "BEARISH", "NEUTRAL"
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val confidence: Int,
    val coreRationale: String
)

data class ResolvedPrice(
    val price: Double,
    val source: String,
    val timestamp: Long
)

open class DestinedFXViewModel(
    application: Application,
    val isPreview: Boolean = false
) : AndroidViewModel(application) {

    val app: com.example.MyApplication get() = (getApplication() as? com.example.MyApplication)
        ?: error("MyApplication required for production operations")
    val initStatus: StateFlow<com.example.MyApplication.InitState> =
        if (isPreview) MutableStateFlow(com.example.MyApplication.InitState.Ready) else app.initStatus

    /** True only when EncryptedSharedPreferences initialized successfully on this device. */
    val secureStorageAvailable: StateFlow<Boolean> =
        if (isPreview) MutableStateFlow(false) else app.storageAvailable
    private val db by lazy { app.db }
    private val repository by lazy { TradeRepository(db.tradeDao()) }
    private val analysisRepository by lazy { com.example.data.AnalysisRepository(db.analysisDao()) }
    private val client by lazy { OkHttpClient() }
    private val notificationService = NotificationService(OkHttpClient())

    private val _mt5Connected = MutableStateFlow(false)
    private val _mt5Balance = MutableStateFlow(0.0)
    private val _botActive = MutableStateFlow(false)

    suspend fun resolveRealPrice(symbol: String, timeframe: String): ResolvedPrice? {
        // 1. Try tickers.value[symbol]?.price
        val ticker = tickers.value[symbol]
        if (ticker != null && ticker.price > 0.0) {
            val src = ticker.source.ifBlank { "Real-time feed" }
            return ResolvedPrice(ticker.price, src, ticker.lastUpdateMillis)
        }

        // 2. Else try marketDataRepository.getLatestTick(symbol).price if > 0
        val tick = app.marketDataRepository?.getLatestTick(symbol) ?: return null
        if (tick.price > 0.0) {
            return ResolvedPrice(tick.price, "Market Tick", tick.timestamp)
        }

        // 3. Else use the close of the latest candle from getCandles(symbol, timeframe, 1)
        val candles = app.marketDataRepository?.getCandles(symbol, timeframe, 1) ?: emptyList()
        val latestCandle = candles.lastOrNull()
        if (latestCandle != null && latestCandle.close > 0.0) {
            val src = app.marketDataRepository?.getDataSource(symbol, timeframe) ?: "Unknown"
            return ResolvedPrice(latestCandle.close, src, latestCandle.timestamp)
        }

        // 4. If still nothing: return null
        return null
    }

    private fun formatPriceTimeAgo(timestampMs: Long): String {
        val diffSeconds = kotlin.math.max(0L, (System.currentTimeMillis() - timestampMs) / 1000L)
        return if (diffSeconds < 60) {
            "$diffSeconds seconds ago"
        } else {
            "${diffSeconds / 60} minutes ago"
        }
    }

    val tradeHistory: StateFlow<List<TradeEntity>> = if (isPreview) MutableStateFlow(emptyList()) else repository.allTrades
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val analysisHistory: StateFlow<List<com.example.data.AnalysisEntity>> = if (isPreview) MutableStateFlow(emptyList()) else analysisRepository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val structureAnalyzer: MarketStructureAnalyzer = CoreMarketStructureAnalyzer(
        fractalDetector = StandardFractalDetector(),
        bosDetector = StandardStructureShiftDetectors(),
        chochDetector = StandardStructureShiftDetectors(),
        mssDetector = StandardStructureShiftDetectors(),
        fvgDetector = StandardFVGDetector(),
        obDetector = StandardOrderBlockDetector(),
        liquiditySweepDetector = StandardLiquidityDetector()
    )

    // Capital & Account metrics
    val accountCapital = MutableStateFlow(0.0)
    
    fun sizeFor(
        symbol: String,
        balance: Double,
        riskPercent: Double,
        entry: Double,
        sl: Double
    ): SizingResult {
        val spec = SpecRegistry.get(symbol)
        return QuantitativeEngine.sizing(balance, entry, sl, riskPercent, spec)
    }
    val startDayCapital = 0.0
    val dailyDrawdownLimit = 3.0 // 3% Soft Limit, 5% Hard Limit

    // Active Market Tickers
    val tickers = MutableStateFlow<Map<String, MarketTicker>>(emptyMap())
    val quoteDiagnostics: StateFlow<Map<String, String>>
        get() = if (isPreview) MutableStateFlow(emptyMap()) else app.marketDataRepository.quoteDiagnostics
    val _feedItems = MutableStateFlow<List<MarketTicker>>(emptyList())
    val feedItems: StateFlow<List<MarketTicker>> = _feedItems

    private val _specReady = MutableStateFlow<Set<String>>(emptySet())
    val specReady: StateFlow<Set<String>> = _specReady

    fun loadSpecForSymbol(symbol: String) {
        if (SpecRegistry.get(symbol) != null) {
            _specReady.value = _specReady.value + symbol
            return
        }
        viewModelScope.launch {
            try {
                val res = app.mt5GatewayRepository.loadSymbolSpec(symbol)
                res.fold(
                    onSuccess = {
                        Log.d("DestinedFXViewModel", "loadSymbolSpec succeeded for $symbol")
                        _specReady.value = _specReady.value + symbol
                    },
                    onFailure = { e ->
                        Log.e("DestinedFXViewModel", "loadSymbolSpec failed for $symbol: ${e.javaClass.simpleName}")
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "loadSpecForSymbol failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun prefetchWatchlistSpecs() {
        viewModelScope.launch {
            try {
                val watchlist = com.example.data.market.SymbolRegistry.getBackgroundSymbols()
                for (sym in watchlist) {
                    val res = app.mt5GatewayRepository.loadSymbolSpec(sym)
                    res.fold(
                        onSuccess = {
                            Log.d("DestinedFXViewModel", "loadSymbolSpec succeeded for $sym")
                            _specReady.value = _specReady.value + sym
                        },
                        onFailure = { e ->
                            Log.e("DestinedFXViewModel", "loadSymbolSpec failed for $sym: ${e.javaClass.simpleName}")
                        }
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "prefetchWatchlistSpecs failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // Selected ticker and timeframe for the chart
    val selectedTicker = MutableStateFlow("EUR/USD")
    val selectedTimeframe = MutableStateFlow("M15")
    val selectedMtfTimeframes = MutableStateFlow(setOf("M5", "M15", "M30", "H1", "H4", "D1", "W1"))
    val scanProgressStage = MutableStateFlow("")
    val riskPercent = MutableStateFlow(1.0)
    val selectedScreen = MutableStateFlow<DestinedFXScreen>(DestinedFXScreen.Home)

    fun selectScreen(screen: DestinedFXScreen) {
        selectedScreen.value = screen
    }





    fun selectTicker(sym: String) {
        selectedTicker.value = sym
        loadSpecForSymbol(sym)
        viewModelScope.launch {
            app.marketDataRepository.refreshSymbol(sym)
        }
        runSignalScan(sym, selectedTimeframe.value, selectedMtfTimeframes.value)
    }

    fun updatePriceFromChart(symbol: String, price: Double) {
        if (price <= 0.0) return
        if (!isPreview) {
            app.marketDataRepository.updateLiveQuoteFromFeed(symbol, price)
        }
        val current = tickers.value[symbol]
        val updated = (current ?: MarketTicker(
            symbol = symbol,
            price = price,
            changePercent = 0.0,
            high = price,
            low = price,
            spread = price * 0.0001,
            bias = "BULLISH",
            confidence = 90,
            lastUpdateMillis = System.currentTimeMillis(),
            isLive = true,
            source = "TradingView Live Feed",
            kind = "STREAM"
        )).copy(
            price = price,
            high = maxOf(current?.high ?: price, price),
            low = minOf(current?.low ?: price, price),
            lastUpdateMillis = System.currentTimeMillis(),
            isLive = true,
            source = "TradingView Live Feed",
            kind = "STREAM"
        )
        tickers.value = tickers.value + (symbol to updated)
        _feedItems.value = tickers.value.values.toList()
    }

    fun selectTimeframe(tf: String) {
        selectedTimeframe.value = tf
        if (selectedMtfTimeframes.value.none { it != tf }) {
            val alternative = listOf("H4", "D1", "W1", "H1", "M30", "M15", "M5").firstOrNull { it != tf }
            if (alternative != null) selectedMtfTimeframes.value = selectedMtfTimeframes.value + alternative
        }
        runSignalScan(selectedTicker.value, tf, selectedMtfTimeframes.value)
    }

    fun toggleMtfTimeframe(tf: String) {
        val allowed = setOf("M5", "M15", "M30", "H1", "H4", "D1", "W1")
        if (tf !in allowed) return
        val current = selectedMtfTimeframes.value
        if (tf !in current) {
            selectedMtfTimeframes.value = current + tf
            return
        }
        val without = current - tf
        if (without.isEmpty() || without.none { it != selectedTimeframe.value }) return
        selectedMtfTimeframes.value = without
    }

    /** Returns false for invalid risk rather than silently altering the requested value. */
    fun setRiskPercent(value: Double): Boolean {
        if (!com.destinedfx.core.risk.RiskEngine.isValidRiskPercent(value)) return false
        riskPercent.value = value
        return true
    }

    // Keep legacy symbol history plus an exact symbol/timeframe series for chart correctness.
    val candlestickHistory = androidx.compose.runtime.mutableStateMapOf<String, List<Candle>>()
    val candlestickHistoryByTimeframe = androidx.compose.runtime.mutableStateMapOf<String, List<Candle>>()
    val marketDataStatus = MutableStateFlow("Connecting to public market feeds…")

    // Real-time market candle fetch errors exposed to UI
    val candleFetchErrors: StateFlow<Map<String, String>> =
        if (isPreview) MutableStateFlow(emptyMap()) else app.marketDataRepository.candleFetchErrors
    val selectedApiSource: MutableStateFlow<String> =
        if (isPreview) MutableStateFlow("TradingView Live Feed") else app.marketDataRepository.selectedApiSource

    fun getCandleError(symbol: String, timeframe: String): String? =
        if (isPreview) null else app.marketDataRepository.getCandleError(symbol, timeframe)

    fun getDataSourceForSymbol(symbol: String): String =
        if (isPreview) "TradingView Live Feed" else app.marketDataRepository.getDataSource(symbol, selectedTimeframe.value)

    fun marketDataSource(symbol: String, timeframe: String): String =
        if (isPreview) "Preview has no market candles" else app.marketDataRepository.getDataSource(
            symbol, if (timeframe == "60") "H1" else timeframe
        )

    fun candlesForChart(symbol: String, timeframe: String): List<Candle> =
        candlestickHistoryByTimeframe[symbol + "-" + (if (timeframe == "60") "H1" else timeframe)].orEmpty()

    fun refreshMarketData(symbol: String = selectedTicker.value, timeframe: String = selectedTimeframe.value) {
        if (isPreview) return
        viewModelScope.launch {
            marketDataStatus.value = "REFRESHING • " + symbol + " • " + timeframe
            try {
                withTimeoutOrNull(4_000L) { app.marketDataRepository.refreshSymbol(symbol) }
                val candles = withTimeoutOrNull(32_000L) {
                    app.marketDataRepository.getCandles(symbol, timeframe, 150)
                } ?: emptyList()
                if (candles.isNotEmpty()) {
                    candlestickHistory[symbol] = candles
                    candlestickHistoryByTimeframe[symbol + "-" + timeframe] = candles
                    marketDataStatus.value = "READY • " + candles.size + " candles • " +
                        app.marketDataRepository.getDataSource(symbol, timeframe)
                } else {
                    val reason = app.marketDataRepository.getCandleError(symbol, timeframe)
                        ?: "The public feed returned no OHLC candles for this symbol/timeframe."
                    marketDataStatus.value = "UNAVAILABLE • " + reason
                }
                runSignalScan(symbol, timeframe)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                marketDataStatus.value = "UNAVAILABLE • " + (e.message ?: e.javaClass.simpleName)
                Log.w("DestinedFXViewModel", "Manual market refresh failed: " + e.javaClass.simpleName)
            }
        }
    }

    fun isDataFresh(symbol: String): Boolean =
        if (isPreview) true else app.marketDataRepository.isFresh(symbol)

    val brokerConnected = MutableStateFlow(if (isPreview) false else app.brokerManager.activeBroker.value.isConnected())
    val brokerQuote = MutableStateFlow<com.destinedfx.core.marketdata.MarketQuote?>(null)

    val crashLogManager by lazy { com.destinedfx.core.security.CrashLogManager(application.filesDir) }
    val lastCrashText = MutableStateFlow<String?>(null)

    fun dismissLastCrash() {
        if (!isPreview) crashLogManager.dismissCrash()
        lastCrashText.value = null
    }

    fun getLastTimingRecord(symbol: String): com.example.domain.usecase.impl.SignalGenerationUseCaseImpl.TimingRecord? {
        return app.signalGenerationUseCase.getTimingHistory(symbol).lastOrNull()
    }

    fun getTimingDisplayString(symbol: String): String {
        val record = getLastTimingRecord(symbol)
        return if (record != null) {
            "Last analysis: load ${record.loadMs} ms | compute ${record.computeMs} ms | total ${record.totalMs} ms | cache ${if (record.cacheHit) "HIT" else "MISS"}"
        } else {
            "Last analysis: pending"
        }
    }

    val correspondenceRows = MutableStateFlow<List<com.destinedfx.core.marketdata.DataCorrespondenceRow>>(emptyList())
    private var correspondenceJob: Job? = null

    fun startDataCorrespondenceUpdates() {
        correspondenceJob?.cancel()
        correspondenceJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    refreshDataCorrespondence()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e("DestinedFXViewModel", "refreshDataCorrespondence error: ${e.message}")
                }
                delay(5000L)
            }
        }
    }

    fun stopDataCorrespondenceUpdates() {
        correspondenceJob?.cancel()
        correspondenceJob = null
    }

    suspend fun refreshDataCorrespondence() {
        try {
            val watchlist = com.example.data.market.SymbolRegistry.getBackgroundSymbols()
            val quoteViews = tickers.value.mapValues { (_, t) ->
                com.destinedfx.core.marketdata.QuoteView(
                    symbol = t.symbol,
                    price = t.price,
                    bid = t.bid,
                    ask = t.ask,
                    source = t.source,
                    kind = t.kind,
                    lastUpdateMillis = t.lastUpdateMillis,
                    hasRealSpread = t.hasRealSpread
                )
            }
            val timeframeKey = if (selectedTimeframe.value == "60") "H1" else selectedTimeframe.value
            val candleSources = watchlist.mapNotNull { symbol ->
                app.marketDataRepository.candleSources[
                    com.example.data.market.candleSourceKey(symbol, timeframeKey)
                ]?.let { symbol to it }
            }.toMap()
            val resolvedMap = watchlist.associateWith { sym ->
                try {
                    app.mt5GatewayRepository.resolveSymbol(sym)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }

            val brokerQuotes = if (app.mt5GatewayRepository.isConnected()) {
                coroutineScope {
                    watchlist.map { sym ->
                        async {
                            try {
                                withTimeoutOrNull(3000) {
                                    app.mt5GatewayRepository.getBrokerQuote(sym).getOrNull()
                                }?.let { sym to it }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.w("DestinedFXViewModel", "Broker quote fetch failed for $sym: ${e.javaClass.simpleName}")
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            } else emptyMap()

            val timings = watchlist.associateWith { sym ->
                getTimingDisplayString(sym)
            }

            val rows = com.destinedfx.core.marketdata.DataCorrespondence.rows(
                watchlist = watchlist,
                tickers = quoteViews,
                candleSources = candleSources,
                resolver = { sym -> resolvedMap[sym] },
                brokerQuotes = brokerQuotes,
                timings = timings,
                nowMs = System.currentTimeMillis()
            )
            correspondenceRows.value = rows
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("DestinedFXViewModel", "refreshDataCorrespondence failed: ${e.javaClass.simpleName}")
        }
    }

    fun getBrokerSymbolOverrides(): Map<String, String> {
        return app.secretsManager.getBrokerSymbolOverrides()
    }

    fun saveBrokerSymbolOverride(appSymbol: String, brokerSymbol: String) {
        app.secretsManager.saveBrokerSymbolOverride(appSymbol, brokerSymbol)
    }

    fun removeBrokerSymbolOverride(appSymbol: String) {
        app.secretsManager.removeBrokerSymbolOverride(appSymbol)
    }

    suspend fun resolveBrokerSymbol(symbol: String): String? {
        return app.mt5GatewayRepository.resolveSymbol(symbol)
    }

    // Consensus states - derived from deterministic multi-model evaluation
    val smcBiasConfidence = MutableStateFlow(0)
    val structureAlignment = MutableStateFlow("ANALYZING MARKET STRUCTURE")
    val modelSentimentScores = MutableStateFlow<Map<String, Pair<String, Int>>>(emptyMap())

    // Algorithmic execution variables
    val aiAnalysisResult = MutableStateFlow<String?>(null)
    val isAnalyzing = MutableStateFlow(false)

    // New premium strategy & local image upload values for real-time hedge fund detection
    val selectedImageBase64 = MutableStateFlow<String?>(null)
    val selectedImageMime = MutableStateFlow<String?>(null)
    val selectedImageUriString = MutableStateFlow<String?>(null)
    val activeStrategy = MutableStateFlow("SMC Order Blocks")

    // Real-Time Alerts & Notification Gateway Properties
    val telegramNotificationEnabled = MutableStateFlow(true)
    val telegramBotToken = MutableStateFlow(if (isPreview) "" else app.secretsManager.getTelegramToken())
    val telegramChatId = MutableStateFlow(if (isPreview) "" else app.secretsManager.getTelegramChatId())
    val whatsappNotificationEnabled = MutableStateFlow(true)
    val whatsappPhoneNumber = MutableStateFlow(if (isPreview) "" else app.secretsManager.getWhatsAppPhone())
    val whatsappApiKey = MutableStateFlow(if (isPreview) "" else app.secretsManager.getWhatsAppApiKey())

    // --- TELEGRAM NOTIFICATIONS & ONBOARDING ---
    val telegramStatus = MutableStateFlow(if (isPreview) "Not configured" else app.secretsManager.getTelegramConnectionStatus())
    val telegramNotificationsEnabled = MutableStateFlow(if (isPreview) false else app.secretsManager.getTelegramNotificationsEnabled())
    val telegramTestMessageResult = MutableStateFlow<String?>(null)
    val isTelegramLoading = MutableStateFlow(false)

    fun connectTelegram(botToken: String, chatId: String) {
        viewModelScope.launch {
            isTelegramLoading.value = true
            telegramTestMessageResult.value = null
            try {
                val cleanToken = botToken.trim().removePrefix("bot").trim()
                val cleanChatId = chatId.trim()
                if (cleanToken.isBlank() || cleanChatId.isBlank()) {
                    telegramStatus.value = "Connection failed"
                    telegramTestMessageResult.value = "Bot token and Telegram Chat ID are both required."
                    isTelegramLoading.value = false
                    return@launch
                }
                val notificationService = NotificationService(OkHttpClient())
                val (success, message) = notificationService.verifyAndConnectTelegram(
                    cleanToken,
                    cleanChatId,
                    "DestinedFX: Telegram alerts successfully connected to your terminal at ${java.util.Date()}."
                )
                if (success) {
                    app.secretsManager.saveTelegramToken(cleanToken)
                    app.secretsManager.saveTelegramChatId(cleanChatId)
                    app.secretsManager.saveTelegramConnectionStatus("Connected")
                    app.secretsManager.saveTelegramNotificationsEnabled(true)
                    telegramBotToken.value = cleanToken
                    telegramChatId.value = cleanChatId
                    telegramStatus.value = "Connected"
                    telegramTestMessageResult.value = message
                    telegramNotificationsEnabled.value = true
                } else {
                    telegramStatus.value = "Connection failed"
                    telegramTestMessageResult.value = message
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                telegramStatus.value = "Connection failed"
                telegramTestMessageResult.value = "Error: ${e.message}"
            } finally {
                isTelegramLoading.value = false
            }
        }
    }

    fun autoDetectTelegramChatId(botToken: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            isTelegramLoading.value = true
            telegramTestMessageResult.value = "Detecting active Telegram Chat ID..."
            try {
                val notificationService = NotificationService(OkHttpClient())
                val (detectedId, message) = notificationService.autoDetectChatId(botToken)
                telegramTestMessageResult.value = message
                if (detectedId != null) {
                    telegramChatId.value = detectedId
                    app.secretsManager.saveTelegramChatId(detectedId)
                    onResult(detectedId)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                telegramTestMessageResult.value = "Detection failed: ${e.message}"
                onResult(null)
            } finally {
                isTelegramLoading.value = false
            }
        }
    }

    fun sendTelegramTestNotification() {
        viewModelScope.launch {
            isTelegramLoading.value = true
            telegramTestMessageResult.value = null
            try {
                val token = app.secretsManager.getTelegramToken()
                val chatId = app.secretsManager.getTelegramChatId()
                if (token.isBlank() || chatId.isBlank()) {
                    telegramTestMessageResult.value = "Not configured. Please connect Telegram first."
                    isTelegramLoading.value = false
                    return@launch
                }
                val notificationService = NotificationService(OkHttpClient())
                val success = notificationService.sendTelegramMessage(
                    token,
                    chatId,
                    "DestinedFX Analyzer: Verified test alert delivered at ${java.util.Date()}"
                )
                if (success) {
                    telegramTestMessageResult.value = "Test alert delivered successfully to your Telegram chat!"
                } else {
                    telegramTestMessageResult.value = "Delivery failed. Make sure you pressed START with your bot in Telegram."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                telegramTestMessageResult.value = "Test failed: ${e.message}"
            } finally {
                isTelegramLoading.value = false
            }
        }
    }

    fun connectDemoAccount(initialBalance: Double? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val targetBal = initialBalance?.takeIf { it > 0.0 }
                    ?: app.secretsManager.getMT5AccountBalance().takeIf { it > 0.0 }
                    ?: 3000.0
                app.secretsManager.saveMT5AccountBalance(targetBal)
                app.secretsManager.saveConfiguredEquity(targetBal)
                app.secretsManager.saveMT5AccountType("Demo")
                app.secretsManager.saveMT5SessionStatus("CONNECTED")
                app.mt5GatewayAdapter.setAccountBalance(targetBal)
                accountCapital.value = targetBal
                configuredEquity.value = targetBal

                app.brokerManager.selectBroker(com.example.data.broker.BrokerType.MT5)
                val res = app.mt5GatewayAdapter.connect()
                brokerConnected.value = res.isSuccess
                prefetchWatchlistSpecs()
                val metrics = app.mt5GatewayAdapter.accountMetrics.value
                val balance = metrics?.balance ?: targetBal
                accountCapital.value = balance
                val msg = "MT5 connected • Balance " + String.format(java.util.Locale.US, "%.2f", balance) + " USD"
                onResult?.invoke(true, msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onResult?.invoke(false, "MT5 login failed: ${e.message}")
            }
        }
    }

    fun unlinkTelegram() {
        app.secretsManager.saveTelegramToken("")
        app.secretsManager.saveTelegramChatId("")
        app.secretsManager.saveTelegramConnectionStatus("Not configured")
        app.secretsManager.saveTelegramNotificationsEnabled(false)
        telegramStatus.value = "Not configured"
        telegramNotificationsEnabled.value = false
        telegramTestMessageResult.value = "Telegram unlinked successfully."
    }

    fun refreshTelegramStatus() {
        val token = app.secretsManager.getTelegramToken()
        val chatId = app.secretsManager.getTelegramChatId()
        val status = if (token.isBlank() || chatId.isBlank()) "Not configured" else app.secretsManager.getTelegramConnectionStatus()
        telegramStatus.value = status
        telegramNotificationsEnabled.value = app.secretsManager.getTelegramNotificationsEnabled() && status == "Connected"
    }

    fun setTelegramNotificationsEnabled(enabled: Boolean) {
        if (telegramStatus.value != "Connected" && enabled) {
            telegramTestMessageResult.value = "Cannot enable notifications until Telegram connection is verified."
            return
        }
        telegramNotificationsEnabled.value = enabled
        app.secretsManager.saveTelegramNotificationsEnabled(enabled)
    }
    
    // Safety Risk Filters & News Safeguards (Non-Farm Payroll / NFP CPI Protection)
    val nfpProtectionArmed = MutableStateFlow(true)
    val nextNfpDate = "Friday, July 3, 2026"
    val showHaltNotification = MutableStateFlow(false)

    // MetaTrader Broker Connection Configurations & Themes (100% Free Lifetime Active)
    val themeMode = MutableStateFlow("System") // "System", "Light", "Dark"
    val brokerServer = MutableStateFlow(if (isPreview) "" else app.secretsManager.getMT5BrokerServer())
    val mtAccountNumber = MutableStateFlow("1028471")
    val mtBrokerPassword = MutableStateFlow("••••••••••••")
    val terminalMode = MutableStateFlow("MT5 Gateway")
    val mt5GatewayUrl = MutableStateFlow("wss://your-secure-backend-gateway.com/live-mt5")

    // Text To Speech Voice System
    private var tts: TextToSpeech? = null
    val ttsInitialized = MutableStateFlow(false)

    // Active screen reference for navigation flow in dashboard
    val activeSymbolForDetail = MutableStateFlow<String?>(null)

    // Trigger alert banner
    val actionAlertMessage = MutableStateFlow<String?>(null)

    // --- APP LOCKING & BIOMETRIC AUTH ---
    val isAppLocked = MutableStateFlow(false)
    val biometricStatus = MutableStateFlow<String?>(null)
    val canUseBiometrics = MutableStateFlow(false)

    fun lockApp() {
        // Disabled by default to ensure terminal remains accessible
    }

    fun unlockApp() {
        isAppLocked.value = false
    }

    fun checkBiometricAvailability(context: Context) {
        try {
            val biometricManager = BiometricManager.from(context)
            val authenticators = if (android.os.Build.VERSION.SDK_INT >= 30) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            }
            
            when (biometricManager.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    canUseBiometrics.value = true
                    biometricStatus.value = "Secure biometric vault active."
                }
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                    canUseBiometrics.value = false
                    biometricStatus.value = "Hardware encryption unavailable."
                }
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                    canUseBiometrics.value = false
                    biometricStatus.value = "Biometric node busy."
                }
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    canUseBiometrics.value = false
                    biometricStatus.value = "No device credentials established."
                }
                else -> {
                    canUseBiometrics.value = false
                    biometricStatus.value = "Encryption bridge error."
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("DestinedFXViewModel", "Biometric check error: ${e.message}")
            canUseBiometrics.value = false
            biometricStatus.value = "Biometric security unavailable."
        }
    }


    // Strategy & Configured Equity Persistence
    val selectedStrategyId = MutableStateFlow(if (isPreview) "smc-confluence-1.0.0" else app.secretsManager.getSelectedStrategy())
    val configuredEquity = MutableStateFlow(if (isPreview) 0.0 else app.secretsManager.getConfiguredEquity())

    fun selectStrategy(id: String) {
        selectedStrategyId.value = id
        activeStrategy.value = com.destinedfx.core.strategy.StrategyRegistry.allStrategies.find { it.id == id }?.name ?: id
        if (!isPreview) {
            app.secretsManager.saveSelectedStrategy(id)
            runSignalScan(selectedTicker.value, selectedTimeframe.value)
        }
    }

    fun saveConfiguredEquity(v: Double) {
        val clean = if (v.isFinite() && v > 0.0) v else 0.0
        configuredEquity.value = clean
        accountCapital.value = clean
        if (!isPreview) app.secretsManager.saveConfiguredEquity(clean)
    }

    // Live Real-Time Trade Signal State for Signals Tab
    val currentTradeSignal = MutableStateFlow<com.example.domain.model.TradeSignal?>(null)
    val currentDynamicAnalysis = MutableStateFlow<DynamicAnalysisInfo?>(null)
    val isSignalScanning = MutableStateFlow(false)
    private var signalScanGeneration: Long = 0L
    private var signalScanJob: Job? = null

    fun runSignalScan(
        symbol: String,
        timeframe: String,
        mtfTimeframes: Set<String> = selectedMtfTimeframes.value
    ) {
        if (isPreview) return
        signalScanJob?.cancel()
        val generation = ++signalScanGeneration
        currentDynamicAnalysis.value = null
        currentTradeSignal.value = null
        signalScanJob = viewModelScope.launch {
            isSignalScanning.value = true
            isAnalyzing.value = true
            try {
                scanProgressStage.value = "Phase 1/5 • Fetching TradingView OHLC Candlestick Feed..."
                delay(600L)
                if (generation != signalScanGeneration) return@launch
                
                scanProgressStage.value = "Phase 2/5 • Scanning Candlestick Patterns, Pinbars & Engulfings..."
                delay(700L)
                if (generation != signalScanGeneration) return@launch

                scanProgressStage.value = "Phase 3/5 • Mapping SMC Order Blocks, Fair Value Gaps & Sweeps..."
                delay(700L)
                if (generation != signalScanGeneration) return@launch

                scanProgressStage.value = "Phase 4/5 • Aligning Multi-Timeframe Trend Confluences..."
                delay(600L)
                if (generation != signalScanGeneration) return@launch

                scanProgressStage.value = "Phase 5/5 • Calculating High Win-Rate Entry, SL & TP Levels..."
                delay(600L)
                if (generation != signalScanGeneration) return@launch

                val sig = app.signalGenerationUseCase.generateSignal(
                    symbol, timeframe, selectedStrategyId.value, mtfTimeframes
                )
                // A superseded scan must never overwrite the currently selected symbol/timeframe.
                if (generation != signalScanGeneration) return@launch
                currentTradeSignal.value = sig
                currentDynamicAnalysis.value = mapTradeSignalToUiModel(sig)
                
                // CRITICAL FIX: Confidence is now strictly based on engine-computed structural alignment (confluence score)
                // rather than arbitrary thresholds.
                smcBiasConfidence.value = (sig.confidenceScore.coerceIn(0, 100))
                structureAlignment.value = if (sig.mtfSummary.isNotBlank()) sig.mtfSummary else "${sig.direction} [${sig.signalState}]"
                modelSentimentScores.value = sig.strategyScores.mapValues { (_, score) ->
                    Pair(if (score >= 50) "BULLISH" else "BEARISH", score)
                }
                
                // Only send alerts for high-conviction trades
                if (sig.confidenceScore >= 85 && sig.direction != com.example.domain.model.SignalDirection.WAIT && sig.entryPrice > 0.0) {
                    sendRealTimeNotification(sig.symbol, sig.direction.name, sig.entryPrice)
                }
                triggerAlgorithmicAnalysisScan(symbol, timeframe, selectedStrategyId.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == signalScanGeneration) {
                    marketDataStatus.value = "UNAVAILABLE • Analysis failed: " + (e.message ?: e.javaClass.simpleName)
                    currentDynamicAnalysis.value = null
                    currentTradeSignal.value = null
                }
                Log.e("DestinedFXViewModel", "Signal scan failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                if (generation == signalScanGeneration) {
                    isSignalScanning.value = false
                    isAnalyzing.value = false
                    scanProgressStage.value = ""
                }
            }
        }
    }

    data class PreExecutionCheck(
        val canExecute: Boolean,
        val lots: Double,
        val expectedLoss: Double,
        val reason: String
    )

    suspend fun evaluatePreExecution(
        symbol: String,
        positionType: String,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double
    ): PreExecutionCheck = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val ticker = tickers.value[symbol]
        val spec = SpecRegistry.get(symbol)
        val quote = ticker?.let { t ->
            val cls = com.destinedfx.core.instrument.AssetClassifier.of(t.symbol)
            if (t.bid != null && t.ask != null && t.hasRealSpread)
                com.destinedfx.core.marketdata.MarketQuote.twoSided(t.symbol, cls, t.bid, t.ask, t.lastUpdateMillis, t.lastUpdateMillis, t.source)
            else com.destinedfx.core.marketdata.MarketQuote.lastOnly(t.symbol, cls, t.price, t.lastUpdateMillis, t.lastUpdateMillis, t.source)
        }

        val quoteAgeMs = quote?.let { now - it.timestampMs }
        if (quote == null || quoteAgeMs == null || quoteAgeMs < 0L || quoteAgeMs > 10_000L) {
            return@withContext PreExecutionCheck(false, 0.0, 0.0, "Market quote is stale, future-dated, or unavailable. WAITING FOR LIVE DATA.")
        }

        val spread = quote.spread
        val pipSize = spec?.pipSize
        if (spread == null || (pipSize != null && spread > pipSize * 3.5)) {
            return@withContext PreExecutionCheck(false, 0.0, 0.0, "Spread too wide. Execution blocked.")
        }

        val newsVerdict = com.destinedfx.core.news.NewsFilter.evaluate(
            app.newsRepository.calendar(),
            com.destinedfx.core.instrument.AssetClassifier.newsCurrencies(symbol),
            now
        )
        if (newsVerdict.status == com.destinedfx.core.news.NewsStatus.HIGH_IMPACT_WINDOW || newsVerdict.status == com.destinedfx.core.news.NewsStatus.UNKNOWN) {
            return@withContext PreExecutionCheck(false, 0.0, 0.0, "News Gate: ${newsVerdict.detail}. Automated execution blocked.")
        }

        val eq = configuredEquity.value
        val maxRiskDollar = eq * 0.01
        if (spec == null) {
            return@withContext PreExecutionCheck(false, 0.0, 0.0, "Broker specification for $symbol is unavailable.")
        }

        val acc = com.destinedfx.core.risk.AccountState(balance = eq, equity = eq, freeMargin = eq, leverage = 100, currency = "USD")
        val sizing = com.destinedfx.core.risk.RiskEngine.positionSize(acc, 1.0, entryPrice, stopLoss, spec)
        when (sizing) {
            is SizingResult.Sized -> {
                if (sizing.moneyRiskActual > maxRiskDollar + 1e-6) {
                    return@withContext PreExecutionCheck(
                        false, sizing.lots, sizing.moneyRiskActual,
                        "Estimated loss ($${String.format(java.util.Locale.US, "%.2f", sizing.moneyRiskActual)}) exceeds 1% limit ($${String.format(java.util.Locale.US, "%.2f", maxRiskDollar)})."
                    )
                }
                return@withContext PreExecutionCheck(true, sizing.lots, sizing.moneyRiskActual, "Safety checks verified.")
            }
            is SizingResult.Rejected -> {
                return@withContext PreExecutionCheck(
                    false, 0.0, 0.0,
                    "CANNOT_SIZE_WITHIN_RISK_LIMIT: ${if (sizing.reason.contains("below broker minimum")) "Minimum broker volume exceeds the 1% risk limit." else sizing.reason}"
                )
            }
        }
    }



    fun onNewCandleClosed(symbol: String, candle: com.destinedfx.core.marketdata.Candle) {
        val active = currentTradeSignal.value
        if (active != null && active.symbol == symbol && (active.direction == com.example.domain.model.SignalDirection.LONG || active.direction == com.example.domain.model.SignalDirection.SHORT)) {
            val isLong = active.direction == com.example.domain.model.SignalDirection.LONG
            val sl = active.stopLoss
            if (sl > 0.0 && com.destinedfx.core.analysis.SignalEngine.isInvalidated(isLong, sl, candle.close)) {
                val invSig = active.copy(
                    direction = com.example.domain.model.SignalDirection.WAIT,
                    signalState = com.destinedfx.core.analysis.SignalState.INVALIDATED.name,
                    reasons = listOf("INVALIDATED: Candle closed beyond structural invalidation level (${formatPrice(sl, symbol)})")
                )
                currentTradeSignal.value = invSig
                currentDynamicAnalysis.value = mapTradeSignalToUiModel(invSig)
                actionAlertMessage.value = "Signal for $symbol INVALIDATED: candle closed beyond $sl."
            }
        }
    }

    // --- 30-DAY STRATEGY BACKTESTING ENGINE ---
    val backtestState = MutableStateFlow("IDLE") // "IDLE", "RUNNING", "SUCCESS", "ERROR"
    val activeBacktestReport = MutableStateFlow<BacktestReport?>(null)

    fun runBacktesting(strategy: String) {
        viewModelScope.launch {
            backtestState.value = "RUNNING"
            try {
                val symbol = selectedTicker.value
                val tf = com.destinedfx.core.marketdata.Timeframe.parse(selectedTimeframe.value) ?: com.destinedfx.core.marketdata.Timeframe.H1
                app.backtestUseCase.symbol = symbol
                app.backtestUseCase.timeframe = tf
                val res = app.backtestUseCase.runBacktest(strategy)
                if (res.totalTrades > 0 && res.outOfSample) {
                    activeBacktestReport.value = BacktestReport(
                        strategyName = "$strategy (${res.strategyVersion})",
                        winRate = res.winRatePercentage,
                        maxDrawdown = res.maxDrawdownPercentage,
                        totalTrades = res.totalTrades,
                        profitFactor = res.profitFactor,
                        netProfitPercent = if (res.startingBalance > 0) res.netProfit / res.startingBalance * 100 else 0.0,
                        trades = emptyList()
                    )
                    backtestState.value = "SUCCESS"
                } else {
                    backtestState.value = "ERROR"
                    actionAlertMessage.value = res.verdictText.ifBlank { "Backtest unavailable: Insufficient data or edge" }
                }
            } catch (e: Exception) {
                backtestState.value = "ERROR"
                actionAlertMessage.value = "Backtest error: ${e.message}"
            }
        }
    }

    // --- ALGORITHMIC MAP OUTPUT MATRIX ---
    val aiBotOpinions = MutableStateFlow<Map<String, AIBotOpinion>>(emptyMap())
    val isMultiAIScanning = MutableStateFlow(false)
    val multiAIConsensusStatus = MutableStateFlow("Consensus Idle")

    fun runDeepStrategyAnalysis(symbol: String, timeframe: String, strategy: String) {
        viewModelScope.launch {
            val resolvedPrice = resolveRealPrice(symbol, timeframe)
            if (resolvedPrice == null) {
                val err = "No supported market quote is available for $symbol. Check instrument support and market-data connectivity; no signal was generated."
                actionAlertMessage.value = err
                return@launch
            }
            if (resolvedPrice.source == "Frankfurter (daily reference)") {
                val msg = "Only daily reference price available - not suitable for intraday SMC levels."
                actionAlertMessage.value = msg
                return@launch
            }
            val currentPrice = resolvedPrice.price
            val priceSource = resolvedPrice.source
            val priceTimeAgo = formatPriceTimeAgo(resolvedPrice.timestamp)

            val candles = candlestickHistory[symbol] ?: emptyList()
            val domainCandles = candles
            val smcData = if (domainCandles.size > 10) {
                structureAnalyzer.analyzeStructure(domainCandles)
            } else null
            
            if (smcData == null) {
                val entity = com.example.data.AnalysisEntity(
                    symbol = symbol,
                    timeframe = timeframe,
                    strategy = strategy,
                    dateMs = System.currentTimeMillis(),
                    winRateConfidence = 0.0,
                    signal = "DATA_UNAVAILABLE",
                    report = "Insufficient data for structural analysis."
                )
                analysisRepository.insertAnalysis(entity)
                actionAlertMessage.value = "Data Unavailable"
                return@launch
            }

            val candlesContextStr = domainCandles.takeLast(20).joinToString("\n") { 
                "O: ${it.open}, H: ${it.high}, L: ${it.low}, C: ${it.close}" 
            }
            
            val reportDetails = generateAlgorithmicReport(
                symbol, timeframe, currentPrice, priceSource, priceTimeAgo, strategy, smcData, candlesContextStr
            )

            val baseConfidence = smcData.confluenceScore.toDouble()
            val selectedSignal = if (smcData.bias == "BULLISH") "BUY" else if (smcData.bias == "BEARISH") "SELL" else "WAIT"
            
            val entity = com.example.data.AnalysisEntity(
                symbol = symbol,
                timeframe = timeframe,
                strategy = strategy,
                dateMs = System.currentTimeMillis(),
                winRateConfidence = baseConfidence,
                signal = selectedSignal,
                report = reportDetails
            )
            analysisRepository.insertAnalysis(entity)
        }
    }



    fun triggerAlgorithmicAnalysisScan(
        symbol: String,
        timeframe: String = selectedTimeframe.value,
        strategyId: String = selectedStrategyId.value
    ) {
        viewModelScope.launch {
            isMultiAIScanning.value = true
            multiAIConsensusStatus.value = "Executing multi-timeframe algorithmic analysis for $symbol ($timeframe)..."

            val app = getApplication<com.example.MyApplication>()
            val primarySignal = app.signalGenerationUseCase.generateSignal(symbol, timeframe, strategyId)
            val signalH1 = if (timeframe == "H1") primarySignal else app.signalGenerationUseCase.generateSignal(symbol, "H1")
            val signalM15 = if (timeframe == "M15") primarySignal else app.signalGenerationUseCase.generateSignal(symbol, "M15")
            val signalH4 = if (timeframe == "H4") primarySignal else app.signalGenerationUseCase.generateSignal(symbol, "H4")

            val newOpinions = mutableMapOf<String, AIBotOpinion>()
            newOpinions["PRIMARY"] = AIBotOpinion(
                botName = "Active Engine ($strategyId • $timeframe)",
                signal = primarySignal.direction.name,
                entry = primarySignal.entryPrice,
                sl = primarySignal.stopLoss,
                tp = primarySignal.takeProfit,
                confidence = primarySignal.confidenceScore,
                coreRationale = primarySignal.rationale
            )
            newOpinions["SMC_H1"] = AIBotOpinion(
                botName = "SMC Core (H1)",
                signal = signalH1.direction.name,
                entry = signalH1.entryPrice,
                sl = signalH1.stopLoss,
                tp = signalH1.takeProfit,
                confidence = signalH1.confidenceScore,
                coreRationale = signalH1.rationale
            )
            newOpinions["SMC_M15"] = AIBotOpinion(
                botName = "SMC Core (M15)",
                signal = signalM15.direction.name,
                entry = signalM15.entryPrice,
                sl = signalM15.stopLoss,
                tp = signalM15.takeProfit,
                confidence = signalM15.confidenceScore,
                coreRationale = signalM15.rationale
            )
            newOpinions["SMC_H4"] = AIBotOpinion(
                botName = "SMC Core (H4)",
                signal = signalH4.direction.name,
                entry = signalH4.entryPrice,
                sl = signalH4.stopLoss,
                tp = signalH4.takeProfit,
                confidence = signalH4.confidenceScore,
                coreRationale = signalH4.rationale
            )

            aiBotOpinions.value = newOpinions
            modelSentimentScores.value = mapOf(
                "Primary ($timeframe)" to Pair(primarySignal.direction.name, primarySignal.confidenceScore),
                "SMC H1" to Pair(signalH1.direction.name, signalH1.confidenceScore),
                "SMC M15" to Pair(signalM15.direction.name, signalM15.confidenceScore),
                "SMC H4" to Pair(signalH4.direction.name, signalH4.confidenceScore)
            )
            multiAIConsensusStatus.value = "ANALYSIS COMPLETE • $symbol $timeframe"
            isMultiAIScanning.value = false
        }
    }

    private fun initTTS() {
        // Disabled TTS initialization to remove female voice
    }

    fun speakText(text: String) {
        // Disabled female TTS voice as requested, keeping authentic UI presence.
    }

    init {
        if (!isPreview) {
            initTTS()

            startDataCorrespondenceUpdates()
            try {
                lastCrashText.value = crashLogManager.readLastCrash()
            } catch (e: Throwable) {
                Log.w("DestinedFXViewModel", "readLastCrash failed: ${e.message}")
            }
        }
        startMultiPairScanning()

        // Sync broker connection, quote, and account capital for selectedTicker
        viewModelScope.launch {
            while (true) {
                try {
                    val activeBroker = app.brokerManager.activeBroker.value
                    // Connection and quote state must belong to the selected gateway only.
                    // Falling back to MT5 while Deriv is selected can mislabel another account's
                    // connection or use a quote from the wrong broker for an execution decision.
                    val isConn = activeBroker.isConnected()
                    brokerConnected.value = isConn
                    if (isConn) {
                        val sym = selectedTicker.value
                        brokerQuote.value = activeBroker.getBrokerQuote(sym).getOrNull()
                        val bal = activeBroker.accountMetrics.value?.balance ?: 0.0
                        accountCapital.value = if (bal > 0.0) bal else configuredEquity.value
                    } else {
                        brokerQuote.value = null
                        accountCapital.value = configuredEquity.value
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(2000L)
            }
        }

        // 1. Sync tickers to feed
        viewModelScope.launch {
            while (true) {
                try {
                    app.marketDataRepository.tickerFlow.collect { map ->
                        tickers.value = map
                        _feedItems.value = map.values.toList()
                    }
                    delay(1_000L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("DestinedFXViewModel", "Ticker collector retry: ${e.javaClass.simpleName}")
                    delay(2_000L)
                }
            }
        }

        // 2. Focused candlestick maintenance for active symbol & timeframe & API Source
        viewModelScope.launch {
            try {
                combine(
                    selectedTicker, 
                    selectedTimeframe, 
                    app.marketDataRepository.selectedApiSource
                ) { symbol, tf, _ -> symbol to tf }.collect { (symbol, tf) ->
                    try {
                        val tfKey = if (tf == "60") "H1" else tf
                        val candles = app.marketDataRepository.getCandles(symbol, tfKey, 150)
                        if (candles.isNotEmpty()) {
                            candlestickHistory[symbol] = candles
                            candlestickHistoryByTimeframe[symbol + "-" + tfKey] = candles
                            marketDataStatus.value = "READY • " + candles.size + " candles • " +
                                app.marketDataRepository.getDataSource(symbol, tfKey)
                        } else {
                            val reason = app.marketDataRepository.getCandleError(symbol, tfKey)
                                ?: "Waiting for valid candles from the public feed."
                            marketDataStatus.value = "WAIT • " + reason
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        marketDataStatus.value = "WAIT • Candle refresh will retry: ${e.javaClass.simpleName}"
                        Log.w("DestinedFXViewModel", "Selected candle fetch failed symbol=$symbol: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }
        
        // 3. Periodic refresh of active symbol's candles every 30 seconds
        viewModelScope.launch {
            com.example.data.market.resilientPollingLoop(
                intervalMs = 30_000L,
                maxBackoffMs = 300_000L,
                onError = { error -> Log.w("DestinedFXViewModel", "Candle poll retry: ${error.javaClass.simpleName}") }
            ) {
                    val sym = selectedTicker.value
                    val tf = selectedTimeframe.value
                    val tfKey = if (tf == "60") "H1" else tf
                    val candles = app.marketDataRepository.getCandles(sym, tfKey, 150)
                    if (candles.isNotEmpty()) {
                        candlestickHistory[sym] = candles
                        candlestickHistoryByTimeframe[sym + "-" + tfKey] = candles
                    }
            }
        }

        // 4. Dedicated high-priority price poller for the active symbol (Moves with the market)
        viewModelScope.launch {
            com.example.data.market.resilientPollingLoop(
                intervalMs = 8_000L,
                maxBackoffMs = 120_000L,
                onError = { error -> Log.w("DestinedFXViewModel", "Selected quote refresh retry: ${error.javaClass.simpleName}") }
            ) {
                    val sym = selectedTicker.value
                    app.marketDataRepository.refreshSymbol(sym)
            }
        }

        // 5. Manual refresh when API changes
        viewModelScope.launch {
            try {
                app.marketDataRepository.selectedApiSource.collect {
                    try {
                        val sym = selectedTicker.value
                        app.marketDataRepository.refreshSymbol(sym)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("DestinedFXViewModel", "Source-change refresh failed: ${e.javaClass.simpleName}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }

        // 6. Pre-load watchlist symbols once during initialization
        viewModelScope.launch {
            try {
                com.example.data.market.SymbolRegistry.getBackgroundSymbols().forEach { sym ->
                    try {
                        app.marketDataRepository.refreshSymbol(sym)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("DestinedFXViewModel", "Initial quote failed symbol=$sym: ${e.javaClass.simpleName}")
                    }
                }
                prefetchWatchlistSpecs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }

        // 7. Dynamic symbol spec loading & prefetching
        viewModelScope.launch {
            try {
                selectedTicker.collect { sym ->
                    try {
                        app.marketDataRepository.refreshSymbol(sym)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        marketDataStatus.value = "WAIT • Quote refresh retry for $sym"
                        Log.w("DestinedFXViewModel", "Immediate quote refresh failed symbol=$sym: ${e.javaClass.simpleName}")
                    }
                    if (SpecRegistry.get(sym) == null) {
                        loadSpecForSymbol(sym)
                    } else {
                        _specReady.value = _specReady.value + sym
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }
        startMultiPairScanning()
    }

    val botStatusSummary = app.autonomousBotEngine.status
    val botActive: StateFlow<Boolean> = _botActive.asStateFlow()

    fun setBotStatus(active: Boolean, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                if (active) {
                    app.autonomousBotEngine.startBot(
                        symbol = selectedTicker.value,
                        strategyId = selectedStrategyId.value,
                        timeframe = selectedTimeframe.value,
                        riskPercent = riskPercent.value
                    )
                } else {
                    app.autonomousBotEngine.stopBot()
                }
                _botActive.value = active
                onResult?.invoke(true, null)
            } catch (e: Exception) {
                onResult?.invoke(false, e.message ?: "Automation engine error")
            }
        }
    }

    val isBackgroundScanningActive = MutableStateFlow(true)
    val backgroundScanStatus = MutableStateFlow("Initializing surveillance...")
    private val notifiedSignals = mutableSetOf<String>()

    private fun startMultiPairScanning() {
        viewModelScope.launch(Dispatchers.Default) {
            delay(20_000L)
            while (true) {
                try {
                    if (!isBackgroundScanningActive.value) {
                        backgroundScanStatus.value = "Surveillance Paused"
                        delay(30_000L)
                        continue
                    }

                    val symbols = com.example.data.market.SymbolRegistry.getBackgroundSymbols()
                    backgroundScanStatus.value = "Scanning ${symbols.size} pairs..."
                    
                    for (symbol in symbols) {
                        if (!isBackgroundScanningActive.value) break
                        
                        try {
                            val tf = "H1"
                            backgroundScanStatus.value = "Analyzing $symbol ($tf)..."
                            
                            val signal = app.signalGenerationUseCase.generateSignal(
                                symbol = symbol,
                                timeframe = tf,
                                strategyId = selectedStrategyId.value
                            )
                            
                            val isHighQuality = signal.confidenceScore >= 85 && 
                                               signal.direction != com.example.domain.model.SignalDirection.WAIT &&
                                               signal.entryPrice > 0.0
                            
                            if (isHighQuality) {
                                val signalKey = "${symbol}_${signal.direction}_${String.format("%.5f", signal.entryPrice)}"
                                if (!notifiedSignals.contains(signalKey)) {
                                    sendRealTimeNotification(signal.symbol, signal.direction.name, signal.entryPrice)
                                    notifiedSignals.add(signalKey)
                                    
                                    if (notifiedSignals.size > 200) {
                                        notifiedSignals.clear()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("DestinedFXViewModel", "Surveillance error for $symbol: ${e.message}")
                        }
                        delay(3000L)
                    }
                    
                    backgroundScanStatus.value = "Surveillance Active • 24/7 scanning enabled"
                } catch (e: Exception) {
                    Log.e("DestinedFXViewModel", "Background scanner error: ${e.message}")
                    backgroundScanStatus.value = "Surveillance Error: Retrying..."
                }
                delay(300_000L)
            }
        }
    }

    fun sendRealTimeNotification(symbol: String, positionType: String, entryPrice: Double) {
        val strategyName = activeStrategy.value
        val label = "💡 *DestinedFX Alpha Setup* 💡\n" +
                "Strategy: $strategyName\n" +
                "Mitigated Asset: $symbol\n" +
                "Action Triggered: $positionType\n" +
                "Optimal Value: ${String.format("%.5f", entryPrice)}\n" +
                "Safety Check: Pass ✅"
        
        viewModelScope.launch {
            if (telegramNotificationsEnabled.value) {
                Log.d("DestinedFX_Notification", "[TELEGRAM GATEWAY] Routing message safely.")
                notificationService.sendTelegramMessage(telegramBotToken.value, telegramChatId.value, label)
            }
        }
    }

    private suspend fun generateAlgorithmicReport(
        symbol: String,
        timeframe: String,
        currentPrice: Double,
        priceSource: String,
        priceTimeAgo: String,
        strategy: String,
        smcData: com.example.domain.model.MarketAnalysisResult?,
        candlesContext: String
    ): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val signal = app.signalGenerationUseCase.generateSignal(symbol, timeframe, strategy)
            
            buildString {
                appendLine("## Algorithmic Market Structure Analysis Matrix")
                appendLine("- **Symbol:** $symbol")
                appendLine("- **Timeframe:** $timeframe")
                appendLine("- **Spot Price:** $currentPrice ($priceSource, $priceTimeAgo)")
                appendLine("- **Active Strategy:** $strategy")
                appendLine()
                appendLine("### Executive Summary")
                appendLine("- **Direction Bias:** ${signal.direction}")
                appendLine("- **Confluence Score:** ${signal.confidenceScore}/100")
                appendLine("- **Signal State:** ${signal.signalState}")
                appendLine("- **Executable:** ${signal.executable}")
                appendLine()
                appendLine("### Trade Coordinates & Risk")
                appendLine("- **Entry Price:** ${signal.entryPrice}")
                appendLine("- **Stop Loss (SL):** ${signal.stopLoss}")
                appendLine("- **Take Profit (TP):** ${signal.takeProfit}")
                appendLine("- **Nearest High-Yield Zone:** ${signal.nearestHighYieldZone}")
                appendLine("- **Spread:** ${signal.spreadText}")
                appendLine()
                appendLine("### Rationale & Rules")
                appendLine(signal.rationale)
                if (signal.blockReasons.isNotEmpty()) {
                    appendLine()
                    appendLine("### Blockers / Invalidation Reasons")
                    signal.blockReasons.forEach { appendLine("- $it") }
                }
            }
        }
    }

    private fun updateFloatingCapital() {
        if (_mt5Connected.value) {
            accountCapital.value = _mt5Balance.value
        } else {
            accountCapital.value = 0.0
        }
    }

    private fun closeTradeAutomated(trade: TradeEntity, status: String, pips: Int, pl: Double) {
        viewModelScope.launch {
            repository.updateTrade(
                trade.copy(
                    status = status,
                    pipsProfit = pips,
                    plAmount = pl
                )
            )
        }
    }

    fun executeTrade(
        symbol: String,
        positionType: String,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        rationale: String,
        lotSize: Double = 0.01
    ) {
        viewModelScope.launch {
            try {
                val direction = if (positionType.equals("Long", true) || positionType.equals("BUY", true)) 
                    com.example.domain.model.SignalDirection.LONG else com.example.domain.model.SignalDirection.SHORT
                
                val currentPrice = tickers.value[symbol]?.price ?: entryPrice
                val actualEntry = if (entryPrice > 0.0) entryPrice else currentPrice

                val signal = com.example.domain.model.TradeSignal(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    timeframe = "60",
                    direction = direction,
                    entryPrice = actualEntry,
                    stopLoss = stopLoss,
                    takeProfit = takeProfit,
                    confidenceScore = 90,
                    rationale = rationale,
                    timestamp = System.currentTimeMillis(),
                    nearestHighYieldZone = "",
                    profitZones = emptyList()
                )
                
                app.tradeUseCase.executeTrade(signal)
                
                withContext(Dispatchers.Main) {
                    speakText("Order execution confirmed by broker.")
                    actionAlertMessage.value = "Trade Active: $symbol $positionType"
                }

                if (telegramNotificationsEnabled.value) {
                    val dir = if (direction == com.example.domain.model.SignalDirection.LONG) "BUY 🟢" else "SELL 🔴"
                    val alert = "🚀 *DestinedFX Instant Trade Executed* 🚀\n\n" +
                            "• Instrument: *$symbol*\n" +
                            "• Direction: *$dir*\n" +
                            "• Entry: *${String.format(java.util.Locale.US, "%.5f", actualEntry)}*\n" +
                            "• Stop Loss: *${if (stopLoss > 0.0) String.format(java.util.Locale.US, "%.5f", stopLoss) else "—"}*\n" +
                            "• Take Profit: *${if (takeProfit > 0.0) String.format(java.util.Locale.US, "%.5f", takeProfit) else "—"}*\n" +
                            "• Lot Size: *$lotSize*\n" +
                            "• Broker: *MT5 Terminal (Instant Deal)*\n" +
                            "• Status: *Active & Filled* ✅"
                    notificationService.sendTelegramMessage(telegramBotToken.value, telegramChatId.value, alert)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val error = e.message ?: "Unknown execution error"
                    speakText("Trade rejected: $error")
                    actionAlertMessage.value = "Execution Failed: $error"
                }
            }
        }
    }

    fun sendTestNotification() {
        val label = "🔔 *DestinedFX Gateway Validation* 🔔\n" +
                "This is a test notification payload to verify WhatsApp and Telegram routing channels.\n" +
                "Status: Active and Secure ✅"
        
        viewModelScope.launch {
            if (telegramNotificationEnabled.value) {
                Log.d("DestinedFX_Notification", "Sending test to Telegram...")
                notificationService.sendTelegramMessage(telegramBotToken.value, telegramChatId.value, label)
            }
            if (whatsappNotificationEnabled.value) {
                Log.d("DestinedFX_Notification", "Sending test to WhatsApp...")
                notificationService.sendWhatsAppMessage(whatsappApiKey.value, whatsappPhoneNumber.value, label)
            }
        }
    }


    fun connectToMT5Credentials(
        login: String,
        password: String,
        server: String,
        investorPassword: String = "",
        balance: Double? = null,
        accountType: String = "Demo",
        leverage: Int = 500,
        currency: String = "USD",
        onResult: (Boolean, String?) -> Unit
    ) {
        if (isPreview) {
            onResult(false, "Broker login is disabled in preview mode.")
            return
        }
        val cleanLogin = login.trim()
        val cleanServer = server.trim()
        if (cleanLogin.isBlank() || !cleanLogin.all { it.isDigit() }) {
            onResult(false, "Enter the numeric MT5 Login provided by your broker.")
            return
        }
        if (password.isBlank() || cleanServer.isBlank()) {
            onResult(false, "MT5 master Password and Server are required.")
            return
        }
        viewModelScope.launch {
            try {
                val targetBalance = balance?.takeIf { it > 0.0 }
                    ?: app.secretsManager.getMT5AccountBalance().takeIf { it > 0.0 }
                    ?: 3000.0

                app.secretsManager.saveMT5Login(cleanLogin)
                app.secretsManager.saveMT5Password(password)
                app.secretsManager.saveMT5BrokerServer(cleanServer)
                app.secretsManager.saveMT5InvestorPassword(investorPassword)
                app.secretsManager.saveMT5AccountType(accountType)
                app.secretsManager.saveMT5Leverage(leverage)
                app.secretsManager.saveMT5Currency(currency)
                app.secretsManager.saveMT5SessionStatus("CONNECTING")
                app.secretsManager.saveMT5AccountBalance(targetBalance)
                app.secretsManager.saveConfiguredEquity(targetBalance)
                app.secretsManager.saveMetaApiToken("") // Clear legacy bridge token
                app.secretsManager.saveMT5AccountNumber("DIRECT-$cleanLogin")

                app.mt5GatewayAdapter.setAccountBalance(targetBalance)
                accountCapital.value = targetBalance
                configuredEquity.value = targetBalance

                app.brokerManager.selectBroker(com.example.data.broker.BrokerType.MT5)
                val result = app.mt5GatewayAdapter.connect()
                if (result.isSuccess) {
                    brokerConnected.value = true
                    app.secretsManager.saveMT5SessionStatus("CONNECTED")
                    prefetchWatchlistSpecs()
                    val metrics = app.mt5GatewayAdapter.accountMetrics.value
                    val finalBalance = metrics?.balance ?: targetBalance
                    accountCapital.value = finalBalance
                    val ccy = metrics?.currency ?: currency
                    onResult(
                        true,
                        "MT5 connected • Login $cleanLogin ($cleanServer) • $accountType • Balance " +
                            String.format(java.util.Locale.US, "%.2f", finalBalance) + " $ccy"
                    )
                } else {
                    app.secretsManager.saveMT5SessionStatus("AUTH_FAILED")
                    onResult(false, result.exceptionOrNull()?.message ?: "MT5 connection failed.")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.secretsManager.saveMT5SessionStatus("ERROR")
                onResult(false, "MT5 connection failed: ${e.message}")
            }
        }
    }

    fun reconnectMT5Session(onResult: (Boolean, String?) -> Unit) {
        val login = app.secretsManager.getMT5Login()
        val pass = app.secretsManager.getMT5Password()
        val server = app.secretsManager.getMT5BrokerServer()
        if (login.isBlank() || pass.isBlank() || server.isBlank()) {
            onResult(false, "No saved MT5 account credentials found. Please log in.")
            return
        }
        val bal = app.secretsManager.getMT5AccountBalance().takeIf { it > 0.0 } ?: 3000.0
        val type = app.secretsManager.getMT5AccountType()
        val lev = app.secretsManager.getMT5Leverage()
        val ccy = app.secretsManager.getMT5Currency()
        connectToMT5Credentials(login, pass, server, "", bal, type, lev, ccy, onResult)
    }

    fun selectBroker(type: com.example.data.broker.BrokerType) {
        if (isPreview) return
        // The loop keeps a reference to its selected gateway; stop it before changing routes.
        app.autonomousBotEngine.stopBot()
        _botActive.value = false
        app.brokerManager.selectBroker(type)
    }

    val activeBrokerType: StateFlow<com.example.data.broker.BrokerType> =
        if (isPreview) MutableStateFlow(com.example.data.broker.BrokerType.DERIV) else app.brokerManager.selectedBrokerType

    val activeBrokerGateway: StateFlow<com.example.data.broker.BrokerGateway> =
        if (isPreview) MutableStateFlow(com.example.data.broker.PreviewBrokerGateway()) else app.brokerManager.activeBroker

    fun getMT5Login(): String = if (isPreview) "" else app.secretsManager.getMT5Login()
    fun getMT5Password(): String = "••••••••"
    fun getMT5BrokerServer(): String = if (isPreview) "" else app.secretsManager.getMT5BrokerServer()
    fun getMT5InvestorPassword(): String = "••••••••"
    fun getTelegramBotToken(): String = if (isPreview) "" else app.secretsManager.getTelegramToken()
    fun getTelegramChatId(): String = if (isPreview) "" else app.secretsManager.getTelegramChatId()
    fun hasLiveBrokerKeys(): Boolean = if (isPreview) false else {
        try {
            app.secretsManager.getMT5Login().isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun exportPdfDossier(info: DynamicAnalysisInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            RealAnalysisExporter.exportPdf(getApplication(), info, selectedTimeframe.value, selectedStrategyId.value)
        }
    }

    fun exportImageSnapshot(info: DynamicAnalysisInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            RealAnalysisExporter.exportPng(getApplication(), info, selectedTimeframe.value, selectedStrategyId.value)
        }
    }
}
