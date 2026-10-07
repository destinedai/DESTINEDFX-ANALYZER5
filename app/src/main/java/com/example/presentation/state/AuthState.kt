package com.example.presentation.state

import com.example.data.user.User

data class AuthState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val showRegister: Boolean = false,
    val showSplash: Boolean = false,
    val error: String? = null
)
