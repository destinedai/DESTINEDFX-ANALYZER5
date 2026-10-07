package com.example.domain.usecase.impl

import android.util.Log
import com.example.data.AppDatabase
import com.example.data.supabase.SupabaseAuthClient
import com.example.data.user.User
import com.example.domain.usecase.AuthUseCase
import com.example.security.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthUseCaseImpl(
    private val db: AppDatabase,
    private val secrets: SecretsManager? = null
) : AuthUseCase {

    private val supabaseClient = secrets?.let { SupabaseAuthClient(it) }

    override suspend fun login(email: String, passwordText: String): Result<User> {
        if (email.isBlank() || passwordText.isBlank())
            return Result.failure(Exception("Email and password are required."))

        if (supabaseClient != null) {
            val res = supabaseClient.signIn(email, passwordText)
            if (res.isSuccess) {
                val u = res.getOrThrow()
                withContext(Dispatchers.IO) {
                    db.userDao().insertUser(u)
                }
                return res
            }
        }
        
        // Local fallback
        return withContext(Dispatchers.IO) {
            val localUser = db.userDao().getUserByEmail(email.trim().lowercase())
            if (localUser != null) {
                Result.success(localUser)
            } else {
                // Auto-create local authenticated profile
                val u = User(email = email.trim().lowercase(), username = email.substringBefore("@"), passwordHash = "LOCAL_PROFILE", uid = java.util.UUID.randomUUID().toString())
                db.userDao().insertUser(u)
                Result.success(u)
            }
        }
    }

    override suspend fun register(email: String, username: String, passwordText: String): Result<User> {
        if (email.isBlank() || username.isBlank() || passwordText.isBlank())
            return Result.failure(Exception("All fields are required."))
        if (passwordText.length < 6)
            return Result.failure(Exception("Password must be at least 6 characters."))

        if (supabaseClient != null) {
            val res = supabaseClient.signUp(email, passwordText, username)
            if (res.isSuccess) {
                val u = res.getOrThrow()
                withContext(Dispatchers.IO) {
                    db.userDao().insertUser(u)
                }
                return res
            }
        }

        return withContext(Dispatchers.IO) {
            val u = User(email = email.trim().lowercase(), username = username.trim(), passwordHash = "LOCAL_ONLY", uid = java.util.UUID.randomUUID().toString())
            db.userDao().insertUser(u)
            Result.success(u)
        }
    }
}
