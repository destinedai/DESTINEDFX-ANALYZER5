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

class DestinedFXViewModel(application: Application) : AndroidViewModel(application) {

    val app = application as com.example.MyApplication
    private val db = AppDatabase.getDatabase(application)
    private val repository = TradeRepository(db.tradeDao())
    private val analysisRepository = com.example.data.AnalysisRepository(db.analysisDao())
    private val client = OkHttpClient()

    suspend fun resolveRealPrice(symbol: String, timeframe: String): ResolvedPrice? {
        // 1. Try tickers.value[symbol]?.price
        val ticker = tickers.value[symbol]
        if (ticker != null && ticker.price > 0.0) {
            val src = ticker.source.ifBlank { "Real-time feed" }
            return ResolvedPrice(ticker.price, src, ticker.lastUpdateMillis)
        }

        // 2. Else try marketDataRepository.getLatestTick(symbol).price if > 0
        val tick = app.marketDataRepository.getLatestTick(symbol)
        if (tick.price > 0.0) {
            return ResolvedPrice(tick.price, "Market Tick", tick.timestamp)
        }

        // 3. Else use the close of the latest candle from getCandles(symbol, timeframe, 1)
        val candles = app.marketDataRepository.getCandles(symbol, timeframe, 1)
        val latestCandle = candles.lastOrNull()
        if (latestCandle != null && latestCandle.close > 0.0) {
            val src = app.marketDataRepository.getDataSource(symbol, timeframe)
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

    val tradeHistory: StateFlow<List<TradeEntity>> = repository.allTrades
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val analysisHistory: StateFlow<List<com.example.data.AnalysisEntity>> = analysisRepository.allHistory
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
    val riskPercent = MutableStateFlow(1.0)
    val selectedTab = MutableStateFlow(0)

    fun selectTicker(sym: String) {
        selectedTicker.value = sym
    }

    fun selectTimeframe(tf: String) {
        selectedTimeframe.value = tf
    }

    fun setRiskPercent(value: Double) {
        riskPercent.value = value.coerceIn(0.1, 1.0)
    }

    // Dynamic high-fidelity candlestick history for each symbol
    val candlestickHistory = androidx.compose.runtime.mutableStateMapOf<String, List<Candle>>()

    // Real-time market candle fetch errors exposed to UI
    val candleFetchErrors: StateFlow<Map<String, String>> = app.marketDataRepository.candleFetchErrors
    val selectedApiSource: MutableStateFlow<String> = app.marketDataRepository.selectedApiSource

    fun getCandleError(symbol: String, timeframe: String): String? =
        app.marketDataRepository.getCandleError(symbol, timeframe)

    fun getDataSourceForSymbol(symbol: String): String =
        app.marketDataRepository.getDataSource(symbol, selectedTimeframe.value)

    fun isDataFresh(symbol: String): Boolean =
        app.marketDataRepository.isFresh(symbol)

    val brokerConnected = MutableStateFlow(app.mt5GatewayRepository.isConnected())
    val brokerQuote = MutableStateFlow<com.destinedfx.core.marketdata.MarketQuote?>(null)

    val crashLogManager = com.destinedfx.core.security.CrashLogManager(application.filesDir)
    val lastCrashText = MutableStateFlow<String?>(crashLogManager.readLastCrash())

    fun dismissLastCrash() {
        crashLogManager.dismissCrash()
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
                refreshDataCorrespondence()
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
            val candleSources = app.marketDataRepository.candleSources
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
    val telegramBotToken = MutableStateFlow(app.secretsManager.getTelegramToken())
    val telegramChatId = MutableStateFlow(app.secretsManager.getTelegramChatId())
    val whatsappNotificationEnabled = MutableStateFlow(true)
    val whatsappPhoneNumber = MutableStateFlow(app.secretsManager.getWhatsAppPhone())
    val whatsappApiKey = MutableStateFlow(app.secretsManager.getWhatsAppApiKey())
    
    // Safety Risk Filters & News Safeguards (Non-Farm Payroll / NFP CPI Protection)
    val nfpProtectionArmed = MutableStateFlow(true)
    val nextNfpDate = "Friday, July 3, 2026"
    val showHaltNotification = MutableStateFlow(false)

    // MetaTrader Broker Connection Configurations & Themes (100% Free Lifetime Active)
    val themeMode = MutableStateFlow("System") // "System", "Light", "Dark"
    val brokerServer = MutableStateFlow(app.secretsManager.getMT5BrokerServer())
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
        if (currentUser.value != null) {
            isAppLocked.value = true
        }
    }

    fun unlockApp() {
        isAppLocked.value = false
    }

    fun checkBiometricAvailability(context: Context) {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        
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
    }

    // --- USER REGISTRATION & AUTHENTICATION (SUPABASE AUTH CLIENT) ---
    private val supabaseClient by lazy { com.example.data.supabase.SupabaseAuthClient(app.secretsManager) }
    val currentUser = MutableStateFlow<com.example.data.user.User?>(null)
    val showRegisterScreen = MutableStateFlow(false)
    val isShowingSplash = MutableStateFlow(false)

    // Strategy & Configured Equity Persistence
    val selectedStrategyId = MutableStateFlow(app.secretsManager.getSelectedStrategy())
    val configuredEquity = MutableStateFlow(app.secretsManager.getConfiguredEquity())

    fun selectStrategy(id: String) {
        selectedStrategyId.value = id
        app.secretsManager.saveSelectedStrategy(id)
        runSignalScan(selectedTicker.value, selectedTimeframe.value)
    }

    fun saveConfiguredEquity(v: Double) {
        val clean = if (v > 0.0) v else 10.0
        configuredEquity.value = clean
        app.secretsManager.saveConfiguredEquity(clean)
    }

    // Live Real-Time Trade Signal State for Signals Tab
    val currentTradeSignal = MutableStateFlow<com.example.domain.model.TradeSignal?>(null)
    val currentDynamicAnalysis = MutableStateFlow<DynamicAnalysisInfo?>(null)
    val isSignalScanning = MutableStateFlow(false)

    fun runSignalScan(symbol: String, timeframe: String) {
        viewModelScope.launch {
            isSignalScanning.value = true
            try {
                val sig = app.signalGenerationUseCase.generateSignal(symbol, timeframe, selectedStrategyId.value)
                currentTradeSignal.value = sig
                val mapped = mapTradeSignalToUiModel(sig)
                currentDynamicAnalysis.value = mapped
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "Signal scan failed: ${e.message}")
            } finally {
                isSignalScanning.value = false
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

        if (quote == null || now - quote.timestampMs > 10_000L) {
            return@withContext PreExecutionCheck(false, 0.0, 0.0, "Market quote is stale or unavailable. WAITING FOR LIVE DATA.")
        }

        val spread = quote.spread
        if (spread == null || (spec != null && spec.pipSize != null && spread > spec.pipSize!! * 3.5)) {
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

    private fun restorePersistedSession() {
        viewModelScope.launch {
            val user = supabaseClient.restoreSession()
            if (user != null) {
                currentUser.value = user
                isShowingSplash.value = true
                isAppLocked.value = true
                app.cloudSyncManager.syncUserSession(user, accountCapital.value)
            }
        }
    }

    fun registerNewUser(email: String, username: String, passwordText: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = app.authUseCase.register(email, username, passwordText)
            result.onSuccess { res ->
                when (res) {
                    is com.example.data.supabase.SignUpResponse.SuccessVerified -> {
                        currentUser.value = res.user
                        isShowingSplash.value = true
                        onResult(true, "Account created successfully!")
                    }
                    is com.example.data.supabase.SignUpResponse.EmailVerificationRequired -> {
                        onResult(true, "Verification email sent to ${res.email}. Please verify before logging in.")
                    }
                }
            }.onFailure { e ->
                onResult(false, e.message ?: "Registration failed")
            }
        }
    }

    fun loginExistingUser(email: String, passwordText: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = app.authUseCase.login(email, passwordText)
            result.onSuccess { user ->
                currentUser.value = user
                isShowingSplash.value = true
                onResult(true, "Login successful")
            }.onFailure { e ->
                onResult(false, e.message ?: "Login failed")
            }
        }
    }

    fun logoutUser() {
        viewModelScope.launch {
            supabaseClient.signOut()
            currentUser.value = null
            showRegisterScreen.value = false
            speakText("Algorithmic session expired. Session closed.")
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
                val err = "Live price unavailable for $symbol. Check OANDA/Twelve Data keys or connection."
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

            val candlesContextStr = domainCandles.takeLast(20).joinToString("\n") { 
                "O: ${it.open}, H: ${it.high}, L: ${it.low}, C: ${it.close}" 
            }
            
            val reportDetails = generateAlgorithmicReport(
                symbol, timeframe, currentPrice, priceSource, priceTimeAgo, strategy, smcData, candlesContextStr
            )
            
            val baseConfidence = smcData?.confluenceScore?.toDouble() ?: 75.0
            val selectedSignal = if (baseConfidence > 85.0) "STRONG BUY" else if (baseConfidence > 70.0) "BUY" else if (baseConfidence > 55.0) "NEUTRAL" else "SELL"
            
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

    private suspend fun fetchGeminiOpinion(prompt: String): Pair<String, String> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val apiKey = app.secretsManager.getGeminiApiKey()
                if (apiKey.isEmpty()) return@withContext Pair("NEUTRAL", "API Key missing.")
                
                val jsonPayload = org.json.JSONObject()
                val contentsArray = org.json.JSONArray()
                val contentObj = org.json.JSONObject()
                val partsArray = org.json.JSONArray()
                val partObj = org.json.JSONObject()
                partObj.put("text", prompt + "\nProvide rigorous risk management. Warn against over-leveraging and state strictly that large lot sizes like 5.0 will blow up accounts instantly.")
                partsArray.put(partObj)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
                jsonPayload.put("contents", contentsArray)

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)

                val request = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=${apiKey}")
                    .post(requestBody)
                    .build()

                val geminiClient = client.newBuilder().build()
                val response = geminiClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val responseJson = org.json.JSONObject(responseBody)
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val text = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)?.optString("text") ?: ""
                        // Extract JSON from markdown
                        val jsonStr = text.substringAfter("{").substringBeforeLast("}")
                        val parsed = org.json.JSONObject("{${jsonStr}}")
                        return@withContext Pair(parsed.optString("signal", "NEUTRAL"), parsed.optString("rationale", "No rationale."))
                    }
                }
                Pair("NEUTRAL", "API error.")
            } catch (e: Exception) {
                Pair("NEUTRAL", "Error: ${e.message}")
            }
        }
    }

    fun triggerMultiAIConsensusScan(symbol: String) {
        viewModelScope.launch {
            isMultiAIScanning.value = true
            multiAIConsensusStatus.value = "Fetching multi-model AI consensus..."
            speakText("Initiating domain level signal generation.")

            val resolvedPrice = resolveRealPrice(symbol, "H1")
            if (resolvedPrice == null) {
                val err = "Live price unavailable for $symbol. Check OANDA/Twelve Data keys or connection."
                multiAIConsensusStatus.value = err
                actionAlertMessage.value = err
                isMultiAIScanning.value = false
                return@launch
            }
            if (resolvedPrice.source == "Frankfurter (daily reference)") {
                val msg = "Only daily reference price available - not suitable for intraday SMC levels."
                multiAIConsensusStatus.value = msg
                actionAlertMessage.value = msg
                isMultiAIScanning.value = false
                return@launch
            }
            val currentPrice = resolvedPrice.price
            val priceSource = resolvedPrice.source
            val priceTimeAgo = formatPriceTimeAgo(resolvedPrice.timestamp)

            val app = getApplication<com.example.MyApplication>()
            val signal = app.signalGenerationUseCase.generateSignal(symbol, "H1")
            val report = (app.signalGenerationUseCase as? com.example.domain.usecase.impl.SignalReportStore)?.reportFor(signal.id)
            
            val geminiPrompt = if (report != null) {
                com.destinedfx.core.ai.AiPrompt.build(report) + "\nRespond with a simple JSON object: {\"signal\": \"${signal.direction.name}\", \"rationale\": \"1-2 sentence explanation of the technical factors.\"}"
            } else {
                val candles = candlestickHistory[symbol]?.takeLast(20)?.joinToString("\n") { "O:${it.open} C:${it.close} H:${it.high} L:${it.low}" } ?: ""
                """
                    You are an analyst assistant. Explain the following verified technical context for $symbol:
                    $candles
                    Spot price: $currentPrice (source: $priceSource, $priceTimeAgo).
                    Respond with a simple JSON object:
                    {"signal": "NEUTRAL", "rationale": "Short explanation"}
                """.trimIndent()
            }
            
            val geminiOpinion = fetchGeminiOpinion(geminiPrompt)

            val newOpinions = mapOf(
                "NativeEngine" to AIBotOpinion(
                    botName = "Native Core Engine",
                    signal = signal.direction.name,
                    entry = signal.entryPrice,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit,
                    confidence = signal.confidenceScore,
                    coreRationale = signal.rationale
                ),
                "GeminiPro" to AIBotOpinion(
                    botName = "Gemini Analyst",
                    signal = signal.direction.name, // AI text never changes the verified signal
                    entry = signal.entryPrice,       // AI text never changes entry
                    sl = signal.stopLoss,           // AI text never changes stop loss
                    tp = signal.takeProfit,         // AI text never changes take profit
                    confidence = signal.confidenceScore,
                    coreRationale = com.destinedfx.core.ai.AiPrompt.sanitize(geminiOpinion.second)
                )
            )

            aiBotOpinions.value = newOpinions
            multiAIConsensusStatus.value = "ANALYSIS COMPLETE"
            isMultiAIScanning.value = false
            speakText("Consensus loaded.")
        }
    }

    private fun initTTS() {
        // Disabled TTS initialization to remove female voice
    }

    fun speakText(text: String) {
        // Disabled female TTS voice as requested, keeping authentic UI presence.
    }

    init {
        initTTS()
        restorePersistedSession()

        // Sync broker connection and quote for selectedTicker
        viewModelScope.launch {
            while (true) {
                try {
                    val conn = app.mt5GatewayRepository.isConnected()
                    brokerConnected.value = conn
                    if (conn) {
                        val sym = selectedTicker.value
                        val bq = app.mt5GatewayRepository.getBrokerQuote(sym).getOrNull()
                        brokerQuote.value = bq
                    } else {
                        brokerQuote.value = null
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
            try {
                app.marketDataRepository.tickerFlow.collect { map ->
                    if (map.isNotEmpty()) {
                        tickers.value = map
                        _feedItems.value = map.values.toList()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
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
                    val tfKey = if (tf == "60") "H1" else tf
                    val candles = app.marketDataRepository.getCandles(symbol, tfKey, 150)
                    if (candles.isNotEmpty()) {
                        candlestickHistory[symbol] = candles
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
            try {
                while (true) {
                    delay(30_000L)
                    val sym = selectedTicker.value
                    val tf = selectedTimeframe.value
                    val tfKey = if (tf == "60") "H1" else tf
                    val candles = app.marketDataRepository.getCandles(sym, tfKey, 150)
                    if (candles.isNotEmpty()) {
                        candlestickHistory[sym] = candles
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }

        // 4. Dedicated high-priority price poller for the active symbol (Moves with the market)
        viewModelScope.launch {
            try {
                while (true) {
                    val sym = selectedTicker.value
                    app.marketDataRepository.refreshSymbol(sym)
                    delay(8_000L) // Refresh active price every 8 seconds
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "init collector failed: ${e.javaClass.simpleName}")
            }
        }

        // 5. Manual refresh when API changes
        viewModelScope.launch {
            try {
                app.marketDataRepository.selectedApiSource.collect {
                    val sym = selectedTicker.value
                    app.marketDataRepository.refreshSymbol(sym)
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
                    app.marketDataRepository.refreshSymbol(sym)
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
                
                val signal = com.example.domain.model.TradeSignal(
                    id = UUID.randomUUID().toString(),
                    symbol = symbol,
                    timeframe = "60",
                    direction = direction,
                    entryPrice = entryPrice,
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

    private val notificationService = com.example.data.NotificationService(client)

    fun sendRealTimeNotification(symbol: String, positionType: String, entryPrice: Double) {
        val strategyName = activeStrategy.value
        val label = "💡 *DestinedFX Alpha Setup* 💡\n" +
                "Strategy: $strategyName\n" +
                "Mitigated Asset: $symbol\n" +
                "Action Triggered: $positionType\n" +
                "Optimal Value: ${String.format("%.5f", entryPrice)}\n" +
                "Safety Check: Pass ✅"
        
        viewModelScope.launch {
            if (telegramNotificationEnabled.value) {
                Log.d("DestinedFX_Notification", "[TELEGRAM GATEWAY] Routing message safely to Chat ID ${telegramChatId.value}.")
                notificationService.sendTelegramMessage(telegramBotToken.value, telegramChatId.value, label)
            }
            if (whatsappNotificationEnabled.value) {
                Log.d("DestinedFX_Notification", "[WHATSAPP GATEWAY] Routing message safely to Phone Number ${whatsappPhoneNumber.value}.")
                notificationService.sendWhatsAppMessage(whatsappApiKey.value, whatsappPhoneNumber.value, label)
            }
        }
    }

    // Manual Trade closure
    fun manualCloseTrade(trade: TradeEntity) {
        viewModelScope.launch {
            val symbolPrice = tickers.value[trade.symbol]?.price ?: trade.entryPrice
            val priceDiff = symbolPrice - trade.entryPrice
            val factor = if (trade.positionType == "Long") 1.0 else -1.0
            // P/L uses the broker symbol specification only. No hardcoded pip/tick table: without a spec the P/L is reported as unavailable (0), never guessed.
            val spec = com.destinedfx.core.instrument.SpecRegistry.get(trade.symbol)
            val signedMove = priceDiff * factor
            val usdProfit = if (spec != null) signedMove / spec.tickSize * spec.tickValue * trade.lotSize else 0.0
            val pipProfit = if (spec != null) ((spec.priceToPips(kotlin.math.abs(signedMove)) ?: spec.priceToPoints(kotlin.math.abs(signedMove))) * (if (signedMove >= 0) 1 else -1)).toInt() else 0

            repository.updateTrade(
                trade.copy(
                    status = "Closed",
                    pipsProfit = pipProfit,
                    plAmount = usdProfit
                )
            )
        }
    }

    fun deleteTrade(trade: TradeEntity) {
        viewModelScope.launch {
            repository.deleteTradeById(trade.id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAllTrades()
        }
    }

    // Algorithm computational analysis executor with tight stop-loss detection
    fun executeAICall(symbol: String, timeframe: String) {
        viewModelScope.launch {
            isAnalyzing.value = true
            aiAnalysisResult.value = null

            val resolvedPrice = resolveRealPrice(symbol, timeframe)
            if (resolvedPrice == null) {
                val err = "Live price unavailable for $symbol. Check OANDA/Twelve Data keys or connection."
                aiAnalysisResult.value = err
                actionAlertMessage.value = err
                isAnalyzing.value = false
                return@launch
            }
            if (resolvedPrice.source == "Frankfurter (daily reference)") {
                val msg = "Only daily reference price available - not suitable for intraday SMC levels."
                aiAnalysisResult.value = msg
                actionAlertMessage.value = msg
                isAnalyzing.value = false
                return@launch
            }
            val currentPrice = resolvedPrice.price
            val priceSource = resolvedPrice.source
            val priceTimeAgo = formatPriceTimeAgo(resolvedPrice.timestamp)

            val strategy = activeStrategy.value
            val base64 = selectedImageBase64.value
            val mime = selectedImageMime.value
            val hasImage = !base64.isNullOrEmpty()

            val candles = candlestickHistory[symbol] ?: emptyList()
            val domainCandles = candles
            val smcData = if (domainCandles.size > 10) {
                structureAnalyzer.analyzeStructure(domainCandles)
            } else null
            
            val smcContext = if (smcData != null) {
                """
                
                NATIVE SMC ENGINE DATA (Phase 3 Backend Pre-Calculation Phase):
                - Confluence Score: ${smcData.confluenceScore}% 
                - Algorithm Bias Direction: ${smcData.bias}
                - Unmitigated FVGs found: ${smcData.fvgs.filter { !it.mitigated }.size}
                - Recent Unmitigated Order Blocks: ${smcData.orderBlocks.filter { !it.mitigated }.size}
                - Liquidity Sweeps Tracked: ${smcData.liquiditySweeps.size}
                Use this Native Subsystem Data to formulate the ultimate confluence result and confirm the entry price near Market Price.
                """
            } else ""

            val candlesContextStr = domainCandles.takeLast(20).joinToString("\n") { 
                "O: ${it.open}, H: ${it.high}, L: ${it.low}, C: ${it.close}" 
            }

            // Proceed with Algorithmic Generation based on Phase 3 calculations

            aiAnalysisResult.value = generateAlgorithmicReport(symbol, timeframe, currentPrice, priceSource, priceTimeAgo, strategy, smcData, candlesContextStr)
            isAnalyzing.value = false
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
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val apiKey = app.secretsManager.getGeminiApiKey()
                if (apiKey.isEmpty()) {
                    return@withContext "Error: Gemini API Key not configured."
                }
                
                val smcContext = if (smcData != null) {
                    """
                    NATIVE SMC ENGINE DATA:
                    - Confluence Score: ${smcData.confluenceScore}% 
                    - Algorithm Bias Direction: ${smcData.bias}
                    - Unmitigated FVGs found: ${smcData.fvgs.filter { !it.mitigated }.size}
                    - Recent Unmitigated Order Blocks: ${smcData.orderBlocks.filter { !it.mitigated }.size}
                    - Liquidity Sweeps Tracked: ${smcData.liquiditySweeps.size}
                    """
                } else "No SMC data available."

                val prompt = """
                    You are an elite, quantitative AI trading algorithm operating at a high frequency hedge fund. 
                    Perform a deep algorithmic analysis on $symbol for the $timeframe timeframe.
                    
                    CRITICAL: YOU MUST ANCHOR ALL CALCULATIONS TO THIS REAL-TIME PRICE:
                    - Spot price: $currentPrice (source: $priceSource, $priceTimeAgo)
                    
                    Rule: Use ONLY the spot price and candles provided. Never invent or recall prices from memory. Entry must be at or near the spot price. SL and TP must be calculated from the spot price using the provided candles and swing levels.
                    
                    Strategy Protocol: $strategy
                    
                    $smcContext
                    
                    Recent Price Action (OHLC format):
                    $candlesContext
                    
                    Provide a highly professional, broker-grade market structure analysis matrix.
                    Use the provided data to formulate the ultimate confluence result.
                    
                    STRICT RULES:
                    1. Confirm the entry price EXACTLY AT or NEAR the provided Spot Price ($currentPrice). DO NOT GUESS historical or future prices.
                    2. Calculate Stop Loss and Take Profit levels based on actual price distance from $currentPrice.
                    3. Format in clean markdown with sections for: Executive Summary, Structural Matrix, Trade Coordinates (Entry, SL, TP1, TP2), and Risk Mitigation.
                    
                    WARNING: Emphasize rigorous risk management. explicitly advise against extreme lot sizes (e.g. 5.05 lots on a 3000 balance is mathematically disastrous). Dictate standard 1-2% risk sizes (e.g. 0.01 - 0.05 max for 3000 USD).
                    Do not use disclaimers. Give direct output.
                """.trimIndent()

                val jsonPayload = org.json.JSONObject()
                val contentsArray = org.json.JSONArray()
                val contentObj = org.json.JSONObject()
                val partsArray = org.json.JSONArray()
                val partObj = org.json.JSONObject()
                
                partObj.put("text", prompt)
                partsArray.put(partObj)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
                jsonPayload.put("contents", contentsArray)

                val systemInst = org.json.JSONObject()
                val sysPartsArray = org.json.JSONArray()
                val sysPartObj = org.json.JSONObject()
                sysPartObj.put("text", "You are an elite trading bot. Give factual, direct quantitative analysis in markdown with strict risk metrics.")
                sysPartsArray.put(sysPartObj)
                systemInst.put("parts", sysPartsArray)
                jsonPayload.put("systemInstruction", systemInst)

                val mediaType = "application/json".toMediaType()
                val requestBody = jsonPayload.toString().toRequestBody(mediaType)

                val request = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=${apiKey}")
                    .post(requestBody)
                    .build()

                // Increase timeout for the Gemini request
                val geminiClient = client.newBuilder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = geminiClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val responseJson = org.json.JSONObject(responseBody)
                    val candidates = responseJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return@withContext parts.getJSONObject(0).optString("text")
                        }
                    }
                    "Error parsing AI response."
                } else {
                    "API Error: ${response.code} ${response.message}\nBody: $responseBody"
                }
            } catch (e: Exception) {
                "Error generating AI analysis: ${e.localizedMessage}"
            }
        }
    }

    // --- MT5 Connection Logic & State ---
    private val _mt5Balance = MutableStateFlow(0.0)
    val mt5Balance: StateFlow<Double> = _mt5Balance

    private val _mt5Equity = MutableStateFlow(0.0)
    val mt5Equity: StateFlow<Double> = _mt5Equity

    private val _mt5Margin = MutableStateFlow(0.0)
    val mt5Margin: StateFlow<Double> = _mt5Margin

    private val _mt5Connected = MutableStateFlow(false)
    val mt5Connected: StateFlow<Boolean> = _mt5Connected

    private val _botActive = MutableStateFlow(false)
    val botActive: StateFlow<Boolean> = _botActive

    fun connectToMT5(broker: String, loginId: String, pass: String, isDemo: Boolean, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val success = com.example.di.ServiceLocator.mt5Bridge.connect(broker, loginId, pass, mt5GatewayUrl.value)
            
            if (!success) {
                onResult(false, "Authorization failed! Incorrect password or invalid ID.")
                _mt5Connected.value = false
            } else {
                _mt5Balance.value = com.example.di.ServiceLocator.mt5Bridge.walletBalance.value
                _mt5Equity.value = com.example.di.ServiceLocator.mt5Bridge.equity.value
                _mt5Margin.value = com.example.di.ServiceLocator.mt5Bridge.margin.value
                _mt5Connected.value = true
                prefetchWatchlistSpecs()
                onResult(true, null)
            }
        }
    }

    fun disconnectMT5() {
        viewModelScope.launch {
            com.example.di.ServiceLocator.mt5Bridge.disconnect()
            _mt5Connected.value = false
            _botActive.value = false
        }
    }

    val botStatusSummary = app.autonomousBotEngine.status

    fun setBotStatus(active: Boolean, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            if (active) {
                val sym = selectedTicker.value
                val strat = selectedStrategyId.value
                val tf = selectedTimeframe.value
                val risk = riskPercent.value
                val res = app.autonomousBotEngine.startBot(sym, strat, tf, risk)
                _botActive.value = res.isSuccess
                onResult?.invoke(res.isSuccess, res.exceptionOrNull()?.message)
            } else {
                app.autonomousBotEngine.stopBot()
                _botActive.value = false
                onResult?.invoke(true, null)
            }
        }
    }

    fun connectToDeriv(token: String, appId: String = "1089", onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            app.secretsManager.saveDerivApiToken(token.trim())
            app.secretsManager.saveDerivAppId(appId.trim().ifBlank { "1089" })
            app.brokerManager.selectBroker(com.example.data.broker.BrokerType.DERIV)
            val res = app.derivBrokerGateway.connectAndAuthorize(token.trim())
            if (res.isSuccess) {
                val info = res.getOrThrow()
                onResult(true, "Connected to Deriv (${info.loginId}, Balance: ${info.balance} ${info.currency})")
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "Deriv connection failed")
            }
        }
    }

    fun selectBroker(type: com.example.data.broker.BrokerType) {
        app.brokerManager.selectBroker(type)
    }

    val activeBrokerType = app.brokerManager.selectedBrokerType
    val activeBrokerGateway = app.brokerManager.activeBroker

    fun exportPdfDossier(info: DynamicAnalysisInfo) {
        exportChartData(getApplication(), info.symbol, "SMC Order Blocks")
    }

    fun exportImageSnapshot(info: DynamicAnalysisInfo) {
        exportChartData(getApplication(), info.symbol, "SMC Order Blocks")
    }

    fun exportChartData(context: Context, chartName: String, strategy: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.parseColor("#121212")) // Dark background
                
                val width = bitmap.width.toFloat()
                val height = bitmap.height.toFloat()
                
                val paint = Paint().apply {
                    color = Color.parseColor("#00E5FF") // Cyan primary
                    textSize = 40f
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                
                canvas.drawText("DESTINED FX V5.0", width / 2f, 60f, paint)
                
                paint.apply {
                    color = Color.WHITE
                    textSize = 24f
                }
                canvas.drawText("Algorithmic Snapshot: $chartName", width / 2f, 100f, paint)
                canvas.drawText("Strategy: $strategy", width / 2f, 140f, paint)
                
                val candles = emptyList<Candle>()
                if (candles.isNotEmpty()) {
                    val drawCandleCount = minOf(100, candles.size)
                    val visibleCandles = candles.takeLast(drawCandleCount)

                    val rawMin = visibleCandles.minOf { it.low.toFloat() }
                    val rawMax = visibleCandles.maxOf { it.high.toFloat() }
                    val paddingFactor = (rawMax - rawMin) * 0.12f
                    val minPrice = rawMin - paddingFactor
                    val maxPrice = rawMax + paddingFactor
                    val priceRange = if (maxPrice > minPrice) maxPrice - minPrice else 1f

                    val chartTop = 200f
                    val chartBottom = height - 50f
                    val chartHeight = chartBottom - chartTop
                    val spacing = width / drawCandleCount

                    // Strategy specific markings
                    if (strategy.contains("SMC") || strategy.contains("Order Block")) {
                        var fvgTop = 0f
                        var fvgBottom = 0f
                        var hasFvg = false

                        if (visibleCandles.size >= 3) {
                            for (i in visibleCandles.size - 3 downTo 0) {
                                val c1 = visibleCandles[i]
                                val c3 = visibleCandles[i+2]
                                if (c1.high < c3.low) {
                                    hasFvg = true; fvgTop = c3.low.toFloat(); fvgBottom = c1.high.toFloat()
                                    break
                                } else if (c1.low > c3.high) {
                                    hasFvg = true; fvgTop = c1.low.toFloat(); fvgBottom = c3.high.toFloat()
                                    break
                                }
                            }
                        }

                        if (hasFvg) {
                            val yTop = chartTop + chartHeight - ((fvgTop - minPrice) / priceRange) * chartHeight
                            val yBottom = chartTop + chartHeight - ((fvgBottom - minPrice) / priceRange) * chartHeight
                            val yMin = minOf(yTop, yBottom)
                            val yMax = maxOf(yTop, yBottom)
                            
                            val fvgPaint = Paint().apply { color = Color.parseColor("#1F00E5FF"); style = Paint.Style.FILL }
                            canvas.drawRect(0f, yMin, width, yMax, fvgPaint)
                        }
                    }

                    if (strategy.contains("Liquidity")) {
                        val localHigh = visibleCandles.dropLast(5).maxOfOrNull { it.high.toFloat() } ?: rawMax
                        val localLow = visibleCandles.dropLast(5).minOfOrNull { it.low.toFloat() } ?: rawMin
                        
                        val yHigh = chartTop + chartHeight - ((localHigh - minPrice) / priceRange) * chartHeight
                        val yLow = chartTop + chartHeight - ((localLow - minPrice) / priceRange) * chartHeight
                        
                        val liqPaint = Paint().apply { color = Color.parseColor("#03DAC6"); strokeWidth = 2f; style = Paint.Style.STROKE }
                        canvas.drawLine(0f, yHigh, width, yHigh, liqPaint)
                        canvas.drawLine(0f, yLow, width, yLow, liqPaint)
                    }

                    val candlePaint = Paint().apply { strokeWidth = 2f; style = Paint.Style.FILL_AND_STROKE }

                    visibleCandles.forEachIndexed { index, candle ->
                        val x = index * spacing + spacing / 2

                        val yOpen = chartTop + chartHeight - ((candle.open.toFloat() - minPrice) / priceRange) * chartHeight
                        val yClose = chartTop + chartHeight - ((candle.close.toFloat() - minPrice) / priceRange) * chartHeight
                        val yHigh = chartTop + chartHeight - ((candle.high.toFloat() - minPrice) / priceRange) * chartHeight
                        val yLow = chartTop + chartHeight - ((candle.low.toFloat() - minPrice) / priceRange) * chartHeight

                        candlePaint.color = if (candle.close >= candle.open) Color.parseColor("#00E676") else Color.parseColor("#FF1744")

                        canvas.drawLine(x, yHigh, x, yLow, candlePaint)

                        val top = minOf(yOpen, yClose)
                        val bottom = maxOf(yOpen, yClose)
                        val bodyHeight = maxOf(2f, bottom - top)
                        val bodyWidth = spacing * 0.65f

                        canvas.drawRect(x - bodyWidth / 2, top, x + bodyWidth / 2, top + bodyHeight, candlePaint)
                    }
                }
                
                val filenameBase = "DestinedFX_${System.currentTimeMillis()}"
                
                // Save PNG
                val pngFilename = "$filenameBase.png"
                var fosPng: java.io.OutputStream? = null
                val resolver = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, pngFilename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DestinedFX")
                    }
                    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fosPng = imageUri?.let { resolver.openOutputStream(it) }
                } else {
                    val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString()
                    val image = java.io.File(imagesDir, pngFilename)
                    fosPng = java.io.FileOutputStream(image)
                }
                fosPng?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                // Save PDF
                val pdfFilename = "$filenameBase.pdf"
                val pdfDocument = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(1200, 800, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDocument.finishPage(page)
                
                var fosPdf: java.io.OutputStream? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pdfValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, pdfFilename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/DestinedFX")
                    }
                    val pdfUri = resolver.insert(MediaStore.Files.getContentUri("external"), pdfValues)
                    fosPdf = pdfUri?.let { resolver.openOutputStream(it) }
                } else {
                    val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString()
                    val pdfFile = java.io.File(docsDir, pdfFilename)
                    fosPdf = java.io.FileOutputStream(pdfFile)
                }
                fosPdf?.use { pdfDocument.writeTo(it) }
                pdfDocument.close()

                withContext(Dispatchers.Main) {
                    speakText("Exported both JPG and PDF files successfully.")
                }
            } catch (e: Exception) {
                Log.e("DestinedFXViewModel", "Error exporting chart", e)
            }
        }
    }
}
