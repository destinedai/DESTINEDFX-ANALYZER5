package com.example.domain.model

data class TradeJournal(
    val id: String,
    val positionId: String,
    val date: Long,
    val asset: String,
    val pnl: Double,
    val setupNotes: String,
    val emotions: String,
    val mistakes: String
)
