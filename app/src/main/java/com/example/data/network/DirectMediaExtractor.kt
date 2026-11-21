package com.example.data.network

import android.media.MediaMetadataRetriever
import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.model.VideoQualityTier
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectMediaExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient
) : VideoSourceExtractor {

    override val priority: Int = 100

    private val directMediaExtensions = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "3gp", "mp3", "m4a", "aac", "ogg", "wav"
    )

    override fun canHandle(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val ext = cleanPath.substringAfterLast('.', "")
        return ext in directMediaExtensions
    }

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        try {
            val probe = probeRemoteMedia(url)
                ?: return@withContext ExtractionOutcome.Failure(
                    DownloadError.ServerUnavailable(detail = "Unable to connect to media server.")
                )

            if (probe.httpCode in listOf(401, 403)) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.RestrictedOrDrm("Access denied by server (HTTP ${probe.httpCode}). Protected or private videos cannot be downloaded.")
                )
            }
            if (probe.httpCode >= 400) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.ServerUnavailable(probe.httpCode)
                )
            }

            val finalUrl = probe.finalUrl
            val mime = probe.contentType?.lowercase(Locale.US)?.substringBefore(';')?.trim().orEmpty()
            val ext = inferExtension(finalUrl, mime)
            val isMediaMime = mime.startsWith("video/") || mime.startsWith("audio/") ||
                mime == "application/octet-stream" || mime.isBlank()

            if (!isMediaMime && !canHandle(finalUrl)) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.UnsupportedFormat("The URL returned '$mime' rather than a supported video or audio file.")
                )
            }

            if (mime.contains("dash+xml") || mime.contains("mpegurl")) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.UnsupportedFormat("Adaptive manifest streams require a direct downloadable media URL.")
                )
            }

            val isAudioOnly = mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "wav")
            val rawName = extractFileTitle(finalUrl)

            // Best-effort metadata probe via MediaMetadataRetriever for direct remote MP4/audio
            val mediaProbe = if (!isAudioOnly) probeMediaDimensions(finalUrl) else null
            val heightFromUrl = extractResolutionHint(finalUrl)
            val resolvedHeight = mediaProbe?.height ?: heightFromUrl
            val qualityLabel = when {
                isAudioOnly -> "Audio (${ext.uppercase(Locale.US)})"
                resolvedHeight != null -> VideoQualityTier.formatLabelFromHeight(resolvedHeight)
                else -> "Original Source"
            }

            val option = MediaStreamOption(
                id = UUID.randomUUID().toString(),
                streamUrl = finalUrl,
                qualityLabel = qualityLabel,
                resolutionHeight = resolvedHeight,
                width = mediaProbe?.width,
                format = ext.uppercase(Locale.US),
                mimeType = mime.ifBlank { if (isAudioOnly) "audio/$ext" else "video/$ext" },
                isAudioOnly = isAudioOnly,
                estimatedBytes = probe.contentLength,
                supportsRange = probe.supportsRange,
                bitrateKbps = mediaProbe?.bitrateKbps,
                isHighestQuality = true,
                eTag = probe.eTag
            )

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = finalUrl,
                    providerName = "Direct Media Link",
                    title = rawName,
                    thumbnailUrl = null,
                    durationMs = mediaProbe?.durationMs,
                    author = extractHostName(finalUrl),
                    isLive = false,
                    streams = listOf(option)
                )
            )
        } catch (e: IOException) {
            ExtractionOutcome.Failure(
                DownloadError.NetworkFailure("Could not reach the video URL: ${e.message ?: "connection error"}")
            )
        } catch (e: Exception) {
            ExtractionOutcome.Failure(
                DownloadError.Unknown(e.message ?: "Failed to inspect direct video link.")
            )
        }
    }

    suspend fun probeRemoteMedia(url: String): RemoteMediaProbe? = withContext(Dispatchers.IO) {
        val headReq = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", USER_AGENT)
            .build()

        okHttpClient.newCall(headReq).execute().use { resp ->
            if (resp.code == 405 || resp.code == 501 || resp.header("Content-Length") == null) {
                // Fallback to 1-byte Range GET to accurately discover Content-Range total & Accept-Ranges
                val rangeReq = Request.Builder()
                    .url(url)
                    .get()
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", USER_AGENT)
                    .build()
                return@use okHttpClient.newCall(rangeReq).execute().use { rangeResp ->
                    val contentRange = rangeResp.header("Content-Range")
                    val totalFromRange = contentRange
                        ?.substringAfter('/', "")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    val contentLen = totalFromRange
                        ?: rangeResp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 1 }
                    val acceptsRange = rangeResp.code == 206 ||
                        rangeResp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    RemoteMediaProbe(
                        httpCode = rangeResp.code,
                        finalUrl = rangeResp.request.url.toString(),
                        contentType = rangeResp.header("Content-Type"),
                        contentLength = contentLen,
                        supportsRange = acceptsRange,
                        eTag = rangeResp.header("ETag")
                    )
                }
            } else {
                val len = resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                val acceptsRange = resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                RemoteMediaProbe(
                    httpCode = resp.code,
                    finalUrl = resp.request.url.toString(),
                    contentType = resp.header("Content-Type"),
                    contentLength = len,
                    supportsRange = acceptsRange,
                    eTag = resp.header("ETag")
                )
            }
        }
    }

    private fun probeMediaDimensions(url: String): RemoteVideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, mapOf("User-Agent" to USER_AGENT))
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000)
            RemoteVideoMetadata(width = width, height = height, durationMs = durationMs, bitrateKbps = bitrate)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inferExtension(url: String, mime: String): String {
        val pathExt = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        if (pathExt in directMediaExtensions) return pathExt
        return when {
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("matroska") -> "mkv"
            mime.contains("quicktime") -> "mov"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("aac") || mime.contains("m4a") -> "m4a"
            mime.contains("ogg") -> "ogg"
            else -> "mp4"
        }
    }

    private fun extractFileTitle(url: String): String {
        val segment = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('/')
            .substringBeforeLast('.')
        val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        return decoded
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Video_${System.currentTimeMillis()}" }
    }

    private fun extractResolutionHint(url: String): Int? {
        val match = Regex("""(?:^|[_\-.x/])(2160|1440|1080|720|480|360|240|144)p?(?:$|[_\-./?])""", RegexOption.IGNORE_CASE)
            .find(url)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractHostName(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.")

    data class RemoteMediaProbe(
        val httpCode: Int,
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val supportsRange: Boolean,
        val eTag: String?
    )

    private data class RemoteVideoMetadata(
        val width: Int?,
        val height: Int?,
        val durationMs: Long?,
        val bitrateKbps: Int?
    )

    companion object {
        const val USER_AGENT = "StreamVault-Android/1.0"
    }
}
