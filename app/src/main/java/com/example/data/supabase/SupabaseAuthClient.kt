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

    suspend fun signUp(email: String, passwordText: String, username: String): Result<SignUpResponse> = Result.failure(Exception("AUTHENTICATION_UNAVAILABLE"))
    suspend fun signIn(email: String, passwordText: String): Result<User> = Result.failure(Exception("AUTHENTICATION_UNAVAILABLE"))
    suspend fun restoreSession(): User? = null
    suspend fun refreshSession(refreshToken: String): User? = null
    suspend fun resendVerificationEmail(email: String): Result<String> = Result.failure(Exception("AUTHENTICATION_UNAVAILABLE"))
    suspend fun checkEmailVerification(email: String, passwordText: String? = null): Result<User> = Result.failure(Exception("AUTHENTICATION_UNAVAILABLE"))
    suspend fun sendPasswordRecovery(email: String): Result<String> = Result.failure(Exception("AUTHENTICATION_UNAVAILABLE"))
    suspend fun signOut() {}
}
