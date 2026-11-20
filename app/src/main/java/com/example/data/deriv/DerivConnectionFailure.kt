package com.example.data.deriv

/** User-safe connection message; deliberately excludes response bodies, URLs, tokens and IDs. */
data class DerivConnectionFailure(
    val code: Code,
    val message: String,
    val recovery: String
) {
    enum class Code { INVALID_INPUT, UNAUTHORIZED, NO_DEMO_ACCOUNT, OTP_EXPIRED, NETWORK, RATE_LIMITED, BROKER_ERROR, STORAGE }
}

internal object DerivConnectionFailureMapper {
    fun from(error: Throwable): DerivConnectionFailure {
        val text = error.message.orEmpty().lowercase()
        return when {
            text.contains("secure android keystore") || text.contains("secure storage") -> DerivConnectionFailure(
                DerivConnectionFailure.Code.STORAGE,
                "Secure Android storage is unavailable; Deriv connection is blocked.",
                "Enable a device lock/Keystore-backed storage, then retry. Credentials were not saved."
            )
            text.contains("app id") || text.contains("token") || text.contains("trade scope") || text.contains("401") || text.contains("unauthorized") -> DerivConnectionFailure(
                DerivConnectionFailure.Code.UNAUTHORIZED,
                "Deriv authentication was rejected.",
                "Check the current App ID and create a fresh PAT for this app with the trade scope required by the Options account-list and OTP endpoints. Keep the PAT private; this verifier does not trade, but a trade-scoped PAT is not inherently read-only."
            )
            text.contains("no unique active") || text.contains("no active") || text.contains("account") && text.contains("selected") -> DerivConnectionFailure(
                DerivConnectionFailure.Code.NO_DEMO_ACCOUNT,
                "No single active DEMO Options account was available.",
                "Create or select a DEMO Options account returned by Deriv, then retry."
            )
            text.contains("otp") && (text.contains("expired") || text.contains("did not connect") || text.contains("one-time")) -> DerivConnectionFailure(
                DerivConnectionFailure.Code.OTP_EXPIRED,
                "The Deriv connection code expired before the session opened.",
                "Retry once on a stable network; a fresh one-time code will be requested."
            )
            text.contains("429") || text.contains("rate limit") || text.contains("too many") -> DerivConnectionFailure(
                DerivConnectionFailure.Code.RATE_LIMITED,
                "Deriv temporarily rate-limited the connection.",
                "Wait briefly and retry; do not repeatedly reconnect or open extra sockets."
            )
            text.contains("timeout") || text.contains("network") || text.contains("socket") || text.contains("unable to resolve") || text.contains("failed to connect") -> DerivConnectionFailure(
                DerivConnectionFailure.Code.NETWORK,
                "Deriv could not be reached reliably.",
                "Check internet access, VPN/firewall rules, and device time, then retry."
            )
            else -> DerivConnectionFailure(
                DerivConnectionFailure.Code.BROKER_ERROR,
                "Deriv did not complete the DEMO connection.",
                "Retry once. If it persists, verify the App ID, PAT scope, account mode, and Deriv service status."
            )
        }
    }
}
