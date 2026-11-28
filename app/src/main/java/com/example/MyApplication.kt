package com.example

import android.app.Application
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.market.MarketDataRepositoryImpl
import com.example.data.mt5.MT5GatewayRepository
import com.example.data.news.ForexNewsRepository
import com.example.data.risk.RiskRepositoryImpl
import com.example.data.trade.TradeRepository
import com.example.domain.engine.impl.CoreMarketStructureAnalyzer
import com.example.domain.engine.MTFAlignmentEngine
import com.example.domain.usecase.impl.*
import com.example.security.SecretsManager
import com.example.data.broker.ModeArmingPolicy
import com.example.data.broker.UniversalConnectorDispatcher
import com.example.security.AppSecurityGuard
import com.example.domain.risk.TradingSafetyLimits
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow


class MyApplication : Application() {

    sealed class InitState {
        object Initializing : InitState()
        object Ready : InitState()
        data class Failed(val error: Throwable) : InitState()
    }
    val initStatus = MutableStateFlow<InitState>(InitState.Initializing)
    val appOpenedAtEpochSeconds: Long = System.currentTimeMillis() / 1_000L

    private var _db: AppDatabase? = null
    val db: AppDatabase get() = _db ?: throw IllegalStateException("AppDatabase not initialized")

    val storageAvailable = MutableStateFlow(false)
    val cTraderCallback = MutableStateFlow<android.net.Uri?>(null)
    fun acceptCTraderCallback(uri: android.net.Uri) {
        if (_cTraderOAuthClient?.isCallbackRedirectAllowed(uri) == true) cTraderCallback.value = uri
    }
    fun clearCTraderCallback() { cTraderCallback.value = null }
    
    private var _secretsManager: SecretsManager? = null
    val secretsManager: SecretsManager get() = _secretsManager ?: throw IllegalStateException("SecretsManager not initialized")
    private var _modeArmingPolicy: ModeArmingPolicy? = null
    val modeArmingPolicy: ModeArmingPolicy get() = _modeArmingPolicy ?: throw IllegalStateException("ModeArmingPolicy not initialized")
    private var _universalConnectorDispatcher: UniversalConnectorDispatcher? = null
    val universalConnectorDispatcher: UniversalConnectorDispatcher get() = _universalConnectorDispatcher ?: throw IllegalStateException("UniversalConnectorDispatcher not initialized")

    private var _marketDataRepository: MarketDataRepositoryImpl? = null
    val marketDataRepository: MarketDataRepositoryImpl get() = _marketDataRepository ?: throw IllegalStateException("MarketDataRepository not initialized")
    private var _derivMarketDataGateway: com.example.data.deriv.DerivMarketDataGateway? = null
    val derivMarketDataGateway: com.example.data.deriv.DerivMarketDataGateway
        get() = _derivMarketDataGateway ?: throw IllegalStateException("DerivMarketDataGateway not initialized")

    private var _mt5GatewayRepository: MT5GatewayRepository? = null
    val mt5GatewayRepository: MT5GatewayRepository get() = _mt5GatewayRepository ?: throw IllegalStateException("MT5GatewayRepository not initialized")

    private var _newsRepository: ForexNewsRepository? = null
    val newsRepository: ForexNewsRepository get() = _newsRepository ?: throw IllegalStateException("ForexNewsRepository not initialized")
    private var _analyzerNewsRepository: com.example.data.news.MT5BridgeNewsRepository? = null
    val analyzerNewsRepository: com.example.data.news.MT5BridgeNewsRepository
        get() = _analyzerNewsRepository ?: throw IllegalStateException("MT5BridgeNewsRepository not initialized")

    private var _riskRepository: RiskRepositoryImpl? = null
    val riskRepository: RiskRepositoryImpl get() = _riskRepository ?: throw IllegalStateException("RiskRepository not initialized")

    private var _tradeRepository: TradeRepository? = null
    val tradeRepository: TradeRepository get() = _tradeRepository ?: throw IllegalStateException("TradeRepository not initialized")

    private var _analysisRepository: com.example.data.AnalysisRepository? = null
    val analysisRepository: com.example.data.AnalysisRepository get() = _analysisRepository ?: throw IllegalStateException("AnalysisRepository not initialized")

    private var _cloudSyncManager: com.example.data.cloud.RealtimeCloudSyncManager? = null
    val cloudSyncManager: com.example.data.cloud.RealtimeCloudSyncManager get() = _cloudSyncManager ?: throw IllegalStateException("CloudSyncManager not initialized")

    private var _authUseCase: AuthUseCaseImpl? = null
    val authUseCase: AuthUseCaseImpl get() = _authUseCase ?: throw IllegalStateException("AuthUseCase not initialized")

    private var _marketDataUseCase: GetMarketDataUseCaseImpl? = null
    val marketDataUseCase: GetMarketDataUseCaseImpl get() = _marketDataUseCase ?: throw IllegalStateException("MarketDataUseCase not initialized")

    private var _signalGenerationUseCase: SignalGenerationUseCaseImpl? = null
    val signalGenerationUseCase: SignalGenerationUseCaseImpl get() = _signalGenerationUseCase ?: throw IllegalStateException("SignalGenerationUseCase not initialized")

    private var _backtestUseCase: BacktestUseCaseImpl? = null
    val backtestUseCase: BacktestUseCaseImpl get() = _backtestUseCase ?: throw IllegalStateException("BacktestUseCase not initialized")

    private var _tradeUseCase: TradeUseCaseImpl? = null
    val tradeUseCase: TradeUseCaseImpl get() = _tradeUseCase ?: throw IllegalStateException("TradeUseCase not initialized")

    private var _brokerManager: com.example.data.broker.BrokerManager? = null
    val brokerManager: com.example.data.broker.BrokerManager get() = _brokerManager ?: throw IllegalStateException("BrokerManager not initialized")

    private var _autonomousBotEngine: com.destinedfx.core.bot.AutonomousBotEngine? = null
    val autonomousBotEngine: com.destinedfx.core.bot.AutonomousBotEngine get() = _autonomousBotEngine ?: throw IllegalStateException("AutonomousBotEngine not initialized")

    private var _structureAnalyzer: CoreMarketStructureAnalyzer? = null
    val structureAnalyzer: CoreMarketStructureAnalyzer get() = _structureAnalyzer ?: throw IllegalStateException("StructureAnalyzer not initialized")

    private var _mtfAlignmentEngine: MTFAlignmentEngine? = null
    val mtfAlignmentEngine: MTFAlignmentEngine get() = _mtfAlignmentEngine ?: throw IllegalStateException("MTFAlignmentEngine not initialized")

    private var _cTraderOAuthClient: com.example.data.ctrader.CTraderOAuthClient? = null
    val cTraderOAuthClient: com.example.data.ctrader.CTraderOAuthClient get() = _cTraderOAuthClient ?: throw IllegalStateException("CTraderOAuthClient not initialized")

    private var _cTraderGateway: com.example.data.ctrader.CTraderReadOnlyGateway? = null
    val cTraderGateway: com.example.data.ctrader.CTraderReadOnlyGateway get() = _cTraderGateway ?: throw IllegalStateException("CTraderReadOnlyGateway not initialized")

    private var _derivBrokerGateway: com.example.data.deriv.DerivBrokerGateway? = null
    val derivBrokerGateway: com.example.data.deriv.DerivBrokerGateway get() = _derivBrokerGateway ?: throw IllegalStateException("DerivBrokerGateway not initialized")

    private var _derivGatewayAdapter: com.example.data.broker.DerivBrokerGatewayAdapter? = null
    val derivGatewayAdapter: com.example.data.broker.DerivBrokerGatewayAdapter get() = _derivGatewayAdapter ?: throw IllegalStateException("DerivGatewayAdapter not initialized")

    private var _mt5GatewayAdapter: com.example.data.broker.MT5BrokerGatewayAdapter? = null
    val mt5GatewayAdapter: com.example.data.broker.MT5BrokerGatewayAdapter get() = _mt5GatewayAdapter ?: throw IllegalStateException("MT5GatewayAdapter not initialized")

    // One app-wide warm-up job is shared by startup and Login. It never runs on the UI thread.
    private val mt5WarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mt5WarmupLock = Any()
    @Volatile private var mt5WarmupJob: Deferred<Result<com.example.data.trade.MT5RemoteBridgeClient.ServiceReadiness>>? = null
    @Volatile private var mt5WarmupReadyUntilMs = 0L
    val mt5HostWarmupStatus = MutableStateFlow("IDLE")

    fun prewarmManagedMt5Host() {
        if (storageAvailable.value) scheduleManagedMt5HostWarmup()
    }

    suspend fun pingManagedMt5HostActivity() {
        val secrets = _secretsManager ?: return
        if (!secrets.isSecureStorageAvailable) return
        for (brand in listOf("FBS", "EXNESS", "HFM", "DERIV")) {
            for (mode in listOf("DEMO", "LIVE")) {
                val client = runCatching {
                    com.example.data.trade.MT5RemoteBridgeClient(
                        secrets = secrets,
                        profile = "${brand}_${mode}",
                        accountMode = mode,
                        brokerBrand = brand
                    )
                }.getOrNull() ?: continue
                if (!client.hasPairing() || !client.managedConfigured()) continue
                runCatching { client.touchHostActivity() }
                    .onSuccess { return }
            }
        }
    }

    suspend fun awaitManagedMt5Host(): com.example.data.trade.MT5RemoteBridgeClient.ServiceReadiness {
        check(storageAvailable.value) { "Secure storage is unavailable; Codespaces wake is blocked." }
        val job = scheduleManagedMt5HostWarmup()
            ?: throw IllegalStateException("MT5 host warm-up could not be started.")
        return job.await().getOrElse { throw it }
    }

    private fun scheduleManagedMt5HostWarmup(): Deferred<Result<com.example.data.trade.MT5RemoteBridgeClient.ServiceReadiness>>? {
        val secrets = _secretsManager ?: return null
        if (!secrets.isSecureStorageAvailable) return null
        synchronized(mt5WarmupLock) {
            val now = System.currentTimeMillis()
            mt5WarmupJob?.let { current ->
                if (current.isActive) return current
                if (current.isCompleted && mt5WarmupReadyUntilMs > now) return current
            }
            val next = mt5WarmupScope.async(start = CoroutineStart.LAZY) {
                mt5HostWarmupStatus.value = "CONNECTING"
                try {
                    val readiness = performManagedMt5HostWarmup(secrets)
                    mt5WarmupReadyUntilMs = System.currentTimeMillis() + 15_000L
                    mt5HostWarmupStatus.value = "READY"
                    Result.success(readiness)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mt5WarmupReadyUntilMs = 0L
                    mt5HostWarmupStatus.value = "FAILED"
                    Result.failure(failure)
                }
            }
            mt5WarmupJob = next
            next.start()
            return next
        }
    }

    private suspend fun performManagedMt5HostWarmup(
        secrets: SecretsManager
    ): com.example.data.trade.MT5RemoteBridgeClient.ServiceReadiness {
        val bridge = com.example.data.trade.MT5RemoteBridgeClient(
            secrets = secrets,
            profile = "FBS_DEMO",
            accountMode = "DEMO",
            brokerBrand = "FBS"
        )
        fun ready(value: com.example.data.trade.MT5RemoteBridgeClient.ServiceReadiness): Boolean =
            bridge.managedConfigured() && value.hostRuntimeReady &&
                value.database.equals("ok", ignoreCase = true) &&
                value.managedExecution.equals("configured", ignoreCase = true) &&
                value.perUserTerminalIsolation.equals("VERIFIED", ignoreCase = true)

        // A previously discovered healthy host is a cache hit: do not call GitHub or start it again.
        val cachedReadiness = bridge.checkServiceReadiness()
        if (ready(cachedReadiness)) return cachedReadiness

        val github = com.example.data.codespaces.GitHubCodespacesClient(secrets)
        val lifecycle = com.example.data.codespaces.CodespacesLifecycleClient(secrets)
        val usage = github.usageEstimate()
        val targets = github.listTargetCodespaces()
        val target = targets.singleOrNull()
            ?: error("Exactly one DestinedFX Codespaces host on the configured branch must be discoverable.")
        var current = github.getCodespace(target.name)

        if (!current.state.equals("Available", ignoreCase = true)) {
            check(!usage.blockStart) {
                "HOST WAKE BLOCKED: the local daily or monthly Codespaces usage reserve has been reached."
            }
            github.recordCodespaceStarted(current)
            try {
                github.startCodespace(current)
            } catch (startError: Exception) {
                val observed = runCatching { github.getCodespace(current.name) }.getOrNull()
                if (observed != null && !observed.state.equals("Available", ignoreCase = true)) {
                    runCatching { github.recordCodespaceStopped(observed) }
                }
                throw startError
            }
            withTimeout(10 * 60 * 1000L) {
                while (!current.state.equals("Available", ignoreCase = true)) {
                    delay(5_000L)
                    current = github.getCodespace(target.name)
                    check(!current.state.equals("Failed", ignoreCase = true) &&
                        !current.state.equals("Deleted", ignoreCase = true)) {
                        "GitHub reports that the DestinedFX Codespace failed to start."
                    }
                }
            }
        } else {
            github.recordCodespaceStarted(current)
        }

        check(current.state.equals("Available", ignoreCase = true)) {
            "GitHub did not confirm that the DestinedFX Codespace is Available."
        }
        lifecycle.discoverAndSaveBridge()
        lifecycle.discoverAndSaveManagementApi()
        val readiness = bridge.checkServiceReadiness()
        check(ready(readiness)) {
            "HOST NOT READY: Bridge, durable storage, Management API, or isolated MT5 host verification failed. Broker credentials were not submitted."
        }
        return readiness
    }


    override fun onCreate() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashText = com.destinedfx.core.security.CrashRecorder.format(thread.name, throwable, System.currentTimeMillis())
                // Crash details are redacted and retained only in Android Keystore-backed storage.
                // If initialization has not established secure storage, do not write them in plaintext.
                _secretsManager?.takeIf { it.isSecureStorageAvailable }?.saveLastCrashLog(crashText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MyApplication", "Crash write failed: ${e.javaClass.simpleName}")
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
        super.onCreate()
        initializeDependencies()
    }

    fun initializeDependencies() {
        try {
            // Secure storage must be initialized before Room so the database passphrase can
            // be loaded from Android Keystore-backed encrypted preferences.
            val secrets = SecretsManager(this@MyApplication)
            _secretsManager = secrets
            storageAvailable.value = secrets.isSecureStorageAvailable
            // Start waking the engine while the remaining app dependencies initialize.
            prewarmManagedMt5Host()
            val dbInstance = AppDatabase.getDatabase(this@MyApplication, secrets)
            _db = dbInstance
            _modeArmingPolicy = ModeArmingPolicy(auditSink = secrets::appendModeArmingAudit)
            _universalConnectorDispatcher = UniversalConnectorDispatcher(emptyMap(), modeArmingPolicy)
            _riskRepository = RiskRepositoryImpl(this@MyApplication, secrets)
            
            val derivMarketDataGateway = com.example.data.deriv.DerivMarketDataGateway()
            _derivMarketDataGateway = derivMarketDataGateway
            _marketDataRepository = MarketDataRepositoryImpl(secrets, derivMarketDataGateway)
            _mt5GatewayRepository = MT5GatewayRepository(secrets)
            _newsRepository = ForexNewsRepository()
            _analyzerNewsRepository = com.example.data.news.MT5BridgeNewsRepository(secrets)
            _tradeRepository = TradeRepository(dbInstance.tradeDao())
            _analysisRepository = com.example.data.AnalysisRepository(dbInstance.analysisDao())
            _cloudSyncManager = com.example.data.cloud.RealtimeCloudSyncManager(secrets)
            
            _structureAnalyzer = CoreMarketStructureAnalyzer(
                com.example.domain.engine.impl.StandardFractalDetector(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardFVGDetector(),
                com.example.domain.engine.impl.StandardOrderBlockDetector(),
                com.example.domain.engine.impl.StandardLiquidityDetector()
            )
            _mtfAlignmentEngine = MTFAlignmentEngine()
            
            _authUseCase = AuthUseCaseImpl(dbInstance, secrets)
            _marketDataUseCase = GetMarketDataUseCaseImpl(marketDataRepository)
            _signalGenerationUseCase = SignalGenerationUseCaseImpl(
                marketDataRepository, newsRepository,
                pythonAnalyzer = ChaquopyPythonAnalyzer(this@MyApplication)
            )
            
            _cTraderOAuthClient = com.example.data.ctrader.CTraderOAuthClient(secrets)
            _cTraderGateway = com.example.data.ctrader.CTraderReadOnlyGateway(secrets, cTraderOAuthClient)
            _derivBrokerGateway = com.example.data.deriv.DerivBrokerGateway(secrets, modeArmingPolicy = modeArmingPolicy)
            _derivGatewayAdapter = com.example.data.broker.DerivBrokerGatewayAdapter(derivBrokerGateway)
            _mt5GatewayAdapter = com.example.data.broker.MT5BrokerGatewayAdapter(mt5GatewayRepository)
            _brokerManager = com.example.data.broker.BrokerManager(derivGatewayAdapter, mt5GatewayAdapter)
            
            _tradeUseCase = TradeUseCaseImpl(
                market = marketDataRepository,
                brokerProvider = { brokerManager.activeBroker.value },
                journal = tradeRepository,
                news = newsRepository,
                reports = signalGenerationUseCase,
                config = { 
                    com.destinedfx.core.trade.TradingConfig(
                        riskPercent = TradingSafetyLimits.MAX_TRADE_RISK_PERCENT,
                        minRR = TradingSafetyLimits.MIN_REWARD_TO_RISK,
                        portfolio = com.destinedfx.core.risk.PortfolioLimits(
                            maxDailyLossPct = TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
                            maxWeeklyLossPct = TradingSafetyLimits.MAX_WEEKLY_LOSS_PERCENT
                        )
                    ) 
                },
                sensitiveActionAuthorized = { AppSecurityGuard.authorizeSensitiveAction() }
            )
            _backtestUseCase = BacktestUseCaseImpl(marketDataRepository, startingBalance = { null })
            
            _autonomousBotEngine = com.destinedfx.core.bot.AutonomousBotEngine(
                marketDataRepo = marketDataRepository,
                newsRepo = newsRepository,
                tradeUseCase = tradeUseCase,
                tradeRepo = tradeRepository,
                brokerManager = brokerManager,
                signalGenerationUseCase = signalGenerationUseCase,
                sensitiveActionAuthorized = { AppSecurityGuard.authorizeSensitiveAction() }
            )
            
            initStatus.value = InitState.Ready
            
            // No public Deriv market-data socket is started at app launch.
            // The Analyzer uses the unauthenticated public Deriv market feed; MT5 account state remains separate.
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The repository starts polling workers from its constructor. Stop those workers if
            // a later dependency fails, so a Retry cannot leave duplicate network loops alive.
            try {
                _marketDataRepository?.close()
                _derivMarketDataGateway?.disconnect()
            } catch (cleanupError: Exception) {
                android.util.Log.w("MyApplication", "Partial initialization cleanup failed", cleanupError)
            }
            _marketDataRepository = null
            _derivMarketDataGateway = null
            android.util.Log.e("MyApplication", "Dependency initialization failed", e)
            initStatus.value = InitState.Failed(e)
        }
    }

    fun retryInitialization() {
        // A second tap while the previous retry is running must not construct another dependency graph.
        if (initStatus.value !is InitState.Failed) return
        initStatus.value = InitState.Initializing
        initializeDependencies()
    }
}
