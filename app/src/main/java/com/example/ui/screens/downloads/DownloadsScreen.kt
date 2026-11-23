package com.example.ui.screens.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
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
import com.example.ui.components.VaultAmbientBackground
import com.example.ui.theme.LocalStreamVaultExtras

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
    val totalSpeedBytes = uiState.tasks
        .filter { it.status == DownloadStatus.DOWNLOADING }
        .sumOf { it.speedBytesPerSec }

    VaultAmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Downloads")
                            if (uiState.tasks.isNotEmpty()) {
                                Text(
                                    text = "${uiState.tasks.size} in queue",
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
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("pause_all_button")
                            ) {
                                Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause All")
                            }
                        } else if (uiState.hasPaused) {
                            TextButton(
                                onClick = viewModel::resumeAll,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("resume_all_button")
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume All")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
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
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .align(Alignment.Center)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 1280.dp)
                            .fillMaxSize()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (downloadingCount > 0) {
                                QueueStatPill(
                                    label = "$downloadingCount active",
                                    accentColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (totalSpeedBytes > 0L) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Speed,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = FormatUtils.formatSpeed(totalSpeedBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            if (queuedCount > 0) {
                                QueueStatPill(
                                    label = "$queuedCount queued",
                                    accentColor = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (pausedCount > 0) {
                                QueueStatPill(
                                    label = "$pausedCount paused",
                                    accentColor = LocalStreamVaultExtras.current.warning
                                )
                            }
                            val failedCount = uiState.tasks.count { it.status == DownloadStatus.FAILED }
                            if (failedCount > 0) {
                                QueueStatPill(
                                    label = "$failedCount failed",
                                    accentColor = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 420.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
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
}

@Composable
private fun QueueStatPill(
    label: String,
    accentColor: Color
) {
    Surface(
        shape = CircleShape,
        color = accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
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
    val animatedProgress by animateFloatAsState(
        targetValue = task.progressFraction,
        animationSpec = tween(durationMillis = 320),
        label = "download_progress"
    )
    val cardBorderColor = when (task.status) {
        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_card_${task.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, cardBorderColor),
        shape = RoundedCornerShape(22.dp)
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
                    modifier = Modifier.size(width = 80.dp, height = 58.dp)
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
                        if (task.status != DownloadStatus.DOWNLOADING) {
                            StatusBadge(status = task.status)
                        }
                        val qualityBadgeLabel = when {
                            task.isAudioOnly -> task.resolutionLabel
                            task.resolutionHeight != null && task.resolutionHeight > 0 ->
                                com.example.domain.model.VideoQualityTier.formatLabelFromHeight(task.resolutionHeight)
                            task.resolutionLabel.contains("Best available", ignoreCase = true) ->
                                "HD Quality"
                            else -> task.resolutionLabel
                        }
                        QualityBadge(
                            label = qualityBadgeLabel,
                            isHighlighted = task.status == DownloadStatus.DOWNLOADING
                        )
                        QualityBadge(label = task.format)
                    }
                }
            }

            // Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val isConnecting = task.status == DownloadStatus.DOWNLOADING &&
                    task.bytesDownloaded <= 0L &&
                    (task.reportedProgressPercent ?: 0) <= 0
                if (isConnecting || (task.status == DownloadStatus.DOWNLOADING && task.totalBytes <= 0L && task.reportedProgressPercent == null)) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        strokeCap = StrokeCap.Round,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                        strokeCap = StrokeCap.Round,
                        color = when (task.status) {
                            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                            DownloadStatus.PAUSED -> LocalStreamVaultExtras.current.warning
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
                    val downloadedStr = FormatUtils.formatProgressBytes(task.bytesDownloaded)
                    val pct = task.progressPercent
                    val progressText = when {
                        task.totalBytes > 0L -> {
                            val effectiveTotal = task.totalBytes.coerceAtLeast(task.bytesDownloaded)
                            "$downloadedStr / ${FormatUtils.formatBytes(effectiveTotal)} ($pct%)"
                        }
                        task.bytesDownloaded > 0L && task.reportedProgressPercent != null -> {
                            "$downloadedStr ($pct%)"
                        }
                        task.bytesDownloaded > 0L -> {
                            "$downloadedStr / Unknown"
                        }
                        task.reportedProgressPercent != null -> {
                            "0 B ($pct%)"
                        }
                        else -> {
                            "0 B / Unknown"
                        }
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.status == DownloadStatus.DOWNLOADING) {
                        Text(
                            text = when {
                                task.speedBytesPerSec > 0L && task.etaSeconds > 0L ->
                                    "${FormatUtils.formatSpeed(task.speedBytesPerSec)} • ETA ${FormatUtils.formatEta(task.etaSeconds)}"
                                task.speedBytesPerSec > 0L ->
                                    FormatUtils.formatSpeed(task.speedBytesPerSec)
                                pct >= 99 && (task.bytesDownloaded > 0L || task.reportedProgressPercent != null) ->
                                    "Finalizing media…"
                                task.streamUrl.startsWith("yt-dlp://") ->
                                    "Connecting to source stream…"
                                else ->
                                    "Connecting…"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!task.errorMessage.isNullOrBlank()) {
                val error = task.errorMessage.orEmpty()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
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
                            shape = RoundedCornerShape(14.dp),
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
                        val canResume = task.supportsRange || task.streamUrl.startsWith("yt-dlp://")
                        FilledTonalButton(
                            onClick = onResume,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("resume_button_${task.id}")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (canResume) "Resume" else "Restart")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        FilledTonalButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(14.dp),
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
