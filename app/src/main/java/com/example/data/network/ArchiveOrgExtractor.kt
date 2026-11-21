package com.example.data.network

import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.model.VideoQualityTier
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArchiveOrgExtractor @Inject constructor(
    private val api: ArchiveOrgApiService
) : VideoSourceExtractor {

    override val priority: Int = 10

    override fun canHandle(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US)
        return (host == "archive.org" || host.endsWith(".archive.org")) &&
            (parsed.pathSegments.contains("details") || parsed.pathSegments.contains("embed"))
    }

    override suspend fun extract(url: String): ExtractionOutcome {
        val parsed = url.toHttpUrlOrNull()
            ?: return ExtractionOutcome.Failure(DownloadError.InvalidUrl())
        val segments = parsed.pathSegments
        val idx = segments.indexOfFirst { it == "details" || it == "embed" }
        val identifier = segments.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            ?: return ExtractionOutcome.Failure(DownloadError.InvalidUrl("Could not locate Archive.org item identifier."))

        return try {
            val response = api.getMetadata(identifier)
            val server = response.server
            val dir = response.dir
            val files = response.files.orEmpty()

            if (server.isNullOrBlank() || dir.isNullOrBlank() || files.isEmpty()) {
                return ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource("No downloadable media files found on this Archive.org item.")
                )
            }

            val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "ogv")
            val audioExtensions = setOf("mp3", "m4a", "ogg")

            val rawOptions = files.mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                val isVideo = ext in videoExtensions
                val isAudio = ext in audioExtensions
                if (!isVideo && !isAudio) return@mapNotNull null

                val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                val downloadUrl = "https://$server$dir/$encodedName"
                val height = file.height?.toIntOrNull()
                    ?: extractHeightFromName(name)
                val width = file.width?.toIntOrNull()
                val sizeBytes = file.size?.toLongOrNull()
                val qualityLabel = if (isAudio) {
                    "Audio (${ext.uppercase(Locale.US)})"
                } else {
                    VideoQualityTier.formatLabelFromHeight(height)
                }

                MediaStreamOption(
                    id = UUID.randomUUID().toString(),
                    streamUrl = downloadUrl,
                    qualityLabel = qualityLabel,
                    resolutionHeight = if (isAudio) null else height,
                    width = width,
                    format = ext.uppercase(Locale.US),
                    mimeType = if (isAudio) "audio/$ext" else "video/$ext",
                    isAudioOnly = isAudio,
                    estimatedBytes = sizeBytes,
                    supportsRange = true,
                    isHighestQuality = false
                )
            }
                .distinctBy { "${it.qualityLabel}_${it.format}" }
                .sortedWith(
                    compareByDescending<MediaStreamOption> { !it.isAudioOnly }
                        .thenByDescending { it.resolutionHeight ?: 0 }
                        .thenByDescending { it.estimatedBytes ?: 0L }
                )

            if (rawOptions.isEmpty()) {
                return ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource("No MP4, WebM, or audio files were provided for this item.")
                )
            }

            val maxVideoHeight = rawOptions.filter { !it.isAudioOnly }.maxOfOrNull { it.resolutionHeight ?: 0 }
            val taggedOptions = rawOptions.mapIndexed { index, opt ->
                val isTop = !opt.isAudioOnly && (
                    (maxVideoHeight != null && opt.resolutionHeight == maxVideoHeight && index == 0) ||
                        (maxVideoHeight == null && index == 0)
                    )
                opt.copy(isHighestQuality = isTop)
            }

            val durationSec = files.firstNotNullOfOrNull { it.length?.toDoubleOrNull() }
            val durationMs = durationSec?.times(1000)?.toLong()

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = "https://archive.org/details/$identifier",
                    providerName = "Internet Archive",
                    title = response.metadata?.title?.takeIf { it.isNotBlank() } ?: identifier,
                    thumbnailUrl = "https://archive.org/services/img/$identifier",
                    durationMs = durationMs,
                    author = response.metadata?.creator,
                    isLive = false,
                    streams = taggedOptions
                )
            )
        } catch (e: IOException) {
            ExtractionOutcome.Failure(DownloadError.NetworkFailure("Failed to fetch Archive.org metadata: ${e.message}"))
        } catch (e: Exception) {
            ExtractionOutcome.Failure(DownloadError.ServerUnavailable(detail = "Archive.org API error: ${e.message}"))
        }
    }

    private fun extractHeightFromName(name: String): Int? =
        Regex("""(2160|1440|1080|720|480|360|240|144)p""", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
