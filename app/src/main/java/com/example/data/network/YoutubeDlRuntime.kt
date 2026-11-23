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
         * Let the installed yt-dlp version select its supported default YouTube clients.
         * Pinning a client list here quickly goes stale as YouTube changes client support,
         * and can force requests through clients that require tokens or trigger verification.
         */
        const val YOUTUBE_EXTRACTOR_ARGS =
            "youtube:player_client=default"

        /** Compatibility retry for versions/videos where the default set is insufficient. */
        const val YOUTUBE_COOKIES_FALLBACK_ARGS =
            "youtube:player_client=web_embedded,tv_samsung,web"

        /** Last unauthenticated retry; Android clients can require PO tokens. */
        const val YOUTUBE_COOKIELESS_FALLBACK_ARGS =
            "youtube:player_client=tv_samsung,visionos"

        fun isBotVerificationError(errorText: String): Boolean {
            val lower = errorText.lowercase()
            return lower.contains("sign in to confirm") ||
                lower.contains("not a bot")
        }
    }
}
