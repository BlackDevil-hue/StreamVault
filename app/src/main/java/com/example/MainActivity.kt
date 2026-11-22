package com.example

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.domain.model.AppSettings
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.VideoAnalyzerRepository
import com.example.ui.navigation.StreamVaultNavHost
import com.example.ui.theme.StreamVaultTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var downloadRepository: DownloadRepository

    @Inject
    lateinit var analyzerRepository: VideoAnalyzerRepository

    private var initialSharedUrl by mutableStateOf<String?>(null)
    private var isInPipMode by mutableStateOf(false)
    private val sharedUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        extractSharedUrl(intent)?.let { url ->
            initialSharedUrl = url
        }

        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings()
            )
            val activeCount by downloadRepository.activeAndQueuedDownloads
                .map { it.size }
                .collectAsStateWithLifecycle(initialValue = 0)
            val windowSizeClass = calculateWindowSizeClass(this)

            StreamVaultTheme(
                themePreference = settings.themePreference,
                dynamicColor = settings.dynamicColorEnabled
            ) {
                StreamVaultNavHost(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    activeDownloadCount = activeCount,
                    isInPictureInPictureMode = isInPipMode,
                    incomingSharedUrls = sharedUrlEvents,
                    initialSharedUrl = initialSharedUrl,
                    onConsumeInitialSharedUrl = { initialSharedUrl = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { url ->
            lifecycleScope.launch {
                sharedUrlEvents.emit(url)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return analyzerRepository.extractFirstUrlFromText(sharedText)
    }
}
