package com.example.data.supabase

import android.util.Log
import com.example.data.user.User
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SignUpResponse {
    data class SuccessVerified(val user: User) : SignUpResponse()
    data class EmailVerificationRequired(val email: String, val username: String, val uid: String) : SignUpResponse()
}

/**
 * Native REST client for Supabase Auth and PostgREST profiles.
 * Never bundles service_role key; uses only client-safe anon key.
 * Strictly enforces email verification checking on signup, login, and session restore.
 */
class SupabaseAuthClient(
    private val secrets: SecretsManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "SupabaseAuth"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun getUrl(): String {
        return secrets.getSupabaseUrl().trim().trimEnd('/')
    }

    private fun getAnonKey(): String {
        return secrets.getSupabaseAnonKey().trim()
    }

    suspend fun signUp(email: String, passwordText: String, username: String): Result<SignUpResponse> = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        if (url.isBlank() || key.isBlank() || url.contains("your-project")) {
            return@withContext Result.failure(Exception("AUTHENTICATION_UNAVAILABLE: Supabase credentials not configured."))
        }

        try {
            val json = JSONObject().apply {
                put("email", email.trim().lowercase())
                put("password", passwordText)
                put("data", JSONObject().apply { put("username", username.trim()) })
            }
            val req = Request.Builder()
                .url("$url/auth/v1/signup")
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val errObj = try { JSONObject(body) } catch (_: Exception) { null }
                val msg = errObj?.optString("error_description")?.ifBlank { null }
                    ?: errObj?.optString("msg")?.ifBlank { null }
                    ?: errObj?.optString("message") ?: "Sign up failed (HTTP ${resp.code})"
                return@withContext Result.failure(Exception(msg))
            }

            val respJson = JSONObject(body)
            val userObj = respJson.optJSONObject("user") ?: respJson
            val uid = userObj.optString("id")
            val emailConfirmedAt = userObj.optString("email_confirmed_at").takeUnless { it.isBlank() || it == "null" }
            val accessToken = respJson.optString("access_token")
            val refreshToken = respJson.optString("refresh_token")

            if (uid.isNotBlank()) secrets.saveSupabaseUid(uid)

            val isVerified = !emailConfirmedAt.isNullOrBlank()

            if (!isVerified) {
                // Email confirmation required by Supabase
                Log.i(TAG, "Supabase signup successful. Email verification pending for: $email")
                Result.success(SignUpResponse.EmailVerificationRequired(email.trim().lowercase(), username.trim(), uid))
            } else {
                if (accessToken.isNotBlank()) secrets.saveSupabaseAccessToken(accessToken)
                if (refreshToken.isNotBlank()) secrets.saveSupabaseRefreshToken(refreshToken)

                val user = User(
                    email = email.trim().lowercase(),
                    username = username.trim(),
                    passwordHash = "SUPABASE_AUTH",
                    uid = uid
                )
                if (accessToken.isNotBlank()) {
                    upsertProfile(user, accessToken)
                }
                Result.success(SignUpResponse.SuccessVerified(user))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up exception: ${e.message}", e)
            Result.failure(Exception("AUTHENTICATION_UNAVAILABLE: Network error during sign up: ${e.message}"))
        }
    }

    suspend fun signIn(email: String, passwordText: String): Result<User> = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        if (url.isBlank() || key.isBlank() || url.contains("your-project")) {
            return@withContext Result.failure(Exception("AUTHENTICATION_UNAVAILABLE: Supabase credentials not configured."))
        }

        try {
            val json = JSONObject().apply {
                put("email", email.trim().lowercase())
                put("password", passwordText)
            }
            val req = Request.Builder()
                .url("$url/auth/v1/token?grant_type=password")
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val errObj = try { JSONObject(body) } catch (_: Exception) { null }
                val msg = errObj?.optString("error_description")?.ifBlank { null }
                    ?: errObj?.optString("msg")?.ifBlank { null }
                    ?: errObj?.optString("message") ?: "Invalid email or password."
                if (msg.contains("Email not confirmed", ignoreCase = true)) {
                    return@withContext Result.failure(Exception("EMAIL_VERIFICATION_REQUIRED: Please verify your email address before logging in."))
                }
                return@withContext Result.failure(Exception(msg))
            }

            val respJson = JSONObject(body)
            val accessToken = respJson.optString("access_token")
            val refreshToken = respJson.optString("refresh_token")
            val userObj = respJson.optJSONObject("user")
            val uid = userObj?.optString("id") ?: ""
            val emailConfirmedAt = userObj?.optString("email_confirmed_at")?.takeUnless { it.isBlank() || it == "null" }

            if (emailConfirmedAt == null) {
                return@withContext Result.failure(Exception("EMAIL_VERIFICATION_REQUIRED: Email not verified yet. Please check your inbox."))
            }

            val userMeta = userObj?.optJSONObject("user_metadata")
            val username = userMeta?.optString("username", "")?.ifBlank { email.substringBefore("@") } ?: email.substringBefore("@")

            if (accessToken.isNotBlank()) secrets.saveSupabaseAccessToken(accessToken)
            if (refreshToken.isNotBlank()) secrets.saveSupabaseRefreshToken(refreshToken)
            if (uid.isNotBlank()) secrets.saveSupabaseUid(uid)
            secrets.saveSupabaseEmailConfirmedAt(emailConfirmedAt)

            val user = User(
                email = email.trim().lowercase(),
                username = username,
                passwordHash = "SUPABASE_AUTH",
                uid = uid
            )
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in exception: ${e.message}", e)
            Result.failure(Exception("AUTHENTICATION_UNAVAILABLE: Network error during login: ${e.message}"))
        }
    }

    suspend fun restoreSession(): User? = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        val token = secrets.getSupabaseAccessToken()
        if (url.isBlank() || key.isBlank() || token.isBlank() || url.contains("your-project")) return@withContext null

        try {
            val req = Request.Builder()
                .url("$url/auth/v1/user")
                .header("apikey", key)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: "{}"
                val userObj = JSONObject(body)
                val emailConfirmedAt = userObj.optString("email_confirmed_at").takeUnless { it.isBlank() || it == "null" }
                if (emailConfirmedAt == null) {
                    Log.w(TAG, "User email not verified in stored session. Locking out.")
                    return@withContext null
                }

                val uid = userObj.optString("id")
                val email = userObj.optString("email")
                val userMeta = userObj.optJSONObject("user_metadata")
                val username = userMeta?.optString("username", "")?.ifBlank { email.substringBefore("@") } ?: email.substringBefore("@")
                secrets.saveSupabaseEmailConfirmedAt(emailConfirmedAt)
                return@withContext User(email = email, username = username, passwordHash = "SUPABASE_AUTH", uid = uid)
            }

            // If token expired, try refresh
            val refreshToken = secrets.getSupabaseRefreshToken()
            if (refreshToken.isNotBlank()) {
                return@withContext refreshSession(refreshToken)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore session: ${e.message}")
            null
        }
    }

    suspend fun refreshSession(refreshToken: String): User? = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        if (url.isBlank() || key.isBlank() || refreshToken.isBlank()) return@withContext null

        try {
            val json = JSONObject().apply { put("refresh_token", refreshToken) }
            val req = Request.Builder()
                .url("$url/auth/v1/token?grant_type=refresh_token")
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val respJson = JSONObject(resp.body?.string() ?: "{}")
                val userObj = respJson.optJSONObject("user")
                val emailConfirmedAt = userObj?.optString("email_confirmed_at")?.takeUnless { it.isBlank() || it == "null" }
                if (emailConfirmedAt == null) {
                    Log.w(TAG, "Refreshed session user email is unconfirmed.")
                    return@withContext null
                }

                val newAccessToken = respJson.optString("access_token")
                val newRefreshToken = respJson.optString("refresh_token")
                val uid = userObj?.optString("id") ?: ""
                val email = userObj?.optString("email") ?: ""
                val userMeta = userObj?.optJSONObject("user_metadata")
                val username = userMeta?.optString("username", "")?.ifBlank { email.substringBefore("@") } ?: email.substringBefore("@")

                if (newAccessToken.isNotBlank()) secrets.saveSupabaseAccessToken(newAccessToken)
                if (newRefreshToken.isNotBlank()) secrets.saveSupabaseRefreshToken(newRefreshToken)
                if (uid.isNotBlank()) secrets.saveSupabaseUid(uid)
                secrets.saveSupabaseEmailConfirmedAt(emailConfirmedAt)

                return@withContext User(email = email, username = username, passwordHash = "SUPABASE_AUTH", uid = uid)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Refresh session failed: ${e.message}")
            null
        }
    }

    suspend fun resendVerificationEmail(email: String): Result<String> = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        if (url.isBlank() || key.isBlank()) {
            return@withContext Result.failure(Exception("AUTHENTICATION_UNAVAILABLE: Supabase not configured."))
        }

        try {
            val json = JSONObject().apply {
                put("type", "signup")
                put("email", email.trim().lowercase())
            }
            val req = Request.Builder()
                .url("$url/auth/v1/resend")
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                Result.success("Verification email resent successfully to $email")
            } else {
                val body = resp.body?.string() ?: ""
                val errObj = try { JSONObject(body) } catch (_: Exception) { null }
                val msg = errObj?.optString("message") ?: "Failed to resend verification email (HTTP ${resp.code})"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    suspend fun checkEmailVerification(email: String, passwordText: String? = null): Result<User> = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        val token = secrets.getSupabaseAccessToken()

        if (token.isNotBlank()) {
            try {
                val req = Request.Builder()
                    .url("$url/auth/v1/user")
                    .header("apikey", key)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val userObj = JSONObject(resp.body?.string() ?: "{}")
                    val confirmedAt = userObj.optString("email_confirmed_at").takeUnless { it.isBlank() || it == "null" }
                    if (confirmedAt != null) {
                        val uid = userObj.optString("id")
                        val emailStr = userObj.optString("email", email)
                        val userMeta = userObj.optJSONObject("user_metadata")
                        val username = userMeta?.optString("username", "")?.ifBlank { emailStr.substringBefore("@") } ?: emailStr.substringBefore("@")
                        val user = User(email = emailStr, username = username, passwordHash = "SUPABASE_AUTH", uid = uid)
                        return@withContext Result.success(user)
                    }
                }
            } catch (_: Exception) {}
        }

        if (!passwordText.isNullOrBlank()) {
            val loginRes = signIn(email, passwordText)
            if (loginRes.isSuccess) {
                return@withContext loginRes
            }
        }

        val rToken = secrets.getSupabaseRefreshToken()
        if (rToken.isNotBlank()) {
            val refreshed = refreshSession(rToken)
            if (refreshed != null) {
                return@withContext Result.success(refreshed)
            }
        }

        Result.failure(Exception("EMAIL_NOT_VERIFIED: Verification pending. Please verify your email before entering."))
    }

    suspend fun sendPasswordRecovery(email: String): Result<String> = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        if (key.isBlank() || url.contains("your-project")) {
            return@withContext Result.failure(Exception("Supabase credentials not configured."))
        }

        try {
            val json = JSONObject().apply { put("email", email.trim().lowercase()) }
            val req = Request.Builder()
                .url("$url/auth/v1/recover")
                .header("apikey", key)
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                Result.success("Password recovery email dispatched successfully.")
            } else {
                val body = resp.body?.string() ?: ""
                Result.failure(Exception("Recovery request failed: HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}"))
        }
    }

    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        val url = getUrl()
        val key = getAnonKey()
        val token = secrets.getSupabaseAccessToken()
        try {
            if (token.isNotBlank() && url.isNotBlank() && key.isNotBlank()) {
                val req = Request.Builder()
                    .url("$url/auth/v1/logout")
                    .header("apikey", key)
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody(JSON_TYPE))
                    .build()
                client.newCall(req).execute()
            }
        } catch (ignored: Exception) {}

        secrets.saveSupabaseAccessToken("")
        secrets.saveSupabaseRefreshToken("")
        secrets.saveSupabaseUid("")
        secrets.saveSupabaseEmailConfirmedAt("")
    }

    private suspend fun upsertProfile(user: User, accessToken: String) {
        val url = getUrl()
        val key = getAnonKey()
        if (url.isBlank() || key.isBlank() || user.uid.isBlank()) return

        try {
            val json = JSONObject().apply {
                put("id", user.uid)
                put("email", user.email)
                put("username", user.username)
            }
            val req = Request.Builder()
                .url("$url/rest/v1/profiles")
                .header("apikey", key)
                .header("Authorization", "Bearer $accessToken")
                .header("Prefer", "resolution=merge-duplicates")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            client.newCall(req).execute()
        } catch (e: Exception) {
            Log.w(TAG, "Profile upsert notice: ${e.message}")
        }
    }
}
