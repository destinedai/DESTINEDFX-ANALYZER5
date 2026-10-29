package com.example

import com.example.data.redactSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramRedactionTest {
    private val token = "123456789:AAE-abcdefghijklmnopqrstuvwxyz012345"

    @Test
    fun exactTokenIsRemovedFromExceptionMessages() {
        val msg = "Unexpected URL https://api.telegram.org/bot$token/getMe failed"
        val out = redactSecrets(msg, token)
        assertFalse(out.contains(token))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun tokenShapedStringsAreMaskedEvenWhenTokenIsNotPassed() {
        val out = redactSecrets("bad host: bot$token/sendMessage")
        assertFalse(out.contains(token))
        assertFalse(out.contains("AAE-"))
    }

    @Test
    fun nullAndBenignMessagesStayUsable() {
        assertEquals("", redactSecrets(null, token))
        assertEquals("timeout", redactSecrets("timeout", token))
    }
}
