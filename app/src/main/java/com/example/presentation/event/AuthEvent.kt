package com.example.presentation.event

sealed class AuthEvent {
    data class Login(val email: String, val passwordText: String) : AuthEvent()
    data class Register(val email: String, val username: String, val passwordText: String) : AuthEvent()
    object Logout : AuthEvent()
    object ToggleRegisterScreen : AuthEvent()
}
