package com.example

import com.example.data.broker.BrokerDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerDirectoryTruthfulnessTest {
    @Test
    fun onlyTheFourRequestedMt5BrokerBrandsAreInTheManagedPicker() {
        assertEquals(setOf("FBS", "EXNESS", "HFM", "DERIV"), BrokerDirectory.allBrokers.map { it.brandId }.toSet())
        assertEquals(4, BrokerDirectory.allBrokers.size)
        assertTrue(BrokerDirectory.allBrokers.all { it.servers.isEmpty() })
    }

    @Test
    fun serverNamesAreNeverInventedAndDerivMt5IsDistinctFromItsOptionsApi() {
        assertTrue(BrokerDirectory.allBrokers.any { it.brandId == "DERIV" && it.name.contains("MT5", ignoreCase = true) })
        assertTrue(BrokerDirectory.allBrokers.all { it.servers.isEmpty() })
        assertEquals("HFM", BrokerDirectory.byBrandId("hfm")?.brandId)
        assertEquals("DERIV", BrokerDirectory.byBrandId("deriv")?.brandId)
        assertEquals(null, BrokerDirectory.byBrandId("UNKNOWN"))
    }

    @Test
    fun brokerSearchReturnsOnlySupportedBrands() {
        assertEquals(listOf("EXNESS"), BrokerDirectory.searchBrokers("exness").map { it.brandId })
        assertEquals(listOf("HFM"), BrokerDirectory.searchBrokers("HF Markets").map { it.brandId })
    }
}
