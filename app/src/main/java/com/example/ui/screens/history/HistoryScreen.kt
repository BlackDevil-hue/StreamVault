package com.example.ui.screens.history

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DownloadTask
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FormatUtils
import com.example.ui.components.MediaThumbnail
import com.example.ui.components.QualityBadge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onPlayVideo: (String) -> Unit,
    onGoToHome: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Download History")
                        Text(
                            text = "${uiState.items.size} saved files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1280.dp)
                    .fillMaxSize()
            ) {
                // Search & Filter Bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Search downloads by title or format...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("history_search_input")
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(HistoryFilter.entries) { filter ->
                        FilterChip(
                            selected = uiState.selectedFilter == filter,
                            onClick = { viewModel.selectFilter(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("history_filter_${filter.name}")
                        )
                    }
                }

                if (uiState.items.isEmpty()) {
                    EmptyStatePanel(
                        icon = Icons.Rounded.History,
                        title = if (uiState.searchQuery.isBlank()) "No Saved Videos Yet" else "No Matching Videos",
                        subtitle = if (uiState.searchQuery.isBlank()) {
                            "Completed downloads appear here with instant offline playback, sharing, and file details."
                        } else {
                            "Try clearing your search or switching quality filters."
                        },
                        actionLabel = if (uiState.searchQuery.isBlank()) "Download a Video" else null,
                        onAction = if (uiState.searchQuery.isBlank()) onGoToHome else null,
                        modifier = Modifier.widthIn(max = 560.dp).align(Alignment.CenterHorizontally)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 420.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(uiState.items, key = { it.id }) { item ->
                            HistoryItemCard(
                                item = item,
                                onPlay = { onPlayVideo(item.id) },
                                onShare = {
                                    val shareIntent = viewModel.buildShareIntent(item)
                                    if (shareIntent != null) {
                                        context.startActivity(Intent.createChooser(shareIntent, "Share video"))
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Video file could not be found on device.")
                                        }
                                    }
                                },
                                onOpenLocation = {
                                    val openIntent = viewModel.buildOpenLocationIntent(item)
                                    val opened = runCatching { context.startActivity(openIntent) }.isSuccess
                                    if (!opened) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Saved at: ${item.localPath ?: "Downloads/StreamVault"}")
                                        }
                                    }
                                },
                                onShowDetails = { viewModel.openDetails(item) },
                                onDelete = { viewModel.requestDelete(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    // File Details Bottom Sheet
    uiState.selectedForDetails?.let { detailItem ->
        ModalBottomSheet(onDismissRequest = viewModel::closeDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "File Details",
                    style = MaterialTheme.typography.headlineSmall
                )
                DetailRow("Title", detailItem.title)
                DetailRow("File Name", detailItem.fileName)
                DetailRow("Resolution", detailItem.resolutionLabel)
                DetailRow("Format", "${detailItem.format} (${detailItem.mimeType})")
                DetailRow("File Size", FormatUtils.formatBytes(detailItem.totalBytes))
                detailItem.durationMs?.let { DetailRow("Duration", FormatUtils.formatDuration(it)) }
                DetailRow("Downloaded", FormatUtils.formatDate(detailItem.completedAt ?: detailItem.createdAt))
                DetailRow("Location", detailItem.localPath ?: detailItem.localUri ?: "Downloads/StreamVault")
                DetailRow("Source URL", detailItem.sourceUrl)
                detailItem.sha256?.let { DetailRow("SHA-256", it.take(24) + "...") }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Delete Confirmation Dialog
    uiState.pendingDeleteItem?.let { target ->
        var deleteFileAlso by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("Delete Download?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Remove \"${target.title}\" from your download history?")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = deleteFileAlso,
                            onCheckedChange = { deleteFileAlso = it }
                        )
                        Text(
                            text = "Also delete media file from storage",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDelete(deleteFileAlso) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    item: DownloadTask,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onOpenLocation: () -> Unit,
    onShowDetails: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MediaThumbnail(
                    thumbnailUrl = item.thumbnailUrl,
                    isAudioOnly = item.isAudioOnly,
                    contentDescription = item.title,
                    durationMs = item.durationMs,
                    modifier = Modifier.size(width = 104.dp, height = 68.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QualityBadge(label = item.resolutionLabel)
                        QualityBadge(label = item.format)
                    }
                    Text(
                        text = "${FormatUtils.formatBytes(item.totalBytes)} • ${FormatUtils.formatDate(item.completedAt ?: item.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("history_play_${item.id}"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_share_${item.id}")
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share ${item.title}")
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("history_delete_${item.id}")
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete ${item.title}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open File Location") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenLocation()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("View File Details") },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShowDetails()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
