package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MyApplication
import com.example.R
import com.example.data.deriv.DerivMultiplierExecutor
import com.example.data.deriv.DerivMultiplierIntent
import com.example.data.deriv.DerivMultiplierProduct
import com.example.data.deriv.DerivMultiplierRules
import com.example.data.deriv.DerivReconciliationStatus
import com.example.data.deriv.DerivDailyRiskMonitor
import com.example.domain.risk.TradingSafetyLimits
import com.example.domain.model.SignalDirection
import com.destinedfx.core.strategy.StrategyRegistry
import com.destinedfx.core.marketdata.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DerivDemoBotService : Service() {
    companion object {
        const val ACTION_START = "com.example.service.DERIV_DEMO_START"
        const val ACTION_STOP = "com.example.service.DERIV_DEMO_STOP"
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_STRATEGY = "strategy"
        const val EXTRA_TIMEFRAME = "timeframe"
        private const val CHANNEL_ID = "deriv_demo_bot"
        private const val NOTIFICATION_ID = 1701
        private const val PREFS = "deriv_bot_config"
        private const val STATUS_STATE = "service_status_state"
        private const val STATUS_REASON = "service_status_reason"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private fun derivUtcDayKey(nowMs: Long = System.currentTimeMillis()): String {
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return formatter.format(java.util.Date(nowMs))
        }
        private val _state = MutableStateFlow("STOPPED")
        val state: StateFlow<String> = _state.asStateFlow()
        private val _statusReason = MutableStateFlow("BOT NOT RUNNING — not started")
        val statusReason: StateFlow<String> = _statusReason.asStateFlow()

        fun hasNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        fun recordStartBlocked(context: Context, reason: String) {
            val exact = reason.trim().take(240).let {
                if (it.startsWith("BOT NOT RUNNING")) it else "BOT NOT RUNNING — $it"
            }
            publishStatus(context, "STOPPED", exact, disableRecovery = true)
        }

        fun restorePersistedStatus(context: Context) {
            if (_state.value in setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedState = prefs.getString(STATUS_STATE, "STOPPED").orEmpty()
            val savedReason = prefs.getString(STATUS_REASON, "BOT NOT RUNNING — not started").orEmpty()
            if (savedState in setOf("STARTING", "CONNECTING", "RECONCILING", "RUNNING")) {
                publishStatus(context, "STOPPED", "BOT NOT RUNNING — previous service state was not re-verified after process restart. Tap Start to reconnect.")
            } else {
                _state.value = savedState
                _statusReason.value = savedReason.ifBlank { "BOT NOT RUNNING — no active service status" }
            }
        }

        private fun publishStatus(context: Context, state: String, reason: String, disableRecovery: Boolean = false) {
            _state.value = state
            _statusReason.value = reason
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(STATUS_STATE, state).putString(STATUS_REASON, reason)
            if (disableRecovery) editor.putBoolean("enabled", false)
            editor.apply()
        }

        fun start(context: android.content.Context, symbol: String, strategy: String, timeframe: String) {
            if (!hasNotificationPermission(context)) {
                val reason = "BOT NOT RUNNING — enable DestinedFX notifications, then tap Start again."
                recordStartBlocked(context, reason)
                throw SecurityException(reason)
            }
            val intent = Intent(context, DerivDemoBotService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SYMBOL, symbol)
                putExtra(EXTRA_STRATEGY, strategy)
                putExtra(EXTRA_TIMEFRAME, timeframe)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                val reason = "Android restricted foreground-service startup. Open DestinedFX, allow notifications, and start again."
                recordStartBlocked(context, reason)
                throw IllegalStateException(reason, error)
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, DerivDemoBotService::class.java).setAction(ACTION_STOP))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private var healthJob: Job? = null
    @Volatile private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        publishStatus(this, "STARTING", "BOT NOT RUNNING — service is starting and has not verified broker readiness")
        startForeground(NOTIFICATION_ID, notification("Starting — verifying selected Deriv account"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSafely()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                stopRequested = false
                loopJob?.cancel()
                val symbol = intent.getStringExtra(EXTRA_SYMBOL)?.trim().orEmpty()
                val strategy = intent.getStringExtra(EXTRA_STRATEGY)?.trim().orEmpty()
                val timeframe = intent.getStringExtra(EXTRA_TIMEFRAME)?.trim().orEmpty()
                if (symbol.isBlank() || strategy.isBlank() || timeframe.isBlank() ||
                    !DerivMultiplierRules.isSupportedUnderlying(symbol) ||
                    strategy !in StrategyRegistry.managedStrategyIds ||
                    Timeframe.parse(timeframe) == null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                    stopSafely("BOT NOT RUNNING — incomplete or unsupported start intent; trading remains stopped")
                    return START_NOT_STICKY
                }
                if (!hasNotificationPermission(this)) {
                    stopSafely("BOT NOT RUNNING — notification permission is denied; allow notifications and tap Start again")
                    return START_NOT_STICKY
                }
                val startApp = application as? MyApplication
                val currentMode = startApp?.secretsManager?.getDerivAccountMode().orEmpty()
                val accepted = startApp?.secretsManager?.hasCurrentDerivDemoAcceptance() == true
                val policyReady = runCatching { startApp?.modeArmingPolicy }.getOrNull() != null
                val currentGeneration = runCatching { startApp?.secretsManager?.getDerivCredentialGeneration("demo") }.getOrNull().orEmpty()
                val hasCredentials = currentGeneration.isNotBlank() &&
                    startApp?.secretsManager?.getDerivAppId()?.isNotBlank() == true
                if (!policyReady || !hasCredentials || !DerivBotRecoveryPolicy.canStartExplicitDemo(currentMode, accepted, hasNotificationPermission(this))) {
                    stopSafely("BOT NOT RUNNING — select DEMO and complete current account verification and explicit acceptance first")
                    return START_NOT_STICKY
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(EXTRA_SYMBOL, symbol).putString(EXTRA_STRATEGY, strategy)
                    .putString(EXTRA_TIMEFRAME, timeframe).putBoolean("enabled", true).apply()
                update("STARTING — verifying selected account; no order requests will be sent")
                loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
            }
            null -> {
                // Recovery is deliberately DEMO-only. A process restart must never turn a
                // persisted REAL configuration into a new live session.
                val app = application as? MyApplication
                val mode = app?.secretsManager?.getDerivAccountMode()?.lowercase()
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val symbol = prefs.getString(EXTRA_SYMBOL, null)?.trim().orEmpty()
                val strategy = prefs.getString(EXTRA_STRATEGY, null)?.trim().orEmpty()
                val timeframe = prefs.getString(EXTRA_TIMEFRAME, null)?.trim().orEmpty()
                val hasCredentials = app?.secretsManager?.getDerivApiToken("demo")?.isNotBlank() == true &&
                    app?.secretsManager?.getDerivAppId()?.isNotBlank() == true &&
                    app?.secretsManager?.getDerivCredentialGeneration("demo")?.isNotBlank() == true &&
                    app?.let { runCatching { it.modeArmingPolicy }.isSuccess } == true
                val accepted = app?.secretsManager?.hasCurrentDerivDemoAcceptance() == true
                if (DerivBotRecoveryPolicy.canRecoverAfterProcessDeath(
                        mode.orEmpty(), prefs.getBoolean("enabled", false), hasCredentials, accepted,
                        symbol, strategy, timeframe, hasNotificationPermission(this)
                    )) {
                    stopRequested = false
                    loopJob?.cancel()
                    loopJob = serviceScope.launch { runBot(symbol, strategy, timeframe) }
                } else {
                    stopSafely(if (mode == "real") "BOT NOT RUNNING — persisted REAL configuration is never restarted automatically" else "BOT NOT RUNNING — saved DEMO configuration, current acceptance, credentials, or notifications are unavailable")
                    return START_NOT_STICKY
                }
            }
            else -> {
                stopSafely("BOT NOT RUNNING — unsupported or missing service action; explicit DEMO start is required")
                return START_NOT_STICKY
            }
        }
        return if (intent?.action == ACTION_START || intent == null) START_STICKY else START_NOT_STICKY
    }

    private suspend fun runBot(symbol: String, strategyId: String, timeframe: String) {
        val app = application as MyApplication
        val gateway = app.derivBrokerGateway
        if (!DerivMultiplierRules.isSupportedUnderlying(symbol)) {
            stopSafely("Safety blocked — $symbol is not a supported Deriv Multiplier underlying")
            return
        }
        // Keep the user-started foreground service alive through transient broker outages.
        // This is especially important after a persisted daily-loss lock: reconnect and retry
        // broker-confirmed closure rather than silently abandoning an unresolved managed contract.
        while (currentCoroutineContext().isActive && !stopRequested &&
            gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY
        ) {
            if (!app.secretsManager.getDerivAccountMode().equals("demo", true)) {
                stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; this service will not open a REAL session")
                return
            }
            if (connectWithBoundedRetry(gateway)) break
            update("RECONNECTING — Deriv connection retries continue; no new purchases are permitted")
            delay(15_000L)
        }
        if (!currentCoroutineContext().isActive || stopRequested) return
        val acceptedAccount = gateway.accountInfo.value
        if (acceptedAccount == null || !DerivBotRecoveryPolicy.isDemoAccount(acceptedAccount.mode, acceptedAccount.accountType) ||
            acceptedAccount.credentialGeneration.isBlank() ||
            acceptedAccount.credentialGeneration != app.secretsManager.getDerivCredentialGeneration("demo") ||
            !app.secretsManager.hasCurrentDerivDemoAcceptance(acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration)) {
            stopSafely("PAUSED — current authenticated DEMO account does not match the accepted verification record")
            return
        }
        val modeIdentity = com.example.data.broker.ModeAccountIdentity(
            path = com.example.data.broker.ModePath.DERIV_OPTIONS,
            broker = "DERIV",
            accountId = acceptedAccount.loginId,
            server = "",
            mode = com.example.data.broker.AccountMode.DEMO,
            credentialGeneration = acceptedAccount.credentialGeneration
        )
        val checkedAtMs = System.currentTimeMillis()
        val currentMetrics = gateway.accountMetrics.value
        val currentPortfolio = gateway.portfolioSnapshot.value
        val balanceFresh = gateway.accountMetricsUpdatedAtMs.value > 0L &&
            checkedAtMs - gateway.accountMetricsUpdatedAtMs.value in 0..8_000L &&
            currentMetrics?.currency.equals(acceptedAccount.currency, ignoreCase = true) &&
            currentMetrics?.balance?.let { it.isFinite() && it >= 0.0 } == true
        val portfolioFresh = currentPortfolio?.let {
            it.successful && it.accountId == acceptedAccount.loginId &&
                it.accountMode == "DEMO" && it.currency.equals(acceptedAccount.currency, true) &&
                checkedAtMs - it.retrievedAtMs in 0..8_000L
        } == true
        val quote = gateway.getQuote(symbol).getOrNull()
        val quoteFresh = quote?.timestampMs?.let {
            it > 0L && it <= checkedAtMs && checkedAtMs - it <= 15_000L
        } == true
        val readOnlyVerification = com.example.data.broker.ModeReadOnlyVerification(
            identity = modeIdentity,
            checkedAtMs = checkedAtMs,
            authenticatedSession = gateway.connectionState.value == com.example.data.deriv.DerivConnectionState.READY,
            freshAccountData = balanceFresh && portfolioFresh,
            freshQuote = quoteFresh,
            freshHeartbeat = false,
            accountIdentityMatched = gateway.accountInfo.value?.let {
                it.loginId == acceptedAccount.loginId && it.mode.equals("demo", true) &&
                    it.accountType.equals("demo", true) && it.currency.equals(acceptedAccount.currency, true)
            } == true
        )
        val pathAcceptance = app.secretsManager.getPathDemoAcceptance(modeIdentity)
        val connectorValidation = app.universalConnectorDispatcher.validateVerification(
            com.example.data.broker.ConnectorKind.DERIV_OPTIONS,
            com.example.data.broker.ConnectorVerification(
                kind = com.example.data.broker.ConnectorKind.DERIV_OPTIONS,
                evidence = com.example.data.broker.ConnectorEvidence(
                    authenticatedSession = readOnlyVerification.authenticatedSession,
                    freshAccountData = readOnlyVerification.freshAccountData,
                    accountIdentityMatched = readOnlyVerification.accountIdentityMatched,
                    freshQuote = readOnlyVerification.freshQuote
                ),
                detail = "Deriv Options account, balance, portfolio and quote evidence",
                identity = modeIdentity,
                readOnlyVerification = readOnlyVerification,
                demoAcceptance = pathAcceptance,
                currentDemoCredentialGeneration = app.secretsManager.getDerivCredentialGeneration("demo")
            )
        )
        if (connectorValidation.isFailure) {
            stopSafely("BOT NOT RUNNING - DEMO arming blocked: " + (connectorValidation.exceptionOrNull()?.message ?: "MODE_POLICY_BLOCKED"))
            return
        }
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            while (currentCoroutineContext().isActive && !stopRequested) {
                if (_state.value == "RUNNING") {
                    val account = gateway.accountInfo.value
                    val metrics = gateway.accountMetrics.value
                    val portfolio = gateway.portfolioSnapshot.value
                    val now = System.currentTimeMillis()
                    val accountMatches = account != null && DerivBotRecoveryPolicy.isDemoAccount(account.mode, account.accountType) &&
                        account.loginId == acceptedAccount.loginId && account.currency == acceptedAccount.currency &&
                        account.credentialGeneration == acceptedAccount.credentialGeneration &&
                        account.credentialGeneration == app.secretsManager.getDerivCredentialGeneration("demo") &&
                        app.secretsManager.getDerivAccountMode().equals("demo", true) &&
                        app.secretsManager.hasCurrentDerivDemoAcceptance(account.loginId, account.currency, credentialGeneration = account.credentialGeneration)
                    val balanceFresh = metrics != null && metrics.balance.isFinite() && metrics.balance > 0.0 &&
                        account != null && metrics.currency.equals(account.currency, true) &&
                        now - gateway.accountMetricsUpdatedAtMs.value in 0..8_000L
                    val portfolioFresh = portfolio?.successful == true && account != null &&
                        portfolio.accountId == acceptedAccount.loginId && portfolio.accountId == account.loginId &&
                        portfolio.accountMode == "DEMO" && portfolio.currency.equals(account.currency, true) &&
                        now - portfolio.retrievedAtMs in 0..8_000L
                    if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY ||
                        !accountMatches || !balanceFresh || !portfolioFresh
                    ) update("RECONNECTING — authenticated accepted DEMO session or fresh matched risk data is unavailable; purchases paused")
                }
                delay(1_000L)
            }
        }
        suspend fun verifyDerivDailyRiskAtEntryBoundary(accountId: String, mode: String): Boolean {
            val utcDay = derivUtcDayKey()
            if (app.secretsManager.isDerivDailyRiskLocked(accountId, mode, utcDay)) return false
            val currentAccount = gateway.accountInfo.value ?: return false
            if (currentAccount.loginId != accountId || currentAccount.mode.uppercase() != mode.uppercase()) return false
            if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY) return false
            gateway.fetchPortfolio()
            val metrics = gateway.accountMetrics.value ?: return false
            val balanceAge = System.currentTimeMillis() - gateway.accountMetricsUpdatedAtMs.value
            val portfolio = gateway.portfolioSnapshot.value ?: return false
            val portfolioAge = System.currentTimeMillis() - portfolio.retrievedAtMs
            if (!metrics.balance.isFinite() || metrics.balance < 0.0 ||
                !metrics.currency.equals(currentAccount.currency, ignoreCase = true) ||
                balanceAge !in 0..8_000L ||
                !portfolio.successful || portfolio.accountId != accountId ||
                portfolio.accountMode != mode.uppercase() ||
                !portfolio.currency.equals(currentAccount.currency, ignoreCase = true) ||
                portfolioAge !in 0..8_000L || !portfolio.exposure.isFinite() ||
                portfolio.contracts.any { !it.profit.isFinite() }
            ) return false
            val currentEquity = metrics.balance + portfolio.contracts.filterNot { it.isSold }.sumOf { it.profit }
            val baseline = app.secretsManager.getDerivDailyRiskStartingEquity(accountId, mode, utcDay) ?: return false
            val snapshot = DerivDailyRiskMonitor.evaluate(
                baseline, currentEquity, TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
                app.secretsManager.isDerivDailyRiskLocked(accountId, mode, utcDay)
            )
            return when (snapshot.status) {
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.NORMAL -> true
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.WARNING_80 -> {
                    if (app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 80)) {
                        update("Running — 80% daily-loss warning; ${"%.1f".format(snapshot.limitUsedPercent)}% of daily limit consumed")
                    }
                    true
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.WARNING_90 -> {
                    app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 80)
                    if (app.secretsManager.markDerivDailyRiskWarningSent(accountId, mode, utcDay, 90)) {
                        update("Running — 90% daily-loss critical warning; ${"%.1f".format(snapshot.limitUsedPercent)}% of daily limit consumed")
                    }
                    true
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.LOCKED -> {
                    app.secretsManager.lockDerivDailyRisk(accountId, mode, utcDay)
                    false
                }
                com.example.data.deriv.DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE -> false
            }
        }

        val executor = DerivMultiplierExecutor(
            gateway,
            app.db.derivExecutionDao(),
            sensitiveActionAuthorized = { com.example.security.AppSecurityGuard.authorizeSensitiveAction() },
            dailyRiskEntryAllowed = { accountId, mode ->
                mode.equals("demo", ignoreCase = true) && accountId == acceptedAccount.loginId &&
                    app.secretsManager.hasCurrentDerivDemoAcceptance(
                        acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration
                    ) &&
                    verifyDerivDailyRiskAtEntryBoundary(accountId, mode)
            }
        )
        if (!reconcileWithRecovery(executor, gateway)) return
        update("Running — scanning $symbol; no order is assumed confirmed")
        while (currentCoroutineContext().isActive && !stopRequested) {
            try {
                if (!app.secretsManager.getDerivAccountMode().equals("demo", true)) {
                    stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; this service will not reconnect or trade REAL")
                    return
                }
                // Reconcile the portfolio and all prior intents before every new scan.
                // Retryable transport failures reconnect; ambiguous execution outcomes
                // stop the bot for explicit manual review.
                if (!reconcileWithRecovery(executor, gateway)) return
                val account = gateway.accountInfo.value
                if (account == null) {
                    stopSafely("Safety blocked — verified Deriv account snapshot is unavailable")
                    return
                }
                if (account.loginId != acceptedAccount.loginId || account.currency != acceptedAccount.currency ||
                    account.credentialGeneration != acceptedAccount.credentialGeneration ||
                    !app.secretsManager.hasCurrentDerivDemoAcceptance(
                        acceptedAccount.loginId, acceptedAccount.currency, credentialGeneration = acceptedAccount.credentialGeneration
                    )
                ) {
                    stopSafely("Safety blocked — authenticated account or accepted credential generation changed; no new purchase is permitted")
                    return
                }
                if (!DerivBotRecoveryPolicy.isDemoAccount(account.mode, account.accountType) || !DerivMultiplierRules.isVerifiedAccount(account)) {
                    stopSafely("Safety blocked — broker account identity/type is not verified; execution is disabled")
                    return
                }
                val metrics = gateway.accountMetrics.value
                val balanceAge = System.currentTimeMillis() - gateway.accountMetricsUpdatedAtMs.value
                if (metrics == null || !metrics.balance.isFinite() || metrics.balance < 0.0 ||
                    !metrics.currency.equals(account.currency, ignoreCase = true) || balanceAge !in 0..8_000L) {
                    update("RECONNECTING — fresh verified ${account.mode.uppercase()} balance is unavailable; purchases paused")
                    if (!connectWithBoundedRetry(gateway)) {
                        stopSafely("ERROR — could not refresh the authenticated ${account.mode.uppercase()} balance; trading remains stopped")
                        return
                    }
                    continue
                }
                val balance = metrics.balance
                val utcDay = derivUtcDayKey()
                val portfolio = gateway.portfolioSnapshot.value
                val portfolioAge = portfolio?.let { System.currentTimeMillis() - it.retrievedAtMs } ?: Long.MAX_VALUE
                if (portfolio == null || !portfolio.successful || portfolio.accountId != account.loginId ||
                    portfolio.accountMode != account.mode.uppercase() ||
                    !portfolio.currency.equals(account.currency, ignoreCase = true) ||
                    portfolioAge !in 0..8_000L || portfolio.contracts.any { !it.profit.isFinite() }
                ) {
                    update("RECONNECTING — daily risk cannot be verified from a fresh account-matched Deriv portfolio; purchases paused")
                    if (!connectWithBoundedRetry(gateway)) {
                        stopSafely("ERROR — Deriv daily risk data could not be refreshed; trading remains stopped")
                        return
                    }
                    continue
                }
                val floatingProfit = portfolio.contracts.filterNot { it.isSold }.sumOf { it.profit }
                val currentEquity = balance + floatingProfit
                if (!currentEquity.isFinite()) {
                    stopSafely("Safety blocked — Deriv daily equity is not finite; no new entry is permitted")
                    return
                }
                val existingDailyBaseline = app.secretsManager.getDerivDailyRiskStartingEquity(account.loginId, account.mode, utcDay)
                if (existingDailyBaseline == null &&
                    !DerivDailyRiskMonitor.canInitializeDailyBaseline(System.currentTimeMillis())
                ) {
                    stopSafely("Safety blocked — Deriv daily-risk baseline was not captured near UTC midnight; new entries remain blocked until the next UTC day or a supported broker-history baseline is available")
                    return
                }
                if (!app.secretsManager.initializeDerivDailyRiskDay(account.loginId, account.mode, utcDay, currentEquity)) {
                    stopSafely("Safety blocked — secure Deriv daily-risk baseline could not be persisted")
                    return
                }
                val startEquity = app.secretsManager.getDerivDailyRiskStartingEquity(account.loginId, account.mode, utcDay)
                val dailyLocked = app.secretsManager.isDerivDailyRiskLocked(account.loginId, account.mode, utcDay)
                val dailyRisk = DerivDailyRiskMonitor.evaluate(
                    startEquity,
                    currentEquity,
                    TradingSafetyLimits.MAX_DAILY_LOSS_PERCENT,
                    dailyLocked
                )
                if (dailyRisk.status == com.example.data.deriv.DerivDailyRiskSnapshot.Status.DATA_UNAVAILABLE) {
                    stopSafely("Safety blocked — Deriv daily-loss calculation is unavailable; no new entry is permitted")
                    return
                }
                if (dailyRisk.status == com.example.data.deriv.DerivDailyRiskSnapshot.Status.LOCKED) {
                    if (!app.secretsManager.lockDerivDailyRisk(account.loginId, account.mode, utcDay)) {
                        stopSafely("Safety blocked — Deriv daily-loss lock could not be persisted; trading remains stopped")
                        return
                    }
                    update("Safety blocked — DERIV DAILY LOSS LIMIT REACHED; new entries locked, attempting broker-confirmed close of DestinedFX contracts")
                    val confirmedClosed = executor.closeBotOwnedContracts()
                    update(if (confirmedClosed) {
                        "Safety blocked — DAILY LOSS LOCKED; broker confirms all DestinedFX-managed contracts are settled. Lock remains until the next UTC day."
                    } else {
                        "Safety blocked — DAILY LOSS LOCKED; one or more DestinedFX contract closures remain unconfirmed. Monitoring and retrying; check broker account immediately."
                    })
                    delay(10_000)
                    continue
                }
                val warningMessages = mutableListOf<String>()
                if (dailyRisk.limitUsedPercent >= TradingSafetyLimits.DAILY_WARNING_80_PERCENT &&
                    app.secretsManager.markDerivDailyRiskWarningSent(account.loginId, account.mode, utcDay, 80)
                ) warningMessages += "80% daily-loss warning"
                if (dailyRisk.limitUsedPercent >= TradingSafetyLimits.DAILY_WARNING_90_PERCENT &&
                    app.secretsManager.markDerivDailyRiskWarningSent(account.loginId, account.mode, utcDay, 90)
                ) warningMessages += "90% daily-loss critical warning"
                if (warningMessages.isNotEmpty()) {
                    update("Running — ${warningMessages.joinToString(" + ")}; ${"%.1f".format(dailyRisk.limitUsedPercent)}% of daily limit consumed")
                }
                val stake = DerivMultiplierRules.safeStakeForBalance(balance)
                val minimumStake = com.destinedfx.core.broker.DerivContractLimits.getMinimumStake(
                    symbol, DerivMultiplierProduct.MULTUP.contractType
                )
                if (stake < minimumStake) {
                    stopSafely("Safety blocked — 1% risk cap permits $stake, below broker minimum $minimumStake; no order was sent")
                    return
                }
                // syncKnownContracts() is already performed by reconcileWithRecovery()
                // against the same fresh, account-matched portfolio snapshot.
                if (executor.hasActiveContracts()) {
                    update("Running — an existing contract is still active; waiting for broker settlement before another entry")
                    delay(10_000)
                    continue
                }
                val signal = app.signalGenerationUseCase.generateSignal(symbol, timeframe, strategyId)
                val planError = if (signal.executable && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT))
                    DerivMultiplierRules.planConsistencyError(signal.direction, signal.entryPrice, signal.stopLoss, signal.takeProfit) else null
                if (planError != null) {
                    update("Running — signal rejected: ${planError.take(120)}")
                } else if (signal.executable && signal.direction in setOf(SignalDirection.LONG, SignalDirection.SHORT)) {
                    val stableId = signal.id.takeIf { it.isNotBlank() }
                    if (stableId == null) {
                        update("Running — signal rejected: analyzer did not supply a stable identity")
                        delay(10_000)
                        continue
                    }
                    val intent = DerivMultiplierIntent(
                        signalId = stableId,
                        symbol = symbol,
                        strategyId = strategyId,
                        strategyVersion = signal.strategyVersion.ifBlank { "unknown" },
                        direction = signal.direction,
                        product = if (signal.direction == SignalDirection.LONG) DerivMultiplierProduct.MULTUP else DerivMultiplierProduct.MULTDOWN,
                        stake = stake,
                        currency = account.currency,
                        multiplier = 10,
                        stopLossAmount = stake,
                        takeProfitAmount = stake * TradingSafetyLimits.MIN_REWARD_TO_RISK,
                        signalTimestampMs = signal.timestamp
                    )
                    update("Submitting — broker proposal required for ${intent.product.contractType}")
                    val result = executor.submit(intent)
                    if (result.isSuccess) update("Contract confirmed — ${result.getOrThrow().contractId}; monitoring broker state")
                    else {
                        val reason = result.exceptionOrNull()?.message?.take(180) ?: "Execution blocked"
                        // An ambiguous purchase or unresolved ledger record requires manual review.
                        // A transient broker/portfolio/balance failure must reconnect and reconcile
                        // before another scan; it must never be presented as a normal skipped entry.
                        when {
                            reason.startsWith("EXECUTION_UNKNOWN") || reason.startsWith("RECONCILIATION_REQUIRED") ->
                                stopSafely("Safety blocked — $reason. Manual reconciliation is required.")
                            reason.contains("REAL_EXECUTION_DISABLED") ->
                                stopSafely("Safety blocked — REAL authorization expired or changed. Re-verify readiness and explicitly arm this account again.")
                            reason.startsWith("PORTFOLIO_RETRYABLE") || reason.startsWith("PORTFOLIO_REQUIRED") || reason.startsWith("BALANCE_REQUIRED") -> {
                                update("RECONNECTING — $reason; purchases paused")
                                if (!connectWithBoundedRetry(gateway)) {
                                    stopSafely("ERROR — reconnect exhausted after portfolio validation failure; trading remains stopped")
                                    return
                                }
                            }
                            reason.contains("DAILY_RISK_RECHECK_FAILED") -> {
                                val utcDay = derivUtcDayKey()
                                val dailyLocked = app.secretsManager.isDerivDailyRiskLocked(account.loginId, account.mode, utcDay)
                                if (dailyLocked) {
                                    update("Safety blocked — Deriv daily-loss limit locked at the purchase boundary; attempting to close DestinedFX-managed contracts")
                                    val closed = executor.closeBotOwnedContracts()
                                    update(if (closed) {
                                        "Safety blocked — daily lock remains active; broker confirms DestinedFX-managed contracts settled"
                                    } else {
                                        "Safety blocked — daily lock remains active; broker has not confirmed all DestinedFX-managed contracts settled"
                                    })
                                    delay(10_000)
                                } else {
                                    stopSafely("Safety blocked — Deriv risk could not be reverified immediately before purchase; trading remains stopped")
                                    return
                                }
                            }
                            else -> update("Running — entry skipped: $reason")
                        }
                    }
                } else {
                    update("Running — no valid fresh Multiplier signal")
                }
                delay(30_000)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                update("RECONNECTING — purchases paused: ${error.message?.take(140)}")
                if (!connectWithBoundedRetry(gateway)) {
                    stopSafely("ERROR — bounded reconnect policy exhausted; reconciliation required")
                    return
                }
            }
        }
    }

    private suspend fun reconcileWithRecovery(
        executor: DerivMultiplierExecutor,
        gateway: com.example.data.deriv.DerivBrokerGateway
    ): Boolean {
        // Retryable transport/portfolio failures are not terminal service failures. Keep the
        // foreground monitor alive, preserve the account/mode daily lock, and retry until the
        // broker is reachable. A manual reconciliation requirement remains a hard stop.
        while (currentCoroutineContext().isActive && !stopRequested) {
            if ((application as? MyApplication)?.secretsManager?.getDerivAccountMode()?.equals("demo", true) != true) {
                stopSafely("BOT NOT RUNNING — Deriv mode changed from DEMO; reconciliation and REAL login are blocked")
                return false
            }
            val result = executor.reconcileBeforeNewEntriesResult()
            when (result.status) {
                DerivReconciliationStatus.READY -> {
                    if (executor.syncKnownContracts()) return true
                    update("RECONNECTING — fresh portfolio refresh failed; purchases remain paused")
                }
                DerivReconciliationStatus.MANUAL_RECONCILIATION_REQUIRED -> {
                    stopSafely("Safety blocked — ${result.reason}. Manual broker reconciliation is required; no new purchase will be sent.")
                    return false
                }
                DerivReconciliationStatus.RETRYABLE_FAILURE -> {
                    update("RECONNECTING — ${result.reason.take(140)}; purchases remain paused")
                }
            }
            if (gateway.connectionState.value != com.example.data.deriv.DerivConnectionState.READY) {
                if (!connectWithBoundedRetry(gateway)) {
                    update("RECONNECTING — bounded attempt batch exhausted; the foreground service will retry")
                    delay(15_000L)
                }
            } else {
                delay(5_000L)
            }
        }
        return false
    }

    private suspend fun connectWithBoundedRetry(gateway: com.example.data.deriv.DerivBrokerGateway): Boolean {
        val job = currentCoroutineContext()[Job]
        val connected = retryFreshDerivAuthorization(
            maxAttempts = MAX_RECONNECT_ATTEMPTS,
            shouldContinue = {
                !stopRequested && job?.isActive != false &&
                    (application as? MyApplication)?.secretsManager?.getDerivAccountMode()?.equals("demo", true) == true
            },
            onAttempt = { attempt ->
                update(if (attempt == 0) "CONNECTING — verifying selected Deriv account" else "RECONNECTING — attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS")
            },
            pause = { delay(it) },
            reconnect = {
                // Each call repeats account listing and OTP retrieval; never reuse an earlier one-time OTP.
                gateway.connectAndAuthorize(expectedMode = "demo")
            }
        )
        if (connected) update("RECONCILING — fresh authenticated broker session established")
        return connected
    }

    private fun stopSafely(message: String = "Stopped — no new entry requests") {
        stopRequested = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        loopJob?.cancel()
        loopJob = null
        healthJob?.cancel()
        healthJob = null
        update(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(text: String) {
        val nextState = when {
            text.startsWith("Running") || text.startsWith("Submitting") || text.startsWith("Contract") -> "RUNNING"
            text.startsWith("STARTING") -> "STARTING"
            text.startsWith("CONNECTING") || text.startsWith("Connecting") || text.startsWith("RECONNECTING") -> "CONNECTING"
            text.startsWith("RECONCILING") -> "RECONCILING"
            text.startsWith("ERROR") -> "ERROR"
            text.startsWith("BOT NOT RUNNING") -> "STOPPED"
            text.startsWith("Stopped") -> "STOPPED"
            text.startsWith("Safety blocked") -> "PAUSED"
            else -> "PAUSED"
        }
        val reason = when {
            nextState == "RUNNING" -> text
            text.startsWith("BOT NOT RUNNING") -> text
            else -> "BOT NOT RUNNING — $text"
        }
        publishStatus(this, nextState, reason)
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("DestinedFX · Deriv DEMO bot · ${_state.value}")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deriv account bot", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopRequested = true
        loopJob?.cancel()
        healthJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
