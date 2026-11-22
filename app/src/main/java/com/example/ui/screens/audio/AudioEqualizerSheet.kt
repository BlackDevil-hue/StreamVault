package com.example.ui.screens.audio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.audio.EqualizerCapabilities
import com.example.domain.model.EqualizerPreset
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioEqualizerSheetContent(
    enabled: Boolean,
    preset: EqualizerPreset,
    bandLevelsMb: List<Int>,
    bassBoostStrength: Int,
    capabilities: EqualizerCapabilities,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onBandLevelChanged: (Int, Int) -> Unit,
    onBassBoostChanged: (Int) -> Unit,
    onResetFlat: () -> Unit
) {
    val bandCount = capabilities.numberOfBands.coerceAtLeast(1)
    val minMb = capabilities.minLevelMb
    val maxMb = capabilities.maxLevelMb.coerceAtLeast(minMb + 100)
    val normalizedBands = List(bandCount) { idx ->
        (bandLevelsMb.getOrNull(idx) ?: preset.defaultBandsMb.getOrNull(idx) ?: 0)
            .coerceIn(minMb, maxMb)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header + Master Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(22.dp),
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Column {
                    Text(
                        text = "Equalizer & Bass Boost",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            !enabled -> "Bypassed"
                            capabilities.isEffectAttached -> "Live audio DSP active"
                            else -> "Saved — activates on playback"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled && capabilities.isEffectAttached) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.testTag("equalizer_enable_switch")
            )
        }

        // Frequency Response Curve Preview
        EqualizerCurveCard(
            enabled = enabled,
            bandsMb = normalizedBands,
            minMb = minMb,
            maxMb = maxMb
        )

        // Presets Row
        LazyRow(
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(EqualizerPreset.entries) { item ->
                FilterChip(
                    selected = preset == item,
                    onClick = { onSelectPreset(item) },
                    label = { Text(item.label) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag("eq_preset_${item.name}")
                )
            }
        }

        // Bass Boost Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speaker,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Bass Boost",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                    ) {
                        Text(
                            text = "${(bassBoostStrength / 10f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Slider(
                    value = bassBoostStrength.coerceIn(0, 1000).toFloat(),
                    onValueChange = { onBassBoostChanged(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    enabled = enabled,
                    modifier = Modifier.testTag("equalizer_bass_slider")
                )
            }
        }

        // Frequency Bands Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Frequency Bands",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = onResetFlat,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset")
                    }
                }

                normalizedBands.forEachIndexed { idx, levelMb ->
                    val freqHz = capabilities.centerFreqsHz.getOrNull(idx) ?: defaultBandFreq(idx)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = formatFreqHz(freqHz),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp)
                        )
                        Slider(
                            value = levelMb.toFloat(),
                            onValueChange = { newVal ->
                                onBandLevelChanged(idx, newVal.roundToInt())
                            },
                            valueRange = minMb.toFloat()..maxMb.toFloat(),
                            enabled = enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("eq_band_slider_$idx")
                        )
                        Text(
                            text = formatDb(levelMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enabled && levelMb != 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.width(54.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EqualizerCurveCard(
    enabled: Boolean,
    bandsMb: List<Int>,
    minMb: Int,
    maxMb: Int
) {
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val secondaryColor = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            val midY = size.height / 2f
            drawLine(
                color = gridColor,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 2f
            )

            if (bandsMb.isEmpty()) return@Canvas
            val span = (maxMb - minMb).coerceAtLeast(1).toFloat()
            val stepX = if (bandsMb.size > 1) size.width / (bandsMb.size - 1).toFloat() else size.width

            val points = bandsMb.mapIndexed { idx, level ->
                val x = if (bandsMb.size > 1) idx * stepX else size.width / 2f
                val fraction = ((level - minMb) / span).coerceIn(0f, 1f)
                val y = size.height * (1f - fraction)
                Offset(x, y)
            }

            val linePath = Path().apply {
                points.forEachIndexed { idx, pt ->
                    if (idx == 0) moveTo(pt.x, pt.y) else {
                        val prev = points[idx - 1]
                        val ctrlX = (prev.x + pt.x) / 2f
                        cubicTo(ctrlX, prev.y, ctrlX, pt.y, pt.x, pt.y)
                    }
                }
            }

            drawPath(
                path = linePath,
                brush = Brush.horizontalGradient(listOf(activeColor, secondaryColor)),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )

            points.forEach { pt ->
                drawCircle(
                    color = activeColor,
                    radius = 4.5.dp.toPx(),
                    center = pt
                )
            }
        }
    }
}

private fun formatFreqHz(freqHz: Int): String =
    if (freqHz >= 1000) {
        val khz = freqHz / 1000f
        if (khz % 1f == 0f) "${khz.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    } else {
        "$freqHz Hz"
    }

private fun formatDb(mb: Int): String {
    val db = mb / 100f
    return if (db > 0f) String.format(Locale.US, "+%.1f dB", db) else String.format(Locale.US, "%.1f dB", db)
}

private fun defaultBandFreq(index: Int): Int = when (index) {
    0 -> 60
    1 -> 230
    2 -> 910
    3 -> 3600
    else -> 14000
}
