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

class MyApplication : Application() {

    lateinit var db: AppDatabase
    val storageAvailable = MutableStateFlow(true)
    lateinit var secretsManager: SecretsManager
    lateinit var marketDataRepository: MarketDataRepositoryImpl
    lateinit var mt5GatewayRepository: MT5GatewayRepository
    lateinit var newsRepository: ForexNewsRepository
    lateinit var riskRepository: RiskRepositoryImpl

    lateinit var tradeRepository: TradeRepository
    lateinit var analysisRepository: com.example.data.AnalysisRepository
    lateinit var cloudSyncManager: com.example.data.cloud.RealtimeCloudSyncManager

    lateinit var derivMarketDataGateway: com.example.data.deriv.DerivMarketDataGateway
    lateinit var derivBrokerGateway: com.example.data.deriv.DerivBrokerGateway
    lateinit var derivGatewayAdapter: com.example.data.broker.DerivBrokerGatewayAdapter
    lateinit var mt5GatewayAdapter: com.example.data.broker.MT5BrokerGatewayAdapter
    lateinit var brokerManager: com.example.data.broker.BrokerManager
    lateinit var autonomousBotEngine: com.destinedfx.core.bot.AutonomousBotEngine

    lateinit var structureAnalyzer: CoreMarketStructureAnalyzer
    lateinit var mtfAlignmentEngine: MTFAlignmentEngine

    lateinit var authUseCase: AuthUseCaseImpl
    lateinit var marketDataUseCase: GetMarketDataUseCaseImpl
    lateinit var signalGenerationUseCase: SignalGenerationUseCaseImpl
    lateinit var tradeUseCase: TradeUseCaseImpl
    lateinit var backtestUseCase: BacktestUseCaseImpl

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
        
        // Initialize critical repositories first
        try {
            db = AppDatabase.getDatabase(this)
            storageAvailable.value = true
            secretsManager = SecretsManager(this)
            riskRepository = RiskRepositoryImpl(this)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MyApplication", "CRITICAL INIT FAILED: ${e.javaClass.simpleName}")
            storageAvailable.value = false
            // Fallback for DB
            db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
                .build()
            secretsManager = SecretsManager(this)
            riskRepository = RiskRepositoryImpl(this)
        }

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("apikey")
            redactHeader("auth-token")
            redactHeader("X-API-KEY")
            redactHeader("x-api-key")
            redactHeader("api-key")
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.metaapi.cloud/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        try {
            derivMarketDataGateway = com.example.data.deriv.DerivMarketDataGateway()
            derivMarketDataGateway.connect()
            marketDataRepository = MarketDataRepositoryImpl(secretsManager, derivMarketDataGateway)
            mt5GatewayRepository = MT5GatewayRepository(secretsManager)
            newsRepository = ForexNewsRepository()
            tradeRepository = TradeRepository(db.tradeDao())
            analysisRepository = com.example.data.AnalysisRepository(db.analysisDao())
            cloudSyncManager = com.example.data.cloud.RealtimeCloudSyncManager(secretsManager)
            
            structureAnalyzer = CoreMarketStructureAnalyzer(
                com.example.domain.engine.impl.StandardFractalDetector(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                com.example.domain.engine.impl.StandardFVGDetector(),
                com.example.domain.engine.impl.StandardOrderBlockDetector(),
                com.example.domain.engine.impl.StandardLiquidityDetector()
            )
            mtfAlignmentEngine = MTFAlignmentEngine()
            
            authUseCase = AuthUseCaseImpl(db, secretsManager)
            marketDataUseCase = GetMarketDataUseCaseImpl(marketDataRepository)
            signalGenerationUseCase = SignalGenerationUseCaseImpl(marketDataRepository, newsRepository)

            derivBrokerGateway = com.example.data.deriv.DerivBrokerGateway(secretsManager)
            derivGatewayAdapter = com.example.data.broker.DerivBrokerGatewayAdapter(derivBrokerGateway)
            mt5GatewayAdapter = com.example.data.broker.MT5BrokerGatewayAdapter(mt5GatewayRepository)
            brokerManager = com.example.data.broker.BrokerManager(derivGatewayAdapter, mt5GatewayAdapter)
            
            tradeUseCase = TradeUseCaseImpl(
                market = marketDataRepository,
                brokerProvider = { brokerManager.activeBroker.value },
                journal = tradeRepository,
                news = newsRepository,
                reports = signalGenerationUseCase,
                config = {
                    val profile = try { riskRepository.getRiskProfile() } catch(e: Exception) { null }
                    val rawRisk = profile?.fixedRiskPercentage ?: 1.0
                    val clampedRisk = if (rawRisk <= 0.0 || rawRisk > 1.0) 1.0 else rawRisk
                    val minRR = if ((profile?.minimumRR ?: 0.0) >= 2.0) profile!!.minimumRR else 2.0
                    com.destinedfx.core.trade.TradingConfig(
                        riskPercent = clampedRisk, 
                        minRR = minRR,
                        portfolio = com.destinedfx.core.risk.PortfolioLimits(
                            maxDailyLossPct = profile?.maxDailyDrawdownPercentage ?: 3.0,
                            maxWeeklyLossPct = profile?.maxWeeklyDrawdownPercentage ?: 6.0
                        )
                    )
                }
            )
            
            backtestUseCase = BacktestUseCaseImpl(marketDataRepository, startingBalance = { try { mt5GatewayRepository.getAccountBalance().getOrNull() } catch(e: Exception) { null } })

            autonomousBotEngine = com.destinedfx.core.bot.AutonomousBotEngine(
                marketDataRepo = marketDataRepository,
                newsRepo = newsRepository,
                tradeUseCase = tradeUseCase,
                tradeRepo = tradeRepository,
                brokerManager = brokerManager,
                secretsManager = secretsManager,
                isEmailVerified = { secretsManager.getSupabaseEmailConfirmedAt() != null },
                isAuthenticated = { secretsManager.getSupabaseAccessToken().isNotBlank() },
                validateSession = { authUseCase.validateCurrentSession().isSuccess }
            )
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MyApplication", "Secondary initialization failed: ${e.javaClass.simpleName}")
            // Ensure all lateinit vars are initialized to avoid crashes
            if (!::derivMarketDataGateway.isInitialized) {
                derivMarketDataGateway = com.example.data.deriv.DerivMarketDataGateway()
                derivMarketDataGateway.connect()
            }
            if (!::marketDataRepository.isInitialized) marketDataRepository = MarketDataRepositoryImpl(secretsManager, derivMarketDataGateway)
            if (!::mt5GatewayRepository.isInitialized) mt5GatewayRepository = MT5GatewayRepository(secretsManager)
            if (!::newsRepository.isInitialized) newsRepository = ForexNewsRepository()
            if (!::tradeRepository.isInitialized) tradeRepository = TradeRepository(db.tradeDao())
            if (!::analysisRepository.isInitialized) analysisRepository = com.example.data.AnalysisRepository(db.analysisDao())
            if (!::cloudSyncManager.isInitialized) cloudSyncManager = com.example.data.cloud.RealtimeCloudSyncManager(secretsManager)
            
            if (!::structureAnalyzer.isInitialized) {
                structureAnalyzer = CoreMarketStructureAnalyzer(
                    com.example.domain.engine.impl.StandardFractalDetector(),
                    com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                    com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                    com.example.domain.engine.impl.StandardStructureShiftDetectors(),
                    com.example.domain.engine.impl.StandardFVGDetector(),
                    com.example.domain.engine.impl.StandardOrderBlockDetector(),
                    com.example.domain.engine.impl.StandardLiquidityDetector()
                )
            }
            if (!::mtfAlignmentEngine.isInitialized) mtfAlignmentEngine = MTFAlignmentEngine()
            
            if (!::authUseCase.isInitialized) authUseCase = AuthUseCaseImpl(db, secretsManager)
            if (!::marketDataUseCase.isInitialized) marketDataUseCase = GetMarketDataUseCaseImpl(marketDataRepository)
            if (!::signalGenerationUseCase.isInitialized) signalGenerationUseCase = SignalGenerationUseCaseImpl(marketDataRepository, newsRepository)
            
            if (!::derivBrokerGateway.isInitialized) derivBrokerGateway = com.example.data.deriv.DerivBrokerGateway(secretsManager)
            if (!::derivGatewayAdapter.isInitialized) derivGatewayAdapter = com.example.data.broker.DerivBrokerGatewayAdapter(derivBrokerGateway)
            if (!::mt5GatewayAdapter.isInitialized) mt5GatewayAdapter = com.example.data.broker.MT5BrokerGatewayAdapter(mt5GatewayRepository)
            if (!::brokerManager.isInitialized) brokerManager = com.example.data.broker.BrokerManager(derivGatewayAdapter, mt5GatewayAdapter)

            if (!::tradeUseCase.isInitialized) {
                tradeUseCase = TradeUseCaseImpl(
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
            }
            if (!::backtestUseCase.isInitialized) backtestUseCase = BacktestUseCaseImpl(marketDataRepository, startingBalance = { null })
            if (!::autonomousBotEngine.isInitialized) {
                autonomousBotEngine = com.destinedfx.core.bot.AutonomousBotEngine(
                    marketDataRepo = marketDataRepository,
                    newsRepo = newsRepository,
                    tradeUseCase = tradeUseCase,
                    tradeRepo = tradeRepository,
                    brokerManager = brokerManager,
                    secretsManager = secretsManager,
                    isEmailVerified = { secretsManager.getSupabaseEmailConfirmedAt() != null },
                    isAuthenticated = { secretsManager.getSupabaseAccessToken().isNotBlank() },
                    validateSession = { if (::authUseCase.isInitialized) authUseCase.validateCurrentSession().isSuccess else false }
                )
            }
        }
    }
}
