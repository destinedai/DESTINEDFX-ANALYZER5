package com.example.ui

import com.example.data.trade.VerifiedBrokerCandle
import com.example.data.trade.VerifiedBrokerQuote
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class MT5AnalyzerInsight(val trend:String="NEUTRAL",val confidence:Int=0,val reason:String="Waiting for MT5 candle data.",
    val support:Double?=null,val resistance:Double?=null,val stopLoss:Double?=null,val takeProfit:Double?=null)

object MT5AnalyzerMath {
    fun analyze(quote:VerifiedBrokerQuote?, candles:List<VerifiedBrokerCandle>):MT5AnalyzerInsight {
        if(quote==null||quote.source!="MT5_TERMINAL")return MT5AnalyzerInsight(reason="Waiting for MT5 quote.")
        val price=(quote.bid+quote.ask)/2.0
        if(!price.isFinite()||price<=0.0)return MT5AnalyzerInsight(reason="Waiting for valid MT5 quote.")
        val bars=candles.asSequence().filter {
            it.source=="MT5_TERMINAL"&&it.complete&&it.symbol.equals(quote.symbol,true)&&
                it.open.isFinite()&&it.high.isFinite()&&it.low.isFinite()&&it.close.isFinite()&&
                it.open>0.0&&it.low>0.0&&it.high>=maxOf(it.open,it.close,it.low)&&it.low<=minOf(it.open,it.close)
        }.distinctBy{it.timestampMs}.sortedBy{it.timestampMs}.toList()
        val recent=bars.takeLast(20); val support=recent.minOfOrNull{it.low}; val resistance=recent.maxOfOrNull{it.high}
        if(bars.size<21)return MT5AnalyzerInsight(reason="Waiting for enough closed MT5 candles.",support=support,resistance=resistance)
        val sma20=recent.map{it.close}.average()
        val rsi=rsi14(bars)?:return MT5AnalyzerInsight(reason="Waiting for valid MT5 RSI data.",support=support,resistance=resistance)
        val atr=atr14(bars)?:return MT5AnalyzerInsight(reason="Waiting for valid MT5 volatility data.",support=support,resistance=resistance)
        val last=bars.last(); val previous=bars[bars.lastIndex-1]
        val trend=when {price>sma20&&rsi>=55.0&&last.close>=previous.close->"BUY"
            price<sma20&&rsi<=45.0&&last.close<=previous.close->"SELL";else->"NEUTRAL"}
        val confidence=if(trend=="NEUTRAL")0 else {
            val distance=(abs(price-sma20)/atr).coerceIn(0.0,2.0)
            val rsiPower=(abs(rsi-50.0)/25.0).coerceIn(0.0,1.0)
            val candlePower=(abs(last.close-previous.close)/atr).coerceIn(0.0,1.0)
            (52.0+distance*10.0+rsiPower*15.0+candlePower*8.0).roundToInt().coerceIn(50,92)
        }
        val ma=String.format(Locale.US,"%.5f",sma20)
        val reason=when(trend){"BUY"->"Price above MA20 · RSI ${rsi.roundToInt()} · bullish candle"
            "SELL"->"Price below MA20 · RSI ${rsi.roundToInt()} · bearish candle"
            else->"No confirmed direction · MA20 $ma · RSI ${rsi.roundToInt()}"}
        val stop:Double?; val target:Double?
        when(trend) {
            "BUY"->{stop=minOf(support?:price-atr*1.5,price-atr*1.5).takeIf{it>0.0&&it<price}
                val risk=stop?.let{price-it};target=risk?.let{price+it*2.0}?.takeIf{it.isFinite()}}
            "SELL"->{stop=maxOf(resistance?:price+atr*1.5,price+atr*1.5).takeIf{it>price}
                val risk=stop?.let{it-price};target=risk?.let{price-it*2.0}?.takeIf{it.isFinite()&&it>0.0}}
            else->{stop=null;target=null}
        }
        return MT5AnalyzerInsight(trend,confidence,reason,support,resistance,stop,target)
    }
    private fun rsi14(bars:List<VerifiedBrokerCandle>):Double?{
        if(bars.size<15)return null
        val c=bars.takeLast(15).zipWithNext{a,b->b.close-a.close}
        val gain=c.sumOf{it.coerceAtLeast(0.0)}/14.0;val loss=c.sumOf{(-it).coerceAtLeast(0.0)}/14.0
        if(!gain.isFinite()||!loss.isFinite())return null
        if(loss<=0.0)return 100.0
        val rs=gain/loss;return (100.0-100.0/(1.0+rs)).takeIf{it.isFinite()}
    }
    private fun atr14(bars:List<VerifiedBrokerCandle>):Double?{
        if(bars.size<15)return null
        val b=bars.takeLast(15);val ranges=(1 until b.size).map{i->
            val c=b[i];val p=b[i-1].close;maxOf(c.high-c.low,abs(c.high-p),abs(c.low-p))}
        return ranges.average().takeIf{it.isFinite()&&it>0.0}
    }
}
