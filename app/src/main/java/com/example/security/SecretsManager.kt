package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.deriv.DerivRealAuthorizationLease
import com.example.data.broker.ModeAccountIdentity
import com.example.data.broker.ModeDemoAcceptance
import com.example.data.broker.ModeArmingAuditEvent
import java.security.MessageDigest

/**
 * Institutional Keystore-backed secrets manager.
 * Stores Supabase, Broker (MT5, OANDA), and Risk Configuration.
 * All values stored in Android Keystore via EncryptedSharedPreferences.
 */
class SecretsManager internal constructor(
    private val ctx: Context,
    private val encryptedPreferencesFactory: (() -> android.content.SharedPreferences)? = null
) {

    companion object {
        private const val PREFS   = "destinedfx_secrets"
        private const val TG_TOK  = "telegram_bot_token"
        private const val TG_CHAT = "telegram_chat_id"
        private const val WA_KEY  = "whatsapp_api_key"
        private const val WA_PHN  = "whatsapp_phone"
        private const val MT5_PW  = "mt5_broker_password"
        private const val MT5_SAVE_PASSWORD = "mt5_save_password"
        private const val MT5_LOGIN = "mt5_broker_login"
        private const val MT5_INVESTOR_PW = "mt5_investor_password"
        private const val MT5_AC  = "mt5_account_number"
        private const val MT5_SV  = "mt5_broker_server"
        private const val MT5_BALANCE = "mt5_account_balance"
        private const val MT5_ACCOUNT_TYPE = "mt5_account_type"
        private const val MT5_LEVERAGE = "mt5_leverage"
        private const val MT5_SESSION_STATUS = "mt5_session_status"
        private const val MT5_CURRENCY = "mt5_currency"
        private const val MT5_LAST_LOGIN_MS = "mt5_last_login_ms"
        private const val OANDA_KEY = "oanda_api_key"
        private const val OANDA_ACC = "oanda_account_id"
        private const val CONFIGURED_EQUITY = "configured_equity"
        private const val SELECTED_STRATEGY = "selected_strategy_id"
        private const val BROKER_SYMBOL_OVERRIDES = "broker_symbol_overrides"
        private const val DERIV_API_TOKEN = "deriv_api_token" // legacy, migrated on first read
        private const val DERIV_API_TOKEN_DEMO = "deriv_api_token_demo"
        private const val DERIV_API_TOKEN_REAL = "deriv_api_token_real"
        private const val DERIV_APP_ID = "deriv_app_id"
        private const val DERIV_ACCOUNT_MODE = "deriv_account_mode"
        private const val DERIV_SAVE_TOKEN = "deriv_save_token"
        private const val DERIV_DEMO_ACCEPTED = "deriv_demo_accepted"
        private const val DERIV_DEMO_ACCEPTED_ACCOUNT = "deriv_demo_accepted_account"
        private const val DERIV_DEMO_ACCEPTED_CURRENCY = "deriv_demo_accepted_currency"
        private const val DERIV_DEMO_ACCEPTED_SESSION = "deriv_demo_accepted_session"
        private const val DERIV_DEMO_ACCEPTED_CREDENTIAL = "deriv_demo_accepted_credential"
        private const val DERIV_DEMO_ACCEPTED_AT = "deriv_demo_accepted_at"
        private const val DERIV_REAL_EXECUTION_ENABLED = "deriv_real_execution_enabled"
        private const val TG_STATUS = "telegram_connection_status"
        private const val TG_NOTIF_ENABLED = "telegram_notifications_enabled"
        private const val CF_ACCESS_CLIENT_ID = "cf_access_client_id"
        private const val CF_ACCESS_CLIENT_SECRET = "cf_access_client_secret"
        private const val MT5_BRIDGE_TOKEN = "mt5_remote_bridge_app_token"
        private const val MT5_MANAGED_SERVICE_URL = "mt5_managed_service_url"
        private const val MT5_BRIDGE_PENDING_ID = "mt5_remote_bridge_pending_id"
        private const val MT5_BRIDGE_PENDING_ACTION = "mt5_remote_bridge_pending_action"
        private const val MT5_BRIDGE_SAFETY_ORIGINAL_ID = "mt5_remote_bridge_safety_original_id"
        private const val MT5_BRIDGE_SAFETY_ORIGINAL_ACTION = "mt5_remote_bridge_safety_original_action"
        private const val MT5_BRIDGE_SAFETY_STOP_ID = "mt5_remote_bridge_safety_stop_id"
        private const val CTRADER_CLIENT_ID = "ctrader_client_id"
        private const val CTRADER_CLIENT_SECRET = "ctrader_client_secret"
        private const val CTRADER_REDIRECT_URI = "ctrader_redirect_uri"
        private const val CTRADER_ACCESS_TOKEN = "ctrader_access_token"
        private const val CTRADER_REFRESH_TOKEN = "ctrader_refresh_token"
        private const val CTRADER_ACCESS_EXPIRES_AT_MS = "ctrader_access_expires_at_ms"
        private const val MODE_DEMO_ACCEPTANCE_PREFIX = "mode_demo_acceptance_"
        private const val MODE_ARMING_AUDIT = "mode_arming_audit_v1"
        private val MODE_ARMING_AUDIT_LOCK = Any()
    }

    val isSecureStorageAvailable: Boolean
    private val prefs: android.content.SharedPreferences
    // Trading selections are non-secret preferences and must remain independent from Analyzer state.
    private val botConfigPrefs = ctx.getSharedPreferences("destinedfx_bot_config", Context.MODE_PRIVATE)
    /** Live trading authorization is deliberately process-memory-only and never restored after restart. */
    @Volatile private var derivRealLease: DerivRealAuthorizationLease? = null

    init {
        prefs = try {
            encryptedPreferencesFactory?.invoke() ?: run {
                val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    ctx, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SecretsManager", "Encrypted Android Keystore storage unavailable; refusing credential persistence", e)
            UnavailableSharedPreferences
        }
        isSecureStorageAvailable = prefs !== UnavailableSharedPreferences
    }

    fun saveCTraderOAuth(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMs: Long
    ): Boolean {
        if (!isSecureStorageAvailable || listOf(clientId, clientSecret, redirectUri, accessToken, refreshToken).any { it.isBlank() } || accessExpiresAtMs <= 0L) return false
        return prefs.edit()
            .putString(CTRADER_CLIENT_ID, clientId)
            .putString(CTRADER_CLIENT_SECRET, clientSecret)
            .putString(CTRADER_REDIRECT_URI, redirectUri)
            .putString(CTRADER_ACCESS_TOKEN, accessToken)
            .putString(CTRADER_REFRESH_TOKEN, refreshToken)
            .putLong(CTRADER_ACCESS_EXPIRES_AT_MS, accessExpiresAtMs)
            .commit()
    }

    fun saveCTraderApplication(clientId: String, clientSecret: String, redirectUri: String): Boolean {
        if (!isSecureStorageAvailable || listOf(clientId, clientSecret, redirectUri).any { it.isBlank() }) return false
        return prefs.edit().putString(CTRADER_CLIENT_ID, clientId)
            .putString(CTRADER_CLIENT_SECRET, clientSecret)
            .putString(CTRADER_REDIRECT_URI, redirectUri).commit()
    }

    fun getCTraderClientId(): String = prefs.getString(CTRADER_CLIENT_ID, "").orEmpty()
    fun getCTraderClientSecret(): String = prefs.getString(CTRADER_CLIENT_SECRET, "").orEmpty()
    fun getCTraderRedirectUri(): String = prefs.getString(CTRADER_REDIRECT_URI, "").orEmpty()
    fun getCTraderAccessToken(): String = prefs.getString(CTRADER_ACCESS_TOKEN, "").orEmpty()
    fun getCTraderRefreshToken(): String = prefs.getString(CTRADER_REFRESH_TOKEN, "").orEmpty()
    fun getCTraderAccessExpiresAtMs(): Long = prefs.getLong(CTRADER_ACCESS_EXPIRES_AT_MS, 0L)

    /** Non-secret fingerprint of the registered cTrader application credentials. */
    fun getCTraderCredentialGeneration(): String {
        if (!isSecureStorageAvailable) return ""
        val id = getCTraderClientId()
        val secret = getCTraderClientSecret()
        val redirect = getCTraderRedirectUri()
        if (id.isBlank() || secret.isBlank() || redirect.isBlank()) return ""
        return sha256Hex(id + "\u0000" + secret + "\u0000" + redirect)
    }

    /** Persist owner acceptance only for the exact verified DEMO account and credential generation. */
    fun savePathDemoAcceptance(identity: ModeAccountIdentity, evidenceDigest: String): Boolean {
        if (!isSecureStorageAvailable || identity.mode != com.example.data.broker.AccountMode.DEMO ||
            !identity.isValid() || !evidenceDigest.matches(Regex("[0-9a-fA-F]{64}"))) return false
        val value = org.json.JSONObject()
            .put("path", identity.path.name)
            .put("broker", identity.broker)
            .put("account_id", identity.accountId)
            .put("server", identity.server)
            .put("mode", identity.mode.name)
            .put("credential_generation", identity.credentialGeneration)
            .put("accepted_at_ms", System.currentTimeMillis())
            .put("evidence_digest", evidenceDigest.lowercase())
        return prefs.edit().putString(MODE_DEMO_ACCEPTANCE_PREFIX + identity.acceptanceKey(), value.toString()).commit()
    }

    fun getPathDemoAcceptance(identity: ModeAccountIdentity): ModeDemoAcceptance? {
        if (!isSecureStorageAvailable || identity.mode != com.example.data.broker.AccountMode.DEMO) return null
        val raw = prefs.getString(MODE_DEMO_ACCEPTANCE_PREFIX + identity.acceptanceKey(), null) ?: return null
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val saved = ModeAccountIdentity(
                path = com.example.data.broker.ModePath.valueOf(obj.getString("path")),
                broker = obj.getString("broker"),
                accountId = obj.getString("account_id"),
                server = obj.optString("server"),
                mode = com.example.data.broker.AccountMode.valueOf(obj.getString("mode")),
                credentialGeneration = obj.getString("credential_generation")
            )
            if (saved != identity) return null
            ModeDemoAcceptance(saved, obj.getLong("accepted_at_ms"), obj.getString("evidence_digest"))
        }.getOrNull()
    }

    /** Permanently invalidates a prior owner DEMO acceptance when credentials are rotated. */
    fun clearPathDemoAcceptance(identity: ModeAccountIdentity): Boolean {
        if (!isSecureStorageAvailable || identity.mode != com.example.data.broker.AccountMode.DEMO) return false
        return prefs.edit().remove(MODE_DEMO_ACCEPTANCE_PREFIX + identity.acceptanceKey()).commit()
    }

    /** Append-only local arming audit containing hashes and reason codes only. */
    fun appendModeArmingAudit(event: ModeArmingAuditEvent): Boolean {
        if (!isSecureStorageAvailable || !event.identityFingerprint.matches(Regex("[0-9a-f]{64}")) ||
            event.event.isBlank() || event.path.isBlank() || event.mode !in setOf("DEMO", "LIVE") ||
            event.reasonCode.any { !it.isLetterOrDigit() && it != '_' }) return false
        synchronized(MODE_ARMING_AUDIT_LOCK) {
            return runCatching {
                val rows = org.json.JSONArray(prefs.getString(MODE_ARMING_AUDIT, "[]").orEmpty())
                if (rows.length() >= 2048) return false
                rows.put(org.json.JSONObject()
                    .put("event", event.event.take(48))
                    .put("path", event.path.take(32))
                    .put("mode", event.mode)
                    .put("identity_fingerprint", event.identityFingerprint)
                    .put("timestamp_ms", event.timestampMs)
                    .put("allowed", event.allowed)
                    .put("reason_code", event.reasonCode.take(64)))
                prefs.edit().putString(MODE_ARMING_AUDIT, rows.toString()).commit()
            }.getOrDefault(false)
        }
    }

    fun modeArmingAuditCount(): Int =
        if (!isSecureStorageAvailable) 0 else runCatching {
            org.json.JSONArray(prefs.getString(MODE_ARMING_AUDIT, "[]")).length()
        }.getOrDefault(0)

    fun saveCTraderRefreshedToken(accessToken: String, refreshToken: String, accessExpiresAtMs: Long): Boolean {
        if (!isSecureStorageAvailable || accessToken.isBlank() || refreshToken.isBlank() || accessExpiresAtMs <= 0L) return false
        return prefs.edit().putString(CTRADER_ACCESS_TOKEN, accessToken)
            .putString(CTRADER_REFRESH_TOKEN, refreshToken)
            .putLong(CTRADER_ACCESS_EXPIRES_AT_MS, accessExpiresAtMs).commit()
    }

    fun clearCTraderOAuth(): Boolean = isSecureStorageAvailable && prefs.edit()
        .remove(CTRADER_CLIENT_ID).remove(CTRADER_CLIENT_SECRET).remove(CTRADER_REDIRECT_URI)
        .remove(CTRADER_ACCESS_TOKEN).remove(CTRADER_REFRESH_TOKEN).remove(CTRADER_ACCESS_EXPIRES_AT_MS)
        .commit()


    fun saveTelegramToken(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(TG_TOK, v).commit()
    fun saveTelegramChatId(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(TG_CHAT, v).commit()
    fun saveWhatsAppApiKey(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(WA_KEY, v).commit()
    fun saveWhatsAppPhone(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(WA_PHN, v).commit()
    fun saveMT5Password(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_PW, v).commit()
    fun saveMT5PasswordIfAllowed(v: String, enabled: Boolean): Boolean {
        if (!isSecureStorageAvailable) return false
        val edit = prefs.edit().putBoolean(MT5_SAVE_PASSWORD, enabled)
        if (enabled) edit.putString(MT5_PW, v) else edit.remove(MT5_PW)
        return edit.commit()
    }
    fun isMT5PasswordSaveEnabled(): Boolean = prefs.getBoolean(MT5_SAVE_PASSWORD, false)
    fun clearMT5Credentials(): Boolean {
        if (!isSecureStorageAvailable) return false
        return prefs.edit().remove(MT5_PW).remove(MT5_INVESTOR_PW).remove(MT5_LOGIN).remove(MT5_AC)
            .remove(MT5_SV).putString(MT5_SESSION_STATUS, "DISCONNECTED").commit()
    }
    fun saveMT5Login(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_LOGIN, v).commit()
    fun saveMT5InvestorPassword(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_INVESTOR_PW, v).commit()
    fun saveMT5AccountNumber(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_AC, v).commit()
    fun saveMT5BrokerServer(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_SV, v).commit()
    fun saveMT5AccountBalance(v: Double): Boolean = isSecureStorageAvailable && v.isFinite() && prefs.edit().putString(MT5_BALANCE, v.toString()).commit()
    fun saveMT5AccountType(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_ACCOUNT_TYPE, v).commit()
    fun saveMT5Leverage(v: Int): Boolean = isSecureStorageAvailable && v > 0 && prefs.edit().putInt(MT5_LEVERAGE, v).commit()
    fun saveMT5SessionStatus(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_SESSION_STATUS, v).commit()
    fun saveMT5Currency(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(MT5_CURRENCY, v).commit()
    fun saveMT5LastLoginMs(v: Long): Boolean = isSecureStorageAvailable && v >= 0L && prefs.edit().putLong(MT5_LAST_LOGIN_MS, v).commit()
    fun saveOandaApiKey(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(OANDA_KEY, v).commit()
    fun saveOandaAccountId(v: String): Boolean = isSecureStorageAvailable && prefs.edit().putString(OANDA_ACC, v).commit()
    fun saveConfiguredEquity(v: Double) = prefs.edit().putString(CONFIGURED_EQUITY, v.toString()).apply()
    fun saveSelectedStrategy(v: String) = prefs.edit().putString(SELECTED_STRATEGY, v).apply()
    fun saveBotSymbol(v: String) { if (v.isNotBlank()) botConfigPrefs.edit().putString("symbol", v.trim()).apply() }
    fun getBotSymbol(): String = botConfigPrefs.getString("symbol", "EUR/USD") ?: "EUR/USD"
    fun saveBotStrategy(v: String) { if (v.isNotBlank()) botConfigPrefs.edit().putString("strategy", v.trim()).apply() }
    fun getBotStrategy(): String = botConfigPrefs.getString("strategy", "smc-confluence-1.0.0") ?: "smc-confluence-1.0.0"
    fun saveBotTimeframe(v: String) { if (v.isNotBlank()) botConfigPrefs.edit().putString("timeframe", v.trim()).apply() }
    fun getBotTimeframe(): String = botConfigPrefs.getString("timeframe", "M15") ?: "M15"
    fun saveBotRiskPercent(v: Double): Boolean {
        if (!v.isFinite() || v <= 0.0 || v > 1.0) return false
        botConfigPrefs.edit().putString("risk_percent", v.toString()).apply()
        return true
    }
    fun getBotRiskPercent(): Double = botConfigPrefs.getString("risk_percent", "0.25")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 } ?: 0.25
    fun getThemeMode(): String = botConfigPrefs.getString("theme_mode", "System")
        ?.takeIf { it in setOf("System", "Light", "Dark") } ?: "System"
    fun saveThemeMode(mode: String): Boolean = mode in setOf("System", "Light", "Dark") &&
        botConfigPrefs.edit().putString("theme_mode", mode).commit()
    /** Deriv credentials are persisted only after encrypted Keystore storage is initialized. */
    fun saveDerivApiToken(v: String, mode: String = getDerivAccountMode()): Boolean {
        val normalizedMode = mode.trim().lowercase()
        if (!isSecureStorageAvailable || v.isBlank() || normalizedMode !in setOf("demo", "real")) return false
        invalidateDerivRealAuthorization()
        val key = if (normalizedMode == "real") DERIV_API_TOKEN_REAL else DERIV_API_TOKEN_DEMO
        return prefs.edit().putString(key, v.trim()).remove(DERIV_API_TOKEN)
            .remove(DERIV_DEMO_ACCEPTED).remove(DERIV_DEMO_ACCEPTED_ACCOUNT).commit()
    }
    fun saveDerivApiTokenIfAllowed(v: String, mode: String, save: Boolean): Boolean {
        val normalizedMode = mode.trim().lowercase()
        if (!isSecureStorageAvailable || v.isBlank() || normalizedMode !in setOf("demo", "real")) return false
        invalidateDerivRealAuthorization()
        val key = if (normalizedMode == "real") DERIV_API_TOKEN_REAL else DERIV_API_TOKEN_DEMO
        val edit = prefs.edit().putBoolean(DERIV_SAVE_TOKEN, save)
            .remove(DERIV_DEMO_ACCEPTED).remove(DERIV_DEMO_ACCEPTED_ACCOUNT)
        if (save) edit.putString(key, v.trim()) else edit.remove(key)
        return edit.remove(DERIV_API_TOKEN).commit()
    }
    fun isDerivApiTokenSaveEnabled(): Boolean = isSecureStorageAvailable && prefs.getBoolean(DERIV_SAVE_TOKEN, false)
    fun saveDerivAppId(v: String): Boolean {
        if (!isSecureStorageAvailable || v.isBlank() || !v.trim().all(Char::isDigit)) return false
        invalidateDerivRealAuthorization()
        return prefs.edit().putString(DERIV_APP_ID, v.trim())
            .remove(DERIV_DEMO_ACCEPTED).remove(DERIV_DEMO_ACCEPTED_ACCOUNT)
            .remove(DERIV_DEMO_ACCEPTED_CURRENCY).remove(DERIV_DEMO_ACCEPTED_SESSION)
            .remove(DERIV_DEMO_ACCEPTED_CREDENTIAL).remove(DERIV_DEMO_ACCEPTED_AT).commit()
    }
    /** Mode is explicit; selecting REAL is separate from arming live trading. */
    fun saveDerivAccountMode(v: String): Boolean {
        val mode = v.trim().lowercase()
        if (!isSecureStorageAvailable || mode !in setOf("demo", "real")) return false
        invalidateDerivRealAuthorization()
        return prefs.edit().putString(DERIV_ACCOUNT_MODE, mode).remove(DERIV_DEMO_ACCEPTED).commit()
    }

    fun saveDerivDemoAcceptance(accountId: String, currency: String, sessionId: String, credentialGeneration: String): Boolean {
        if (!isSecureStorageAvailable || getDerivAccountMode() != "demo" || accountId.isBlank() || currency.isBlank() || sessionId.isBlank() || credentialGeneration.isBlank()) return false
        return prefs.edit().putBoolean(DERIV_DEMO_ACCEPTED, true)
            .putString(DERIV_DEMO_ACCEPTED_ACCOUNT, accountId.trim())
            .putString(DERIV_DEMO_ACCEPTED_CURRENCY, currency.trim())
            .putString(DERIV_DEMO_ACCEPTED_SESSION, sessionId.trim())
            .putString(DERIV_DEMO_ACCEPTED_CREDENTIAL, credentialGeneration.trim())
            .putLong(DERIV_DEMO_ACCEPTED_AT, System.currentTimeMillis()).commit()
    }

    fun hasCurrentDerivDemoAcceptance(accountId: String? = null, currency: String? = null, sessionId: String? = null, credentialGeneration: String? = null): Boolean {
        if (!isSecureStorageAvailable || getDerivAccountMode() != "demo" || !prefs.getBoolean(DERIV_DEMO_ACCEPTED, false)) return false
        return listOf(accountId to DERIV_DEMO_ACCEPTED_ACCOUNT, currency to DERIV_DEMO_ACCEPTED_CURRENCY, sessionId to DERIV_DEMO_ACCEPTED_SESSION, credentialGeneration to DERIV_DEMO_ACCEPTED_CREDENTIAL)
            .all { (expected, key) -> expected == null || expected.isNotBlank() && expected == prefs.getString(key, "") }
    }
    fun enableDerivRealExecution(
        explicitConfirmation: Boolean,
        accountId: String,
        accountType: String,
        currency: String,
        authenticatedSessionId: String,
        credentialGeneration: String
    ): Boolean {
        if (!isSecureStorageAvailable || !explicitConfirmation || !getDerivAccountMode().equals("real", ignoreCase = true) ||
            accountId.isBlank() || !accountType.equals("real", ignoreCase = true) ||
            currency.isBlank() || authenticatedSessionId.isBlank() || credentialGeneration.isBlank()
        ) return false
        derivRealLease = DerivRealAuthorizationLease(
            brokerIdentity = "DERIV_OPTIONS",
            accountId = accountId.trim(),
            accountType = accountType.trim().lowercase(),
            currency = currency.trim().uppercase(),
            authenticatedSessionId = authenticatedSessionId,
            credentialGeneration = credentialGeneration,
            armedAtMs = System.currentTimeMillis()
        )
        return true
    }
    /** Legacy arming has no account/session identity and is intentionally rejected. */
    @Deprecated("Use the account- and session-bound overload")
    fun enableDerivRealExecution(explicitConfirmation: Boolean): Boolean = false
    fun getDerivRealAuthorizationLease(nowMs: Long = System.currentTimeMillis()): DerivRealAuthorizationLease? {
        activeDerivRealLease(nowMs)
        return derivRealLease
    }
    fun isDerivRealExecutionAuthorized(
        accountId: String,
        accountType: String,
        currency: String,
        authenticatedSessionId: String,
        credentialGeneration: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isSecureStorageAvailable) return false
        return activeDerivRealLease(nowMs)?.matches(
            broker = "DERIV_OPTIONS",
            currentAccountId = accountId,
            currentAccountType = accountType,
            currentCurrency = currency,
            currentSessionId = authenticatedSessionId,
            currentCredentialGeneration = credentialGeneration,
            nowMs = nowMs
        ) == true
    }
    private fun activeDerivRealLease(nowMs: Long): DerivRealAuthorizationLease? {
        val lease = derivRealLease ?: return null
        if (lease.state != DerivRealAuthorizationLease.State.ARMED) return null
        if (!lease.isValidAt(nowMs)) {
            derivRealLease = lease.copy(state = DerivRealAuthorizationLease.State.INVALIDATED)
            return null
        }
        return lease
    }
    fun invalidateDerivRealAuthorization() {
        derivRealLease = derivRealLease?.copy(state = DerivRealAuthorizationLease.State.INVALIDATED)
    }
    fun disableDerivRealExecution(): Boolean {
        if (!isSecureStorageAvailable) return false
        invalidateDerivRealAuthorization()
        return prefs.edit().remove(DERIV_REAL_EXECUTION_ENABLED).putString(DERIV_ACCOUNT_MODE, "demo").commit()
    }
    fun clearDerivCredentials(): Boolean {
        if (!isSecureStorageAvailable) return false
        invalidateDerivRealAuthorization()
        return prefs.edit().remove(DERIV_API_TOKEN).remove(DERIV_API_TOKEN_DEMO).remove(DERIV_API_TOKEN_REAL)
            .remove(DERIV_APP_ID).remove(DERIV_ACCOUNT_MODE).remove(DERIV_SAVE_TOKEN).commit()
    }
    fun saveTelegramConnectionStatus(status: String) = prefs.edit().putString(TG_STATUS, status).apply()
    fun saveTelegramNotificationsEnabled(enabled: Boolean) = prefs.edit().putBoolean(TG_NOTIF_ENABLED, enabled).apply()

    /** Store only a public service origin, never a credential or token. */
    fun getManagedMT5ServiceUrl(): String = if (isSecureStorageAvailable) prefs.getString(MT5_MANAGED_SERVICE_URL, "") ?: "" else ""
    fun saveManagedMT5ServiceUrl(value: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val origin = value.trim().trimEnd('/')
        if (origin.isBlank()) return false
        return prefs.edit().putString(MT5_MANAGED_SERVICE_URL, origin).commit()
    }

    /** Cloudflare Access service credentials remain in encrypted preferences and are never logged. */
    fun getCloudflareAccessClientId(): String = if (isSecureStorageAvailable) prefs.getString(CF_ACCESS_CLIENT_ID, "") ?: "" else ""
    fun getCloudflareAccessClientSecret(): String = if (isSecureStorageAvailable) prefs.getString(CF_ACCESS_CLIENT_SECRET, "") ?: "" else ""
    fun saveCloudflareAccessServiceToken(clientId: String, clientSecret: String): Boolean {
        if (!isSecureStorageAvailable || clientId.isBlank() || clientSecret.isBlank() ||
            clientId.length > 512 || clientSecret.length > 2048 ||
            clientId.any { it.isISOControl() } || clientSecret.any { it.isISOControl() }
        ) return false
        return prefs.edit().putString(CF_ACCESS_CLIENT_ID, clientId.trim())
            .putString(CF_ACCESS_CLIENT_SECRET, clientSecret.trim()).commit()
    }
    fun clearCloudflareAccessServiceToken(): Boolean = isSecureStorageAvailable &&
        prefs.edit().remove(CF_ACCESS_CLIENT_ID).remove(CF_ACCESS_CLIENT_SECRET).commit()

    /** Bridge app credentials are persisted only when Android encrypted preferences initialized successfully. */
    private fun managedProfile(profile: String): String = profile.trim().uppercase().also { require(it == "DEMO" || it == "LIVE") { "Profile must be DEMO or LIVE" } }
    private fun profileKey(kind: String, profile: String) = "${kind}_${managedProfile(profile).lowercase()}"
    fun saveMT5BridgeAppToken(value: String, profile: String = "DEMO"): Boolean {
        check(isSecureStorageAvailable) { "Secure storage unavailable" }
        return prefs.edit().putString(profileKey(MT5_BRIDGE_TOKEN, profile), value).commit()
    }
    fun getMT5BridgeAppToken(profile: String = "DEMO"): String = if (isSecureStorageAvailable) {
        prefs.getString(profileKey(MT5_BRIDGE_TOKEN, profile), "") ?: ""
    } else ""
    fun clearMT5BridgeAppToken(profile: String = "DEMO") {
        if (isSecureStorageAvailable) {
            prefs.edit().remove(profileKey(MT5_BRIDGE_TOKEN, profile)).apply()
        }
    }
    fun saveManagedMT5Profile(
        profile: String, login: String, server: String, accessType: String,
        password: String, savePassword: Boolean = false
    ): Boolean {
        if (!isSecureStorageAvailable || login.isBlank() || server.isBlank() ||
            accessType !in setOf("MASTER", "INVESTOR") || (savePassword && password.isBlank())
        ) return false
        val mode = runCatching { managedProfile(profile) }.getOrNull() ?: return false
        val edit = prefs.edit().putString("managed_mt5_${mode.lowercase()}_login", login.trim())
            .putString("managed_mt5_${mode.lowercase()}_server", server.trim())
            .putString("managed_mt5_${mode.lowercase()}_access", accessType)
            .putBoolean("managed_mt5_${mode.lowercase()}_save_password", savePassword)
        if (savePassword) edit.putString("managed_mt5_${mode.lowercase()}_password", password)
        else edit.remove("managed_mt5_${mode.lowercase()}_password")
        return edit.commit()
    }
    fun getManagedMT5Login(profile: String): String = prefs.getString("managed_mt5_${managedProfile(profile).lowercase()}_login", "") ?: ""
    fun getManagedMT5Server(profile: String): String = prefs.getString("managed_mt5_${managedProfile(profile).lowercase()}_server", "") ?: ""
    fun getManagedMT5AccessType(profile: String): String = prefs.getString("managed_mt5_${managedProfile(profile).lowercase()}_access", "MASTER") ?: "MASTER"
    fun isManagedMT5PasswordSaveEnabled(profile: String): Boolean = prefs.getBoolean("managed_mt5_${managedProfile(profile).lowercase()}_save_password", false)
    fun getManagedMT5Password(profile: String): String = prefs.getString("managed_mt5_${managedProfile(profile).lowercase()}_password", "") ?: ""
    fun getManagedMT5SelectedProfile(): String = (prefs.getString("managed_mt5_selected_profile", "DEMO") ?: "DEMO").uppercase().takeIf { it in setOf("DEMO", "LIVE") } ?: "DEMO"
    fun saveManagedMT5SelectedProfile(profile: String) { prefs.edit().putString("managed_mt5_selected_profile", managedProfile(profile)).apply() }
    /** Writes both reconciliation values atomically and synchronously before any command request is issued. */
    fun saveMT5BridgePendingCommand(requestId: String, action: String, profile: String = "DEMO"): Boolean {
        if (!isSecureStorageAvailable || requestId.isBlank() || action.isBlank()) return false
        val suffix = managedProfile(profile).lowercase()
        return prefs.edit().putString("${MT5_BRIDGE_PENDING_ID}_$suffix", requestId)
            .putString("${MT5_BRIDGE_PENDING_ACTION}_$suffix", action).commit()
    }
    fun getMT5BridgePendingCommandId(profile: String = "DEMO"): String = if (isSecureStorageAvailable) {
        prefs.getString("${MT5_BRIDGE_PENDING_ID}_${managedProfile(profile).lowercase()}", "") ?: ""
    } else ""
    fun getMT5BridgePendingCommandAction(profile: String = "DEMO"): String = if (isSecureStorageAvailable) {
        prefs.getString("${MT5_BRIDGE_PENDING_ACTION}_${managedProfile(profile).lowercase()}", "") ?: ""
    } else ""
    fun clearMT5BridgePendingCommand(profile: String = "DEMO") {
        if (isSecureStorageAvailable) {
            val suffix = managedProfile(profile).lowercase()
            prefs.edit().remove("${MT5_BRIDGE_PENDING_ID}_$suffix").remove("${MT5_BRIDGE_PENDING_ACTION}_$suffix").commit()
        }
    }
    fun saveMT5BridgeSafetyStop(originalId: String, originalAction: String, stopId: String, profile: String = "DEMO"): Boolean {
        if (!isSecureStorageAvailable || originalId.isBlank() || originalAction.isBlank() || stopId.isBlank()) return false
        val suffix = managedProfile(profile).lowercase()
        return prefs.edit().putString("${MT5_BRIDGE_SAFETY_ORIGINAL_ID}_$suffix", originalId)
            .putString("${MT5_BRIDGE_SAFETY_ORIGINAL_ACTION}_$suffix", originalAction)
            .putString("${MT5_BRIDGE_SAFETY_STOP_ID}_$suffix", stopId).commit()
    }
    fun getMT5BridgeSafetyOriginalId(profile: String = "DEMO"): String = if (isSecureStorageAvailable) prefs.getString("${MT5_BRIDGE_SAFETY_ORIGINAL_ID}_${managedProfile(profile).lowercase()}", "") ?: "" else ""
    fun getMT5BridgeSafetyOriginalAction(profile: String = "DEMO"): String = if (isSecureStorageAvailable) prefs.getString("${MT5_BRIDGE_SAFETY_ORIGINAL_ACTION}_${managedProfile(profile).lowercase()}", "") ?: "" else ""
    fun getMT5BridgeSafetyStopId(profile: String = "DEMO"): String = if (isSecureStorageAvailable) prefs.getString("${MT5_BRIDGE_SAFETY_STOP_ID}_${managedProfile(profile).lowercase()}", "") ?: "" else ""
    fun clearMT5BridgeSafetyStop(profile: String = "DEMO") {
        if (isSecureStorageAvailable) {
            val suffix = managedProfile(profile).lowercase()
            prefs.edit().remove("${MT5_BRIDGE_SAFETY_ORIGINAL_ID}_$suffix").remove("${MT5_BRIDGE_SAFETY_ORIGINAL_ACTION}_$suffix").remove("${MT5_BRIDGE_SAFETY_STOP_ID}_$suffix").commit()
        }
    }
    
    fun getSelectedMT5BrokerBrand(): String =
        (prefs.getString("mt5_selected_broker_brand", "FBS") ?: "FBS").uppercase()
            .takeIf { it in setOf("FBS", "EXNESS", "HFM", "DERIV") } ?: "FBS"

    fun saveSelectedMT5BrokerBrand(brand: String): Boolean {
        val normalized = brand.trim().uppercase()
        if (!isSecureStorageAvailable || normalized !in setOf("FBS", "EXNESS", "HFM", "DERIV")) return false
        return prefs.edit().putString("mt5_selected_broker_brand", normalized).commit()
    }

    /**
     * Broker-qualified MT5 profiles keep each broker/mode pairing independent.
     * Example keys: FBS_DEMO, EXNESS_LIVE, HFM_DEMO. Legacy DEMO/LIVE
     * methods below remain available for existing callers, but new broker
     * connections must use these account-scoped methods.
     */
    private fun mt5AccountProfile(profileKey: String): String =
        profileKey.trim().uppercase().also {
            require(it.matches(Regex("[A-Z0-9_-]{1,80}"))) { "Invalid broker account profile key" }
        }

    private fun mt5AccountKey(kind: String, profileKey: String): String =
        "mt5_account_${mt5AccountProfile(profileKey).lowercase()}_${kind}"

    fun saveMT5BridgeAccountAppToken(profileKey: String, value: String): Boolean =
        isSecureStorageAvailable && value.isNotBlank() &&
            prefs.edit().putString(mt5AccountKey("bridge_token", profileKey), value).commit()

    fun getMT5BridgeAccountAppToken(profileKey: String): String =
        if (isSecureStorageAvailable) prefs.getString(mt5AccountKey("bridge_token", profileKey), "") ?: "" else ""

    fun clearMT5BridgeAccountAppToken(profileKey: String): Boolean =
        isSecureStorageAvailable && prefs.edit().remove(mt5AccountKey("bridge_token", profileKey)).commit()

    /** Configured profile namespaces that have a saved opaque app token; no token values are returned. */
    fun getConfiguredMT5AccountProfileKeys(): List<String> {
        if (!isSecureStorageAvailable) return emptyList()
        val knownBrands = setOf("FBS", "EXNESS", "HFM", "DERIV")
        return prefs.all.keys.asSequence()
            .filter { it.startsWith("mt5_account_") && it.endsWith("_bridge_token") }
            .map { it.removePrefix("mt5_account_").removeSuffix("_bridge_token").uppercase() }
            .filter { profile ->
                val separator = profile.lastIndexOf('_')
                separator > 0 &&
                    profile.substringBeforeLast('_') in knownBrands &&
                    profile.substring(separator + 1) in setOf("DEMO", "LIVE") &&
                    prefs.getString(mt5AccountKey("bridge_token", profile), "").orEmpty().isNotBlank()
            }
            .distinct()
            .sorted()
            .toList()
    }

    fun saveManagedMT5AccountProfile(
        profileKey: String, login: String, server: String, accessType: String,
        password: String, savePassword: Boolean = false
    ): Boolean {
        if (!isSecureStorageAvailable || !login.matches(Regex("[0-9]{1,20}")) ||
            server.isBlank() || server.any { it.code < 32 || it.code == 127 } ||
            accessType !in setOf("MASTER", "INVESTOR") || (savePassword && password.isBlank())
        ) return false
        val prefix = mt5AccountKey("profile", profileKey).removeSuffix("_profile")
        val edit = prefs.edit()
            .putString("${prefix}_login", login.trim())
            .putString("${prefix}_server", server.trim())
            .putString("${prefix}_access", accessType)
            .putBoolean("${prefix}_save_password", savePassword)
            .putString("${prefix}_credential_generation", sha256Hex(login.trim() + "\u0000" + server.trim() + "\u0000" + password))
        if (savePassword) edit.putString("${prefix}_password", password)
        else edit.remove("${prefix}_password")
        return edit.commit()
    }

    private fun mt5AccountValue(kind: String, profileKey: String): String =
        if (isSecureStorageAvailable) prefs.getString(mt5AccountKey(kind, profileKey), "") ?: "" else ""

    fun getManagedMT5AccountLogin(profileKey: String): String = mt5AccountValue("login", profileKey)
    fun getManagedMT5AccountServer(profileKey: String): String = mt5AccountValue("server", profileKey)
    fun getManagedMT5AccountAccessType(profileKey: String): String =
        if (isSecureStorageAvailable) prefs.getString(mt5AccountKey("access", profileKey), "MASTER") ?: "MASTER" else "MASTER"
    fun getManagedMT5AccountPassword(profileKey: String): String = mt5AccountValue("password", profileKey)
    fun isManagedMT5AccountPasswordSaveEnabled(profileKey: String): Boolean =
        isSecureStorageAvailable && prefs.getBoolean(mt5AccountKey("save_password", profileKey), false)
    fun getManagedMT5CredentialGeneration(profileKey: String): String = mt5AccountValue("credential_generation", profileKey)

    fun clearManagedMT5AccountProfile(profileKey: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val prefix = mt5AccountKey("profile", profileKey).removeSuffix("_profile")
        return prefs.edit().remove("${prefix}_login").remove("${prefix}_server")
            .remove("${prefix}_access").remove("${prefix}_password")
            .remove("${prefix}_save_password").remove("${prefix}_credential_generation").commit()
    }

    /** Stable account slots. Each slot is an independent encrypted namespace; no DEMO/LIVE values are shared. */
    fun getManagedMT5AccountRegistry(): List<com.example.data.mt5.MT5AccountSlot> {
        val slots = listOf("FBS", "EXNESS", "HFM", "DERIV").flatMap { brand ->
            listOf("DEMO", "LIVE").map { mode ->
                val key = "${brand}_${mode}"
                com.example.data.mt5.MT5AccountSlot(
                    accountId = key,
                    broker = brand,
                    platform = "MT5",
                    accountMode = mode,
                    login = getManagedMT5AccountLogin(key),
                    server = getManagedMT5AccountServer(key),
                    accessType = getManagedMT5AccountAccessType(key),
                    connected = false,
                    passwordSaved = isManagedMT5AccountPasswordSaveEnabled(key)
                )
            }
        }
        return slots
    }

    fun saveMT5BridgeAccountPendingCommand(requestId: String, action: String, profileKey: String): Boolean {
        if (!isSecureStorageAvailable || requestId.isBlank() || action.isBlank()) return false
        val prefix = mt5AccountKey("pending", profileKey).removeSuffix("_pending")
        return prefs.edit().putString("${prefix}_pending_id", requestId)
            .putString("${prefix}_pending_action", action).commit()
    }

    fun getMT5BridgeAccountPendingCommandId(profileKey: String): String =
        mt5AccountValue("pending_id", profileKey)
    fun getMT5BridgeAccountPendingCommandAction(profileKey: String): String =
        mt5AccountValue("pending_action", profileKey)

    fun clearMT5BridgeAccountPendingCommand(profileKey: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val prefix = mt5AccountKey("pending", profileKey).removeSuffix("_pending")
        return prefs.edit().remove("${prefix}_pending_id").remove("${prefix}_pending_action").commit()
    }

    fun saveMT5BridgeAccountSafetyStop(
        originalId: String, originalAction: String, stopId: String, profileKey: String
    ): Boolean {
        if (!isSecureStorageAvailable || originalId.isBlank() || originalAction.isBlank() || stopId.isBlank()) return false
        val prefix = mt5AccountKey("safety", profileKey).removeSuffix("_safety")
        return prefs.edit().putString("${prefix}_safety_original_id", originalId)
            .putString("${prefix}_safety_original_action", originalAction)
            .putString("${prefix}_safety_stop_id", stopId).commit()
    }

    fun getMT5BridgeAccountSafetyOriginalId(profileKey: String): String =
        mt5AccountValue("safety_original_id", profileKey)
    fun getMT5BridgeAccountSafetyOriginalAction(profileKey: String): String =
        mt5AccountValue("safety_original_action", profileKey)
    fun getMT5BridgeAccountSafetyStopId(profileKey: String): String =
        mt5AccountValue("safety_stop_id", profileKey)

    fun clearMT5BridgeAccountSafetyStop(profileKey: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val prefix = mt5AccountKey("safety", profileKey).removeSuffix("_safety")
        return prefs.edit().remove("${prefix}_safety_original_id")
            .remove("${prefix}_safety_original_action").remove("${prefix}_safety_stop_id").commit()
    }

    fun getSupabaseUrl(): String = ""
    fun getSupabaseAnonKey(): String = ""
    fun getSupabaseAccessToken(): String = ""
    fun getMetaApiRegion(): String = ""
    fun getMetaApiToken(): String = ""
    fun getMetaApiPendingTransactionId(): String = ""
    fun saveSupabaseUrl(v: String) {}
    fun saveSupabaseAnonKey(v: String) {}
    fun saveSupabaseAccessToken(v: String) {}
    fun saveMetaApiToken(v: String) {}
    fun saveMetaApiRegion(v: String) {}
    fun saveMetaApiPendingTransactionId(v: String) {}
    fun saveSupabaseUid(v: String) {}
    fun saveSupabaseRefreshToken(v: String) {}
    fun saveSupabaseEmailConfirmedAt(v: String) {}
    fun getSupabaseRefreshToken(): String = ""
    fun getSupabaseEmailConfirmedAt(): String = ""

    private fun derivDailyRiskPrefix(accountId: String, mode: String): String {
        val normalizedMode = mode.trim().uppercase()
        require(normalizedMode in setOf("DEMO", "REAL")) { "Deriv account mode must be DEMO or REAL" }
        val safeAccount = accountId.trim()
        require(safeAccount.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Deriv account ID must be a stable broker identifier without separators"
        }
        return "deriv_daily_risk_${normalizedMode}_${safeAccount}"
    }

    fun initializeDerivDailyRiskDay(accountId: String, mode: String, utcDay: String, startingEquity: Double): Boolean {
        if (!isSecureStorageAvailable || utcDay.isBlank() || !startingEquity.isFinite() || startingEquity <= 0.0) return false
        val prefix = derivDailyRiskPrefix(accountId, mode)
        val dayKey = "${prefix}_day"
        if (prefs.getString(dayKey, null) == utcDay) {
            return prefs.getString("${prefix}_baseline", null)?.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true
        }
        return prefs.edit()
            .putString(dayKey, utcDay)
            .putString("${prefix}_baseline", startingEquity.toString())
            .putBoolean("${prefix}_locked", false)
            .putBoolean("${prefix}_warn80", false)
            .putBoolean("${prefix}_warn90", false)
            .commit()
    }

    fun getDerivDailyRiskStartingEquity(accountId: String, mode: String, utcDay: String): Double? {
        if (!isSecureStorageAvailable) return null
        val prefix = derivDailyRiskPrefix(accountId, mode)
        if (prefs.getString("${prefix}_day", null) != utcDay) return null
        return prefs.getString("${prefix}_baseline", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun isDerivDailyRiskLocked(accountId: String, mode: String, utcDay: String): Boolean {
        if (!isSecureStorageAvailable) return true
        val prefix = derivDailyRiskPrefix(accountId, mode)
        return prefs.getString("${prefix}_day", null) == utcDay && prefs.getBoolean("${prefix}_locked", false)
    }

    fun lockDerivDailyRisk(accountId: String, mode: String, utcDay: String): Boolean {
        if (!isSecureStorageAvailable) return false
        val prefix = derivDailyRiskPrefix(accountId, mode)
        if (prefs.getString("${prefix}_day", null) != utcDay) return false
        return prefs.edit().putBoolean("${prefix}_locked", true).commit()
    }

    fun markDerivDailyRiskWarningSent(accountId: String, mode: String, utcDay: String, threshold: Int): Boolean {
        if (!isSecureStorageAvailable || threshold !in setOf(80, 90)) return false
        val prefix = derivDailyRiskPrefix(accountId, mode)
        if (prefs.getString("${prefix}_day", null) != utcDay) return false
        val key = "${prefix}_warn${threshold}"
        if (prefs.getBoolean(key, false)) return false
        return prefs.edit().putBoolean(key, true).commit()
    }

    fun getDerivApiToken(mode: String = getDerivAccountMode()): String {
        if (!isSecureStorageAvailable) return ""
        val normalizedMode = mode.trim().lowercase().takeIf { it == "demo" || it == "real" } ?: return ""
        val key = if (normalizedMode == "real") DERIV_API_TOKEN_REAL else DERIV_API_TOKEN_DEMO
        val scoped = prefs.getString(key, "").orEmpty()
        if (scoped.isNotBlank()) return scoped
        // Migrate the unscoped token only into the account mode selected when it is first read.
        val legacy = prefs.getString(DERIV_API_TOKEN, "").orEmpty()
        if (legacy.isNotBlank() && normalizedMode == getDerivAccountMode()) {
            prefs.edit().putString(key, legacy).remove(DERIV_API_TOKEN).commit()
            return legacy
        }
        return ""
    }
    // New Deriv REST/Options API rejects legacy App ID 1089; don't prefill a stale stored default.
    fun getDerivAppId(): String = if (!isSecureStorageAvailable) "" else (prefs.getString(DERIV_APP_ID, "") ?: "")
        .trim()
        .takeUnless { it.isBlank() || it == "1089" } ?: ""
    fun isDerivRealExecutionEnabled(nowMs: Long = System.currentTimeMillis()): Boolean =
        isSecureStorageAvailable && activeDerivRealLease(nowMs) != null
    /** A non-secret fingerprint changes when either the PAT or registered app ID changes. */
    fun getDerivCredentialGeneration(mode: String = getDerivAccountMode()): String {
        val token = getDerivApiToken(mode)
        val appId = getDerivAppId()
        if (token.isBlank() || appId.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest("$appId\u0000$token".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun getDerivAccountMode(): String = if (isSecureStorageAvailable) {
        (prefs.getString(DERIV_ACCOUNT_MODE, "demo") ?: "demo").lowercase().takeIf { it == "demo" || it == "real" } ?: "demo"
    } else "demo"
    fun getTelegramConnectionStatus(): String = prefs.getString(TG_STATUS, "Not configured") ?: "Not configured"
    fun getTelegramNotificationsEnabled(): Boolean = prefs.getBoolean(TG_NOTIF_ENABLED, false)

    fun getTelegramToken(): String     = prefs.getString(TG_TOK,  "") ?: ""
    fun getTelegramChatId(): String    = prefs.getString(TG_CHAT, "") ?: ""
    fun getWhatsAppApiKey(): String    = prefs.getString(WA_KEY,  "") ?: ""
    fun getWhatsAppPhone(): String     = prefs.getString(WA_PHN,  "") ?: ""
    fun getMT5Password(): String       = prefs.getString(MT5_PW,  "") ?: ""
    fun getMT5Login(): String          = prefs.getString(MT5_LOGIN, "") ?: ""
    fun getMT5InvestorPassword(): String = prefs.getString(MT5_INVESTOR_PW, "") ?: ""
    fun getMT5AccountNumber(): String  = prefs.getString(MT5_AC,  "") ?: ""
    fun getMT5BrokerServer(): String   = prefs.getString(MT5_SV,  "") ?: ""
    fun getMT5AccountBalance(): Double = prefs.getString(MT5_BALANCE, "0.0")?.toDoubleOrNull() ?: 0.0
    fun getMT5AccountType(): String    = prefs.getString(MT5_ACCOUNT_TYPE, "Demo") ?: "Demo"
    fun getMT5Leverage(): Int          = prefs.getInt(MT5_LEVERAGE, 0)
    fun getMT5SessionStatus(): String  = prefs.getString(MT5_SESSION_STATUS, "DISCONNECTED") ?: "DISCONNECTED"
    fun getMT5Currency(): String       = prefs.getString(MT5_CURRENCY, "USD") ?: "USD"
    fun getMT5LastLoginMs(): Long      = prefs.getLong(MT5_LAST_LOGIN_MS, 0L)
    fun getOandaApiKey(): String       = prefs.getString(OANDA_KEY, "") ?: ""
    fun getOandaAccountId(): String    = prefs.getString(OANDA_ACC, "") ?: ""
    fun getConfiguredEquity(): Double  = prefs.getString(CONFIGURED_EQUITY, "0.0")?.toDoubleOrNull() ?: 0.0
    fun getSelectedStrategy(): String  = prefs.getString(SELECTED_STRATEGY, "smc-confluence-1.0.0") ?: "smc-confluence-1.0.0"


    fun hasCredentials() = getMT5AccountNumber().isNotBlank()
    fun clearAll()       { invalidateDerivRealAuthorization(); prefs.edit().clear().apply() }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun getBrokerSymbolOverrides(): Map<String, String> {
        val jsonStr = prefs.getString(BROKER_SYMBOL_OVERRIDES, "") ?: ""
        if (jsonStr.isBlank()) return emptyMap()
        return try {
            val json = org.json.JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveBrokerSymbolOverride(appSymbol: String, brokerSymbol: String) {
        val current = getBrokerSymbolOverrides().toMutableMap()
        current[appSymbol] = brokerSymbol
        val json = org.json.JSONObject(current as Map<*, *>)
        prefs.edit().putString(BROKER_SYMBOL_OVERRIDES, json.toString()).apply()
    }

    fun removeBrokerSymbolOverride(appSymbol: String) {
        val current = getBrokerSymbolOverrides().toMutableMap()
        current.remove(appSymbol)
        val json = org.json.JSONObject(current as Map<*, *>)
        prefs.edit().putString(BROKER_SYMBOL_OVERRIDES, json.toString()).apply()
    }
}

class SecureStorageUnavailableException(cause: Throwable) :
    IllegalStateException("Secure Android Keystore storage is unavailable; credentials were not saved.", cause)

/** Read-only, non-persistent preferences used only to keep guest analysis available. */
private object UnavailableSharedPreferences : android.content.SharedPreferences {
    override fun getAll(): Map<String, *> = emptyMap<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = false
    override fun edit(): android.content.SharedPreferences.Editor = Editor
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private object Editor : android.content.SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = this
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = this
        override fun clear() = this
        override fun commit(): Boolean = false
        override fun apply() {}
    }
}
