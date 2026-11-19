package com.example

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.DestinedFXScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreWorkspaceScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WorkspaceScreenshotActivity>()

    @Test
    fun capturesAllAppWorkspacesAtCompactAndStandardPhoneWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDirectory = File(instrumentation.targetContext.filesDir, "ui-review")
        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs()) { "Unable to create screenshot output directory" }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Shared screenshot export requires Android 10 or later" }
        val resolver = instrumentation.targetContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/DESTINEDFX-Test-UI-Review/"
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath)
        )

        val screens = DestinedFXScreen.allScreens
        try {
            val widthsAndHeights = listOf(320 to 800, 360 to 800, 390 to 844, 430 to 900)
            for ((widthDp, heightDp) in widthsAndHeights) {
                setLogicalDisplay(instrumentation, widthDp, heightDp)
                composeRule.waitForIdle()
                for (screen in screens) {
                    composeRule.activity.showWorkspace(screen)
                    // StateFlow collection and Compose rendering are asynchronous. Do not save a
                    // stale frame under the next screen's filename: wait for the actual visible
                    // workspace header to match the requested destination before taking a shot.
                    composeRule.waitUntil(timeoutMillis = 10_000L) {
                        runCatching {
                            composeRule.onNodeWithTag("workspace_header_title")
                                .assertTextEquals(screen.title)
                        }.isSuccess
                    }
                    composeRule.onNodeWithTag("workspace_header_title").assertIsDisplayed()
                    if (screen.title == "Bot") {
                        val accountsTitle = composeRule.onNodeWithTag("broker_flow_header_title")
                        accountsTitle.assertIsDisplayed()
                        accountsTitle.assertTextEquals("Accounts")
                        val headerHeightPx = accountsTitle.fetchSemanticsNode().boundsInRoot.height
                        assertTrue(
                            "Broker Accounts title wrapped on ${widthDp}dp phone viewport",
                            headerHeightPx <= 28f,
                        )
                    }
                    composeRule.waitForIdle()
                    SystemClock.sleep(150)
                    val image = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull("Screenshot unavailable for ${screen.title} at ${widthDp}dp", image)
                    val fileName = "${screen.title.lowercase().replace(' ', '_')}_${widthDp}dp.png"
                    val screenshot = File(outputDirectory, fileName)
                    screenshot.outputStream().use { stream ->
                        assertTrue("PNG encoding failed for $fileName", image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertEquals("Unexpected screenshot width", widthDp, image.width)
                    assertTrue("Screenshot is unexpectedly small: $fileName", screenshot.length() > 2_000L)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val imageUri = checkNotNull(
                        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ) { "Could not create shared MediaStore entry for $fileName" }
                    try {
                        val stream = checkNotNull(resolver.openOutputStream(imageUri)) {
                            "Could not open shared MediaStore entry for $fileName"
                        }
                        stream.use { destination ->
                            screenshot.inputStream().use { source -> source.copyTo(destination) }
                        }
                        val publishValues = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        check(resolver.update(imageUri, publishValues, null, null) == 1) {
                            "Could not publish shared MediaStore entry for $fileName"
                        }
                    } catch (failure: Throwable) {
                        resolver.delete(imageUri, null, null)
                        throw failure
                    }
                    image.recycle()
                }
            }
            assertEquals("Expected all app workspaces at all four phone widths", screens.size * 4, outputDirectory.listFiles()?.size)
            val exportedFileNames = mutableSetOf<String>()
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) exportedFileNames += cursor.getString(nameColumn)
            }
            assertEquals("Expected all app workspace screenshots exported through MediaStore", screens.size * 4, exportedFileNames.size)
        } finally {
            runShell(instrumentation, "wm size reset")
            runShell(instrumentation, "wm density reset")
            composeRule.waitForIdle()
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

    @Test
    fun everyWorkspaceRouteRendersAtStandardPhoneWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        setLogicalDisplay(instrumentation, 390, 844)
        try {
            composeRule.waitForIdle()
            for (screen in DestinedFXScreen.allScreens) {
                composeRule.activity.showWorkspace(screen)
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    runCatching {
                        composeRule.onNodeWithTag("workspace_header_title").assertTextEquals(screen.title)
                    }.isSuccess
                }
                composeRule.onNodeWithTag("workspace_header_title").assertIsDisplayed()
                composeRule.waitForIdle()
            }
        } finally {
            runShell(instrumentation, "wm size reset")
            runShell(instrumentation, "wm density reset")
            composeRule.waitForIdle()
        }
    }

}
