package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.example.ui.theme.*
import com.example.ui.viewmodel.RiskViewModel

class MainActivity : FragmentActivity() {
    private var mainViewModel: DestinedFXViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = (LocalContext.current.applicationContext as MyApplication)
            val initStatus by app.initStatus.collectAsStateWithLifecycle()
            var timedOutToReady by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                delay(1200L)
                timedOutToReady = true
            }

            val isReady = initStatus is MyApplication.InitState.Ready || (timedOutToReady && initStatus !is MyApplication.InitState.Failed)

            if (isReady) {
                val mainVm: DestinedFXViewModel = viewModel(factory = AppContainer.Factory)
                mainViewModel = mainVm
                val riskVm: RiskViewModel = viewModel(factory = AppContainer.Factory)
                val themeMode by riskVm.themeMode.collectAsStateWithLifecycle()

                DestinedFXTheme(themeMode = themeMode) {
                    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        DestinedFXApp(
                            viewModel = mainVm
                        )
                    }
                }
            } else when (val state = initStatus) {
                is MyApplication.InitState.Failed -> {
                    DestinedFXTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            color = DarkBackground
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Initialization Error",
                                        tint = LossRed,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Text(
                                        text = "Startup Diagnostic Notice",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = state.error.localizedMessage ?: state.error.javaClass.simpleName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF90A4AE),
                                        textAlign = TextAlign.Center
                                    )
                                    Button(
                                        onClick = { app.retryInitialization() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan
                                        )
                                    ) {
                                        Text(
                                            text = "Retry Initialization",
                                            color = DarkBackground,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    DestinedFXTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            color = DarkBackground
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = ElectricCyan
                                    )
                                    Text(
                                        text = "Initializing DestinedFX Engine...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF90A4AE)
                                    )
                                }
                            }
                        }
                    }
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
                        mainViewModel?.biometricStatus?.value = "Auth Error: $errString"
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    mainViewModel?.unlockApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    mainViewModel?.biometricStatus?.value = "Validation failed. Access denied."
                }
            })

        try {
            val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("DestinedFX Secure Access")
                .setSubtitle("Confirm identity to unlock encrypted node")

            if (android.os.Build.VERSION.SDK_INT >= 30) {
                promptInfoBuilder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                promptInfoBuilder.setDeviceCredentialAllowed(true)
            }

            biometricPrompt.authenticate(promptInfoBuilder.build())
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Biometric authenticate failed: ${e.message}")
            mainViewModel?.biometricStatus?.value = "Biometric prompt error: ${e.message}"
        }
    }
}
