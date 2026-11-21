package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.StorageInfo
import com.example.domain.repository.DownloadRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val storageInfo: StorageInfo? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private val storageFlow = MutableStateFlow<StorageInfo?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        storageFlow
    ) { settings, storage ->
        SettingsUiState(settings = settings, storageInfo = storage)
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

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { settingsRepository.setThemePreference(preference) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun clearDownloadHistory(deleteFiles: Boolean) {
        viewModelScope.launch {
            downloadRepository.clearHistory(deleteFiles = deleteFiles)
            refreshStorageInfo()
        }
    }
}
