package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainer
import com.example.ui.DestinedFXApp
import com.example.ui.DestinedFXViewModel
import com.example.ui.theme.DestinedFXTheme
import com.example.ui.viewmodel.RiskViewModel

class MainActivity : FragmentActivity() {
    private lateinit var mainViewModel: DestinedFXViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Lock app when it goes to background
                if (::mainViewModel.isInitialized) {
                    mainViewModel.lockApp()
                }
            }
        })

        setContent {
            val app = (LocalContext.current.applicationContext as MyApplication)
            mainViewModel = viewModel()
            val riskVm: RiskViewModel = viewModel(factory = AppContainer.Factory)
            val themeMode by riskVm.themeMode.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                mainViewModel.checkBiometricAvailability(this@MainActivity)
            }

            DestinedFXTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    DestinedFXApp(
                        viewModel = mainViewModel
                    )
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        mainViewModel.biometricStatus.value = "Auth Error: $errString"
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    mainViewModel.unlockApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    mainViewModel.biometricStatus.value = "Validation failed. Access denied."
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("DestinedFX Secure Access")
            .setSubtitle("Confirm identity to unlock encrypted node")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
