package com.example.data.broker

/**
 * Phone login metadata only. This table describes UI and routing contracts; it is not evidence
 * that an account exists or that a broker session is connected.
 */
enum class LoginConnector { DERIV_OPTIONS, CTRADER, MT5_TERMINAL }
enum class LoginField { LOGIN, PASSWORD, SERVER, APP_ID, TOKEN, GRANT_ACCESS }
enum class LoginMode { DEMO, LIVE }

data class BrokerLoginSpec(
    val id: String,
    val displayName: String,
    val connector: LoginConnector,
    val fields: List<LoginField>,
    val fieldLabels: Map<LoginField, String>,
    val modes: Set<LoginMode>,
    val investorAccess: Boolean,
    val connectedEvidence: Set<String>,
    val demoSignupUrl: String,
    val passwordRecoveryUrl: String,
    val sourceUrls: List<String>,
    val capabilityRow: String,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank())
        require(fields.isNotEmpty())
        require(fields.all { fieldLabels[it].orEmpty().isNotBlank() })
        require(modes.isNotEmpty())
        require(connectedEvidence.isNotEmpty())
        require(sourceUrls.isNotEmpty())
        require(capabilityRow.isNotBlank())
    }
}

object BrokerLoginSpecs {
    private const val DERIV_AUTH = "https://developers.deriv.com/docs/intro/authentication/"
    private const val DERIV_HOME = "https://deriv.com/"
    private const val DERIV_LOGIN = "https://app.deriv.com/"
    private const val FBS_HOME = "https://fbs.com/"
    private const val FBS_LOGIN = "https://fbs.com/cabinet/login"
    private const val EXNESS_HOME = "https://www.exness.com/"
    private const val EXNESS_LOGIN = "https://my.exness.com/"
    private const val HFM_HOME = "https://www.hfm.com/"
    private const val HFM_LOGIN = "https://my.hfm.com/"
    private const val CTRADER_AUTH = "https://help.ctrader.com/open-api/account-authentication/"
    private const val CTRADER_HOME = "https://www.ctrader.com/"
    private const val CTRADER_LOGIN = "https://id.ctrader.com/"
    private const val MT5_PYTHON_INIT = "https://www.mql5.com/en/docs/python_metatrader5/mt5initialize_py"
    private const val MT5_PYTHON_LOGIN = "https://www.mql5.com/en/docs/python_metatrader5/mt5login_py"

    private val mt5Fields = listOf(LoginField.LOGIN, LoginField.PASSWORD, LoginField.SERVER)
    private val mt5Labels = mapOf(
        LoginField.LOGIN to "Login",
        LoginField.PASSWORD to "Password",
        LoginField.SERVER to "Server",
    )
    private val ctraderFields = listOf(LoginField.GRANT_ACCESS)
    private val ctraderLabels = mapOf(LoginField.GRANT_ACCESS to "Grant access")
    private val derivFields = listOf(LoginField.APP_ID, LoginField.TOKEN)
    private val derivLabels = mapOf(LoginField.APP_ID to "App ID", LoginField.TOKEN to "Personal Access Token")

    val all: List<BrokerLoginSpec> = listOf(
        BrokerLoginSpec(
            id = "DERIV_OPTIONS", displayName = "Deriv Options / Multipliers", connector = LoginConnector.DERIV_OPTIONS,
            fields = derivFields, fieldLabels = derivLabels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = false,
            connectedEvidence = setOf("authenticated Deriv session", "fresh account data", "fresh quote", "account identity match"),
            demoSignupUrl = DERIV_HOME, passwordRecoveryUrl = DERIV_LOGIN, sourceUrls = listOf(DERIV_AUTH, DERIV_HOME),
            capabilityRow = "Phone API login; read-only verification and DEMO acceptance required; LIVE remains owner-armed.",
        ),
        BrokerLoginSpec(
            id = "DERIV_CTRADER", displayName = "Deriv cTrader", connector = LoginConnector.CTRADER,
            fields = ctraderFields, fieldLabels = ctraderLabels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            connectedEvidence = setOf("Spotware-approved app", "owner grant", "fresh account data", "fresh quote", "account identity match"),
            demoSignupUrl = DERIV_HOME, passwordRecoveryUrl = CTRADER_LOGIN, sourceUrls = listOf(CTRADER_AUTH, DERIV_HOME),
            capabilityRow = "cTrader Open API grant flow; account access is unverified until Spotware approval and owner consent succeed.",
        ),
        BrokerLoginSpec(
            id = "DERIV_MT5", displayName = "Deriv MT5", connector = LoginConnector.MT5_TERMINAL,
            fields = mt5Fields, fieldLabels = mt5Labels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            connectedEvidence = setOf("fresh EA heartbeat", "exact login", "exact server", "company alias match", "terminal mode match"),
            demoSignupUrl = DERIV_HOME, passwordRecoveryUrl = DERIV_LOGIN, sourceUrls = listOf(DERIV_HOME, MT5_PYTHON_INIT, MT5_PYTHON_LOGIN),
            capabilityRow = "Owner-hosted genuine MT5 terminal; Android does not log into MT5 directly.",
        ),
        BrokerLoginSpec(
            id = "FBS_MT5", displayName = "FBS MT5", connector = LoginConnector.MT5_TERMINAL,
            fields = mt5Fields, fieldLabels = mt5Labels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            connectedEvidence = setOf("fresh EA heartbeat", "exact login", "exact server", "company alias match", "terminal mode match"),
            demoSignupUrl = FBS_HOME, passwordRecoveryUrl = FBS_LOGIN, sourceUrls = listOf(FBS_HOME, FBS_LOGIN, MT5_PYTHON_INIT, MT5_PYTHON_LOGIN),
            capabilityRow = "Owner-hosted genuine MT5 terminal; exact broker-issued server is required and never guessed.",
        ),
        BrokerLoginSpec(
            id = "EXNESS_MT5", displayName = "Exness MT5", connector = LoginConnector.MT5_TERMINAL,
            fields = mt5Fields, fieldLabels = mt5Labels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            connectedEvidence = setOf("fresh EA heartbeat", "exact login", "exact server", "company alias match", "terminal mode match"),
            demoSignupUrl = EXNESS_HOME, passwordRecoveryUrl = EXNESS_LOGIN, sourceUrls = listOf(EXNESS_HOME, EXNESS_LOGIN, MT5_PYTHON_INIT, MT5_PYTHON_LOGIN),
            capabilityRow = "Owner-hosted genuine MT5 terminal; Exness API is not substituted for the MT5 route.",
        ),
        BrokerLoginSpec(
            id = "HFM_MT5", displayName = "HFM MT5", connector = LoginConnector.MT5_TERMINAL,
            fields = mt5Fields, fieldLabels = mt5Labels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            demoSignupUrl = HFM_HOME, passwordRecoveryUrl = HFM_LOGIN, sourceUrls = listOf(HFM_HOME, HFM_LOGIN, MT5_PYTHON_INIT, MT5_PYTHON_LOGIN),
            capabilityRow = "Owner-hosted genuine MT5 terminal; exact broker-issued server and company identity are required.",
        ),
        BrokerLoginSpec(
            id = "CTRADER", displayName = "Generic cTrader", connector = LoginConnector.CTRADER,
            fields = ctraderFields, fieldLabels = ctraderLabels, modes = setOf(LoginMode.DEMO, LoginMode.LIVE), investorAccess = true,
            connectedEvidence = setOf("Spotware-approved app", "owner grant", "fresh account data", "fresh quote", "account identity match"),
            demoSignupUrl = CTRADER_HOME, passwordRecoveryUrl = CTRADER_LOGIN, sourceUrls = listOf(CTRADER_AUTH, CTRADER_HOME),
            capabilityRow = "Generic cTrader Open API grant flow; broker/account enablement remains owner-verification dependent.",
        ),
    )

    fun byId(id: String): BrokerLoginSpec? = all.firstOrNull { it.id == id.trim().uppercase() }
    fun forConnector(connector: LoginConnector): List<BrokerLoginSpec> = all.filter { it.connector == connector }
}
