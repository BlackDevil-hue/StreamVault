package com.example.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.local.dao.DownloadDao
import com.example.data.network.DirectMediaExtractor
import com.example.data.network.YouTubeStreamSpec
import com.example.data.network.YoutubeCookieProvider
import com.example.data.network.YoutubeDlRuntime
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.storage.StorageManager
import com.example.domain.model.DownloadStatus
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@HiltWorker
class VideoDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val okHttpClient: OkHttpClient,
    private val storageManager: StorageManager,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val notificationHelper: DownloadNotificationHelper,
    private val youtubeDlRuntime: YoutubeDlRuntime,
    private val youtubeCookieProvider: YoutubeCookieProvider
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return@withContext Result.failure()
        val entity = downloadDao.getById(downloadId) ?: return@withContext Result.failure()

        if (entity.status != DownloadStatus.DOWNLOADING.name) {
            return@withContext Result.success()
        }

        val isYtDlpDownload = entity.streamUrl.startsWith("yt-dlp://")
        val initialYtDlpBytes = if (isYtDlpDownload) {
            storageManager.getYtDlpDownloadedBytes(downloadId)
        } else {
            0L
        }
        if (isYtDlpDownload && initialYtDlpBytes > 0L) {
            downloadDao.updateYtDlpPausedProgress(downloadId, initialYtDlpBytes, DownloadStatus.DOWNLOADING.name)
        }

        val settings = preferencesDataStore.getCurrent()
        val notificationId = downloadId.hashCode()
        val stagingFile = storageManager.getStagingFile(downloadId)
        var existingBytes = if (stagingFile.exists()) stagingFile.length() else 0L
        val resumedBytes = if (isYtDlpDownload) initialYtDlpBytes else existingBytes
        val initialPct = if (entity.totalBytes > 0L && resumedBytes > 0L) {
            ((resumedBytes * 100L) / entity.totalBytes).toInt().coerceIn(0, 99)
        } else {
            entity.reportedProgressPercent?.coerceIn(0, 99) ?: 0
        }
        val initialText = if (entity.totalBytes > 0L && resumedBytes > 0L) {
            "${formatBytesSimple(resumedBytes)} / ${formatBytesSimple(entity.totalBytes)} • Resuming…"
        } else {
            "Connecting…"
        }

        notificationHelper.cancelProgress(downloadId)
        runCatching {
            setForeground(
                createForegroundInfo(
                    notificationId = notificationId,
                    title = entity.title,
                    percent = initialPct,
                    speedText = initialText,
                    indeterminate = entity.totalBytes <= 0L && initialPct <= 0
                )
            )
        }

        downloadDao.updateStatus(downloadId, DownloadStatus.DOWNLOADING.name, null)

        if (entity.totalBytes > 0L && existingBytes > 0L) {
            val remaining = (entity.totalBytes - existingBytes).coerceAtLeast(0L)
            if (!storageManager.hasEnoughStorage(remaining)) {
                val msg = "Not enough free storage space to complete this download."
                downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                notificationHelper.notifyFailed(downloadId, entity.title, msg, settings.notificationsEnabled)
                return@withContext Result.failure()
            }
        }

        try {
            if (entity.streamUrl.startsWith("yt-dlp://")) {
                val parsedYouTube = YouTubeStreamSpec.parse(entity.streamUrl)
                val isYouTube = entity.streamUrl.startsWith("yt-dlp://youtube/")
                if (isYouTube && parsedYouTube == null) {
                    throw IOException("Invalid YouTube video reference.")
                }
                val isYouTubeAudio = parsedYouTube?.isAudioOnly == true
                val targetHeight = parsedYouTube?.targetHeight
                val inputUrl = if (parsedYouTube != null) {
                    "https://www.youtube.com/watch?v=${parsedYouTube.videoId}"
                } else {
                    val encodedUrl = entity.streamUrl.substringAfter("yt-dlp://social/")
                        .takeIf { entity.streamUrl.startsWith("yt-dlp://social/") }
                        ?: throw IOException("Invalid social video reference.")
                    runCatching {
                        String(
                            android.util.Base64.decode(
                                encodedUrl,
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                            ),
                            Charsets.UTF_8
                        )
                    }.getOrElse { throw IOException("Invalid social video reference.") }
                }
                // FFmpeg is needed to mux separate video/audio tracks and to encode MP3 audio.
                youtubeDlRuntime.ensureInitialized(needsFfmpeg = true)
                youtubeDlRuntime.ensureLatestYoutubeDl()
                // Keep yt-dlp's .part files in durable app storage so Pause/Resume continues
                // the existing transfer instead of clearing it on the next worker run.
                val tempDir = storageManager.getYtDlpWorkingDirectory(downloadId).apply { mkdirs() }
                val lastDbUpdate = AtomicLong(0L)
                val lastForegroundUpdate = AtomicLong(0L)
                val lastReportedTotal = AtomicLong(entity.totalBytes.coerceAtLeast(0L))
                val wasFinalizing = AtomicBoolean(false)
                val qualityResolved = AtomicBoolean(
                    entity.resolutionHeight != null &&
                        entity.resolutionHeight > 0 &&
                        !entity.resolutionLabel.contains("Best available", ignoreCase = true)
                )
                val progressTracker = YtDlpProgressTracker(
                    expectedBytes = entity.totalBytes.coerceAtLeast(0L),
                    initialDownloadedBytes = initialYtDlpBytes,
                    initialProgressPercent = entity.reportedProgressPercent,
                    fileLengthProvider = { path -> File(path).length() }
                )

                fun buildYtDlpRequest(extractorArgs: String?): YoutubeDLRequest =
                    YoutubeDLRequest(inputUrl).apply {
                        addOption("--no-playlist")
                        addOption("--continue")
                        addOption("--newline")
                        addOption("--progress")
                        addOption("--no-warnings")
                        addOption("--retries", "5")
                        addOption("--fragment-retries", "5")
                        if (isYouTube && extractorArgs != null) {
                            addOption("--extractor-args", extractorArgs)
                        }
                        if (isYouTubeAudio) {
                            // Prefer a dedicated audio track, but allow extraction from a combined
                            // format when YouTube does not expose a separate audio-only format.
                            addOption("-f", "bestaudio/best")
                            addOption("--extract-audio")
                            addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "320K")
                            addOption("--embed-thumbnail")
                            addOption("--embed-metadata")
                            // Prefer track title and performer metadata for embedded MP3 ID3 tags.
                            addOption("--parse-metadata", "%(track,alt_title,title)s:%(meta_title)s")
                            addOption("--parse-metadata", "%(artist,artists|)s:%(meta_artist)s")
                            val metadataArgs = buildList {
                                val tagTitle = sanitizeFfmpegMetadataValue(entity.title)
                                if (tagTitle.isNotEmpty()) {
                                    add("-metadata")
                                    add("title=${shlexQuote(tagTitle)}")
                                }
                                val tagArtist = entity.artist?.let(::sanitizeFfmpegMetadataValue).orEmpty()
                                if (tagArtist.isNotEmpty()) {
                                    add("-metadata")
                                    add("artist=${shlexQuote(tagArtist)}")
                                }
                            }
                            if (metadataArgs.isNotEmpty()) {
                                addOption("--postprocessor-args", "Metadata:${metadataArgs.joinToString(" ")}")
                            }
                        } else {
                            val videoSelector = if (targetHeight != null && targetHeight > 0) {
                                "bestvideo[height=$targetHeight][vcodec^=avc1]+bestaudio[acodec^=mp4a.40]/" +
                                    "bestvideo[height=$targetHeight][vcodec^=avc1]+bestaudio[ext=m4a]/" +
                                    "bestvideo[height=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/" +
                                    "bestvideo[height=$targetHeight]+bestaudio/" +
                                    "bestvideo[height<=$targetHeight][vcodec^=avc1]+bestaudio[acodec^=mp4a.40]/" +
                                    "bestvideo[height<=$targetHeight]+bestaudio/" +
                                    "best[height<=$targetHeight]/best"
                            } else {
                                "bestvideo+bestaudio/best"
                            }
                            addOption("-f", videoSelector)
                            addOption("--merge-output-format", "mp4")
                        }
                        addOption("-o", File(tempDir, "%(id)s.%(ext)s").absolutePath)
                    }

                fun executeAttempt(extractorArgs: String?, useCookies: Boolean) {
                    val request = buildYtDlpRequest(extractorArgs)
                    val cookieFile = if (useCookies) {
                        youtubeCookieProvider.attachCookies(request, inputUrl)
                    } else {
                        null
                    }
                    try {
                        YoutubeDL.getInstance().execute(request, downloadId) { progress, etaSeconds, outputLine ->
                            if (isStopped) return@execute
                            val snapshot = progressTracker.update(progress, etaSeconds, outputLine)
                            val resolvedHeightFromLine = if (!isYouTubeAudio && !qualityResolved.get()) {
                                (YouTubeStreamSpec.resolveHeightFromFormatSpec(snapshot.detectedFormatSpec) ?: targetHeight)
                                    ?.takeIf { it > 0 }
                            } else {
                                null
                            }
                            val shouldPersistResolvedQuality = resolvedHeightFromLine != null &&
                                qualityResolved.compareAndSet(false, true)
                            val now = System.currentTimeMillis()
                            val enteredFinalizing = snapshot.finalizing && wasFinalizing.compareAndSet(false, true)
                            val discoveredNewTotal = snapshot.totalBytes > 0L && lastReportedTotal.get() != snapshot.totalBytes
                            val shouldUpdateDb = now - lastDbUpdate.get() >= 500L ||
                                enteredFinalizing ||
                                discoveredNewTotal ||
                                shouldPersistResolvedQuality
                            if (shouldUpdateDb) {
                                lastDbUpdate.set(now)
                                lastReportedTotal.set(snapshot.totalBytes)
                                val shouldUpdateForeground = now - lastForegroundUpdate.get() >= 1000L || enteredFinalizing
                                if (shouldUpdateForeground) {
                                    lastForegroundUpdate.set(now)
                                }
                                runBlocking(Dispatchers.IO) {
                                    if (isStopped) return@runBlocking
                                    if (shouldPersistResolvedQuality && resolvedHeightFromLine != null) {
                                        downloadDao.updateResolvedQuality(
                                            id = downloadId,
                                            resolutionLabel = com.example.domain.model.VideoQualityTier.formatLabelFromHeight(resolvedHeightFromLine),
                                            resolutionHeight = resolvedHeightFromLine
                                        )
                                    }
                                    downloadDao.updateYtDlpProgress(
                                        id = downloadId,
                                        bytesDownloaded = snapshot.bytesDownloaded,
                                        totalBytes = snapshot.totalBytes,
                                        speed = snapshot.speedBytesPerSec,
                                        percent = snapshot.percent,
                                        eta = snapshot.etaSeconds
                                    )
                                    if (shouldUpdateForeground) {
                                        runCatching {
                                            setForeground(
                                                createForegroundInfo(
                                                    notificationId = notificationId,
                                                    title = entity.title,
                                                    percent = snapshot.percent,
                                                    speedText = when {
                                                        snapshot.finalizing -> "Finalizing media…"
                                                        snapshot.totalBytes > 0L && snapshot.speedBytesPerSec > 0L ->
                                                            "${formatBytesSimple(snapshot.bytesDownloaded)} / ${formatBytesSimple(snapshot.totalBytes)} • ${formatBytesSimple(snapshot.speedBytesPerSec)}/s"
                                                        snapshot.speedBytesPerSec > 0L ->
                                                            "${formatBytesSimple(snapshot.bytesDownloaded)} • ${formatBytesSimple(snapshot.speedBytesPerSec)}/s"
                                                        snapshot.totalBytes > 0L ->
                                                            "${formatBytesSimple(snapshot.bytesDownloaded)} / ${formatBytesSimple(snapshot.totalBytes)}"
                                                        else -> "Connecting to stream…"
                                                    },
                                                    indeterminate = snapshot.totalBytes <= 0L && snapshot.percent <= 0
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        cookieFile?.delete()
                    }
                }

                // CoroutineWorker.onStopped() is final. Watch its stopped flag separately so a
                // blocking yt-dlp call can still be terminated when WorkManager pauses/cancels it.
                val stopWatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                stopWatcherScope.launch {
                    while (!isStopped) delay(100L)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(downloadId) }
                }
                try {
                    try {
                        executeAttempt(
                            extractorArgs = if (isYouTube) YoutubeDlRuntime.YOUTUBE_EXTRACTOR_ARGS else null,
                            useCookies = true
                        )
                    } catch (primaryError: Exception) {
                        if (!isYouTube || isStopped || !YoutubeDlRuntime.isBotVerificationError(primaryError.message.orEmpty())) {
                            throw primaryError
                        }
                        try {
                            executeAttempt(
                                extractorArgs = YoutubeDlRuntime.YOUTUBE_COOKIES_FALLBACK_ARGS,
                                useCookies = true
                            )
                        } catch (cookieFallbackError: Exception) {
                            if (isStopped || !YoutubeDlRuntime.isBotVerificationError(cookieFallbackError.message.orEmpty())) {
                                throw cookieFallbackError
                            }
                            executeAttempt(
                                extractorArgs = YoutubeDlRuntime.YOUTUBE_COOKIELESS_FALLBACK_ARGS,
                                useCookies = false
                            )
                        }
                    }
                } finally {
                    stopWatcherScope.cancel()
                }
                if (isStopped) {
                    preserveStoppedDownload(downloadId, entity, stagingFile)
                    return@withContext Result.success()
                }
                val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "ts", "avi")
                val audioExtensions = setOf("m4a", "webm", "opus", "ogg", "mp3", "aac")
                val intermediateTrackRegex = Regex(".*\\.f\\d+\\.[^.]+$", RegexOption.IGNORE_CASE)
                val completedFiles = tempDir.listFiles()
                    ?.filter {
                        it.isFile &&
                            !it.name.endsWith(".part", ignoreCase = true) &&
                            !it.name.endsWith(".ytdl", ignoreCase = true) &&
                            !it.name.contains(".temp.", ignoreCase = true)
                    }
                    .orEmpty()
                val downloadedFile = if (isYouTubeAudio) {
                    completedFiles.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
                        ?: completedFiles.firstOrNull { it.extension.lowercase() in audioExtensions }
                } else {
                    val videoCandidates = completedFiles.filter { it.extension.lowercase() in videoExtensions }
                    val mergedCandidates = videoCandidates.filterNot { intermediateTrackRegex.matches(it.name) }
                    if (mergedCandidates.isNotEmpty()) {
                        mergedCandidates.maxByOrNull { it.length() }
                    } else {
                        val hasUnmergedAudioTrack = completedFiles.any {
                            intermediateTrackRegex.matches(it.name) && it.extension.lowercase() in audioExtensions
                        }
                        if (hasUnmergedAudioTrack) {
                            throw IOException("Failed to merge YouTube video and audio tracks.")
                        }
                        videoCandidates.maxByOrNull { it.length() }
                    }
                }
                    ?: throw IOException("yt-dlp did not create a completed media file.")
                if (downloadedFile.length() <= 0L) throw IOException("Downloaded media file is empty.")
                if (!storageManager.hasEnoughStorage(downloadedFile.length())) {
                    throw IOException("Not enough free storage space to complete this download.")
                }
                val actualExtension = downloadedFile.extension.lowercase().ifBlank { if (isYouTubeAudio) "mp3" else "mp4" }
                val outputFileName = "${entity.fileName.substringBeforeLast('.', entity.fileName)}.$actualExtension"
                val outputMimeType = if (isYouTubeAudio) {
                    when (actualExtension) {
                        "m4a", "mp4" -> "audio/mp4"
                        "webm" -> "audio/webm"
                        "opus", "ogg" -> "audio/ogg"
                        "mp3" -> "audio/mpeg"
                        else -> "audio/$actualExtension"
                    }
                } else {
                    when (actualExtension) {
                        "mp4", "m4v" -> "video/mp4"
                        "webm" -> "video/webm"
                        "mkv" -> "video/x-matroska"
                        "mov" -> "video/quicktime"
                        "avi" -> "video/x-msvideo"
                        "3gp" -> "video/3gpp"
                        "flv" -> "video/x-flv"
                        "ts" -> "video/mp2t"
                        else -> entity.mimeType
                    }
                }
                stagingFile.parentFile?.mkdirs()
                if (stagingFile.exists()) stagingFile.delete()
                if (!downloadedFile.renameTo(stagingFile)) {
                    downloadedFile.copyTo(stagingFile, overwrite = true)
                }
                tempDir.deleteRecursively()

                val committed = storageManager.commitDownloadedFile(
                    stagingFile = stagingFile,
                    fileName = outputFileName,
                    mimeType = outputMimeType,
                    resolutionLabel = if (isYouTubeAudio) "Audio Only (${actualExtension.uppercase()})" else entity.resolutionLabel,
                    resolutionHeight = entity.resolutionHeight,
                    isAudioOnly = entity.isAudioOnly,
                    settings = settings
                )
                val resolvedHeight = committed.detectedHeight ?: entity.resolutionHeight
                val finalResolutionLabel = if (isYouTubeAudio) {
                    "Audio Only (${actualExtension.uppercase()})"
                } else {
                    com.example.domain.model.VideoQualityTier.formatLabelFromHeight(resolvedHeight)
                }
                downloadDao.markCompleted(
                    id = downloadId,
                    finalBytes = committed.finalBytes,
                    localUri = committed.uriString,
                    localPath = committed.displayPath,
                    fileName = committed.finalFileName,
                    sha256 = committed.sha256,
                    resolutionLabel = finalResolutionLabel,
                    resolutionHeight = resolvedHeight,
                    format = actualExtension.uppercase(),
                    mimeType = outputMimeType,
                    durationMs = committed.detectedDurationMs ?: entity.durationMs,
                    completedAt = System.currentTimeMillis(),
                    artist = committed.detectedArtist
                )
                notificationHelper.notifyCompleted(
                    downloadId = downloadId,
                    title = entity.title,
                    resolution = finalResolutionLabel,
                    notificationsEnabled = settings.notificationsEnabled
                )
                return@withContext Result.success()
            }

            val requestBuilder = Request.Builder()
                .url(entity.streamUrl)
                .header("User-Agent", DirectMediaExtractor.USER_AGENT)

            if (existingBytes > 0L && entity.supportsRange) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
                entity.eTag?.let { requestBuilder.header("If-Range", it) }
            } else if (existingBytes > 0L && !entity.supportsRange) {
                // Server does not support range requests; restart clean
                stagingFile.delete()
                existingBytes = 0L
            }

            val call = okHttpClient.newCall(requestBuilder.build())
            val httpStopWatcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            httpStopWatcher.launch {
                while (!isStopped) delay(100L)
                call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (response.code in listOf(401, 403)) {
                        val msg = "Access denied by server (HTTP ${response.code})."
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                        notificationHelper.notifyFailed(downloadId, entity.title, msg, settings.notificationsEnabled)
                        return@withContext Result.failure()
                    }
                    if (response.code == 416) {
                        // Range not satisfiable -> restart from 0
                        stagingFile.delete()
                        existingBytes = 0L
                        return@withContext Result.retry()
                    }
                    if (!response.isSuccessful) {
                        val msg = "Server returned HTTP ${response.code}."
                        if (response.code >= 500) {
                            downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                            return@withContext Result.retry()
                        }
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                        notificationHelper.notifyFailed(downloadId, entity.title, msg, settings.notificationsEnabled)
                        return@withContext Result.failure()
                    }

                    val isPartial = response.code == 206
                    if (!isPartial && existingBytes > 0L) {
                        // Server ignored Range and sent 200 OK full stream
                        stagingFile.delete()
                        existingBytes = 0L
                    }

                    val body = response.body ?: throw IOException("Empty media stream response.")
                    val contentLength = body.contentLength()
                    val totalFromRange = response.header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                    val totalBytes = when {
                        isPartial && totalFromRange != null -> totalFromRange
                        isPartial && contentLength > 0L -> existingBytes + contentLength
                        contentLength > 0L -> contentLength
                        entity.totalBytes > 0L -> entity.totalBytes
                        else -> 0L
                    }

                    if (totalBytes > 0L && !storageManager.hasEnoughStorage((totalBytes - existingBytes).coerceAtLeast(0L))) {
                        val msg = "Insufficient storage space (${formatBytesSimple(totalBytes)} required)."
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                        notificationHelper.notifyFailed(downloadId, entity.title, msg, settings.notificationsEnabled)
                        return@withContext Result.failure()
                    }

                    downloadDao.updateActiveProgress(
                        id = downloadId,
                        bytesDownloaded = existingBytes,
                        totalBytes = if (totalBytes > 0L) totalBytes.coerceAtLeast(existingBytes) else 0L,
                        speed = 0L,
                        eta = 0L
                    )

                    var downloaded = existingBytes
                    var windowBytes = 0L
                    var lastUpdateMs = System.currentTimeMillis()
                    var lastNotifyMs = 0L
                    val buffer = ByteArray(32 * 1024)

                    FileOutputStream(stagingFile, isPartial).use { output ->
                        body.byteStream().use { input ->
                            while (true) {
                                if (isStopped) {
                                    output.flush()
                                    preserveStoppedDownload(downloadId, entity, stagingFile)
                                    return@withContext Result.success()
                                }

                                val read = input.read(buffer)
                                if (read == -1) break

                                output.write(buffer, 0, read)
                                downloaded += read
                                windowBytes += read

                                val now = System.currentTimeMillis()
                                val elapsedMs = now - lastUpdateMs
                                if (elapsedMs >= 500L && !isStopped) {
                                    val speedBps = (windowBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                                    val remaining = if (totalBytes > downloaded) totalBytes - downloaded else 0L
                                    val etaSec = if (speedBps > 0L && remaining > 0L) remaining / speedBps else 0L

                                    downloadDao.updateActiveProgress(
                                        id = downloadId,
                                        bytesDownloaded = downloaded,
                                        totalBytes = if (totalBytes > 0L) totalBytes.coerceAtLeast(downloaded) else 0L,
                                        speed = speedBps,
                                        eta = etaSec
                                    )

                                    if (now - lastNotifyMs >= 1000L && !isStopped) {
                                        val pct = if (totalBytes > 0L) {
                                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                        } else {
                                            0
                                        }
                                        val speedLabel = if (totalBytes > 0L) {
                                            "${formatBytesSimple(downloaded)} / ${formatBytesSimple(totalBytes)} • ${formatBytesSimple(speedBps)}/s"
                                        } else {
                                            "${formatBytesSimple(downloaded)} • ${formatBytesSimple(speedBps)}/s"
                                        }
                                        runCatching {
                                            setForeground(
                                                createForegroundInfo(
                                                    notificationId = notificationId,
                                                    title = entity.title,
                                                    percent = pct,
                                                    speedText = speedLabel,
                                                    indeterminate = totalBytes <= 0L
                                                )
                                            )
                                        }
                                        lastNotifyMs = now
                                    }

                                    windowBytes = 0L
                                    lastUpdateMs = now
                                }
                            }
                        }
                        output.flush()
                    }

                    if (isStopped) {
                        preserveStoppedDownload(downloadId, entity, stagingFile)
                        return@withContext Result.success()
                    }

                    // File integrity verification
                    val actualLen = stagingFile.length()
                    if (actualLen <= 0L) {
                        throw IOException("Downloaded file is empty.")
                    }
                    if (totalBytes > 0L && actualLen < totalBytes) {
                        downloadDao.updateProgress(
                            id = downloadId,
                            bytesDownloaded = actualLen,
                            totalBytes = totalBytes,
                            speed = 0L,
                            eta = 0L,
                            status = DownloadStatus.PAUSED.name
                        )
                        return@withContext Result.retry()
                    }

                    downloadDao.updateActiveProgress(
                        id = downloadId,
                        bytesDownloaded = actualLen,
                        totalBytes = if (totalBytes > 0L) totalBytes.coerceAtLeast(actualLen) else actualLen,
                        speed = 0L,
                        eta = 0L
                    )

                    // Commit staging file to MediaStore / SAF / final location
                    val committed = storageManager.commitDownloadedFile(
                        stagingFile = stagingFile,
                        fileName = entity.fileName,
                        mimeType = entity.mimeType,
                        resolutionLabel = entity.resolutionLabel,
                        resolutionHeight = entity.resolutionHeight,
                        isAudioOnly = entity.isAudioOnly,
                        settings = settings
                    )

                    val finalResolutionLabel = if (!entity.isAudioOnly && committed.detectedHeight != null) {
                        com.example.domain.model.VideoQualityTier.formatLabelFromHeight(committed.detectedHeight)
                    } else {
                        entity.resolutionLabel
                    }

                    downloadDao.markCompleted(
                        id = downloadId,
                        finalBytes = committed.finalBytes,
                        localUri = committed.uriString,
                        localPath = committed.displayPath,
                        fileName = committed.finalFileName,
                        sha256 = committed.sha256,
                        resolutionLabel = finalResolutionLabel,
                        resolutionHeight = committed.detectedHeight ?: entity.resolutionHeight,
                        format = entity.format,
                        mimeType = entity.mimeType,
                        durationMs = committed.detectedDurationMs,
                        completedAt = System.currentTimeMillis()
                    )

                    notificationHelper.notifyCompleted(
                        downloadId = downloadId,
                        title = entity.title,
                        resolution = finalResolutionLabel,
                        notificationsEnabled = settings.notificationsEnabled
                    )
                }
            } finally {
                httpStopWatcher.cancel()
            }

            Result.success()
        } catch (ce: CancellationException) {
            preserveStoppedDownload(downloadId, entity, stagingFile)
            Result.success()
        } catch (io: IOException) {
            if (isStopped) {
                preserveStoppedDownload(downloadId, entity, stagingFile)
                return@withContext Result.success()
            }
            val latest = downloadDao.getById(downloadId)
            val currentBytes = if (isYtDlpDownload) {
                storageManager.getYtDlpDownloadedBytes(downloadId).takeIf { it > 0L }
                    ?: (latest?.bytesDownloaded ?: 0L)
            } else {
                if (stagingFile.exists()) stagingFile.length() else (latest?.bytesDownloaded ?: 0L)
            }
            if (isYtDlpDownload) {
                downloadDao.updateYtDlpPausedProgress(downloadId, currentBytes, DownloadStatus.FAILED.name)
            } else {
                downloadDao.updateProgress(
                    id = downloadId,
                    bytesDownloaded = currentBytes,
                    totalBytes = (latest?.totalBytes ?: 0L).let { if (it > 0L) it.coerceAtLeast(currentBytes) else 0L },
                    speed = 0L,
                    eta = 0L,
                    status = DownloadStatus.FAILED.name
                )
            }
            val errMsg = "Connection interrupted: ${io.message ?: "network error"}"
            downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, errMsg)
            notificationHelper.notifyFailed(downloadId, entity.title, errMsg, settings.notificationsEnabled)
            if (settings.autoRetryFailed && runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (e: Exception) {
            if (isStopped) {
                preserveStoppedDownload(downloadId, entity, stagingFile)
                return@withContext Result.success()
            }
            val errMsg = e.message ?: "Download failed unexpectedly."
            downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, errMsg)
            notificationHelper.notifyFailed(downloadId, entity.title, errMsg, settings.notificationsEnabled)
            Result.failure()
        }
    }

    private suspend fun preserveStoppedDownload(
        downloadId: String,
        entity: com.example.data.local.entity.DownloadEntity,
        stagingFile: File
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val latest = downloadDao.getById(downloadId)
        if (latest == null || latest.status == DownloadStatus.CANCELLED.name) {
            storageManager.deleteStagingFile(downloadId)
            storageManager.deleteYtDlpWorkingFiles(downloadId)
            return@withContext
        }
        // The row may already have been resumed while this worker was stopping. In that case
        // leave it alone so this old worker cannot turn the new run back into PAUSED.
        if (latest.status != DownloadStatus.PAUSED.name) return@withContext

        if (entity.streamUrl.startsWith("yt-dlp://")) {
            val partialBytes = storageManager.getYtDlpDownloadedBytes(downloadId)
            downloadDao.updateYtDlpPausedProgress(downloadId, partialBytes, DownloadStatus.PAUSED.name)
        } else {
            val partialBytes = if (stagingFile.exists()) stagingFile.length() else latest.bytesDownloaded
            downloadDao.updateProgress(
                id = downloadId,
                bytesDownloaded = partialBytes,
                totalBytes = latest.totalBytes.coerceAtLeast(partialBytes),
                speed = 0L,
                eta = 0L,
                status = DownloadStatus.PAUSED.name
            )
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        percent: Int,
        speedText: String,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = notificationHelper.buildProgressNotification(
            title = title,
            progressPercent = percent,
            speedText = speedText,
            downloadId = inputData.getString(KEY_DOWNLOAD_ID).orEmpty(),
            indeterminate = indeterminate
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun formatBytesSimple(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private fun sanitizeFfmpegMetadataValue(raw: String): String =
        raw.replace('\u0000', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun shlexQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        const val KEY_DOWNLOAD_ID = "key_download_id"
    }
}
