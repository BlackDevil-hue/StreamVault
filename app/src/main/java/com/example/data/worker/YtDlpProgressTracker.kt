package com.example.data.worker

/** Tracks yt-dlp progress once per output file so retries cannot add the same stream twice. */
internal class YtDlpProgressTracker(
    private val expectedBytes: Long,
    initialDownloadedBytes: Long = 0L,
    initialProgressPercent: Int? = null
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
        var totalBytes: Long = 0L,
        var percent: Double = 0.0
    )

    private val tracks = linkedMapOf<String, TrackProgress>()
    private val initialBytes = initialDownloadedBytes.coerceAtLeast(0L)
    private var resumedCompletedBytes = 0L
    private var resumeBaselineSet = initialBytes <= 0L
    private var activeTrackKey = "default"
    private var hasObservedTrackTotal = false
    private var lastOverallPercent = (initialProgressPercent ?: 0).coerceIn(0, 99)
    private var lastSpeed = 0L

    @Synchronized
    fun update(callbackPercent: Float, callbackEta: Long, line: String): Snapshot {
        val destination = destinationPattern.find(line)?.groupValues?.getOrNull(1)
            ?.trim()?.trim('"', '\'')
        if (!destination.isNullOrBlank()) activeTrackKey = destination

        val linePercent = percentPattern.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val percent = (linePercent ?: callbackPercent.toDouble()).coerceIn(0.0, 100.0)
        val isDownloadLine = line.contains("[download]", ignoreCase = true)
        val lineTotal = sizePattern.find(line)?.let { match ->
            // yt-dlp prefixes estimated totals with '~'. Ignore those: they are bitrate guesses,
            // and can be many times larger than the actual output file.
            if (match.groupValues[1].isNotEmpty()) return@let null
            match.groupValues.getOrNull(2)?.replace(",", "")?.toDoubleOrNull()?.let { value ->
                value.times(unitMultiplier(match.groupValues[3])).toLong().coerceAtLeast(0L)
            }
        } ?: 0L

        if (isDownloadLine) {
            val track = tracks.getOrPut(activeTrackKey) { TrackProgress() }
            if (lineTotal > 0L) {
                track.totalBytes = maxOf(track.totalBytes, lineTotal)
                hasObservedTrackTotal = true
            }

            // On resume, bytes belonging to already completed tracks form a baseline. Subtract
            // the active partial file so it is represented only by this track's live percentage.
            if (!resumeBaselineSet) {
                val activeFileBytes = track.totalBytes.takeIf { it > 0L }
                    ?.let { it * percent.toLong() / 100L }
                    ?: 0L
                resumedCompletedBytes = (initialBytes - activeFileBytes).coerceAtLeast(0L)
                resumeBaselineSet = true
            }

            // Percentages can fall when yt-dlp retries a file. Keep that retry attached to the
            // same destination instead of adding another copy of its size to the job total.
            track.percent = maxOf(track.percent, percent)
            speedPattern.find(line)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                if (value != null) {
                    lastSpeed = value.times(unitMultiplier(match.groupValues[2])).toLong().coerceAtLeast(0L)
                }
            }
        } else if (
            line.contains("size=", ignoreCase = true) ||
            line.contains("ffmpeg", ignoreCase = true) ||
            line.contains("extractaudio", ignoreCase = true) ||
            line.contains("embedding", ignoreCase = true)
        ) {
            lastSpeed = 0L
        }

        val trackTotal = tracks.values.sumOf { it.totalBytes }
        val trackDownloaded = tracks.values.sumOf { state ->
            state.totalBytes * state.percent.toLong() / 100L
        }
        val downloaded = resumedCompletedBytes + trackDownloaded
        val measuredTotal = resumedCompletedBytes + trackTotal
        val total = if (hasObservedTrackTotal) {
            maxOf(measuredTotal, downloaded)
        } else {
            maxOf(expectedBytes.coerceAtLeast(0L), measuredTotal, downloaded)
        }
        val transferPercent = (if (total > 0L) {
            (downloaded * 100.0 / total).toInt()
        } else {
            percent.toInt()
        }).coerceIn(0, 99)
        lastOverallPercent = maxOf(lastOverallPercent, transferPercent).coerceAtMost(99)

        val eta = if (lastSpeed > 0L && total > downloaded) {
            (total - downloaded) / lastSpeed
        } else {
            callbackEta.coerceAtLeast(0L)
        }
        val finalizing = !isDownloadLine && (
            percent >= 98.0 || line.contains("ffmpeg", true) ||
                line.contains("extractaudio", true) || line.contains("size=", true)
            )
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
        val destinationPattern = Regex("Destination:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val percentPattern = Regex("\\[download]\\s+(\\d+(?:\\.\\d+)?)%")
        val sizePattern = Regex("\\bof\\s+(~)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)\\b", RegexOption.IGNORE_CASE)
        val speedPattern = Regex("\\bat\\s+([\\d,]+(?:\\.\\d+)?)\\s*(B|KiB|MiB|GiB|TiB|KB|MB|GB|TB)/s\\b", RegexOption.IGNORE_CASE)
    }
}
