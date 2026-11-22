package com.example.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.audio.AudioPlaybackService
import com.example.audio.EqualizerCapabilities
import com.example.ui.components.FormatUtils
import com.example.ui.components.MediaThumbnail
import com.example.ui.screens.audio.AudioEqualizerSheetContent
import kotlinx.coroutines.delay

private enum class VideoResizeOption(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    isInPictureInPictureMode: Boolean,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var hasEnded by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var hasAppliedInitialSpeed by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var unlockHintVisible by remember { mutableStateOf(false) }
    var resizeOption by remember { mutableStateOf(VideoResizeOption.FIT) }

    var isFullscreen by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var subtitlesEnabled by remember { mutableStateOf(true) }

    var brightnessFraction by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it in 0f..1f } ?: 0.5f
        )
    }
    var volumeFraction by remember {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        mutableFloatStateOf(cur.toFloat() / max.toFloat())
    }
    var gestureHudLabel by remember { mutableStateOf<String?>(null) }
    var hudTriggerTick by remember { mutableIntStateOf(0) }

    val seekIntervalMs = (uiState.settings.seekIntervalSeconds.coerceIn(5, 30)) * 1000L

    val subtitlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.setExternalSubtitle(uri)
        }
    }

    val isAudioMode = uiState.task?.isAudioOnly == true
    val localVideoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    var serviceBinder by remember { mutableStateOf<AudioPlaybackService.LocalBinder?>(null) }
    var serviceAudioPlayer by remember { mutableStateOf<Player?>(null) }

    val eqCapabilities by (serviceBinder?.getEqualizerCapabilitiesFlow()?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(EqualizerCapabilities()) })

    DisposableEffect(context, isAudioMode) {
        if (!isAudioMode) {
            serviceBinder = null
            serviceAudioPlayer = null
            return@DisposableEffect onDispose { }
        }
        var isBound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? AudioPlaybackService.LocalBinder
                serviceBinder = binder
                serviceAudioPlayer = binder?.getPlayer()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinder = null
                serviceAudioPlayer = null
            }
        }
        val intent = Intent(context, AudioPlaybackService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            serviceBinder = null
            serviceAudioPlayer = null
            if (isBound) {
                runCatching { context.unbindService(connection) }
            }
        }
    }

    val activePlayer: Player? = if (isAudioMode) serviceAudioPlayer else localVideoPlayer

    // Release local video ExoPlayer when PlayerScreen leaves composition
    DisposableEffect(localVideoPlayer) {
        onDispose {
            localVideoPlayer.release()
        }
    }

    // Apply default speed from Settings once loaded
    LaunchedEffect(uiState.settings.defaultPlaybackSpeed, activePlayer) {
        val player = activePlayer ?: return@LaunchedEffect
        if (!hasAppliedInitialSpeed) {
            val defaultSpd = uiState.settings.defaultPlaybackSpeed
            playbackSpeed = defaultSpd
            player.setPlaybackSpeed(defaultSpd)
            hasAppliedInitialSpeed = true
        }
    }

    // Bind media URI, resume position, and optional external subtitle
    var loadedTaskId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.task?.id, uiState.mediaUri, uiState.externalSubtitleUri, activePlayer) {
        val player = activePlayer ?: return@LaunchedEffect
        val mediaUri = uiState.mediaUri ?: return@LaunchedEffect
        val task = uiState.task
        val taskId = task?.id
        val isSameVideo = loadedTaskId != null && loadedTaskId == taskId
        val alreadyLoadedInService = isAudioMode &&
            taskId != null &&
            player.currentMediaItem?.mediaId == taskId &&
            player.playbackState != Player.STATE_IDLE

        if (alreadyLoadedInService && !isSameVideo) {
            loadedTaskId = taskId
            hasEnded = player.playbackState == Player.STATE_ENDED
            isPlaying = player.isPlaying
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying) {
                AudioPlaybackService.startForegroundPlayback(context)
            }
            return@LaunchedEffect
        }

        val resumePosition = if (isSameVideo) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            uiState.initialResumePositionMs.coerceAtLeast(0L)
        }

        if (loadedTaskId != null && !isSameVideo) {
            viewModel.savePlaybackPosition(loadedTaskId!!, player.currentPosition, player.duration)
        }
        loadedTaskId = taskId
        hasEnded = false

        val builder = MediaItem.Builder()
            .setMediaId(taskId ?: mediaUri.toString())
            .setUri(mediaUri)

        if (task != null) {
            val metadata = MediaMetadata.Builder()
                .setTitle(task.title)
                .setArtist(task.artist?.takeIf { it.isNotBlank() } ?: "StreamVault Audio")
                .setArtworkUri(task.thumbnailUrl?.takeIf { it.isNotBlank() }?.toUri())
                .build()
            builder.setMediaMetadata(metadata)
        }

        uiState.externalSubtitleUri?.let { subUri ->
            val subPath = subUri.toString().lowercase()
            val subMime = if (subPath.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(subMime)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subConfig))
        }

        if (isAudioMode) {
            AudioPlaybackService.startForegroundPlayback(context)
        }
        player.setMediaItem(builder.build())
        if (resumePosition > 0L) player.seekTo(resumePosition)
        player.prepare()
        player.playWhenReady = true
    }

    // Listen to active Player state (only save position & detach listener on dispose)
    DisposableEffect(activePlayer) {
        val player = activePlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) hasEnded = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (player.duration > 0L) {
                    durationMs = player.duration
                }
                if (playbackState == Player.STATE_ENDED) {
                    hasEnded = true
                    uiState.task?.id?.let { id ->
                        viewModel.savePlaybackPosition(id, 0L, player.duration)
                    }
                    if (uiState.settings.autoPlayNextVideo && uiState.hasNext) {
                        viewModel.playNext()
                    } else {
                        controlsVisible = true
                    }
                } else {
                    hasEnded = false
                }
            }
        }
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        if (player.duration > 0L) durationMs = player.duration
        player.addListener(listener)
        onDispose {
            uiState.task?.id?.let { id ->
                viewModel.savePlaybackPosition(id, player.currentPosition, player.duration)
            }
            player.removeListener(listener)
        }
    }

    LaunchedEffect(activePlayer, isPlaying, isScrubbing) {
        val player = activePlayer ?: return@LaunchedEffect
        while (true) {
            if (!isScrubbing) {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            }
            if (player.duration > 0L) durationMs = player.duration
            isPlaying = player.isPlaying
            delay(350L)
        }
    }

    // Auto-hide controls after 3.5s during playback
    LaunchedEffect(controlsVisible, isPlaying, showSpeedSheet, showSubtitleSheet, showAudioSheet, isLocked) {
        if (controlsVisible && isPlaying && !showSpeedSheet && !showSubtitleSheet && !showAudioSheet && !isLocked) {
            delay(3500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(unlockHintVisible) {
        if (unlockHintVisible) {
            delay(2500L)
            unlockHintVisible = false
        }
    }

    LaunchedEffect(hudTriggerTick) {
        if (gestureHudLabel != null) {
            delay(1200L)
            gestureHudLabel = null
        }
    }

    // Pause video on background unless entering PiP; keep audio-only playing in AudioPlaybackService
    DisposableEffect(lifecycleOwner, isInPictureInPictureMode, isAudioMode, activePlayer, uiState.task?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val player = activePlayer ?: return@LifecycleEventObserver
                uiState.task?.id?.let { id ->
                    viewModel.savePlaybackPosition(id, player.currentPosition, player.duration)
                }
                if (!isInPictureInPictureMode && !isAudioMode) {
                    player.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Immersive fullscreen & orientation cleanup
    DisposableEffect(isFullscreen, isLandscape, activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isFullscreen || isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        onDispose {
            activity?.window?.let { win ->
                WindowCompat.getInsetsController(win, win.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Ensure orientation & brightness reset when leaving PlayerScreen
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = -1f
                win.attributes = lp
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            uiState.fileMissing || uiState.mediaUri == null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Video file unavailable",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = "The file may have been moved or deleted from device storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                    Button(onClick = onBack) {
                        Text("Back to History")
                    }
                }
            }

            else -> {
                if (!isAudioMode) {
                    // ExoPlayer Surface for video items
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = localVideoPlayer
                                useController = false
                                resizeMode = resizeOption.mode
                                keepScreenOn = true
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { view ->
                            if (view.player !== localVideoPlayer) view.player = localVideoPlayer
                            if (view.resizeMode != resizeOption.mode) view.resizeMode = resizeOption.mode
                            view.keepScreenOn = isPlaying
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (uiState.task?.isAudioOnly == true && !isInPictureInPictureMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        Color(0xFF070B14)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            MediaThumbnail(
                                thumbnailUrl = uiState.task?.thumbnailUrl,
                                isAudioOnly = true,
                                contentDescription = uiState.task?.title ?: "Audio",
                                modifier = Modifier.size(180.dp)
                            )
                            uiState.task?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.LightGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Gesture Layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isLocked, seekIntervalMs) {
                            detectTapGestures(
                                onTap = {
                                    if (isLocked) {
                                        unlockHintVisible = !unlockHintVisible
                                    } else {
                                        controlsVisible = !controlsVisible
                                    }
                                },
                                onDoubleTap = { offset ->
                                    if (isLocked) {
                                        unlockHintVisible = true
                                        return@detectTapGestures
                                    }
                                    val halfWidth = size.width / 2f
                                    val sec = uiState.settings.seekIntervalSeconds
                                    val player = activePlayer ?: return@detectTapGestures
                                    if (offset.x < halfWidth) {
                                        player.seekTo((player.currentPosition - seekIntervalMs).coerceAtLeast(0L))
                                        gestureHudLabel = "-${sec}s"
                                    } else {
                                        val maxDur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                        player.seekTo((player.currentPosition + seekIntervalMs).coerceAtMost(maxDur))
                                        gestureHudLabel = "+${sec}s"
                                    }
                                    hudTriggerTick++
                                }
                            )
                        }
                        .pointerInput(isLocked) {
                            detectVerticalDragGestures { change, dragAmount ->
                                if (isLocked) return@detectVerticalDragGestures
                                change.consume()
                                val delta = -dragAmount / (size.height.coerceAtLeast(1).toFloat())
                                val isLeftSide = change.position.x < size.width / 2f
                                if (isLeftSide) {
                                    brightnessFraction = (brightnessFraction + delta * 1.4f).coerceIn(0.05f, 1.0f)
                                    activity?.window?.let { win ->
                                        val lp = win.attributes
                                        lp.screenBrightness = brightnessFraction
                                        win.attributes = lp
                                    }
                                    gestureHudLabel = "Brightness ${(brightnessFraction * 100).toInt()}%"
                                } else {
                                    volumeFraction = (volumeFraction + delta * 1.4f).coerceIn(0f, 1f)
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val targetVol = (volumeFraction * maxVol).toInt().coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                    gestureHudLabel = "Volume ${(volumeFraction * 100).toInt()}%"
                                }
                                hudTriggerTick++
                            }
                        }
                )

                if (isBuffering && !isInPictureInPictureMode) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Locked Screen Unlock Pill
                AnimatedVisibility(
                    visible = isLocked && unlockHintVisible && !isInPictureInPictureMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(bottom = 32.dp)
                ) {
                    Surface(
                        onClick = {
                            isLocked = false
                            unlockHintVisible = false
                            controlsVisible = true
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("player_unlock_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Tap to unlock screen", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                // Gesture HUD Indicator
                AnimatedVisibility(
                    visible = gestureHudLabel != null && !isInPictureInPictureMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.80f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val label = gestureHudLabel.orEmpty()
                            if (label.startsWith("Brightness")) {
                                Icon(Icons.Rounded.BrightnessHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            } else if (label.startsWith("Volume")) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Custom Compose Player Controls Overlay
                AnimatedVisibility(
                    visible = controlsVisible && !isLocked && !isInPictureInPictureMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.78f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.86f)
                                    )
                                )
                            )
                            .safeDrawingPadding()
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                    .testTag("player_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uiState.task?.title ?: "Video Player",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                uiState.task?.resolutionLabel?.let { res ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                    ) {
                                        Text(
                                            text = "$res • ${uiState.task?.format.orEmpty()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            if (isAudioMode) {
                                IconButton(
                                    onClick = { showEqualizerSheet = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                        .testTag("player_equalizer_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        contentDescription = "Equalizer & Bass Boost",
                                        tint = if (uiState.settings.equalizerEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White
                                        }
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showAudioSheet = true },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                    .testTag("player_audio_tracks_button")
                            ) {
                                Icon(Icons.Rounded.Audiotrack, contentDescription = "Audio & details", tint = Color.White)
                            }
                            IconButton(
                                onClick = { showSubtitleSheet = true },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                    .testTag("player_subtitles_button")
                            ) {
                                Icon(Icons.Rounded.ClosedCaption, contentDescription = "Subtitles", tint = Color.White)
                            }
                            IconButton(
                                onClick = { showSpeedSheet = true },
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                    .testTag("player_speed_button")
                            ) {
                                Icon(Icons.Rounded.Speed, contentDescription = "Playback speed", tint = Color.White)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                                IconButton(
                                    onClick = {
                                        val params = PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                        activity.enterPictureInPictureMode(params)
                                    },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color.Black.copy(alpha = 0.42f), CircleShape)
                                        .testTag("player_pip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        // Center Transport Controls (Prev Video, Rewind, Play/Pause/Replay, Forward, Next Video)
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.hasPrevious) {
                                IconButton(
                                    onClick = { viewModel.playPrevious() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                                        .testTag("player_prev_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.SkipPrevious,
                                        contentDescription = "Previous video",
                                        tint = Color.White
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val player = activePlayer ?: return@IconButton
                                    player.seekTo((player.currentPosition - seekIntervalMs).coerceAtLeast(0L))
                                    gestureHudLabel = "-${uiState.settings.seekIntervalSeconds}s"
                                    hudTriggerTick++
                                },
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(Color.Black.copy(alpha = 0.52f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                    .testTag("player_rewind_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay10,
                                    contentDescription = "Rewind ${uiState.settings.seekIntervalSeconds} seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val player = activePlayer ?: return@IconButton
                                    if (hasEnded) {
                                        if (isAudioMode) AudioPlaybackService.startForegroundPlayback(context)
                                        player.seekTo(0L)
                                        player.play()
                                    } else if (player.isPlaying) {
                                        player.pause()
                                    } else {
                                        if (isAudioMode) AudioPlaybackService.startForegroundPlayback(context)
                                        player.play()
                                    }
                                },
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                    .testTag("player_play_pause")
                            ) {
                                Icon(
                                    imageVector = when {
                                        hasEnded -> Icons.Rounded.Replay
                                        isPlaying -> Icons.Rounded.Pause
                                        else -> Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = when {
                                        hasEnded -> "Replay"
                                        isPlaying -> "Pause"
                                        else -> "Play"
                                    },
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val player = activePlayer ?: return@IconButton
                                    val maxDur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                    player.seekTo((player.currentPosition + seekIntervalMs).coerceAtMost(maxDur))
                                    gestureHudLabel = "+${uiState.settings.seekIntervalSeconds}s"
                                    hudTriggerTick++
                                },
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(Color.Black.copy(alpha = 0.52f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                    .testTag("player_forward_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forward10,
                                    contentDescription = "Forward ${uiState.settings.seekIntervalSeconds} seconds",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            if (uiState.hasNext) {
                                IconButton(
                                    onClick = { viewModel.playNext() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                                        .testTag("player_next_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.SkipNext,
                                        contentDescription = "Next video",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        // Bottom Timeline + Lock / Aspect / Speed / Orientation / Fullscreen Bar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (hasEnded && uiState.nextVideoTitle != null) {
                                Surface(
                                    onClick = { viewModel.playNext() },
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.Black.copy(alpha = 0.72f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        .padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = "Up next: ${uiState.nextVideoTitle}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            val displayedPosMs = if (isScrubbing) scrubPositionMs else currentPositionMs
                            Slider(
                                value = if (durationMs > 0L) {
                                    (displayedPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                } else 0f,
                                onValueChange = { fraction ->
                                    if (durationMs > 0L) {
                                        isScrubbing = true
                                        scrubPositionMs = (fraction * durationMs).toLong()
                                    }
                                },
                                onValueChangeFinished = {
                                    if (durationMs > 0L && isScrubbing) {
                                        currentPositionMs = scrubPositionMs
                                        activePlayer?.seekTo(scrubPositionMs)
                                    }
                                    isScrubbing = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${FormatUtils.formatDuration(displayedPosMs)} / ${FormatUtils.formatDuration(durationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            isLocked = true
                                            controlsVisible = false
                                            unlockHintVisible = true
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("player_lock_button")
                                    ) {
                                        Icon(Icons.Rounded.Lock, contentDescription = "Lock screen", tint = Color.White)
                                    }
                                    TextButton(
                                        onClick = {
                                            val nextIdx = (resizeOption.ordinal + 1) % VideoResizeOption.entries.size
                                            resizeOption = VideoResizeOption.entries[nextIdx]
                                            gestureHudLabel = "Aspect: ${resizeOption.label}"
                                            hudTriggerTick++
                                        },
                                        modifier = Modifier.testTag("player_aspect_button")
                                    ) {
                                        Icon(
                                            Icons.Rounded.AspectRatio,
                                            contentDescription = "Cycle aspect ratio",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(resizeOption.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                    TextButton(onClick = { showSpeedSheet = true }) {
                                        Text("${playbackSpeed}x", color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(
                                        onClick = { isLandscape = !isLandscape },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("player_landscape_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ScreenRotation,
                                            contentDescription = "Toggle landscape mode",
                                            tint = Color.White
                                        )
                                    }
                                    IconButton(
                                        onClick = { isFullscreen = !isFullscreen },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("player_fullscreen_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isFullscreen) {
                                                Icons.Rounded.FullscreenExit
                                            } else {
                                                Icons.Rounded.Fullscreen
                                            },
                                            contentDescription = "Toggle fullscreen",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Playback Speed Sheet
    if (showSpeedSheet) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Playback Speed", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(6.dp))
                speeds.forEach { spd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playbackSpeed = spd
                                activePlayer?.setPlaybackSpeed(spd)
                                showSpeedSheet = false
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = kotlin.math.abs(playbackSpeed - spd) < 0.01f,
                            onClick = {
                                playbackSpeed = spd
                                activePlayer?.setPlaybackSpeed(spd)
                                showSpeedSheet = false
                            }
                        )
                        Text(
                            text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Audio Track & Video Details Sheet
    if (showAudioSheet) {
        ModalBottomSheet(onDismissRequest = { showAudioSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Audio Tracks & Media Info", style = MaterialTheme.typography.titleLarge)
                uiState.task?.let { task ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(task.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${task.resolutionLabel} • ${task.format} • ${FormatUtils.formatBytes(task.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                val audioGroups = activePlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }.orEmpty()
                if (audioGroups.isNotEmpty()) {
                    Text("Audio Track", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    audioGroups.forEachIndexed { idx, group ->
                        val fmt = group.getTrackFormat(0)
                        val label = buildString {
                            append(fmt.label ?: fmt.language?.uppercase() ?: "Track ${idx + 1}")
                            if (fmt.channelCount > 0) append(" (${fmt.channelCount}ch)")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activePlayer?.let { player ->
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                            .build()
                                    }
                                    showAudioSheet = false
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = group.isSelected,
                                onClick = {
                                    activePlayer?.let { player ->
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                            .build()
                                    }
                                    showAudioSheet = false
                                }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Text(
                        text = "Single default audio stream active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Subtitles Sheet
    if (showSubtitleSheet) {
        ModalBottomSheet(onDismissRequest = { showSubtitleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Subtitles & Captions", style = MaterialTheme.typography.titleLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Display subtitles when available", style = MaterialTheme.typography.bodyLarge)
                    FilledTonalButton(
                        onClick = {
                            subtitlesEnabled = !subtitlesEnabled
                            activePlayer?.let { player ->
                                val paramsBuilder = player.trackSelectionParameters.buildUpon()
                                paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                                player.trackSelectionParameters = paramsBuilder.build()
                            }
                        }
                    ) {
                        Text(if (subtitlesEnabled) "On" else "Off")
                    }
                }

                val textGroups = activePlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }.orEmpty()
                if (textGroups.isNotEmpty()) {
                    textGroups.forEachIndexed { idx, group ->
                        val format = group.getTrackFormat(0)
                        val label = format.label ?: format.language ?: "Subtitle Track ${idx + 1}"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = group.isSelected && subtitlesEnabled,
                                onClick = {
                                    subtitlesEnabled = true
                                    activePlayer?.let { player ->
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                            .build()
                                    }
                                    showSubtitleSheet = false
                                }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Text(
                        text = "No embedded subtitle tracks detected in this video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        showSubtitleSheet = false
                        subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Subtitles, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Load External Subtitle (.srt / .vtt)")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showEqualizerSheet) {
        val eqSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEqualizerSheet = false },
            sheetState = eqSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            AudioEqualizerSheetContent(
                enabled = uiState.settings.equalizerEnabled,
                preset = uiState.settings.equalizerPreset,
                bandLevelsMb = uiState.settings.equalizerBandLevelsMb,
                bassBoostStrength = uiState.settings.bassBoostStrength,
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
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
