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
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(pageUrl)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val cookieDomain = pageUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
            .takeIf { it.isNotBlank() } ?: return null

        val cookies = cookieHeader.split(';').mapNotNull { item ->
            val cookie = item.trim()
            val separator = cookie.indexOf('=')
            if (separator <= 0) null else cookie.substring(0, separator) to cookie.substring(separator + 1)
        }
        if (cookies.isEmpty()) return null

        val cookieFile = File.createTempFile("streamvault-cookies-", ".txt", context.cacheDir)
        cookieFile.bufferedWriter().use { writer ->
            writer.appendLine("# Netscape HTTP Cookie File")
            cookies.forEach { (name, value) ->
                writer.appendLine(".$cookieDomain\tTRUE\t/\tTRUE\t0\t$name\t$value")
            }
        }
        request.addOption("--cookies", cookieFile.absolutePath)
        return cookieFile
    }

    private companion object {
        const val YOUTUBE_URL = "https://www.youtube.com/"
    }
}
