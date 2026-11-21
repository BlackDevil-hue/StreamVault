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
        const val YOUTUBE_EXTRACTOR_ARGS =
            // Let the current yt-dlp release choose its supported clients. A fixed list of
            // mobile/embedded clients can yield SABR-only responses with no downloadable URLs.
            "youtube:player_client=default"
    }
}
