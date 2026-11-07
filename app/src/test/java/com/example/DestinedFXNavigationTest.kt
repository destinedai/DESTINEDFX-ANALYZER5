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
    fun primaryNavigationExposesTheSixCoreWorkspacesInOrder() {
        assertEquals(
            listOf("Analyzer", "Strategies", "Calendar", "Watchlist", "Risk", "Settings"),
            DestinedFXScreen.primaryScreens.map { it.title }
        )
        assertFalse(DestinedFXScreen.primaryScreens.contains(DestinedFXScreen.Bot))
    }

    @Test
    fun secondaryWorkspacesRemainReachableWithoutHidingCoreTabs() {
        assertTrue(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Home))
        assertFalse(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Bot))
        assertFalse(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Risk))
        assertFalse(DestinedFXScreen.moreScreens.contains(DestinedFXScreen.Settings))
        assertEquals(DestinedFXScreen.allScreens.size, DestinedFXScreen.allScreens.distinctBy { it.tag }.size)
    }
}
