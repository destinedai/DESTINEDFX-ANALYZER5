package com.example

import com.example.security.AppSecurityGuard
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSecurityGuardTest {
    @After
    fun resetGuard() {
        AppSecurityGuard.lock()
    }

    @Test
    fun lockedProcessCannotAuthorizeSensitiveAction() {
        AppSecurityGuard.lock()
        assertFalse(AppSecurityGuard.authorizeSensitiveAction(nowMs = 1_000L))
    }

    @Test
    fun authenticatedLeaseExpires() {
        AppSecurityGuard.unlock(nowMs = 1_000L)
        assertTrue(AppSecurityGuard.authorizeSensitiveAction(nowMs = 1_000L))
        assertFalse(AppSecurityGuard.authorizeSensitiveAction(nowMs = 301_001L))
    }

    @Test
    fun lifecycleLockInvalidatesPreviouslyGrantedLease() {
        AppSecurityGuard.unlock(nowMs = 1_000L)
        assertTrue(AppSecurityGuard.authorizeSensitiveAction(nowMs = 2_000L))
        AppSecurityGuard.lock()
        assertFalse(AppSecurityGuard.authorizeSensitiveAction(nowMs = 2_001L))
    }
}
