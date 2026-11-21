package com.example.data.network

import com.example.domain.model.DownloadError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class UrlValidationResult {
    data class Valid(val httpsUrl: String, val host: String) : UrlValidationResult()
    data class Invalid(val error: DownloadError) : UrlValidationResult()
}

@Singleton
class UrlSecurityValidator @Inject constructor() {

    private val blockedHosts = setOf(
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "::1",
        "169.254.169.254",
        "metadata.google.internal"
    )

    private val drmRestrictedDomains = listOf(
        "netflix.com",
        "disneyplus.com",
        "primevideo.com",
        "hulu.com",
        "max.com",
        "hbomax.com",
        "peacocktv.com",
        "paramountplus.com",
        "appletv.apple.com",
        "spotify.com",
        "vimeo.com" // Note: Vimeo requires authorized API tokens / platform terms unless direct MP4 link provided
    )

    private val urlRegex = Regex(
        """https?://[^\s<>"{}|\\^`\[\]]+""",
        RegexOption.IGNORE_CASE
    )

    fun extractFirstUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text.trim())?.value ?: return null
        return match.trimEnd('.', ',', ';', '!', ')', ']', '>', '"', '\'')
    }

    fun validateAndNormalize(rawInput: String): UrlValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return UrlValidationResult.Invalid(DownloadError.InvalidUrl("Please paste or enter a video URL."))
        }

        val candidate = if (!trimmed.contains("://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val parsed: HttpUrl = candidate.toHttpUrlOrNull()
            ?: return UrlValidationResult.Invalid(DownloadError.InvalidUrl("The URL format is invalid."))

        if (parsed.scheme != "https" && parsed.scheme != "http") {
            return UrlValidationResult.Invalid(
                DownloadError.InvalidUrl("Only secure HTTP/HTTPS web URLs are supported.")
            )
        }

        // Prefer HTTPS wherever possible (cleartext traffic is disabled in manifest)
        val secureUrl = if (parsed.scheme == "http") {
            parsed.newBuilder().scheme("https").build()
        } else {
            parsed
        }

        val host = secureUrl.host.lowercase(Locale.US)
        if (host.isBlank() || !host.contains('.') && host !in blockedHosts) {
            return UrlValidationResult.Invalid(DownloadError.InvalidUrl("Invalid website hostname."))
        }

        if (isPrivateOrLocalHost(host)) {
            return UrlValidationResult.Invalid(
                DownloadError.InvalidUrl("Local network and private IP addresses are not allowed.")
            )
        }

        if (isRestrictedPlatform(host, secureUrl.encodedPath)) {
            return UrlValidationResult.Invalid(
                DownloadError.RestrictedOrDrm(
                    "Downloads from $host are not supported due to platform terms, DRM, or access restrictions. Use direct media URLs or authorized open-media hosts."
                )
            )
        }

        return UrlValidationResult.Valid(
            httpsUrl = secureUrl.toString(),
            host = host
        )
    }

    fun isSafeRedirectTarget(url: HttpUrl): Boolean {
        if (url.scheme != "https") return false
        val host = url.host.lowercase(Locale.US)
        return !isPrivateOrLocalHost(host)
    }

    fun isPrivateOrLocalHost(host: String): Boolean {
        val lower = host.lowercase(Locale.US).trim('[', ']')
        if (lower in blockedHosts || lower.endsWith(".local") || lower.endsWith(".internal")) {
            return true
        }
        // Check numeric IP literals synchronously without triggering unnecessary DNS
        if (isIpLiteral(lower)) {
            return try {
                val addr = InetAddress.getByName(lower)
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isMulticastAddress ||
                    isCarrierGradeNat(addr)
            } catch (_: UnknownHostException) {
                true
            }
        }
        return false
    }

    fun sanitizeFileName(rawTitle: String, extension: String): String {
        val cleanExt = extension.lowercase(Locale.US).trimStart('.').ifBlank { "mp4" }
        val base = rawTitle
            .replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .take(80)
            .ifBlank { "StreamVault_Video_${System.currentTimeMillis()}" }
        return if (base.endsWith(".$cleanExt", ignoreCase = true)) base else "$base.$cleanExt"
    }

    private fun isRestrictedPlatform(host: String, path: String): Boolean {
        // Allow direct .mp4/.webm/.m4a CDN links even if path ends with media extension on open CDNs,
        // otherwise check known DRM/restricted consumer platforms
        val lowerPath = path.lowercase(Locale.US)
        val isDirectMediaPath = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".mp3", ".m4a")
            .any { lowerPath.endsWith(it) }
        if (isDirectMediaPath) return false

        return drmRestrictedDomains.any { restricted ->
            host == restricted || host.endsWith(".$restricted")
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        return ipv4.matches(host) || host.contains(':')
    }

    private fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // 100.64.0.0/10
        return b0 == 100 && b1 in 64..127
    }
}
