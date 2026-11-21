package com.example.domain.model

sealed class DownloadError(val userMessage: String, val recoverable: Boolean = true) {
    data class InvalidUrl(val detail: String = "Please enter a valid HTTP or HTTPS video URL.") :
        DownloadError(detail, recoverable = false)

    data class UnsupportedSource(
        val detail: String = "This website does not provide an authorized downloadable media stream."
    ) : DownloadError(detail, recoverable = false)

    data class RestrictedOrDrm(
        val detail: String = "This video is protected by DRM, authentication, or platform download restrictions and cannot be downloaded."
    ) : DownloadError(detail, recoverable = false)

    data class NetworkFailure(
        val detail: String = "Network connection lost or timed out. Check your connection and retry."
    ) : DownloadError(detail, recoverable = true)

    data class InsufficientStorage(
        val detail: String = "Not enough free storage space to save this video."
    ) : DownloadError(detail, recoverable = false)

    data class Interrupted(
        val detail: String = "Download was interrupted. Resume or retry to continue."
    ) : DownloadError(detail, recoverable = true)

    data class ServerUnavailable(
        val code: Int? = null,
        val detail: String = "The media server is currently unavailable${if (code != null) " (HTTP $code)" else ""}."
    ) : DownloadError(detail, recoverable = true)

    data class UnsupportedFormat(
        val detail: String = "Unsupported or unreadable media format."
    ) : DownloadError(detail, recoverable = false)

    data class PermissionDenied(
        val detail: String = "Required permission or folder access was denied."
    ) : DownloadError(detail, recoverable = true)

    data class Unknown(val detail: String = "An unexpected error occurred.") :
        DownloadError(detail, recoverable = true)
}
