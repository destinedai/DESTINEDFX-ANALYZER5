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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import com.example.security.SecretsManager
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LossRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

private val LoginBlue = Color(0xFF1976D2)

private enum class FlowPage { ACCOUNTS, BROKERS, LOGIN, PICK_ACCOUNT, ACCOUNT, SETTINGS, ADVANCED }
private enum class AccountTab { TRADE, HISTORY, BOT }
private data class PendingBrokerAction(val action: String, val ticket: String, val label: String)

@Composable
fun MetaTraderBrokerFlow(
    secrets: SecretsManager,
    context: Context,
    hostProfile: HostProfile,
    onGrantAccess: (BrokerLoginSpec, LoginForm) -> String?,
    onConnect: suspend (BrokerLoginSpec, LoginForm, (ConnectStage) -> Unit) -> ConnectOutcome,
    onRefreshAccount: suspend (BrokerLoginSpec, LoginForm) -> VerifiedAccountDetails?,
    onAccountCommand: suspend (BrokerLoginSpec, LoginForm, String, String?) -> String,
    onEmergencyStop: suspend (BrokerLoginSpec, LoginForm) -> String,
    onAcceptDemo: suspend (BrokerLoginSpec, LoginForm) -> String,
    onArmLive: suspend (BrokerLoginSpec, LoginForm, String, String) -> String,
    onOpenAdvanced: () -> Unit,
    onClose: () -> Unit,
) {
    var page by remember { mutableStateOf(FlowPage.ACCOUNTS) }
    var selected by remember { mutableStateOf<BrokerLoginSpec?>(null) }
    var query by remember { mutableStateOf("") }
    var manualDialog by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var form by remember { mutableStateOf(LoginForm()) }
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
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rows = remember { BrokerCatalog.rows() }
    val filtered = BrokerCatalog.search(rows, query)
    val securePolicy = LoginRules.savePasswordPolicy(secrets.isSecureStorageAvailable)
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
                }
                is ConnectOutcome.SelectAccount -> {
                    accountChoices = outcome.accounts
                    page = FlowPage.PICK_ACCOUNT
                }
                is ConnectOutcome.Failed -> failure = outcome.sentence
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
                    if (refreshed.login == accountDetails?.login && refreshed.mode == accountDetails?.mode) {
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

    Column(Modifier.fillMaxSize().background(DarkBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                color = TextPrimary, fontSize = 22.sp, modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            if (page == FlowPage.ACCOUNTS) {
                TextButton(onClick = { page = FlowPage.SETTINGS }, modifier = Modifier.height(48.dp).testTag("settings")) {
                    Text("SETTINGS", color = ElectricCyan)
                }
            }
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
                        form = when (account.spec.connector) {
                            LoginConnector.MT5_TERMINAL -> {
                                val accessType = AccessType.values().firstOrNull {
                                    it.wireValue == secrets.getManagedMT5AccountAccessType(account.profileKey)
                                } ?: AccessType.INVESTOR
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
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                ) { Text("ADVANCED", color = TextPrimary) }
            }
            FlowPage.ADVANCED -> {
                Button(
                    onClick = onOpenAdvanced,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("legacy_bot_tools"),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                ) { Text("LEGACY BOT TOOLS", color = TextPrimary) }
            }
            FlowPage.BROKERS -> {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("find_broker"), label = { Text("Find broker") }, singleLine = true, textStyle = TextStyle(fontSize = 16.sp))
                if (filtered.isEmpty()) {
                    Text(BrokerCatalog.EMPTY_STATE, color = TextPrimary, fontSize = 18.sp, modifier = Modifier.testTag("broker_empty"))
                    Text(BrokerCatalog.CAUTION, color = WarningAmber, fontSize = 12.sp)
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.specId }) { row ->
                        BrokerRowCard(row) {
                            selected = BrokerLoginSpecs.byId(row.specId)
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
                                else -> LoginForm()
                            }
                            failure = null
                            page = FlowPage.LOGIN
                        }
                    }
                }
                TextButton(onClick = { manualDialog = true }, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("cant_find_broker")) { Text(BrokerCatalog.CANT_FIND, color = ElectricCyan) }
            }
            FlowPage.LOGIN -> {
                val spec = selected ?: return@Column
                Text("Open a demo account", color = TextPrimary, modifier = Modifier.fillMaxWidth().height(48.dp).clickable { openUrl(spec.demoSignupUrl) }.testTag("open_demo"))
                Text("Open a real account", color = TextSecondary, modifier = Modifier.fillMaxWidth().height(48.dp).clickable { openUrl(spec.realAccountUrl) }.testTag("open_real"))
                Text("Login to an existing account", color = TextPrimary, fontSize = 18.sp)
                if (spec.connector == LoginConnector.MT5_TERMINAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LoginMode.values().forEach { mode ->
                            val label = mode.name
                            Text(
                                label,
                                color = if (form.mode == mode) Color.White else TextSecondary,
                                modifier = Modifier.weight(1f).height(48.dp)
                                    .background(if (form.mode == mode) LoginBlue else DarkSurfaceVariant, RoundedCornerShape(4.dp))
                                    .clickable { form = LoginRules.withMode(form, mode) }
                                    .testTag("login_mode_${label.lowercase()}"),
                            )
                        }
                    }
                    var typeMenu by remember { mutableStateOf(false) }
                    Text("Type: ${form.type.label}", color = TextPrimary, modifier = Modifier.fillMaxWidth().height(48.dp).clickable { typeMenu = true }.padding(12.dp).testTag("type_dropdown"))
                    DropdownMenu(typeMenu, { typeMenu = false }) { LoginRules.availableTypes(spec, form.mode, hostProfile).forEach { type -> DropdownMenuItem(text = { Text(type.label) }, onClick = { form = form.copy(type = type); typeMenu = false }) } }
                    if (form.mode == LoginMode.LIVE && form.type == AccessType.INVESTOR) {
                        Text("Investor access cannot trade", color = WarningAmber, fontSize = 14.sp)
                    }
                    if (form.mode == LoginMode.LIVE && hostProfile != HostProfile.PERSISTENT) {
                        Text("LIVE Trade account passwords are blocked on an EPHEMERAL host; choose Investor access.", color = WarningAmber, fontSize = 14.sp)
                    }
                    LoginInput("Login", form.login, { form = form.copy(login = it.filter(Char::isDigit)) }, KeyboardType.Number)
                    LoginInput("Password", form.password, { form = form.copy(password = it) }, KeyboardType.Password, true, ImeAction.Next)
                    var serverMenu by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        form.server,
                        { server -> form = LoginRules.withMode(form.copy(server = server), LoginRules.modeForServer(server)) },
                        modifier = Modifier.fillMaxWidth().clickable { serverMenu = true }.testTag("server_field"),
                        label = { Text("Server") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        keyboardActions = loginKeyboardActions(),
                    )
                    DropdownMenu(serverMenu, { serverMenu = false }) {
                        ServerOptions.build(spec, emptyList(), emptyList()).forEach { option ->
                            if (option is ServerOption.Suggested) DropdownMenuItem(
                                text = { Text(option.value) },
                                onClick = {
                                    form = LoginRules.withMode(form.copy(server = option.value), LoginRules.modeForServer(option.value))
                                    serverMenu = false
                                },
                            ) else DropdownMenuItem(text = { Text("Type server name") }, onClick = { serverMenu = false })
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
                                    .background(if (form.mode == mode) LoginBlue else DarkSurfaceVariant, RoundedCornerShape(4.dp))
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
                                    .background(if (form.mode == mode) LoginBlue else DarkSurfaceVariant, RoundedCornerShape(4.dp))
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
                        androidx.compose.material3.Checkbox(checked = form.savePassword, enabled = securePolicy.enabled, onCheckedChange = { form = form.copy(savePassword = it) })
                        Text(if (spec.connector == LoginConnector.DERIV_OPTIONS) "Save token" else "Save password", color = TextPrimary)
                    }
                    if (!securePolicy.enabled) Text(securePolicy.reason.orEmpty(), color = WarningAmber, fontSize = 11.sp)
                }
                Text("Forgot password?", color = ElectricCyan, modifier = Modifier.fillMaxWidth().height(48.dp).clickable { openUrl(spec.passwordRecoveryUrl) })
                stage?.let { Text("${it.label}...", color = ElectricCyan, modifier = Modifier.testTag("connect_progress")) }
                failure?.let { Text(it, color = LossRed, modifier = Modifier.testTag("connect_error")) }
                Button(enabled = !busy && LoginRules.loginEnabled(spec, form), onClick = { connect(spec, form) },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login_button"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White) else Text("LOGIN")
                }
                if (busy) TextButton(onClick = { busy = false }, modifier = Modifier.height(48.dp)) { Text("CANCEL") }
            }
            FlowPage.PICK_ACCOUNT -> {
                val spec = selected ?: return@Column
                accountChoices.forEach { account ->
                    Button(
                        onClick = {
                            val next = form.copy(accountId = account.id, mode = account.mode)
                            form = next
                            connect(spec, next)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("provider_account_${account.id}"),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
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
                                    connect(spec, retryForm)
                                },
                                modifier = Modifier.fillMaxWidth().testTag("reconnect_reported_mode"),
                                colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
                            ) { Text("RECONNECT AS $verifiedMode") }
                        }
                        AccountSummary(
                            spec = spec,
                            details = details,
                            nowMs = clockNowMs,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            AccountTab.values().forEach { tab ->
                                Button(
                                    onClick = { accountTab = tab },
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("account_tab_${tab.name.lowercase()}"),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (accountTab == tab) LoginBlue else DarkSurfaceVariant),
                                ) { Text(tab.name, color = TextPrimary) }
                            }
                        }
                        when (accountTab) {
                            AccountTab.TRADE -> AccountTradeList(
                                modifier = Modifier.weight(1f),
                                details = details,
                                enabled = spec.connector == LoginConnector.MT5_TERMINAL && !accountBusy,
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
                Text("ModeArmingPolicy: $policyMessage", color = WarningAmber)
                Text("DEMO acceptance requires fresh identity-matched read-only evidence. MT5 also requires a validated DEMO PASS. LIVE arming requires PERSISTENT host evidence, DEMO acceptance, fresh verification, risk clearance and explicit re-authentication.")
                OutlinedTextField(
                    value = liveTypedConfirmation,
                    onValueChange = { liveTypedConfirmation = it },
                    label = { Text("Typed LIVE confirmation") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = liveMinimumLot,
                    onValueChange = { liveMinimumLot = it },
                    label = { Text("Broker minimum lot") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                            try { policyMessage = onArmLive(spec, form, liveTypedConfirmation, liveMinimumLot) }
                            catch (_: Exception) { policyMessage = "LIVE arming failed closed. Verify the fresh account state and required evidence." }
                            finally { accountBusy = false }
                        }
                    },
                ) { Text("ARM LIVE") }
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
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
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
            MetricLine("Balance", details.balance, details.currency)
            MetricLine("Equity", details.equity, details.currency)
            MetricLine("Free margin", details.freeMargin, details.currency)
            MetricLine("Leverage", details.leverage?.toDouble(), "x")
            MetricLine("Margin level", details.marginLevel, "%")
            if (details.hostProfile == HostProfile.EPHEMERAL) {
                Text("Test host, DEMO only, stops when idle", color = WarningAmber, fontSize = 12.sp, modifier = Modifier.testTag("ephemeral_host_notice"))
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
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
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
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
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
                        modifier = Modifier.fillMaxWidth().background(DarkSurface).padding(10.dp),
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
    message: String?,
    policyMessage: String,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onEmergencyStop: () -> Unit,
    onCheckPolicy: () -> Unit,
    onOpenArming: () -> Unit,
) {
    val canControl = connector in setOf(LoginConnector.MT5_TERMINAL, LoginConnector.DERIV_OPTIONS)
    val canStart = canControl && (connector != LoginConnector.DERIV_OPTIONS || details.mode == "DEMO")
    val running = details.engineState in setOf("RUNNING", "PAUSED")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ModeArmingPolicy: $policyMessage", color = WarningAmber, fontSize = 12.sp, modifier = Modifier.testTag("mode_arming_state"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onStart, enabled = canStart && !busy && !running, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) { Text("START") }
            Button(onClick = onPauseResume, enabled = canControl && !busy && running, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)) { Text(if (details.engineState == "PAUSED") "RESUME" else "PAUSE") }
            Button(onClick = onStop, enabled = canControl && !busy && running, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = LossRed)) { Text("STOP") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCheckPolicy, enabled = canControl && !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("CHECK POLICY") }
            TextButton(onClick = onOpenArming, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) { Text("MODE ARMING") }
        }
        if (!canControl) Text("This verified provider connection is read-only.", color = TextSecondary, fontSize = 12.sp)
        message?.let { Text(it, color = if (it.contains("BLOCKED", true) || it.contains("FAILED", true)) LossRed else TextSecondary, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onEmergencyStop,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account_emergency_stop"),
            colors = ButtonDefaults.buttonColors(containerColor = LossRed, contentColor = Color.White),
        ) { Text("EMERGENCY STOP", fontSize = 15.sp) }
    }
}

@Composable
private fun AccountRow(account: SavedLoginAccount, onClick: () -> Unit) {
    val testKey = "${account.spec.id}_${account.mode.name}_${account.profileKey}".replace(Regex("[^A-Za-z0-9_-]"), "_")
    val brokerName = if (account.spec.id == "CTRADER" && account.server.isNotBlank()) account.server else account.spec.displayName
    Card(
        Modifier.fillMaxWidth().height(64.dp).clickable { onClick() }.testTag("saved_account_$testKey"),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
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
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth().height(56.dp)
            .testTag("field_${label.replace(" ", "_").lowercase()}"),
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
