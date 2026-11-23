package com.example.data.network

import com.example.data.local.dao.AudioLyricsDao
import com.example.data.local.entity.AudioLyricsEntity
import com.example.domain.model.LrcParser
import com.example.domain.model.LyricsContent
import com.example.domain.model.LyricsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

internal data class LrclibCandidate(
    val trackName: String,
    val artistName: String,
    val durationSeconds: Double?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

@Singleton
class LyricsRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val audioLyricsDao: AudioLyricsDao
) {
    suspend fun getLyrics(
        trackId: String,
        rawTitle: String,
        rawArtist: String?,
        durationMs: Long?,
        forceRefresh: Boolean = false
    ): LyricsUiState = withContext(Dispatchers.IO) {
        val cleaned = cleanQueryInputs(rawTitle, rawArtist)
        val effectiveTitle = cleaned.first
        val effectiveArtist = cleaned.second

        if (!forceRefresh) {
            val cached = audioLyricsDao.getByDownloadId(trackId)
            if (cached != null && (
                    !cached.syncedLrc.isNullOrBlank() ||
                        !cached.plainLyrics.isNullOrBlank() ||
                        cached.isInstrumental
                    )
            ) {
                return@withContext LyricsUiState.Loaded(cached.toLyricsContent())
            }
        }

        fetchAndCache(
            trackId = trackId,
            queryTitle = effectiveTitle,
            queryArtist = effectiveArtist,
            durationMs = durationMs,
            preserveOffset = true
        )
    }

    suspend fun searchAndSaveLyrics(
        trackId: String,
        customTitle: String,
        customArtist: String?,
        durationMs: Long?
    ): LyricsUiState = withContext(Dispatchers.IO) {
        val cleanTitle = customTitle.trim().ifBlank { return@withContext LyricsUiState.Unavailable(
            trackId = trackId,
            queryTitle = customTitle,
            queryArtist = customArtist,
            message = "Enter a song title to search for lyrics."
        ) }
        val cleanArtist = customArtist?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true)
        }
        fetchAndCache(
            trackId = trackId,
            queryTitle = cleanTitle,
            queryArtist = cleanArtist,
            durationMs = durationMs,
            preserveOffset = false
        )
    }

    suspend fun updateOffset(trackId: String, offsetMs: Long): LyricsUiState.Loaded? = withContext(Dispatchers.IO) {
        val clamped = offsetMs.coerceIn(-30_000L, 30_000L)
        audioLyricsDao.updateOffset(trackId, clamped)
        audioLyricsDao.getByDownloadId(trackId)?.let { entity ->
            LyricsUiState.Loaded(entity.toLyricsContent())
        }
    }

    private suspend fun fetchAndCache(
        trackId: String,
        queryTitle: String,
        queryArtist: String?,
        durationMs: Long?,
        preserveOffset: Boolean
    ): LyricsUiState {
        var sawNetworkError = false
        val existingOffsetMs = if (preserveOffset) {
            audioLyricsDao.getByDownloadId(trackId)?.offsetMs ?: 0L
        } else {
            0L
        }
        val primaryArtist = queryArtist
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val candidateFromLrclib = runCatching {
            fetchFromLrclib(
                title = queryTitle,
                artist = queryArtist,
                primaryArtist = primaryArtist,
                durationMs = durationMs
            )
        }.onFailure { err ->
            if (err is IOException) sawNetworkError = true
        }.getOrNull()

        if (candidateFromLrclib != null && (
                !candidateFromLrclib.syncedLyrics.isNullOrBlank() ||
                    !candidateFromLrclib.plainLyrics.isNullOrBlank() ||
                    candidateFromLrclib.instrumental
                )
        ) {
            val entity = AudioLyricsEntity(
                downloadId = trackId,
                queryTitle = queryTitle,
                queryArtist = queryArtist,
                syncedLrc = candidateFromLrclib.syncedLyrics?.takeIf { it.isNotBlank() },
                plainLyrics = candidateFromLrclib.plainLyrics?.takeIf { it.isNotBlank() },
                isInstrumental = candidateFromLrclib.instrumental &&
                    candidateFromLrclib.syncedLyrics.isNullOrBlank() &&
                    candidateFromLrclib.plainLyrics.isNullOrBlank(),
                sourceLabel = "LRCLIB",
                offsetMs = existingOffsetMs,
                fetchedAt = System.currentTimeMillis()
            )
            audioLyricsDao.upsert(entity)
            return LyricsUiState.Loaded(entity.toLyricsContent())
        }

        // Fallback to api.lyrics.ovh for plain lyrics when artist is known
        val artistForOvh = primaryArtist ?: queryArtist
        if (!artistForOvh.isNullOrBlank()) {
            val plainOvh = runCatching {
                fetchFromLyricsOvh(title = queryTitle, artist = artistForOvh)
            }.onFailure { err ->
                if (err is IOException) sawNetworkError = true
            }.getOrNull()

            if (!plainOvh.isNullOrBlank()) {
                val entity = AudioLyricsEntity(
                    downloadId = trackId,
                    queryTitle = queryTitle,
                    queryArtist = queryArtist,
                    syncedLrc = null,
                    plainLyrics = plainOvh,
                    isInstrumental = false,
                    sourceLabel = "Lyrics.ovh",
                    offsetMs = existingOffsetMs,
                    fetchedAt = System.currentTimeMillis()
                )
                audioLyricsDao.upsert(entity)
                return LyricsUiState.Loaded(entity.toLyricsContent())
            }
        }

        return LyricsUiState.Unavailable(
            trackId = trackId,
            queryTitle = queryTitle,
            queryArtist = queryArtist,
            message = if (sawNetworkError) {
                "Could not connect to fetch lyrics. Check your connection and tap Retry."
            } else {
                "No lyrics found for \"$queryTitle\". Try editing the song or artist name."
            },
            isNetworkError = sawNetworkError
        )
    }

    private fun fetchFromLrclib(
        title: String,
        artist: String?,
        primaryArtist: String?,
        durationMs: Long?
    ): LrclibCandidate? {
        val durationSec = durationMs?.takeIf { it > 0L }?.div(1000L)
        val candidates = mutableListOf<LrclibCandidate>()

        fun hasValidSynced(candidate: LrclibCandidate): Boolean =
            !candidate.syncedLyrics.isNullOrBlank() && LrcParser.parse(candidate.syncedLyrics).isNotEmpty()

        // 1. Exact lookup when artist is available
        if (!artist.isNullOrBlank()) {
            val getUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", primaryArtist ?: artist)
                .apply {
                    if (durationSec != null && durationSec > 0L) {
                        addQueryParameter("duration", durationSec.toString())
                    }
                }
                .build()
            executeGet(getUrl.toString())?.let { body ->
                val obj = JSONObject(body)
                val exact = parseLrclibObject(obj)
                if (hasValidSynced(exact) && durationSec != null) {
                    return exact
                }
                if (!exact.syncedLyrics.isNullOrBlank() || !exact.plainLyrics.isNullOrBlank() || exact.instrumental) {
                    candidates += exact
                }
            }
        }

        // 2. Search with track_name + primary artist
        if (!primaryArtist.isNullOrBlank()) {
            val searchArtistUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", primaryArtist)
                .build()
            executeGet(searchArtistUrl.toString())?.let { body ->
                candidates += parseLrclibArray(body)
            }
        }

        // 3. Broad query fallback if no synced candidate was found yet
        if (candidates.none(::hasValidSynced)) {
            val q = if (!primaryArtist.isNullOrBlank()) "$title $primaryArtist" else title
            val broadUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .build()
            executeGet(broadUrl.toString())?.let { body ->
                candidates += parseLrclibArray(body)
            }
        }

        // 4. Title-only fallback if artist hint was inaccurate or noisy
        if (candidates.none(::hasValidSynced) && !primaryArtist.isNullOrBlank()) {
            val titleOnlyUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .build()
            executeGet(titleOnlyUrl.toString())?.let { body ->
                candidates += parseLrclibArray(body)
            }
        }

        return selectBestLrclibCandidate(
            candidates = candidates,
            targetTitle = title,
            targetArtist = artist,
            targetDurationSeconds = durationSec?.toDouble()
        )
    }

    private fun fetchFromLyricsOvh(title: String, artist: String): String? {
        val url = "https://api.lyrics.ovh/v1".toHttpUrl().newBuilder()
            .addPathSegment(artist)
            .addPathSegment(title)
            .build()
        val body = executeGet(url.toString()) ?: return null
        val lyrics = JSONObject(body).optString("lyrics").trim()
        return lyrics.takeIf { it.isNotBlank() }
    }

    private fun executeGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseLrclibArray(jsonArrayText: String): List<LrclibCandidate> {
        val array = JSONArray(jsonArrayText)
        return (0 until array.length()).mapNotNull { idx ->
            array.optJSONObject(idx)?.let(::parseLrclibObject)
        }
    }

    private fun parseLrclibObject(obj: JSONObject): LrclibCandidate =
        LrclibCandidate(
            trackName = obj.optString("trackName", ""),
            artistName = obj.optString("artistName", ""),
            durationSeconds = obj.optDouble("duration", 0.0).takeIf { !it.isNaN() && it > 0.0 },
            instrumental = obj.optBoolean("instrumental", false),
            plainLyrics = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" },
            syncedLyrics = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        )

    private fun cleanQueryInputs(rawTitle: String, rawArtist: String?): Pair<String, String?> {
        val sanitizedArtist = rawArtist?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true)
        }
        val extracted = YouTubeAudioMetadataExtractor.extract(
            rawTitle = rawTitle,
            rawArtist = sanitizedArtist
        )
        return extracted.songTitle.ifBlank { rawTitle.trim() } to (extracted.songArtist ?: sanitizedArtist)
    }

    internal fun selectBestLrclibCandidate(
        candidates: List<LrclibCandidate>,
        targetTitle: String,
        targetArtist: String?,
        targetDurationSeconds: Double?
    ): LrclibCandidate? {
        if (candidates.isEmpty()) return null
        val normTitle = normalizeMatchText(targetTitle)
        val normArtist = targetArtist?.let(::normalizeMatchText).orEmpty()

        return candidates.maxByOrNull { item ->
            var score = 0
            val hasValidSynced = !item.syncedLyrics.isNullOrBlank() && LrcParser.parse(item.syncedLyrics).isNotEmpty()
            if (hasValidSynced) score += 1000
            else if (!item.plainLyrics.isNullOrBlank()) score += 300
            else if (item.instrumental) score += 50

            val itemTitle = normalizeMatchText(item.trackName)
            when {
                itemTitle == normTitle && normTitle.isNotEmpty() -> score += 250
                normTitle.isNotEmpty() && (itemTitle.contains(normTitle) || normTitle.contains(itemTitle)) -> score += 120
            }

            if (normArtist.isNotEmpty()) {
                val itemArtist = normalizeMatchText(item.artistName)
                when {
                    itemArtist == normArtist -> score += 350
                    itemArtist.contains(normArtist) || normArtist.contains(itemArtist) -> score += 220
                }
            }

            if (targetDurationSeconds != null && targetDurationSeconds > 0.0 && item.durationSeconds != null) {
                val diff = abs(item.durationSeconds - targetDurationSeconds)
                score += when {
                    diff <= 4.0 -> 400
                    diff <= 12.0 -> 220
                    diff <= 25.0 -> 80
                    else -> -200
                }
            }
            score
        }
    }

    private fun normalizeMatchText(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun AudioLyricsEntity.toLyricsContent(): LyricsContent {
        val parsedSynced = LrcParser.parse(syncedLrc)
        val plain = plainLyrics
            ?.lineSequence()
            ?.map { it.trimEnd() }
            ?.toList()
            ?.let { lines ->
                // Trim leading/trailing blank lines while preserving verse breaks
                val firstNonBlank = lines.indexOfFirst { it.isNotBlank() }
                val lastNonBlank = lines.indexOfLast { it.isNotBlank() }
                if (firstNonBlank < 0) emptyList() else lines.subList(firstNonBlank, lastNonBlank + 1)
            }
            .orEmpty()
        return LyricsContent(
            trackId = downloadId,
            queryTitle = queryTitle,
            queryArtist = queryArtist,
            syncedLines = parsedSynced,
            plainLines = plain,
            isInstrumental = isInstrumental,
            sourceLabel = sourceLabel,
            offsetMs = offsetMs
        )
    }

    private companion object {
        const val USER_AGENT = "StreamVault/1.0 (https://lrclib.net)"
    }
}
