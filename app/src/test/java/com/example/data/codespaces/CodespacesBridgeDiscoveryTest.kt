package com.example.data.codespaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CodespacesBridgeDiscoveryTest {
    @Test
    fun buildsTheDocumentedHttpsOriginForPort8000() {
        assertEquals(
            "https://steady-lizard-abc123-8000.app.github.dev",
            codespacesBridgeOrigin("steady-lizard-abc123")
        )
    }

    @Test
    fun supportsAnUpdatedGithubForwardingDomainWithoutChangingTheCodespaceName() {
        assertEquals(
            "https://steady-lizard-abc123-8000.preview.app.github.dev",
            codespacesBridgeOrigin("steady-lizard-abc123", "preview.app.github.dev")
        )
    }

    @Test
    fun rejectsPathInjectionAndArbitraryForwardingDomains() {
        assertThrows(IllegalArgumentException::class.java) {
            codespacesBridgeOrigin("codespace.example/path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codespacesBridgeOrigin("codespace", "attacker.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codespacesBridgeOrigin("codespace", "https://app.github.dev")
        }
    }

    @Test
    fun allowsBridgeOriginStorageWhenLinuxTerminalIsolationIsBlocked() {
        assertTrue(
            codespacesBridgeConfigurationAllowsOriginSave(
                status = "configured",
                database = "ok",
                managedExecution = "configured",
                isolationStatus = "BLOCKED"
            )
        )
    }

    @Test
    fun onlyAcceptsKnownIsolationStatesWhenSavingBridgeOrigin() {
        assertTrue(codespacesBridgeConfigurationAllowsOriginSave("configured", "ok", "configured", "VERIFIED"))
        assertTrue(codespacesBridgeConfigurationAllowsOriginSave("configured", "ok", "configured", "WORKER_ATTESTATION_REQUIRED"))
        assertFalse(codespacesBridgeConfigurationAllowsOriginSave("configured", "ok", "configured", ""))
        assertFalse(codespacesBridgeConfigurationAllowsOriginSave("configured", "ok", "configured", "UNKNOWN"))
        assertFalse(codespacesBridgeConfigurationAllowsOriginSave("unconfigured", "ok", "configured", "BLOCKED"))
        assertFalse(codespacesBridgeConfigurationAllowsOriginSave("configured", "down", "configured", "BLOCKED"))
    }

    @Test
    fun blockedIsolationNoticeMakesTheRegistrationBlockExplicit() {
        val notice = codespacesBridgeIsolationNotice("BLOCKED")
        assertTrue(notice.contains("BLOCKED"))
        assertTrue(notice.contains("broker credentials cannot be registered"))
    }
}
