package com.example.domain.model

enum class VideoQualityTier(val height: Int, val label: String) {
    AUDIO_ONLY(0, "Audio Only"),
    P144(144, "144p"),
    P240(240, "240p"),
    P360(360, "360p"),
    P480(480, "480p"),
    P720(720, "720p HD"),
    P1080(1080, "1080p Full HD"),
    P1440(1440, "1440p 2K"),
    P2160(2160, "2160p 4K"),
    ORIGINAL(-1, "Original Quality");

    companion object {
        fun fromHeight(height: Int?): VideoQualityTier {
            if (height == null || height <= 0) return ORIGINAL
            return when {
                height >= 2160 -> P2160
                height >= 1440 -> P1440
                height >= 1080 -> P1080
                height >= 720 -> P720
                height >= 480 -> P480
                height >= 360 -> P360
                height >= 240 -> P240
                else -> P144
            }
        }

        fun formatLabelFromHeight(height: Int?): String {
            if (height == null || height <= 0) return ORIGINAL.label
            return fromHeight(height).label
        }
    }
}

data class MediaStreamOption(
    val id: String,
    val streamUrl: String,
    val qualityLabel: String,
    val resolutionHeight: Int?,
    val width: Int? = null,
    val format: String,
    val mimeType: String,
    val isAudioOnly: Boolean = false,
    val estimatedBytes: Long? = null,
    val supportsRange: Boolean = false,
    val bitrateKbps: Int? = null,
    val isHighestQuality: Boolean = false,
    val eTag: String? = null
)

data class VideoAnalysisResult(
    val sourceUrl: String,
    val normalizedUrl: String,
    val providerName: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val author: String? = null,
    val isLive: Boolean = false,
    val streams: List<MediaStreamOption>
)
