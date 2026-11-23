package com.example.domain.model

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

data class LyricsContent(
    val trackId: String,
    val queryTitle: String,
    val queryArtist: String?,
    val syncedLines: List<LyricLine>,
    val plainLines: List<String>,
    val isInstrumental: Boolean = false,
    val sourceLabel: String? = null
) {
    val hasSyncedLyrics: Boolean get() = syncedLines.isNotEmpty()
    val hasAnyLyrics: Boolean get() = syncedLines.isNotEmpty() || plainLines.isNotEmpty()
}

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data class Loading(val trackId: String, val title: String) : LyricsUiState
    data class Loaded(val content: LyricsContent) : LyricsUiState
    data class Unavailable(
        val trackId: String,
        val queryTitle: String,
        val queryArtist: String?,
        val message: String,
        val isNetworkError: Boolean = false
    ) : LyricsUiState
}

object LrcParser {

    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val METADATA_TAG_REGEX = Regex("^\\[[a-zA-Z]{1,10}:.*]$")

    fun parse(rawLrc: String?): List<LyricLine> {
        if (rawLrc.isNullOrBlank()) return emptyList()
        val parsed = mutableListOf<LyricLine>()

        rawLrc.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || METADATA_TAG_REGEX.matches(line)) return@forEach

            val matches = TIMESTAMP_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = TIMESTAMP_REGEX.replace(line, "").trim()
            if (text.isBlank()) return@forEach

            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionRaw = match.groupValues[3]
                val millis = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fractionRaw.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = minutes * 60_000L + seconds * 1_000L + millis
                parsed += LyricLine(timestampMs = totalMs, text = text)
            }
        }

        return parsed.sortedBy { it.timestampMs }
    }

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val targetMs = positionMs.coerceAtLeast(0L) + 150L
        if (targetMs < lines.first().timestampMs) return -1

        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= targetMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
