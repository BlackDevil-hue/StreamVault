package com.example.ui.screens.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.LrcParser
import com.example.domain.model.LyricsUiState

@Composable
fun LiveLyricPreviewBanner(
    lyricsState: LyricsUiState,
    positionMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loaded = (lyricsState as? LyricsUiState.Loaded)?.content
    val previewLine = remember(loaded, positionMs) {
        when {
            loaded == null -> null
            loaded.hasSyncedLyrics -> {
                val idx = LrcParser.findActiveLineIndex(loaded.syncedLines, positionMs)
                if (idx >= 0) loaded.syncedLines[idx].text
                else loaded.syncedLines.firstOrNull()?.text
            }
            loaded.plainLines.isNotEmpty() -> {
                loaded.plainLines.firstOrNull { it.isNotBlank() }
            }
            else -> null
        }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_lyric_preview_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Lyrics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            AnimatedContent(
                targetState = previewLine ?: when (lyricsState) {
                    is LyricsUiState.Loading -> "Loading lyrics…"
                    is LyricsUiState.Unavailable -> "Tap to view or search lyrics"
                    else -> "Tap to open lyrics"
                },
                label = "lyricPreviewLine",
                modifier = Modifier.weight(1f)
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (previewLine != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (previewLine != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun LyricsViewport(
    lyricsState: LyricsUiState,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(276.dp)
            .testTag("audio_lyrics_viewport"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        val loaded = (lyricsState as? LyricsUiState.Loaded)?.content
                        Text(
                            text = when {
                                loaded?.hasSyncedLyrics == true -> "Synced Lyrics"
                                loaded != null -> "Lyrics"
                                else -> "Song Lyrics"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        loaded?.sourceLabel?.takeIf { it.isNotBlank() }?.let { src ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = src,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenSearch,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("lyrics_edit_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search lyrics manually",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("lyrics_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh lyrics",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Body
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (lyricsState) {
                        LyricsUiState.Idle, is LyricsUiState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = if (lyricsState is LyricsUiState.Loading) {
                                        "Fetching lyrics for \"${lyricsState.title}\"…"
                                    } else {
                                        "Loading lyrics…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        is LyricsUiState.Unavailable -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = lyricsState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = onOpenSearch,
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Edit Search")
                                    }
                                    FilledTonalButton(
                                        onClick = onRetry,
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retry")
                                    }
                                }
                            }
                        }

                        is LyricsUiState.Loaded -> {
                            val content = lyricsState.content
                            when {
                                content.isInstrumental -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(20.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Instrumental track",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                content.hasSyncedLyrics -> {
                                    val listState = rememberLazyListState()
                                    val activeIdx = remember(content.syncedLines, positionMs) {
                                        LrcParser.findActiveLineIndex(content.syncedLines, positionMs)
                                    }

                                    LaunchedEffect(activeIdx, content.trackId) {
                                        if (activeIdx >= 0) {
                                            val target = (activeIdx - 2).coerceAtLeast(0)
                                            listState.animateScrollToItem(target)
                                        }
                                    }

                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        itemsIndexed(
                                            items = content.syncedLines,
                                            key = { idx, line -> "${idx}_${line.timestampMs}" }
                                        ) { index, line ->
                                            val isActive = index == activeIdx
                                            val isPast = activeIdx > index
                                            val textColor by animateColorAsState(
                                                targetValue = when {
                                                    isActive -> MaterialTheme.colorScheme.primary
                                                    isPast -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                                },
                                                animationSpec = tween(durationMillis = 220),
                                                label = "lyricTextColor"
                                            )
                                            val rowBg by animateColorAsState(
                                                targetValue = if (isActive) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f)
                                                },
                                                animationSpec = tween(durationMillis = 220),
                                                label = "lyricRowBg"
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(rowBg)
                                                    .clickable { onSeekTo(line.timestampMs) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = line.text,
                                                    style = MaterialTheme.typography.bodyLarge.copy(
                                                        fontSize = if (isActive) 17.sp else 15.sp,
                                                        lineHeight = if (isActive) 23.sp else 21.sp,
                                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                                    ),
                                                    color = textColor,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                content.plainLines.isNotEmpty() -> {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(content.plainLines) { line ->
                                            if (line.isBlank()) {
                                                Spacer(Modifier.height(6.dp))
                                            } else {
                                                Text(
                                                    text = line,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 15.sp,
                                                        lineHeight = 22.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsSearchDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSearch: (String, String) -> Unit
) {
    var titleQuery by remember(initialTitle) { mutableStateOf(initialTitle) }
    var artistQuery by remember(initialArtist) { mutableStateOf(initialArtist) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Song Lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Adjust the song title or singer name to find synced or plain lyrics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = titleQuery,
                    onValueChange = { titleQuery = it },
                    label = { Text("Song title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artistQuery,
                    onValueChange = { artistQuery = it },
                    label = { Text("Artist / Singer (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(titleQuery.trim(), artistQuery.trim()) },
                enabled = titleQuery.isNotBlank()
            ) {
                Text("Find Lyrics")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
