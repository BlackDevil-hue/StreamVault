package com.example.ui.screens.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.CompactActiveDownloadCard
import com.example.ui.components.CompactRecentHistoryRow
import com.example.ui.components.ErrorBanner

@Composable
fun HomeScreen(
    onNavigateToOptions: (String) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onOpenBrowser: () -> Unit,
    onPlayVideo: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is HomeNavigationEvent.OpenDownloadOptions -> onNavigateToOptions(event.url)
            }
        }
    }

    // Automatically inspect clipboard when Home screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                viewModel.onClipboardTextInspected(clipText)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val tabletLayout = maxWidth >= 840.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = if (tabletLayout) 1040.dp else 720.dp),
            state = listState,
            contentPadding = PaddingValues(horizontal = if (tabletLayout) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Compact brand header with a warm media palette.
            item {
                HomeHeroHeader()
            }

            // Smart Clipboard Auto-Detection Banner
            item {
                AnimatedVisibility(
                    visible = uiState.clipboardDetectedUrl != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val detected = uiState.clipboardDetectedUrl ?: ""
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .testTag("clipboard_detected_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Link detected in clipboard",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = detected,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.analyzeClipboardUrl() },
                                modifier = Modifier.testTag("clipboard_analyze_button")
                            ) {
                                Text("Analyze")
                            }
                            IconButton(
                                onClick = { viewModel.dismissClipboardSuggestion() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss detected link",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quick access", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onOpenBrowser,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Browse")
                        }
                        FilledTonalButton(
                            onClick = { coroutineScope.launch { listState.animateScrollToItem(4) } },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Playlist & batch")
                        }
                        FilledTonalButton(
                            onClick = onNavigateToDownloads,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("My downloads")
                        }
                    }
                }
            }

            // URL Input + Paste + Analyze Video Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Download from a link",
                            style = MaterialTheme.typography.titleLarge
                        )
                        OutlinedTextField(
                            value = uiState.urlInput,
                            onValueChange = viewModel::onUrlChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("url_input_field"),
                            placeholder = {
                                Text(
                                    "Paste a YouTube, Instagram, X, or media link...",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingIcon = {
                                if (uiState.urlInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.onUrlChanged("") },
                                        modifier = Modifier.testTag("clear_url_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Clear,
                                            contentDescription = "Clear URL"
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    viewModel.analyzeCurrentUrl()
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )

                        AnimatedVisibility(
                            visible = uiState.errorMessage != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            uiState.errorMessage?.let { msg ->
                                ErrorBanner(message = msg)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    viewModel.pasteFromClipboard(clipText)
                                },
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("paste_link_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Paste Link")
                            }

                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    viewModel.analyzeCurrentUrl()
                                },
                                enabled = !uiState.isAnalyzing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("analyze_video_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                if (uiState.isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Analyzing...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Analyze Video")
                                }
                            }
                        }
                        TextButton(
                            onClick = onOpenBrowser,
                            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("open_browser_button")
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Browse the web instead")
                        }
                    }
                }
            }

            item {
                BatchDownloadCard(
                    input = uiState.batchInput,
                    playlistStartIndex = uiState.playlistStartIndex,
                    playlistEndIndex = uiState.playlistEndIndex,
                    items = uiState.batchItems,
                    message = uiState.batchMessage,
                    isAnalyzing = uiState.isAnalyzingBatch,
                    isQueueing = uiState.isQueueingBatch,
                    onInputChanged = viewModel::onBatchInputChanged,
                    onPlaylistStartChanged = viewModel::onPlaylistStartIndexChanged,
                    onPlaylistEndChanged = viewModel::onPlaylistEndIndexChanged,
                    onAnalyze = {
                        keyboardController?.hide()
                        viewModel.analyzeBatch()
                    },
                    onToggleItem = viewModel::setBatchItemSelected,
                    onSelectAll = { viewModel.setAllBatchItemsSelected(true) },
                    onClearSelection = { viewModel.setAllBatchItemsSelected(false) },
                    onQueueSelected = viewModel::queueSelectedBatch
                )
            }

            // Active Downloading Status Cards
            if (uiState.activeDownloads.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Downloads (${uiState.activeDownloads.size})",
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(onClick = onNavigateToDownloads) {
                            Text("Manage All")
                        }
                    }
                }
                items(uiState.activeDownloads.take(3), key = { it.id }) { task ->
                    CompactActiveDownloadCard(
                        task = task,
                        onClick = onNavigateToDownloads
                    )
                }
            }

            // Recent Downloads
            if (uiState.recentDownloads.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Downloads",
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(onClick = onNavigateToHistory) {
                            Text("See History")
                        }
                    }
                }
                items(uiState.recentDownloads, key = { it.id }) { item ->
                    CompactRecentHistoryRow(
                        task = item,
                        onPlay = { onPlayVideo(item.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun BatchDownloadCard(
    input: String,
    playlistStartIndex: String,
    playlistEndIndex: String,
    items: List<BatchVideoItem>,
    message: String?,
    isAnalyzing: Boolean,
    isQueueing: Boolean,
    onInputChanged: (String) -> Unit,
    onPlaylistStartChanged: (String) -> Unit,
    onPlaylistEndChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onQueueSelected: () -> Unit
) {
    val readyCount = items.count { it.result != null && !it.queued }
    val selectedCount = items.count { it.selected && it.result != null && !it.queued }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Batch downloads", style = MaterialTheme.typography.titleLarge)
                    Text("Add links or choose a playlist range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                modifier = Modifier.fillMaxWidth().testTag("batch_url_input"),
                placeholder = { Text("https://…\nhttps://…", maxLines = 2) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(
                            onClick = { onInputChanged("") },
                            modifier = Modifier.testTag("clear_batch_urls_button")
                        ) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear batch links")
                        }
                    }
                },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Default)
            )
            Text(
                "YouTube playlist item range",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = playlistStartIndex,
                    onValueChange = onPlaylistStartChanged,
                    modifier = Modifier.weight(1f).testTag("playlist_start_index"),
                    label = { Text("Start item") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = playlistEndIndex,
                    onValueChange = onPlaylistEndChanged,
                    modifier = Modifier.weight(1f).testTag("playlist_end_index"),
                    label = { Text("End item") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            Text(
                "Choose which playlist items to analyze; uncheck videos to skip them. The batch limit is 20 videos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onAnalyze,
                enabled = !isAnalyzing && !isQueueing && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("analyze_batch_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Analyzing links…")
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Analyze links")
                }
            }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (readyCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "$selectedCount of $readyCount selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onSelectAll) { Text("Select all") }
                    TextButton(onClick = onClearSelection) { Text("Deselect all") }
                }
            }
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.selected && item.result != null && !item.queued,
                        onCheckedChange = { onToggleItem(item.url, it) },
                        enabled = item.result != null && !item.queued
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.result?.title ?: item.error ?: item.url,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                item.queued -> "Added to download queue"
                                item.result != null -> item.result.providerName
                                item.error != null -> item.error
                                else -> item.url
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (readyCount > 0) {
                FilledTonalButton(
                    onClick = onQueueSelected,
                    enabled = !isQueueing && selectedCount > 0,
                    modifier = Modifier.fillMaxWidth().height(50.dp).testTag("queue_batch_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isQueueing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Adding to queue…")
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Queue selected ($selectedCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroHeader() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFFFD66B))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFFFD33D), Color(0xFFFF8A00))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_art),
                        contentDescription = "StreamVault Logo",
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                    Column {
                        Text(
                            text = "StreamVault",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF241600),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "VIDEO & MUSIC DOWNLOADER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4D2A00)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Your media, ready for offline.",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF241600)
                    )
                    Text(
                        text = "Paste a link or browse for something to save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4D2A00)
                    )
                }
            }
        }
    }
}

