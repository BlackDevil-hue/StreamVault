package com.example.ui.screens.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.AudioLibraryDao
import com.example.data.local.entity.AudioPlaylistEntity
import com.example.data.local.entity.AudioPlaylistTrackEntity
import com.example.domain.model.DownloadTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val message: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val audioLibraryDao: AudioLibraryDao
) : ViewModel() {
    private val selectedPlaylistId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val sort = MutableStateFlow(AudioSort.RECENT)
    private val message = MutableStateFlow<String?>(null)

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

    val uiState: StateFlow<AudioLibraryUiState> = combine(libraryState, message) { state, notice ->
        state.copy(message = notice)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AudioLibraryUiState()
    )

    fun selectPlaylist(id: String?) { selectedPlaylistId.value = id }
    fun setSearchQuery(value: String) { searchQuery.value = value }
    fun selectSort(value: AudioSort) { sort.value = value }
    fun clearMessage() { message.value = null }

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
}
