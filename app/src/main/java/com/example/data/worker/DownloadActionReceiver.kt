package com.example.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.repository.DownloadRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var downloadRepository: DownloadRepository

    @Inject
    lateinit var notificationHelper: DownloadNotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> {
                        val latestProgress = downloadRepository.getDownloadById(downloadId)?.progressPercent ?: progress
                        downloadRepository.pauseDownload(downloadId)
                        notificationHelper.notifyPaused(downloadId, title, latestProgress)
                    }
                    ACTION_RESUME -> {
                        downloadRepository.resumeDownload(downloadId)
                    }
                    ACTION_CANCEL -> {
                        downloadRepository.cancelDownload(downloadId)
                    }
                }
            } catch (error: Exception) {
                notificationHelper.notifyFailed(
                    downloadId,
                    title,
                    error.message ?: "Could not update the download.",
                    notificationsEnabled = true
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.example.streamvault.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.example.streamvault.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.streamvault.action.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_TITLE = "extra_download_title"
        const val EXTRA_PROGRESS = "extra_download_progress"
    }
}
