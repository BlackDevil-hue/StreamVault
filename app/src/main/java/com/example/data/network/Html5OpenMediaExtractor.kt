package com.example.data.network

import com.example.domain.model.DownloadError
import com.example.domain.model.MediaStreamOption
import com.example.domain.model.VideoAnalysisResult
import com.example.domain.model.VideoQualityTier
import com.example.domain.source.ExtractionOutcome
import com.example.domain.source.VideoSourceExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Html5OpenMediaExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val directMediaExtractor: DirectMediaExtractor,
    private val urlValidator: UrlSecurityValidator
) : VideoSourceExtractor {

    override val priority: Int = 200 // Fallback extractor for open HTML5/OpenGraph pages

    override fun canHandle(url: String): Boolean = true

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        try {
            // First check if the URL itself redirects or directly serves video/audio Content-Type
            val probe = directMediaExtractor.probeRemoteMedia(url)
                ?: return@withContext ExtractionOutcome.Failure(DownloadError.ServerUnavailable())

            if (probe.httpCode in listOf(401, 403)) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.RestrictedOrDrm("This page requires authentication or denies download access (HTTP ${probe.httpCode}).")
                )
            }
            if (probe.httpCode >= 400) {
                return@withContext ExtractionOutcome.Failure(DownloadError.ServerUnavailable(probe.httpCode))
            }

            val contentType = probe.contentType?.lowercase(Locale.US).orEmpty()
            if (contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                return@withContext directMediaExtractor.extract(probe.finalUrl)
            }

            if (!contentType.contains("html") && !contentType.contains("xhtml")) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.UnsupportedFormat("Unsupported content type: ${contentType.ifBlank { "unknown" }}")
                )
            }

            // Fetch HTML bounded to 1.5 MB to avoid excessive memory usage
            val request = Request.Builder()
                .url(probe.finalUrl)
                .get()
                .header("User-Agent", DirectMediaExtractor.USER_AGENT)
                .build()

            val html = okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ExtractionOutcome.Failure(DownloadError.ServerUnavailable(resp.code))
                }
                val body = resp.body ?: return@withContext ExtractionOutcome.Failure(
                    DownloadError.ServerUnavailable(detail = "Empty response from page.")
                )
                body.source().let { source ->
                    source.request(1_500_000L)
                    source.buffer.clone().readUtf8()
                }
            }

            // Check for DRM / EME markers
            if (containsDrmMarkers(html)) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.RestrictedOrDrm("This page uses DRM encryption (Widevine/EME) and cannot be downloaded.")
                )
            }

            val baseUrl = probe.finalUrl.toHttpUrlOrNull()
            val title = extractMetaContent(html, "og:title")
                ?: extractHtmlTitle(html)
                ?: "Web Video"
            val thumbnail = extractMetaContent(html, "og:image")
                ?.let { resolveUrl(baseUrl, it) }

            val foundCandidates = mutableListOf<FoundStream>()

            // 1. OpenGraph video meta tags
            val ogVideo = extractMetaContent(html, "og:video:secure_url")
                ?: extractMetaContent(html, "og:video:url")
                ?: extractMetaContent(html, "og:video")
            if (!ogVideo.isNullOrBlank()) {
                val resolved = resolveUrl(baseUrl, ogVideo)
                if (resolved != null && isDirectDownloadableExtension(resolved)) {
                    val h = extractMetaContent(html, "og:video:height")?.toIntOrNull()
                    foundCandidates += FoundStream(resolved, h, null)
                }
            }

            // 2. HTML5 <video src="..."> and <source src="..." type="...">
            val sourceTagRegex = Regex(
                """<(?:source|video)\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""",
                RegexOption.IGNORE_CASE
            )
            sourceTagRegex.findAll(html).forEach { match ->
                val fullTag = match.value
                val rawSrc = match.groupValues[1]
                val resolved = resolveUrl(baseUrl, rawSrc) ?: return@forEach
                if (!isDirectDownloadableExtension(resolved) &&
                    !fullTag.contains("video/", ignoreCase = true) &&
                    !fullTag.contains("audio/", ignoreCase = true)
                ) {
                    return@forEach
                }
                val labelHint = Regex("""(?:size|label|data-quality|res)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(fullTag)?.groupValues?.getOrNull(1)
                val height = labelHint?.let { Regex("""\d{3,4}""").find(it)?.value?.toIntOrNull() }
                foundCandidates += FoundStream(resolved, height, labelHint)
            }

            // 3. JSON-LD VideoObject contentUrl
            val jsonLdRegex = Regex(
                """<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            jsonLdRegex.findAll(html).forEach { match ->
                runCatching {
                    val obj = JSONObject(match.groupValues[1].trim())
                    val contentUrl = obj.optString("contentUrl")
                    if (contentUrl.isNotBlank()) {
                        resolveUrl(baseUrl, contentUrl)?.let { resolved ->
                            if (isDirectDownloadableExtension(resolved)) {
                                foundCandidates += FoundStream(resolved, obj.optInt("height").takeIf { it > 0 }, null)
                            }
                        }
                    }
                }
            }

            if (foundCandidates.isEmpty()) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource(
                        "No directly downloadable video or audio streams were found on this webpage."
                    )
                )
            }

            val options = mutableListOf<MediaStreamOption>()
            for (candidate in foundCandidates.distinctBy { it.url }.take(8)) {
                val valid = urlValidator.validateAndNormalize(candidate.url)
                if (valid !is UrlValidationResult.Valid) continue
                val mediaProbe = runCatching { directMediaExtractor.probeRemoteMedia(valid.httpsUrl) }.getOrNull()
                if (mediaProbe != null && mediaProbe.httpCode < 400) {
                    val mime = mediaProbe.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
                    if (mime.contains("html")) continue
                    val ext = valid.httpsUrl.substringBefore('?').substringAfterLast('.', "mp4")
                        .takeIf { it.length in 2..5 } ?: "mp4"
                    val isAudio = mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "ogg", "wav")
                    val height = candidate.height ?: extractHeightFromText(valid.httpsUrl)
                    options += MediaStreamOption(
                        id = UUID.randomUUID().toString(),
                        streamUrl = valid.httpsUrl,
                        qualityLabel = if (isAudio) "Audio (${ext.uppercase(Locale.US)})" else VideoQualityTier.formatLabelFromHeight(height),
                        resolutionHeight = if (isAudio) null else height,
                        format = ext.uppercase(Locale.US),
                        mimeType = mime.ifBlank { if (isAudio) "audio/$ext" else "video/$ext" },
                        isAudioOnly = isAudio,
                        estimatedBytes = mediaProbe.contentLength,
                        supportsRange = mediaProbe.supportsRange,
                        eTag = mediaProbe.eTag
                    )
                }
            }

            if (options.isEmpty()) {
                return@withContext ExtractionOutcome.Failure(
                    DownloadError.UnsupportedSource("Found media links were unreachable or not directly downloadable.")
                )
            }

            val sorted = options.sortedWith(
                compareByDescending<MediaStreamOption> { !it.isAudioOnly }
                    .thenByDescending { it.resolutionHeight ?: 0 }
                    .thenByDescending { it.estimatedBytes ?: 0L }
            ).mapIndexed { idx, opt ->
                opt.copy(isHighestQuality = idx == 0 && !opt.isAudioOnly)
            }

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = probe.finalUrl,
                    providerName = baseUrl?.host?.removePrefix("www.") ?: "Web Page",
                    title = title,
                    thumbnailUrl = thumbnail,
                    durationMs = null,
                    author = baseUrl?.host?.removePrefix("www."),
                    isLive = false,
                    streams = sorted
                )
            )
        } catch (e: IOException) {
            ExtractionOutcome.Failure(DownloadError.NetworkFailure("Failed to load page: ${e.message}"))
        } catch (e: Exception) {
            ExtractionOutcome.Failure(DownloadError.Unknown(e.message ?: "Failed to analyze webpage."))
        }
    }

    private fun containsDrmMarkers(html: String): Boolean {
        val lower = html.lowercase(Locale.US)
        return lower.contains("com.widevine.alpha") ||
            lower.contains("com.microsoft.playready") ||
            lower.contains("requestmediakeysystemaccess")
    }

    private fun isDirectDownloadableExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".ogg")
            .any { path.endsWith(it) }
    }

    private fun extractMetaContent(html: String, propertyName: String): String? {
        val p1 = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(propertyName)}["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!p1.isNullOrBlank()) return decodeHtmlEntities(p1)
        val p2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(propertyName)}["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        return p2?.let { decodeHtmlEntities(it) }
    }

    private fun extractHtmlTitle(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.let { decodeHtmlEntities(it) }

    private fun decodeHtmlEntities(input: String): String =
        input.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private fun resolveUrl(base: okhttp3.HttpUrl?, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return base?.resolve(trimmed)?.toString()
    }

    private fun extractHeightFromText(text: String): Int? =
        Regex("""(2160|1440|1080|720|480|360|240|144)p?""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private data class FoundStream(
        val url: String,
        val height: Int?,
        val labelHint: String?
    )
}
