package com.example.ui.screens.options

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.MediaStreamOption
import com.example.ui.components.ErrorBanner
import com.example.ui.components.FormatUtils
import com.example.ui.components.IconMedallion
import com.example.ui.components.MediaThumbnail
import com.example.ui.components.QualityBadge
import com.example.ui.components.VaultAmbientBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadOptionsScreen(
    onBack: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onPlayVideo: (String) -> Unit,
    viewModel: DownloadOptionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with download regardless of notification permission result
        viewModel.startDownload()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                DownloadOptionsEvent.NavigateToDownloads -> onNavigateToDownloads()
                is DownloadOptionsEvent.PlayExisting -> onPlayVideo(event.downloadId)
                is DownloadOptionsEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    VaultAmbientBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Download Options") },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("options_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            bottomBar = {
                val analysis = uiState.analysis
                if (!uiState.isLoading && uiState.errorMessage == null && analysis != null) {
                    val selected = uiState.selectedOption
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                        tonalElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            uiState.settings.notificationsEnabled &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            viewModel.startDownload()
                                        }
                                    },
                                    enabled = selected != null && !uiState.isStartingDownload,
                                    modifier = Modifier
                                        .widthIn(max = 720.dp)
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("start_download_button"),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    if (uiState.isStartingDownload) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        val sizeLabel = selected?.estimatedBytes?.let { " • ${FormatUtils.formatBytes(it)}" } ?: ""
                                        Text(
                                            text = "Download ${selected?.qualityLabel ?: ""}$sizeLabel",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                when {
                    uiState.isLoading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = "Inspecting media stream & qualities…",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = uiState.sourceUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    uiState.errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ErrorBanner(
                                message = uiState.errorMessage ?: "Unable to analyze video.",
                                actionLabel = "Retry",
                                onAction = viewModel::retryAnalysis
                            )
                            FilledTonalButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Try Another Link")
                            }
                        }
                    }

                    uiState.analysis != null -> {
                        val analysis = uiState.analysis!!
                        LazyColumn(
                            modifier = Modifier
                                .widthIn(max = 720.dp)
                                .fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Video Metadata Preview Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    shape = RoundedCornerShape(22.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val selectedIsAudioOnly = uiState.selectedOption?.isAudioOnly == true ||
                                            analysis.streams.all { it.isAudioOnly }
                                        val previewTitle = if (selectedIsAudioOnly) {
                                            analysis.audioTitle?.takeIf { it.isNotBlank() } ?: analysis.title
                                        } else {
                                            analysis.title
                                        }
                                        val previewAuthor = if (selectedIsAudioOnly && analysis.providerName == "YouTube") {
                                            analysis.audioArtist?.takeIf { it.isNotBlank() }
                                        } else if (selectedIsAudioOnly) {
                                            analysis.audioArtist?.takeIf { it.isNotBlank() } ?: analysis.author
                                        } else {
                                            analysis.author
                                        }
                                        MediaThumbnail(
                                            thumbnailUrl = analysis.thumbnailUrl,
                                            isAudioOnly = selectedIsAudioOnly,
                                            contentDescription = previewTitle,
                                            durationMs = analysis.durationMs,
                                            modifier = Modifier.size(width = 124.dp, height = 84.dp)
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = analysis.providerName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                            Text(
                                                text = previewTitle,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!previewAuthor.isNullOrBlank()) {
                                                Text(
                                                    text = previewAuthor,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Duplicate Warning Card
                            if (uiState.duplicateExisting != null) {
                                val dup = uiState.duplicateExisting!!
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(18.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.WarningAmber,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.tertiary
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Already Downloaded",
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    text = "${dup.fileName} (${dup.resolutionLabel}) is already saved.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            FilledTonalButton(
                                                onClick = viewModel::playExistingDuplicate,
                                                modifier = Modifier.height(44.dp)
                                            ) {
                                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Play")
                                            }
                                        }
                                    }
                                }
                            }

                            // Quality & Format Selection Header
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Available Quality & Formats (${analysis.streams.size})",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }

                            items(analysis.streams, key = { it.id }) { stream ->
                                val isSelected = uiState.selectedOption?.id == stream.id
                                StreamOptionRow(
                                    option = stream,
                                    isSelected = isSelected,
                                    onSelect = { viewModel.selectStreamOption(stream) }
                                )
                            }

                            // Filename & Destination Card
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = uiState.customFileName,
                                            onValueChange = viewModel::onCustomFileNameChanged,
                                            label = { Text("Save as file name") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("output_filename_field"),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                            )
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = uiState.settings.customFolderName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            uiState.storageInfo?.let { info ->
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.SdStorage,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.tertiary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = "${FormatUtils.formatBytes(info.availableBytes)} free",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamOptionRow(
    option: MediaStreamOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stream_option_${option.qualityLabel}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
            IconMedallion(
                icon = if (option.isAudioOnly) Icons.Rounded.Audiotrack else Icons.Rounded.HighQuality,
                tint = if (option.isHighestQuality || isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                containerColor = if (option.isHighestQuality || isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                size = 38.dp,
                iconSize = 19.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = option.qualityLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (option.isHighestQuality) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "BEST",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityBadge(label = option.format, isHighlighted = isSelected)
                    Text(
                        text = FormatUtils.formatBytes(option.estimatedBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (option.supportsRange) {
                        Text(
                            text = "• Resumable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
