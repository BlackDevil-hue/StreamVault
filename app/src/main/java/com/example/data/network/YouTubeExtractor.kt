package com.example.data.network

import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the on-device yt-dlp engine so YouTube analysis does not depend on public API mirrors. */
@Singleton
class YouTubeExtractor @Inject constructor(
    private val youtubeDlRuntime: YoutubeDlRuntime,
    private val youtubeCookieProvider: YoutubeCookieProvider
) : VideoSourceExtractor {

    override val priority: Int = 5

    override fun canHandle(url: String): Boolean = extractVideoId(url) != null

    fun isPlaylistUrl(rawUrl: String): Boolean {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
        return (host == "youtube.com" || host.endsWith(".youtube.com")) &&
            parsed.encodedPath == "/playlist" && !parsed.queryParameter("list").isNullOrBlank()
    }

    suspend fun expandPlaylist(
        rawUrl: String,
        startIndex: Int = 1,
        endIndex: Int = 50,
        limit: Int = 50
    ): List<String> = withContext(Dispatchers.IO) {
        youtubeDlRuntime.ensureLatestYoutubeDl()
        val safeLimit = limit.coerceIn(1, 50)
        val safeStart = startIndex.coerceAtLeast(1)
        val safeEnd = endIndex.coerceAtLeast(safeStart)
            .coerceAtMost((safeStart.toLong() + safeLimit - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val request = YoutubeDLRequest(rawUrl).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-warnings")
            addOption("--extractor-args", YoutubeDlRuntime.YOUTUBE_EXTRACTOR_ARGS)
            addOption("--playlist-start", safeStart.toString())
            addOption("--playlist-end", safeEnd.toString())
        }
        val cookieFile = youtubeCookieProvider.attachCookies(request)
        val output = try {
            YoutubeDL.getInstance().execute(request).out
        } finally {
            cookieFile?.delete()
        }
        val entries = JSONObject(output).optJSONArray("entries")
            ?: throw IllegalStateException("No videos were found in this playlist.")
        (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                ?.let { "https://www.youtube.com/watch?v=$it" }
        }.distinct().take(safeLimit)
            .ifEmpty { throw IllegalStateException("No downloadable videos were found in this playlist.") }
    }

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url)
            ?: return@withContext ExtractionOutcome.Failure(
                DownloadError.InvalidUrl("This is not a supported YouTube video URL.")
            )

        try {
            youtubeDlRuntime.ensureLatestYoutubeDl()
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--extractor-args", YoutubeDlRuntime.YOUTUBE_EXTRACTOR_ARGS)
            }
            val cookieFile = youtubeCookieProvider.attachCookies(request)
            val output = try {
                YoutubeDL.getInstance().execute(request).out
            } finally {
                cookieFile?.delete()
            }
            val info = JSONObject(output)
            val title = info.optString("title").takeIf { it.isNotBlank() } ?: "YouTube video"
            val artists = info.optJSONArray("artists")
                ?.let { values -> (0 until values.length()).map { values.optString(it) } }
                ?.filter { it.isNotBlank() }
                ?.joinToString(", ")
            val artist = info.optString("artist").takeIf { it.isNotBlank() } ?: artists
            val estimatedSizes = estimateDownloadSizes(info)

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = "https://www.youtube.com/watch?v=$videoId",
                    providerName = "YouTube",
                    title = title,
                    thumbnailUrl = info.optString("thumbnail").takeIf { it.isNotBlank() },
                    author = artist,
                    streams = listOf(
                        MediaStreamOption(
                            id = UUID.randomUUID().toString(),
                            // The worker recognizes this marker and downloads/muxes the best
                            // separate video and audio tracks with yt-dlp and FFmpeg.
                            streamUrl = "yt-dlp://youtube/$videoId",
                            qualityLabel = "Best available video quality",
                            resolutionHeight = null,
                            format = "MP4",
                            mimeType = "video/mp4",
                            estimatedBytes = estimatedSizes.first,
                            supportsRange = true,
                            isHighestQuality = true
                        ),
                        MediaStreamOption(
                            id = UUID.randomUUID().toString(),
                            streamUrl = "yt-dlp://youtube/audio/$videoId",
                            qualityLabel = "Audio only · MP3 320 kbps",
                            resolutionHeight = null,
                            format = "MP3",
                            mimeType = "audio/mpeg",
                            isAudioOnly = true,
                            estimatedBytes = estimatedSizes.second,
                            supportsRange = false,
                            isHighestQuality = false
                        )
                    )
                )
            )
        } catch (e: Exception) {
            val errorText = e.message.orEmpty()
            val detail = errorText.lineSequence().firstOrNull()?.take(180)
                ?: "yt-dlp could not analyze this video."
            val userMessage = if (
                errorText.contains("sign in to confirm", ignoreCase = true) ||
                errorText.contains("not a bot", ignoreCase = true)
            ) {
                "YouTube requires verification for this request. Open YouTube in Browser mode, sign in if prompted, then retry analysis."
            } else {
                "YouTube extraction failed: $detail"
            }
            ExtractionOutcome.Failure(
                DownloadError.UnsupportedSource(userMessage)
            )
        }
    }

    /** Use only sizes yt-dlp reports directly; bitrate-derived estimates can be wildly off. */
    private fun estimateDownloadSizes(info: JSONObject): Pair<Long?, Long?> {
        val formats = info.optJSONArray("formats") ?: return null to null
        val entries = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
        fun codec(format: JSONObject, key: String): String = format.optString(key, "none")
        fun quality(format: JSONObject, key: String): Double = format.optDouble(key, 0.0)
        fun size(format: JSONObject?): Long? {
            if (format == null) return null
            return format.optLong("filesize", 0L).takeIf { it > 0L }
        }
        val videoOnly = entries.filter { codec(it, "vcodec") != "none" && codec(it, "acodec") == "none" }
            .maxWithOrNull(compareBy<JSONObject> { quality(it, "height") }.thenBy { quality(it, "fps") }.thenBy { quality(it, "tbr") })
        val audioOnly = entries.filter { codec(it, "acodec") != "none" && codec(it, "vcodec") == "none" }
            .maxByOrNull { maxOf(quality(it, "abr"), quality(it, "tbr")) }
        val muxed = entries.filter { codec(it, "vcodec") != "none" && codec(it, "acodec") != "none" }
            .maxWithOrNull(compareBy<JSONObject> { quality(it, "height") }.thenBy { quality(it, "tbr") })
        val videoSize = size(videoOnly)?.let { video -> size(audioOnly)?.let { audio -> video + audio } }
            ?: size(muxed)
        val audioSize = size(audioOnly) ?: size(muxed)
        return videoSize to audioSize
    }

    private fun extractVideoId(rawUrl: String): String? {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase(Locale.US).removePrefix("www.").removePrefix("m.")
        val id = when {
            host == "youtu.be" -> parsed.pathSegments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                parsed.encodedPath == "/watch" -> parsed.queryParameter("v")
                parsed.pathSegments.firstOrNull() in setOf("shorts", "embed", "live") -> parsed.pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
}
