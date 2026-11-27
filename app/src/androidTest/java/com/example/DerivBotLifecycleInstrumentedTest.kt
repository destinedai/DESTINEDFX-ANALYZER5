package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.destinedfx.core.instrument.AssetClassifier
import com.destinedfx.core.marketdata.MarketQuote
import com.destinedfx.core.strategy.StrategyRegistry
import com.example.data.broker.AccountMode
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModePath
import com.example.data.deriv.DerivAccountInfo
import com.example.data.deriv.DerivBotRuntimeGateway
import com.example.data.deriv.DerivBuyResult
import com.example.data.deriv.DerivConnectionState
import com.example.data.deriv.DerivContractInfo
import com.example.data.deriv.DerivExecutionFailure
import com.example.data.deriv.DerivExecutionFailureKind
import com.example.data.deriv.DerivMultiplierIntent
import com.example.data.deriv.DerivPortfolioSnapshot
import com.example.data.deriv.DerivSellResult
import com.example.data.deriv.DerivSubmissionStage
import com.example.domain.model.Candle
import com.example.domain.model.Position
import com.example.domain.repository.AccountMetrics
import com.example.security.AppSecurityGuard
import com.example.service.DerivDemoBotLifecycleHarness
import com.example.service.DerivDemoBotService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Android foreground service + Room/executor, with only the broker replaced by a DEMO fake. */
@RunWith(AndroidJUnit4::class)
class DerivBotLifecycleInstrumentedTest {
    private lateinit var context: Context
    private lateinit var app: MyApplication
    private lateinit var secrets: com.example.security.SecretsManager
    private lateinit var activity: Activity
    private lateinit var gateway: FakeDemoGateway
    private lateinit var identity: ModeAccountIdentity
    private lateinit var credentialGeneration: String
    private lateinit var evidenceFile: File
    private var evidenceUri: Uri? = null
    private val evidenceLock = Any()
    private val eventRows = JSONArray()
    private val tradeCycles = JSONArray()
    private var managedCycleStartIndex = -1
    private var managedCycleCount = 0
    private val openTradeRows = ConcurrentHashMap<String, JSONObject>()
    private val manageTradeRows = ConcurrentHashMap<String, JSONObject>()
    private val accountId = "D-CI-FAKE-1001"
    private val currency = "USD"
    private val sessionId = "destinedfx-ci-fake-session"
    private val rows = JSONArray()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val permission = instrumentation.uiAutomation.executeShellCommand(
            "pm grant " + context.packageName + " android.permission.POST_NOTIFICATIONS"
        )
        ParcelFileDescriptor.AutoCloseInputStream(permission).bufferedReader().use { it.readText() }

        app = context.applicationContext as MyApplication
        secrets = app.secretsManager
        assertTrue(secrets.isSecureStorageAvailable)
        assertTrue(secrets.saveDerivAccountMode("demo"))
        assertTrue(secrets.saveDerivAppId("12345"))
        assertTrue(secrets.saveDerivApiToken("ci-only-fake-demo-token", "demo"))
        credentialGeneration = secrets.getDerivCredentialGeneration("demo")
        assertTrue(credentialGeneration.isNotBlank())
        assertTrue(secrets.saveDerivDemoAcceptance(accountId, currency, sessionId, credentialGeneration))
        identity = ModeAccountIdentity(
            path = ModePath.DERIV_OPTIONS,
            broker = "DERIV",
            accountId = accountId,
            server = "",
            mode = AccountMode.DEMO,
            credentialGeneration = credentialGeneration
        )
        assertTrue(secrets.savePathDemoAcceptance(identity, "ab".repeat(32)))
        val utcDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        assertTrue(secrets.initializeDerivDailyRiskDay(accountId, "demo", utcDay, 100.0))
        assertTrue(secrets.setDerivBotRecoveryEnabled(false))
        assertEquals("demo", secrets.getDerivAccountMode())
        assertEquals(0, app.modeArmingPolicy.activeLeaseCount())
        AppSecurityGuard.unlock()

        gateway = FakeDemoGateway(accountId, currency, sessionId, credentialGeneration)
        DerivDemoBotLifecycleHarness.reset()
        DerivDemoBotLifecycleHarness.gateway = gateway
        DerivDemoBotLifecycleHarness.candleProvider = { symbol, granularity, _ ->
            assertEquals("1HZ100V", symbol)
            assertEquals(60, granularity)
            breakoutCandles()
        }
        DerivDemoBotLifecycleHarness.scanDelayMs = 150L
        DerivDemoBotLifecycleHarness.pauseDelayMs = 40L
        DerivDemoBotLifecycleHarness.activeWaitDelayMs = 40L
        DerivDemoBotLifecycleHarness.eventRecorder = { event -> appendServiceEvent(event) }
        evidenceFile = File(context.filesDir, "bot-lifecycle-evidence.json")
        evidenceFile.delete()
        createSharedEvidenceFile()
        Log.i("DestinedFXBotLifecycle", "EVIDENCE_PATH=${evidenceFile.absolutePath}")
        persistEvidence()
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) {
            if (DerivDemoBotService.state.value != "STOPPED") {
                runCatching { DerivDemoBotService.stop(context) }
                SystemClock.sleep(250L)
            }
            runCatching { secrets.setDerivBotRecoveryEnabled(false) }
            runCatching { secrets.clearPathDemoAcceptance(identity) }
            runCatching { persistEvidence() }
        }
        AppSecurityGuard.lock()
        DerivDemoBotLifecycleHarness.reset()
        if (::activity.isInitialized) runCatching { activity.finish() }
    }

    @Test
    fun demoBotForegroundServiceCompletesLifecycleWithoutDuplicateOrAutomaticRestart() {
        val beforeStart = DerivDemoBotService.state.value
        DerivDemoBotService.start(context, "1HZ100V", StrategyRegistry.BREAKOUT_MOMENTUM.id, "M1", 1.0)
        await("initial demo execution") {
            gateway.purchaseAttempts.get() >= 1 && DerivDemoBotService.state.value == "RUNNING"
        }
        assertEquals(1, DerivDemoBotLifecycleHarness.runStarts.get())
        val initialBuys = gateway.purchaseAttempts.get()
        val firstContract = gateway.purchasedContractIds.last()
        awaitPersistedContract(firstContract)
        DerivDemoBotService.start(context, "1HZ100V", StrategyRegistry.BREAKOUT_MOMENTUM.id, "M1", 1.0)
        SystemClock.sleep(250L)
        assertEquals(1, DerivDemoBotLifecycleHarness.runStarts.get())
        assertEquals(initialBuys, gateway.purchaseAttempts.get())
        assertEquals(gateway.purchaseSignalIds.size, gateway.purchaseSignalIds.toSet().size)
        record("Start", beforeStart, DerivDemoBotService.state.value,
            "One foreground service loop and one broker-confirmed DEMO entry; a repeated Start created no second loop/order.")
        record("Run", "STARTING", DerivDemoBotService.state.value,
            "The Android foreground service entered its active scan loop and obtained fresh broker quote/candle inputs.")

        val beforePause = DerivDemoBotService.state.value
        DerivDemoBotService.pause(context)
        await("Pause state") { DerivDemoBotService.state.value == "PAUSED" }
        assertTrue(gateway.activeContracts.value.containsKey(firstContract))
        assertTrue(gateway.sellRequests.isEmpty())
        gateway.settleByBroker(firstContract)
        SystemClock.sleep(300L)
        assertEquals(initialBuys, gateway.purchaseAttempts.get())
        assertEquals("PAUSED", DerivDemoBotService.state.value)
        record("Pause", beforePause, DerivDemoBotService.state.value,
            "No new purchase occurred during Pause. Existing position stayed broker-managed until external settlement confirmation.")

        val beforeResume = DerivDemoBotService.state.value
        DerivDemoBotService.resume(context)
        await("Resume execution") {
            gateway.purchaseAttempts.get() >= initialBuys + 1 && DerivDemoBotService.state.value == "RUNNING"
        }
        assertEquals(1, DerivDemoBotLifecycleHarness.runStarts.get())
        val secondContract = gateway.purchasedContractIds.last()
        awaitPersistedContract(secondContract)
        assertEquals(gateway.purchaseSignalIds.size, gateway.purchaseSignalIds.toSet().size)
        record("Resume", beforeResume, DerivDemoBotService.state.value,
            "Existing service loop resumed and emitted one new DEMO setup with a different signal identity.")

        val unownedAtStop = gateway.addUnownedBrokerContract()
        val beforeStop = DerivDemoBotService.state.value
        DerivDemoBotService.stop(context)
        await("STOP close-out") {
            DerivDemoBotService.state.value == "STOPPED" && gateway.sellRequests.contains(secondContract)
        }
        assertFalse(gateway.sellRequests.contains(unownedAtStop))
        assertTrue(gateway.activeContracts.value.containsKey(unownedAtStop))
        assertTrue(DerivDemoBotService.statusReason.value.contains("broker confirms all DestinedFX Bot contracts are closed"))
        record("Stop", beforeStop, DerivDemoBotService.state.value,
            "Broker-confirmed close of the Bot-owned contract; an unowned contract was not sold.")

        val buysAfterStop = gateway.purchaseAttempts.get()
        context.startService(Intent(context, DerivDemoBotService::class.java))
        await("no restart after Stop") { DerivDemoBotService.state.value == "STOPPED" }
        SystemClock.sleep(200L)
        assertEquals(buysAfterStop, gateway.purchaseAttempts.get())
        record("NoAutoRestartAfterStop", "STOPPED", DerivDemoBotService.state.value,
            "Null-intent service restart after Stop sent no order.")
        gateway.settleByBroker(unownedAtStop)

        val beforeRestart = DerivDemoBotService.state.value
        DerivDemoBotService.start(context, "1HZ100V", StrategyRegistry.BREAKOUT_MOMENTUM.id, "M1", 1.0)
        await("explicit restart after Stop") {
            gateway.purchaseAttempts.get() >= buysAfterStop + 1 && DerivDemoBotService.state.value == "RUNNING"
        }
        assertEquals(2, DerivDemoBotLifecycleHarness.runStarts.get())
        val thirdContract = gateway.purchasedContractIds.last()
        awaitPersistedContract(thirdContract)
        record("RestartAfterStop", beforeRestart, DerivDemoBotService.state.value,
            "Explicit owner Start created one fresh DEMO entry after Stop.")

        val unownedAtEmergency = gateway.addUnownedBrokerContract()
        val beforeEmergency = DerivDemoBotService.state.value
        // This mirrors the current Emergency-Stop UI sequence; the implementation is unchanged.
        app.modeArmingPolicy.emergencyStop()
        DerivDemoBotService.stop(context)
        secrets.invalidateDerivRealAuthorization()
        await("Emergency-Stop close-out") {
            DerivDemoBotService.state.value == "STOPPED" && gateway.sellRequests.contains(thirdContract)
        }
        assertFalse(gateway.sellRequests.contains(unownedAtEmergency))
        assertTrue(gateway.activeContracts.value.containsKey(unownedAtEmergency))
        assertEquals("demo", secrets.getDerivAccountMode())
        assertEquals(0, app.modeArmingPolicy.activeLeaseCount())
        record("Emergency-Stop", beforeEmergency, DerivDemoBotService.state.value,
            "Existing Emergency-Stop sequence disarmed the mode policy, invalidated REAL authorization and broker-confirmed closing only the Bot-owned contract.")

        val buysAfterEmergency = gateway.purchaseAttempts.get()
        context.startService(Intent(context, DerivDemoBotService::class.java))
        await("no restart after Emergency-Stop") { DerivDemoBotService.state.value == "STOPPED" }
        SystemClock.sleep(200L)
        assertEquals(buysAfterEmergency, gateway.purchaseAttempts.get())
        record("NoAutoRestartAfterEmergencyStop", "STOPPED", DerivDemoBotService.state.value,
            "Null-intent service restart after Emergency-Stop sent no order.")
        gateway.settleByBroker(unownedAtEmergency)

        val beforeSecondRestart = DerivDemoBotService.state.value
        DerivDemoBotService.start(context, "1HZ100V", StrategyRegistry.BREAKOUT_MOMENTUM.id, "M1", 1.0)
        await("explicit restart after Emergency-Stop") {
            gateway.purchaseAttempts.get() >= buysAfterEmergency + 1 && DerivDemoBotService.state.value == "RUNNING"
        }
        assertEquals(3, DerivDemoBotLifecycleHarness.runStarts.get())
        val fourthContract = gateway.purchasedContractIds.last()
        awaitPersistedContract(fourthContract)
        record("RestartAfterEmergencyStop", beforeSecondRestart, DerivDemoBotService.state.value,
            "Explicit owner Start created one new DEMO execution after Emergency-Stop.")
        record("Restart", "STOPPED", DerivDemoBotService.state.value,
            "Explicit owner restart after Emergency-Stop created a fresh DEMO service run; no automatic restart occurred.")

        val beforeCleanup = DerivDemoBotService.state.value
        DerivDemoBotService.stop(context)
        await("final cleanup close-out") {
            DerivDemoBotService.state.value == "STOPPED" && gateway.sellRequests.contains(fourthContract)
        }
        assertEquals(4, gateway.purchaseAttempts.get())
        assertEquals(3, gateway.sellRequests.size)
        record("CleanupStop", beforeCleanup, DerivDemoBotService.state.value,
            "The final Bot-owned contract was broker-confirmed closed; the first contract settled externally during Pause.")

        // Exercise continuous operation through the real Android foreground service,
        // Room ledger and executor. Only broker I/O is replaced by the in-process DEMO fake.
        val buysBeforeCycles = gateway.purchaseAttempts.get()
        managedCycleStartIndex = tradeCycles.length()
        managedCycleCount = 0
        val scanDirection = AtomicInteger(0)
        gateway.autoManageCycles = true
        DerivDemoBotLifecycleHarness.candleProvider = { symbol, granularity, _ ->
            assertEquals("1HZ100V", symbol)
            assertEquals(60, granularity)
            val direction = if (scanDirection.incrementAndGet() % 2 == 1) "LONG" else "SHORT"
            gateway.nextDirection = direction
            breakoutCandles(direction == "LONG")
        }
        DerivDemoBotService.start(context, "1HZ100V", StrategyRegistry.BREAKOUT_MOMENTUM.id, "M1", 1.0)
        await("five new complete managed DEMO cycles and the following opening", timeoutMs = 25_000L) {
            val fifthCycleIndex = managedCycleStartIndex + 4
            tradeCycles.length() > fifthCycleIndex &&
                gateway.purchaseAttempts.get() >= buysBeforeCycles + 6 &&
                tradeCycles.getJSONObject(fifthCycleIndex).optString("next_open_at_utc").isNotBlank()
        }
        val managedCycles = (managedCycleStartIndex until managedCycleStartIndex + 5).map {
            tradeCycles.getJSONObject(it)
        }
        val sides = managedCycles.map { it.getString("trade_side") }.toSet()
        assertEquals("The five new emulator cycles must include BUY and SELL", setOf("BUY", "SELL"), sides)
        assertTrue("Five full cycles must each have a following open",
            managedCycles.all { it.optString("next_open_at_utc").isNotBlank() })
        managedCycleCount = managedCycles.size
        assertEquals(5, managedCycleCount)
        record("FiveManagedDemoCycles", "RUNNING", "RUNNING",
            "Five new broker-confirmed DEMO cycles completed OPEN -> MANAGE -> CLOSE -> NEXT; both BUY and SELL were recorded.")

        DerivDemoBotService.pause(context)
        await("Pause after five cycles") {
            DerivDemoBotService.state.value == "PAUSED" ||
                DerivDemoBotService.statusReason.value.contains("PAUSED")
        }
        val buysDuringPause = gateway.purchaseAttempts.get()
        SystemClock.sleep(300L)
        assertEquals("Pause must not create a new contract", buysDuringPause, gateway.purchaseAttempts.get())
        record("PauseAfterCycles", "RUNNING", DerivDemoBotService.state.value,
            "No new DEMO entries were submitted during Pause after the continuous-cycle run.")

        DerivDemoBotService.resume(context)
        await("Resume after managed cycle pause") {
            gateway.purchaseAttempts.get() > buysDuringPause && DerivDemoBotService.state.value == "RUNNING"
        }
        val resumedContract = gateway.purchasedContractIds.lastOrNull()
            ?: throw AssertionError("Resume reported a purchase attempt without a broker contract identity")
        // Broker purchase acknowledgement precedes the executor's Room commit. Wait for the
        // exact contract ID and buy transaction to be durable before testing production STOP.
        awaitPersistedContract(resumedContract)
        await("resumed Bot-owned contract is still active and broker-sellable") {
            gateway.activeContracts.value[resumedContract]?.isValidToSell == true
        }
        gateway.autoManageCycles = false
        val beforeFinalStop = DerivDemoBotService.state.value
        record("Resume", "PAUSED", beforeFinalStop,
            "The same foreground loop resumed, submitted one new DEMO trade, and durably persisted its broker contract identity before Stop.")
        val activeBeforeFinalStop = resumedContract
        DerivDemoBotService.stop(context)
        await("final STOP after resumed DEMO loop") {
            DerivDemoBotService.state.value == "STOPPED" &&
                gateway.sellRequests.contains(activeBeforeFinalStop)
        }
        val expectedStopReason = "Stopped — Deriv broker confirms all DestinedFX Bot contracts are closed"
        assertEquals("STOP must confirm the entire Bot-owned ledger, not only the last sell request",
            expectedStopReason, DerivDemoBotService.statusReason.value)
        assertFalse("STOP must not sell unowned contracts", gateway.sellRequests.any { it >= 990_000L })
        record("FinalStopAfterCycles", beforeFinalStop, DerivDemoBotService.state.value,
            "STOP confirmed every Bot-owned DEMO contract closed; service reason=${DerivDemoBotService.statusReason.value}; no unowned position was sent to SELL.")
        assertTrue(evidenceFile.isFile && evidenceFile.length() > 100L)
        persistEvidence()
    }

    /**
     * A fake broker confirms a purchase before the executor finishes writing its Room row.
     * STOP is intentionally tested only after the broker-confirmed contract ID and buy
     * transaction ID are durably visible in the same ledger that production STOP consumes.
     */
    private fun awaitPersistedContract(contractId: Long) {
        val index = gateway.purchasedContractIds.indexOf(contractId)
        assertTrue("Fake gateway has no purchase identity for contract $contractId", index >= 0)
        val signalId = gateway.purchaseSignalIds[index]
        await("Room confirmation for broker contract $contractId") {
            runBlocking {
                val row = app.db.derivExecutionDao().findBySignalId(accountId, "DEMO", signalId)
                row?.contractId == contractId &&
                    row.buyTransactionId != null &&
                    row.status in setOf("CONTRACT_CONFIRMED", "CONTRACT_OPEN")
            }
        }
    }

    private fun await(description: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && !condition()) SystemClock.sleep(20L)
        assertTrue(
            "Timed out waiting for $description; state=${DerivDemoBotService.state.value}; reason=${DerivDemoBotService.statusReason.value}",
            condition()
        )
    }

    private fun record(step: String, before: String, after: String, detail: String) {
        val brokerLog = JSONArray()
        gateway.events.forEach { brokerLog.put(it) }
        val row = JSONObject()
            .put("step", step)
            .put("timestamp_utc", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            .put("state_before", before)
            .put("state_after", after)
            .put("account_mode", "DEMO")
            .put("fake_gateway_only", true)
            .put("live_enabled", false)
            .put("purchase_count", gateway.purchaseAttempts.get())
            .put("sell_requests", JSONArray(gateway.sellRequests))
            .put("broker_gateway_log", brokerLog)
            .put("detail", detail)
        rows.put(row)
        Log.i("DestinedFXBotLifecycle", row.toString())
        persistEvidence()
    }

    private fun createSharedEvidenceFile() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "bot-lifecycle-evidence.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DestinedFX")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        evidenceUri = requireNotNull(context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        )) { "Could not create shared emulator evidence file in Downloads/DestinedFX" }
    }

    private fun appendServiceEvent(event: Map<String, String>) {
        synchronized(evidenceLock) {
            val row = JSONObject()
            event.forEach { (key, value) ->
                when (key) {
                    "timestamp_ms", "opened_at_ms", "managed_at_ms", "closed_at_ms",
                    "signal_timestamp_ms", "contract_id" -> row.put(key, value.toLongOrNull() ?: value)
                    "stake", "entry_price", "exit_price", "profit" -> row.put(key, value.toDoubleOrNull() ?: value)
                    "fake_gateway_only", "live_enabled" -> row.put(key, value.toBooleanStrictOrNull() ?: value)
                    else -> row.put(key, value)
                }
            }
            val eventName = event["event"].orEmpty()
            val signalId = event["signal_id"].orEmpty()
            eventRows.put(row)
            when (eventName) {
                "OPEN" -> {
                    openTradeRows[signalId] = row
                    if (tradeCycles.length() > 0) {
                        tradeCycles.getJSONObject(tradeCycles.length() - 1)
                            .put("next_open_at_utc", utc(row.optLong("timestamp_ms")))
                    }
                }
                "MANAGE" -> manageTradeRows[signalId] = row
                "CLOSE" -> {
                    val opened = openTradeRows[signalId]
                    if (opened != null) {
                        val managed = manageTradeRows[signalId]
                        tradeCycles.put(JSONObject()
                            .put("signal_id", signalId)
                            .put("contract_id", row.optLong("contract_id"))
                            .put("account_mode", "DEMO")
                            .put("fake_gateway_only", true)
                            .put("live_enabled", false)
                            .put("symbol", row.optString("symbol"))
                            .put("direction", row.optString("direction"))
                            .put("trade_side", row.optString("trade_side"))
                            .put("product", row.optString("product"))
                            .put("stake", row.optDouble("stake"))
                            .put("lot", row.optString("lot"))
                            .put("entry_price", row.optDouble("entry_price"))
                            .put("exit_price", row.optDouble("exit_price"))
                            .put("profit", row.optDouble("profit"))
                            .put("close_reason", row.optString("close_reason"))
                            .put("scan_at_utc", utc(opened.optLong("signal_timestamp_ms", opened.optLong("timestamp_ms"))))
                            .put("signal_at_utc", utc(opened.optLong("timestamp_ms")))
                            .put("open_at_utc", utc(opened.optLong("timestamp_ms")))
                            .put("manage_at_utc", managed?.let { utc(it.optLong("timestamp_ms")) } ?: utc(row.optLong("timestamp_ms")))
                            .put("close_at_utc", utc(row.optLong("timestamp_ms")))
                            .put("opened_at_ms", row.optLong("opened_at_ms"))
                            .put("managed_at_ms", row.optLong("managed_at_ms"))
                            .put("closed_at_ms", row.optLong("closed_at_ms")))
                        openTradeRows.remove(signalId)
                        manageTradeRows.remove(signalId)
                    }
                }
            }
            persistEvidenceLocked()
        }
    }

    private fun utc(milliseconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(milliseconds))

    private fun evidenceDocument(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("account_mode", "DEMO")
        .put("fake_gateway_only", true)
        .put("live_enabled", false)
        .put("transitions", rows)
        .put("trade_events", eventRows)
        .put("trade_cycles", tradeCycles)
        .put("managed_cycle_start_index", managedCycleStartIndex)
        .put("managed_cycle_count", managedCycleCount)
        .put("stop_reason", DerivDemoBotService.statusReason.value)

    private fun persistEvidence() {
        synchronized(evidenceLock) { persistEvidenceLocked() }
    }

    private fun persistEvidenceLocked() {
        val parent = requireNotNull(evidenceFile.parentFile)
        check(parent.exists() || parent.mkdirs())
        val bytes = evidenceDocument().toString(2).toByteArray(Charsets.UTF_8)
        FileOutputStream(evidenceFile, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        val uri = requireNotNull(evidenceUri) { "Shared emulator evidence URI was not created" }
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Could not open shared emulator evidence output stream" }
            output.write(bytes)
            output.flush()
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        Log.i("DestinedFXBotLifecycle", "EVIDENCE_FLUSHED|internal=${evidenceFile.absolutePath}|shared=Downloads/DestinedFX/bot-lifecycle-evidence.json|bytes=${bytes.size}")
    }

    private fun breakoutCandles(bullish: Boolean = true): List<Candle> {
        val lastTime = System.currentTimeMillis() - 61_000L
        return (0 until 35).map { i ->
            val timestamp = lastTime - (34 - i) * 60_000L
            if (i == 34 && bullish) Candle(timestamp, 100.00, 100.09, 99.99, 100.08, volume = 100.0, complete = true)
            else if (i == 34) Candle(timestamp, 100.00, 100.01, 99.91, 99.92, volume = 100.0, complete = true)
            else Candle(timestamp, 100.00, 100.01, 99.98, 100.00, volume = 100.0, complete = true)
        }
    }

    private class FakeDemoGateway(
        accountId: String,
        currency: String,
        sessionId: String,
        credentialGeneration: String
    ) : DerivBotRuntimeGateway {
        private val account = DerivAccountInfo(
            loginId = accountId,
            currency = currency,
            balance = 100.0,
            email = "lifecycle.fake@destinedfx.invalid",
            accountType = "demo",
            demoVerified = true,
            mode = "demo",
            realExecutionAuthorized = false,
            authenticatedSessionId = sessionId,
            credentialGeneration = credentialGeneration
        )
        override val accountInfo: StateFlow<DerivAccountInfo?> = MutableStateFlow(account)
        override val accountMetrics: MutableStateFlow<AccountMetrics?> =
            MutableStateFlow(AccountMetrics(100.0, 100.0, 0.0, 100.0, 0.0, currency))
        private val metricsUpdated = MutableStateFlow(System.currentTimeMillis())
        override val accountMetricsUpdatedAtMs: StateFlow<Long> = metricsUpdated
        private val active = MutableStateFlow<Map<Long, DerivContractInfo>>(emptyMap())
        override val activeContracts: StateFlow<Map<Long, DerivContractInfo>> = active
        private val portfolio = MutableStateFlow<DerivPortfolioSnapshot?>(
            DerivPortfolioSnapshot(true, accountId, "DEMO", System.currentTimeMillis(), emptyList(), currency = currency)
        )
        override val portfolioSnapshot: StateFlow<DerivPortfolioSnapshot?> = portfolio
        override val connectionState = MutableStateFlow(DerivConnectionState.DISCONNECTED)
        val events = CopyOnWriteArrayList<String>()
        val purchaseAttempts = AtomicInteger(0)
        val purchasedContractIds = CopyOnWriteArrayList<Long>()
        val purchaseSignalIds = CopyOnWriteArrayList<String>()
        val sellRequests = CopyOnWriteArrayList<Long>()
        val managedCloseCount = AtomicInteger(0)
        @Volatile var nextDirection: String = "LONG"
        @Volatile var autoManageCycles: Boolean = false
        private val cycleSequence = AtomicInteger(0)
        private data class FakeTradeMeta(
            val cycle: Int,
            val intent: DerivMultiplierIntent,
            val openedAtMs: Long
        )
        private val tradeMeta = ConcurrentHashMap<Long, FakeTradeMeta>()
        private val ownedIds = ConcurrentHashMap.newKeySet<Long>()
        private val externalIds = AtomicInteger(0)
        private val historical = ConcurrentHashMap<Long, DerivContractInfo>()

        override suspend fun connectAndAuthorize(
            apiToken: String?,
            selectedAccountId: String?,
            expectedMode: String?
        ): Result<DerivAccountInfo> {
            if (!expectedMode.isNullOrBlank() && !expectedMode.equals("demo", true)) {
                return Result.failure(SecurityException("Lifecycle fake rejects non-DEMO sessions"))
            }
            events.add("CONNECT_DEMO|expectedMode=$expectedMode")
            // Preflight data must be refreshed by an authenticated connect, not by fixture setup time.
            refreshSnapshot()
            connectionState.value = DerivConnectionState.READY
            return Result.success(account)
        }

        override suspend fun getQuote(symbol: String): Result<MarketQuote> {
            val now = System.currentTimeMillis()
            events.add("QUOTE|symbol=$symbol|mode=DEMO")
            val quotePrices = if (nextDirection == "SHORT") 99.91 to 99.93 else 100.07 to 100.09
            val quote = MarketQuote.twoSided(
                symbol, AssetClassifier.of(symbol), quotePrices.first, quotePrices.second, now, now,
                "DESTINEDFX_CI_FAKE_DEMO_GATEWAY"
            ) ?: return Result.failure(IllegalStateException("Test quote could not be created"))
            return Result.success(quote)
        }

        override suspend fun fetchPortfolio(): List<Position> {
            refreshManagedProfit()
            refreshSnapshot()
            events.add("PORTFOLIO|contracts=" + active.value.size + "|mode=DEMO")
            return emptyList()
        }

        private fun refreshManagedProfit() {
            if (!autoManageCycles) return
            val now = System.currentTimeMillis()
            active.value.values.forEach { current ->
                val meta = tradeMeta[current.contractId] ?: return@forEach
                if (meta.cycle <= 0 || now - meta.openedAtMs < 60L) return@forEach
                val profit = when (meta.cycle % 3) {
                    1 -> meta.intent.takeProfitAmount + 0.01
                    2 -> -meta.intent.stopLossAmount
                    else -> 0.0
                }
                active.value = active.value + (current.contractId to current.copy(profit = profit))
                events.add("MANAGED_PNL|contract=${current.contractId}|profit=$profit|mode=DEMO")
            }
        }

        override suspend fun lookupHistoricalContract(contractId: Long, sinceMs: Long): DerivContractInfo? =
            historical[contractId]

        override suspend fun executeMultiplier(
            intent: DerivMultiplierIntent,
            allowPurchase: suspend () -> Boolean,
            onStage: suspend (requestId: Int?, proposalId: String?, stage: DerivSubmissionStage) -> Unit
        ): Result<DerivBuyResult> {
            if (!allowPurchase()) {
                events.add("PURCHASE_BLOCKED|signal=" + intent.signalId)
                return Result.failure(DerivExecutionFailure(
                    DerivExecutionFailureKind.PURCHASE_NOT_SENT,
                    "PURCHASE_NOT_SENT: fake DEMO gateway refused a paused entry"
                ))
            }
            val sequence = purchaseAttempts.incrementAndGet()
            val contractId = 880_000L + sequence
            val transactionId = 770_000L + sequence
            val cycle = if (autoManageCycles) cycleSequence.incrementAndGet() else 0
            tradeMeta[contractId] = FakeTradeMeta(cycle, intent, System.currentTimeMillis())
            val contract = DerivContractInfo(
                contractId = contractId,
                transactionId = transactionId,
                symbol = intent.symbol,
                contractType = intent.product.contractType,
                buyPrice = intent.stake,
                bidPrice = 0.0,
                profit = 0.0,
                isSold = false,
                isValidToSell = true,
                status = "open",
                purchaseTime = System.currentTimeMillis() / 1000L,
                expiryTime = null
            )
            active.value = active.value + (contractId to contract)
            ownedIds.add(contractId)
            purchasedContractIds.add(contractId)
            purchaseSignalIds.add(intent.signalId)
            refreshSnapshot()
            val requestId = 4000 + sequence
            val proposalId = "fake-proposal-" + sequence
            onStage(requestId, proposalId, DerivSubmissionStage.PURCHASE_SENT)
            events.add("PURCHASE_CONFIRMED|contract=$contractId|transaction=$transactionId|signal=" + intent.signalId)
            return Result.success(DerivBuyResult(contractId, transactionId, intent.stake, proposalId, requestId))
        }

        override suspend fun sellContract(contractId: Long): Result<DerivSellResult> =
            sellContract(contractId, "STOP")

        override suspend fun sellContract(contractId: Long, closeReason: String): Result<DerivSellResult> {
            if (!ownedIds.contains(contractId)) {
                events.add("SELL_REJECTED_UNOWNED|contract=" + contractId)
                return Result.failure(SecurityException("Fake broker refuses to close unowned contract"))
            }
            val current = active.value[contractId]
                ?: return Result.failure(IllegalStateException("Fake contract is not active"))
            if (!current.isValidToSell) return Result.failure(IllegalStateException("Fake broker did not permit close"))
            val settledProfit = current.profit.takeIf { closeReason != "STOP" } ?: 0.0
            val sold = current.copy(
                bidPrice = (current.buyPrice + settledProfit).coerceAtLeast(0.0),
                profit = settledProfit,
                isSold = true,
                isValidToSell = false,
                status = "sold"
            )
            historical[contractId] = sold
            active.value = active.value - contractId
            ownedIds.remove(contractId)
            sellRequests.add(contractId)
            if (closeReason != "STOP") managedCloseCount.incrementAndGet()
            refreshSnapshot()
            events.add("SELL_CONFIRMED|contract=" + contractId + "|transaction=" + current.transactionId + "|reason=" + closeReason)
            return Result.success(DerivSellResult(contractId, current.transactionId, sold.bidPrice))
        }

        fun addUnownedBrokerContract(): Long {
            val id = 990_000L + externalIds.incrementAndGet()
            val external = DerivContractInfo(
                contractId = id,
                transactionId = id + 100_000L,
                symbol = "1HZ100V",
                contractType = "MULTUP",
                buyPrice = 2.0,
                bidPrice = 0.0,
                profit = 0.0,
                isSold = false,
                isValidToSell = true,
                status = "open",
                purchaseTime = System.currentTimeMillis() / 1000L,
                expiryTime = null
            )
            active.value = active.value + (id to external)
            refreshSnapshot()
            events.add("UNOWNED_POSITION_ADDED|contract=" + id)
            return id
        }

        fun settleByBroker(contractId: Long) {
            val current = active.value[contractId] ?: return
            historical[contractId] = current.copy(
                bidPrice = current.buyPrice + 0.10,
                profit = 0.10,
                isSold = true,
                isValidToSell = false,
                status = "sold"
            )
            active.value = active.value - contractId
            ownedIds.remove(contractId)
            refreshSnapshot()
            events.add("BROKER_SETTLEMENT_CONFIRMED|contract=" + contractId)
        }

        private fun refreshSnapshot() {
            val now = System.currentTimeMillis()
            metricsUpdated.value = now
            accountMetrics.value = AccountMetrics(100.0, 100.0, 0.0, 100.0, 0.0, account.currency)
            portfolio.value = DerivPortfolioSnapshot(
                successful = true,
                accountId = account.loginId,
                accountMode = "DEMO",
                retrievedAtMs = now,
                contracts = active.value.values.toList(),
                currency = account.currency
            )
        }

        override fun disconnect() {
            events.add("DISCONNECT|fake DEMO gateway")
        }
    }
}
