package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.supabase.SignUpResponse
import com.example.data.user.User
import com.example.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val showRegister: Boolean = false,
    val verificationPendingEmail: String? = null,
    val infoMessage: String? = null
)

class AuthViewModel(private val auth: AuthUseCase) : ViewModel() {

    private val _s = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _s

    fun login(email: String, passwordText: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading = true, error = null, infoMessage = null)
        auth.login(email, passwordText).fold(
            onSuccess = { _s.value = _s.value.copy(isLoading = false, user = it, verificationPendingEmail = null) },
            onFailure = {
                val msg = it.message ?: "Authentication failed"
                if (msg.contains("EMAIL_VERIFICATION_REQUIRED", ignoreCase = true)) {
                    _s.value = _s.value.copy(
                        isLoading = false,
                        error = msg,
                        verificationPendingEmail = email.trim().lowercase()
                    )
                } else {
                    _s.value = _s.value.copy(isLoading = false, error = msg)
                }
            }
        )
    }

    fun register(email: String, username: String, passwordText: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading = true, error = null, infoMessage = null)
        auth.register(email, username, passwordText).fold(
            onSuccess = { res ->
                when (res) {
                    is SignUpResponse.SuccessVerified -> {
                        _s.value = _s.value.copy(isLoading = false, user = res.user, showRegister = false)
                    }
                    is SignUpResponse.EmailVerificationRequired -> {
                        _s.value = _s.value.copy(
                            isLoading = false,
                            showRegister = false,
                            verificationPendingEmail = res.email,
                            infoMessage = "Verification email sent to ${res.email}. Please verify your email before entering."
                        )
                    }
                }
            },
            onFailure = { _s.value = _s.value.copy(isLoading = false, error = it.message) }
        )
    }

    fun resendVerification(email: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading = true, error = null, infoMessage = null)
        auth.resendVerification(email).fold(
            onSuccess = { _s.value = _s.value.copy(isLoading = false, infoMessage = it) },
            onFailure = { _s.value = _s.value.copy(isLoading = false, error = it.message) }
        )
    }

    fun checkVerification(email: String, passwordText: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading = true, error = null, infoMessage = null)
        auth.checkVerification(email, passwordText).fold(
            onSuccess = { _s.value = _s.value.copy(isLoading = false, user = it, verificationPendingEmail = null) },
            onFailure = { _s.value = _s.value.copy(isLoading = false, error = it.message) }
        )
    }

    fun backToSignIn() {
        _s.value = _s.value.copy(verificationPendingEmail = null, showRegister = false, error = null, infoMessage = null)
    }

    fun logout() {
        _s.value = AuthUiState()
    }

    fun toggleRegister() {
        _s.value = _s.value.copy(showRegister = !_s.value.showRegister, error = null, infoMessage = null)
    }

    fun clearError() {
        _s.value = _s.value.copy(error = null, infoMessage = null)
    }
}
