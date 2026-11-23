package com.example.data.network

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Passes the in-app WebView session to yt-dlp using a short-lived private cookie file. */
@Singleton
class YoutubeCookieProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun attachCookies(request: YoutubeDLRequest, pageUrl: String = YOUTUBE_URL): File? {
        runCatching { CookieManager.getInstance().flush() }
        val entries = collectCookieEntries(pageUrl)
        if (entries.isEmpty()) return null

        val expirySeconds = (System.currentTimeMillis() / 1000L) + THIRTY_DAYS_SECONDS
        val cookieFile = File.createTempFile("streamvault-cookies-", ".txt", context.cacheDir)
        cookieFile.bufferedWriter().use { writer ->
            writer.appendLine("# Netscape HTTP Cookie File")
            entries.forEach { (domain, name, value) ->
                writer.appendLine("$domain\tTRUE\t/\tTRUE\t$expirySeconds\t$name\t$value")
            }
        }
        request.addOption("--cookies", cookieFile.absolutePath)
        return cookieFile
    }

    private data class CookieEntry(
        val domain: String,
        val name: String,
        val value: String
    )

    private fun collectCookieEntries(pageUrl: String): List<CookieEntry> {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        val isYouTube = isYouTubeUrl(pageUrl)
        val targets = if (isYouTube) {
            listOf(
                "https://www.youtube.com/" to ".youtube.com",
                "https://m.youtube.com/" to ".youtube.com",
                "https://music.youtube.com/" to ".youtube.com",
                "https://youtube.com/" to ".youtube.com",
                "https://accounts.google.com/" to ".google.com",
                "https://www.google.com/" to ".google.com"
            )
        } else {
            val domain = extractCookieDomain(pageUrl) ?: return emptyList()
            listOf(pageUrl to ".$domain")
        }

        val seen = LinkedHashSet<Pair<String, String>>()
        val result = mutableListOf<CookieEntry>()
        for ((url, domain) in targets) {
            val header = runCatching { manager.getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: continue
            header.split(';').forEach { item ->
                val cookie = item.trim()
                val separator = cookie.indexOf('=')
                if (separator > 0) {
                    val name = cookie.substring(0, separator).trim()
                    val value = cookie.substring(separator + 1)
                    if (name.isNotEmpty() && seen.add(domain to name)) {
                        result += CookieEntry(domain = domain, name = name, value = value)
                    }
                }
            }
        }
        return result
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    private fun extractCookieDomain(pageUrl: String): String? =
        pageUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
            .takeIf { it.isNotBlank() }

    private companion object {
        const val YOUTUBE_URL = "https://www.youtube.com/"
        const val THIRTY_DAYS_SECONDS = 30L * 24L * 60L * 60L
    }
}
