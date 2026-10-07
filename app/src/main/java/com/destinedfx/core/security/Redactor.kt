package com.destinedfx.core.security

import java.net.URI

/** Central redaction used by every logger/exception path. Secrets must never reach logcat, crash reports or UI. */
object Redactor {
    private val patterns = listOf(
        Regex("(?i)(api[_-]?key|apikey|token|auth-token|authorization|password|passwd|secret|key)=([^&\\s\"']+)"),
        Regex("(?i)(\"?(?:password|token|apiKey|secret|auth-token)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)"),
        Regex("(?i)(bearer\\s+)([A-Za-z0-9._\\-]+)"))
    fun redact(s: String): String = patterns.fold(s) { acc, r -> r.replace(acc) { m -> m.groupValues[1] + "***" } }
}

object SecureTransport {
    /** Broker/AI gateways must be TLS. cleartext ws:// and http:// are refused outright. */
    fun requireSecure(url: String): Result<String> = runCatching {
        val u = URI(url.trim()); require(u.scheme == "wss" || u.scheme == "https") { "Insecure or missing scheme; wss:// or https:// required" }
        require(!u.host.isNullOrBlank() && !u.host.contains("your-secure-backend")) { "Gateway URL not configured" }; url.trim() }
}

/** Short-lived broker session: the raw password is exchanged once for a token that expires; only the token is kept in memory. */
class BrokerSession(val token: CharArray, val expiresAtMs: Long) {
    fun isValid(nowMs: Long, skewMs: Long = 30_000) = nowMs + skewMs < expiresAtMs
    fun wipe() { token.fill('\u0000') }
    override fun toString() = "BrokerSession(secret=***, expiresAtMs=$expiresAtMs)"
}
