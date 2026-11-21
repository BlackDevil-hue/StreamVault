package com.example.domain.model

data class DownloadTask(
    val id: String,
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
    val status: DownloadStatus,
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
    val progressFraction: Float
        get() = reportedProgressPercent?.div(100f)?.coerceIn(0f, 1f)
            ?: if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = reportedProgressPercent?.coerceIn(0, 100) ?: (progressFraction * 100f).toInt().coerceIn(0, 100)
}
