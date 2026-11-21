package com.example.domain.model

import com.example.ui.theme.ThemePreference

enum class DefaultQualityPreference(val label: String, val targetHeight: Int) {
    HIGHEST("Highest Available", Int.MAX_VALUE),
    P1080("1080p Full HD", 1080),
    P720("720p HD", 720),
    P480("480p", 480),
    P360("360p", 360)
}

data class AppSettings(
    val defaultQuality: DefaultQualityPreference = DefaultQualityPreference.HIGHEST,
    val customTreeUri: String? = null,
    val customFolderName: String = "Downloads/StreamVault",
    val maxConcurrentDownloads: Int = 2,
    val wifiOnly: Boolean = false,
    val organizeByQuality: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.DARK,
    val notificationsEnabled: Boolean = true
)

data class StorageInfo(
    val availableBytes: Long,
    val totalBytes: Long,
    val appDownloadsBytes: Long
)
