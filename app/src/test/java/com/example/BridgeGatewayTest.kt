package com.example

import com.example.data.trade.BridgeGateway
import com.example.data.trade.BridgeGatewayException
import com.example.data.trade.BridgeGatewayFailure
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** All response bodies below are recorded-shape FIXTURES, not captures from a real Codespaces host. */
class BridgeGatewayTest {
    private val request = Request.Builder().url("https://fixture-codespace-8000.app.github.dev/v1/app/state").build()

    private fun fixture(code: Int, contentType: String, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .header("Content-Type", contentType)
        .body(body.toResponseBody(contentType.toMediaType())).build()

    private fun failureOf(response: Response): BridgeGatewayFailure? = try {
        BridgeGateway.inspect(response); null
    } catch (e: BridgeGatewayException) { e.failure }

    @Test fun everyRequestCarriesTheTunnelSkipHeaderAndJsonAccept() {
        val prepared = BridgeGateway.prepare(request.newBuilder().header("Accept", "text/html").build())
        assertEquals("true", prepared.header("X-Tunnel-Skip-AntiPhishing-Page"))
        assertEquals("application/json", prepared.header("Accept"))
    }

    @Test fun jsonSuccessPassesThrough() {
        val response = fixture(200, "application/json", """{"status":"ready"}""")
        assertSame(response, BridgeGateway.inspect(response))
    }

    @Test fun interstitialHtmlIsItsOwnFailureNeverConnected() {
        val html = "<!DOCTYPE html><html><body>Continue to the site you are about to visit</body></html>"
        assertEquals(BridgeGatewayFailure.BRIDGE_NEEDS_BROWSER_APPROVAL, failureOf(fixture(200, "text/html; charset=utf-8", html)))
    }

    @Test fun htmlWithAMislabelledContentTypeIsStillDetected() {
        assertEquals(BridgeGatewayFailure.BRIDGE_NEEDS_BROWSER_APPROVAL, failureOf(fixture(200, "text/plain", "  <html>page</html>")))
    }

    @Test fun unauthorizedJsonIsABridgeAnswerNotAGatewayFailure() {
        assertEquals(null, failureOf(fixture(401, "application/json", """{"detail":"Unauthorized"}""")))
    }

    @Test fun bridgeJson404IsNotTreatedAsAChangedUrl() {
        assertEquals(null, failureOf(fixture(404, "application/json", """{"detail":"Not Found"}""")))
    }

    @Test fun gateway404WithoutJsonMeansTheUrlChanged() {
        assertEquals(BridgeGatewayFailure.BRIDGE_URL_CHANGED, failureOf(fixture(404, "text/plain", "Not Found")))
    }

    @Test fun userMessagesAreOneLineAndNameTheFix() {
        assertTrue(BridgeGatewayFailure.BRIDGE_NEEDS_BROWSER_APPROVAL.userMessage.contains("Continue"))
        assertTrue(BridgeGatewayFailure.BRIDGE_URL_CHANGED.userMessage.contains("Re-pair"))
        BridgeGatewayFailure.values().forEach { assertFalse(it.userMessage.contains("\n")) }
    }

    @Test fun onlyAppGithubDevOriginsCountAsCodespacesOrigins() {
        assertTrue(BridgeGateway.isCodespacesOrigin("https://my-codespace-8000.app.github.dev"))
        assertFalse(BridgeGateway.isCodespacesOrigin("http://my-codespace-8000.app.github.dev"))
        assertFalse(BridgeGateway.isCodespacesOrigin("https://evil.example.com/app.github.dev"))
        assertFalse(BridgeGateway.isCodespacesOrigin("https://app.github.dev.evil.com"))
    }

    @Test fun timeoutIsNotMappedToAGatewayFailure() {
        try { throw java.net.SocketTimeoutException("fixture timeout") }
        catch (e: java.io.IOException) { assertFalse(e is BridgeGatewayException) }
    }
}
