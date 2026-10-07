package com.example.data.trade

import kotlinx.coroutines.flow.Flow

class TradeRepository(private val tradeDao: TradeDao) {
    val allTrades: Flow<List<TradeEntity>> = tradeDao.getAllTrades()

    suspend fun insertTrade(trade: TradeEntity): Long {
        return tradeDao.insertTrade(trade)
    }

    suspend fun updateTrade(trade: TradeEntity) {
        tradeDao.updateTrade(trade)
    }

    suspend fun deleteTradeById(id: Int) {
        tradeDao.deleteTradeById(id)
    }

    suspend fun clearAllTrades() {
        tradeDao.clearAllTrades()
    }
}
