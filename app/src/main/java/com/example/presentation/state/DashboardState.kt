package com.example.presentation.state

import com.example.domain.model.Position

data class DashboardState(
    val isLoading: Boolean = false,
    val accountBalance: Double = 0.0,
    val totalProfit: Double = 0.0,
    val winRate: Double = 0.0,
    val activePositions: List<Position> = emptyList(),
    val error: String? = null
)
