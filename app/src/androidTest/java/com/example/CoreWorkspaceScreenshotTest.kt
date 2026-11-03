package com.example

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.DestinedFXScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreWorkspaceScreenshotTest {
    @Test
    fun capturesSixDedicatedWorkspacesAtCompactAndStandardPhoneWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDirectory = File(instrumentation.targetContext.filesDir, "ui-review")
        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Unable to create screenshot output directory" }
        val sharedOutputDirectory = "/sdcard/Download/destinedfx-ui-review"
        runShell(instrumentation, "rm -rf $sharedOutputDirectory && mkdir -p $sharedOutputDirectory")

        val screens = listOf(
            DestinedFXScreen.Analyzer,
            DestinedFXScreen.Strategies,
            DestinedFXScreen.Calendar,
            DestinedFXScreen.Bot,
            DestinedFXScreen.Risk,
            DestinedFXScreen.Settings
        )
        val scenario = ActivityScenario.launch(WorkspaceScreenshotActivity::class.java)
        try {
            for (widthDp in listOf(320, 430)) {
                setLogicalDisplay(instrumentation, widthDp, if (widthDp == 320) 800 else 900)
                for (screen in screens) {
                    scenario.onActivity { it.showWorkspace(screen) }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(250)
                    val image = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull("Screenshot unavailable for ${screen.title} at ${widthDp}dp", image)
                    val fileName = "${screen.title.lowercase().replace(' ', '_')}_${widthDp}dp.png"
                    val screenshot = File(outputDirectory, fileName)
                    screenshot.outputStream().use { stream ->
                        assertTrue("PNG encoding failed for $fileName", image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertEquals("Unexpected screenshot width", widthDp, image.width)
                    assertTrue("Screenshot is unexpectedly small: $fileName", screenshot.length() > 2_000L)
                    val copied = runShell(
                        instrumentation,
                        "run-as ${instrumentation.targetContext.packageName} cat files/ui-review/$fileName > $sharedOutputDirectory/$fileName && echo PNG_COPY_OK"
                    )
                    assertTrue("Could not export $fileName from the target app: $copied", copied.contains("PNG_COPY_OK"))
                    image.recycle()
                }
            }
            assertEquals("Expected a screenshot for all six workspaces at both widths", 12, outputDirectory.listFiles()?.size)
        } finally {
            runShell(instrumentation, "wm size reset")
            runShell(instrumentation, "wm density reset")
            scenario.close()
        }
    }

    private fun setLogicalDisplay(instrumentation: android.app.Instrumentation, widthDp: Int, heightDp: Int) {
        runShell(instrumentation, "wm density 160")
        runShell(instrumentation, "wm size ${widthDp}x${heightDp}")
        SystemClock.sleep(200)
    }

    private fun runShell(instrumentation: android.app.Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
