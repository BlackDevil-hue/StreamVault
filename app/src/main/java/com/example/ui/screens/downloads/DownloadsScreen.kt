package com.example.ui.screens.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DownloadStatus
import com.example.domain.model.DownloadTask
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FormatUtils
import com.example.ui.components.MediaThumbnail
import com.example.ui.components.QualityBadge
import com.example.ui.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onGoToHome: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadingCount = uiState.tasks.count { it.status == DownloadStatus.DOWNLOADING }
    val queuedCount = uiState.tasks.count { it.status == DownloadStatus.QUEUED }
    val pausedCount = uiState.tasks.count { it.status == DownloadStatus.PAUSED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Downloads")
                        if (uiState.tasks.isNotEmpty()) {
                            Text(
                                text = "${uiState.tasks.size} in progress",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.hasActiveOrQueued) {
                        TextButton(
                            onClick = viewModel::pauseAll,
                            modifier = Modifier.testTag("pause_all_button")
                        ) {
                            Text("Pause All")
                        }
                    } else if (uiState.hasPaused) {
                        TextButton(
                            onClick = viewModel::resumeAll,
                            modifier = Modifier.testTag("resume_all_button")
                        ) {
                            Text("Resume All")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (uiState.tasks.isEmpty()) {
                EmptyStatePanel(
                    icon = Icons.Rounded.CloudDownload,
                    title = "No Active Downloads",
                    subtitle = "Paste a video URL on the Home tab to analyze available resolutions and start downloading.",
                    actionLabel = "Add Video Link",
                    onAction = onGoToHome,
                    modifier = Modifier.widthIn(max = 520.dp).align(Alignment.Center)
                )
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1280.dp)
                        .fillMaxSize()
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp)) {
                        Text("Your active transfers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildList {
                                if (downloadingCount > 0) add("$downloadingCount downloading")
                                if (queuedCount > 0) add("$queuedCount queued")
                                if (pausedCount > 0) add("$pausedCount paused")
                            }.joinToString("  ·  ").ifBlank { "Review failed items and retry when ready" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 420.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(uiState.tasks, key = { it.id }) { task ->
                            ActiveDownloadItemCard(
                                task = task,
                                onPause = { viewModel.pauseDownload(task.id) },
                                onResume = { viewModel.resumeDownload(task.id) },
                                onRetry = { viewModel.retryDownload(task.id) },
                                onCancel = { viewModel.cancelDownload(task.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadItemCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    var showFullError by remember(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_card_${task.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MediaThumbnail(
                    thumbnailUrl = task.thumbnailUrl,
                    isAudioOnly = task.isAudioOnly,
                    contentDescription = task.title,
                    modifier = Modifier.size(width = 76.dp, height = 56.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadge(status = task.status)
                        QualityBadge(label = task.resolutionLabel)
                        QualityBadge(label = task.format)
                    }
                }
            }

            // Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.status == DownloadStatus.DOWNLOADING && task.totalBytes <= 0L && task.reportedProgressPercent == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        strokeCap = StrokeCap.Round
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        strokeCap = StrokeCap.Round,
                        color = when (task.status) {
                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            DownloadStatus.PAUSED -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val progressText = if (task.reportedProgressPercent != null && task.totalBytes > 0L) {
                        "${FormatUtils.formatBytes(task.bytesDownloaded)} / ~${FormatUtils.formatBytes(task.totalBytes)} (${task.reportedProgressPercent}%)"
                    } else if (task.reportedProgressPercent != null) {
                        "${task.reportedProgressPercent}% of current stream"
                    } else {
                        val downloadedStr = FormatUtils.formatBytes(task.bytesDownloaded)
                        val totalStr = if (task.totalBytes > 0L) FormatUtils.formatBytes(task.totalBytes) else "Unknown"
                        "$downloadedStr / $totalStr (${task.progressPercent}%)"
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.status == DownloadStatus.DOWNLOADING) {
                        Text(
                            text = when {
                                task.speedBytesPerSec > 0L -> "${FormatUtils.formatSpeed(task.speedBytesPerSec)} • ETA ${FormatUtils.formatEta(task.etaSeconds)}"
                                task.reportedProgressPercent != null && task.reportedProgressPercent >= 99 -> "Finalizing media…"
                                task.streamUrl.startsWith("yt-dlp://") -> "Connecting to source stream…"
                                else -> "${FormatUtils.formatSpeed(task.speedBytesPerSec)} • ${FormatUtils.formatEta(task.etaSeconds)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!task.errorMessage.isNullOrBlank()) {
                val error = task.errorMessage.orEmpty()
                Column {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = if (showFullError) 12 else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (error.length > 220) {
                        TextButton(
                            onClick = { showFullError = !showFullError },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text(if (showFullError) "Hide details" else "Show details")
                        }
                    }
                }
            }

            // Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        FilledTonalButton(
                            onClick = onPause,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("pause_button_${task.id}")
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pause")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        FilledTonalButton(
                            onClick = onResume,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("resume_button_${task.id}")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (task.supportsRange) "Resume" else "Restart")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        FilledTonalButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("retry_button_${task.id}")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                    else -> Unit
                }

                Spacer(modifier = Modifier.width(10.dp))

                FilledTonalIconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("cancel_button_${task.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel ${task.fileName}"
                    )
                }
            }
        }
    }
}
