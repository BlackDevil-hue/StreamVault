package com.example.ui.screens.audio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.audio.AudioPlaybackService
import com.example.audio.EqualizerCapabilities
import com.example.domain.model.DownloadTask
import com.example.domain.model.LyricsUiState
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FormatUtils
import com.example.ui.components.MediaThumbnail
import com.example.ui.components.QualityBadge
import com.example.ui.components.VaultAmbientBackground
import kotlinx.coroutines.delay
import java.io.File

private data class QueueTrackPreview(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioLibraryScreen(viewModel: AudioLibraryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var binder by remember { mutableStateOf<AudioPlaybackService.LocalBinder?>(null) }
    var controller by remember { mutableStateOf<Player?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var playbackDuration by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }

    var currentMediaId by remember { mutableStateOf<String?>(null) }
    var currentTitle by remember { mutableStateOf<String?>(null) }
    var currentArtist by remember { mutableStateOf<String?>(null) }
    var currentArtworkUrl by remember { mutableStateOf<String?>(null) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var queuePreview by remember { mutableStateOf<List<QueueTrackPreview>>(emptyList()) }

    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    var showNowPlayingSheet by remember { mutableStateOf(false) }
    var showLyricsView by remember { mutableStateOf(false) }
    var showLyricsSearchDialog by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTrackTarget by remember { mutableStateOf<DownloadTask?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showPlaylistMenu by remember { mutableStateOf(false) }

    var requestedNotificationPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun ensurePlaybackForeground() {
        if (!requestedNotificationPermission &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestedNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AudioPlaybackService.startForegroundPlayback(context)
    }

    val sleepTimerRemainingMs by (binder?.getSleepTimerRemainingFlow()?.collectAsStateWithLifecycle()
        ?: remember { mutableLongStateOf(0L) })
    val eqCapabilities by (binder?.getEqualizerCapabilitiesFlow()?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(EqualizerCapabilities()) })

    DisposableEffect(context) {
        var isBound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val localBinder = service as? AudioPlaybackService.LocalBinder
                binder = localBinder
                val player = localBinder?.getPlayer()
                controller = player
                if (player != null && player.playbackState == Player.STATE_IDLE) {
                    player.setPlaybackSpeed(uiState.defaultPlaybackSpeed)
                    playbackSpeed = uiState.defaultPlaybackSpeed
                } else if (player != null) {
                    playbackSpeed = player.playbackParameters.speed
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                controller = null
            }
        }
        val intent = Intent(context, AudioPlaybackService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            binder = null
            controller = null
            if (isBound) {
                runCatching { context.unbindService(connection) }
            }
        }
    }

    fun syncPlayerSnapshot(player: Player) {
        isPlaying = player.isPlaying
        val item = player.currentMediaItem
        currentMediaId = item?.mediaId
        currentTitle = item?.mediaMetadata?.title?.toString()
        currentArtist = item?.mediaMetadata?.artist?.toString()
        currentArtworkUrl = item?.mediaMetadata?.artworkUri?.toString()
        currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        shuffleEnabled = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        playbackSpeed = player.playbackParameters.speed
        val count = player.mediaItemCount
        queuePreview = (0 until count).map { idx ->
            val mi = player.getMediaItemAt(idx)
            QueueTrackPreview(
                mediaId = mi.mediaId,
                title = mi.mediaMetadata.title?.toString().orEmpty().ifBlank { "Track ${idx + 1}" },
                artist = mi.mediaMetadata.artist?.toString().orEmpty().ifBlank { "Unknown artist" },
                artworkUrl = mi.mediaMetadata.artworkUri?.toString()
            )
        }
    }

    DisposableEffect(controller) {
        val activeController = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackDuration = activeController.duration.takeIf { it > 0L } ?: playbackDuration
                playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (!isScrubbing) {
                    playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncPlayerSnapshot(activeController)
                playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleEnabled = shuffleModeEnabled
            }
            override fun onRepeatModeChanged(newRepeatMode: Int) {
                repeatMode = newRepeatMode
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                syncPlayerSnapshot(activeController)
            }
        }
        activeController.addListener(listener)
        syncPlayerSnapshot(activeController)
        onDispose { activeController.removeListener(listener) }
    }

    LaunchedEffect(controller, isScrubbing, isPlaying) {
        while (true) {
            val activeController = controller
            if (activeController != null) {
                if (!isScrubbing) {
                    playbackPosition = activeController.currentPosition.coerceAtLeast(0L)
                }
                playbackDuration = activeController.duration.takeIf { it > 0L } ?: 0L
                isPlaying = activeController.isPlaying
            }
            delay(if (isPlaying) 100L else 350L)
        }
    }

    LaunchedEffect(uiState.message) {
        if (uiState.message != null) {
            delay(2500L)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(currentMediaId, currentTitle) {
        val trackId = currentMediaId
        val title = currentTitle.orEmpty()
        if (!trackId.isNullOrBlank() && title.isNotBlank()) {
            var trackDuration = uiState.tracks.firstOrNull { it.id == trackId }?.durationMs?.takeIf { it > 0L }
                ?: controller?.duration?.takeIf { it > 0L }
            if (trackDuration == null) {
                repeat(12) {
                    delay(50L)
                    trackDuration = controller?.duration?.takeIf { it > 0L }
                    if (trackDuration != null) return@repeat
                }
            }
            viewModel.loadLyricsForTrack(
                trackId = trackId,
                title = title,
                artist = currentArtist,
                durationMs = trackDuration
            )
        }
    }

    fun startPlayback(index: Int, shuffle: Boolean = false) {
        val activeController = controller ?: return
        val mediaItems = uiState.tracks.map(::toAudioMediaItem)
        if (mediaItems.isEmpty()) return
        ensurePlaybackForeground()
        activeController.shuffleModeEnabled = shuffle
        activeController.setPlaybackSpeed(playbackSpeed)
        activeController.setMediaItems(mediaItems, index.coerceIn(mediaItems.indices), 0L)
        activeController.prepare()
        activeController.play()
    }

    val seekMs = uiState.seekIntervalSeconds.coerceIn(5, 30) * 1000L

    VaultAmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Audio Library")
                            Text(
                                "${uiState.tracks.size} tracks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showEqualizerSheet = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("audio_equalizer_button")
                        ) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                contentDescription = "Equalizer & Bass Boost",
                                tint = if (uiState.equalizerEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier.size(48.dp)
                            ) {
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
                        IconButton(
                            onClick = { showCreatePlaylist = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Create playlist")
                        }
                        if (uiState.selectedPlaylistId != null) {
                            Box {
                                IconButton(
                                    onClick = { showPlaylistMenu = true },
                                    modifier = Modifier.size(48.dp)
                                ) {
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                if (currentTitle != null) {
                    MiniPlayer(
                        title = currentTitle.orEmpty(),
                        artist = currentArtist.orEmpty(),
                        artworkUrl = currentArtworkUrl,
                        isPlaying = isPlaying,
                        position = playbackPosition,
                        duration = playbackDuration,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        onExpand = { showNowPlayingSheet = true },
                        onToggle = {
                            if (isPlaying) {
                                controller?.pause()
                            } else {
                                ensurePlaybackForeground()
                                controller?.play()
                            }
                        },
                        onNext = { controller?.seekToNextMediaItem() }
                    )
                }
            }
        ) { insets ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 960.dp)
                        .fillMaxSize()
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = { Text("Search songs or artists") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = uiState.selectedPlaylistId == null,
                                onClick = { viewModel.selectPlaylist(null) },
                                label = { Text("All tracks") },
                                leadingIcon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp)) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        items(uiState.playlists, key = { it.id }) { playlist ->
                            FilterChip(
                                selected = uiState.selectedPlaylistId == playlist.id,
                                onClick = { viewModel.selectPlaylist(playlist.id) },
                                label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(16.dp)) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            uiState.sort.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val randomIdx = if (uiState.tracks.isNotEmpty()) {
                                        uiState.tracks.indices.random()
                                    } else 0
                                    startPlayback(randomIdx, shuffle = true)
                                },
                                enabled = uiState.tracks.isNotEmpty(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Icon(Icons.Rounded.Shuffle, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Shuffle")
                            }
                            FilledTonalButton(
                                onClick = { startPlayback(0, shuffle = false) },
                                enabled = uiState.tracks.isNotEmpty(),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play all")
                            }
                        }
                    }
                    if (uiState.message != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(
                                uiState.message.orEmpty(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (uiState.tracks.isEmpty()) {
                        EmptyStatePanel(
                            icon = Icons.Rounded.MusicNote,
                            title = if (uiState.searchQuery.isBlank()) "Your Audio Vault Is Empty" else "No Matching Tracks",
                            subtitle = if (uiState.searchQuery.isBlank()) {
                                "Download audio tracks from supported links to organize playlists and listen offline."
                            } else {
                                "Try a different song title, artist name, or playlist filter."
                            },
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.tracks, key = { it.id }) { track ->
                                val isCurrent = currentMediaId == track.id || (currentMediaId == null && currentTitle == track.title)
                                AudioTrackRow(
                                    track = track,
                                    isCurrent = isCurrent,
                                    isPlaying = isCurrent && isPlaying,
                                    onPlay = {
                                        if (isCurrent) {
                                            showNowPlayingSheet = true
                                        } else {
                                            startPlayback(uiState.tracks.indexOfFirst { it.id == track.id })
                                        }
                                    },
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
        }
    }

    if (showNowPlayingSheet && currentTitle != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showNowPlayingSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            NowPlayingSheetContent(
                title = currentTitle.orEmpty(),
                artist = currentArtist.orEmpty(),
                artworkUrl = currentArtworkUrl,
                isPlaying = isPlaying,
                position = if (isScrubbing) scrubPosition else playbackPosition,
                duration = playbackDuration,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playbackSpeed = playbackSpeed,
                seekSeconds = uiState.seekIntervalSeconds,
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                equalizerEnabled = uiState.equalizerEnabled,
                lyricsState = lyricsState,
                showLyricsView = showLyricsView,
                queue = queuePreview,
                currentIndex = currentIndex,
                onToggleLyricsView = { showLyrics -> showLyricsView = showLyrics },
                onSeekToMs = { targetMs ->
                    val clamped = targetMs.coerceAtLeast(0L)
                    playbackPosition = clamped
                    controller?.seekTo(clamped)
                },
                onRetryLyrics = {
                    val trackId = currentMediaId
                    val title = currentTitle.orEmpty()
                    if (!trackId.isNullOrBlank() && title.isNotBlank()) {
                        val trackDuration = playbackDuration.takeIf { it > 0L }
                            ?: uiState.tracks.firstOrNull { it.id == trackId }?.durationMs
                        viewModel.loadLyricsForTrack(
                            trackId = trackId,
                            title = title,
                            artist = currentArtist,
                            durationMs = trackDuration,
                            forceRefresh = true
                        )
                    }
                },
                onOpenLyricsSearch = { showLyricsSearchDialog = true },
                onAdjustLyricsOffset = viewModel::adjustLyricsOffset,
                onResetLyricsOffset = viewModel::resetLyricsOffset,
                onToggleShuffle = {
                    val next = !shuffleEnabled
                    shuffleEnabled = next
                    controller?.shuffleModeEnabled = next
                },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onRewind = {
                    val active = controller ?: return@NowPlayingSheetContent
                    active.seekTo((active.currentPosition - seekMs).coerceAtLeast(0L))
                },
                onTogglePlay = {
                    if (isPlaying) {
                        controller?.pause()
                    } else {
                        ensurePlaybackForeground()
                        controller?.play()
                    }
                },
                onForward = {
                    val active = controller ?: return@NowPlayingSheetContent
                    val maxDur = active.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    active.seekTo((active.currentPosition + seekMs).coerceAtMost(maxDur))
                },
                onNext = { controller?.seekToNextMediaItem() },
                onCycleRepeat = {
                    val nextMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    repeatMode = nextMode
                    controller?.repeatMode = nextMode
                },
                onScrub = { value ->
                    isScrubbing = true
                    scrubPosition = value.toLong()
                },
                onScrubFinished = {
                    if (isScrubbing) {
                        playbackPosition = scrubPosition
                        controller?.seekTo(scrubPosition)
                    }
                    isScrubbing = false
                },
                onSelectSpeed = { spd ->
                    playbackSpeed = spd
                    controller?.setPlaybackSpeed(spd)
                },
                onSetSleepTimer = { mins ->
                    binder?.setSleepTimerMinutes(mins)
                },
                onOpenEqualizer = {
                    showEqualizerSheet = true
                },
                onSelectQueueItem = { index ->
                    ensurePlaybackForeground()
                    controller?.seekTo(index, 0L)
                    controller?.play()
                }
            )
        }
    }

    if (showLyricsSearchDialog && currentMediaId != null) {
        val activeState = lyricsState
        val initialQueryTitle = when (activeState) {
            is LyricsUiState.Loaded -> activeState.content.queryTitle
            is LyricsUiState.Unavailable -> activeState.queryTitle
            else -> currentTitle.orEmpty()
        }
        val initialQueryArtist = when (activeState) {
            is LyricsUiState.Loaded -> activeState.content.queryArtist.orEmpty()
            is LyricsUiState.Unavailable -> activeState.queryArtist.orEmpty()
            else -> currentArtist.orEmpty().takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
        }
        LyricsSearchDialog(
            initialTitle = initialQueryTitle,
            initialArtist = initialQueryArtist,
            onDismiss = { showLyricsSearchDialog = false },
            onSearch = { searchTitle, searchArtist ->
                showLyricsSearchDialog = false
                showLyricsView = true
                val trackId = currentMediaId ?: return@LyricsSearchDialog
                val trackDuration = playbackDuration.takeIf { it > 0L }
                    ?: uiState.tracks.firstOrNull { it.id == trackId }?.durationMs
                viewModel.searchLyrics(
                    trackId = trackId,
                    customTitle = searchTitle,
                    customArtist = searchArtist.ifBlank { null },
                    durationMs = trackDuration
                )
            }
        )
    }

    if (showEqualizerSheet) {
        val eqSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            sheetState = eqSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            AudioEqualizerSheetContent(
                enabled = uiState.equalizerEnabled,
                preset = uiState.equalizerPreset,
                bandLevelsMb = uiState.equalizerBandLevelsMb,
                bassBoostStrength = uiState.bassBoostStrength,
                capabilities = eqCapabilities,
                onToggleEnabled = viewModel::setEqualizerEnabled,
                onSelectPreset = viewModel::selectEqualizerPreset,
                onBandLevelChanged = { bandIdx, levelMb ->
                    viewModel.setEqualizerBandLevel(bandIdx, levelMb, eqCapabilities.numberOfBands)
                },
                onBassBoostChanged = viewModel::setBassBoostStrength,
                onResetFlat = viewModel::resetEqualizerFlat
            )
        }
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
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
                        TextButton(
                            onClick = {
                                viewModel.addTrackToPlaylist(track.id, playlist.id)
                                addTrackTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
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
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    isInPlaylist: Boolean
) {
    Card(
        onClick = onPlay,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MediaThumbnail(
                thumbnailUrl = track.thumbnailUrl,
                isAudioOnly = true,
                contentDescription = track.title,
                modifier = Modifier.size(52.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    track.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QualityBadge(label = track.format, isHighlighted = isCurrent)
                    Text(
                        FormatUtils.formatDuration(track.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                    if (isPlaying) "Now playing ${track.title}" else "Play ${track.title}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = if (isInPlaylist) onRemoveFromPlaylist else onAddToPlaylist,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    if (isInPlaylist) Icons.Rounded.DeleteOutline else Icons.Rounded.PlaylistAdd,
                    if (isInPlaylist) "Remove from playlist" else "Add to playlist"
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    sleepTimerRemainingMs: Long,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    Card(
        onClick = onExpand,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("audio_mini_player"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediaThumbnail(
                    thumbnailUrl = artworkUrl,
                    isAudioOnly = true,
                    contentDescription = title,
                    modifier = Modifier.size(46.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (artist.isNotBlank()) {
                            Text(
                                artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (sleepTimerRemainingMs > 0L) {
                            Text(
                                "• ${FormatUtils.formatDuration(sleepTimerRemainingMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.ExpandLess, contentDescription = "Open full player")
                }
                Surface(
                    onClick = onToggle,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                IconButton(onClick = onNext, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
                }
            }
            if (duration > 0L) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingSheetContent(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    playbackSpeed: Float,
    seekSeconds: Int,
    sleepTimerRemainingMs: Long,
    equalizerEnabled: Boolean,
    lyricsState: LyricsUiState,
    showLyricsView: Boolean,
    queue: List<QueueTrackPreview>,
    currentIndex: Int,
    onToggleLyricsView: (Boolean) -> Unit,
    onSeekToMs: (Long) -> Unit,
    onRetryLyrics: () -> Unit,
    onOpenLyricsSearch: () -> Unit,
    onAdjustLyricsOffset: (Long) -> Unit,
    onResetLyricsOffset: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectQueueItem: (Int) -> Unit
) {
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cover / Lyrics mode switcher
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !showLyricsView,
                onClick = { onToggleLyricsView(false) },
                label = { Text("Artwork") },
                leadingIcon = { Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("now_playing_cover_tab")
            )
            FilterChip(
                selected = showLyricsView,
                onClick = { onToggleLyricsView(true) },
                label = { Text("Lyrics") },
                leadingIcon = { Icon(Icons.Rounded.Lyrics, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("now_playing_lyrics_tab")
            )
        }

        AnimatedContent(
            targetState = showLyricsView,
            label = "nowPlayingVisualMode"
        ) { lyricsVisible ->
            if (lyricsVisible) {
                LyricsViewport(
                    lyricsState = lyricsState,
                    positionMs = position,
                    onSeekTo = onSeekToMs,
                    onRetry = onRetryLyrics,
                    onOpenSearch = onOpenLyricsSearch,
                    onAdjustOffset = onAdjustLyricsOffset,
                    onResetOffset = onResetLyricsOffset
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    MediaThumbnail(
                        thumbnailUrl = artworkUrl,
                        isAudioOnly = true,
                        contentDescription = title,
                        modifier = Modifier
                            .size(192.dp)
                            .clip(RoundedCornerShape(26.dp))
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (queue.isNotEmpty()) {
                Text(
                    text = "Track ${currentIndex + 1} of ${queue.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (!showLyricsView) {
            LiveLyricPreviewBanner(
                lyricsState = lyricsState,
                positionMs = position,
                onClick = { onToggleLyricsView(true) }
            )
        }

        // Scrubbing Timeline
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (duration > 0L) position.coerceIn(0L, duration).toFloat() else 0f,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..(duration.coerceAtLeast(1L)).toFloat(),
                enabled = duration > 0L
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = FormatUtils.formatDuration(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = FormatUtils.formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Full Transport Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Toggle shuffle",
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onRewind, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.Replay10, contentDescription = "Rewind ${seekSeconds}s")
            }
            Surface(
                onClick = onTogglePlay,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(66.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            IconButton(onClick = onForward, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.Forward10, contentDescription = "Forward ${seekSeconds}s")
            }
            IconButton(onClick = onNext, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = onCycleRepeat, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Cycle repeat mode",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Speed, Sleep Timer & Equalizer Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    onClick = { speedMenuExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "${playbackSpeed}x",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                        DropdownMenuItem(
                            text = { Text("${spd}x") },
                            onClick = {
                                onSelectSpeed(spd)
                                speedMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    onClick = { sleepMenuExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = if (sleepTimerRemainingMs > 0L) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (sleepTimerRemainingMs > 0L) {
                                FormatUtils.formatDuration(sleepTimerRemainingMs)
                            } else {
                                "Timer"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                DropdownMenu(expanded = sleepMenuExpanded, onDismissRequest = { sleepMenuExpanded = false }) {
                    listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "60 minutes")
                        .forEach { (mins, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onSetSleepTimer(mins)
                                    sleepMenuExpanded = false
                                }
                            )
                        }
                }
            }

            Surface(
                onClick = onOpenEqualizer,
                shape = RoundedCornerShape(14.dp),
                color = if (equalizerEnabled) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (equalizerEnabled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("now_playing_equalizer_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (equalizerEnabled) "EQ On" else "EQ",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Queue Preview
        if (queue.size > 1) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Up Next Queue",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(queue) { index, item ->
                        val active = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectQueueItem(index) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (active) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
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
