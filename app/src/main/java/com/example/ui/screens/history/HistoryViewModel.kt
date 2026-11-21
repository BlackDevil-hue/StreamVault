package com.example.ui.screens.history

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.storage.StorageManager
import com.example.domain.model.DownloadTask
import com.example.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryFilter(val label: String) {
    ALL("All"),
    VIDEO("Videos"),
    AUDIO("Audio")
}

data class HistoryUiState(
    val searchQuery: String = "",
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val items: List<DownloadTask> = emptyList(),
    val selectedForDetails: DownloadTask? = null,
    val pendingDeleteItem: DownloadTask? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val storageManager: StorageManager
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(HistoryFilter.ALL)
    private val detailsFlow = MutableStateFlow<DownloadTask?>(null)
    private val pendingDeleteFlow = MutableStateFlow<DownloadTask?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    val uiState: StateFlow<HistoryUiState> = combine(
        searchQueryFlow,
        filterFlow,
        downloadRepository.completedDownloads,
        detailsFlow,
        pendingDeleteFlow
    ) { query, filter, allItems, detailsItem, deleteItem ->
        val filtered = allItems.filter { item ->
            val matchesQuery = query.isBlank() ||
                item.title.contains(query, ignoreCase = true) ||
                item.fileName.contains(query, ignoreCase = true) ||
                item.format.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.VIDEO -> !item.isAudioOnly
                HistoryFilter.AUDIO -> item.isAudioOnly
            }
            matchesQuery && matchesFilter
        }
        HistoryUiState(
            searchQuery = query,
            selectedFilter = filter,
            items = filtered,
            selectedForDetails = detailsItem,
            pendingDeleteItem = deleteItem
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState()
    )

    fun onSearchQueryChanged(query: String) {
        searchQueryFlow.value = query
    }

    fun selectFilter(filter: HistoryFilter) {
        filterFlow.value = filter
    }

    fun openDetails(item: DownloadTask) {
        detailsFlow.value = item
    }

    fun closeDetails() {
        detailsFlow.value = null
    }

    fun requestDelete(item: DownloadTask) {
        pendingDeleteFlow.value = item
    }

    fun dismissDelete() {
        pendingDeleteFlow.value = null
    }

    fun confirmDelete(deleteLocalFile: Boolean) {
        val target = pendingDeleteFlow.value ?: return
        pendingDeleteFlow.value = null
        if (detailsFlow.value?.id == target.id) detailsFlow.value = null
        viewModelScope.launch {
            val deleted = runCatching {
                downloadRepository.deleteHistoryItem(target.id, deleteFile = deleteLocalFile)
            }.getOrDefault(false)
            if (!deleted) {
                _messages.emit("Couldn't delete the file from storage. Its history entry was kept so you can retry.")
            }
        }
    }

    fun buildShareIntent(item: DownloadTask): Intent? =
        storageManager.createShareIntent(
            localUri = item.localUri,
            localPath = item.localPath,
            mimeType = item.mimeType,
            title = item.title
        )

    fun buildOpenLocationIntent(item: DownloadTask): Intent =
        storageManager.createOpenLocationIntent(item.localUri)
}
