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
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MyApplication : Application() {

    sealed class InitState {
        object Initializing : InitState()
        object Ready : InitState()
        data class Failed(val error: Throwable) : InitState()
    }
    val initStatus = MutableStateFlow<InitState>(InitState.Initializing)

    private var _db: AppDatabase? = null
    val db: AppDatabase get() = _db ?: throw IllegalStateException("AppDatabase not initialized")

    val storageAvailable = MutableStateFlow(true)
    
    private var _secretsManager: SecretsManager? = null
    val secretsManager: SecretsManager get() = _secretsManager ?: throw IllegalStateException("SecretsManager not initialized")

    private var _marketDataRepository: MarketDataRepositoryImpl? = null
    val marketDataRepository: MarketDataRepositoryImpl get() = _marketDataRepository ?: throw IllegalStateException("MarketDataRepository not initialized")

    private var _mt5GatewayRepository: MT5GatewayRepository? = null
    val mt5GatewayRepository: MT5GatewayRepository get() = _mt5GatewayRepository ?: throw IllegalStateException("MT5GatewayRepository not initialized")

    private var _newsRepository: ForexNewsRepository? = null
    val newsRepository: ForexNewsRepository get() = _newsRepository ?: throw IllegalStateException("ForexNewsRepository not initialized")

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

    private var _derivBrokerGateway: com.example.data.deriv.DerivBrokerGateway? = null
    val derivBrokerGateway: com.example.data.deriv.DerivBrokerGateway get() = _derivBrokerGateway ?: throw IllegalStateException("DerivBrokerGateway not initialized")

    private var _derivGatewayAdapter: com.example.data.broker.DerivBrokerGatewayAdapter? = null
    val derivGatewayAdapter: com.example.data.broker.DerivBrokerGatewayAdapter get() = _derivGatewayAdapter ?: throw IllegalStateException("DerivGatewayAdapter not initialized")

    private var _mt5GatewayAdapter: com.example.data.broker.MT5BrokerGatewayAdapter? = null
    val mt5GatewayAdapter: com.example.data.broker.MT5BrokerGatewayAdapter get() = _mt5GatewayAdapter ?: throw IllegalStateException("MT5GatewayAdapter not initialized")


    override fun onCreate() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashText = com.destinedfx.core.security.CrashRecorder.format(thread.name, throwable, System.currentTimeMillis())
                com.destinedfx.core.security.CrashLogManager(filesDir).writeCrash(crashText)
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
            // Initialize
            val dbInstance = AppDatabase.getDatabase(this@MyApplication)
            _db = dbInstance
            val secrets = SecretsManager(this@MyApplication)
            _secretsManager = secrets
            _riskRepository = RiskRepositoryImpl(this@MyApplication)
            
            val derivMarketDataGateway = com.example.data.deriv.DerivMarketDataGateway()
            
            _marketDataRepository = MarketDataRepositoryImpl(secrets, derivMarketDataGateway)
            _mt5GatewayRepository = MT5GatewayRepository(secrets)
            _newsRepository = ForexNewsRepository()
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
            _signalGenerationUseCase = SignalGenerationUseCaseImpl(marketDataRepository, newsRepository)
            
            _derivBrokerGateway = com.example.data.deriv.DerivBrokerGateway(secrets)
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
                        riskPercent = 1.0, 
                        minRR = 2.0,
                        portfolio = com.destinedfx.core.risk.PortfolioLimits(maxDailyLossPct = 3.0, maxWeeklyLossPct = 6.0)
                    ) 
                }
            )
            _backtestUseCase = BacktestUseCaseImpl(marketDataRepository, startingBalance = { null })
            
            _autonomousBotEngine = com.destinedfx.core.bot.AutonomousBotEngine(
                marketDataRepo = marketDataRepository,
                newsRepo = newsRepository,
                tradeUseCase = tradeUseCase,
                tradeRepo = tradeRepository,
                brokerManager = brokerManager,
                secretsManager = secrets,
                isEmailVerified = { secrets.getSupabaseEmailConfirmedAt() != null },
                isAuthenticated = { secrets.getSupabaseAccessToken().isNotBlank() },
                validateSession = { authUseCase.validateCurrentSession().isSuccess }
            )
            
            initStatus.value = InitState.Ready
            
            // Asynchronous connection
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    derivMarketDataGateway.connect()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("MyApplication", "Deriv gateway connect failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MyApplication", "Initialization warning: ${e.message}", e)
            initStatus.value = InitState.Ready
        }
    }

    fun retryInitialization() {
        if (initStatus.value is InitState.Ready) return
        initStatus.value = InitState.Initializing
        initializeDependencies()
    }
}
