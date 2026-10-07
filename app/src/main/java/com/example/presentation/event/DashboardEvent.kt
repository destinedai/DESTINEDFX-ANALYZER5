package com.example.presentation.event

sealed class DashboardEvent {
    object RefreshDashboard : DashboardEvent()
    data class PositionClicked(val positionId: String) : DashboardEvent()
}
