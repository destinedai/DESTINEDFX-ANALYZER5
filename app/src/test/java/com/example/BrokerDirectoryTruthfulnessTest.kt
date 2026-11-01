package com.example

import com.example.data.broker.BrokerDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerDirectoryTruthfulnessTest {
    @Test
    fun listedBrokersDoNotPretendToHaveVerifiedServerPresets() {
        assertTrue(BrokerDirectory.allBrokers.isNotEmpty())
        assertTrue(BrokerDirectory.allBrokers.all { it.servers.isEmpty() })
    }

    @Test
    fun oandaAndDerivRemainOutsideTheManagedMt5Picker() {
        val names = BrokerDirectory.allBrokers.map { it.name }
        assertFalse(names.any { it.contains("OANDA", ignoreCase = true) })
        assertFalse(names.any { it.contains("Deriv", ignoreCase = true) })
    }
}
