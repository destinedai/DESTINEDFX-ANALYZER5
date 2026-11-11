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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.broker.BrokerLoginSpec
import com.example.data.broker.BrokerLoginSpecs
import com.example.data.broker.LoginConnector
import com.example.data.broker.LoginMode
import com.example.data.broker.HostProfile
import com.example.data.broker.ModeArmingPolicy
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

private val LoginBlue = Color(0xFF1976D2)

private enum class FlowPage { ACCOUNTS, BROKERS, LOGIN, PICK_ACCOUNT, VERIFIED }

@Composable
fun MetaTraderBrokerFlow(
    secrets: SecretsManager,
    modeArmingPolicy: ModeArmingPolicy,
    context: Context,
    hostProfile: HostProfile,
    onGrantAccess: (BrokerLoginSpec, LoginForm) -> String?,
    onConnect: suspend (BrokerLoginSpec, LoginForm, (ConnectStage) -> Unit) -> ConnectOutcome,
    onOpenModes: () -> Unit,
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
                    page = FlowPage.VERIFIED
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
    val goBack = { when (page) { FlowPage.ACCOUNTS -> onClose(); FlowPage.BROKERS -> page = FlowPage.ACCOUNTS; FlowPage.LOGIN, FlowPage.PICK_ACCOUNT -> page = FlowPage.BROKERS; FlowPage.VERIFIED -> page = FlowPage.ACCOUNTS } }

    Column(Modifier.fillMaxSize().background(DarkBackground).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("‹", color = TextPrimary, fontSize = 32.sp, modifier = Modifier.testTag("broker_back").clickable { goBack() })
            Text(
                when (page) {
                    FlowPage.ACCOUNTS -> "Accounts"
                    FlowPage.BROKERS -> "Brokers"
                    FlowPage.PICK_ACCOUNT -> "Select account"
                    else -> selected?.displayName ?: "Account"
                },
                color = TextPrimary, fontSize = 22.sp, modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
        when (page) {
            FlowPage.ACCOUNTS -> {
                Text("Saved accounts", color = TextSecondary, fontSize = 14.sp)
                val saved = BrokerLoginSpecs.all.filter { it.id.endsWith("_MT5") && secrets.getManagedMT5AccountLogin("${it.id.removeSuffix("_MT5")}_DEMO").isNotBlank() }
                if (saved.isEmpty()) Text("No saved accounts. Add a broker to connect.", color = TextSecondary, modifier = Modifier.testTag("accounts_empty"))
                saved.forEach { spec ->
                    AccountRow(spec, secrets.getManagedMT5AccountLogin("${spec.id.removeSuffix("_MT5")}_DEMO")) { selected = spec; page = FlowPage.LOGIN }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { page = FlowPage.BROKERS }, modifier = Modifier.fillMaxWidth().testTag("add_account"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) { Text("ADD ACCOUNT") }
            }
            FlowPage.BROKERS -> {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("find_broker"), label = { Text("Find broker") }, singleLine = true)
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
                                    token = secrets.getDerivApiToken(secrets.getDerivAccountMode()),
                                )
                                LoginConnector.CTRADER -> LoginForm(
                                    mode = LoginMode.LIVE,
                                    appId = secrets.getCTraderClientId(),
                                    token = secrets.getCTraderClientSecret(),
                                )
                                else -> LoginForm()
                            }
                            failure = null
                            page = FlowPage.LOGIN
                        }
                    }
                }
                TextButton(onClick = { manualDialog = true }, modifier = Modifier.fillMaxWidth().testTag("cant_find_broker")) { Text(BrokerCatalog.CANT_FIND, color = ElectricCyan) }
            }
            FlowPage.LOGIN -> {
                val spec = selected ?: return@Column
                Text("Open a demo account", color = TextPrimary, modifier = Modifier.clickable { openUrl(spec.demoSignupUrl) }.testTag("open_demo"))
                Text("Open a real account", color = TextSecondary, modifier = Modifier.clickable { openUrl(spec.demoSignupUrl) }.testTag("open_real"))
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
                    Text("Type: ${form.type.label}", color = TextPrimary, modifier = Modifier.fillMaxWidth().clickable { typeMenu = true }.padding(12.dp).testTag("type_dropdown"))
                    DropdownMenu(typeMenu, { typeMenu = false }) { LoginRules.availableTypes(spec, form.mode, hostProfile).forEach { type -> DropdownMenuItem(text = { Text(type.label) }, onClick = { form = form.copy(type = type); typeMenu = false }) } }
                    if (form.mode == LoginMode.LIVE && form.type == AccessType.INVESTOR) {
                        Text("Investor access cannot trade", color = WarningAmber, fontSize = 14.sp)
                    }
                    if (form.mode == LoginMode.LIVE && hostProfile != HostProfile.PERSISTENT) {
                        Text("LIVE Trade account passwords are blocked on an EPHEMERAL host; choose Investor access.", color = WarningAmber, fontSize = 14.sp)
                    }
                    LoginInput("Login", form.login, { form = form.copy(login = it.filter(Char::isDigit)) }, KeyboardType.Number)
                    LoginInput("Password", form.password, { form = form.copy(password = it) }, KeyboardType.Password, true)
                    var serverMenu by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        form.server,
                        { server -> form = LoginRules.withMode(form.copy(server = server), LoginRules.modeForServer(server)) },
                        modifier = Modifier.fillMaxWidth().clickable { serverMenu = true }.testTag("server_field"),
                        label = { Text("Server") },
                        singleLine = true,
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
                    LoginInput("Personal Access Token", form.token, { form = form.copy(token = it) }, KeyboardType.Password, true)
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
                    LoginInput("Client Secret", form.token, { form = form.copy(token = it) }, KeyboardType.Password, true)
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
                        enabled = spotwareApprovalConfirmed && form.appId.isNotBlank() && form.token.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("grant_access"),
                        colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
                    ) { Text("GRANT ACCESS") }
                }
                if (spec.connector != LoginConnector.CTRADER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = form.savePassword, enabled = securePolicy.enabled, onCheckedChange = { form = form.copy(savePassword = it) })
                        Text(if (spec.connector == LoginConnector.DERIV_OPTIONS) "Save token" else "Save password", color = TextPrimary)
                    }
                    if (!securePolicy.enabled) Text(securePolicy.reason.orEmpty(), color = WarningAmber, fontSize = 11.sp)
                }
                Text("Forgot password?", color = ElectricCyan, modifier = Modifier.clickable { openUrl(spec.passwordRecoveryUrl) })
                stage?.let { Text("${it.label}...", color = ElectricCyan, modifier = Modifier.testTag("connect_progress")) }
                failure?.let { Text(it, color = LossRed, modifier = Modifier.testTag("connect_error")) }
                Button(enabled = !busy && LoginRules.loginEnabled(spec, form), onClick = { connect(spec, form) },
                    modifier = Modifier.fillMaxWidth().testTag("login_button"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White) else Text("LOGIN")
                }
                if (busy) TextButton(onClick = { busy = false }) { Text("CANCEL") }
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
            FlowPage.VERIFIED -> {
                Text("Account verified", color = ElectricCyan, fontSize = 20.sp)
                if (modeMismatch && verifiedMode != null) {
                    Text("Terminal reports $verifiedMode; reconnect as $verifiedMode", color = WarningAmber, modifier = Modifier.testTag("terminal_mode_mismatch"))
                    Button(
                        onClick = {
                            val targetMode = LoginMode.valueOf(requireNotNull(verifiedMode))
                            val retryForm = LoginRules.withMode(form, targetMode)
                            form = retryForm
                            connect(requireNotNull(selected), retryForm)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("reconnect_reported_mode"),
                        colors = ButtonDefaults.buttonColors(containerColor = LoginBlue),
                    ) { Text("RECONNECT AS $verifiedMode") }
                } else {
                    Text("Terminal-reported mode: ${verifiedMode ?: "unverified"}", color = TextPrimary)
                    Button(onClick = onOpenModes, modifier = Modifier.fillMaxWidth().testTag("open_modes"), colors = ButtonDefaults.buttonColors(containerColor = LoginBlue)) { Text("OPEN MODES") }
                }
            }
        }
    }
    if (manualDialog) AlertDialog(onDismissRequest = { manualDialog = false }, title = { Text("Find your broker") }, text = { OutlinedTextField(manualName, { manualName = it }, label = { Text("Company or server name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { ManualBrokerProfile.create(manualName); manualDialog = false; manualName = "" }) { Text("SAVE") } }, dismissButton = { TextButton(onClick = { manualDialog = false }) { Text("CANCEL") } })
}

@Composable private fun AccountRow(spec: BrokerLoginSpec, login: String, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable { onClick() }.testTag("saved_account_${spec.id}"), colors = CardDefaults.cardColors(containerColor = DarkSurface)) { Column(Modifier.padding(14.dp)) { Text(spec.displayName, color = TextPrimary); Text("$login · saved account", color = TextSecondary, fontSize = 12.sp) } } }
@Composable private fun BrokerRowCard(row: BrokerRow, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable { onClick() }.testTag("broker_${row.specId}"), colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant), shape = RoundedCornerShape(0.dp)) { Row(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(row.title.take(1), color = ElectricCyan, fontSize = 20.sp, modifier = Modifier.size(36.dp)); Column { Text(row.title, color = TextPrimary); Text(row.subtitle, color = ElectricCyan, fontSize = 12.sp) } } } }
@Composable private fun LoginInput(label: String, value: String, onValue: (String) -> Unit, keyboard: KeyboardType, secret: Boolean = false) { OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth().testTag("field_${label.replace(" ", "_").lowercase()}"), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }
