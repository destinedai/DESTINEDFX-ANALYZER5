package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.DestinedFXViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DestinedFX", appName)
  }

  @Test
  fun `launch MainActivity directly`() {
    androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertNotNull(activity)
      }
    }
  }

  @Test
  fun `verify application and repository initialization`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    assertNotNull(context.db)
    assertNotNull(context.secretsManager)
    assertNotNull(context.marketDataRepository)
    assertNotNull(context.mt5GatewayRepository)
  }

  @Test
  fun `verify biometric availability check does not throw exception`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val vm = DestinedFXViewModel(context)
    vm.checkBiometricAvailability(context)
    assertNotNull(vm.biometricStatus.value)
  }

  @Test
  fun `verify MT5GatewayRepository loadSymbolSpec gracefully handles unconfigured state`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val result = context.mt5GatewayRepository.loadSymbolSpec("EURUSD")
    assertTrue(result.isFailure)
  }

  @Test
  fun `verify Telegram settings state persistence and unlinking`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val vm = DestinedFXViewModel(context)
    assertEquals("Not configured", vm.telegramStatus.value)
    
    vm.setTelegramNotificationsEnabled(true)
    // Should not allow enabling notifications when not connected
    assertFalse(vm.telegramNotificationsEnabled.value)

    context.secretsManager.saveTelegramToken("mock_token")
    context.secretsManager.saveTelegramChatId("mock_chat")
    context.secretsManager.saveTelegramConnectionStatus("Connected")
    vm.refreshTelegramStatus()
    assertEquals("Connected", vm.telegramStatus.value)

    vm.unlinkTelegram()
    assertEquals("Not configured", vm.telegramStatus.value)
    assertFalse(vm.telegramNotificationsEnabled.value)
  }

  @Test
  fun `verify fresh install opens terminal directly in guest mode without login or lock gate`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val vm = DestinedFXViewModel(context)

    // 1. Fresh install has no Supabase session (unauthenticated guest)
    assertNull(vm.currentUser.value)

    // 2. Terminal is NOT locked by biometrics or login gate
    assertFalse(vm.isAppLocked.value)
    assertFalse(vm.showAuthScreen.value)

    // 3. Open the website-inspired Home dashboard first; all native destinations remain accessible.
    assertEquals(com.example.ui.DestinedFXScreen.Home, vm.selectedScreen.value)
    vm.selectScreen(com.example.ui.DestinedFXScreen.Analyzer)
    assertEquals(com.example.ui.DestinedFXScreen.Analyzer, vm.selectedScreen.value)
    vm.selectScreen(com.example.ui.DestinedFXScreen.ImageAnalyzer)
    assertEquals(com.example.ui.DestinedFXScreen.ImageAnalyzer, vm.selectedScreen.value)
    vm.selectScreen(com.example.ui.DestinedFXScreen.Strategies)
    assertEquals(com.example.ui.DestinedFXScreen.Strategies, vm.selectedScreen.value)

    // 4. Broker trading remains blocked without credentials
    assertFalse(vm.brokerConnected.value)
  }

  @Test
  fun `verify logout re-enters guest mode without trapping user on mandatory login screen`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val vm = DestinedFXViewModel(context)

    vm.logoutUser()

    assertNull(vm.currentUser.value)
    assertFalse(vm.showAuthScreen.value)
    assertFalse(vm.isAppLocked.value)
    assertEquals(com.example.ui.DestinedFXScreen.Home, vm.selectedScreen.value)
  }

  @Test
  fun `verify guest bot activation does not require Supabase but does require broker credentials`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val botEngine = context.autonomousBotEngine

    // Fresh guest mode is permitted. With no Deriv/MT5 credentials, activation must instead
    // fail at the broker connection boundary (not because a Supabase session is missing).
    context.secretsManager.saveDerivApiToken("")
    val result = botEngine.startBot("EURUSD", "smc-confluence-1.0.0", "H1", 1.0)
    assertTrue(result.isFailure)
    val reason = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("Expected missing broker credentials to block activation, got: " + reason,
      reason.contains("BROKER_DISCONNECTED"))
    assertFalse(reason.contains("AUTH_REQUIRED"))
    assertFalse(reason.contains("EMAIL_VERIFICATION_REQUIRED"))
  }

  @Test
  fun `verify chart and MTF selections are native reactive view model state`() {
    val context = ApplicationProvider.getApplicationContext<MyApplication>()
    val vm = DestinedFXViewModel(context)
    assertEquals("EUR/USD", vm.selectedTicker.value)
    assertEquals("M15", vm.selectedTimeframe.value)
    assertTrue("H4" in vm.selectedMtfTimeframes.value)
    vm.selectTicker("GBP/USD")
    vm.selectTimeframe("H1")
    vm.toggleMtfTimeframe("M5")
    assertEquals("GBP/USD", vm.selectedTicker.value)
    assertEquals("H1", vm.selectedTimeframe.value)
    assertFalse("M5" in vm.selectedMtfTimeframes.value)
  }
}
