package com.example.ui.bot

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.BrokerEndpointStatus
import com.example.data.broker.BrokerEndpointVerification
import com.example.data.broker.BrokerEndpointVerifier
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import com.example.security.SecretsManager
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.ProfitGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

private val LoginBlue = Color(0xFF1976D2)
private val BrokerSurface = Color(0xFF101010)
private val BrokerSurfaceVariant = Color(0xFF181818)

private enum class FlowPage { ACCOUNTS, BROKERS, LOGIN, PICK_ACCOUNT, ACCOUNT, SETTINGS, ADVANCED }
private enum class AccountTab { TRADE, HISTORY, BOT }
private data class PendingBrokerAction(val action: String, val ticket: String, val label: String)

@Composable
fun MetaTraderBrokerFlow(
    secrets: SecretsManager,
    context: Context,
    hostProfile: HostProfile,
    initialMode: LoginMode = LoginMode.DEMO,
    onGrantAccess: (BrokerLoginSpec, LoginForm) -> String?,
    onConnect: suspend (BrokerLoginSpec, LoginForm, (ConnectStage) -> Unit) -> ConnectOutcome,
    onSearchServers: suspend (BrokerLoginSpec, LoginMode, String) -> List<String> = { _, _, _ -> emptyList() },
    onRefreshAccount: suspend (BrokerLoginSpec, LoginForm) -> VerifiedAccountDetails?,
    onAccountCommand: suspend (BrokerLoginSpec, LoginForm, String, String?) -> String,
    onEmergencyStop: suspend (BrokerLoginSpec, LoginForm) -> String,
    onAcceptDemo: suspend (BrokerLoginSpec, LoginForm) -> String,
    onArmLive: suspend (BrokerLoginSpec, LoginForm, String, String) -> String,
    onOpenAdvanced: () -> Unit,
    onClose: () -> Unit,
    onTelegramEvent: (String) -> Unit = {},
) {
    var page by remember { mutableStateOf(FlowPage.ACCOUNTS) }
    var selected by remember { mutableStateOf<BrokerLoginSpec?>(null) }
    var query by remember { mutableStateOf("") }
    var serverSearch by remember { mutableStateOf("") }
    var serverResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverSearchBusy by remember { mutableStateOf(false) }
    var serverSearchError by remember { mutableStateOf("") }
    var serverSelectionVerified by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var form by remember(initialMode) { mutableStateOf(LoginForm(mode = initialMode)) }
    var serverSaveStatus by remember(selected?.id, form.mode) { mutableStateOf("") }
    var modeHintChosen by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ConnectStage?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var verifiedMode by remember { mutableStateOf<String?>(null) }
    var modeMismatch by remember { mutableStateOf(false) }
    var accountDetails by remember { mutableStateOf<VerifiedAccountDetails?>(null) }
    var accountTab by remember { mutableStateOf(AccountTab.TRADE) }
    var accountBusy by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var policyMessage by remember { mutableStateOf("Check the current ModeArmingPolicy state before starting.") }
    var pendingAction by remember { mutableStateOf<PendingBrokerAction?>(null) }
    var showEmergencyConfirmation by remember { mutableStateOf(false) }
    var showArmingSheet by remember { mutableStateOf(false) }
    var demoAcceptanceConfirmation by remember { mutableStateOf(false) }
    var liveTypedConfirmation by remember { mutableStateOf("") }
    var liveMinimumLot by remember { mutableStateOf("") }
    var clockNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var accountChoices by remember { mutableStateOf<List<LoginAccountChoice>>(emptyList()) }
    var spotwareApprovalConfirmed by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loginScrollState = rememberScrollState()
    val rows = remember { BrokerCatalog.rows() }
    val filtered = BrokerCatalog.search(rows, query)
    val securePolicy = LoginRules.savePasswordPolicy(secrets.isSecureStorageAvailable)
    val activeMt5ProfileKey = "${selected?.id?.removeSuffix("_MT5")}_${form.mode.name}"
    val activeMt5ServerValid = form.server == form.server.trim() && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(form.server)
    val selectedMt5BrokerKey = selected?.id?.removeSuffix("_MT5")?.filter(Char::isLetterOrDigit)?.uppercase(java.util.Locale.ROOT).orEmpty()
    val normalizedServerKey = form.server.filter(Char::isLetterOrDigit).uppercase(java.util.Locale.ROOT)
    val serverBelongsToSelectedBroker = selected?.connector != LoginConnector.MT5_TERMINAL ||
        (selectedMt5BrokerKey.length >= 2 && normalizedServerKey.contains(selectedMt5BrokerKey))
    val activeMt5ServerSaved = activeMt5ServerValid && secrets.getManagedMT5AccountServer(activeMt5ProfileKey) == form.server
    val mt5CredentialFieldsEnabled = true
    var endpointVerification by remember(selected?.id, form.mode, form.server) {
        mutableStateOf<BrokerEndpointVerification?>(null)
    }
    var endpointVerificationBusy by remember { mutableStateOf(false) }
    val openUrl: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val connect: (BrokerLoginSpec, LoginForm) -> Unit = { spec, candidate ->
        busy = true
        failure = null
        stage = null
        scope.launch {
            when (val outcome = onConnect(spec, candidate) { stage = it }) {
                is ConnectOutcome.Verified -> {
                    verifiedMode = outcome.accountMode
                    modeMismatch = outcome.modeMismatch
                    accountDetails = outcome.account
                    accountTab = AccountTab.TRADE
                    accountMessage = null
                    page = FlowPage.ACCOUNT
                    val brokerLabel = spec.id.removeSuffix("_MT5").removeSuffix("_OPTIONS")
                    fun accountMetric(value: Double?): String =
                        value?.takeIf { it.isFinite() && it >= 0.0 }
                            ?.let { String.format(java.util.Locale.US, "%.2f", it) }
                            ?: "UNAVAILABLE"
                    onTelegramEvent(
                        "$brokerLabel ${outcome.accountMode.uppercase()} Connected - Balance: ${accountMetric(outcome.account?.balance)}, Equity: ${accountMetric(outcome.account?.equity)}"
                    )
                }
                is ConnectOutcome.SelectAccount -> {
                    accountChoices = outcome.accounts
                    page = FlowPage.PICK_ACCOUNT
                }
                is ConnectOutcome.Failed -> {
                    failure = outcome.sentence
                    onTelegramEvent(outcome.code)
                }
            }
            busy = false
        }
    }
    val goBack = {
        when (page) {
            FlowPage.ACCOUNTS -> onClose()
            FlowPage.BROKERS, FlowPage.SETTINGS -> page = FlowPage.ACCOUNTS
            FlowPage.LOGIN, FlowPage.PICK_ACCOUNT -> page = FlowPage.BROKERS
            FlowPage.ACCOUNT -> page = FlowPage.ACCOUNTS
            FlowPage.ADVANCED -> page = FlowPage.SETTINGS
        }
    }
    // A new broker login form must always start at its header, even when the previous
    // broker form was scrolled to the credential fields.
    LaunchedEffect(page, selected?.id) {
        if (page == FlowPage.LOGIN) loginScrollState.scrollTo(0)
    }
    LaunchedEffect(page, accountDetails?.retrievedAtMs) {
        while (page == FlowPage.ACCOUNT) {
            clockNowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    LaunchedEffect(page, selected?.id, form.login, form.mode) {
        while (page == FlowPage.ACCOUNT) {
            delay(10_000L)
            val spec = selected ?: break
            try {
                onRefreshAccount(spec, form)?.let { refreshed ->
                    if (matchesVerifiedAccountIdentity(accountDetails, refreshed)) {
                        accountDetails = refreshed
                        accountMessage = null
                    }
                }
            } catch (_: Exception) {
                accountMessage = "Account refresh failed. Values remain marked stale until verified data returns."
            }
        }
    }
    LaunchedEffect(page, selected?.id, form.login, form.mode, accountDetails?.retrievedAtMs) {
        val spec = selected
        if (page == FlowPage.ACCOUNT && spec != null) {
            try {
                policyMessage = onAccountCommand(spec, form, "CHECK_MODE", null)
            } catch (_: Exception) {
                policyMessage = "ModeArmingPolicy: fresh status unavailable; trading remains gated."
            }
        }
    }
    val runAccountAction: (String, String?) -> Unit = { action, ticket ->
        val spec = selected
        if (spec != null) {
            accountBusy = true
            accountMessage = null
            scope.launch {
                try {
                    val result = onAccountCommand(spec, form, action, ticket)
                    accountMessage = result
                    if (action == "CHECK_MODE") policyMessage = result
                } catch (_: Exception) {
                    accountMessage = "Action was not confirmed. Verify the broker and host state before retrying."
                } finally {
                    accountBusy = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "‹",
                color = TextPrimary,
                fontSize = 32.sp,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }.testTag("broker_back").clickable { goBack() },
            )
            Text(
                when (page) {
                    FlowPage.ACCOUNTS -> "Accounts"
                    FlowPage.BROKERS -> "Brokers"
                    FlowPage.PICK_ACCOUNT -> "Select account"
                    FlowPage.SETTINGS -> "Settings"
                    FlowPage.ADVANCED -> "Advanced"
                    FlowPage.ACCOUNT -> selected?.displayName ?: "Account"
                    else -> selected?.displayName ?: "Account"
                },
                color = TextPrimary,
                fontSize = 18.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("broker_flow_header_title"),
            )
            if (page == FlowPage.ACCOUNTS) {
                TextButton(onClick = { page = FlowPage.SETTINGS }, modifier = Modifier.height(48.dp).testTag("settings")) {
                    Text("SETTINGS", color = ElectricCyan)
                }
            }
            TextButton(
                onClick = { showInfoSheet = true },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Information" }.testTag("flow_info"),
            ) { Text("ⓘ", color = ElectricCyan, fontSize = 20.sp) }
        }
        when (page) {
            FlowPage.ACCOUNTS -> {
                Text("Saved accounts", color = TextSecondary, fontSize = 14.sp)
                val saved = SavedLoginAccounts.build(
                    secrets.getManagedMT5AccountRegistry(),
                    secrets.getBrokerAccountProfiles(),
                )
                if (saved.isEmpty()) Text("No saved accounts. Add a broker to connect.", color = TextSecondary, modifier = Modifier.testTag("accounts_empty"))
                if (saved.isNotEmpty()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        saved.forEach { account ->
                            AccountRow(account) {
                        selected = account.spec
                            serverSearch = account.spec.id.removeSuffix("_MT5")
                            serverResults = emptyList()
                            serverSearchError = ""
                            serverSelectionVerified = false
                            modeHintChosen = false
                            form = when (account.spec.connector) {
                            LoginConnector.MT5_TERMINAL -> {
                                val accessType = AccessType.values().firstOrNull {
                                    it.wireValue == secrets.getManagedMT5AccountAccessType(account.profileKey)
                                } ?: AccessType.TRADE
                                val passwordSaved = secrets.isManagedMT5AccountPasswordSaveEnabled(account.profileKey)
                                LoginForm(
                                    type = accessType,
                                    mode = account.mode,
                                    login = account.login,
                                    password = if (passwordSaved && com.example.security.AppSecurityGuard.authorizeSensitiveAction()) {
                                        secrets.getManagedMT5AccountPassword(account.profileKey)
                                    } else "",
                                    server = account.server,
                                    savePassword = passwordSaved,
                                )
                            }
                            LoginConnector.DERIV_OPTIONS -> {
                                val mode = if (account.mode == LoginMode.DEMO) "demo" else "real"
                                val tokenSaved = secrets.isDerivApiTokenSaveEnabled()
                                val tokenAuthorized = tokenSaved && com.example.security.AppSecurityGuard.authorizeSensitiveAction()
                                LoginForm(
                                    mode = account.mode,
                                    accountId = account.profileKey,
                                    login = account.login,
                                    appId = secrets.getDerivAppId(),
                                    token = if (tokenAuthorized) secrets.getDerivApiToken(mode) else "",
                                    savePassword = tokenSaved,
                                )
                            }
                            LoginConnector.CTRADER -> LoginForm(
                                mode = account.mode,
                                accountId = account.profileKey,
                                login = account.login,
                                server = account.server,
                                appId = secrets.getCTraderClientId(),
                                token = "",
                            )
                        }
                        failure = null
                        page = FlowPage.LOGIN
                    }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Button(onClick = { page = FlowPage.BROKERS }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("add_account"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) { Text("ADD ACCOUNT") }
            }
            FlowPage.SETTINGS -> {
                Button(
                    onClick = { page = FlowPage.ADVANCED },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("settings_advanced"),
                    colors = ButtonDefaults.buttonColors(containerColor = BrokerSurfaceVariant),
                ) { Text("ADVANCED", color = TextPrimary) }
            }
            FlowPage.ADVANCED -> {
                Button(
                    onClick = onOpenAdvanced,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("legacy_bot_tools"),
                    colors = ButtonDefaults.buttonColors(containerColor = BrokerSurfaceVariant),
                ) { Text("LEGACY BOT TOOLS", color = TextPrimary) }
            }
            FlowPage.BROKERS -> {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("find_broker"), label = { Text("Find broker") }, singleLine = true, textStyle = TextStyle(fontSize = 16.sp))
                if (filtered.isEmpty()) {
                    Text(BrokerCatalog.EMPTY_STATE, color = TextPrimary, fontSize = 18.sp, modifier = Modifier.testTag("broker_empty"))
                    Text(BrokerCatalog.CAUTION, color = WarningAmber, fontSize = 12.sp)
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f).testTag("broker_list")) {
                    items(filtered, key = { it.specId }) { row ->
                        BrokerRowCard(row) {
                            selected = BrokerLoginSpecs.byId(row.specId)
                        serverSearch = row.specId.removeSuffix("_MT5")
                        serverResults = emptyList()
                        serverSearchError = ""
                        serverSelectionVerified = false
                        modeHintChosen = false
                        val spec = selected
                            form = when (spec?.connector) {
                                LoginConnector.DERIV_OPTIONS -> LoginForm(
                                    mode = LoginRules.modeForServer(secrets.getDerivAccountMode()),
                                    appId = secrets.getDerivAppId(),
                                    token = if (secrets.isDerivApiTokenSaveEnabled() &&
                                        com.example.security.AppSecurityGuard.authorizeSensitiveAction()
                                    ) secrets.getDerivApiToken(secrets.getDerivAccountMode()) else "",
                                )
                                LoginConnector.CTRADER -> LoginForm(
                                    mode = LoginMode.LIVE,
                                    appId = secrets.getCTraderClientId(),
                                    token = "",
                                )
                                else -> if (spec?.connector == LoginConnector.MT5_TERMINAL) {
                                val brand = spec.id.removeSuffix("_MT5")
                                val initialMode = LoginMode.DEMO
                                LoginForm().copy(
                                    mode = initialMode,
                                    server = secrets.getManagedMT5AccountServer("${brand}_${initialMode.name}")
                                )
                            } else LoginForm()
                            }
                            serverSaveStatus = ""
                            failure = null
                            page = FlowPage.LOGIN
                        }
                    }
                }
                TextButton(onClick = { manualDialog = true }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("cant_find_broker")) { Text(BrokerCatalog.CANT_FIND, color = ElectricCyan) }
            }
            FlowPage.LOGIN -> {
                val spec = selected ?: return@Column
                // Keep the page header and Back control fixed; only the long login form scrolls.
                // imePadding reduces the viewport above the keyboard while verticalScroll keeps
                // every credential field and action reachable on short phone screens.
                Column(
                    Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(loginScrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Text(
                    "DESTINEDFX",
                    color = ElectricCyan,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                    letterSpacing = 1.4.sp
                )
                Text(
                    if (spec.connector == LoginConnector.MT5_TERMINAL) "MT5 account sign-in" else "Broker account sign-in",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                if (spec.connector == LoginConnector.MT5_TERMINAL) {
                    Text(
                        "Choose the account type. The terminal verifies DEMO or REAL after login.",
                        color = WarningAmber,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("mt5_mode_hint_notice"),
                    )
                    Text(
                        "Enter your MT5 account number, master password and exact broker server.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("mt5_login_fields_help"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LoginMode.values().forEach { mode ->
                            val isReal = mode == LoginMode.LIVE
                            val label = if (isReal) "REAL" else "DEMO"
                            val selectedMode = modeHintChosen && form.mode == mode
                            Text(
                                label,
                                color = if (selectedMode) Color.White else TextSecondary,
                                fontSize = 15.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                modifier = Modifier.weight(1f).height(54.dp)
                                    .background(
                                        if (selectedMode) (if (isReal) LossRed else LoginBlue) else BrokerSurfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        val brand = spec.id.removeSuffix("_MT5")
                                        val savedServer = secrets.getManagedMT5AccountServer("${brand}_${mode.name}")
                                        // Never erase owner-entered text when this mode has no saved server.
                                        // The active-mode saved check below still requires an explicit save.
                                        form = LoginRules.withMode(form, mode).copy(server = "")
                                        serverSearch = spec.id.removeSuffix("_MT5")
                                        serverResults = emptyList()
                                        serverSearchError = ""
                                        serverSelectionVerified = false
                                        serverSaveStatus = ""
                                        endpointVerification = null
                                        modeHintChosen = true
                                    }
                                    .testTag("login_mode_${if (isReal) "real" else "demo"}"),
                            )
                        }
                    }
                    Text("Type: Trade account", color = TextPrimary, modifier = Modifier.fillMaxWidth().height(48.dp).padding(12.dp).testTag("mt5_type_trade_only"))
                    LoginInput("Login", form.login, { form = form.copy(login = it.filter(Char::isDigit)) },
                        KeyboardType.Number, enabled = mt5CredentialFieldsEnabled)
                    LoginInput("Master Password", form.password, { form = form.copy(password = it) },
                        KeyboardType.Password, true, ImeAction.Next, enabled = mt5CredentialFieldsEnabled)
                    var serverMenu by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = if (serverSelectionVerified) form.server else serverSearch,
                        onValueChange = { value ->
                            serverSearch = value
                            form = form.copy(server = "")
                            serverSelectionVerified = false
                            serverResults = emptyList()
                            serverSearchError = ""
                            serverSaveStatus = ""
                            serverMenu = value.trim().length >= 2
                        },
                        modifier = Modifier.fillMaxWidth().testTag("server_field"),
                        enabled = mt5CredentialFieldsEnabled,
                        trailingIcon = {
                            TextButton(enabled = mt5CredentialFieldsEnabled, onClick = {
                                if (serverSearch.isBlank()) serverSearch = spec.id.removeSuffix("_MT5")
                                serverMenu = true
                            }) { Text("SEARCH") }
                        },
                        label = { Text("Search MT5 Server") },
                        placeholder = { Text("Type any broker name (e.g. Exness, XM, IC Markets)") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { serverMenu = true }),
                    )
                    LaunchedEffect(serverMenu, spec.id, form.mode, serverSearch) {
                        val searchText = serverSearch.trim()
                        if (!serverMenu || searchText.length < 2) return@LaunchedEffect
                        delay(300L)
                        serverSearchBusy = true
                        serverSearchError = ""
                        try {
                            val matches = onSearchServers(spec, form.mode, searchText)
                            serverResults = matches
                            serverSelectionVerified = form.server.isNotBlank() &&
                                matches.any { it == form.server }
                            if (matches.isEmpty()) serverSearchError = "Server not found"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            serverResults = emptyList()
                            serverSelectionVerified = false
                            serverSearchError = "Server search unavailable. No server was selected."
                        } finally {
                            serverSearchBusy = false
                        }
                    }
                    val mt5Brand = spec.id.removeSuffix("_MT5")
                    if (form.mode == LoginMode.LIVE) {
                        Text("OFFICIAL MT5 SERVER REFERENCE · $mt5Brand", color = ElectricCyan, fontSize = 9.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(BrokerEndpointVerifier.officialServerReference(mt5Brand, form.mode.name),
                            color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                        Button(onClick = {
                            scope.launch {
                                endpointVerificationBusy = true
                                try { endpointVerification = BrokerEndpointVerifier.verify(mt5Brand, form.mode.name, form.server) }
                                finally { endpointVerificationBusy = false }
                            }
                        }, enabled = !busy && !endpointVerificationBusy && form.server.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("verify_broker_endpoint"),
                            colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) {
                            Text(if (endpointVerificationBusy) "VERIFYING BROKER ENDPOINT..." else "VERIFY BROKER ENDPOINT")
                        }
                        endpointVerification?.let { verification ->
                            val verified = verification.status == BrokerEndpointStatus.DNS_TCP_VERIFIED
                            Text(if (verified) "GREEN · OFFICIAL DNS/TCP VERIFIED" else "RED · ${verification.status.name.replace('_', ' ')}",
                                color = if (verified) ProfitGreen else LossRed, fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                                modifier = Modifier.testTag("broker_endpoint_result"))
                            Text("Host: ${verification.host.ifBlank { "not publicly mapped" }} · Port: ${verification.port?.toString() ?: "—"}",
                                color = TextSecondary, fontSize = 9.sp)
                            Text("Resolved IP: ${verification.resolvedIps.joinToString().ifBlank { "not resolved / not publicly mapped" }}",
                                color = TextSecondary, fontSize = 9.sp)
                            Text(verification.detail, color = if (verified) WarningAmber else LossRed, fontSize = 9.sp)
                            Text("Pre-login DNS/TCP does not verify MT5 login, TLS certificate identity or absence of a transparent proxy.",
                                color = WarningAmber, fontSize = 9.sp)
                        }
                    }
                    val serverHint = ServerOptions.fieldHint(spec)
                    Text(serverHint, color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp,
                        modifier = Modifier.testTag("mt5_server_field_help"))
                    Button(
                        onClick = {
                            val candidate = form.server
                            serverSaveStatus = when {
                                !serverSelectionVerified || candidate.isBlank() -> "Server not found"
                                !serverBelongsToSelectedBroker -> "This catalogue server belongs to another broker. Select the matching broker profile before saving."
                                candidate != candidate.trim() || !Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,95}").matches(candidate) -> "Select a real server from the MT5 list."
                                secrets.saveManagedMT5AccountServer(activeMt5ProfileKey, candidate) -> "Server saved"
                                else -> "Server could not be saved to encrypted storage."
                            }
                        },
                        enabled = !busy && activeMt5ServerValid && serverSelectionVerified && serverBelongsToSelectedBroker && mt5CredentialFieldsEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("save_mt5_server")
                    ) { Text("SAVE SERVER") }
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("mt5_server_save_status"),
                    ) {
                        if (serverSaveStatus.isNotBlank()) {
                            Text(
                                serverSaveStatus,
                                color = if (serverSaveStatus == "Server saved") ElectricCyan else LossRed,
                                fontSize = 10.sp,
                            )
                        } else {
                            // Keep a stable status region when mode changes clear the last message.
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                    Text(
                        "Login is verified by the paired MT5 terminal. No order is sent during sign-in.",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.testTag("mt5_login_verification_path")
                    )
                    DropdownMenu(serverMenu, { serverMenu = false }) {
                        OutlinedTextField(
                            value = serverSearch,
                            onValueChange = {
                                serverSearch = it
                                form = form.copy(server = "")
                                serverSelectionVerified = false
                                serverResults = emptyList()
                                serverSearchError = ""
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("server_search"),
                            label = { Text("Search MT5 broker servers") },
                            singleLine = true,
                        )
                        if (serverSearchBusy) {
                            Text("SEARCHING MT5 SERVER LIST...", color = ElectricCyan, fontSize = 10.sp, modifier = Modifier.padding(12.dp))
                        } else if (serverResults.isEmpty()) {
                            Text(
                                serverSearchError.ifBlank { if (serverSearch.trim().length < 2) "Enter a broker name to search." else "Server not found" },
                                color = if (serverSearchError.isBlank()) TextSecondary else LossRed,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(12.dp).testTag("server_search_empty")
                            )
                        }
                        serverResults.forEach { serverName ->
                            DropdownMenuItem(
                                text = { Text(serverName) },
                                modifier = Modifier.testTag("server_result_$serverName"),
                                onClick = {
                                    form = form.copy(server = serverName, type = AccessType.TRADE)
                                    serverSearch = serverName
                                    serverSelectionVerified = true
                                    serverMenu = false
                                    serverSaveStatus = ""
                                },
                            )
                        }
                    }
                } else if (spec.connector == LoginConnector.DERIV_OPTIONS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LoginMode.values().forEach { mode ->
                            val label = mode.name
                            Text(
                                label,
                                color = if (form.mode == mode) Color.White else TextSecondary,
                                modifier = Modifier.weight(1f).height(48.dp)
                                    .background(if (form.mode == mode) LoginBlue else BrokerSurfaceVariant, RoundedCornerShape(4.dp))
                                    .clickable { form = LoginRules.withMode(form, mode) }
                                    .testTag("login_mode_${label.lowercase()}"),
                            )
                        }
                    }
                    LoginInput("App ID", form.appId, { form = form.copy(appId = it) }, KeyboardType.Number, true)
                    LoginInput("Personal Access Token", form.token, { form = form.copy(token = it) }, KeyboardType.Password, true, ImeAction.Done)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LoginMode.values().forEach { mode ->
                            val label = mode.name
                            Text(
                                label,
                                color = if (form.mode == mode) Color.White else TextSecondary,
                                modifier = Modifier.weight(1f).height(48.dp)
                                    .background(if (form.mode == mode) LoginBlue else BrokerSurfaceVariant, RoundedCornerShape(4.dp))
                                    .clickable { form = LoginRules.withMode(form, mode) }
                                    .testTag("login_mode_${label.lowercase()}"),
                            )
                        }
                    }
                    LoginInput("Client ID", form.appId, { form = form.copy(appId = it) }, KeyboardType.Text)
                    LoginInput("Client Secret", form.token, { form = form.copy(token = it) }, KeyboardType.Password, true, ImeAction.Done)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        androidx.compose.material3.Checkbox(
                            checked = spotwareApprovalConfirmed,
                            onCheckedChange = { spotwareApprovalConfirmed = it },
                        )
                        Text(
                            if (spotwareApprovalConfirmed) "Spotware approval confirmed" else "WAITING FOR SPOTWARE APPROVAL",
                            color = if (spotwareApprovalConfirmed) ElectricCyan else WarningAmber,
                        )
                    }
                    Button(
                        onClick = {
                            failure = onGrantAccess(spec, form)
                                ?: "WAITING FOR SPOTWARE APPROVAL"
                        },
                        enabled = spotwareApprovalConfirmed && form.appId.isNotBlank() &&
                            (form.token.isNotBlank() || secrets.getCTraderClientSecret().isNotBlank()),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("grant_access"),
                        colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
                    ) { Text("GRANT ACCESS") }
                }
                if (spec.connector != LoginConnector.CTRADER) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        androidx.compose.material3.Checkbox(checked = form.savePassword, enabled = securePolicy.enabled && mt5CredentialFieldsEnabled, onCheckedChange = { form = form.copy(savePassword = it) })
                        Text(if (spec.connector == LoginConnector.DERIV_OPTIONS) "Save token" else "Save password", color = TextPrimary)
                    }
                    if (!securePolicy.enabled) Text(securePolicy.reason.orEmpty(), color = WarningAmber, fontSize = 11.sp)
                }
                Text("Forgot password? Use your broker app", color = ElectricCyan, modifier = Modifier.fillMaxWidth().height(40.dp).clickable { openUrl(spec.passwordRecoveryUrl) }.testTag("forgot_password"))
                stage?.let { Text("${it.label}...", color = ElectricCyan, modifier = Modifier.testTag("connect_progress")) }
                failure?.let { Text(it, color = LossRed, modifier = Modifier.testTag("connect_error")) }
                Button(
                    enabled = !busy && LoginRules.canSubmitLogin(spec, form, modeHintChosen) &&
                        (spec.connector != LoginConnector.MT5_TERMINAL || (activeMt5ServerSaved && serverSelectionVerified && serverBelongsToSelectedBroker)) &&
                        (spec.connector != LoginConnector.MT5_TERMINAL || mt5CredentialFieldsEnabled),
                    onClick = { connect(spec, form.copy(type = AccessType.TRADE)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login_button"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White)
                    else Text("LOGIN")
                }

                }
            }
            FlowPage.PICK_ACCOUNT -> {
                val spec = selected ?: return@Column
                accountChoices.forEach { account ->
                    Button(
                        onClick = {
                            val next = form.copy(accountId = account.id, mode = account.mode)
                            form = next
                            connect(spec, next.copy(type = AccessType.TRADE))
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("provider_account_${account.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = BrokerSurfaceVariant),
                    ) { Text("${account.label} · ${account.mode.name}", color = TextPrimary) }
                }
            }
            FlowPage.ACCOUNT -> {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val spec = selected ?: return@Column
                    val details = accountDetails
                    if (details == null) {
                        Text("Fresh account details were not returned by the verified session.", color = WarningAmber)
                    } else {
                        if (modeMismatch && verifiedMode != null) {
                            Text("Terminal reports $verifiedMode; reconnect as $verifiedMode", color = WarningAmber, modifier = Modifier.testTag("terminal_mode_mismatch"))
                            Button(
                                onClick = {
                                    val retryForm = LoginRules.withMode(form, LoginMode.valueOf(requireNotNull(verifiedMode)))
                                    form = retryForm
                                    connect(spec, retryForm.copy(type = AccessType.TRADE))
                                },
                                modifier = Modifier.fillMaxWidth().testTag("reconnect_reported_mode"),
                                colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
                            ) { Text("RECONNECT AS $verifiedMode") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            AccountTab.values().forEach { tab ->
                                Button(
                                    onClick = { accountTab = tab },
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("account_tab_${tab.name.lowercase()}"),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (accountTab == tab) LoginBlue else BrokerSurfaceVariant),
                                ) { Text(tab.name, color = TextPrimary) }
                            }
                        }
                        if (accountTab != AccountTab.BOT) {
                            AccountSummary(
                                spec = spec,
                                details = details,
                                nowMs = clockNowMs,
                            )
                        }
                        when (accountTab) {
                            AccountTab.TRADE -> AccountTradeList(
                                modifier = Modifier.weight(1f),
                                details = details,
                                enabled = spec.connector == LoginConnector.MT5_TERMINAL && !accountBusy &&
                                    true,
                                onClose = { ticket, symbol -> pendingAction = PendingBrokerAction("CLOSE_POSITION_SELECTED", ticket, "Close $symbol position $ticket?") },
                                onCancel = { ticket, symbol -> pendingAction = PendingBrokerAction("CANCEL_PENDING_SELECTED", ticket, "Cancel $symbol pending order $ticket?") },
                            )
                            AccountTab.HISTORY -> AccountHistoryList(details, Modifier.weight(1f))
                            AccountTab.BOT -> AccountBotControls(
                                modifier = Modifier.weight(1f),
                                details = details,
                                connector = spec.connector,
                                busy = accountBusy,
                                message = accountMessage,
                                policyMessage = policyMessage,
                                riskAccepted = true,
                                onAcceptRisk = {},
                                onStart = { runAccountAction("START", null) },
                                onPauseResume = { runAccountAction(if (details.engineState == "PAUSED") "RESUME" else "PAUSE", null) },
                                onStop = { runAccountAction("STOP", null) },
                                onEmergencyStop = { showEmergencyConfirmation = true },
                                onCheckPolicy = { runAccountAction("CHECK_MODE", null) },
                                onOpenArming = { showArmingSheet = true },
                            )
                        }
                    }
                }
            }
        }
    }
    if (manualDialog) AlertDialog(onDismissRequest = { manualDialog = false }, title = { Text("Find your broker") }, text = { OutlinedTextField(manualName, { manualName = it }, label = { Text("Company or server name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { ManualBrokerProfile.create(manualName); manualDialog = false; manualName = "" }) { Text("SAVE") } }, dismissButton = { TextButton(onClick = { manualDialog = false }) { Text("CANCEL") } })
    if (showInfoSheet) AlertDialog(
        onDismissRequest = { showInfoSheet = false },
        title = { Text("Account safety") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connected status requires fresh, identity-matched broker evidence.")
                Text("Login never arms trading. Existing risk, news, lease, and Emergency Stop gates remain in force.")
                Text("A fixture or test result is not proof of a broker login.")
            }
        },
        confirmButton = { TextButton(onClick = { showInfoSheet = false }) { Text("CLOSE") } },
    )
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Confirm broker action") },
            text = { Text(action.label) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    runAccountAction(action.action, action.ticket)
                }) { Text("CONFIRM") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("CANCEL") } },
        )
    }
    if (showEmergencyConfirmation) AlertDialog(
        onDismissRequest = { showEmergencyConfirmation = false },
        title = { Text("Emergency Stop") },
        text = { Text("Disarm LIVE leases and stop new entries. Existing positions are not liquidated.") },
        confirmButton = {
            TextButton(onClick = {
                showEmergencyConfirmation = false
                val spec = selected ?: return@TextButton
                accountBusy = true
                scope.launch {
                    try { accountMessage = onEmergencyStop(spec, form) }
                    catch (_: Exception) { accountMessage = "Emergency stop was not confirmed. Verify and stop manually at the broker/host." }
                    finally { accountBusy = false }
                }
            }) { Text("EMERGENCY STOP") }
        },
        dismissButton = { TextButton(onClick = { showEmergencyConfirmation = false }) { Text("CANCEL") } },
    )
    if (showArmingSheet) AlertDialog(
        onDismissRequest = { showArmingSheet = false },
        title = { Text("Mode arming") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Account authorization: $policyMessage", color = WarningAmber)
                Text("REAL MT5 is confirmed only for the exact authenticated LIVE broker account shown in this session. DEMO PASS, host type, backup and minimum-lot acknowledgement are not LIVE authorization requirements.")
                OutlinedTextField(
                    value = liveTypedConfirmation,
                    onValueChange = { liveTypedConfirmation = it },
                    label = { Text("Type CONFIRM REAL TRADE") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    enabled = selected != null && !accountBusy,
                    onClick = {
                        demoAcceptanceConfirmation = true
                    },
                ) {
                    Text(if (selected?.connector == LoginConnector.MT5_TERMINAL) "ACCEPT VALIDATED DEMO PASS" else "ACCEPT VERIFIED DEMO ACCOUNT")
                }
                TextButton(
                    enabled = selected != null && form.mode == LoginMode.LIVE && !accountBusy,
                    onClick = {
                        val spec = selected ?: return@TextButton
                        accountBusy = true
                        scope.launch {
                            try { policyMessage = onArmLive(spec, form, liveTypedConfirmation, "") }
                            catch (_: Exception) { policyMessage = "LIVE arming failed closed. Verify the fresh account state and required evidence." }
                            finally { accountBusy = false }
                        }
                    },
                ) { Text("CONFIRM REAL TRADE") }
            }
        },
        dismissButton = { TextButton(onClick = { showArmingSheet = false }) { Text("CLOSE") } },
    )
    if (demoAcceptanceConfirmation) AlertDialog(
        onDismissRequest = { demoAcceptanceConfirmation = false },
        title = { Text("Accept validated DEMO PASS?") },
        text = { Text("This records owner acceptance for the exact DEMO account and credential generation. It does not enable LIVE or place an order.") },
        confirmButton = {
            TextButton(onClick = {
                demoAcceptanceConfirmation = false
                val spec = selected ?: return@TextButton
                accountBusy = true
                scope.launch {
                    try { policyMessage = onAcceptDemo(spec, form) }
                    catch (_: Exception) { policyMessage = "DEMO acceptance was not recorded. Revalidate the matching DEMO PASS." }
                    finally { accountBusy = false }
                }
            }) { Text("ACCEPT DEMO") }
        },
        dismissButton = { TextButton(onClick = { demoAcceptanceConfirmation = false }) { Text("CANCEL") } },
    )
}

@Composable
private fun AccountSummary(
    spec: BrokerLoginSpec,
    details: VerifiedAccountDetails,
    nowMs: Long,
) {
    val stale = !details.isFreshAt(nowMs)
    val brokerName = if (spec.connector == LoginConnector.CTRADER && details.server.isNotBlank()) details.server else spec.displayName
    Card(
        Modifier.fillMaxWidth().testTag("verified_account_summary"),
        colors = CardDefaults.cardColors(containerColor = BrokerSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(brokerName, color = TextPrimary, fontSize = 17.sp)
                Text(details.mode, color = ElectricCyan, fontSize = 13.sp, modifier = Modifier.testTag("verified_account_mode"))
            }
            Text("Login ${maskSavedLogin(details.login)} · Server ${details.server.ifBlank { "unavailable" }}", color = TextSecondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (stale) "STALE" else "FRESH", color = if (stale) LossRed else ElectricCyan, fontSize = 11.sp, modifier = Modifier.testTag("account_freshness"))
                Text(
                    if (details.retrievedAtMs > 0L && nowMs >= details.retrievedAtMs) "${(nowMs - details.retrievedAtMs) / 1_000L}s" else "age unavailable",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            // Never keep a last-known balance looking current after the verified heartbeat ages out.
            MetricLine("Balance", details.balance.takeIf { !stale }, details.currency.takeIf { !stale })
            MetricLine("Equity", details.equity.takeIf { !stale }, details.currency.takeIf { !stale })
            MetricLine("Margin", details.margin.takeIf { !stale }, details.currency.takeIf { !stale })
            MetricLine("Free margin", details.freeMargin.takeIf { !stale }, details.currency.takeIf { !stale })
            MetricLine("Leverage", details.leverage?.toDouble().takeIf { !stale }, "x")
            MetricLine("Margin level", details.marginLevel.takeIf { !stale }, "%")
            if (details.hostProfile == HostProfile.EPHEMERAL) {
                Text("Codespaces host can stop at its reported lifetime; START → DIE and remaining time are shown in the host panel.",
                    color = WarningAmber, fontSize = 10.sp, modifier = Modifier.testTag("ephemeral_host_notice"))
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: Double?, unit: String?) {
    val display = value?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        if (unit == "x") "${it.toInt()}x" else String.format(java.util.Locale.US, "%.2f", it) + (unit?.takeIf { value -> value.isNotBlank() }?.let { suffix -> " $suffix" } ?: "")
    } ?: "UNAVAILABLE"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(display, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun AccountTradeList(
    modifier: Modifier,
    details: VerifiedAccountDetails,
    enabled: Boolean,
    onClose: (String, String) -> Unit,
    onCancel: (String, String) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("OPEN POSITIONS", color = ElectricCyan, fontSize = 12.sp) }
            val positions = details.positions
            if (positions == null) {
                item { Text("Position data unavailable from the verified source.", color = TextSecondary) }
            } else if (positions.isEmpty()) {
                item { Text("No open positions reported.", color = TextSecondary) }
            } else {
                items(positions) { position ->
                    val ticket = position.optString("position_id").ifBlank { position.optString("ticket") }
                    val symbol = position.optString("symbol", "Unknown symbol")
                    Card(colors = CardDefaults.cardColors(containerColor = BrokerSurfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$symbol · ${position.optString("direction", "unknown")}", color = TextPrimary)
                                Text("Volume ${position.optString("volume", "unavailable")} · P/L ${position.optString("floating_profit", "unavailable")}", color = TextSecondary, fontSize = 11.sp)
                            }
                            Button(
                                onClick = { if (ticket.isNotBlank()) onClose(ticket, symbol) },
                                enabled = enabled && ticket.isNotBlank(),
                                modifier = Modifier.height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                            ) { Text("CLOSE") }
                        }
                    }
                }
            }
            item { Text("PENDING ORDERS", color = ElectricCyan, fontSize = 12.sp) }
            val orders = details.pendingOrders
            if (orders == null) {
                item { Text("Pending-order data unavailable from the verified source.", color = TextSecondary) }
            } else if (orders.isEmpty()) {
                item { Text("No pending orders reported.", color = TextSecondary) }
            } else {
                items(orders) { order ->
                    val ticket = order.optString("ticket")
                    val symbol = order.optString("symbol", "Unknown symbol")
                    Card(colors = CardDefaults.cardColors(containerColor = BrokerSurfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("$symbol · ${order.optString("direction", "unknown")}", color = TextPrimary)
                                Text("Volume ${order.optString("remaining_volume", "unavailable")} · ${order.optString("state", "pending")}", color = TextSecondary, fontSize = 11.sp)
                            }
                            Button(
                                onClick = { if (ticket.isNotBlank()) onCancel(ticket, symbol) },
                                enabled = enabled && ticket.isNotBlank(),
                                modifier = Modifier.height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = LossRed),
                            ) { Text("CANCEL") }
                        }
                    }
                }
            }
        }
        if (!enabled) Text("This provider connection is read-only.", color = WarningAmber, fontSize = 12.sp)
    }
}

@Composable
private fun AccountHistoryList(details: VerifiedAccountDetails, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("HISTORY · DEALS", color = ElectricCyan, fontSize = 12.sp) }
            val deals = details.deals
            if (deals == null) {
                item { Text("Deal history unavailable from the verified source.", color = TextSecondary) }
            } else if (deals.isEmpty()) {
                item { Text("No deals reported.", color = TextSecondary) }
            } else {
                items(deals) { deal ->
                    Text(
                        "${deal.optString("symbol", "Unknown symbol")} · ${deal.optString("closed_at", "time unavailable")} · P/L ${deal.optString("profit", "unavailable")}",
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth().background(BrokerSurface).padding(10.dp),
                    )
                }
            }
        }
    }

}

@Composable
private fun AccountBotControls(
    modifier: Modifier,
    details: VerifiedAccountDetails,
    connector: LoginConnector,
    busy: Boolean,
    riskAccepted: Boolean,
    onAcceptRisk: () -> Unit,
    message: String?,
    policyMessage: String,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onEmergencyStop: () -> Unit,
    onCheckPolicy: () -> Unit,
    onOpenArming: () -> Unit,
) {
    var showRealStartConfirmation by remember(details.login, details.server, details.mode) {
        mutableStateOf(false)
    }
    val canControl = connector in setOf(LoginConnector.MT5_TERMINAL, LoginConnector.DERIV_OPTIONS)
    val canExecute = canControl
    val canStart = canExecute && (connector != LoginConnector.DERIV_OPTIONS || details.mode == "DEMO")
    val running = details.engineState in setOf("RUNNING", "PAUSED")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ModeArmingPolicy: $policyMessage", color = WarningAmber, fontSize = 12.sp, modifier = Modifier.testTag("mode_arming_state"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (details.mode.equals("LIVE", ignoreCase = true)) {
                        showRealStartConfirmation = true
                    } else {
                        onStart()
                    }
                },
                enabled = canStart && !busy && !running,
                modifier = Modifier.weight(1f).height(48.dp).testTag("account_start"),
                colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
            ) { Text("START") }
            Button(onClick = onPauseResume, enabled = canExecute && !busy && running, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = BrokerSurfaceVariant)) { Text(if (details.engineState == "PAUSED") "RESUME" else "PAUSE") }
            Button(onClick = onStop, enabled = canExecute && !busy && running, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = LossRed)) { Text("STOP") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCheckPolicy, enabled = canControl && !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("CHECK POLICY") }
            TextButton(onClick = onOpenArming, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("MODE ARMING") }
        }
        if (!canControl) Text("This verified provider connection is read-only.", color = TextSecondary, fontSize = 12.sp)
        // No persistence/backup acknowledgement is required for a verified LIVE MT5 session.
        message?.let { Text(it, color = if (it.contains("BLOCKED", true) || it.contains("FAILED", true)) LossRed else TextSecondary, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onEmergencyStop,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account_emergency_stop"),
            colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White),
        ) { Text("EMERGENCY STOP", fontSize = 15.sp) }
    }
    if (showRealStartConfirmation) AlertDialog(
        onDismissRequest = { showRealStartConfirmation = false },
        title = { Text("REAL money confirmation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You are about to trade REAL money")
                Text("Confirm only after checking the REAL account, lot size, risk limits and stop-loss settings.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showRealStartConfirmation = false
                    onStart()
                },
                modifier = Modifier.testTag("confirm_real_trade_start"),
            ) { Text("CONFIRM REAL TRADE") }
        },
        dismissButton = {
            TextButton(
                onClick = { showRealStartConfirmation = false },
                modifier = Modifier.testTag("cancel_real_trade_start"),
            ) { Text("CANCEL") }
        },
    )

}

@Composable
private fun AccountRow(account: SavedLoginAccount, onClick: () -> Unit) {
    val testKey = "${account.spec.id}_${account.mode.name}_${account.profileKey}".replace(Regex("[^A-Za-z0-9_-]"), "_")
    val brokerName = if (account.spec.id == "CTRADER" && account.server.isNotBlank()) account.server else account.spec.displayName
    Card(
        Modifier.fillMaxWidth().height(64.dp).clickable { onClick() }.testTag("saved_account_$testKey"),
        colors = CardDefaults.cardColors(containerColor = BrokerSurface),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(brokerName, color = TextPrimary)
                Text(maskSavedLogin(account.login), color = TextSecondary, fontSize = 12.sp)
            }
            Text(account.mode.name, color = ElectricCyan, fontSize = 12.sp)
        }
    }
}

private fun maskSavedLogin(value: String): String =
    if (value.length <= 4) "••••" else "••••${value.takeLast(4)}"
@Composable
private fun BrokerRowCard(row: BrokerRow, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().height(64.dp).clickable { onClick() }.testTag("broker_${row.specId}"),
        colors = CardDefaults.cardColors(containerColor = BrokerSurfaceVariant),
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title.take(1), color = ElectricCyan, fontSize = 20.sp, modifier = Modifier.size(36.dp))
            Column {
                Text(row.title, color = TextPrimary, fontSize = 16.sp)
                Text(row.subtitle, color = ElectricCyan, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LoginInput(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    keyboard: KeyboardType,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp)
            .testTag(if (label == "Master Password") "field_password" else "field_${label.replace(" ", "_").lowercase()}"),
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = loginKeyboardActions(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun loginKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Down) },
        onDone = { keyboardController?.hide() },
    )
}
