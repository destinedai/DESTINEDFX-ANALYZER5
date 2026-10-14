package com.example.domain.usecase.impl

import com.example.data.AppDatabase
import com.example.data.supabase.SignUpResponse
import com.example.data.supabase.SupabaseAuthClient
import com.example.data.user.User
import com.example.domain.usecase.AuthUseCase
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Authoritative Supabase Authentication implementation.
 * Zero local authentication fallbacks (LOCAL_PROFILE, LOCAL_ONLY removed).
 * Enforces email confirmation verification before allowing app access.
 */
class AuthUseCaseImpl(
    private val db: AppDatabase,
    private val secrets: SecretsManager
) : AuthUseCase {

    private val supabaseClient = SupabaseAuthClient(secrets)

    override suspend fun login(email: String, passwordText: String): Result<User> {
        if (email.isBlank() || passwordText.isBlank())
            return Result.failure(Exception("Email and password are required."))

        val res = supabaseClient.signIn(email, passwordText)
        if (res.isSuccess) {
            val u = res.getOrThrow()
            withContext(Dispatchers.IO) {
                db.userDao().insertUser(u)
            }
            return res
        }
        return Result.failure(res.exceptionOrNull() ?: Exception("AUTHENTICATION_UNAVAILABLE: Unable to sign in via Supabase."))
    }

    override suspend fun register(email: String, username: String, passwordText: String): Result<SignUpResponse> {
        if (email.isBlank() || username.isBlank() || passwordText.isBlank())
            return Result.failure(Exception("All fields are required."))
        if (passwordText.length < 6)
            return Result.failure(Exception("Password must be at least 6 characters."))

        val res = supabaseClient.signUp(email, passwordText, username)
        if (res.isSuccess) {
            val signUpRes = res.getOrThrow()
            if (signUpRes is SignUpResponse.SuccessVerified) {
                withContext(Dispatchers.IO) {
                    db.userDao().insertUser(signUpRes.user)
                }
            }
            return Result.success(signUpRes)
        }
        return Result.failure(res.exceptionOrNull() ?: Exception("AUTHENTICATION_UNAVAILABLE: Registration failed via Supabase."))
    }

    override suspend fun resendVerification(email: String): Result<String> {
        if (email.isBlank()) return Result.failure(Exception("Email is required."))
        return supabaseClient.resendVerificationEmail(email)
    }

    override suspend fun checkVerification(email: String, passwordText: String?): Result<User> {
        if (email.isBlank()) return Result.failure(Exception("Email is required."))
        val res = supabaseClient.checkEmailVerification(email, passwordText)
        if (res.isSuccess) {
            val u = res.getOrThrow()
            withContext(Dispatchers.IO) {
                db.userDao().insertUser(u)
            }
        }
        return res
    }

    override suspend fun validateCurrentSession(): Result<User> {
        val user = supabaseClient.restoreSession()
        return if (user != null) {
            val confirmedAt = secrets.getSupabaseEmailConfirmedAt()
            if (confirmedAt == null) {
                Result.failure(Exception("EMAIL_VERIFICATION_REQUIRED: Account email must be confirmed before automated execution."))
            } else {
                withContext(Dispatchers.IO) {
                    db.userDao().insertUser(user)
                }
                Result.success(user)
            }
        } else {
            Result.failure(Exception("AUTH_REQUIRED: No valid Supabase session. Please sign in."))
        }
    }

    override fun getSupabaseClient(): SupabaseAuthClient = supabaseClient
}
