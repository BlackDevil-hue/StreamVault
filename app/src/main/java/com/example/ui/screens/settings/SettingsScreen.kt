package com.example.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DefaultQualityPreference
import com.example.domain.model.EqualizerPreset
import com.example.ui.components.FormatUtils
import com.example.ui.components.IconMedallion
import com.example.ui.components.VaultAmbientBackground
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showQualityDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearBrowserDialog by remember { mutableStateOf(false) }
    var showPrivacySheet by remember { mutableStateOf(false) }
    var hasSystemNotificationPermission by remember {
        mutableStateOf(hasPostNotificationPermission(context))
    }
    var askedNotificationOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageInfo()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasSystemNotificationPermission = hasPostNotificationPermission(context)
                if (hasSystemNotificationPermission) {
                    askedNotificationOnce = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            val folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Custom Folder"
            viewModel.setCustomFolder(uri.toString(), folderName)
            scope.launch { snackbarHostState.showSnackbar("Download folder set to $folderName") }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasSystemNotificationPermission = granted || hasPostNotificationPermission(context)
        viewModel.setNotificationsEnabled(granted)
        if (!granted) {
            askedNotificationOnce = true
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Notification permission denied. Allow it in Android Settings.",
                    actionLabel = "Settings"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openAppNotificationSettings(context)
                }
            }
        }
    }

    VaultAmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
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
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Storage Summary Card
                    item {
                        val storage = uiState.storageInfo
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        IconMedallion(icon = Icons.Rounded.SdStorage)
                                        Column {
                                            Text("Storage Overview", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = "Device, vault & cache usage",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = "Cache: ${FormatUtils.formatProgressBytes(uiState.tempCacheBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                if (storage != null) {
                                    val usedTotal = (storage.totalBytes - storage.availableBytes).coerceAtLeast(0L)
                                    val fraction = if (storage.totalBytes > 0L) {
                                        (usedTotal.toFloat() / storage.totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else 0f

                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(9.dp)
                                            .clip(CircleShape),
                                        strokeCap = StrokeCap.Round,
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${FormatUtils.formatBytes(storage.availableBytes)} available",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        Text(
                                            text = "StreamVault: ${FormatUtils.formatProgressBytes(storage.appDownloadsBytes)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Download Engine Preferences
                    item {
                        SectionCard(title = "Download Engine") {
                            SettingsActionRow(
                                icon = Icons.Rounded.HighQuality,
                                title = "Default Video Quality",
                                subtitle = uiState.settings.defaultQuality.label,
                                testTag = "settings_default_quality",
                                onClick = { showQualityDialog = true },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsActionRow(
                                icon = Icons.Rounded.Folder,
                                title = "Download Folder",
                                subtitle = uiState.settings.customFolderName,
                                testTag = "settings_download_folder",
                                onClick = { folderPicker.launch(null) },
                                trailingContent = if (uiState.settings.customTreeUri != null) {
                                    {
                                        TextButton(
                                            onClick = {
                                                viewModel.setCustomFolder(null, "Downloads/StreamVault")
                                            }
                                        ) {
                                            Text("Reset")
                                        }
                                    }
                                } else {
                                    {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconMedallion(icon = Icons.Rounded.Speed, size = 38.dp, iconSize = 19.dp)
                                        Text("Max Simultaneous Downloads", style = MaterialTheme.typography.bodyLarge)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = "${uiState.settings.maxConcurrentDownloads}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Slider(
                                    value = uiState.settings.maxConcurrentDownloads.toFloat(),
                                    onValueChange = { viewModel.setMaxConcurrentDownloads(kotlin.math.round(it).toInt()) },
                                    valueRange = 1f..5f,
                                    steps = 3,
                                    modifier = Modifier.testTag("settings_max_concurrent_slider")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Wifi,
                                title = "Wi-Fi Only Downloads",
                                subtitle = "Pause cellular downloads automatically",
                                checked = uiState.settings.wifiOnly,
                                testTag = "settings_wifi_only_switch",
                                onCheckedChange = viewModel::setWifiOnly
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.FolderSpecial,
                                title = "Organize by Quality",
                                subtitle = "Group saved files into resolution subfolders",
                                checked = uiState.settings.organizeByQuality,
                                testTag = "settings_organize_switch",
                                onCheckedChange = viewModel::setOrganizeByQuality
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Autorenew,
                                title = "Auto-Retry Interrupted Downloads",
                                subtitle = "Automatically retry temporary network drops",
                                checked = uiState.settings.autoRetryFailed,
                                testTag = "settings_auto_retry_switch",
                                onCheckedChange = viewModel::setAutoRetryFailed
                            )
                        }
                    }

                    // Player & Playback Preferences
                    item {
                        SectionCard(title = "Video & Audio Player") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconMedallion(
                                        icon = Icons.Rounded.SlowMotionVideo,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                        size = 38.dp,
                                        iconSize = 19.dp
                                    )
                                    Column {
                                        Text("Default Playback Speed", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "Applied when opening local video or audio",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                        val selected = kotlin.math.abs(uiState.settings.defaultPlaybackSpeed - speed) < 0.01f
                                        FilterChip(
                                            selected = selected,
                                            onClick = { viewModel.setDefaultPlaybackSpeed(speed) },
                                            label = { Text("${speed}x") },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            modifier = Modifier.testTag("settings_speed_${speed}")
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconMedallion(
                                        icon = Icons.Rounded.FastForward,
                                        size = 38.dp,
                                        iconSize = 19.dp
                                    )
                                    Column {
                                        Text("Seek & Skip Interval", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "Used for skip buttons and double-tap seek",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(5, 10, 15, 30).forEach { seconds ->
                                        FilterChip(
                                            selected = uiState.settings.seekIntervalSeconds == seconds,
                                            onClick = { viewModel.setSeekIntervalSeconds(seconds) },
                                            label = { Text("${seconds}s") },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            modifier = Modifier.testTag("settings_seek_${seconds}")
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Bookmark,
                                title = "Remember Video Position",
                                subtitle = "Resume local videos from where you left off",
                                checked = uiState.settings.rememberPlaybackPosition,
                                testTag = "settings_remember_position_switch",
                                onCheckedChange = viewModel::setRememberPlaybackPosition
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.PlayCircleOutline,
                                title = "Auto-Play Next Video",
                                subtitle = "Continue to the next saved video when one finishes",
                                checked = uiState.settings.autoPlayNextVideo,
                                testTag = "settings_autoplay_next_switch",
                                onCheckedChange = viewModel::setAutoPlayNextVideo
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.GraphicEq,
                                title = "Audio Equalizer & Bass Boost",
                                subtitle = if (uiState.settings.equalizerEnabled) {
                                    "${uiState.settings.equalizerPreset.label} preset • Bass ${uiState.settings.bassBoostStrength / 10}%"
                                } else {
                                    "Disabled — open Audio Library for 5-band sliders"
                                },
                                checked = uiState.settings.equalizerEnabled,
                                testTag = "settings_equalizer_switch",
                                onCheckedChange = viewModel::setEqualizerEnabled
                            )

                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }.forEach { preset ->
                                        val selected = uiState.settings.equalizerEnabled &&
                                            uiState.settings.equalizerPreset == preset
                                        FilterChip(
                                            selected = selected,
                                            onClick = { viewModel.selectEqualizerPreset(preset) },
                                            label = { Text(preset.label) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            modifier = Modifier.testTag("settings_eq_preset_${preset.name}")
                                        )
                                    }
                                }
                                Text(
                                    text = "Fine-tune individual 5-band frequencies & Bass Boost from the Audio Library Equalizer button.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // YouTube & In-App Browser Settings
                    item {
                        SectionCard(title = "YouTube & Browser") {
                            SettingsSwitchRow(
                                icon = Icons.Rounded.Shield,
                                title = "Ad & Tracker Filtering",
                                subtitle = "Block common ad endpoints and promotional overlays",
                                checked = uiState.settings.browserAdBlockerEnabled,
                                testTag = "settings_browser_adblock_switch",
                                onCheckedChange = viewModel::setBrowserAdBlockerEnabled
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsSwitchRow(
                                icon = Icons.Rounded.DesktopWindows,
                                title = "Desktop Site by Default",
                                subtitle = "Request full desktop YouTube layout in the browser",
                                checked = uiState.settings.browserDesktopMode,
                                testTag = "settings_browser_desktop_switch",
                                onCheckedChange = viewModel::setBrowserDesktopMode
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            SettingsActionRow(
                                icon = Icons.Rounded.Cookie,
                                title = "Clear Browser Cookies & Web Data",
                                subtitle = "Sign out of YouTube browser session and reset web cache",
                                testTag = "settings_clear_browser_data",
                                onClick = { showClearBrowserDialog = true },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }

                    // Appearance & Notifications
                    item {
                        SectionCard(title = "Appearance & Alerts") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconMedallion(
                                        icon = Icons.Rounded.BrightnessMedium,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                        size = 38.dp,
                                        iconSize = 19.dp
                                    )
                                    Text("Theme Mode", style = MaterialTheme.typography.bodyLarge)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ThemePreference.entries.forEach { pref ->
                                        val (label, icon) = when (pref) {
                                            ThemePreference.DARK -> "Dark" to Icons.Rounded.DarkMode
                                            ThemePreference.LIGHT -> "Light" to Icons.Rounded.LightMode
                                            ThemePreference.SYSTEM -> "System" to Icons.Rounded.BrightnessAuto
                                        }
                                        FilterChip(
                                            selected = uiState.settings.themePreference == pref,
                                            onClick = { viewModel.setThemePreference(pref) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(label) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                                            ),
                                            modifier = Modifier.testTag("settings_theme_${pref.name}")
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            SettingsSwitchRow(
                                icon = Icons.Rounded.Palette,
                                title = "Material You Dynamic Color",
                                subtitle = if (supportsDynamicColor) {
                                    "Match app accents to your Android wallpaper"
                                } else {
                                    "Requires Android 12 or newer"
                                },
                                checked = supportsDynamicColor && uiState.settings.dynamicColorEnabled,
                                enabled = supportsDynamicColor,
                                testTag = "settings_dynamic_color_switch",
                                onCheckedChange = viewModel::setDynamicColorEnabled
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                            val requiresTiramisuPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            val activity = remember(context) { context.findActivity() }
                            val canRequestRuntimePermission = !askedNotificationOnce || (
                                activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                                    activity,
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            )

                            SettingsSwitchRow(
                                icon = Icons.Rounded.Notifications,
                                title = "Download Notifications",
                                subtitle = if (requiresTiramisuPermission && !hasSystemNotificationPermission) {
                                    "Android 13+ notification permission needed"
                                } else {
                                    "Show live progress and completion alerts"
                                },
                                checked = uiState.settings.notificationsEnabled && hasSystemNotificationPermission,
                                testTag = "settings_notifications_switch",
                                onCheckedChange = { enable ->
                                    if (enable && requiresTiramisuPermission && !hasSystemNotificationPermission) {
                                        if (canRequestRuntimePermission) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            openAppNotificationSettings(context)
                                        }
                                    } else {
                                        viewModel.setNotificationsEnabled(enable)
                                    }
                                }
                            )

                            if (requiresTiramisuPermission) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                                SettingsActionRow(
                                    icon = if (hasSystemNotificationPermission) {
                                        Icons.Rounded.NotificationsActive
                                    } else {
                                        Icons.Rounded.NotificationsOff
                                    },
                                    title = "Android 13+ Notification Permission",
                                    subtitle = if (hasSystemNotificationPermission) {
                                        "Granted — download progress & media controls enabled"
                                    } else if (canRequestRuntimePermission) {
                                        "Tap to grant permission for background alerts"
                                    } else {
                                        "Blocked in Android — tap to open System Notification Settings"
                                    },
                                    testTag = "settings_notification_permission_row",
                                    iconTint = if (hasSystemNotificationPermission) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    iconContainer = if (hasSystemNotificationPermission) {
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                    },
                                    onClick = {
                                        if (hasSystemNotificationPermission || !canRequestRuntimePermission) {
                                            openAppNotificationSettings(context)
                                        } else {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    trailingContent = {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (hasSystemNotificationPermission) {
                                                MaterialTheme.colorScheme.tertiaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.primaryContainer
                                            }
                                        ) {
                                            Text(
                                                text = when {
                                                    hasSystemNotificationPermission -> "Allowed"
                                                    canRequestRuntimePermission -> "Allow"
                                                    else -> "Settings"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (hasSystemNotificationPermission) {
                                                    MaterialTheme.colorScheme.onTertiaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                },
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Maintenance & Legal
                    item {
                        SectionCard(title = "Data & Privacy") {
                            SettingsActionRow(
                                icon = Icons.Rounded.CleaningServices,
                                title = "Clear Temporary Cache",
                                subtitle = "Free ${FormatUtils.formatProgressBytes(uiState.tempCacheBytes)} of cached thumbnails and temp files",
                                testTag = "settings_clear_cache",
                                onClick = {
                                    viewModel.clearTempCache { freed ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Cleared ${FormatUtils.formatProgressBytes(freed)} of temporary cache"
                                            )
                                        }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            SettingsActionRow(
                                icon = Icons.Rounded.DeleteSweep,
                                title = "Clear Download History",
                                subtitle = "Remove completed download entries",
                                testTag = "settings_clear_history",
                                onClick = { showClearHistoryDialog = true },
                                iconTint = MaterialTheme.colorScheme.error,
                                iconContainer = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            SettingsActionRow(
                                icon = Icons.Rounded.PrivacyTip,
                                title = "Privacy Policy & Terms",
                                subtitle = "Security, HTTPS, and authorized media policy",
                                testTag = "settings_privacy",
                                onClick = { showPrivacySheet = true },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Default Video Quality") },
            text = {
                Column {
                    DefaultQualityPreference.entries.forEach { pref ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.setDefaultQuality(pref)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.settings.defaultQuality == pref,
                                onClick = {
                                    viewModel.setDefaultQuality(pref)
                                    showQualityDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(pref.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            }
        )
    }

    if (showClearBrowserDialog) {
        AlertDialog(
            onDismissRequest = { showClearBrowserDialog = false },
            title = { Text("Clear Browser Data?") },
            text = {
                Text("This clears all in-app browser cookies, YouTube sign-in sessions, and WebView site storage.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                        }
                        showClearBrowserDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Browser cookies and web storage cleared") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearBrowserDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearHistoryDialog) {
        var deleteFilesToo by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear Download History") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This will remove all completed records from your Download History list.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = deleteFilesToo, onCheckedChange = { deleteFilesToo = it })
                        Text("Also delete downloaded files from storage", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearDownloadHistory(deleteFilesToo)
                        showClearHistoryDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Download history cleared") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPrivacySheet) {
        ModalBottomSheet(onDismissRequest = { showPrivacySheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Privacy Policy & Authorized Use", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "• Zero Personal Data Collection: StreamVault stores download history, queue state, and settings strictly on your device using Room and DataStore.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• HTTPS & SSRF Protection: All external URLs are validated before network requests, blocking private/local network targets and enforcing HTTPS.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "• Copyright & DRM Respect: StreamVault only downloads directly permitted and open media sources. It does not bypass DRM, paywalls, or platform download restrictions.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconContainer: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconMedallion(
            icon = icon,
            tint = iconTint,
            containerColor = iconContainer,
            size = 38.dp,
            iconSize = 19.dp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailingContent?.invoke()
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconMedallion(icon = icon, size = 38.dp, iconSize = 19.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
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
