package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.EqualizerPreset
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
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
        val AUTO_RETRY_FAILED = booleanPreferencesKey("auto_retry_failed")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_PROMPT_DISMISSED = booleanPreferencesKey("notification_prompt_dismissed")
        val BROWSER_AD_BLOCKER = booleanPreferencesKey("browser_ad_blocker")
        val BROWSER_DESKTOP_MODE = booleanPreferencesKey("browser_desktop_mode")
        val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        val SEEK_INTERVAL_SECONDS = intPreferencesKey("seek_interval_seconds")
        val REMEMBER_PLAYBACK_POSITION = booleanPreferencesKey("remember_playback_position")
        val AUTO_PLAY_NEXT_VIDEO = booleanPreferencesKey("auto_play_next_video")
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val VIDEO_POSITIONS_JSON = stringPreferencesKey("video_positions_json")
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
            val eqPreset = prefs[Keys.EQUALIZER_PRESET]
                ?.let { runCatching { EqualizerPreset.valueOf(it) }.getOrNull() }
                ?: EqualizerPreset.FLAT
            val eqBands = prefs[Keys.EQUALIZER_BAND_LEVELS]
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull()?.coerceIn(-1500, 1500) }
                ?.takeIf { it.isNotEmpty() }
                ?: eqPreset.defaultBandsMb
            AppSettings(
                defaultQuality = quality,
                customTreeUri = prefs[Keys.CUSTOM_TREE_URI],
                customFolderName = prefs[Keys.CUSTOM_FOLDER_NAME] ?: "Downloads/StreamVault",
                maxConcurrentDownloads = (prefs[Keys.MAX_CONCURRENT] ?: 2).coerceIn(1, 5),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
                organizeByQuality = prefs[Keys.ORGANIZE_BY_QUALITY] ?: false,
                autoRetryFailed = prefs[Keys.AUTO_RETRY_FAILED] ?: true,
                themePreference = theme,
                dynamicColorEnabled = prefs[Keys.DYNAMIC_COLOR_ENABLED] ?: false,
                notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
                notificationPromptDismissed = prefs[Keys.NOTIFICATION_PROMPT_DISMISSED] ?: false,
                browserAdBlockerEnabled = prefs[Keys.BROWSER_AD_BLOCKER] ?: true,
                browserDesktopMode = prefs[Keys.BROWSER_DESKTOP_MODE] ?: false,
                defaultPlaybackSpeed = (prefs[Keys.DEFAULT_PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f),
                seekIntervalSeconds = (prefs[Keys.SEEK_INTERVAL_SECONDS] ?: 10).coerceIn(5, 30),
                rememberPlaybackPosition = prefs[Keys.REMEMBER_PLAYBACK_POSITION] ?: true,
                autoPlayNextVideo = prefs[Keys.AUTO_PLAY_NEXT_VIDEO] ?: true,
                equalizerEnabled = prefs[Keys.EQUALIZER_ENABLED] ?: false,
                equalizerPreset = eqPreset,
                equalizerBandLevelsMb = eqBands,
                bassBoostStrength = (prefs[Keys.BASS_BOOST_STRENGTH] ?: 0).coerceIn(0, 1000)
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
        dataStore.edit { it[Keys.MAX_CONCURRENT] = count.coerceIn(1, 5) }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setOrganizeByQuality(enabled: Boolean) {
        dataStore.edit { it[Keys.ORGANIZE_BY_QUALITY] = enabled }
    }

    suspend fun setAutoRetryFailed(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_RETRY_FAILED] = enabled }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[Keys.THEME_PREFERENCE] = preference.name }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR_ENABLED] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setNotificationPromptDismissed(dismissed: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_PROMPT_DISMISSED] = dismissed }
    }

    suspend fun setBrowserAdBlockerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BROWSER_AD_BLOCKER] = enabled }
    }

    suspend fun setBrowserDesktopMode(enabled: Boolean) {
        dataStore.edit { it[Keys.BROWSER_DESKTOP_MODE] = enabled }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        dataStore.edit { it[Keys.DEFAULT_PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setSeekIntervalSeconds(seconds: Int) {
        dataStore.edit { it[Keys.SEEK_INTERVAL_SECONDS] = seconds.coerceIn(5, 30) }
    }

    suspend fun setRememberPlaybackPosition(enabled: Boolean) {
        dataStore.edit { it[Keys.REMEMBER_PLAYBACK_POSITION] = enabled }
    }

    suspend fun setAutoPlayNextVideo(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_PLAY_NEXT_VIDEO] = enabled }
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EQUALIZER_ENABLED] = enabled }
    }

    suspend fun applyEqualizerPreset(preset: EqualizerPreset, bandLevelsMb: List<Int>, bassStrength: Int) {
        dataStore.edit {
            it[Keys.EQUALIZER_PRESET] = preset.name
            it[Keys.EQUALIZER_BAND_LEVELS] = bandLevelsMb.joinToString(",")
            it[Keys.BASS_BOOST_STRENGTH] = bassStrength.coerceIn(0, 1000)
        }
    }

    suspend fun setEqualizerBandLevels(levelsMb: List<Int>) {
        dataStore.edit {
            it[Keys.EQUALIZER_PRESET] = EqualizerPreset.CUSTOM.name
            it[Keys.EQUALIZER_BAND_LEVELS] = levelsMb.joinToString(",")
        }
    }

    suspend fun setBassBoostStrength(strength: Int) {
        dataStore.edit { it[Keys.BASS_BOOST_STRENGTH] = strength.coerceIn(0, 1000) }
    }

    suspend fun getVideoPlaybackPosition(downloadId: String): Long {
        val raw = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()[Keys.VIDEO_POSITIONS_JSON] ?: return 0L
        return runCatching { JSONObject(raw).optLong(downloadId, 0L).coerceAtLeast(0L) }.getOrDefault(0L)
    }

    suspend fun saveVideoPlaybackPosition(downloadId: String, positionMs: Long) {
        dataStore.edit { prefs ->
            val current = runCatching {
                JSONObject(prefs[Keys.VIDEO_POSITIONS_JSON].orEmpty())
            }.getOrElse { JSONObject() }
            if (positionMs <= 0L) {
                current.remove(downloadId)
            } else {
                current.put(downloadId, positionMs)
                if (current.length() > 50) {
                    val firstKey = current.keys().asSequence().firstOrNull()
                    if (firstKey != null) current.remove(firstKey)
                }
            }
            prefs[Keys.VIDEO_POSITIONS_JSON] = current.toString()
        }
    }
}
