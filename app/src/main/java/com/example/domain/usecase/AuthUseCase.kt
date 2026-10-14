package com.example.domain.usecase

import com.example.data.supabase.SignUpResponse
import com.example.data.supabase.SupabaseAuthClient
import com.example.data.user.User

interface AuthUseCase {
    suspend fun login(email: String, passwordText: String): Result<User>
    suspend fun register(email: String, username: String, passwordText: String): Result<SignUpResponse>
    suspend fun resendVerification(email: String): Result<String>
    suspend fun checkVerification(email: String, passwordText: String? = null): Result<User>
    suspend fun validateCurrentSession(): Result<User>
    fun getSupabaseClient(): SupabaseAuthClient
}
