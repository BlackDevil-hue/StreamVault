package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.network.YouTubeExtractor
import com.example.domain.model.AppSettings
import com.example.domain.model.DownloadTask
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.VideoAnalyzerRepository
import com.example.domain.source.ExtractionOutcome
import com.example.ui.state.AnalysisSessionCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val urlInput: String = "",
    val clipboardDetectedUrl: String? = null,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null,
    val activeDownloads: List<DownloadTask> = emptyList(),
    val recentDownloads: List<DownloadTask> = emptyList(),
    val notificationPromptDismissed: Boolean = false,
    val batchInput: String = "",
    val playlistStartIndex: String = "1",
    val playlistEndIndex: String = "50",
    val batchItems: List<BatchVideoItem> = emptyList(),
    val isAnalyzingBatch: Boolean = false,
    val isQueueingBatch: Boolean = false,
    val batchMessage: String? = null
)

data class BatchVideoItem(
    val url: String,
    val result: VideoAnalysisResult? = null,
    val error: String? = null,
    val selected: Boolean = true,
    val queued: Boolean = false
)

sealed interface HomeNavigationEvent {
    data class OpenDownloadOptions(val url: String) : HomeNavigationEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val analyzerRepository: VideoAnalyzerRepository,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val analysisCache: AnalysisSessionCache,
    private val youtubeExtractor: YouTubeExtractor
) : ViewModel() {

    private val urlInputFlow = MutableStateFlow("")
    private val clipboardUrlFlow = MutableStateFlow<String?>(null)
    private val isAnalyzingFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)
    private val batchFlow = MutableStateFlow(HomeUiState())
    private var dismissedClipboardUrl: String? = null

    private val _navEvents = MutableSharedFlow<HomeNavigationEvent>()
    val navEvents = _navEvents.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        urlInputFlow,
        clipboardUrlFlow,
        isAnalyzingFlow,
        errorFlow,
        downloadRepository.activeAndQueuedDownloads,
        downloadRepository.recentDownloads,
        batchFlow,
        settingsRepository.settings
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val batch = values[6] as HomeUiState
        val settings = values[7] as AppSettings
        HomeUiState(
            urlInput = values[0] as String,
            clipboardDetectedUrl = values[1] as String?,
            isAnalyzing = values[2] as Boolean,
            errorMessage = values[3] as String?,
            activeDownloads = values[4] as List<DownloadTask>,
            recentDownloads = values[5] as List<DownloadTask>,
            notificationPromptDismissed = settings.notificationPromptDismissed,
            batchInput = batch.batchInput,
            playlistStartIndex = batch.playlistStartIndex,
            playlistEndIndex = batch.playlistEndIndex,
            batchItems = batch.batchItems,
            isAnalyzingBatch = batch.isAnalyzingBatch,
            isQueueingBatch = batch.isQueueingBatch,
            batchMessage = batch.batchMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun dismissNotificationPrompt() {
        viewModelScope.launch {
            settingsRepository.setNotificationPromptDismissed(true)
        }
    }

    fun onNotificationPermissionGranted() {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(true)
            settingsRepository.setNotificationPromptDismissed(true)
        }
    }

    fun onUrlChanged(newValue: String) {
        urlInputFlow.value = newValue
        if (errorFlow.value != null) errorFlow.value = null
    }

    fun onClipboardTextInspected(clipboardText: String?) {
        val extracted = analyzerRepository.extractFirstUrlFromText(clipboardText)
        if (extracted != null &&
            extracted != urlInputFlow.value.trim() &&
            extracted != dismissedClipboardUrl
        ) {
            clipboardUrlFlow.value = extracted
        } else if (extracted == null) {
            clipboardUrlFlow.value = null
        }
    }

    fun dismissClipboardSuggestion() {
        dismissedClipboardUrl = clipboardUrlFlow.value
        clipboardUrlFlow.value = null
    }

    fun pasteFromClipboard(clipboardText: String?) {
        val extracted = analyzerRepository.extractFirstUrlFromText(clipboardText) ?: clipboardText?.trim()
        if (!extracted.isNullOrBlank()) {
            urlInputFlow.value = extracted
            clipboardUrlFlow.value = null
            errorFlow.value = null
        } else {
            errorFlow.value = "No link found in clipboard."
        }
    }

    fun pasteAndAnalyzeFromClipboard(clipboardText: String?) {
        val extracted = analyzerRepository.extractFirstUrlFromText(clipboardText) ?: clipboardText?.trim()
        if (!extracted.isNullOrBlank()) {
            urlInputFlow.value = extracted
            clipboardUrlFlow.value = null
            errorFlow.value = null
            analyzeCurrentUrl()
        } else {
            errorFlow.value = "No link found in clipboard."
        }
    }

    fun analyzeClipboardUrl() {
        val url = clipboardUrlFlow.value ?: return
        urlInputFlow.value = url
        clipboardUrlFlow.value = null
        analyzeCurrentUrl()
    }

    fun analyzeCurrentUrl() {
        val raw = urlInputFlow.value.trim()
        if (raw.isBlank()) {
            errorFlow.value = "Please paste or enter a video URL to analyze."
            return
        }
        if (isAnalyzingFlow.value) return

        viewModelScope.launch {
            isAnalyzingFlow.value = true
            errorFlow.value = null
            when (val outcome = analyzerRepository.analyzeUrl(raw)) {
                is ExtractionOutcome.Success -> {
                    analysisCache.put(outcome.result.sourceUrl, outcome.result)
                    _navEvents.emit(HomeNavigationEvent.OpenDownloadOptions(outcome.result.sourceUrl))
                }
                is ExtractionOutcome.Failure -> {
                    errorFlow.value = outcome.error.userMessage
                }
            }
            isAnalyzingFlow.value = false
        }
    }

    fun onBatchInputChanged(value: String) {
        batchFlow.value = batchFlow.value.copy(batchInput = value, batchMessage = null)
    }

    fun onPlaylistStartIndexChanged(value: String) {
        batchFlow.value = batchFlow.value.copy(playlistStartIndex = value.filter { it.isDigit() }, batchMessage = null)
    }

    fun onPlaylistEndIndexChanged(value: String) {
        batchFlow.value = batchFlow.value.copy(playlistEndIndex = value.filter { it.isDigit() }, batchMessage = null)
    }

    fun setBatchItemSelected(url: String, selected: Boolean) {
        batchFlow.value = batchFlow.value.copy(
            batchItems = batchFlow.value.batchItems.map { if (it.url == url) it.copy(selected = selected) else it }
        )
    }

    fun setAllBatchItemsSelected(selected: Boolean) {
        batchFlow.value = batchFlow.value.copy(
            batchItems = batchFlow.value.batchItems.map {
                if (it.result != null && !it.queued) it.copy(selected = selected) else it
            }
        )
    }

    fun analyzeBatch() {
        if (batchFlow.value.isAnalyzingBatch || batchFlow.value.isQueueingBatch || isAnalyzingFlow.value) return
        val urls = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
            .findAll(batchFlow.value.batchInput)
            .map { it.value.trimEnd('.', ',', ';', '!', ')', ']', '}') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(50)
            .toList()
        if (urls.isEmpty()) {
            batchFlow.value = batchFlow.value.copy(batchMessage = "Paste one or more video links, one per line.")
            return
        }
        val hasPlaylist = urls.any(youtubeExtractor::isPlaylistUrl)
        val enteredPlaylistStart = batchFlow.value.playlistStartIndex.toIntOrNull() ?: 0
        val enteredPlaylistEnd = batchFlow.value.playlistEndIndex.toIntOrNull() ?: 0
        if (hasPlaylist && (
                enteredPlaylistStart !in 1..1_000_000 ||
                    enteredPlaylistEnd !in enteredPlaylistStart..1_000_000
                )
        ) {
            batchFlow.value = batchFlow.value.copy(
                batchMessage = "Enter a valid playlist range (start at 1, and end at or after the start)."
            )
            return
        }
        val playlistStartIndex = enteredPlaylistStart.takeIf { it in 1..1_000_000 } ?: 1
        val playlistEndIndex = enteredPlaylistEnd.takeIf { it in playlistStartIndex..1_000_000 } ?: 50

        viewModelScope.launch {
            batchFlow.value = batchFlow.value.copy(
                isAnalyzingBatch = true,
                batchMessage = "Expanding playlist items $playlistStartIndex–$playlistEndIndex and analyzing up to 50 videos…",
                batchItems = urls.map { BatchVideoItem(it) }
            )
            var analyzedVideoCount = 0
            urls.forEach { url ->
                val isPlaylist = youtubeExtractor.isPlaylistUrl(url)
                val remaining = (50 - analyzedVideoCount).coerceAtLeast(0)
                if (remaining == 0) {
                    batchFlow.value = batchFlow.value.copy(
                        batchItems = batchFlow.value.batchItems.map {
                            if (it.url == url) it.copy(error = "Batch limit reached (50 videos).") else it
                        }
                    )
                    return@forEach
                }
                val targets = if (isPlaylist && remaining > 0) {
                    runCatching {
                        youtubeExtractor.expandPlaylist(
                            rawUrl = url,
                            startIndex = playlistStartIndex,
                            endIndex = playlistEndIndex,
                            limit = remaining
                        )
                    }.getOrElse { error ->
                        batchFlow.value = batchFlow.value.copy(
                            batchItems = batchFlow.value.batchItems.map {
                                if (it.url == url) it.copy(error = error.message ?: "Could not read this playlist.") else it
                            }
                        )
                        emptyList()
                    }
                } else listOf(url)

                val limitedTargets = targets.take(remaining)
                if (limitedTargets.isNotEmpty() && (limitedTargets.size > 1 || limitedTargets.first() != url)) {
                    batchFlow.value = batchFlow.value.copy(
                        batchItems = batchFlow.value.batchItems.flatMap { item ->
                            if (item.url == url) limitedTargets.map { BatchVideoItem(it) } else listOf(item)
                        }
                    )
                }

                limitedTargets.forEach { videoUrl ->
                    analyzedVideoCount++
                    val outcome = runCatching { analyzerRepository.analyzeUrl(videoUrl) }.getOrNull()
                    val item = when (outcome) {
                        is ExtractionOutcome.Success -> {
                            analysisCache.put(outcome.result.sourceUrl, outcome.result)
                            BatchVideoItem(url = videoUrl, result = outcome.result)
                        }
                        is ExtractionOutcome.Failure -> BatchVideoItem(url = videoUrl, error = outcome.error.userMessage)
                        null -> BatchVideoItem(url = videoUrl, error = "Could not analyze this link.")
                    }
                    batchFlow.value = batchFlow.value.copy(
                        batchItems = batchFlow.value.batchItems.map { if (it.url == videoUrl) item else it }
                    )
                }
            }
            batchFlow.value = batchFlow.value.copy(
                isAnalyzingBatch = false,
                batchMessage = "${batchFlow.value.batchItems.count { it.result != null }} of ${batchFlow.value.batchItems.size} videos ready."
            )
        }
    }

    fun queueSelectedBatch() {
        if (batchFlow.value.isQueueingBatch) return
        val selected = batchFlow.value.batchItems.filter { it.selected && it.result != null && !it.queued }
        if (selected.isEmpty()) {
            batchFlow.value = batchFlow.value.copy(batchMessage = "Select at least one analyzed video.")
            return
        }
        viewModelScope.launch {
            batchFlow.value = batchFlow.value.copy(isQueueingBatch = true, batchMessage = null)
            val targetHeight = settingsRepository.getCurrentSettings().defaultQuality.targetHeight
            var queued = 0
            selected.forEach { item ->
                val result = item.result ?: return@forEach
                val videoStreams = result.streams.filter { !it.isAudioOnly }
                val option = when {
                    videoStreams.isEmpty() -> result.streams.firstOrNull()
                    targetHeight == Int.MAX_VALUE ->
                        videoStreams.firstOrNull { it.isHighestQuality } ?: videoStreams.first()
                    else ->
                        videoStreams.filter { (it.resolutionHeight ?: 0) <= targetHeight }
                            .maxByOrNull { it.resolutionHeight ?: 0 }
                            ?: videoStreams.minByOrNull { it.resolutionHeight ?: Int.MAX_VALUE }
                            ?: videoStreams.first()
                }
                if (option == null) {
                    batchFlow.value = batchFlow.value.copy(
                        batchItems = batchFlow.value.batchItems.map {
                            if (it.url == item.url) it.copy(error = "No downloadable formats were found.") else it
                        }
                    )
                    return@forEach
                }
                val enqueueResult = downloadRepository.enqueueDownload(result, option, null)
                enqueueResult.fold(
                    onSuccess = {
                        queued++
                        batchFlow.value = batchFlow.value.copy(
                            batchItems = batchFlow.value.batchItems.map {
                                if (it.url == item.url) it.copy(queued = true, error = null) else it
                            }
                        )
                    },
                    onFailure = { error ->
                        batchFlow.value = batchFlow.value.copy(
                            batchItems = batchFlow.value.batchItems.map {
                                if (it.url == item.url) it.copy(error = error.message ?: "Could not queue this video.") else it
                            }
                        )
                    }
                )
            }
            batchFlow.value = batchFlow.value.copy(
                isQueueingBatch = false,
                batchMessage = if (queued > 0) "$queued video${if (queued == 1) "" else "s"} added to Downloads."
                else "No videos were added. Check the errors above."
            )
        }
    }
}
