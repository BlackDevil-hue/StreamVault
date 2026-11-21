package com.example.data.network

import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.model.VideoQualityTier
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WikimediaExtractor @Inject constructor(
    private val api: WikimediaApiService
) : VideoSourceExtractor {

    override val priority: Int = 15

    override fun canHandle(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.US)
        return host == "commons.wikimedia.org" && url.contains("File:", ignoreCase = true)
    }

    override suspend fun extract(url: String): ExtractionOutcome {
        val filePart = url.substringAfter("File:", "")
            .substringBefore('#')
            .substringBefore('?')
        if (filePart.isBlank()) {
            return ExtractionOutcome.Failure(DownloadError.InvalidUrl("Missing Wikimedia File title."))
        }

        val decodedFile = runCatching { URLDecoder.decode(filePart, "UTF-8") }.getOrDefault(filePart)
        val fileTitle = "File:$decodedFile"

        return try {
            val response = api.getMediaInfo(titles = fileTitle)
            val page = response.query?.pages?.values?.firstOrNull()
            val info = page?.videoinfo?.firstOrNull() ?: page?.imageinfo?.firstOrNull()
                ?: return ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource("No downloadable video or audio metadata found on Wikimedia Commons.")
                )

            val streams = mutableListOf<MediaStreamOption>()

            val origUrl = info.url
            val origMime = info.mime.orEmpty()
            if (!origUrl.isNullOrBlank() && (origMime.startsWith("video/") || origMime.startsWith("audio/"))) {
                val ext = origUrl.substringAfterLast('.', "webm").substringBefore('?').uppercase(Locale.US)
                val isAudio = origMime.startsWith("audio/")
                streams += MediaStreamOption(
                    id = UUID.randomUUID().toString(),
                    streamUrl = origUrl,
                    qualityLabel = if (isAudio) "Audio ($ext)" else VideoQualityTier.formatLabelFromHeight(info.height),
                    resolutionHeight = if (isAudio) null else info.height,
                    width = info.width,
                    format = ext,
                    mimeType = origMime,
                    isAudioOnly = isAudio,
                    estimatedBytes = info.size,
                    supportsRange = true
                )
            }

            info.derivatives.orEmpty().forEach { deriv ->
                val src = deriv.src ?: return@forEach
                val type = deriv.type.orEmpty()
                val mime = type.substringBefore(';').trim()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) return@forEach
                val isAudio = mime.startsWith("audio/")
                val ext = src.substringAfterLast('.', "mp4").substringBefore('?').uppercase(Locale.US)
                streams += MediaStreamOption(
                    id = UUID.randomUUID().toString(),
                    streamUrl = src,
                    qualityLabel = if (isAudio) "Audio ($ext)" else VideoQualityTier.formatLabelFromHeight(deriv.height),
                    resolutionHeight = if (isAudio) null else deriv.height,
                    width = deriv.width,
                    format = ext,
                    mimeType = mime,
                    isAudioOnly = isAudio,
                    estimatedBytes = null,
                    supportsRange = true,
                    bitrateKbps = deriv.bandwidth?.div(1000)?.toInt()
                )
            }

            if (streams.isEmpty()) {
                return ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource("This Wikimedia Commons file is not a video or audio asset.")
                )
            }

            val sorted = streams
                .distinctBy { it.streamUrl }
                .sortedWith(
                    compareByDescending<MediaStreamOption> { !it.isAudioOnly }
                        .thenByDescending { it.resolutionHeight ?: 0 }
                )
            val tagged = sorted.mapIndexed { idx, opt ->
                opt.copy(isHighestQuality = idx == 0 && !opt.isAudioOnly)
            }

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = url,
                    providerName = "Wikimedia Commons",
                    title = decodedFile.replace('_', ' ').substringBeforeLast('.'),
                    thumbnailUrl = info.thumburl,
                    durationMs = info.duration?.times(1000)?.toLong(),
                    author = "Wikimedia Commons",
                    isLive = false,
                    streams = tagged
                )
            )
        } catch (e: IOException) {
            ExtractionOutcome.Failure(DownloadError.NetworkFailure("Failed to connect to Wikimedia Commons: ${e.message}"))
        } catch (e: Exception) {
            ExtractionOutcome.Failure(DownloadError.ServerUnavailable(detail = "Wikimedia API error: ${e.message}"))
        }
    }
}
