package com.example.ui.screens.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.AudioLibraryDao
import com.example.data.local.entity.AudioPlaylistEntity
import com.example.data.local.entity.AudioPlaylistTrackEntity
import com.example.data.network.LyricsRepository
import com.example.domain.model.DownloadTask
import com.example.domain.model.EqualizerPreset
import com.example.domain.model.LyricsUiState
import com.example.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class AudioSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title"),
    ARTIST("Artist"),
    DURATION("Duration")
}

data class AudioLibraryUiState(
    val tracks: List<DownloadTask> = emptyList(),
    val playlists: List<AudioPlaylistEntity> = emptyList(),
    val selectedPlaylistId: String? = null,
    val searchQuery: String = "",
    val sort: AudioSort = AudioSort.RECENT,
    val message: String? = null,
    val defaultPlaybackSpeed: Float = 1.0f,
    val seekIntervalSeconds: Int = 10,
    val equalizerEnabled: Boolean = false,
    val equalizerPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val equalizerBandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val bassBoostStrength: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val audioLibraryDao: AudioLibraryDao,
    private val settingsRepository: SettingsRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {
    private val selectedPlaylistId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val sort = MutableStateFlow(AudioSort.RECENT)
    private val message = MutableStateFlow<String?>(null)
    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()
    private var lyricsFetchJob: Job? = null

    private val visibleTracks = selectedPlaylistId.flatMapLatest { playlistId ->
        if (playlistId == null) audioLibraryDao.observeAudioDownloads()
        else audioLibraryDao.observePlaylistTracks(playlistId)
    }.map { entities -> entities.map { it.toDomain() } }

    private val libraryState = combine(
        visibleTracks,
        audioLibraryDao.observePlaylists(),
        selectedPlaylistId,
        searchQuery,
        sort
    ) { tracks, playlists, selectedId, query, selectedSort ->
        val filtered = tracks.filter { track ->
            query.isBlank() || track.title.contains(query, true) || track.artist.orEmpty().contains(query, true)
        }
        val ordered = if (selectedId != null) filtered else when (selectedSort) {
            AudioSort.RECENT -> filtered.sortedByDescending { it.completedAt ?: it.createdAt }
            AudioSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            AudioSort.ARTIST -> filtered.sortedWith(compareBy<DownloadTask> { it.artist.orEmpty().lowercase() }.thenBy { it.title.lowercase() })
            AudioSort.DURATION -> filtered.sortedBy { it.durationMs ?: Long.MAX_VALUE }
        }
        AudioLibraryUiState(ordered, playlists, selectedId, query, selectedSort)
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<AudioLibraryUiState> = combine(
        libraryState,
        message,
        settingsRepository.settings
    ) { state, notice, settings ->
        state.copy(
            message = notice,
            defaultPlaybackSpeed = settings.defaultPlaybackSpeed,
            seekIntervalSeconds = settings.seekIntervalSeconds,
            equalizerEnabled = settings.equalizerEnabled,
            equalizerPreset = settings.equalizerPreset,
            equalizerBandLevelsMb = settings.equalizerBandLevelsMb,
            bassBoostStrength = settings.bassBoostStrength
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AudioLibraryUiState()
    )

    fun selectPlaylist(id: String?) { selectedPlaylistId.value = id }
    fun setSearchQuery(value: String) { searchQuery.value = value }
    fun selectSort(value: AudioSort) { sort.value = value }
    fun clearMessage() { message.value = null }

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

    fun createPlaylist(name: String) {
        val normalizedName = name.trim().take(48)
        if (normalizedName.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val id = UUID.randomUUID().toString()
                audioLibraryDao.createPlaylist(AudioPlaylistEntity(id, normalizedName, System.currentTimeMillis()))
                selectedPlaylistId.value = id
                message.value = "Playlist created."
            }.onFailure { message.value = "A playlist with that name already exists." }
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            audioLibraryDao.deletePlaylist(id)
            if (selectedPlaylistId.value == id) selectedPlaylistId.value = null
            message.value = "Playlist deleted."
        }
    }

    fun addTrackToPlaylist(trackId: String, playlistId: String) {
        viewModelScope.launch {
            audioLibraryDao.addTrack(AudioPlaylistTrackEntity(playlistId, trackId, audioLibraryDao.nextPosition(playlistId)))
            message.value = "Added to playlist."
        }
    }

    fun removeTrackFromPlaylist(trackId: String, playlistId: String) {
        viewModelScope.launch {
            audioLibraryDao.removeTrack(playlistId, trackId)
            message.value = "Removed from playlist."
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
            val current = uiState.value.equalizerBandLevelsMb
            val updated = List(totalBands.coerceAtLeast(1)) { idx ->
                if (idx == bandIndex) levelMb else current.getOrNull(idx) ?: 0
            }
            if (!uiState.value.equalizerEnabled) {
                settingsRepository.setEqualizerEnabled(true)
            }
            settingsRepository.setEqualizerBandLevels(updated)
        }
    }

    fun setBassBoostStrength(strength: Int) {
        viewModelScope.launch {
            if (!uiState.value.equalizerEnabled && strength > 0) {
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
