package com.example.ui.screens.browser

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class BrowserMediaTarget(val badgeLabel: String, val ctaLabel: String, val isDirectMedia: Boolean) {
    VIDEO("Video detected", "Download video / audio", true),
    SHORTS("Short detected", "Download Short", true),
    PLAYLIST("Playlist detected", "Download playlist", true),
    PAGE("YouTube & web", "Analyze current page", false)
}

/** Local filtering and YouTube SPA / cosmetic ad helper for the in-app browser. */
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
        "pubmatic.com",
        "moatads.com",
        "scorecardresearch.com"
    )

    private val blockedYoutubeAdPaths = setOf(
        "/pagead/",
        "/api/stats/ads",
        "/get_midroll_info",
        "/ptracking",
        "/pcs/activeview",
        "/youtubei/v1/player/ad_break"
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

    fun isYouTubeHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    fun detectMediaTarget(url: String): BrowserMediaTarget {
        val parsed = url.toHttpUrlOrNull() ?: return BrowserMediaTarget.PAGE
        val host = parsed.host.lowercase().removePrefix("www.").removePrefix("m.")
        val path = parsed.encodedPath.lowercase()
        return when {
            host == "youtu.be" && !parsed.pathSegments.firstOrNull().isNullOrBlank() -> BrowserMediaTarget.VIDEO
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                path.startsWith("/shorts/") -> BrowserMediaTarget.SHORTS
                path == "/watch" && !parsed.queryParameter("v").isNullOrBlank() -> BrowserMediaTarget.VIDEO
                path == "/playlist" && !parsed.queryParameter("list").isNullOrBlank() -> BrowserMediaTarget.PLAYLIST
                else -> BrowserMediaTarget.PAGE
            }
            else -> BrowserMediaTarget.PAGE
        }
    }

    fun buildHelperScript(adBlockEnabled: Boolean): String {
        val enabledLiteral = if (adBlockEnabled) "true" else "false"
        return """
            (function() {
                window.__svAdBlockEnabled = $enabledLiteral;
                if (!window.__svBridgeInstalled) {
                    window.__svBridgeInstalled = true;
                    var notify = function() {
                        try {
                            if (window.StreamVaultBridge) {
                                window.StreamVaultBridge.onUrlUpdate(window.location.href, document.title || '');
                            }
                        } catch (e) {}
                    };
                    var origPush = history.pushState;
                    history.pushState = function() {
                        var res = origPush.apply(this, arguments);
                        setTimeout(notify, 50);
                        return res;
                    };
                    var origReplace = history.replaceState;
                    history.replaceState = function() {
                        var res = origReplace.apply(this, arguments);
                        setTimeout(notify, 50);
                        return res;
                    };
                    window.addEventListener('popstate', notify);
                    window.addEventListener('hashchange', notify);
                    window.addEventListener('yt-navigate-finish', notify);
                    notify();
                }

                var styleId = 'sv-ad-hide-style';
                var existing = document.getElementById(styleId);
                if (window.__svAdBlockEnabled) {
                    if (!existing && document.head) {
                        var st = document.createElement('style');
                        st.id = styleId;
                        st.textContent = 'ytd-ad-slot-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, .ytp-ad-overlay-container, #masthead-ad, ytd-banner-promo-renderer, ytd-in-feed-ad-layout-renderer, ytm-companion-ad-renderer { display: none !important; }';
                        document.head.appendChild(st);
                    }
                    if (!window.__svAdSkipInterval) {
                        window.__svAdSkipInterval = setInterval(function() {
                            if (!window.__svAdBlockEnabled) return;
                            try {
                                var skipBtn = document.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern');
                                if (skipBtn) skipBtn.click();
                            } catch (e) {}
                        }, 1000);
                    }
                } else {
                    if (existing) {
                        existing.remove();
                    }
                    if (window.__svAdSkipInterval) {
                        clearInterval(window.__svAdSkipInterval);
                        window.__svAdSkipInterval = null;
                    }
                }
            })();
        """.trimIndent()
    }
}
