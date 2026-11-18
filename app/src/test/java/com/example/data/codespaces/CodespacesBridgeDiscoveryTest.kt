package com.example.data.codespaces

import org.junit.Assert.assertEquals
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
}
