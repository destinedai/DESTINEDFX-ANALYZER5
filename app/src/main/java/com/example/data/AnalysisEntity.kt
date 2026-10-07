package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analysis_history")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val symbol: String,
    val timeframe: String,
    val strategy: String,
    val dateMs: Long,
    val winRateConfidence: Double,
    val signal: String, // BUY, SELL, NEUTRAL
    val report: String
)
