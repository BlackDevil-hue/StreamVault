package com.example.data.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_downloads_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            val alertsChannel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Completed & Failed Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when downloads finish or need attention"
            }
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    fun buildProgressNotification(
        title: String,
        progressPercent: Int,
        speedText: String,
        downloadId: String,
        indeterminate: Boolean = false
    ): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Starting download..." else "$progressPercent% • $speedText")
            .setProgress(100, progressPercent.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                actionPendingIntent(downloadId, DownloadActionReceiver.ACTION_PAUSE, title, progressPercent)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                actionPendingIntent(downloadId, DownloadActionReceiver.ACTION_CANCEL, title, progressPercent)
            )
            .build()
    }

    fun notifyPaused(downloadId: String, title: String, progressPercent: Int) {
        if (!hasNotificationPermission()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Paused • $progressPercent% complete")
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                actionPendingIntent(downloadId, DownloadActionReceiver.ACTION_RESUME, title, progressPercent)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                actionPendingIntent(downloadId, DownloadActionReceiver.ACTION_CANCEL, title, progressPercent)
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(pausedNotificationId(downloadId), notification)
        }
    }

    fun cancelProgress(downloadId: String) {
        NotificationManagerCompat.from(context).apply {
            cancel(downloadId.hashCode())
            cancel(pausedNotificationId(downloadId))
        }
    }

    fun notifyCompleted(downloadId: String, title: String, resolution: String, notificationsEnabled: Boolean) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context,
            completedNotificationId(downloadId),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText("$title ($resolution)")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(completedNotificationId(downloadId), notification)
        }
    }

    fun notifyFailed(downloadId: String, title: String, reason: String, notificationsEnabled: Boolean) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed: $title")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(failedNotificationId(downloadId), notification)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun actionPendingIntent(
        downloadId: String,
        action: String,
        title: String,
        progressPercent: Int
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        31 * downloadId.hashCode() + action.hashCode(),
        Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(DownloadActionReceiver.EXTRA_TITLE, title)
            putExtra(DownloadActionReceiver.EXTRA_PROGRESS, progressPercent)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pausedNotificationId(downloadId: String): Int = downloadId.hashCode() xor Int.MIN_VALUE

    private fun completedNotificationId(downloadId: String): Int = downloadId.hashCode() xor 0x40000000

    private fun failedNotificationId(downloadId: String): Int = downloadId.hashCode() xor 0x20000000

    companion object {
        const val CHANNEL_ID = "streamvault_downloads_progress"
        const val ALERTS_CHANNEL_ID = "streamvault_downloads_alerts"
    }
}
