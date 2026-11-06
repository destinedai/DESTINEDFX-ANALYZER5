package com.example.data.deriv

enum class DerivDemoVerificationState { NOT_RUN, VERIFYING, PASS, FAILED }

data class DerivDemoVerificationResult(
    val accountPrefix: String,
    val accountType: String,
    val currency: String,
    val balanceReceived: Boolean,
    val portfolioReceived: Boolean,
    val tickReceived: Boolean,
    val checkedAtMs: Long,
    val failureMessage: String? = null
) {
    val state: DerivDemoVerificationState
        get() = if (failureMessage.isNullOrBlank() && accountType == "DEMO" && balanceReceived && portfolioReceived && tickReceived) {
            DerivDemoVerificationState.PASS
        } else {
            DerivDemoVerificationState.FAILED
        }
}

fun redactedDerivAccountPrefix(accountId: String): String = accountId.trim().take(4).ifBlank { "unverified" }

fun derivDemoVerificationLabel(result: DerivDemoVerificationResult?): String = when (result?.state) {
    null -> "NOT RUN"
    DerivDemoVerificationState.PASS -> "PASS · READ-ONLY DEMO EVIDENCE"
    DerivDemoVerificationState.FAILED -> "FAILED · DEMO NOT VERIFIED"
    DerivDemoVerificationState.NOT_RUN -> "NOT RUN"
    DerivDemoVerificationState.VERIFYING -> "VERIFYING"
}
