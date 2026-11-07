package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The six core workspaces stay directly accessible in the bottom navigation.
 * Home and secondary utilities remain reachable from the header's More workspaces menu.
 */
sealed class DestinedFXScreen(val title: String, val purpose: String, val icon: ImageVector, val tag: String) {
    object Home : DestinedFXScreen("Home", "Terminal overview and latest activity", Icons.Default.Home, "nav_tab_home")
    object Analyzer : DestinedFXScreen("Analyzer", "Build and validate an independent market plan", Icons.Default.ShowChart, "nav_tab_chart")
    object ImageAnalyzer : DestinedFXScreen("Image Analyzer", "Inspect chart screenshots with on-device evidence", Icons.Default.Image, "nav_tab_image_analyzer")
    object Strategies : DestinedFXScreen("Strategies", "Review deterministic strategy rules and coverage", Icons.Default.Architecture, "nav_tab_strategies")
    object Calendar : DestinedFXScreen("Calendar", "Verify sessions and economic event risk", Icons.Default.CalendarMonth, "nav_tab_calendar")
    object Bot : DestinedFXScreen("Read-only scope", "Broker connectivity and order execution are not available", Icons.Default.Info, "nav_tab_read_only_scope")
    object Watchlist : DestinedFXScreen("Watchlist", "Track instruments that matter to you", Icons.Default.BookmarkBorder, "nav_tab_watchlist")
    object Chat : DestinedFXScreen("Chat", "Review assistant conversations and context", Icons.Default.Chat, "nav_tab_chat")
    object More : DestinedFXScreen("More", "Additional workspaces and disclosures", Icons.Default.MoreHoriz, "nav_tab_more")
    object Risk : DestinedFXScreen("Risk", "Calculate exposure within the safety rules", Icons.Default.Calculate, "nav_tab_risk")
    object Settings : DestinedFXScreen("Settings", "Configure preferences, connections, and security", Icons.Default.Settings, "nav_tab_settings")
    object About : DestinedFXScreen("About & Disclosure", "Understand data, safety, and product boundaries", Icons.Default.Info, "nav_tab_about")
    object Contact : DestinedFXScreen("Contact", "Get help and send product feedback", Icons.Default.Email, "nav_tab_contact")

    companion object {
        val primaryScreens: List<DestinedFXScreen>
            get() = listOf(Analyzer, Strategies, Calendar, Watchlist, Risk, Settings)

        val moreScreens: List<DestinedFXScreen>
            get() = listOf(Home, ImageAnalyzer, Chat, About, Contact)

        val allScreens: List<DestinedFXScreen>
            get() = listOf(Home, Analyzer, Strategies, Calendar, More, Chat, ImageAnalyzer, Bot, Watchlist, Risk, Settings, About, Contact)
    }
}
