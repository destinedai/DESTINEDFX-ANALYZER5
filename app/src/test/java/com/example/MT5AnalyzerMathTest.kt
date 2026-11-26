package com.example

import com.example.data.trade.VerifiedBrokerCandle
import com.example.data.trade.VerifiedBrokerQuote
import com.example.ui.MT5AnalyzerMath
import com.example.ui.analyzerTradingViewTicker
import com.example.ui.tradingViewIntervalForMt5
import com.example.ui.tradingViewSymbolForMt5
import org.junit.Assert.*
import org.junit.Test

class MT5AnalyzerMathTest{
    private fun bars()=(0 until 30).map{i->
        val close=1.10+i*0.001+if(i%2==0)0.0002 else -0.0001
        VerifiedBrokerCandle("EURUSD.a","M15",1_800_000_000_000L+i*900_000L,close-0.0001,close+0.0004,close-0.0004,close,100.0)
    }
    private val quote=VerifiedBrokerQuote("EURUSD.a",1.1300,1.1302,5,1_800_000_000_000L,0)
    @Test fun missingMT5QuoteNeverCreatesDirectionalSignalOrTradeLevels(){
        val result=MT5AnalyzerMath.analyze(null,bars());assertEquals("NEUTRAL",result.trend);assertNull(result.stopLoss);assertNull(result.takeProfit)
    }
    @Test fun insufficientMT5BarsRemainNeutral(){
        val result=MT5AnalyzerMath.analyze(quote,bars().take(10));assertEquals("NEUTRAL",result.trend)
        assertNull(result.stopLoss);assertNull(result.takeProfit);assertNotNull(result.support);assertNotNull(result.resistance)
    }
    @Test fun verifiedMT5QuoteAndClosedCandlesCalculateLevels(){
        val result=MT5AnalyzerMath.analyze(quote,bars());assertTrue(result.trend in setOf("BUY","SELL","NEUTRAL"))
        assertTrue(result.confidence in 0..92);assertNotNull(result.support);assertNotNull(result.resistance)
        assertTrue(result.support!!<=result.resistance!!)
    }
    @Test fun TradingViewMapsCurrentMT5SymbolAndTimeframe(){
        assertEquals("FX:EURUSD",tradingViewSymbolForMt5("EURUSD.a"))
        assertEquals("TVC:GOLD",tradingViewSymbolForMt5("XAUUSDm"))
        assertEquals("TVC:GOLD",tradingViewSymbolForMt5("GOLD"))
        assertEquals("60",tradingViewIntervalForMt5("H1"));assertEquals("1D",tradingViewIntervalForMt5("D1"))
    }

    @Test fun unsupportedTradingViewSymbolReturnsNullAndCannotRenderChart() {
        val ticker = analyzerTradingViewTicker("FAKEPAIR")
        assertNull(ticker)
        assertNotEquals("FX:EURUSD", ticker)
        assertFalse(ticker != null)
    }
}
