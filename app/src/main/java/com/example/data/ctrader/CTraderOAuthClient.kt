package com.example.data.ctrader

import android.net.Uri
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Collections

/** OAuth state for the phone-only cTrader connector. Secrets never enter logs or UI state. */
data class CTraderOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMs: Long
)

class CTraderOAuthClient(
    private val secrets: SecretsManager,
    private val http: OkHttpClient = OkHttpClient()
) {
    companion object {
        const val AUTHORIZATION_ENDPOINT = "https://id.ctrader.com/my/settings/openapi/grantingaccess/"
        const val TOKEN_ENDPOINT = "https://openapi.ctrader.com/apps/token"
        const val VIEW_SCOPE = "accounts"
        const val TRADE_SCOPE = "trading"
        private const val AUTH_CODE_MAX_AGE_MS = 60_000L
        private const val MAX_RETRIES = 3
    }

    fun authorizationUri(scope: String): Uri {
        require(scope == VIEW_SCOPE || scope == TRADE_SCOPE) { "Unsupported cTrader scope" }
        val clientId = secrets.getCTraderClientId().trim()
        val redirectUri = secrets.getCTraderRedirectUri().trim()
        require(clientId.isNotBlank() && redirectUri.isNotBlank()) { "cTrader application details are not configured" }
        val finalUrl = AUTHORIZATION_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scope)
            .addQueryParameter("product", "web")
            .build()
        return Uri.parse(finalUrl.toString())
    }

    suspend fun exchangeAuthorizationCode(code: String, receivedAtMs: Long = System.currentTimeMillis()): Result<CTraderOAuthTokens> =
        withContext(Dispatchers.IO) {
            if (code.isBlank()) return@withContext Result.failure(IllegalArgumentException("cTrader authorization code is empty"))
            exchange(
                grantType = "authorization_code",
                code = code,
                redirectUri = secrets.getCTraderRedirectUri().trim(),
                receivedAtMs = receivedAtMs
            )
        }

    suspend fun refresh(): Result<CTraderOAuthTokens> = withContext(Dispatchers.IO) {
        val refreshToken = secrets.getCTraderRefreshToken().trim()
        if (refreshToken.isBlank()) return@withContext Result.failure(IllegalStateException("cTrader refresh token is unavailable"))
        exchange(grantType = "refresh_token", refreshToken = refreshToken, receivedAtMs = System.currentTimeMillis())
    }

    fun accessTokenIsFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        secrets.getCTraderAccessToken().isNotBlank() && secrets.getCTraderAccessExpiresAtMs() - nowMs > 60_000L

    private fun exchange(
        grantType: String,
        code: String? = null,
        refreshToken: String? = null,
        redirectUri: String? = null,
        receivedAtMs: Long
    ): Result<CTraderOAuthTokens> {
        if (grantType == "authorization_code" &&
            (receivedAtMs <= 0L || kotlin.math.abs(System.currentTimeMillis() - receivedAtMs) > AUTH_CODE_MAX_AGE_MS)
        ) {
            return Result.failure(IllegalArgumentException("cTrader authorization response time is invalid"))
        }
        val clientId = secrets.getCTraderClientId().trim()
        val clientSecret = secrets.getCTraderClientSecret().trim()
        if (clientId.isBlank() || clientSecret.isBlank()) return Result.failure(IllegalStateException("cTrader application credentials are unavailable"))
        val url = TOKEN_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", grantType)
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("client_secret", clientSecret)
            .apply {
                code?.let { addQueryParameter("code", it) }
                refreshToken?.let { addQueryParameter("refresh_token", it) }
                redirectUri?.let { addQueryParameter("redirect_uri", it) }
            }.build()
        val requestBuilder = Request.Builder().url(url).header("Accept", "application/json")
        val request = if (grantType == "refresh_token") requestBuilder.post(okhttp3.RequestBody.create(null, ByteArray(0))).build()
        else requestBuilder.get().build()
        return try {
            var attempt = 0
            while (true) {
                val response = http.newCall(request).execute()
                if (response.code == 429 && attempt++ < MAX_RETRIES) {
                    response.close()
                    Thread.sleep(250L shl (attempt - 1))
                    continue
                }
                response.use {
                val body = it.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return Result.failure(IOException("cTrader token response was not valid JSON"))
                val error = json.optString("errorCode").takeIf { it.isNotBlank() }
                if (!response.isSuccessful || error != null) {
                    val message = if (response.code == 401 || response.code == 403 || error != null) "cTrader authorization needs regrant" else "cTrader token exchange was rejected"
                    return Result.failure(IOException(message))
                }
                val access = json.optString("accessToken")
                val refresh = json.optString("refreshToken")
                val expiresIn = json.optLong("expiresIn", 0L)
                if (access.isBlank() || refresh.isBlank() || expiresIn <= 0L) return Result.failure(IOException("cTrader token response was incomplete"))
                val tokens = CTraderOAuthTokens(access, refresh, System.currentTimeMillis() + expiresIn * 1_000L)
                val saved = if (secrets.getCTraderAccessToken().isBlank()) {
                    secrets.saveCTraderOAuth(clientId, clientSecret, redirectUri.orEmpty(), tokens.accessToken, tokens.refreshToken, tokens.accessExpiresAtMs)
                } else {
                    secrets.saveCTraderRefreshedToken(tokens.accessToken, tokens.refreshToken, tokens.accessExpiresAtMs)
                }
                if (!saved) Result.failure(IllegalStateException("Secure storage unavailable; cTrader credentials were not saved")) else Result.success(tokens)
                }
            }
        } catch (_: Exception) {
            Result.failure(IOException("cTrader token exchange failed"))
        }
    }

    private val consumedCodes = Collections.synchronizedSet(mutableSetOf<String>())

    fun isCallbackRedirectAllowed(uri: Uri): Boolean {
        val configured = Uri.parse(secrets.getCTraderRedirectUri().trim())
        return configured.scheme.isNullOrBlank().not() && configured.host.isNullOrBlank().not() &&
            configured.path.isNullOrBlank().not() && configured.query == null && configured.fragment == null &&
            uri.scheme == configured.scheme && uri.host == configured.host && uri.path == configured.path && uri.fragment == null
    }

    fun consumeAuthorizationCallback(uri: Uri, receivedAtMs: Long = System.currentTimeMillis()): Result<String> {
        if (!isCallbackRedirectAllowed(uri)) return Result.failure(IllegalArgumentException("cTrader callback redirect does not match the registered URI"))
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) return Result.failure(IOException("cTrader authorization returned an error"))
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("cTrader callback did not contain an authorization code"))
        if (receivedAtMs <= 0L || kotlin.math.abs(System.currentTimeMillis() - receivedAtMs) > AUTH_CODE_MAX_AGE_MS) {
            return Result.failure(IllegalArgumentException("cTrader authorization response time is invalid"))
        }
        if (!consumedCodes.add(code)) return Result.failure(IllegalArgumentException("cTrader authorization code was already used"))
        return Result.success(code)
    }
}
