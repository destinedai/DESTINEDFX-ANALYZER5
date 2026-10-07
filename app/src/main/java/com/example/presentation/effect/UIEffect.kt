package com.example.presentation.effect

sealed class UIEffect {
    data class ShowToast(val message: String) : UIEffect()
    data class ShowSnackbar(val message: String) : UIEffect()
    data class NavigateTo(val route: String) : UIEffect()
    object NavigateBack : UIEffect()
}
