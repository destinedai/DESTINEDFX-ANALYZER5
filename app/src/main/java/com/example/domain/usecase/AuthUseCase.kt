package com.example.domain.usecase

import com.example.data.user.User

interface AuthUseCase {
    suspend fun login(email: String, passwordText: String): Result<User>
    suspend fun register(email: String, username: String, passwordText: String): Result<User>
}
