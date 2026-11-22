package com.example.data.repository

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.local.dao.DownloadDao
import com.example.data.local.entity.DownloadEntity
import com.example.data.network.UrlSecurityValidator
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.storage.StorageManager
import com.example.data.worker.DownloadNotificationHelper
import com.example.data.worker.VideoDownloadWorker
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTask
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.StorageInfo
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val workManager: WorkManager,
    private val storageManager: StorageManager,
    private val preferencesDataStore: UserPreferencesDataStore,
    private val urlValidator: UrlSecurityValidator,
    private val notificationHelper: DownloadNotificationHelper
) : DownloadRepository {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()

    init {
        // Reactive queue pump: automatically schedules next QUEUED item when capacity opens
        repoScope.launch {
            downloadDao.observeActiveAndQueued()
                .map { list ->
                    val downloading = list.count { it.status == DownloadStatus.DOWNLOADING.name }
                    val queuedIds = list.filter { it.status == DownloadStatus.QUEUED.name }.map { it.id }
                    downloading to queuedIds
                }
                .distinctUntilChanged()
                .collect { (downloadingCount, queuedIds) ->
                    if (queuedIds.isNotEmpty()) {
                        pumpQueue(downloadingCount, queuedIds)
                    }
                }
        }
    }

    override val activeAndQueuedDownloads: Flow<List<DownloadTask>> =
        downloadDao.observeActiveAndQueued().map { list -> list.map { it.toDomain() } }

    override val completedDownloads: Flow<List<DownloadTask>> =
        downloadDao.observeCompleted().map { list -> list.map { it.toDomain() } }

    override val recentDownloads: Flow<List<DownloadTask>> =
        downloadDao.observeRecentCompleted(limit = 5).map { list -> list.map { it.toDomain() } }

    override fun observeDownload(id: String): Flow<DownloadTask?> =
        downloadDao.observeById(id).map { it?.toDomain() }

    override suspend fun getDownloadById(id: String): DownloadTask? =
        downloadDao.getById(id)?.toDomain()

    override suspend fun findDuplicateCompleted(normalizedUrl: String, streamUrl: String): DownloadTask? =
        downloadDao.findDuplicateCompleted(normalizedUrl, streamUrl)?.toDomain()

    override suspend fun enqueueDownload(
        analysis: VideoAnalysisResult,
        option: MediaStreamOption,
        overrideFileName: String?
    ): Result<String> {
        val estimated = option.estimatedBytes ?: 0L
        val isYtDlp = option.streamUrl.startsWith("yt-dlp://")
        if (!isYtDlp && estimated > 0L && !storageManager.hasEnoughStorage(estimated)) {
            return Result.failure(
                IllegalStateException("Insufficient storage space to download this video.")
            )
        }

        val id = UUID.randomUUID().toString()
        val ext = option.format.lowercase().ifBlank { if (option.isAudioOnly) "m4a" else "mp4" }
        val fileName = overrideFileName?.takeIf { it.isNotBlank() }
            ?: urlValidator.sanitizeFileName(
                rawTitle = "${analysis.title}_${option.qualityLabel}",
                extension = ext
            )

        val settings = preferencesDataStore.getCurrent()
        val activeCount = downloadDao.countDownloading()
        val initialStatus = if (activeCount < settings.maxConcurrentDownloads) {
            DownloadStatus.DOWNLOADING
        } else {
            DownloadStatus.QUEUED
        }

        val entity = DownloadEntity(
            id = id,
            sourceUrl = analysis.sourceUrl,
            normalizedUrl = analysis.normalizedUrl,
            streamUrl = option.streamUrl,
            title = analysis.title,
            artist = analysis.author?.takeUnless { analysis.providerName == "Direct Media Link" },
            thumbnailUrl = analysis.thumbnailUrl,
            durationMs = analysis.durationMs,
            resolutionLabel = option.qualityLabel,
            resolutionHeight = option.resolutionHeight,
            format = option.format,
            mimeType = option.mimeType,
            isAudioOnly = option.isAudioOnly,
            status = initialStatus.name,
            bytesDownloaded = 0L,
            totalBytes = estimated,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            supportsRange = option.supportsRange,
            eTag = option.eTag,
            localUri = null,
            localPath = null,
            fileName = fileName,
            sha256 = null,
            errorMessage = null,
            workRequestId = null,
            createdAt = System.currentTimeMillis(),
            completedAt = null
        )
        downloadDao.insert(entity)

        if (initialStatus == DownloadStatus.DOWNLOADING) {
            enqueueWorkerForDownload(id, settings.wifiOnly)
        }
        return Result.success(id)
    }

    override suspend fun pauseDownload(id: String) {
        val entity = downloadDao.getById(id) ?: return
        downloadDao.updateStatus(id, DownloadStatus.PAUSED.name, null)
        workManager.cancelUniqueWork(uniqueWorkName(id))
        entity.workRequestId?.let { workId ->
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
        }
        notificationHelper.cancelProgress(id)
    }

    override suspend fun resumeDownload(id: String) {
        val entity = downloadDao.getById(id) ?: return
        if (entity.status == DownloadStatus.COMPLETED.name) return
        notificationHelper.cancelProgress(id)
        val settings = preferencesDataStore.getCurrent()
        val activeCount = downloadDao.countDownloading()
        if (activeCount < settings.maxConcurrentDownloads) {
            downloadDao.updateStatus(id, DownloadStatus.DOWNLOADING.name, null)
            enqueueWorkerForDownload(id, settings.wifiOnly)
        } else {
            downloadDao.updateStatus(id, DownloadStatus.QUEUED.name, null)
        }
    }

    override suspend fun cancelDownload(id: String) {
        val entity = downloadDao.getById(id) ?: return
        downloadDao.updateStatus(id, DownloadStatus.CANCELLED.name, null)
        workManager.cancelUniqueWork(uniqueWorkName(id))
        entity.workRequestId?.let { workId ->
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
        }
        storageManager.deleteStagingFile(id)
        storageManager.deleteYtDlpWorkingFiles(id)
        downloadDao.deleteById(id)
        notificationHelper.cancelProgress(id)
    }

    override suspend fun retryDownload(id: String) {
        resumeDownload(id)
    }

    override suspend fun pauseAll() {
        val active = downloadDao.getQueued() + downloadDao.getDownloading()
        active.forEach { entity ->
            pauseDownload(entity.id)
        }
    }

    override suspend fun resumeAll() {
        val all = downloadDao.getPaused() + downloadDao.getQueued()
        all.forEach { resumeDownload(it.id) }
    }

    override suspend fun deleteHistoryItem(id: String, deleteFile: Boolean): Boolean {
        val entity = downloadDao.getById(id) ?: return false
        val fileDeleted = !deleteFile || withContext(Dispatchers.IO) {
            storageManager.deleteMediaFile(entity.localUri, entity.localPath)
        }
        if (!fileDeleted) {
            return false
        }
        downloadDao.deleteById(id)
        return true
    }

    override suspend fun clearHistory(deleteFiles: Boolean) {
        if (deleteFiles) {
            downloadDao.getAllCompleted().forEach { entity ->
                if (withContext(Dispatchers.IO) { storageManager.deleteMediaFile(entity.localUri, entity.localPath) }) {
                    downloadDao.deleteById(entity.id)
                }
            }
        } else {
            downloadDao.deleteAllCompleted()
        }
    }

    override suspend fun recoverInterruptedDownloads() {
        val downloading = downloadDao.getDownloading()
        val settings = preferencesDataStore.getCurrent()
        downloading.forEach { entity ->
            val workInfos = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueWorkName(entity.id)).get()
            }.getOrDefault(emptyList())
            val isRunning = workInfos.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            if (!isRunning) {
                if (entity.supportsRange) {
                    enqueueWorkerForDownload(entity.id, settings.wifiOnly)
                } else {
                    downloadDao.updateStatus(
                        entity.id,
                        DownloadStatus.PAUSED.name,
                        "Interrupted by app restart. Tap Resume to restart."
                    )
                }
            }
        }
    }

    override suspend fun getStorageInfo(): StorageInfo {
        val usedBytes = downloadDao.getTotalCompletedBytes()
        return storageManager.getStorageInfo(usedBytes)
    }

    private suspend fun pumpQueue(currentDownloading: Int, queuedIds: List<String>) {
        queueMutex.withLock {
            val settings = preferencesDataStore.getCurrent()
            val availableSlots = (settings.maxConcurrentDownloads - currentDownloading).coerceAtLeast(0)
            if (availableSlots <= 0) return

            queuedIds.asReversed().take(availableSlots).forEach { id ->
                downloadDao.updateStatus(id, DownloadStatus.DOWNLOADING.name, null)
                enqueueWorkerForDownload(id, settings.wifiOnly)
            }
        }
    }

    private suspend fun enqueueWorkerForDownload(downloadId: String, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setInputData(workDataOf(VideoDownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("download_$downloadId")
            .build()

        downloadDao.updateWorkRequestId(downloadId, request.id.toString())
        workManager.enqueueUniqueWork(
            uniqueWorkName(downloadId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun uniqueWorkName(downloadId: String) = "streamvault_dl_$downloadId"
}
