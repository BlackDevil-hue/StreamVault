package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.storage.StorageManager
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.EqualizerPreset
import com.example.domain.model.StorageInfo
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storageInfo: StorageInfo? = null,
    val tempCacheBytes: Long = 0L
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadRepository: DownloadRepository,
    private val storageManager: StorageManager
) : ViewModel() {

    private val storageFlow = MutableStateFlow<StorageInfo?>(null)
    private val tempCacheFlow = MutableStateFlow(0L)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        storageFlow,
        tempCacheFlow
    ) { settings, storage, cacheBytes ->
        SettingsUiState(
            settings = settings,
            storageInfo = storage,
            tempCacheBytes = cacheBytes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            storageFlow.value = downloadRepository.getStorageInfo()
            tempCacheFlow.value = withContext(Dispatchers.IO) {
                storageManager.getTempCacheBytes()
            }
        }
    }

    fun setDefaultQuality(quality: DefaultQualityPreference) {
        viewModelScope.launch { settingsRepository.setDefaultQuality(quality) }
    }

    fun setCustomFolder(treeUri: String?, displayName: String) {
        viewModelScope.launch { settingsRepository.setCustomFolder(treeUri, displayName) }
    }

    fun setMaxConcurrentDownloads(count: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(count) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnly(enabled) }
    }

    fun setOrganizeByQuality(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setOrganizeByQuality(enabled) }
    }

    fun setAutoRetryFailed(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoRetryFailed(enabled) }
    }

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { settingsRepository.setThemePreference(preference) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun setBrowserAdBlockerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBrowserAdBlockerEnabled(enabled) }
    }

    fun setBrowserDesktopMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBrowserDesktopMode(enabled) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(speed) }
    }

    fun setSeekIntervalSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setSeekIntervalSeconds(seconds) }
    }

    fun setRememberPlaybackPosition(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRememberPlaybackPosition(enabled) }
    }

    fun setAutoPlayNextVideo(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPlayNextVideo(enabled) }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEqualizerEnabled(enabled) }
    }

    fun selectEqualizerPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            settingsRepository.setEqualizerEnabled(true)
            settingsRepository.applyEqualizerPreset(
                preset = preset,
                bandLevelsMb = preset.defaultBandsMb,
                bassStrength = preset.defaultBassStrength
            )
        }
    }

    fun resetEqualizerFlat() {
        viewModelScope.launch {
            settingsRepository.applyEqualizerPreset(
                preset = EqualizerPreset.FLAT,
                bandLevelsMb = EqualizerPreset.FLAT.defaultBandsMb,
                bassStrength = 0
            )
        }
    }

    fun clearTempCache(onCompleted: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                storageManager.clearTempCache()
            }
            refreshStorageInfo()
            onCompleted(freed)
        }
    }

    fun clearDownloadHistory(deleteFiles: Boolean) {
        viewModelScope.launch {
            downloadRepository.clearHistory(deleteFiles = deleteFiles)
            refreshStorageInfo()
        }
    }
}
