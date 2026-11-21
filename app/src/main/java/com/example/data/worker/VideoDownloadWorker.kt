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
        if (isYtDlpDownload) {
            // Clear estimates saved by older app versions before starting/retrying this transfer.
            // The tracker repopulates size and bytes from yt-dlp's live stream output.
            downloadDao.resetYtDlpProgress(downloadId)
        }

        val settings = preferencesDataStore.getCurrent()
        val notificationId = downloadId.hashCode()

        runCatching {
            setForeground(
                createForegroundInfo(
                    notificationId = notificationId,
                    title = entity.title,
                    percent = 0,
                    speedText = "Connecting...",
                    indeterminate = true
                )
            )
        }

        downloadDao.updateStatus(downloadId, DownloadStatus.DOWNLOADING.name, null)

        val stagingFile = storageManager.getStagingFile(downloadId)
        var existingBytes = if (stagingFile.exists()) stagingFile.length() else 0L

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
                val isYouTube = entity.streamUrl.startsWith("yt-dlp://youtube/")
                val isYouTubeAudio = entity.streamUrl.startsWith("yt-dlp://youtube/audio/")
                val inputUrl = if (isYouTube) {
                    val videoId = entity.streamUrl.substringAfterLast('/')
                        .takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                        ?: throw IOException("Invalid YouTube video reference.")
                    "https://www.youtube.com/watch?v=$videoId"
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
                val request = YoutubeDLRequest(inputUrl).apply {
                    addOption("--no-playlist")
                    addOption("--continue")
                    if (isYouTube) addOption("--extractor-args", YoutubeDlRuntime.YOUTUBE_EXTRACTOR_ARGS)
                    if (isYouTubeAudio) {
                        // Prefer a dedicated audio track, but allow extraction from a combined
                        // format when YouTube does not expose a separate audio-only format.
                        addOption("-f", "bestaudio/best")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "320K")
                        addOption("--embed-thumbnail")
                        addOption("--embed-metadata")
                        // Use track performer metadata only; uploader may be a label or producer.
                        addOption("--parse-metadata", "%(artist,artists|)s:%(meta_artist)s")
                    } else {
                        addOption("-f", "bestvideo+bestaudio/best")
                        addOption("--merge-output-format", "mp4")
                        if (isYouTube) addOption("--embed-thumbnail")
                    }
                    addOption("-o", File(tempDir, "%(id)s.%(ext)s").absolutePath)
                }
                val cookieFile = youtubeCookieProvider.attachCookies(request, inputUrl)
                val lastProgressUpdate = AtomicLong(0L)
                val progressTracker = YtDlpProgressTracker(
                    expectedBytes = 0L,
                    initialDownloadedBytes = storageManager.getYtDlpDownloadedBytes(downloadId),
                    initialProgressPercent = null
                )
                // CoroutineWorker.onStopped() is final. Watch its stopped flag separately so a
                // blocking yt-dlp call can still be terminated when WorkManager pauses/cancels it.
                val stopWatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                stopWatcherScope.launch {
                    while (!isStopped) delay(100L)
                    runCatching { YoutubeDL.getInstance().destroyProcessById(downloadId) }
                }
                try {
                    YoutubeDL.getInstance().execute(request, downloadId) { progress, etaSeconds, outputLine ->
                        val snapshot = progressTracker.update(progress, etaSeconds, outputLine)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate.get() >= 750L || progress >= 100f || snapshot.finalizing) {
                            lastProgressUpdate.set(now)
                            runBlocking(Dispatchers.IO) {
                                downloadDao.updateYtDlpProgress(
                                    id = downloadId,
                                    bytesDownloaded = snapshot.bytesDownloaded,
                                    totalBytes = snapshot.totalBytes,
                                    speed = snapshot.speedBytesPerSec,
                                    percent = snapshot.percent,
                                    eta = snapshot.etaSeconds,
                                    status = DownloadStatus.DOWNLOADING.name
                                )
                                setForeground(
                                    createForegroundInfo(
                                        notificationId = notificationId,
                                        title = entity.title,
                                        percent = snapshot.percent,
                                        speedText = when {
                                            snapshot.finalizing -> "Finalizing media…"
                                            snapshot.speedBytesPerSec > 0L -> "${formatBytesSimple(snapshot.speedBytesPerSec)}/s"
                                            else -> "Waiting for stream…"
                                        },
                                        indeterminate = snapshot.totalBytes <= 0L
                                    )
                                )
                            }
                        }
                    }
                } finally {
                    stopWatcherScope.cancel()
                    cookieFile?.delete()
                }
                if (isStopped) {
                    preserveStoppedDownload(downloadId, entity, stagingFile)
                    return@withContext Result.success()
                }
                val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "ts", "avi")
                val completedFiles = tempDir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") }
                    .orEmpty()
                val downloadedFile = if (isYouTubeAudio) {
                    completedFiles.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
                } else {
                    completedFiles.filter { it.extension.lowercase() in videoExtensions }
                        .maxByOrNull { it.length() }
                }
                    ?: throw IOException("yt-dlp did not create a completed video file.")
                if (downloadedFile.length() <= 0L) throw IOException("Downloaded video is empty.")
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
                downloadedFile.copyTo(stagingFile, overwrite = true)
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
                val finalResolutionLabel = if (isYouTubeAudio) {
                    "Audio Only (${actualExtension.uppercase()})"
                } else {
                    com.example.domain.model.VideoQualityTier.formatLabelFromHeight(committed.detectedHeight)
                }
                downloadDao.markCompleted(
                    id = downloadId,
                    finalBytes = committed.finalBytes,
                    localUri = committed.uriString,
                    localPath = committed.displayPath,
                    fileName = committed.finalFileName,
                    sha256 = committed.sha256,
                    resolutionLabel = finalResolutionLabel,
                    resolutionHeight = committed.detectedHeight,
                    format = actualExtension.uppercase(),
                    mimeType = outputMimeType,
                    durationMs = committed.detectedDurationMs,
                    completedAt = System.currentTimeMillis()
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

            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
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
                val totalBytes = when {
                    isPartial && contentLength > 0L -> existingBytes + contentLength
                    contentLength > 0L -> contentLength
                    entity.totalBytes > 0L -> entity.totalBytes
                    else -> -1L
                }

                if (totalBytes > 0L && !storageManager.hasEnoughStorage((totalBytes - existingBytes).coerceAtLeast(0L))) {
                    val msg = "Insufficient storage space (${formatBytesSimple(totalBytes)} required)."
                    downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, msg)
                    notificationHelper.notifyFailed(downloadId, entity.title, msg, settings.notificationsEnabled)
                    return@withContext Result.failure()
                }

                var downloaded = existingBytes
                var windowBytes = 0L
                var lastUpdateMs = System.currentTimeMillis()
                var lastNotifyMs = 0L
                val buffer = ByteArray(32 * 1024)

                FileOutputStream(stagingFile, isPartial).use { output ->
                    body.byteStream().use { input ->
                        while (true) {
                            if (isStopped) {
                                // Check whether user paused or cancelled
                                val latest = downloadDao.getById(downloadId)
                                if (latest?.status == DownloadStatus.CANCELLED.name) {
                                    stagingFile.delete()
                                } else if (latest?.status != DownloadStatus.PAUSED.name) {
                                    downloadDao.updateProgress(
                                        id = downloadId,
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes.coerceAtLeast(downloaded),
                                        speed = 0L,
                                        eta = 0L,
                                        status = DownloadStatus.PAUSED.name
                                    )
                                }
                                return@withContext Result.success()
                            }

                            val read = input.read(buffer)
                            if (read == -1) break

                            output.write(buffer, 0, read)
                            downloaded += read
                            windowBytes += read

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastUpdateMs
                            if (elapsedMs >= 500L) {
                                val speedBps = (windowBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                                val remaining = if (totalBytes > downloaded) totalBytes - downloaded else 0L
                                val etaSec = if (speedBps > 0L && remaining > 0L) remaining / speedBps else 0L

                                downloadDao.updateProgress(
                                    id = downloadId,
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes.coerceAtLeast(downloaded),
                                    speed = speedBps,
                                    eta = etaSec,
                                    status = DownloadStatus.DOWNLOADING.name
                                )

                                if (now - lastNotifyMs >= 1000L) {
                                    val pct = if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt() else 0
                                    runCatching {
                                        setForeground(
                                            createForegroundInfo(
                                                notificationId = notificationId,
                                                title = entity.title,
                                                percent = pct,
                                                speedText = "${formatBytesSimple(speedBps)}/s",
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
            val currentBytes = if (stagingFile.exists()) stagingFile.length() else (latest?.bytesDownloaded ?: 0L)
            downloadDao.updateProgress(
                id = downloadId,
                bytesDownloaded = currentBytes,
                totalBytes = (latest?.totalBytes ?: 0L).coerceAtLeast(currentBytes),
                speed = 0L,
                eta = 0L,
                status = DownloadStatus.FAILED.name
            )
            val errMsg = "Connection interrupted: ${io.message ?: "network error"}"
            downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name, errMsg)
            notificationHelper.notifyFailed(downloadId, entity.title, errMsg, settings.notificationsEnabled)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
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

    companion object {
        const val KEY_DOWNLOAD_ID = "key_download_id"
    }
}
