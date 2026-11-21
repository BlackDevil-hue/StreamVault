package com.example.ui.screens.player

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.storage.StorageManager
import com.example.domain.model.DownloadTask
import com.example.domain.repository.DownloadRepository
import com.example.ui.navigation.AppRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    val task: DownloadTask? = null,
    val mediaUri: Uri? = null,
    val externalSubtitleUri: Uri? = null,
    val fileMissing: Boolean = false
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val downloadRepository: DownloadRepository,
    private val storageManager: StorageManager
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AppRoute.Player>()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val task = downloadRepository.getDownloadById(route.downloadId)
            if (task == null) {
                _uiState.update { it.copy(isLoading = false, fileMissing = true) }
                return@launch
            }
            val uri = storageManager.resolvePlayableUri(task.localUri, task.localPath)
            val exists = storageManager.fileExists(task.localUri, task.localPath)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    task = task,
                    mediaUri = uri,
                    fileMissing = uri == null || !exists
                )
            }
        }
    }

    fun setExternalSubtitle(uri: Uri) {
        _uiState.update { it.copy(externalSubtitleUri = uri) }
    }
}
