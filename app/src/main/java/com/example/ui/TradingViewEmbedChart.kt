package com.example.ui

import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Locale

internal fun tradingViewSymbolForMt5(mt5Symbol:String):String?{
    val raw=mt5Symbol.trim().uppercase(Locale.ROOT).substringAfterLast(':')
    val compact=raw.filter{it in 'A'..'Z'||it in '0'..'9'}
    if(compact.isBlank())return null
    val pair=compact.take(6)
    val currencies=setOf("USD","EUR","GBP","JPY","CHF","CAD","AUD","NZD","SEK","NOK","DKK","SGD","HKD","MXN","ZAR","TRY","CNH","PLN","HUF","CZK")
    val isPair=pair.length==6&&(pair.substring(0,3) in currencies||pair.startsWith("XAU")||pair.startsWith("XAG"))&&
        (pair.substring(3,6) in currencies||pair.endsWith("USD"))
    return "OANDA:"+if(isPair)pair else compact
}
internal fun tradingViewIntervalForMt5(timeframe:String):String=when(timeframe.trim().uppercase(Locale.ROOT)){
    "M1"->"1";"M5"->"5";"M15"->"15";"M30"->"30";"H1"->"60";"H4"->"240";"D1"->"1D";"W1"->"1W";else->"15"
}
internal fun tradingViewEmbedUrl(mt5Symbol:String,timeframe:String):String{
    val sym=tradingViewSymbolForMt5(mt5Symbol)?: "OANDA:EURUSD"
    return "https://www.tradingview.com/widgetembed/?frameElementId=destinedfx_tradingview"+
        "&symbol="+URLEncoder.encode(sym,"UTF-8")+"&interval="+tradingViewIntervalForMt5(timeframe)+
        "&hidesidetoolbar=1&hide_top_toolbar=1&symboledit=0&saveimage=0&toolbarbg=171B27"+
        "&theme=dark&style=1&timezone=Etc%2FUTC&withdateranges=0&hideideas=1&locale=en"
}
@Composable
fun TradingViewEmbedChart(mt5Symbol:String?,timeframe:String,modifier:Modifier=Modifier){
    val url=mt5Symbol?.let{tradingViewEmbedUrl(it,timeframe)}
    var retry by remember(mt5Symbol,timeframe){mutableIntStateOf(0)}
    var state by remember{mutableStateOf(EmbedState.LOADING)}
    val key="$url#$retry"
    val setState=rememberUpdatedState<(EmbedState)->Unit>{state=it}
    val web=remember{mutableStateOf<WebView?>(null)}
    LaunchedEffect(key){
        state=EmbedState.LOADING
        if(url!=null){delay(25_000L);if(state==EmbedState.LOADING)state=EmbedState.FAILED}
    }
    DisposableEffect(Unit){
        onDispose{web.value?.let{runCatching{it.stopLoading()};runCatching{it.removeAllViews()};runCatching{it.destroy()}};web.value=null}
    }
    Box(modifier.background(Color(0xFF171B27))){
        if(url==null)Text("Waiting for MT5...",color=Color(0xFF9AA8BA),fontSize=12.sp,modifier=Modifier.align(Alignment.Center))
        else{
            AndroidView(Modifier.fillMaxSize(),factory={context->
                WebView(context).apply{
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=true
                    settings.databaseEnabled=true
                    settings.cacheMode=android.webkit.WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically=true
                    settings.blockNetworkLoads=false
                    settings.allowFileAccess=false
                    settings.allowContentAccess=false
                    settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    overScrollMode=View.OVER_SCROLL_NEVER
                    setBackgroundColor(AndroidColor.rgb(23,27,39))
                    webChromeClient=WebChromeClient()
                    webViewClient=object:WebViewClient(){
                        override fun onPageStarted(view:WebView?,pageUrl:String?,favicon:android.graphics.Bitmap?){
                            super.onPageStarted(view,pageUrl,favicon);setState.value(EmbedState.LOADING)
                        }
                        override fun onPageFinished(view:WebView?,pageUrl:String?){
                            super.onPageFinished(view,pageUrl);setState.value(EmbedState.LOADED)
                        }
                        override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:WebResourceError?){
                            super.onReceivedError(view,request,error);if(request?.isForMainFrame==true)setState.value(EmbedState.FAILED)
                        }
                        override fun onReceivedHttpError(view:WebView?,request:WebResourceRequest?,error:android.webkit.WebResourceResponse?){
                            super.onReceivedHttpError(view,request,error)
                            if(request?.isForMainFrame==true&&(error?.statusCode?:200)>=400)setState.value(EmbedState.FAILED)
                        }
                    }
                    web.value=this
                }
            },update={view->if(view.tag!=key){view.tag=key;state=EmbedState.LOADING;view.stopLoading();view.loadUrl(url)}})
            if(state==EmbedState.LOADING)Column(Modifier.align(Alignment.TopStart).padding(10.dp)){
                CircularProgressIndicator(color=Color(0xFF2DD4BF),strokeWidth=2.dp)
                Text("Loading chart...",color=Color(0xFF9AA8BA),fontSize=10.sp)
            }else if(state==EmbedState.FAILED)Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){
                Text("Loading chart...",color=Color(0xFF9AA8BA),fontSize=12.sp)
                TextButton(onClick={retry++}){Text("RETRY",color=Color(0xFF2DD4BF))}
            }
        }
    }
}
private enum class EmbedState{LOADING,LOADED,FAILED}
