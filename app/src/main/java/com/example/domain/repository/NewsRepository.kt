package com.example.domain.repository

data class NewsEvent(
    val title: String,
    val currency: String,
    val impact: String,           // "High" | "Medium" | "Low"
    val dateTimeUtcMs: Long,
    val forecast: String?,
    val previous: String?
)

interface NewsRepository {
    /** Last verified calendar result, or null when never loaded. null/Failed => NEWS STATUS UNKNOWN (never "no news"). */
    suspend fun calendar(): com.destinedfx.core.news.CalendarResult? = null
    suspend fun fetchUpcomingEvents(): List<NewsEvent>
    suspend fun isHighImpactWindowActive(currencies: List<String>, bufferMinutes: Int = 30): Boolean
    suspend fun getNextHighImpactEvent(currency: String): NewsEvent?
}
