package com.example.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.CompactRecentHistoryRow
import com.example.ui.components.ErrorBanner
import com.example.ui.components.IconMedallion
import com.example.ui.components.VaultAmbientBackground
import com.example.ui.theme.LocalStreamVaultExtras

@Composable
fun HomeScreen(
    onNavigateToOptions: (String) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onOpenBrowser: (String?) -> Unit,
    onPlayVideo: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var hasNotificationPermission by remember { mutableStateOf(hasPostNotificationPermission(context)) }
    var askedNotificationPermissionOnce by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted || hasPostNotificationPermission(context)
        if (granted) {
            viewModel.onNotificationPermissionGranted()
        } else {
            askedNotificationPermissionOnce = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is HomeNavigationEvent.OpenDownloadOptions -> onNavigateToOptions(event.url)
            }
        }
    }

    // Automatically inspect clipboard & notification permission when Home screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasPostNotificationPermission(context)
                hasNotificationPermission = granted
                if (granted) {
                    askedNotificationPermissionOnce = false
                    viewModel.onNotificationPermissionGranted()
                }
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                viewModel.onClipboardTextInspected(clipText)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    VaultAmbientBackground {
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
                contentPadding = PaddingValues(
                    start = if (tabletLayout) 24.dp else 16.dp,
                    end = if (tabletLayout) 24.dp else 16.dp,
                    top = statusBarTopPadding + 14.dp,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Brand Hero Banner with vault artwork & live queue indicator
                item(key = "hero_header") {
                    HomeHeroHeader(activeDownloadCount = uiState.activeDownloads.size)
                }

                // Android 13+ Notification Permission Onboarding Card
                item(key = "notification_permission_banner") {
                    val showPermissionPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasNotificationPermission &&
                        !uiState.notificationPromptDismissed
                    AnimatedVisibility(
                        visible = showPermissionPrompt,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        val activity = remember(context) { context.findActivity() }
                        val canAskSystemAgain = !askedNotificationPermissionOnce || (
                            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize()
                                .testTag("home_notification_permission_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    IconMedallion(
                                        icon = Icons.Rounded.NotificationsActive,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        size = 40.dp,
                                        iconSize = 20.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Enable download & playback alerts",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = if (canAskSystemAgain) {
                                                "Android 13+ requires permission to show live download progress and background audio player controls."
                                            } else {
                                                "Notifications are blocked by Android. Open Notification Settings to turn them on."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { viewModel.dismissNotificationPrompt() },
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("home_dismiss_notifications_button")
                                    ) {
                                        Text("Later")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canAskSystemAgain) {
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                openAppNotificationSettings(context)
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("home_allow_notifications_button")
                                    ) {
                                        Text(if (canAskSystemAgain) "Allow" else "Open Settings")
                                    }
                                }
                            }
                        }
                    }
                }

                // Smart Clipboard Auto-Detection Banner
                item(key = "clipboard_banner") {
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
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconMedallion(
                                    icon = Icons.Rounded.AutoAwesome,
                                    size = 38.dp,
                                    iconSize = 20.dp
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
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("clipboard_analyze_button")
                                ) {
                                    Text("Analyze")
                                }
                                IconButton(
                                    onClick = { viewModel.dismissClipboardSuggestion() },
                                    modifier = Modifier.size(40.dp)
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

                // Quick Access Action Grid
                item(key = "quick_access") {
                    val quickActions = listOf(
                        QuickAccessAction(
                            label = "Browse",
                            subtitle = "YouTube & web",
                            icon = Icons.Rounded.Public,
                            tint = MaterialTheme.colorScheme.primary,
                            testTag = "quick_access_browse",
                            onClick = { onOpenBrowser(null) }
                        ),
                        QuickAccessAction(
                            label = "YT Music",
                            subtitle = "Songs & albums",
                            icon = Icons.Rounded.Album,
                            tint = MaterialTheme.colorScheme.secondary,
                            testTag = "quick_access_yt_music",
                            onClick = { onOpenBrowser("https://music.youtube.com") }
                        ),
                        QuickAccessAction(
                            label = "My downloads",
                            subtitle = if (uiState.activeDownloads.isNotEmpty()) {
                                "${uiState.activeDownloads.size} active in queue"
                            } else {
                                "Queue & progress"
                            },
                            icon = Icons.Rounded.Download,
                            tint = MaterialTheme.colorScheme.tertiary,
                            testTag = "quick_access_downloads",
                            onClick = onNavigateToDownloads
                        ),
                        QuickAccessAction(
                            label = "History",
                            subtitle = "Saved videos",
                            icon = Icons.Rounded.History,
                            tint = MaterialTheme.colorScheme.primary,
                            testTag = "quick_access_history",
                            onClick = onNavigateToHistory
                        )
                    )
                    val columns = if (tabletLayout) 4 else 2

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Quick access",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            quickActions.chunked(columns).forEach { rowActions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowActions.forEach { action ->
                                        QuickAccessPill(
                                            icon = action.icon,
                                            label = action.label,
                                            subtitle = action.subtitle,
                                            tint = action.tint,
                                            onClick = action.onClick,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag(action.testTag)
                                        )
                                    }
                                    repeat(columns - rowActions.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // URL Input + Paste + Analyze Video Card
                item(key = "single_link_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                IconMedallion(icon = Icons.Rounded.Bolt)
                                Column {
                                    Text(
                                        text = "Download from a link",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "Inspect resolutions, audio tracks & file sizes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

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
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                                        Text("Analyze")
                                    }
                                }
                            }

                            TextButton(
                                onClick = { onOpenBrowser(null) },
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .height(48.dp)
                                    .testTag("open_browser_button")
                            ) {
                                Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Browse the web instead")
                            }
                        }
                    }
                }

                // Batch & Playlist Card
                item(key = "batch_card") {
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

                // Recent Downloads
                if (uiState.recentDownloads.isNotEmpty()) {
                    item(key = "recent_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent Downloads",
                                style = MaterialTheme.typography.titleLarge
                            )
                            TextButton(
                                onClick = onNavigateToHistory,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("See History")
                            }
                        }
                    }
                    items(uiState.recentDownloads, key = { "recent_${it.id}" }) { item ->
                        CompactRecentHistoryRow(
                            task = item,
                            onPlay = { onPlayVideo(item.id) }
                        )
                    }
                }

                item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

private data class QuickAccessAction(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val testTag: String,
    val onClick: () -> Unit
)

@Composable
private fun QuickAccessPill(
    icon: ImageVector,
    label: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconMedallion(
                    icon = Icons.Rounded.PlaylistAdd,
                    tint = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                )
                Column {
                    Text("Batch downloads", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Add multiple links or choose a playlist range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("batch_url_input"),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag("playlist_start_index"),
                    label = { Text("Start item") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
                OutlinedTextField(
                    value = playlistEndIndex,
                    onValueChange = onPlaylistEndChanged,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("playlist_end_index"),
                    label = { Text("End item") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
            Text(
                "Choose which playlist items to analyze; uncheck videos to skip them. The batch limit is 50 videos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = onAnalyze,
                enabled = !isAnalyzing && !isQueueing && input.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("analyze_batch_button"),
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
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(10.dp),
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
                Button(
                    onClick = onQueueSelected,
                    enabled = !isQueueing && selectedCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("queue_batch_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isQueueing) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
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
private fun HomeHeroHeader(activeDownloadCount: Int) {
    val extras = LocalStreamVaultExtras.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, extras.heroAccentBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_vault),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                extras.heroScrimStart,
                                extras.heroScrimEnd
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_art),
                            contentDescription = "StreamVault Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    RoundedCornerShape(12.dp)
                                )
                        )
                        Column {
                            Text(
                                text = "StreamVault",
                                style = MaterialTheme.typography.titleMedium,
                                color = extras.heroOnScrim,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "OFFLINE MEDIA VAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.42f),
                        border = BorderStroke(1.dp, extras.heroOnScrim.copy(alpha = 0.22f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (activeDownloadCount > 0) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                            )
                            Text(
                                text = if (activeDownloadCount > 0) "$activeDownloadCount active" else "Ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = extras.heroOnScrim
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun hasPostNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
