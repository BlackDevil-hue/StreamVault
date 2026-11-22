package com.example.ui.screens.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.storage.StorageManager
import com.example.domain.model.AppSettings
import com.example.domain.model.DownloadTask
import com.example.domain.model.EqualizerPreset
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.navigation.AppRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    val task: DownloadTask? = null,
    val mediaUri: Uri? = null,
    val externalSubtitleUri: Uri? = null,
    val fileMissing: Boolean = false,
    val initialResumePositionMs: Long = 0L,
    val settings: AppSettings = AppSettings(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val nextVideoTitle: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloadRepository: DownloadRepository,
    private val storageManager: StorageManager,
    private val settingsRepository: SettingsRepository,
    private val preferencesDataStore: UserPreferencesDataStore
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AppRoute.Player>()
    private var videoPlaylist: List<DownloadTask> = emptyList()
    private var currentIndex: Int = -1

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { latestSettings ->
                _uiState.update { it.copy(settings = latestSettings) }
            }
        }
        viewModelScope.launch {
            val allCompleted = downloadRepository.completedDownloads.first()
            videoPlaylist = allCompleted.filter { !it.isAudioOnly }
            loadVideoInternal(route.downloadId)
        }
    }

    private suspend fun loadVideoInternal(downloadId: String) {
        val task = downloadRepository.getDownloadById(downloadId)
        if (task == null) {
            _uiState.update { it.copy(isLoading = false, fileMissing = true) }
            return
        }
        currentIndex = videoPlaylist.indexOfFirst { it.id == task.id }
        val uri = storageManager.resolvePlayableUri(task.localUri, task.localPath)
        val exists = storageManager.fileExists(task.localUri, task.localPath)
        val settings = settingsRepository.getCurrentSettings()
        val resumePos = if (settings.rememberPlaybackPosition) {
            preferencesDataStore.getVideoPlaybackPosition(task.id)
        } else {
            0L
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                task = task,
                mediaUri = uri,
                externalSubtitleUri = null,
                fileMissing = uri == null || !exists,
                initialResumePositionMs = resumePos,
                settings = settings,
                hasPrevious = currentIndex > 0,
                hasNext = currentIndex in 0 until (videoPlaylist.size - 1),
                nextVideoTitle = videoPlaylist.getOrNull(currentIndex + 1)?.title
            )
        }
    }

    fun playPrevious() {
        val target = videoPlaylist.getOrNull(currentIndex - 1) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadVideoInternal(target.id)
        }
    }

    fun playNext() {
        val target = videoPlaylist.getOrNull(currentIndex + 1) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadVideoInternal(target.id)
        }
    }

    fun setExternalSubtitle(uri: Uri) {
        _uiState.update { it.copy(externalSubtitleUri = uri) }
    }

    fun savePlaybackPosition(downloadId: String, positionMs: Long, durationMs: Long) {
        if (!_uiState.value.settings.rememberPlaybackPosition) return
        val nearEnd = durationMs > 0L && positionMs >= (durationMs - 3_000L).coerceAtLeast(0L)
        val valueToStore = if (nearEnd || positionMs < 2_000L) 0L else positionMs
        viewModelScope.launch {
            preferencesDataStore.saveVideoPlaybackPosition(downloadId, valueToStore)
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEqualizerEnabled(enabled) }
    }

    fun selectEqualizerPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            settingsRepository.setEqualizerEnabled(true)
            settingsRepository.applyEqualizerPreset(
                preset = preset,
                bandLevelsMb = preset.defaultBandsMb,
                bassStrength = preset.defaultBassStrength
            )
        }
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelMb: Int, totalBands: Int) {
        viewModelScope.launch {
            val current = _uiState.value.settings.equalizerBandLevelsMb
            val updated = List(totalBands.coerceAtLeast(1)) { idx ->
                if (idx == bandIndex) levelMb else current.getOrNull(idx) ?: 0
            }
            if (!_uiState.value.settings.equalizerEnabled) {
                settingsRepository.setEqualizerEnabled(true)
            }
            settingsRepository.setEqualizerBandLevels(updated)
        }
    }

    fun setBassBoostStrength(strength: Int) {
        viewModelScope.launch {
            if (!_uiState.value.settings.equalizerEnabled && strength > 0) {
                settingsRepository.setEqualizerEnabled(true)
            }
            settingsRepository.setBassBoostStrength(strength)
        }
    }

    fun resetEqualizerFlat() {
        viewModelScope.launch {
            settingsRepository.applyEqualizerPreset(
                preset = EqualizerPreset.FLAT,
                bandLevelsMb = EqualizerPreset.FLAT.defaultBandsMb,
                bassStrength = 0
            )
        }
    }
}
