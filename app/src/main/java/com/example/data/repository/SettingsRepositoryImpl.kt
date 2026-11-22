package com.example.data.repository

import com.example.data.preferences.UserPreferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.EqualizerPreset
import com.example.domain.repository.SettingsRepository
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val preferencesDataStore: UserPreferencesDataStore
) : SettingsRepository {

    override val settings: Flow<AppSettings> = preferencesDataStore.settingsFlow

    override suspend fun getCurrentSettings(): AppSettings = preferencesDataStore.getCurrent()

    override suspend fun setDefaultQuality(quality: DefaultQualityPreference) =
        preferencesDataStore.setDefaultQuality(quality)

    override suspend fun setCustomFolder(treeUri: String?, displayName: String) =
        preferencesDataStore.setCustomFolder(treeUri, displayName)

    override suspend fun setMaxConcurrentDownloads(count: Int) =
        preferencesDataStore.setMaxConcurrentDownloads(count)

    override suspend fun setWifiOnly(enabled: Boolean) =
        preferencesDataStore.setWifiOnly(enabled)

    override suspend fun setOrganizeByQuality(enabled: Boolean) =
        preferencesDataStore.setOrganizeByQuality(enabled)

    override suspend fun setAutoRetryFailed(enabled: Boolean) =
        preferencesDataStore.setAutoRetryFailed(enabled)

    override suspend fun setThemePreference(preference: ThemePreference) =
        preferencesDataStore.setThemePreference(preference)

    override suspend fun setDynamicColorEnabled(enabled: Boolean) =
        preferencesDataStore.setDynamicColorEnabled(enabled)

    override suspend fun setNotificationsEnabled(enabled: Boolean) =
        preferencesDataStore.setNotificationsEnabled(enabled)

    override suspend fun setNotificationPromptDismissed(dismissed: Boolean) =
        preferencesDataStore.setNotificationPromptDismissed(dismissed)

    override suspend fun setBrowserAdBlockerEnabled(enabled: Boolean) =
        preferencesDataStore.setBrowserAdBlockerEnabled(enabled)

    override suspend fun setBrowserDesktopMode(enabled: Boolean) =
        preferencesDataStore.setBrowserDesktopMode(enabled)

    override suspend fun setDefaultPlaybackSpeed(speed: Float) =
        preferencesDataStore.setDefaultPlaybackSpeed(speed)

    override suspend fun setSeekIntervalSeconds(seconds: Int) =
        preferencesDataStore.setSeekIntervalSeconds(seconds)

    override suspend fun setRememberPlaybackPosition(enabled: Boolean) =
        preferencesDataStore.setRememberPlaybackPosition(enabled)

    override suspend fun setAutoPlayNextVideo(enabled: Boolean) =
        preferencesDataStore.setAutoPlayNextVideo(enabled)

    override suspend fun setEqualizerEnabled(enabled: Boolean) =
        preferencesDataStore.setEqualizerEnabled(enabled)

    override suspend fun applyEqualizerPreset(
        preset: EqualizerPreset,
        bandLevelsMb: List<Int>,
        bassStrength: Int
    ) = preferencesDataStore.applyEqualizerPreset(preset, bandLevelsMb, bassStrength)

    override suspend fun setEqualizerBandLevels(levelsMb: List<Int>) =
        preferencesDataStore.setEqualizerBandLevels(levelsMb)

    override suspend fun setBassBoostStrength(strength: Int) =
        preferencesDataStore.setBassBoostStrength(strength)
}
