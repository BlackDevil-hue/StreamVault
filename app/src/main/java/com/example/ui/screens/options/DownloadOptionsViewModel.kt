package com.example.ui.screens.options

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.network.UrlSecurityValidator
import com.example.domain.model.AppSettings
import com.example.domain.model.DownloadTask
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.StorageInfo
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.VideoAnalyzerRepository
import com.example.domain.source.ExtractionOutcome
import com.example.ui.navigation.AppRoute
import com.example.ui.state.AnalysisSessionCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadOptionsUiState(
    val sourceUrl: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val analysis: VideoAnalysisResult? = null,
    val selectedOptionId: String? = null,
    val customFileName: String = "",
    val duplicateExisting: DownloadTask? = null,
    val storageInfo: StorageInfo? = null,
    val settings: AppSettings = AppSettings(),
    val isStartingDownload: Boolean = false
) {
    val selectedOption: MediaStreamOption?
        get() = analysis?.streams?.firstOrNull { it.id == selectedOptionId }
            ?: analysis?.streams?.firstOrNull()
}

sealed interface DownloadOptionsEvent {
    data object NavigateToDownloads : DownloadOptionsEvent
    data class PlayExisting(val downloadId: String) : DownloadOptionsEvent
    data class ShowMessage(val message: String) : DownloadOptionsEvent
}

@HiltViewModel
class DownloadOptionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val analyzerRepository: VideoAnalyzerRepository,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val analysisCache: AnalysisSessionCache,
    private val urlValidator: UrlSecurityValidator
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AppRoute.DownloadOptions>()

    private val _uiState = MutableStateFlow(DownloadOptionsUiState(sourceUrl = route.url))
    val uiState: StateFlow<DownloadOptionsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DownloadOptionsEvent>()
    val events = _events.asSharedFlow()

    init {
        loadAnalysis(route.url)
    }

    fun retryAnalysis() {
        loadAnalysis(route.url, forceRefresh = true)
    }

    private fun loadAnalysis(url: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val settings = settingsRepository.getCurrentSettings()
            val storage = downloadRepository.getStorageInfo()

            val cached = if (!forceRefresh) analysisCache.get(url) else null
            if (cached != null) {
                applySuccess(cached, settings, storage)
                return@launch
            }

            when (val outcome = analyzerRepository.analyzeUrl(url)) {
                is ExtractionOutcome.Success -> {
                    analysisCache.put(url, outcome.result)
                    applySuccess(outcome.result, settings, storage)
                }
                is ExtractionOutcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = outcome.error.userMessage,
                            storageInfo = storage,
                            settings = settings
                        )
                    }
                }
            }
        }
    }

    private suspend fun applySuccess(
        result: VideoAnalysisResult,
        settings: AppSettings,
        storage: StorageInfo
    ) {
        val preferred = pickPreferredStream(result.streams, settings.defaultQuality.targetHeight)
        val duplicate = preferred?.let {
            downloadRepository.findDuplicateCompleted(result.normalizedUrl, it.streamUrl)
        }
        val suggestedName = preferred?.let { option ->
            val rawName = if (option.isAudioOnly) {
                result.audioTitle?.takeIf { it.isNotBlank() } ?: result.title
            } else {
                "${result.title}_${option.qualityLabel}"
            }
            urlValidator.sanitizeFileName(
                rawTitle = rawName,
                extension = option.format.lowercase()
            )
        } ?: ""

        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                analysis = result,
                selectedOptionId = preferred?.id,
                customFileName = suggestedName,
                duplicateExisting = duplicate,
                storageInfo = storage,
                settings = settings
            )
        }
    }

    fun selectStreamOption(option: MediaStreamOption) {
        val analysis = _uiState.value.analysis ?: return
        viewModelScope.launch {
            val duplicate = downloadRepository.findDuplicateCompleted(analysis.normalizedUrl, option.streamUrl)
            val rawName = if (option.isAudioOnly) {
                analysis.audioTitle?.takeIf { it.isNotBlank() } ?: analysis.title
            } else {
                "${analysis.title}_${option.qualityLabel}"
            }
            val suggestedName = urlValidator.sanitizeFileName(
                rawTitle = rawName,
                extension = option.format.lowercase()
            )
            _uiState.update {
                it.copy(
                    selectedOptionId = option.id,
                    customFileName = suggestedName,
                    duplicateExisting = duplicate
                )
            }
        }
    }

    fun onCustomFileNameChanged(name: String) {
        _uiState.update { it.copy(customFileName = name) }
    }

    fun startDownload() {
        val state = _uiState.value
        val analysis = state.analysis ?: return
        val option = state.selectedOption ?: return
        if (state.isStartingDownload) return

        viewModelScope.launch {
            _uiState.update { it.copy(isStartingDownload = true) }
            val result = downloadRepository.enqueueDownload(
                analysis = analysis,
                option = option,
                overrideFileName = state.customFileName.trim().ifBlank { null }
            )
            _uiState.update { it.copy(isStartingDownload = false) }
            result.fold(
                onSuccess = {
                    _events.emit(DownloadOptionsEvent.NavigateToDownloads)
                },
                onFailure = { err ->
                    _events.emit(
                        DownloadOptionsEvent.ShowMessage(
                            err.message ?: "Unable to start download."
                        )
                    )
                }
            )
        }
    }

    fun playExistingDuplicate() {
        val existingId = _uiState.value.duplicateExisting?.id ?: return
        viewModelScope.launch {
            _events.emit(DownloadOptionsEvent.PlayExisting(existingId))
        }
    }

    private fun pickPreferredStream(
        streams: List<MediaStreamOption>,
        targetHeight: Int
    ): MediaStreamOption? {
        if (streams.isEmpty()) return null
        val videoStreams = streams.filter { !it.isAudioOnly }
        if (videoStreams.isEmpty()) return streams.first()
        if (targetHeight == Int.MAX_VALUE) return videoStreams.first()
        return videoStreams
            .filter { (it.resolutionHeight ?: 0) <= targetHeight }
            .maxByOrNull { it.resolutionHeight ?: 0 }
            ?: videoStreams.minByOrNull { it.resolutionHeight ?: Int.MAX_VALUE }
            ?: videoStreams.first()
    }
}
