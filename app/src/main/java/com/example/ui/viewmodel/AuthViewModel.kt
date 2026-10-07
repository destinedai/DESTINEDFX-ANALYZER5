package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.user.User
import com.example.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val user: User? = null, val isLoading: Boolean = false,
    val error: String? = null, val showRegister: Boolean = false
)

class AuthViewModel(private val auth: AuthUseCase) : ViewModel() {

    private val _s = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _s

    fun login(email: String, password: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading=true, error=null)
        auth.login(email, password).fold(
            onSuccess = { _s.value = _s.value.copy(isLoading=false, user=it) },
            onFailure = { _s.value = _s.value.copy(isLoading=false, error=it.message) }
        )
    }

    fun register(email: String, username: String, password: String) = viewModelScope.launch(Dispatchers.IO) {
        _s.value = _s.value.copy(isLoading=true, error=null)
        auth.register(email, username, password).fold(
            onSuccess = { _s.value = _s.value.copy(isLoading=false, showRegister=false) },
            onFailure = { _s.value = _s.value.copy(isLoading=false, error=it.message) }
        )
    }

    fun logout()         { _s.value = AuthUiState() }
    fun toggleRegister() { _s.value = _s.value.copy(showRegister=!_s.value.showRegister, error=null) }
    fun clearError()     { _s.value = _s.value.copy(error=null) }
}
