package com.example.data.network

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Initializes the bundled download engines only when a YouTube operation needs them. */
@Singleton
class YoutubeDlRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val initializationMutex = Mutex()
    private var youtubeDlInitialized = false
    private var youtubeDlUpdated = false
    private var ffmpegInitialized = false

    suspend fun ensureInitialized(needsFfmpeg: Boolean) = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            if (!youtubeDlInitialized) {
                YoutubeDL.getInstance().init(context)
                youtubeDlInitialized = true
            }
            if (needsFfmpeg && !ffmpegInitialized) {
                FFmpeg.getInstance().init(context)
                ffmpegInitialized = true
            }
        }
    }

    suspend fun ensureLatestYoutubeDl() {
        ensureInitialized(needsFfmpeg = false)
        withContext(Dispatchers.IO) {
            initializationMutex.withLock {
                if (!youtubeDlUpdated) {
                    // If the update server is unavailable, continue with the bundled extractor.
                    youtubeDlUpdated = runCatching {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            context,
                            YoutubeDL.UpdateChannel.NIGHTLY
                        )
                    }.isSuccess
                }
            }
        }
    }

    companion object {
        /**
         * Keep yt-dlp's version-aware defaults and add Safari's pre-merged HLS ladder.
         * Those HLS formats can expose higher resolutions without the GVS PO token required
         * by many separate video/audio formats. The same client set is used for analysis
         * and download so each displayed resolution remains selectable.
         */
        const val YOUTUBE_EXTRACTOR_ARGS =
            "youtube:player_client=default,web_safari"

        /** Retry the trusted HLS client first, then the compatible default clients. */
        const val YOUTUBE_COOKIES_FALLBACK_ARGS =
            "youtube:player_client=web_safari,web_embedded,tv_samsung,web"

        /** Last unauthenticated retry; Android clients can require PO tokens. */
        const val YOUTUBE_COOKIELESS_FALLBACK_ARGS =
            "youtube:player_client=web_safari,tv_samsung,visionos"

        fun isBotVerificationError(errorText: String): Boolean {
            val lower = errorText.lowercase()
            return lower.contains("sign in to confirm") ||
                lower.contains("not a bot")
        }
    }
}
