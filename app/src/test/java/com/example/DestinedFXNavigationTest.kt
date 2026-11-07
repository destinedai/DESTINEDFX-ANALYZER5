package com.example

import com.example.ui.DestinedFXScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestinedFXNavigationTest {
    @Test
    fun primaryNavigationExposesTheFiveOwnerPriorityWorkspacesInOrder() {
        assertEquals(
            listOf("Home", "Analyzer", "Bot", "Watchlist", "More"),
            DestinedFXScreen.primaryScreens.map { it.title }
        )
    }

    @Test
    fun secondaryWorkspacesRemainReachableWithoutDuplicatingPrimaryTabs() {
        assertTrue(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Strategies))
        assertTrue(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Calendar))
        assertTrue(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Risk))
        assertTrue(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Settings))
        assertFalse(DestinedFXScreen.moreScreens.any { it in DestinedFXScreen.primaryScreens })
        assertTrue(DestinedFXScreen.allScreens.containsAll(DestinedFXScreen.primaryScreens))
        assertEquals(DestinedFXScreen.allScreens.size, DestinedFXScreen.allScreens.distinctBy { it.tag }.size)
    }
}
