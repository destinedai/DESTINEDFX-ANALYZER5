package com.example.ui.bot

import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.HostProfile
import com.example.data.broker.LoginMode
import com.example.data.broker.SavedBrokerAccountProfile
import com.example.data.mt5.MT5AccountSlot
import com.example.data.trade.BridgeGatewayException
import com.example.data.trade.BridgeGatewayFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake backend below is a labelled FIXTURE. A passing test here is not proof that any broker logs in. */
class BrokerLoginModelTest {
    private val mt5 = BrokerLoginSpecs.byId("FBS_MT5")!!
    private val derivOptions = BrokerLoginSpecs.byId("DERIV_OPTIONS")!!
    private val ctrader = BrokerLoginSpecs.byId("CTRADER")!!
    private val goodForm = LoginForm(type = AccessType.TRADE, mode = LoginMode.DEMO, login = "123456", password = "fixture-secret", server = "Fixture-Demo")

    private class FakeBackend(
        private val script: List<ConnectObservation>,
        private val failSubmit: Exception? = null,
        private val preflightFailure: String? = null,
    ) : ConnectBackend {
        var submitted = 0
        private var index = 0
        override suspend fun submit(spec: BrokerLoginSpec, form: LoginForm) { failSubmit?.let { throw it }; submitted++ }
        override suspend fun observe(): ConnectObservation = script[minOf(index++, script.lastIndex)]
        override suspend fun preflight(spec: BrokerLoginSpec, form: LoginForm): String? = preflightFailure
    }

    private fun useCase(backend: ConnectBackend, timeoutMs: Long = 120_000, clockStep: Long = 0): ConnectUseCase {
        var now = 0L
        return ConnectUseCase(backend, pollIntervalMs = 1, timeoutMs = timeoutMs, clock = { now.also { now += clockStep } }, pause = {})
    }

    // ---- Brokers list and search ----
    @Test fun rowsComeFromTheSpecsAndNeverInventCompanyNames() {
        val rows = BrokerCatalog.rows()
        assertEquals(BrokerLoginSpecs.all.map { it.id }, rows.map { it.specId })
        assertEquals("Deriv.com Limited", rows.first { it.specId == "DERIV_MT5" }.title)
        assertEquals("FBS Markets Inc.", rows.first { it.specId == "FBS_MT5" }.title)
        rows.filter { it.specId in setOf("EXNESS_MT5", "HFM_MT5") }.forEach { assertFalse(it.companyVerified) }
        assertEquals("Exness MT5", rows.first { it.specId == "EXNESS_MT5" }.title)
    }

    @Test fun savedAccountsCombineBothMt5ModesWithProviderProfiles() {
        fun mt5Slot(key: String, mode: String, login: String) = MT5AccountSlot(
            accountId = key,
            broker = "FBS",
            platform = "MT5",
            accountMode = mode,
            login = login,
            server = "Fixture-$mode",
            accessType = "INVESTOR",
            connected = false,
            passwordSaved = false,
        )
        val saved = SavedLoginAccounts.build(
            mt5Profiles = listOf(mt5Slot("FBS_DEMO", "DEMO", "111111"), mt5Slot("FBS_LIVE", "LIVE", "222222")),
            providerProfiles = listOf(
                SavedBrokerAccountProfile("DERIV_OPTIONS", "V100001", "", LoginMode.DEMO),
                SavedBrokerAccountProfile("DERIV_CTRADER", "7001", "Deriv", LoginMode.LIVE, "8001"),
                SavedBrokerAccountProfile("CTRADER", "9001", "Fixture broker", LoginMode.DEMO, "9011"),
            ),
        )
        assertEquals(
            setOf("FBS_MT5", "DERIV_OPTIONS", "DERIV_CTRADER", "CTRADER"),
            saved.map { it.spec.id }.toSet(),
        )
        assertTrue(saved.any { it.profileKey == "FBS_LIVE" && it.mode == LoginMode.LIVE })
        assertTrue(saved.any { it.profileKey == "7001" && it.login == "8001" && it.server == "Deriv" })
    }

    @Test fun searchFiltersByBrandAndAliasAsTheOwnerTypes() {
        val rows = BrokerCatalog.rows()
        assertEquals(rows, BrokerCatalog.search(rows, "  "))
        assertTrue(BrokerCatalog.search(rows, "fbs").map { it.specId }.contains("FBS_MT5"))
        assertTrue(BrokerCatalog.search(rows, "hf markets").map { it.specId }.contains("HFM_MT5"))
        assertTrue(BrokerCatalog.search(rows, "deriv.com").map { it.specId }.contains("DERIV_MT5"))
        assertTrue(BrokerCatalog.search(rows, "zzzz-no-such-broker").isEmpty())
    }

    @Test fun cantFindYourBrokerCreatesAnUnverifiedManualProfile() {
        val profile = ManualBrokerProfile.create("  Some Broker Ltd  ")!!
        assertEquals("unverified broker", profile.status)
        assertEquals("Some Broker Ltd", profile.server)
        assertNull(ManualBrokerProfile.create("   "))
    }

    // ---- Login form rules ----
    @Test fun mt5LoginNeedsNumericLoginPasswordAndServer() {
        assertTrue(LoginRules.loginEnabled(mt5, goodForm))
        assertFalse(LoginRules.loginEnabled(mt5, goodForm.copy(login = "12ab")))
        assertFalse(LoginRules.loginEnabled(mt5, goodForm.copy(login = "")))
        assertFalse(LoginRules.loginEnabled(mt5, goodForm.copy(password = " ")))
        assertFalse(LoginRules.loginEnabled(mt5, goodForm.copy(server = "")))
    }

    @Test fun derivOptionsNeedsAppIdAndTokenAndCtraderUsesGrantAccess() {
        assertFalse(LoginRules.loginEnabled(derivOptions, LoginForm(appId = "1089")))
        assertTrue(LoginRules.loginEnabled(derivOptions, LoginForm(appId = "1089", token = "fixture-token")))
        assertTrue(LoginRules.loginEnabled(ctrader, LoginForm()))
    }

    @Test fun investorTypeIsOnlyOfferedWhereTheSpecAllowsIt() {
        assertEquals(listOf(AccessType.TRADE, AccessType.INVESTOR), LoginRules.availableTypes(mt5))
        assertEquals(listOf(AccessType.TRADE), LoginRules.availableTypes(derivOptions))
        assertEquals("INVESTOR", AccessType.INVESTOR.wireValue)
        assertEquals("MASTER", AccessType.TRADE.wireValue)
    }

    @Test fun savePasswordIsOnlyDefaultOnWithASecureLockScreen() {
        assertEquals(SavePasswordPolicy(true, true, null), LoginRules.savePasswordPolicy(true))
        val off = LoginRules.savePasswordPolicy(false)
        assertFalse(off.defaultOn); assertFalse(off.enabled); assertTrue(off.reason!!.isNotBlank())
    }

    @Test fun serverModeIsOnlyAHintNeverAConfirmation() {
        assertEquals(ServerModeHint.DEMO_LIKELY, LoginRules.serverModeHint("Deriv-Demo"))
        assertEquals(ServerModeHint.UNKNOWN, LoginRules.serverModeHint("Deriv-Server"))
    }

    @Test fun modeDefaultsFromDemoOrTrialServerAndCanBeChangedByOwner() {
        assertEquals(LoginMode.DEMO, LoginRules.modeForServer("Broker-Demo"))
        assertEquals(LoginMode.DEMO, LoginRules.modeForServer("Broker-Trial"))
        assertEquals(LoginMode.LIVE, LoginRules.modeForServer("Broker-Real"))
        assertEquals(LoginMode.LIVE, LoginRules.modeForServer(""))
        val live = LoginRules.withMode(goodForm, LoginMode.LIVE)
        assertEquals(AccessType.INVESTOR, live.type)
        assertEquals(LoginMode.DEMO, LoginRules.withMode(live, LoginMode.DEMO).mode)
        assertEquals(AccessType.TRADE, LoginRules.withMode(live, LoginMode.DEMO).type)
    }

    @Test fun liveTradeAccountIsOnlySelectableOnPersistentHost() {
        assertEquals(listOf(AccessType.TRADE, AccessType.INVESTOR),
            LoginRules.availableTypes(mt5, LoginMode.DEMO, HostProfile.EPHEMERAL))
        assertEquals(listOf(AccessType.INVESTOR),
            LoginRules.availableTypes(mt5, LoginMode.LIVE, HostProfile.EPHEMERAL))
        assertEquals(listOf(AccessType.TRADE, AccessType.INVESTOR),
            LoginRules.availableTypes(mt5, LoginMode.LIVE, HostProfile.PERSISTENT))
        assertEquals(listOf(AccessType.TRADE),
            LoginRules.availableTypes(derivOptions, LoginMode.LIVE, HostProfile.PERSISTENT))
    }

    @Test fun ephemeralLiveMasterPasswordIsRejectedBeforeSubmit() = runBlocking {
        val backend = FakeBackend(
            listOf(ConnectObservation(ConnectStage.VERIFIED, accountMode = "LIVE")),
            preflightFailure = "EPHEMERAL_HOST_LIVE_BLOCKED",
        )
        val outcome = useCase(backend).connect(mt5, goodForm.copy(mode = LoginMode.LIVE))
        assertEquals(
            ConnectOutcome.Failed(
                "EPHEMERAL_HOST_LIVE_BLOCKED",
                "LIVE Trade account passwords are blocked on an EPHEMERAL host; choose Investor access.",
            ),
            outcome,
        )
        assertEquals(0, backend.submitted)
    }

    @Test fun serverDropdownNeverGuessesAndEndsWithTypeServerName() {
        val options = ServerOptions.build(mt5, ownerHistory = listOf("Mine-Demo", ""), hostReported = listOf("Host-Demo", "Mine-Demo"))
        assertEquals(listOf("Mine-Demo", "Host-Demo"), options.filterIsInstance<ServerOption.Suggested>().map { it.value })
        assertEquals(ServerOption.TypeServerName, options.last())
        assertEquals(listOf<ServerOption>(ServerOption.TypeServerName), ServerOptions.build(mt5, emptyList(), emptyList()))
    }

    @Test fun formToStringNeverShowsSecrets() {
        val text = goodForm.copy(token = "fixture-token").toString()
        assertFalse(text.contains("fixture-secret")); assertFalse(text.contains("fixture-token")); assertFalse(text.contains("123456"))
    }

    // ---- Error mapping ----
    @Test fun everyContractFailureCodeHasAPlainSentence() {
        listOf(
            "WRONG_CREDENTIALS", "ACCOUNT_OR_SERVER_MISMATCH", "NO_CONNECTION", "SERVER_NOT_FOUND", "TERMINAL_OFFLINE", "EA_OFFLINE",
            "ALGO_TRADING_DISABLED", "WEBREQUEST_NOT_ALLOWED", "LOGIN_FAILED_UNCLASSIFIED", "EPHEMERAL_HOST_LIVE_BLOCKED",
            "BRIDGE_NEEDS_BROWSER_APPROVAL", "BRIDGE_URL_CHANGED", "WAITING_FOR_SPOTWARE_APPROVAL",
            "PROVIDER_AUTH_FAILED", "ACCOUNT_IDENTITY_MISMATCH", "PROVIDER_DATA_STALE", "SECURE_STORAGE_UNAVAILABLE",
        ).forEach { code ->
            assertTrue(code, ConnectFailures.knownCodes.contains(code))
            assertTrue(code, ConnectFailures.sentence(code).let { it.isNotBlank() && !it.contains("\n") })
        }
        assertEquals(ConnectFailures.sentence("LOGIN_FAILED_UNCLASSIFIED"), ConnectFailures.sentence("SOMETHING_NEW"))
    }

    // ---- Connect flow ----
    @Test fun successReportsStagesInOrderAndTheTerminalsOwnMode() = runBlocking {
        val stages = mutableListOf<ConnectStage>()
        val backend = FakeBackend(listOf(
            ConnectObservation(null), ConnectObservation(ConnectStage.TERMINAL_LOGGING_IN), ConnectObservation(ConnectStage.HEARTBEAT),
            ConnectObservation(ConnectStage.VERIFIED, accountMode = "DEMO"),
        ))
        val outcome = useCase(backend).connect(mt5, goodForm) { stages += it }
        assertEquals(listOf(ConnectStage.SENT, ConnectStage.TERMINAL_LOGGING_IN, ConnectStage.HEARTBEAT, ConnectStage.VERIFIED), stages)
        assertEquals(ConnectOutcome.Verified("DEMO", modeMismatch = false), outcome)
    }

    @Test fun aDemoLookingServerThatTheTerminalReportsAsLiveIsFlaggedNotTrusted() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(ConnectStage.VERIFIED, accountMode = "LIVE")))
        assertEquals(ConnectOutcome.Verified("LIVE", modeMismatch = true), useCase(backend).connect(mt5, goodForm))
    }

    @Test fun terminalModeMismatchIsComparedToSelectedModeAndReconnectUsesThatMode() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(ConnectStage.VERIFIED, accountMode = "DEMO")))
        val liveForm = goodForm.copy(mode = LoginMode.LIVE, type = AccessType.INVESTOR, server = "Broker-Real")
        val outcome = useCase(backend).connect(mt5, liveForm)
        assertEquals(ConnectOutcome.Verified("DEMO", modeMismatch = true), outcome)
        assertEquals(LoginMode.DEMO, LoginRules.withMode(liveForm, LoginMode.DEMO).mode)
        assertEquals(AccessType.TRADE, LoginRules.withMode(liveForm, LoginMode.DEMO).type)
    }

    @Test fun injectedProviderFixtureUsesTheSharedConnectAndAccountPickerContract() = runBlocking {
        // FIXTURE transport: this validates app orchestration only, not a provider login.
        val seenSpecs = mutableListOf<String>()
        val backend = ProviderConnectBackend(ProviderLoginVerifier { spec, form ->
            seenSpecs += "${spec.id}:${form.mode.name}"
            if (spec.id == "CTRADER" && form.accountId == null) {
                ConnectObservation(
                    stage = null,
                    accountChoices = listOf(LoginAccountChoice("fixture-42", "Fixture broker · ••••0042", LoginMode.LIVE)),
                )
            } else {
                ConnectObservation(ConnectStage.VERIFIED, accountMode = form.mode.name)
            }
        })
        val selectionStages = mutableListOf<ConnectStage>()
        val choices = ConnectUseCase(backend).connect(ctrader, LoginForm(mode = LoginMode.LIVE)) {
            selectionStages += it
        }
        assertEquals(
            ConnectOutcome.SelectAccount(
                listOf(LoginAccountChoice("fixture-42", "Fixture broker · ••••0042", LoginMode.LIVE)),
            ),
            choices,
        )
        assertEquals(listOf(ConnectStage.SENT), selectionStages)
        val verifiedStages = mutableListOf<ConnectStage>()
        val verified = ConnectUseCase(backend).connect(
            ctrader,
            LoginForm(mode = LoginMode.LIVE, accountId = "fixture-42"),
        ) { verifiedStages += it }
        assertEquals(ConnectOutcome.Verified("LIVE", modeMismatch = false), verified)
        assertEquals(listOf(ConnectStage.SENT, ConnectStage.VERIFIED), verifiedStages)

        val deriv = ProviderConnectBackend(ProviderLoginVerifier { spec, form ->
            seenSpecs += "${spec.id}:${form.mode.name}"
            ConnectObservation(ConnectStage.VERIFIED, accountMode = "DEMO")
        })
        val derivOutcome = ConnectUseCase(deriv).connect(
            derivOptions,
            LoginForm(mode = LoginMode.LIVE, appId = "fixture-app", token = "fixture-token"),
        )
        assertEquals(ConnectOutcome.Verified("DEMO", modeMismatch = true), derivOutcome)
        val waiting = ConnectUseCase(
            ProviderConnectBackend(ProviderLoginVerifier { _, _ ->
                throw ConnectSubmissionFailure("WAITING_FOR_SPOTWARE_APPROVAL")
            }),
        ).connect(ctrader, LoginForm())
        assertEquals(
            ConnectOutcome.Failed("WAITING_FOR_SPOTWARE_APPROVAL", "WAITING FOR SPOTWARE APPROVAL"),
            waiting,
        )
        assertTrue(seenSpecs.containsAll(listOf("CTRADER:LIVE", "DERIV_OPTIONS:LIVE")))
    }

    @Test fun verifiedWithoutATerminalModeIsNeverAccepted() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(ConnectStage.VERIFIED, accountMode = null)))
        val outcome = useCase(backend).connect(mt5, goodForm)
        assertTrue(outcome is ConnectOutcome.Failed)
    }

    @Test fun stagesNeverGoBackwards() = runBlocking {
        val stages = mutableListOf<ConnectStage>()
        val backend = FakeBackend(listOf(
            ConnectObservation(ConnectStage.HEARTBEAT), ConnectObservation(ConnectStage.TERMINAL_LOGGING_IN),
            ConnectObservation(ConnectStage.VERIFIED, accountMode = "DEMO"),
        ))
        useCase(backend).connect(mt5, goodForm) { stages += it }
        assertEquals(listOf(ConnectStage.SENT, ConnectStage.HEARTBEAT, ConnectStage.VERIFIED), stages)
    }

    @Test fun hostFailureCodeBecomesOnePlainSentence() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(ConnectStage.TERMINAL_LOGGING_IN), ConnectObservation(null, failureCode = "WRONG_CREDENTIALS")))
        val outcome = useCase(backend).connect(mt5, goodForm) as ConnectOutcome.Failed
        assertEquals("WRONG_CREDENTIALS", outcome.code)
        assertEquals(ConnectFailures.sentence("WRONG_CREDENTIALS"), outcome.sentence)
    }

    @Test fun incompleteFormIsRejectedBeforeAnythingIsSent() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(null)))
        val outcome = useCase(backend).connect(mt5, goodForm.copy(password = ""))
        assertEquals("FORM_INCOMPLETE", (outcome as ConnectOutcome.Failed).code)
        assertEquals(0, backend.submitted)
    }

    @Test fun silenceEndsInATimeoutNotInAFakeSuccess() = runBlocking {
        val backend = FakeBackend(listOf(ConnectObservation(null)))
        val outcome = useCase(backend, timeoutMs = 10, clockStep = 5).connect(mt5, goodForm)
        assertEquals("CONNECT_TIMEOUT", (outcome as ConnectOutcome.Failed).code)
    }

    @Test fun gatewayFailuresKeepTheirOwnCodes() = runBlocking {
        val backend = FakeBackend(emptyList(), failSubmit = BridgeGatewayException(BridgeGatewayFailure.BRIDGE_NEEDS_BROWSER_APPROVAL))
        val outcome = useCase(backend).connect(mt5, goodForm) as ConnectOutcome.Failed
        assertEquals("BRIDGE_NEEDS_BROWSER_APPROVAL", outcome.code)
    }

    @Test fun unexpectedExceptionsAreUnclassifiedAndDoNotLeakTheirMessage() = runBlocking {
        val backend = FakeBackend(emptyList(), failSubmit = IllegalStateException("secret fixture-secret leaked"))
        val outcome = useCase(backend).connect(mt5, goodForm) as ConnectOutcome.Failed
        assertEquals("LOGIN_FAILED_UNCLASSIFIED", outcome.code)
        assertFalse(outcome.toString().contains("fixture-secret"))
    }

    @Test fun cancellationPropagatesSoTheCancelButtonStopsTheFlow() {
        val backend = FakeBackend(emptyList(), failSubmit = CancellationException("cancel pressed"))
        try { runBlocking { useCase(backend).connect(mt5, goodForm) }; fail("cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
