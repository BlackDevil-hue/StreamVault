package com.example.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.domain.model.AppSettings
import com.example.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EqualizerCapabilities(
    val isEffectAttached: Boolean = false,
    val numberOfBands: Int = 5,
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val centerFreqsHz: List<Int> = listOf(60, 230, 910, 3600, 14000)
)

/** Owns audio playback independently of Compose screens so music continues in the background. */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class AudioPlaybackService : Service() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    inner class LocalBinder : Binder() {
        fun getPlayer(): Player = player
        fun getSleepTimerRemainingFlow(): StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()
        fun getEqualizerCapabilitiesFlow(): StateFlow<EqualizerCapabilities> = _equalizerCapabilities.asStateFlow()
        fun setSleepTimerMinutes(minutes: Int) = this@AudioPlaybackService.setSleepTimer(minutes)
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var platformSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private lateinit var imageLoader: ImageLoader

    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    private val _equalizerCapabilities = MutableStateFlow(EqualizerCapabilities())
    private var sleepTimerJob: Job? = null
    private var artworkLoadJob: Job? = null
    private var cachedArtworkUri: String? = null
    private var cachedArtworkBitmap: Bitmap? = null
    private var hasBoundClients: Boolean = false
    private var isForegroundActive: Boolean = false
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var boundAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var latestSettings: AppSettings = AppSettings()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncMediaSessionAndNotification()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncMediaSessionAndNotification()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            loadArtworkFor(mediaItem?.mediaMetadata?.artworkUri)
            syncMediaSessionAndNotification()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachAudioEffects(audioSessionId)
            applyAudioEffects(latestSettings)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        imageLoader = ImageLoader(this)
        ensureNotificationChannel()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                addListener(playerListener)
            }

        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                latestSettings = settings
                applyAudioEffects(settings)
            }
        }
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId > 0) {
            attachAudioEffects(player.audioSessionId)
        }

        platformSession = MediaSession(this, "StreamVaultAudio").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }

                override fun onPause() {
                    player.pause()
                }

                override fun onSkipToNext() {
                    player.seekToNextMediaItem()
                }

                override fun onSkipToPrevious() {
                    player.seekToPreviousMediaItem()
                }

                override fun onSeekTo(pos: Long) {
                    player.seekTo(pos)
                    syncPlaybackState()
                }

                override fun onStop() {
                    player.pause()
                    player.seekTo(0L)
                    cancelSleepTimer()
                    ServiceCompat.stopForeground(this@AudioPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        hasBoundClients = true
        return binder
    }

    override fun onRebind(intent: Intent?) {
        hasBoundClients = true
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClients = false
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_FOREGROUND -> {
                promoteToForeground(player.currentMediaItem)
            }
            ACTION_PREV -> {
                promoteToForeground(player.currentMediaItem)
                player.seekToPreviousMediaItem()
            }
            ACTION_TOGGLE -> {
                promoteToForeground(player.currentMediaItem)
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }
            }
            ACTION_NEXT -> {
                promoteToForeground(player.currentMediaItem)
                player.seekToNextMediaItem()
            }
            ACTION_STOP -> {
                player.pause()
                cancelSleepTimer()
                demoteForeground(removeNotification = true)
                stopSelf()
                return START_NOT_STICKY
            }
            null -> {
                if (player.currentMediaItem != null && shouldRemainForeground()) {
                    promoteToForeground(player.currentMediaItem)
                } else if (!hasBoundClients && player.currentMediaItem == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        if (action != null &&
            action != ACTION_START_FOREGROUND &&
            action != ACTION_STOP &&
            player.currentMediaItem == null &&
            !hasBoundClients
        ) {
            demoteForeground(removeNotification = true)
            stopSelf()
            return START_NOT_STICKY
        }

        return if (shouldRemainForeground()) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!shouldRemainForeground()) {
            demoteForeground(removeNotification = true)
            stopSelf()
        } else {
            promoteToForeground(player.currentMediaItem)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelSleepTimer()
        artworkLoadJob?.cancel()
        releaseAudioEffects()
        serviceScope.cancel()
        player.removeListener(playerListener)
        player.release()
        platformSession.isActive = false
        platformSession.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        if (boundAudioSessionId == audioSessionId && equalizer != null) return
        releaseAudioEffects()
        boundAudioSessionId = audioSessionId

        val eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        val bb = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        equalizer = eq
        bassBoost = bb

        if (eq != null) {
            val bands = eq.numberOfBands.toInt().coerceAtLeast(1)
            val range = runCatching { eq.bandLevelRange }.getOrNull()
            val minMb = range?.getOrNull(0)?.toInt() ?: -1500
            val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
            val freqs = (0 until bands).map { idx ->
                runCatching { (eq.getCenterFreq(idx.toShort()) / 1000).coerceAtLeast(20) }.getOrDefault(60)
            }
            _equalizerCapabilities.value = EqualizerCapabilities(
                isEffectAttached = true,
                numberOfBands = bands,
                minLevelMb = minMb,
                maxLevelMb = maxMb,
                centerFreqsHz = freqs
            )
            applyAudioEffects(latestSettings)
        } else {
            _equalizerCapabilities.value = EqualizerCapabilities(isEffectAttached = false)
        }
    }

    private fun applyAudioEffects(settings: AppSettings) {
        val eq = equalizer
        if (eq != null) {
            runCatching {
                eq.enabled = settings.equalizerEnabled
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                val bands = eq.numberOfBands.toInt()
                for (i in 0 until bands) {
                    val targetMb = settings.equalizerBandLevelsMb.getOrNull(i)
                        ?: settings.equalizerPreset.defaultBandsMb.getOrNull(i)
                        ?: 0
                    eq.setBandLevel(i.toShort(), targetMb.coerceIn(minMb, maxMb).toShort())
                }
            }
        }
        val bb = bassBoost
        if (bb != null) {
            runCatching {
                val strength = settings.bassBoostStrength.coerceIn(0, 1000)
                bb.enabled = settings.equalizerEnabled && strength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(strength.toShort())
                }
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        boundAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingMs.value = 0L
            return
        }
        val endTimeMs = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimerRemainingMs.value = minutes * 60_000L
        sleepTimerJob = serviceScope.launch {
            while (true) {
                val remaining = (endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                _sleepTimerRemainingMs.value = remaining
                if (remaining == 0L) {
                    if (player.isPlaying) {
                        player.pause()
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = 0L
    }

    private fun loadArtworkFor(artworkUri: Uri?) {
        val uriString = artworkUri?.toString()
        if (uriString.isNullOrBlank()) {
            cachedArtworkUri = null
            cachedArtworkBitmap = null
            return
        }
        if (uriString == cachedArtworkUri && cachedArtworkBitmap != null) return
        cachedArtworkUri = uriString
        cachedArtworkBitmap = null
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
            val request = ImageRequest.Builder(this@AudioPlaybackService)
                .data(artworkUri)
                .allowHardware(false)
                .size(512)
                .build()
            val result = runCatching { imageLoader.execute(request) }.getOrNull()
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null && cachedArtworkUri == uriString) {
                serviceScope.launch(Dispatchers.Main.immediate) {
                    cachedArtworkBitmap = bitmap
                    syncMediaSessionAndNotification()
                }
            }
        }
    }

    private fun shouldRemainForeground(): Boolean {
        return player.isPlaying ||
            (player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE)
    }

    private fun promoteToForeground(mediaItem: MediaItem?) {
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildMediaNotification(mediaItem),
                fgsType
            )
            isForegroundActive = true
        }
    }

    private fun demoteForeground(removeNotification: Boolean) {
        val flags = if (removeNotification) {
            ServiceCompat.STOP_FOREGROUND_REMOVE
        } else {
            ServiceCompat.STOP_FOREGROUND_DETACH
        }
        ServiceCompat.stopForeground(this, flags)
        isForegroundActive = false
    }

    private fun syncMediaSessionAndNotification() {
        syncMetadata()
        syncPlaybackState()

        val currentItem = player.currentMediaItem
        if (currentItem == null || player.playbackState == Player.STATE_IDLE) {
            demoteForeground(removeNotification = true)
            return
        }

        val notification = buildMediaNotification(currentItem)
        if (shouldRemainForeground()) {
            if (!isForegroundActive) {
                startForegroundPlayback(this)
            }
            promoteToForeground(currentItem)
        } else {
            demoteForeground(removeNotification = false)
            if (canPostNotifications()) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun syncMetadata() {
        val meta = player.currentMediaItem?.mediaMetadata ?: return
        val duration = player.duration.takeIf { it > 0L } ?: -1L
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.title?.toString() ?: "StreamVault Audio")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist?.toString() ?: "Unknown artist")
        if (duration > 0L) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        }
        val activeArtUri = meta.artworkUri?.toString()
        if (activeArtUri != null && activeArtUri == cachedArtworkUri && cachedArtworkBitmap != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedArtworkBitmap)
        }
        platformSession.setMetadata(builder.build())
    }

    private fun syncPlaybackState() {
        val state = when {
            player.isPlaying -> PlaybackState.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            player.playbackState == Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP

        platformSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, player.currentPosition.coerceAtLeast(0L), player.playbackParameters.speed)
                .build()
        )
    }

    @Suppress("DEPRECATION")
    private fun buildMediaNotification(mediaItem: MediaItem?): Notification {
        val title = mediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "StreamVault Audio" }
        val artist = mediaItem?.mediaMetadata?.artist?.toString().orEmpty().ifBlank { "Unknown artist" }

        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Previous",
            servicePendingIntent(ACTION_PREV, 11, foreground = true)
        ).build()

        val toggleAction = Notification.Action.Builder(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (player.isPlaying) "Pause" else "Play",
            servicePendingIntent(ACTION_TOGGLE, 12, foreground = true)
        ).build()

        val nextAction = Notification.Action.Builder(
            android.R.drawable.ic_media_next,
            "Next",
            servicePendingIntent(ACTION_NEXT, 13, foreground = true)
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val activeArtUri = mediaItem?.mediaMetadata?.artworkUri?.toString()
        val largeIcon = if (activeArtUri != null && activeArtUri == cachedArtworkUri) cachedArtworkBitmap else null

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentIntent)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 14))
            .setOnlyAlertOnce(true)
            .setOngoing(player.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(toggleAction)
            .addAction(nextAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(platformSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(
        action: String,
        requestCode: Int,
        foreground: Boolean = false
    ): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background audio library playback controls"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "streamvault_audio_playback"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START_FOREGROUND = "com.example.audio.ACTION_START_FOREGROUND"
        private const val ACTION_PREV = "com.example.audio.ACTION_PREV"
        private const val ACTION_TOGGLE = "com.example.audio.ACTION_TOGGLE"
        private const val ACTION_NEXT = "com.example.audio.ACTION_NEXT"
        private const val ACTION_STOP = "com.example.audio.ACTION_STOP"

        fun startForegroundPlayback(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
