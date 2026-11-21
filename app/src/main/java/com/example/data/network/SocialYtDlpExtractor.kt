package com.example.data.network

import android.util.Base64
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

/** Instagram and X posts are resolved through the bundled yt-dlp engine. */
@Singleton
class SocialYtDlpExtractor @Inject constructor(
    private val youtubeDlRuntime: YoutubeDlRuntime,
    private val cookieProvider: YoutubeCookieProvider
) : VideoSourceExtractor {
    override val priority: Int = 4

    override fun canHandle(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US)
            ?.removePrefix("www.")?.removePrefix("mobile.")?.removePrefix("m.") ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "x.com" || host.endsWith(".x.com") ||
            host == "twitter.com" || host.endsWith(".twitter.com")
    }

    override suspend fun extract(url: String): ExtractionOutcome = withContext(Dispatchers.IO) {
        try {
            youtubeDlRuntime.ensureLatestYoutubeDl()
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--skip-download")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            val cookieFile = cookieProvider.attachCookies(request, url)
            val output = try {
                YoutubeDL.getInstance().execute(request).out
            } finally {
                cookieFile?.delete()
            }
            val info = JSONObject(output)
            val title = info.optString("title").takeIf { it.isNotBlank() }
                ?: info.optString("description").lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "Social video"
            val artist = info.optString("uploader").takeIf { it.isNotBlank() }
                ?: info.optString("channel").takeIf { it.isNotBlank() }
            val canonicalUrl = info.optString("webpage_url").takeIf { it.isNotBlank() } ?: url
            val encodedUrl = Base64.encodeToString(
                canonicalUrl.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val estimatedBytes = estimateBestVideoSize(info)
            val hostName = canonicalUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.US)
                ?.removePrefix("www.")?.removePrefix("mobile.")?.removePrefix("m.") ?: ""
            val providerName = when {
                hostName == "instagram.com" || hostName.endsWith(".instagram.com") -> "Instagram"
                hostName == "x.com" || hostName.endsWith(".x.com") ||
                    hostName == "twitter.com" || hostName.endsWith(".twitter.com") -> "X"
                else -> "Social media"
            }

            ExtractionOutcome.Success(
                VideoAnalysisResult(
                    sourceUrl = url,
                    normalizedUrl = canonicalUrl,
                    providerName = providerName,
                    title = title,
                    thumbnailUrl = info.optString("thumbnail").takeIf { it.isNotBlank() },
                    author = artist,
                    durationMs = info.optDouble("duration", 0.0).takeIf { it > 0.0 }?.times(1000)?.toLong(),
                    isLive = false,
                    streams = listOf(
                        MediaStreamOption(
                            id = UUID.randomUUID().toString(),
                            streamUrl = "yt-dlp://social/$encodedUrl",
                            qualityLabel = "Best available quality",
                            resolutionHeight = info.optInt("height").takeIf { it > 0 },
                            width = info.optInt("width").takeIf { it > 0 },
                            format = "MP4",
                            mimeType = "video/mp4",
                            estimatedBytes = estimatedBytes,
                            supportsRange = false,
                            isHighestQuality = true
                        )
                    )
                )
            )
        } catch (error: Exception) {
            val detail = error.message.orEmpty().lineSequence().firstOrNull()?.take(180)
                ?: "yt-dlp could not read this post."
            ExtractionOutcome.Failure(
                DownloadError.UnsupportedSource("Social video extraction failed: $detail")
            )
        }
    }

    private fun estimateBestVideoSize(info: JSONObject): Long? {
        val formats = info.optJSONArray("formats") ?: return info.optLong("filesize", 0L).takeIf { it > 0L }
        val candidates = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
        val best = candidates.maxWithOrNull(
            compareBy<JSONObject> { it.optInt("height") }
                .thenBy { it.optDouble("fps") }
                .thenBy { it.optDouble("tbr") }
        )
        return best?.optLong("filesize", 0L)?.takeIf { it > 0L }
            ?: info.optLong("filesize", 0L).takeIf { it > 0L }
    }
}
