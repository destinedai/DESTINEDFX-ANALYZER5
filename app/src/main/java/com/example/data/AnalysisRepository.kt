package com.example.data

import kotlinx.coroutines.flow.Flow

class AnalysisRepository(private val analysisDao: AnalysisDao) {
    val allHistory: Flow<List<AnalysisEntity>> = analysisDao.getAllHistory()

    suspend fun insertAnalysis(analysis: AnalysisEntity) {
        analysisDao.insertAnalysis(analysis)
    }

    suspend fun clearHistory() {
        analysisDao.clearHistory()
    }
}
