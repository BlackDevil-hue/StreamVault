package com.example.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTask
import com.example.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val tasks: List<DownloadTask> = emptyList()
) {
    val hasActiveOrQueued: Boolean
        get() = tasks.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    val hasPaused: Boolean
        get() = tasks.any { it.status == DownloadStatus.PAUSED }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = downloadRepository.activeAndQueuedDownloads
        .map { DownloadsUiState(tasks = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DownloadsUiState()
        )

    fun pauseDownload(id: String) {
        viewModelScope.launch { downloadRepository.pauseDownload(id) }
    }

    fun resumeDownload(id: String) {
        viewModelScope.launch { downloadRepository.resumeDownload(id) }
    }

    fun retryDownload(id: String) {
        viewModelScope.launch { downloadRepository.retryDownload(id) }
    }

    fun cancelDownload(id: String) {
        viewModelScope.launch { downloadRepository.cancelDownload(id) }
    }

    fun pauseAll() {
        viewModelScope.launch { downloadRepository.pauseAll() }
    }

    fun resumeAll() {
        viewModelScope.launch { downloadRepository.resumeAll() }
    }
}
