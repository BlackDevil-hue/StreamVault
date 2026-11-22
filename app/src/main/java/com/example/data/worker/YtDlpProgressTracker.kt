package com.example.data.worker

import java.io.File

/**
 * Tracks yt-dlp progress across single or multi-track (video + audio) transfers without
 * double-counting retries or resumed files.
 */
internal class YtDlpProgressTracker(
    private val expectedBytes: Long,
    initialDownloadedBytes: Long = 0L,
    initialProgressPercent: Int? = null,
    private val fileLengthProvider: (String) -> Long = { path -> File(path).length() }
) {
    data class Snapshot(
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val etaSeconds: Long,
        val finalizing: Boolean
    )

    private data class TrackProgress(
        var exactTotalBytes: Long = 0L,
        var approxTotalBytes: Long = 0L,
        var directDownloadedBytes: Long = 0L,
        var percent: Double = 0.0
    ) {
        val effectiveTotalBytes: Long
            get() = if (exactTotalBytes > 0L) exactTotalBytes else approxTotalBytes

        val downloadedBytes: Long
            get() = if (effectiveTotalBytes > 0L) {
                (effectiveTotalBytes * (percent / 100.0)).toLong()
                    .coerceIn(directDownloadedBytes, effectiveTotalBytes)
            } else {
                directDownloadedBytes
            }
    }

    private val tracks = linkedMapOf<String, TrackProgress>()
    private val initialBytes = initialDownloadedBytes.coerceAtLeast(0L)
    private var activeTrackKey = "default"
    private var expectedTrackCount = 1
    private var hasObservedTrackTotal = false
    private var lastOverallPercent = (initialProgressPercent ?: 0).coerceIn(0, 99)
    private var lastSpeed = 0L

    @Synchronized
    fun update(callbackPercent: Float, callbackEta: Long, line: String): Snapshot {
        formatInfoPattern.find(line)?.groupValues?.getOrNull(1)?.let { formatSpec ->
            expectedTrackCount = if (formatSpec.contains('+')) 2 else 1
        }

        val destination = destinationPattern.find(line)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"', '\'')
        if (!destination.isNullOrBlank()) {
            activeTrackKey = destination
            tracks.getOrPut(activeTrackKey) { TrackProgress() }
        }

        val alreadyDownloadedPath = alreadyDownloadedPattern.find(line)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"', '\'')
        if (!alreadyDownloadedPath.isNullOrBlank()) {
            val fileLen = runCatching { fileLengthProvider(alreadyDownloadedPath) }.getOrDefault(0L)
                .coerceAtLeast(0L)
            val track = tracks.getOrPut(alreadyDownloadedPath) { TrackProgress() }
            if (fileLen > 0L) {
                track.exactTotalBytes = maxOf(track.exactTotalBytes, fileLen)
                hasObservedTrackTotal = true
            }
            track.percent = 100.0
            activeTrackKey = alreadyDownloadedPath
        }

        val linePercent = percentPattern.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val percent = (linePercent ?: callbackPercent.toDouble()).coerceIn(0.0, 100.0)
        val isDownloadLine = line.contains("[download]", ignoreCase = true)

        val sizeMatch = sizePattern.find(line)
        val isApproxSize = sizeMatch?.groupValues?.getOrNull(1)?.isNotEmpty() == true
        val lineTotal = sizeMatch?.let { match ->
            match.groupValues.getOrNull(2)?.replace(",", "")?.toDoubleOrNull()?.let { value ->
                value.times(unitMultiplier(match.groupValues[3])).toLong().coerceAtLeast(0L)
            }
        } ?: 0L

        if (isDownloadLine) {
            val track = tracks.getOrPut(activeTrackKey) { TrackProgress() }
            if (lineTotal > 0L) {
                if (isApproxSize) {
                    track.approxTotalBytes = lineTotal
                } else {
                    track.exactTotalBytes = maxOf(track.exactTotalBytes, lineTotal)
                }
                hasObservedTrackTotal = true
            }

            resumeBytePattern.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { resumedByte ->
                if (resumedByte > 0L) {
                    track.directDownloadedBytes = maxOf(track.directDownloadedBytes, resumedByte)
                }
            }

            downloadedOnlyPattern.find(line)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                if (value != null) {
                    val downloadedNow = value.times(unitMultiplier(match.groupValues[2])).toLong().coerceAtLeast(0L)
                    track.directDownloadedBytes = maxOf(track.directDownloadedBytes, downloadedNow)
                }
            }

            if (linePercent != null || callbackPercent >= 0f) {
                // Percentages can reset if yt-dlp retries; keep per-track progress monotonic.
                track.percent = maxOf(track.percent, percent)
            }

            speedPattern.find(line)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                if (value != null) {
                    lastSpeed = value.times(unitMultiplier(match.groupValues[2])).toLong().coerceAtLeast(0L)
                }
            }
        } else if (isPostProcessingLine(line)) {
            lastSpeed = 0L
        }

        val measuredTotal = tracks.values.sumOf { it.effectiveTotalBytes }
        val trackDownloaded = tracks.values.sumOf { it.downloadedBytes }
        val downloaded = if (trackDownloaded > 0L) trackDownloaded else initialBytes
        val tracksWithTotal = tracks.values.count { it.effectiveTotalBytes > 0L }

        val total = when {
            hasObservedTrackTotal && expectedTrackCount > tracksWithTotal && expectedBytes > measuredTotal -> {
                expectedBytes
            }
            hasObservedTrackTotal -> maxOf(measuredTotal, downloaded)
            expectedBytes > 0L -> maxOf(expectedBytes, downloaded)
            else -> 0L
        }

        val transferPercent = if (
            expectedTrackCount > tracksWithTotal &&
            hasObservedTrackTotal &&
            expectedBytes <= measuredTotal
        ) {
            // Track 1 of a multi-track download is still running; reserve headroom for audio track.
            val unscaled = if (total > 0L) (downloaded * 90.0 / total).toInt() else (percent * 0.90).toInt()
            unscaled.coerceIn(0, 90)
        } else {
            when {
                total > 0L -> (downloaded * 100.0 / total).toInt()
                linePercent != null || callbackPercent >= 0f -> percent.toInt()
                else -> lastOverallPercent
            }.coerceIn(0, 99)
        }
        lastOverallPercent = maxOf(lastOverallPercent, transferPercent).coerceAtMost(99)

        val eta = if (lastSpeed > 0L && total > downloaded) {
            (total - downloaded) / lastSpeed
        } else {
            callbackEta.coerceAtLeast(0L)
        }

        val finalizing = !isDownloadLine && (percent >= 98.0 || isPostProcessingLine(line))
        if (finalizing) lastSpeed = 0L

        return Snapshot(
            percent = if (finalizing) maxOf(lastOverallPercent, 99) else lastOverallPercent,
            bytesDownloaded = downloaded,
            totalBytes = total,
            speedBytesPerSec = lastSpeed,
            etaSeconds = if (finalizing) 0L else eta,
            finalizing = finalizing
        )
    }

    private fun isPostProcessingLine(line: String): Boolean =
        line.contains("ffmpeg", ignoreCase = true) ||
            line.contains("extractaudio", ignoreCase = true) ||
            line.contains("[merger]", ignoreCase = true) ||
            line.contains("[embedthumbnail]", ignoreCase = true) ||
            line.contains("[metadata]", ignoreCase = true) ||
            line.contains("[fixup", ignoreCase = true) ||
            line.contains("size=", ignoreCase = true)

    private fun unitMultiplier(unit: String): Double = when (unit.lowercase()) {
        "kib" -> 1024.0
        "mib" -> 1024.0 * 1024
        "gib" -> 1024.0 * 1024 * 1024
        "tib" -> 1024.0 * 1024 * 1024 * 1024
        "kb" -> 1_000.0
        "mb" -> 1_000_000.0
        "gb" -> 1_000_000_000.0
        "tb" -> 1_000_000_000_000.0
        else -> 1.0
    }

    private companion object {
        val formatInfoPattern = Regex("Downloading \\d+ format\\(s\\):\\s*(\\S+)", RegexOption.IGNORE_CASE)
        val destinationPattern = Regex("Destination:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val alreadyDownloadedPattern = Regex("\\[download]\\s+(.+?)\\s+has already been downloaded", RegexOption.IGNORE_CASE)
        val resumeBytePattern = Regex("\\[download]\\s+Resuming download at byte\\s+(\\d+)", RegexOption.IGNORE_CASE)
        val percentPattern = Regex("\\[download]\\s+(\\d+(?:\\.\\d+)?)%")
        val sizePattern = Regex("\\bof\\s+(~)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)\\b", RegexOption.IGNORE_CASE)
        val downloadedOnlyPattern = Regex("\\[download]\\s+([\\d,]+(?:\\.\\d+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)\\s+at\\b", RegexOption.IGNORE_CASE)
        val speedPattern = Regex("\\bat\\s+([\\d,]+(?:\\.\\d+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)/s\\b", RegexOption.IGNORE_CASE)
    }
}
