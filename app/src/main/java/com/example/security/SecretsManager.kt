package com.example.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Institutional Keystore-backed secrets manager.
 * Stores Supabase, Gemini, Broker (MT5, OANDA), TwelveData, and Risk Configuration.
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
        private const val MT5_AC  = "mt5_account_number"
        private const val MT5_SV  = "mt5_broker_server"
        private const val META    = "metaapi_token"
        private const val AV_KEY  = "alpha_vantage_key"
        private const val FMP_KEY = "fmp_api_key"
        private const val SB_URL  = "supabase_url"
        private const val SB_KEY  = "supabase_anon_key"
        private const val SB_ACCESS_TOKEN = "supabase_access_token"
        private const val SB_REFRESH_TOKEN = "supabase_refresh_token"
        private const val SB_UID = "supabase_uid"
        private const val SB_EMAIL_CONFIRMED_AT = "supabase_email_confirmed_at"
        private const val GEMINI_KEY = "gemini_api_key"
        private const val OANDA_KEY = "oanda_api_key"
        private const val OANDA_ACC = "oanda_account_id"
        private const val TD_KEY    = "twelve_data_api_key"
        private const val CONFIGURED_EQUITY = "configured_equity"
        private const val SELECTED_STRATEGY = "selected_strategy_id"
        private const val BROKER_SYMBOL_OVERRIDES = "broker_symbol_overrides"
        private const val DERIV_API_TOKEN = "deriv_api_token"
        private const val DERIV_APP_ID = "deriv_app_id"
    }

    private val prefs: android.content.SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(PREFS)
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            }
        } catch (ignored: Exception) {}
        try {
            val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (fallbackEx: Exception) {
            android.util.Log.e("SecretsManager", "KEYSTORE UNAVAILABLE", fallbackEx)
            InMemorySharedPreferences()
        }
    }

    fun saveTelegramToken(v: String)    = prefs.edit().putString(TG_TOK,  v).apply()
    fun saveTelegramChatId(v: String)   = prefs.edit().putString(TG_CHAT, v).apply()
    fun saveWhatsAppApiKey(v: String)   = prefs.edit().putString(WA_KEY,  v).apply()
    fun saveWhatsAppPhone(v: String)    = prefs.edit().putString(WA_PHN,  v).apply()
    fun saveMT5Password(v: String)      = prefs.edit().putString(MT5_PW,  v).apply()
    fun saveMT5AccountNumber(v: String) = prefs.edit().putString(MT5_AC,  v).apply()
    fun saveMT5BrokerServer(v: String)  = prefs.edit().putString(MT5_SV,  v).apply()
    fun saveMetaApiToken(v: String)     = prefs.edit().putString(META,    v).apply()
    fun saveAlphaVantageKey(v: String)  = prefs.edit().putString(AV_KEY,  v).apply()
    fun saveFmpKey(v: String)           = prefs.edit().putString(FMP_KEY, v).apply()
    fun saveSupabaseUrl(v: String)      = prefs.edit().putString(SB_URL,  v).apply()
    fun saveSupabaseAnonKey(v: String)  = prefs.edit().putString(SB_KEY,  v).apply()
    fun saveSupabaseAccessToken(v: String) = prefs.edit().putString(SB_ACCESS_TOKEN, v).apply()
    fun saveSupabaseRefreshToken(v: String) = prefs.edit().putString(SB_REFRESH_TOKEN, v).apply()
    fun saveSupabaseUid(v: String)      = prefs.edit().putString(SB_UID, v).apply()
    fun saveSupabaseEmailConfirmedAt(v: String) = prefs.edit().putString(SB_EMAIL_CONFIRMED_AT, v).apply()
    fun saveGeminiApiKey(v: String)     = prefs.edit().putString(GEMINI_KEY, v).apply()
    fun saveOandaApiKey(v: String)      = prefs.edit().putString(OANDA_KEY, v).apply()
    fun saveOandaAccountId(v: String)   = prefs.edit().putString(OANDA_ACC, v).apply()
    fun saveTwelveDataApiKey(v: String) = prefs.edit().putString(TD_KEY,    v).apply()
    fun saveConfiguredEquity(v: Double) = prefs.edit().putString(CONFIGURED_EQUITY, v.toString()).apply()
    fun saveSelectedStrategy(v: String) = prefs.edit().putString(SELECTED_STRATEGY, v).apply()
    fun saveDerivApiToken(v: String)    = prefs.edit().putString(DERIV_API_TOKEN, v).apply()
    fun saveDerivAppId(v: String)       = prefs.edit().putString(DERIV_APP_ID, v).apply()

    fun getDerivApiToken(): String     = prefs.getString(DERIV_API_TOKEN, "") ?: ""
    fun getDerivAppId(): String        = prefs.getString(DERIV_APP_ID, "1089") ?: "1089"

    fun getTelegramToken(): String     = prefs.getString(TG_TOK,  "") ?: ""
    fun getTelegramChatId(): String    = prefs.getString(TG_CHAT, "") ?: ""
    fun getWhatsAppApiKey(): String    = prefs.getString(WA_KEY,  "") ?: ""
    fun getWhatsAppPhone(): String     = prefs.getString(WA_PHN,  "") ?: ""
    fun getMT5Password(): String       = prefs.getString(MT5_PW,  "") ?: ""
    fun getMT5AccountNumber(): String  = prefs.getString(MT5_AC,  "") ?: ""
    fun getMT5BrokerServer(): String   = prefs.getString(MT5_SV,  "") ?: ""
    fun getMetaApiToken(): String      = prefs.getString(META,    "") ?: ""
    fun getAlphaVantageKey(): String   = prefs.getString(AV_KEY, "") ?: ""
    fun getFmpKey(): String            = prefs.getString(FMP_KEY, "") ?: ""
    fun getSupabaseUrl(): String {
        val saved = prefs.getString(SB_URL, "") ?: ""
        if (saved.isNotBlank() && saved != "default" && !saved.contains("your-project")) return saved
        return try {
            val bc = com.example.BuildConfig.SUPABASE_URL
            if (bc.isNotBlank() && bc != "default" && !bc.contains("your-project")) bc else "https://wdqweqzpoiegtvomvono.supabase.co"
        } catch (_: Exception) {
            "https://wdqweqzpoiegtvomvono.supabase.co"
        }
    }

    fun getSupabaseAnonKey(): String {
        val saved = prefs.getString(SB_KEY, "") ?: ""
        if (saved.isNotBlank() && saved != "default") return saved
        return try {
            val bc = com.example.BuildConfig.SUPABASE_ANON_KEY
            if (bc.isNotBlank() && bc != "default") bc else ""
        } catch (_: Exception) {
            ""
        }
    }
    fun getSupabaseAccessToken(): String = prefs.getString(SB_ACCESS_TOKEN, "") ?: ""
    fun getSupabaseRefreshToken(): String = prefs.getString(SB_REFRESH_TOKEN, "") ?: ""
    fun getSupabaseUid(): String       = prefs.getString(SB_UID, "") ?: ""
    fun getSupabaseEmailConfirmedAt(): String? = prefs.getString(SB_EMAIL_CONFIRMED_AT, "")?.takeUnless { it.isBlank() || it == "null" }
    fun getGeminiApiKey(): String      = prefs.getString(GEMINI_KEY, "") ?: ""
    fun getOandaApiKey(): String       = prefs.getString(OANDA_KEY, "") ?: ""
    fun getOandaAccountId(): String    = prefs.getString(OANDA_ACC, "") ?: ""
    fun getTwelveDataApiKey(): String  = prefs.getString(TD_KEY, "") ?: ""
    fun getConfiguredEquity(): Double  = prefs.getString(CONFIGURED_EQUITY, "10.0")?.toDoubleOrNull() ?: 10.0
    fun getSelectedStrategy(): String  = prefs.getString(SELECTED_STRATEGY, "smc-confluence-1.0.0") ?: "smc-confluence-1.0.0"

    fun hasCredentials() = getMetaApiToken().isNotBlank() || getMT5AccountNumber().isNotBlank()
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
