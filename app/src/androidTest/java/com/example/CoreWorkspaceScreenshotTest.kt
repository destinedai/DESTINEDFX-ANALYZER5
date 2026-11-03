package com.example

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
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
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Shared screenshot export requires Android 10 or later" }
        val resolver = instrumentation.targetContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/DESTINEDFX-Test-UI-Review/"
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath)
        )

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
            assertEquals("Expected a screenshot for all six workspaces at both widths", 12, outputDirectory.listFiles()?.size)
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
            assertEquals("Expected all 12 screenshots exported through MediaStore", 12, exportedFileNames.size)
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
