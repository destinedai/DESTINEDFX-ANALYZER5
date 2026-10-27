package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Institutional Keystore-backed secrets manager.
 * Stores Supabase, Broker (MT5, OANDA), and Risk Configuration.
 * All values stored in Android Keystore via EncryptedSharedPreferences.
 */
class SecretsManager(private val ctx: Context) {

    companion object {
        private const val PREFS   = "destinedfx_secrets"
        private const val TG_TOK  = "telegram_bot_token"
        private const val TG_CHAT = "telegram_chat_id"
        private const val WA_KEY  = "whatsapp_api_key"
        private const val WA_PHN  = "whatsapp_phone"
        private const val MT5_PW  = "mt5_broker_password"
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
        private const val DERIV_API_TOKEN = "deriv_api_token"
        private const val DERIV_APP_ID = "deriv_app_id"
        private const val DERIV_ACCOUNT_MODE = "deriv_account_mode"
        private const val TG_STATUS = "telegram_connection_status"
        private const val TG_NOTIF_ENABLED = "telegram_notifications_enabled"
    }

    val isSecureStorageAvailable: Boolean
    private val prefs: android.content.SharedPreferences

    init {
        var tempSecure = false
        prefs = try {
            val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(
                ctx, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            tempSecure = true
            p
        } catch (e: Exception) {
            android.util.Log.e("SecretsManager", "KEYSTORE UNAVAILABLE - falling back to memory-only storage to prevent plaintext disk exposure", e)
            tempSecure = false
            InMemorySharedPreferences()
        }
        isSecureStorageAvailable = tempSecure
    }


    fun saveTelegramToken(v: String)    = prefs.edit().putString(TG_TOK,  v).apply()
    fun saveTelegramChatId(v: String)   = prefs.edit().putString(TG_CHAT, v).apply()
    fun saveWhatsAppApiKey(v: String)   = prefs.edit().putString(WA_KEY,  v).apply()
    fun saveWhatsAppPhone(v: String)    = prefs.edit().putString(WA_PHN,  v).apply()
    fun saveMT5Password(v: String)      = prefs.edit().putString(MT5_PW,  v).apply()
    fun saveMT5Login(v: String)         = prefs.edit().putString(MT5_LOGIN, v).apply()
    fun saveMT5InvestorPassword(v: String) = prefs.edit().putString(MT5_INVESTOR_PW, v).apply()
    fun saveMT5AccountNumber(v: String) = prefs.edit().putString(MT5_AC,  v).apply()
    fun saveMT5BrokerServer(v: String)  = prefs.edit().putString(MT5_SV,  v).apply()
    fun saveMT5AccountBalance(v: Double) = prefs.edit().putString(MT5_BALANCE, v.toString()).apply()
    fun saveMT5AccountType(v: String)   = prefs.edit().putString(MT5_ACCOUNT_TYPE, v).apply()
    fun saveMT5Leverage(v: Int)         = prefs.edit().putInt(MT5_LEVERAGE, v).apply()
    fun saveMT5SessionStatus(v: String) = prefs.edit().putString(MT5_SESSION_STATUS, v).apply()
    fun saveMT5Currency(v: String)      = prefs.edit().putString(MT5_CURRENCY, v).apply()
    fun saveMT5LastLoginMs(v: Long)     = prefs.edit().putLong(MT5_LAST_LOGIN_MS, v).apply()
    fun saveOandaApiKey(v: String)      = prefs.edit().putString(OANDA_KEY, v).apply()
    fun saveOandaAccountId(v: String)   = prefs.edit().putString(OANDA_ACC, v).apply()
    fun saveConfiguredEquity(v: Double) = prefs.edit().putString(CONFIGURED_EQUITY, v.toString()).apply()
    fun saveSelectedStrategy(v: String) = prefs.edit().putString(SELECTED_STRATEGY, v).apply()
    fun saveDerivApiToken(v: String)    = prefs.edit().putString(DERIV_API_TOKEN, v).apply()
    fun saveDerivAppId(v: String)       = prefs.edit().putString(DERIV_APP_ID, v).apply()
    fun saveDerivAccountMode(v: String) = prefs.edit().putString(DERIV_ACCOUNT_MODE, v.trim().lowercase().takeIf { it == "demo" || it == "real" } ?: "demo").apply()
    fun saveTelegramConnectionStatus(status: String) = prefs.edit().putString(TG_STATUS, status).apply()
    fun saveTelegramNotificationsEnabled(enabled: Boolean) = prefs.edit().putBoolean(TG_NOTIF_ENABLED, enabled).apply()
    
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

    fun getDerivApiToken(): String     = prefs.getString(DERIV_API_TOKEN, "") ?: ""
    // New Deriv REST/Options API rejects legacy App ID 1089; don't prefill a stale stored default.
    fun getDerivAppId(): String = (prefs.getString(DERIV_APP_ID, "") ?: "")
        .trim()
        .takeUnless { it.isBlank() || it == "1089" } ?: ""
    fun getDerivAccountMode(): String  = (prefs.getString(DERIV_ACCOUNT_MODE, "demo") ?: "demo").trim().lowercase().takeIf { it == "demo" || it == "real" } ?: "demo"
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
    fun getMT5Leverage(): Int          = prefs.getInt(MT5_LEVERAGE, 500)
    fun getMT5SessionStatus(): String  = prefs.getString(MT5_SESSION_STATUS, "DISCONNECTED") ?: "DISCONNECTED"
    fun getMT5Currency(): String       = prefs.getString(MT5_CURRENCY, "USD") ?: "USD"
    fun getMT5LastLoginMs(): Long      = prefs.getLong(MT5_LAST_LOGIN_MS, 0L)
    fun getOandaApiKey(): String       = prefs.getString(OANDA_KEY, "") ?: ""
    fun getOandaAccountId(): String    = prefs.getString(OANDA_ACC, "") ?: ""
    fun getConfiguredEquity(): Double  = prefs.getString(CONFIGURED_EQUITY, "0.0")?.toDoubleOrNull() ?: 0.0
    fun getSelectedStrategy(): String  = prefs.getString(SELECTED_STRATEGY, "smc-confluence-1.0.0") ?: "smc-confluence-1.0.0"


    fun hasCredentials() = getMT5AccountNumber().isNotBlank()
    fun clearAll()       = prefs.edit().clear().apply()

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

private class InMemorySharedPreferences : android.content.SharedPreferences {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    override fun getAll(): Map<String, *> = map
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : android.content.SharedPreferences.Editor {
        private val temp = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor = apply { temp[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor = apply { temp[key] = values }
        override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor = apply { temp[key] = value }
        override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor = apply { temp[key] = value }
        override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor = apply { temp[key] = value }
        override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor = apply { temp[key] = value }
        override fun remove(key: String): android.content.SharedPreferences.Editor = apply { temp[key] = null }
        override fun clear(): android.content.SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) map.clear()
            for ((k, v) in temp) {
                if (v == null) map.remove(k) else map[k] = v
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
