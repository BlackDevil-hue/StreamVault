package com.example.data.repository

import com.example.data.preferences.UserPreferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
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

    override suspend fun setThemePreference(preference: ThemePreference) =
        preferencesDataStore.setThemePreference(preference)

    override suspend fun setNotificationsEnabled(enabled: Boolean) =
        preferencesDataStore.setNotificationsEnabled(enabled)
}
