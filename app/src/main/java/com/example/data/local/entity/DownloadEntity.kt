package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTask

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["normalizedUrl"]),
        Index(value = ["createdAt"])
    ]
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val normalizedUrl: String,
    val streamUrl: String,
    val title: String,
    val artist: String? = null,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val resolutionLabel: String,
    val resolutionHeight: Int?,
    val format: String,
    val mimeType: String,
    val isAudioOnly: Boolean,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val supportsRange: Boolean,
    val eTag: String?,
    val localUri: String?,
    val localPath: String?,
    val fileName: String,
    val sha256: String?,
    val errorMessage: String?,
    val workRequestId: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val reportedProgressPercent: Int? = null
) {
    fun toDomain(): DownloadTask = DownloadTask(
        id = id,
        sourceUrl = sourceUrl,
        normalizedUrl = normalizedUrl,
        streamUrl = streamUrl,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = durationMs,
        resolutionLabel = resolutionLabel,
        resolutionHeight = resolutionHeight,
        format = format,
        mimeType = mimeType,
        isAudioOnly = isAudioOnly,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        speedBytesPerSec = speedBytesPerSec,
        etaSeconds = etaSeconds,
        supportsRange = supportsRange,
        eTag = eTag,
        localUri = localUri,
        localPath = localPath,
        fileName = fileName,
        sha256 = sha256,
        errorMessage = errorMessage,
        workRequestId = workRequestId,
        createdAt = createdAt,
        completedAt = completedAt,
        reportedProgressPercent = reportedProgressPercent
    )
}

fun DownloadTask.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    sourceUrl = sourceUrl,
    normalizedUrl = normalizedUrl,
    streamUrl = streamUrl,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs,
    resolutionLabel = resolutionLabel,
    resolutionHeight = resolutionHeight,
    format = format,
    mimeType = mimeType,
    isAudioOnly = isAudioOnly,
    status = status.name,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSec = speedBytesPerSec,
    etaSeconds = etaSeconds,
    supportsRange = supportsRange,
    eTag = eTag,
    localUri = localUri,
    localPath = localPath,
    fileName = fileName,
    sha256 = sha256,
    errorMessage = errorMessage,
    workRequestId = workRequestId,
    createdAt = createdAt,
    completedAt = completedAt,
    reportedProgressPercent = reportedProgressPercent
)
