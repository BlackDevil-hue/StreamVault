package com.example.ui.screens.browser

import android.net.Uri

/** Local filtering for common advertising networks and recognizable YouTube ad endpoints. */
internal object BrowserAdBlocker {
    private val blockedHostSuffixes = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "amazon-adsystem.com",
        "openx.net",
        "rubiconproject.com",
        "pubmatic.com"
    )

    private val blockedYoutubeAdPaths = setOf(
        "/pagead/",
        "/api/stats/ads",
        "/get_midroll_info",
        "/ptracking"
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (blockedHostSuffixes.any { host == it || host.endsWith(".$it") }) return true
        if (host == "adservice.google.com" || host.startsWith("adservice.google.")) return true

        val isYoutubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
        if (!isYoutubeHost) return false
        val path = uri.encodedPath?.lowercase().orEmpty()
        return blockedYoutubeAdPaths.any(path::contains)
    }
}
