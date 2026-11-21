package com.example.ui.screens.audio

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.audio.AudioPlaybackService
import com.example.domain.model.DownloadTask
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioLibraryScreen(viewModel: AudioLibraryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var playbackDuration by remember { mutableLongStateOf(0L) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentArtist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTrackTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showPlaylistMenu by remember { mutableStateOf(false) }

    val controllerFuture = remember(context) {
        MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        ).buildAsync()
    }
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            { runCatching { controllerFuture.get() }.onSuccess { controller = it } },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            controller = null
            MediaController.releaseFuture(controllerFuture)
        }
    }
    DisposableEffect(controller) {
        val activeController = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = mediaItem?.mediaMetadata?.title?.toString()
                currentArtist = mediaItem?.mediaMetadata?.artist?.toString()
            }
        }
        activeController.addListener(listener)
        isPlaying = activeController.isPlaying
        currentTitle = activeController.currentMediaItem?.mediaMetadata?.title?.toString()
        currentArtist = activeController.currentMediaItem?.mediaMetadata?.artist?.toString()
        onDispose { activeController.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            val activeController = controller
            if (activeController != null) {
                playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
                playbackDuration = activeController.duration.takeIf { it > 0L } ?: 0L
                isPlaying = activeController.isPlaying
            }
            delay(400L)
        }
    }
    LaunchedEffect(uiState.message) {
        if (uiState.message != null) {
            delay(2500L)
            viewModel.clearMessage()
        }
    }

    fun startPlayback(index: Int) {
        val activeController = controller ?: return
        val mediaItems = uiState.tracks.map(::toAudioMediaItem)
        if (mediaItems.isEmpty()) return
        activeController.setMediaItems(mediaItems, index.coerceIn(mediaItems.indices), 0L)
        activeController.prepare()
        activeController.play()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Audio Library")
                        Text("${uiState.tracks.size} tracks", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Rounded.Sort, contentDescription = "Sort audio")
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            AudioSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = { viewModel.selectSort(option); sortMenuExpanded = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showCreatePlaylist = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create playlist")
                    }
                    if (uiState.selectedPlaylistId != null) {
                        Box {
                            IconButton(onClick = { showPlaylistMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options")
                            }
                            DropdownMenu(expanded = showPlaylistMenu, onDismissRequest = { showPlaylistMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete playlist") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        uiState.selectedPlaylistId?.let(viewModel::deletePlaylist)
                                        showPlaylistMenu = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (currentTitle != null) {
                MiniPlayer(
                    title = currentTitle.orEmpty(),
                    artist = currentArtist.orEmpty(),
                    isPlaying = isPlaying,
                    position = playbackPosition,
                    duration = playbackDuration,
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onToggle = { if (isPlaying) controller?.pause() else controller?.play() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onSeek = { controller?.seekTo(it.toLong()) }
                )
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search songs or artists") },
                leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedPlaylistId == null,
                        onClick = { viewModel.selectPlaylist(null) },
                        label = { Text("All tracks") },
                        leadingIcon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp)) }
                    )
                }
                items(uiState.playlists, key = { it.id }) { playlist ->
                    FilterChip(
                        selected = uiState.selectedPlaylistId == playlist.id,
                        onClick = { viewModel.selectPlaylist(playlist.id) },
                        label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(16.dp)) }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(uiState.sort.label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { startPlayback(0) }, enabled = uiState.tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Text("Play all")
                }
            }
            if (uiState.message != null) {
                Text(uiState.message.orEmpty(), Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            if (uiState.tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.MusicNote, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (uiState.searchQuery.isBlank()) "Your audio downloads will appear here" else "No matching tracks")
                        Text("Download audio to build your music library.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.tracks, key = { it.id }) { track ->
                        AudioTrackRow(
                            track = track,
                            onPlay = { startPlayback(uiState.tracks.indexOfFirst { it.id == track.id }) },
                            onAddToPlaylist = { addTrackTarget = track },
                            onRemoveFromPlaylist = {
                                uiState.selectedPlaylistId?.let { viewModel.removeTrackFromPlaylist(track.id, it) }
                            },
                            isInPlaylist = uiState.selectedPlaylistId != null
                        )
                    }
                }
            }
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(value = playlistName, onValueChange = { playlistName = it },
                    label = { Text("Playlist name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(playlistName)
                    playlistName = ""
                    showCreatePlaylist = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } }
        )
    }
    addTrackTarget?.let { track ->
        AlertDialog(
            onDismissRequest = { addTrackTarget = null },
            title = { Text("Add to playlist") },
            text = {
                if (uiState.playlists.isEmpty()) Text("Create a playlist first using the + button.")
                else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    uiState.playlists.forEach { playlist ->
                        TextButton(onClick = {
                            viewModel.addTrackToPlaylist(track.id, playlist.id)
                            addTrackTarget = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.QueueMusic, null)
                            Spacer(Modifier.size(8.dp))
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addTrackTarget = null }) { Text("Done") } }
        )
    }
}

@Composable
private fun AudioTrackRow(
    track: DownloadTask,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    isInPlaylist: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${FormatUtils.formatDuration(track.durationMs)}  ·  ${track.format}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play ${track.title}") }
            IconButton(onClick = if (isInPlaylist) onRemoveFromPlaylist else onAddToPlaylist) {
                Icon(if (isInPlaylist) Icons.Rounded.DeleteOutline else Icons.Rounded.PlaylistAdd,
                    if (isInPlaylist) "Remove from playlist" else "Add to playlist")
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous track") }
                IconButton(onClick = onToggle) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) "Pause" else "Play") }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Next track") }
            }
            if (duration > 0L) {
                Slider(value = position.coerceIn(0L, duration).toFloat(), onValueChange = onSeek,
                    valueRange = 0f..duration.toFloat(), modifier = Modifier.height(26.dp))
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun toAudioMediaItem(task: DownloadTask): MediaItem {
    val uri = task.localUri?.let(Uri::parse) ?: task.localPath?.let { File(it).toUri() }
        ?: Uri.EMPTY
    val metadata = MediaMetadata.Builder()
        .setTitle(task.title)
        .setArtist(task.artist ?: "Unknown artist")
        .apply { task.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
        .build()
    return MediaItem.Builder().setMediaId(task.id).setUri(uri).setMediaMetadata(metadata).build()
}
