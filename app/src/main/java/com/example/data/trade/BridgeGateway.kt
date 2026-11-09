package com.example.data.trade

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException

/** Distinct, non-generic failures of the Codespaces (Dev Tunnels) gateway in front of the bridge. */
enum class BridgeGatewayFailure(val userMessage: String) {
    /** The gateway answered with its HTML anti-phishing page instead of the bridge's JSON. */
    BRIDGE_NEEDS_BROWSER_APPROVAL("Open the bridge URL once in your phone browser and tap Continue, then retry."),
    /** The codespace host no longer answers at this URL (recreated, deleted or renamed). */
    BRIDGE_URL_CHANGED("The test host URL changed. Re-pair with the new bridge URL and pairing code.")
}

class BridgeGatewayException(val failure: BridgeGatewayFailure) : IOException(failure.name)

/**
 * Applied to every bridge request, REST and streaming. The tunnel skips its interstitial for non-GET
 * requests, for clients that do not accept text/html, and when the skip header is present; we send
 * both the header and an explicit JSON Accept, and still treat an HTML reply as its own failure.
 * An HTML reply is never a connected state and never a generic error.
 */
object BridgeGateway {
    const val TUNNEL_SKIP_HEADER = "X-Tunnel-Skip-AntiPhishing-Page"

    fun prepare(request: Request): Request = request.newBuilder()
        .header("Accept", "application/json")
        .header(TUNNEL_SKIP_HEADER, "true")
        .build()

    /** Returns the response unchanged when it came from the bridge; throws a typed failure when it did not. */
    fun inspect(response: Response): Response {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val isJson = contentType.contains("json")
        val prefix = if (isJson) "" else runCatching { response.peekBody(512).string().trimStart() }.getOrDefault("")
        val looksHtml = contentType.contains("text/html") || prefix.startsWith("<")
        if (response.code == 404 && !isJson) throw BridgeGatewayException(BridgeGatewayFailure.BRIDGE_URL_CHANGED)
        if (looksHtml) throw BridgeGatewayException(BridgeGatewayFailure.BRIDGE_NEEDS_BROWSER_APPROVAL)
        return response
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val response = try {
            chain.proceed(prepare(chain.request()))
        } catch (_: UnknownHostException) {
            throw BridgeGatewayException(BridgeGatewayFailure.BRIDGE_URL_CHANGED)
        }
        try { inspect(response) } catch (e: BridgeGatewayException) { response.close(); throw e }
    }

    /** True for the GitHub Codespaces forwarded-port origins; any other origin must be entered explicitly as https. */
    fun isCodespacesOrigin(origin: String): Boolean =
        Regex("^https://[a-z0-9-]+\\.app\\.github\\.dev$").matches(origin.trim().lowercase())
}
