package com.example.ui.screens.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.network.LyricsRepository
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.storage.StorageManager
import com.example.domain.model.AppSettings
import com.example.domain.model.DownloadTask
import com.example.domain.model.EqualizerPreset
import com.example.domain.model.LyricsUiState
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.navigation.AppRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    private val preferencesDataStore: UserPreferencesDataStore,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AppRoute.Player>()
    private var videoPlaylist: List<DownloadTask> = emptyList()
    private var currentIndex: Int = -1

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
    private var lyricsFetchJob: Job? = null

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

    fun loadLyricsForTrack(
        trackId: String?,
        title: String,
        artist: String?,
        durationMs: Long?,
        forceRefresh: Boolean = false
    ) {
        if (trackId.isNullOrBlank() || title.isBlank()) {
            lyricsFetchJob?.cancel()
            _lyricsState.value = LyricsUiState.Idle
            return
        }
        val current = _lyricsState.value
        if (!forceRefresh) {
            if (current is LyricsUiState.Loaded && current.content.trackId == trackId) return
            if (current is LyricsUiState.Loading && current.trackId == trackId) return
        }
        lyricsFetchJob?.cancel()
        lyricsFetchJob = viewModelScope.launch {
            _lyricsState.value = LyricsUiState.Loading(trackId = trackId, title = title)
            _lyricsState.value = lyricsRepository.getLyrics(
                trackId = trackId,
                rawTitle = title,
                rawArtist = artist,
                durationMs = durationMs,
                forceRefresh = forceRefresh
            )
        }
    }

    fun searchLyrics(
        trackId: String,
        customTitle: String,
        customArtist: String?,
        durationMs: Long?
    ) {
        if (trackId.isBlank()) return
        lyricsFetchJob?.cancel()
        lyricsFetchJob = viewModelScope.launch {
            _lyricsState.value = LyricsUiState.Loading(trackId = trackId, title = customTitle)
            _lyricsState.value = lyricsRepository.searchAndSaveLyrics(
                trackId = trackId,
                customTitle = customTitle,
                customArtist = customArtist,
                durationMs = durationMs
            )
        }
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        val loaded = (_lyricsState.value as? LyricsUiState.Loaded) ?: return
        val nextOffset = (loaded.content.offsetMs + deltaMs).coerceIn(-30_000L, 30_000L)
        viewModelScope.launch {
            lyricsRepository.updateOffset(loaded.content.trackId, nextOffset)?.let { updated ->
                _lyricsState.value = updated
            }
        }
    }

    fun resetLyricsOffset() {
        val loaded = (_lyricsState.value as? LyricsUiState.Loaded) ?: return
        if (loaded.content.offsetMs == 0L) return
        viewModelScope.launch {
            lyricsRepository.updateOffset(loaded.content.trackId, 0L)?.let { updated ->
                _lyricsState.value = updated
            }
        }
    }
}
