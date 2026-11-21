package com.example.domain.repository

import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.DownloadTask
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.StorageInfo
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.source.ExtractionOutcome
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow

interface VideoAnalyzerRepository {
    suspend fun analyzeUrl(rawUrl: String): ExtractionOutcome
    fun extractFirstUrlFromText(text: String?): String?
}

interface DownloadRepository {
    val activeAndQueuedDownloads: Flow<List<DownloadTask>>
    val completedDownloads: Flow<List<DownloadTask>>
    val recentDownloads: Flow<List<DownloadTask>>

    fun observeDownload(id: String): Flow<DownloadTask?>
    suspend fun getDownloadById(id: String): DownloadTask?
    suspend fun findDuplicateCompleted(normalizedUrl: String, streamUrl: String): DownloadTask?

    suspend fun enqueueDownload(
        analysis: VideoAnalysisResult,
        option: MediaStreamOption,
        overrideFileName: String? = null
    ): Result<String>

    suspend fun pauseDownload(id: String)
    suspend fun resumeDownload(id: String)
    suspend fun cancelDownload(id: String)
    suspend fun retryDownload(id: String)
    suspend fun pauseAll()
    suspend fun resumeAll()

    suspend fun deleteHistoryItem(id: String, deleteFile: Boolean): Boolean
    suspend fun clearHistory(deleteFiles: Boolean)
    suspend fun recoverInterruptedDownloads()
    suspend fun getStorageInfo(): StorageInfo
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun getCurrentSettings(): AppSettings
    suspend fun setDefaultQuality(quality: DefaultQualityPreference)
    suspend fun setCustomFolder(treeUri: String?, displayName: String)
    suspend fun setMaxConcurrentDownloads(count: Int)
    suspend fun setWifiOnly(enabled: Boolean)
    suspend fun setOrganizeByQuality(enabled: Boolean)
    suspend fun setThemePreference(preference: ThemePreference)
    suspend fun setNotificationsEnabled(enabled: Boolean)
}
