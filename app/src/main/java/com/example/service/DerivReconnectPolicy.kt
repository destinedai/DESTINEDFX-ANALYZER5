package com.example.service

/**
 * Every retry calls the authorization provider again. The production provider performs a new
 * account lookup and requests a new single-use Deriv OTP; a URL from a prior attempt is never reused.
 */
internal suspend fun <T> retryFreshDerivAuthorization(
    maxAttempts: Int,
    shouldContinue: () -> Boolean,
    onAttempt: (Int) -> Unit,
    pause: suspend (Long) -> Unit,
    reconnect: suspend () -> Result<T>
): Boolean {
    if (maxAttempts <= 0) return false
    var delayMs = 2_000L
    repeat(maxAttempts) { attempt ->
        if (!shouldContinue()) return false
        onAttempt(attempt)
        if (reconnect().isSuccess) return true
        if (attempt < maxAttempts - 1) {
            pause(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000L)
        }
    }
    return false
}
