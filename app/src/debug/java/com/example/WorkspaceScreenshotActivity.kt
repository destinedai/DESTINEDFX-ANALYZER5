package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ui.DestinedFXApp
import com.example.ui.DestinedFXScreen
import com.example.ui.createPreviewViewModel
import com.example.ui.theme.DestinedFXTheme

/** Debug-only host using deterministic, explicitly non-live preview data for emulator screenshots. */
class WorkspaceScreenshotActivity : ComponentActivity() {
    private lateinit var previewViewModel: com.example.ui.DestinedFXViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewViewModel = createPreviewViewModel(application)
        setContent {
            DestinedFXTheme {
                DestinedFXApp(previewViewModel)
            }
        }
    }

    fun showWorkspace(screen: DestinedFXScreen) {
        previewViewModel.selectScreen(screen)
    }
}
