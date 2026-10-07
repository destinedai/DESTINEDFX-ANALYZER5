package com.example.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.MyApplication
import com.example.ui.viewmodel.AnalysisViewModel
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.RiskViewModel

object AppContainer {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
            AuthViewModel(
                auth = app.authUseCase
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
            DashboardViewModel(
                market = app.marketDataUseCase,
                mt5 = app.mt5GatewayRepository,
                trades = app.tradeRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
            AnalysisViewModel(
                signal = app.signalGenerationUseCase,
                news = app.newsRepository,
                history = app.analysisRepository
            )
        }
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
            RiskViewModel(
                risk = app.riskRepository,
                secrets = app.secretsManager
            )
        }
    }
}
