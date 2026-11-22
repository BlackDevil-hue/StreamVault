package com.example.domain.model

import com.example.ui.theme.ThemePreference

enum class DefaultQualityPreference(val label: String, val targetHeight: Int) {
    HIGHEST("Highest Available", Int.MAX_VALUE),
    P1080("1080p Full HD", 1080),
    P720("720p HD", 720),
    P480("480p", 480),
    P360("360p", 360)
}

enum class EqualizerPreset(
    val label: String,
    val defaultBandsMb: List<Int>,
    val defaultBassStrength: Int
) {
    FLAT("Flat", listOf(0, 0, 0, 0, 0), 0),
    BASS_BOOST("Bass Boost", listOf(600, 400, 0, 100, 200), 650),
    POP("Pop", listOf(-150, 250, 450, 200, -100), 250),
    ROCK("Rock", listOf(450, 250, -150, 300, 500), 400),
    VOCAL("Vocal", listOf(-250, 100, 500, 400, 100), 0),
    CUSTOM("Custom", listOf(0, 0, 0, 0, 0), 0)
}

data class AppSettings(
    val defaultQuality: DefaultQualityPreference = DefaultQualityPreference.HIGHEST,
    val customTreeUri: String? = null,
    val customFolderName: String = "Downloads/StreamVault",
    val maxConcurrentDownloads: Int = 2,
    val wifiOnly: Boolean = false,
    val organizeByQuality: Boolean = false,
    val autoRetryFailed: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.DARK,
    val dynamicColorEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notificationPromptDismissed: Boolean = false,
    val browserAdBlockerEnabled: Boolean = true,
    val browserDesktopMode: Boolean = false,
    val defaultPlaybackSpeed: Float = 1.0f,
    val seekIntervalSeconds: Int = 10,
    val rememberPlaybackPosition: Boolean = true,
    val autoPlayNextVideo: Boolean = true,
    val equalizerEnabled: Boolean = false,
    val equalizerPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val equalizerBandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val bassBoostStrength: Int = 0
)

data class StorageInfo(
    val availableBytes: Long,
    val totalBytes: Long,
    val appDownloadsBytes: Long
)
