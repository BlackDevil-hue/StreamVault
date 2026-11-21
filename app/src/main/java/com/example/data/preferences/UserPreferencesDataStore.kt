package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "streamvault_settings")

@Singleton
class UserPreferencesDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val CUSTOM_TREE_URI = stringPreferencesKey("custom_tree_uri")
        val CUSTOM_FOLDER_NAME = stringPreferencesKey("custom_folder_name")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val ORGANIZE_BY_QUALITY = booleanPreferencesKey("organize_by_quality")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }

    val settingsFlow: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val quality = prefs[Keys.DEFAULT_QUALITY]
                ?.let { runCatching { DefaultQualityPreference.valueOf(it) }.getOrNull() }
                ?: DefaultQualityPreference.HIGHEST
            val theme = prefs[Keys.THEME_PREFERENCE]
                ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                ?: ThemePreference.DARK
            AppSettings(
                defaultQuality = quality,
                customTreeUri = prefs[Keys.CUSTOM_TREE_URI],
                customFolderName = prefs[Keys.CUSTOM_FOLDER_NAME] ?: "Downloads/StreamVault",
                maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT] ?: 2).coerceIn(1, 4),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
                organizeByQuality = prefs[Keys.ORGANIZE_BY_QUALITY] ?: false,
                themePreference = theme,
                notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
            )
        }

    suspend fun getCurrent(): AppSettings = settingsFlow.first()

    suspend fun setDefaultQuality(quality: DefaultQualityPreference) {
        dataStore.edit { it[Keys.DEFAULT_QUALITY] = quality.name }
    }

    suspend fun setCustomFolder(treeUri: String?, displayName: String) {
        dataStore.edit {
            if (treeUri.isNullOrBlank()) {
                it.remove(Keys.CUSTOM_TREE_URI)
                it[Keys.CUSTOM_FOLDER_NAME] = "Downloads/StreamVault"
            } else {
                it[Keys.CUSTOM_TREE_URI] = treeUri
                it[Keys.CUSTOM_FOLDER_NAME] = displayName
            }
        }
    }

    suspend fun setMaxConcurrentDownloads(count: Int) {
        dataStore.edit { it[Keys.MAX_CONCURRENT] = count.coerceIn(1, 4) }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setOrganizeByQuality(enabled: Boolean) {
        dataStore.edit { it[Keys.ORGANIZE_BY_QUALITY] = enabled }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[Keys.THEME_PREFERENCE] = preference.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }
}
