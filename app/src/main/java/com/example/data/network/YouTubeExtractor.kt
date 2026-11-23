package com.example.data.network

import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.model.VideoQualityTier
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

internal data class ParsedYouTubeStream(
    val videoId: String,
    val isAudioOnly: Boolean,
    val targetHeight: Int?
)

internal object YouTubeStreamSpec {
    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

    fun buildVideoUri(videoId: String, targetHeight: Int? = null): String {
        return if (targetHeight != null && targetHeight > 0) {
            "yt-dlp://youtube/$videoId?height=$targetHeight"
        } else {
            "yt-dlp://youtube/$videoId"
        }
    }

    fun buildAudioUri(videoId: String): String = "yt-dlp://youtube/audio/$videoId"

    fun parse(streamUrl: String): ParsedYouTubeStream? {
        if (!streamUrl.startsWith("yt-dlp://youtube/")) return null
        val remainder = streamUrl.removePrefix("yt-dlp://youtube/")
        val pathPart = remainder.substringBefore('?')
        val queryPart = remainder.substringAfter('?', "")
        val isAudio = pathPart.startsWith("audio/")
        val videoId = if (isAudio) {
            pathPart.removePrefix("audio/").substringBefore('/')
        } else {
            pathPart.substringBefore('/')
        }
        if (!videoId.matches(VIDEO_ID_REGEX)) return null

        val targetHeight = queryPart.split('&')
            .mapNotNull { param ->
                val idx = param.indexOf('=')
                if (idx <= 0) null else param.substring(0, idx) to param.substring(idx + 1)
            }
            .firstOrNull { it.first == "height" }
            ?.second
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        return ParsedYouTubeStream(
            videoId = videoId,
            isAudioOnly = isAudio,
            targetHeight = targetHeight
        )
    }

    /**
     * Maps common YouTube format itags (reported in yt-dlp's
     * "Downloading 1 format(s): <video>+<audio>" line) to their video height.
     */
    fun resolveHeightFromFormatSpec(formatSpec: String?): Int? {
        if (formatSpec.isNullOrBlank()) return null
        val videoItag = formatSpec.substringBefore('+').trim()
        return YOUTUBE_ITAG_HEIGHTS[videoItag]
    }

    private val YOUTUBE_ITAG_HEIGHTS = mapOf(
        // 4K 2160p (VP9, AV1, HDR)
        "313" to 2160, "315" to 2160, "337" to 2160, "401" to 2160, "571" to 2160, "701" to 2160,
        // 1440p 2K
        "271" to 1440, "308" to 1440, "336" to 1440, "400" to 1440, "700" to 1440,
        // 1080p Full HD (AVC1, VP9, AV1, HLS)
        "137" to 1080, "248" to 1080, "299" to 1080, "303" to 1080, "335" to 1080,
        "399" to 1080, "614" to 1080, "616" to 1080, "699" to 1080, "96" to 1080,
        // 720p HD
        "22" to 720, "136" to 720, "247" to 720, "298" to 720, "302" to 720,
        "334" to 720, "398" to 720, "609" to 720, "698" to 720, "95" to 720,
        // 480p
        "135" to 480, "244" to 480, "333" to 480, "397" to 480, "606" to 480, "94" to 480,
        // 360p
        "18" to 360, "134" to 360, "243" to 360, "396" to 360, "605" to 360, "93" to 360,
        // 240p
        "133" to 240, "242" to 240, "395" to 240, "604" to 240, "92" to 240,
        // 144p
        "160" to 144, "278" to 144, "394" to 144, "597" to 144, "598" to 144, "602" to 144, "91" to 144
    )
}

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
                addOption("--dump-single-json")
                addOption("--skip-download")
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
            ExtractionOutcome.Success(parseVideoAnalysis(url, videoId, info))
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

    internal data class RawYouTubeFormat(
        val ext: String,
        val protocol: String,
        val vcodec: String,
        val acodec: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val tbr: Double,
        val abr: Double,
        val audioChannels: Int,
        val filesize: Long,
        val filesizeApprox: Long
    )

    internal fun parseVideoAnalysis(
        sourceUrl: String,
        videoId: String,
        info: JSONObject
    ): VideoAnalysisResult {
        val title = info.optString("title").takeIf { it.isNotBlank() } ?: "YouTube video"
        val artistsList = info.optJSONArray("artists")
            ?.let { values -> (0 until values.length()).map { values.optString(it) } }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val creatorsList = info.optJSONArray("creators")
            ?.let { values -> (0 until values.length()).map { values.optString(it) } }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val rawArtist = info.optString("artist").takeIf { it.isNotBlank() }
        val uploader = info.optString("uploader").takeIf { it.isNotBlank() }
        val channel = info.optString("channel").takeIf { it.isNotBlank() }
        val artist = rawArtist
            ?: artistsList.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: uploader
            ?: channel

        val audioMetadata = YouTubeAudioMetadataExtractor.extract(
            rawTitle = title,
            rawTrack = info.optString("track").takeIf { it.isNotBlank() },
            rawAltTitle = info.optString("alt_title").takeIf { it.isNotBlank() },
            rawArtist = rawArtist,
            rawArtists = artistsList,
            rawAlbumArtist = info.optString("album_artist").takeIf { it.isNotBlank() },
            rawCreator = info.optString("creator").takeIf { it.isNotBlank() },
            rawCreators = creatorsList,
            uploader = uploader,
            channel = channel,
            description = info.optString("description").takeIf { it.isNotBlank() }
        )

        val durationSeconds = info.optDouble("duration", 0.0).takeIf { !it.isNaN() && it > 0.0 }
        val durationMs = durationSeconds?.times(1000.0)?.toLong()
        val isLive = info.optBoolean("is_live", false)

        val formatsJson = info.optJSONArray("formats") ?: info.optJSONArray("requested_formats")
        val rawFormats = if (formatsJson != null) {
            (0 until formatsJson.length()).mapNotNull { index ->
                formatsJson.optJSONObject(index)?.let { fmt ->
                    RawYouTubeFormat(
                        ext = fmt.optString("ext", ""),
                        protocol = fmt.optString("protocol", ""),
                        vcodec = fmt.optString("vcodec", "none"),
                        acodec = fmt.optString("acodec", "none"),
                        width = fmt.optInt("width", 0),
                        height = fmt.optInt("height", 0),
                        fps = fmt.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it },
                        tbr = fmt.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                        abr = fmt.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it },
                        audioChannels = fmt.optInt("audio_channels", 0),
                        filesize = fmt.optLong("filesize", 0L),
                        filesizeApprox = fmt.optLong("filesize_approx", 0L)
                    )
                }
            }
        } else {
            emptyList()
        }.ifEmpty {
            val topHeight = info.optInt("height", 0)
            val topWidth = info.optInt("width", 0)
            if (topHeight > 0) {
                listOf(
                    RawYouTubeFormat(
                        ext = info.optString("ext", "mp4"),
                        protocol = info.optString("protocol", "https"),
                        vcodec = info.optString("vcodec", "avc1"),
                        acodec = info.optString("acodec", "mp4a.40.2"),
                        width = topWidth,
                        height = topHeight,
                        fps = info.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it },
                        tbr = info.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                        abr = info.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it },
                        audioChannels = info.optInt("audio_channels", 2),
                        filesize = info.optLong("filesize", 0L),
                        filesizeApprox = info.optLong("filesize_approx", 0L)
                    )
                )
            } else {
                emptyList()
            }
        }

        val streams = buildStreamOptions(videoId, rawFormats, durationSeconds)

        return VideoAnalysisResult(
            sourceUrl = sourceUrl,
            normalizedUrl = "https://www.youtube.com/watch?v=$videoId",
            providerName = "YouTube",
            title = title,
            thumbnailUrl = info.optString("thumbnail").takeIf { it.isNotBlank() },
            durationMs = durationMs,
            author = artist,
            audioTitle = audioMetadata.songTitle,
            audioArtist = audioMetadata.songArtist,
            isLive = isLive,
            streams = streams
        )
    }

    internal fun buildStreamOptions(
        videoId: String,
        rawFormats: List<RawYouTubeFormat>,
        durationSeconds: Double?
    ): List<MediaStreamOption> {
        val entries = rawFormats.filter { fmt ->
            val ext = fmt.ext.lowercase(Locale.US)
            val protocol = fmt.protocol.lowercase(Locale.US)
            ext != "mhtml" && protocol != "mhtml"
        }

        fun directSize(format: RawYouTubeFormat?): Long? {
            if (format == null) return null
            return format.filesize.takeIf { it > 0L }
                ?: format.filesizeApprox.takeIf { it > 0L }
        }

        val audioOnlyEntries = entries.filter {
            !it.acodec.equals("none", ignoreCase = true) &&
                it.vcodec.equals("none", ignoreCase = true)
        }
        val stereoAudioEntries = audioOnlyEntries.filter {
            it.audioChannels <= 2
        }.ifEmpty { audioOnlyEntries }

        val bestM4aAudio = stereoAudioEntries
            .filter {
                it.acodec.lowercase(Locale.US).startsWith("mp4a.40") ||
                    it.ext.equals("m4a", ignoreCase = true)
            }
            .maxByOrNull { maxOf(it.abr, it.tbr) }
        val bestAnyAudio = stereoAudioEntries
            .maxByOrNull { maxOf(it.abr, it.tbr) }

        val videoEntries = entries.filter {
            !it.vcodec.equals("none", ignoreCase = true) && it.height > 0
        }

        // Group formats by normalized tier height (supporting landscape, widescreen, and vertical
        // Shorts cleanly).
        val groupedByTier = linkedMapOf<VideoQualityTier, MutableList<RawYouTubeFormat>>()
        for (fmt in videoEntries) {
            val tier = VideoQualityTier.fromDimensions(
                width = fmt.width.takeIf { it > 0 },
                height = fmt.height
            )
            if (tier.height > 0) {
                groupedByTier.getOrPut(tier) { mutableListOf() }.add(fmt)
            }
        }

        val sortedTiers = groupedByTier.keys.sortedByDescending { it.height }
        val videoOptions = sortedTiers.mapIndexed { index, tier ->
            val candidates = groupedByTier[tier].orEmpty()
            val videoOnly = candidates.filter { it.acodec.equals("none", ignoreCase = true) }
            val muxed = candidates.filter { !it.acodec.equals("none", ignoreCase = true) }

            // Match VideoDownloadWorker's format selection order so estimatedBytes mirrors the
            // actual stream yt-dlp downloads for this resolution.
            val avcVideo = videoOnly.filter { it.vcodec.lowercase(Locale.US).startsWith("avc1") }
            val mp4Video = videoOnly.filter { it.ext.equals("mp4", ignoreCase = true) }

            val chosenVideoPool = when {
                avcVideo.isNotEmpty() -> avcVideo
                mp4Video.isNotEmpty() -> mp4Video
                videoOnly.isNotEmpty() -> videoOnly
                else -> muxed
            }
            val chosenVideo = chosenVideoPool.maxWithOrNull(
                compareBy<RawYouTubeFormat> { it.height }
                    .thenBy { it.fps }
                    .thenBy { it.tbr }
            )

            val isMuxed = chosenVideo != null && !chosenVideo.acodec.equals("none", ignoreCase = true)
            val chosenAudio = when {
                isMuxed -> null
                chosenVideo != null && (
                    chosenVideo.vcodec.lowercase(Locale.US).startsWith("avc1") ||
                        chosenVideo.ext.equals("mp4", ignoreCase = true)
                    ) -> bestM4aAudio ?: bestAnyAudio
                else -> bestAnyAudio ?: bestM4aAudio
            }

            val videoSize = directSize(chosenVideo)
            val audioSize = if (isMuxed) 0L else (directSize(chosenAudio) ?: directSize(bestAnyAudio) ?: 0L)
            val estimatedBytes = when {
                isMuxed -> videoSize
                videoSize != null -> videoSize + audioSize
                else -> directSize(
                    muxed.maxWithOrNull(compareBy<RawYouTubeFormat> { it.height }.thenBy { it.tbr })
                )
            }

            val actualStreamHeight = chosenVideo?.height?.takeIf { it > 0 } ?: tier.height
            val actualStreamWidth = chosenVideo?.width?.takeIf { it > 0 }

            MediaStreamOption(
                id = UUID.randomUUID().toString(),
                streamUrl = YouTubeStreamSpec.buildVideoUri(videoId, actualStreamHeight),
                qualityLabel = tier.label,
                resolutionHeight = tier.height,
                width = actualStreamWidth,
                format = "MP4",
                mimeType = "video/mp4",
                estimatedBytes = estimatedBytes,
                supportsRange = true,
                bitrateKbps = chosenVideo?.let { it.tbr.toInt().takeIf { b -> b > 0 } },
                isHighestQuality = index == 0
            )
        }

        val resolvedVideoOptions = videoOptions.ifEmpty {
            listOf(
                MediaStreamOption(
                    id = UUID.randomUUID().toString(),
                    streamUrl = YouTubeStreamSpec.buildVideoUri(videoId),
                    qualityLabel = "HD Quality",
                    resolutionHeight = null,
                    format = "MP4",
                    mimeType = "video/mp4",
                    estimatedBytes = directSize(bestAnyAudio),
                    supportsRange = true,
                    isHighestQuality = true
                )
            )
        }

        // MP3 320 kbps transcode size is bounded by source audio size or 40 KB/s * duration;
        // never fall back to muxed video size for the audio-only option.
        val audioEstimatedBytes = directSize(bestAnyAudio)
            ?: directSize(bestM4aAudio)
            ?: durationSeconds?.let { (it * 40_000.0).toLong().takeIf { bytes -> bytes > 0L } }

        val audioOption = MediaStreamOption(
            id = UUID.randomUUID().toString(),
            streamUrl = YouTubeStreamSpec.buildAudioUri(videoId),
            qualityLabel = "Audio only · MP3 320 kbps",
            resolutionHeight = null,
            format = "MP3",
            mimeType = "audio/mpeg",
            isAudioOnly = true,
            estimatedBytes = audioEstimatedBytes,
            supportsRange = false,
            bitrateKbps = 320,
            isHighestQuality = false
        )

        return resolvedVideoOptions + audioOption
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
