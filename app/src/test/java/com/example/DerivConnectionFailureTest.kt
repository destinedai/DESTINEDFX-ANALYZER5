package com.example

import com.example.data.deriv.DerivConnectionFailure
import com.example.data.deriv.DerivConnectionFailureMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivConnectionFailureTest {
    @Test
    fun unauthorizedMessageDoesNotEchoTokenOrResponseBody() {
        val token = "PAT-secret-must-not-appear"
        val result = DerivConnectionFailureMapper.from(Exception("HTTP 401: invalid token $token"))

        assertEquals(DerivConnectionFailure.Code.UNAUTHORIZED, result.code)
        assertFalse(result.message.contains(token))
        assertFalse(result.recovery.contains(token))
        assertTrue(result.recovery.contains("fresh PAT"))
    }

    @Test
    fun otpTimeoutExplainsFreshOneTimeRecovery() {
        val result = DerivConnectionFailureMapper.from(Exception("Deriv authenticated WebSocket did not connect before the OTP expired."))

        assertEquals(DerivConnectionFailure.Code.OTP_EXPIRED, result.code)
        assertTrue(result.recovery.contains("fresh one-time code"))
    }

    @Test
    fun missingDemoAccountIsActionable() {
        val result = DerivConnectionFailureMapper.from(Exception("No unique active DEMO Options account was selected"))

        assertEquals(DerivConnectionFailure.Code.NO_DEMO_ACCOUNT, result.code)
        assertTrue(result.message.contains("DEMO"))
        assertTrue(result.recovery.contains("select"))
    }

    @Test
    fun storageFailureFailsClosed() {
        val result = DerivConnectionFailureMapper.from(Exception("Secure Android Keystore storage is unavailable"))

        assertEquals(DerivConnectionFailure.Code.STORAGE, result.code)
        assertTrue(result.message.contains("blocked"))
    }
}
