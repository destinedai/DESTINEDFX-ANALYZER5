package com.example.domain.engine

import com.example.domain.model.Candle
import com.example.domain.model.OrderBlock

interface OrderBlockDetector {
    suspend fun detectOrderBlocks(candles: List<Candle>): List<OrderBlock>
}
