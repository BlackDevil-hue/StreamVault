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
        val isYouTube = isYouTubeUrl(pageUrl)
        if (isYouTube && !hasAuthenticatedSession()) {
            // Do not pass unauthenticated/guest visitor cookies to YouTube.
            // When an automated request contains an anonymous visitor cookie from an emulator/cloud IP,
            // YouTube's anti-bot system triggers "Sign in to confirm you're not a bot".
            // Cookieless requests with tv/ios/web client emulation succeed cleanly with all resolutions.
            return null
        }
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

    fun hasAuthenticatedSession(): Boolean {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
        val urlsToCheck = listOf(
            YOUTUBE_URL,
            "https://m.youtube.com/",
            "https://accounts.google.com/"
        )
        return urlsToCheck.any { url ->
            val cookieHeader = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
            AUTH_COOKIE_NAMES.any { name ->
                cookieHeader.contains("$name=")
            }
        }
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
        val AUTH_COOKIE_NAMES = setOf(
            "LOGIN_INFO", "SID", "SSID", "SAPISID", "APISID", "HSID",
            "__Secure-1PSID", "__Secure-3PSID"
        )
    }
}
