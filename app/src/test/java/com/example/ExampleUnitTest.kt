package com.example

import com.example.data.network.UrlSecurityValidator
import com.example.data.network.UrlValidationResult
import com.example.data.worker.YtDlpProgressTracker
import com.example.domain.model.DownloadError
import com.example.domain.model.VideoQualityTier
import com.example.ui.components.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private val validator = UrlSecurityValidator()

    @Test
    fun `validates and upgrades http URL to https`() {
        val result = validator.validateAndNormalize("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertTrue(valid.httpsUrl.startsWith("https://"))
    }

    @Test
    fun `blocks localhost and private IP SSRF targets`() {
        val targets = listOf(
            "https://localhost/video.mp4",
            "https://127.0.0.1/video.mp4",
            "https://192.168.1.50/video.mp4",
            "https://10.0.0.2/video.mp4",
            "https://169.254.169.254/latest/meta-data"
        )
        targets.forEach { url ->
            val res = validator.validateAndNormalize(url)
            assertTrue("Expected $url to be blocked", res is UrlValidationResult.Invalid)
        }
    }

    @Test
    fun `rejects DRM restricted platforms gracefully`() {
        val res = validator.validateAndNormalize("https://www.netflix.com/watch/12345")
        assertTrue(res is UrlValidationResult.Invalid)
        assertTrue((res as UrlValidationResult.Invalid).error is DownloadError.RestrictedOrDrm)
    }

    @Test
    fun `extracts URL from shared text snippet`() {
        val text = "Check out this open video: https://archive.org/details/BigBuckBunny_328! #video"
        val extracted = validator.extractFirstUrlFromText(text)
        assertEquals("https://archive.org/details/BigBuckBunny_328", extracted)
    }

    @Test
    fun `sanitizes unsafe characters in output file name`() {
        val clean = validator.sanitizeFileName("My: Video / Test* <HD>", "mp4")
        assertEquals("My_ Video _ Test_ _HD_.mp4", clean)
    }

    @Test
    fun `quality tier maps resolutions accurately`() {
        assertEquals("2160p 4K", VideoQualityTier.formatLabelFromHeight(2160))
        assertEquals("1440p 2K", VideoQualityTier.formatLabelFromHeight(1440))
        assertEquals("1080p Full HD", VideoQualityTier.formatLabelFromHeight(1080))
        assertEquals("720p HD", VideoQualityTier.formatLabelFromHeight(720))
        assertEquals("480p", VideoQualityTier.formatLabelFromHeight(480))
        assertEquals("360p", VideoQualityTier.formatLabelFromHeight(360))
        assertEquals("240p", VideoQualityTier.formatLabelFromHeight(240))
        assertEquals("144p", VideoQualityTier.formatLabelFromHeight(144))
    }

    @Test
    fun `format utils formats bytes speed eta and duration`() {
        assertEquals("Unknown size", FormatUtils.formatBytes(0L))
        assertEquals("0 B", FormatUtils.formatProgressBytes(0L))
        assertEquals("1.0 MB", FormatUtils.formatProgressBytes(1024L * 1024L))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("2.5 MB/s", FormatUtils.formatSpeed((2.5 * 1024 * 1024).toLong()))
        assertEquals("2m 5s", FormatUtils.formatEta(125L))
        assertEquals("01:05", FormatUtils.formatDuration(65_000L))
    }

    @Test
    fun `yt-dlp tracker tracks approximate tilde sizes and refines dynamically`() {
        val tracker = YtDlpProgressTracker(expectedBytes = 0L)
        tracker.update(-1f, 0L, "[download] Destination: /tmp/video.mp4")

        val snap1 = tracker.update(25f, 12L, "[download]  25.0% of ~ 40.00MiB at  2.00MiB/s ETA 00:15 (frag 4/16)")
        assertEquals(40L * 1024 * 1024, snap1.totalBytes)
        assertEquals(10L * 1024 * 1024, snap1.bytesDownloaded)
        assertEquals(25, snap1.percent)

        // Approximate total is updated if later fragment estimates a smaller total
        val snap2 = tracker.update(50f, 8L, "[download]  50.0% of ~ 36.00MiB at  2.00MiB/s ETA 00:09 (frag 8/16)")
        assertEquals(36L * 1024 * 1024, snap2.totalBytes)
        assertEquals(18L * 1024 * 1024, snap2.bytesDownloaded)
        assertEquals(50, snap2.percent)
    }

    @Test
    fun `yt-dlp tracker handles multi-track downloads and resume without double counting`() {
        val fileSizes = mapOf("/tmp/id.f137.mp4" to 40L * 1024 * 1024)
        val tracker = YtDlpProgressTracker(
            expectedBytes = 0L,
            initialDownloadedBytes = 42L * 1024 * 1024,
            fileLengthProvider = { path -> fileSizes[path] ?: 0L }
        )

        tracker.update(-1f, 0L, "[info] abcdefghijk: Downloading 1 format(s): 137+140")
        val skippedTrack1 = tracker.update(-1f, 0L, "[download] /tmp/id.f137.mp4 has already been downloaded")
        assertEquals(40L * 1024 * 1024, skippedTrack1.totalBytes)
        assertEquals(90, skippedTrack1.percent)

        tracker.update(-1f, 0L, "[download] Destination: /tmp/id.f140.m4a")
        val track2Progress = tracker.update(50f, 2L, "[download]  50.0% of   10.00MiB at  1.00MiB/s ETA 00:05")
        assertEquals(50L * 1024 * 1024, track2Progress.totalBytes)
        assertEquals(45L * 1024 * 1024, track2Progress.bytesDownloaded)
        assertEquals(90, track2Progress.percent)
        assertFalse(track2Progress.finalizing)

        val finalizing = tracker.update(-1f, 0L, "[Merger] Merging formats into \"/tmp/id.mp4\"")
        assertTrue(finalizing.finalizing)
        assertEquals(99, finalizing.percent)
        assertEquals(50L * 1024 * 1024, finalizing.totalBytes)
    }

    @Test
    fun `yt-dlp tracker parses downloaded bytes when total size is unknown`() {
        val tracker = YtDlpProgressTracker(expectedBytes = 0L)
        tracker.update(-1f, 0L, "[download] Destination: /tmp/stream.mp4")
        val snap = tracker.update(-1f, 0L, "[download]   12.00MiB at  1.50MiB/s (00:08)")
        assertEquals(0L, snap.totalBytes)
        assertEquals(12L * 1024 * 1024, snap.bytesDownloaded)
        assertEquals((1.5 * 1024 * 1024).toLong(), snap.speedBytesPerSec)
    }

    @Test
    fun `browser target detector identifies youtube videos shorts playlists and pages`() {
        assertEquals(
            com.example.ui.screens.browser.BrowserMediaTarget.VIDEO,
            com.example.ui.screens.browser.BrowserAdBlocker.detectMediaTarget("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            com.example.ui.screens.browser.BrowserMediaTarget.SHORTS,
            com.example.ui.screens.browser.BrowserAdBlocker.detectMediaTarget("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
        assertEquals(
            com.example.ui.screens.browser.BrowserMediaTarget.PLAYLIST,
            com.example.ui.screens.browser.BrowserAdBlocker.detectMediaTarget("https://m.youtube.com/playlist?list=PL1234567890")
        )
        assertEquals(
            com.example.ui.screens.browser.BrowserMediaTarget.PAGE,
            com.example.ui.screens.browser.BrowserAdBlocker.detectMediaTarget("https://m.youtube.com/feed/trending")
        )
    }

    @Test
    fun `app settings exposes sensible player browser and download defaults`() {
        val defaults = com.example.domain.model.AppSettings()
        assertTrue(defaults.autoRetryFailed)
        assertTrue(defaults.browserAdBlockerEnabled)
        assertFalse(defaults.browserDesktopMode)
        assertEquals(1.0f, defaults.defaultPlaybackSpeed)
        assertEquals(10, defaults.seekIntervalSeconds)
        assertTrue(defaults.rememberPlaybackPosition)
        assertTrue(defaults.autoPlayNextVideo)
        assertFalse(defaults.notificationPromptDismissed)
        assertFalse(defaults.equalizerEnabled)
        assertEquals(com.example.domain.model.EqualizerPreset.FLAT, defaults.equalizerPreset)
        assertEquals(listOf(0, 0, 0, 0, 0), defaults.equalizerBandLevelsMb)
        assertEquals(0, defaults.bassBoostStrength)
    }

    @Test
    fun `equalizer presets expose 5 band curves and safe bass boost bounds`() {
        com.example.domain.model.EqualizerPreset.entries.forEach { preset ->
            assertEquals("Expected 5 bands for ${preset.name}", 5, preset.defaultBandsMb.size)
            assertTrue(preset.defaultBassStrength in 0..1000)
        }
        assertTrue(com.example.domain.model.EqualizerPreset.BASS_BOOST.defaultBassStrength > 0)
        assertTrue(com.example.domain.model.EqualizerPreset.BASS_BOOST.defaultBandsMb.first() > 0)
    }

    @Test
    fun `home ui state defaults playlist end item to 50`() {
        val state = com.example.ui.screens.home.HomeUiState()
        assertEquals("1", state.playlistStartIndex)
        assertEquals("50", state.playlistEndIndex)
    }

    @Test
    fun `youtube stream spec builds and parses video, height, legacy, and audio uris`() {
        val video1080 = com.example.data.network.YouTubeStreamSpec.buildVideoUri("dQw4w9WgXcQ", 1080)
        assertEquals("yt-dlp://youtube/dQw4w9WgXcQ?height=1080", video1080)
        val parsed1080 = com.example.data.network.YouTubeStreamSpec.parse(video1080)
        assertEquals("dQw4w9WgXcQ", parsed1080?.videoId)
        assertFalse(parsed1080?.isAudioOnly ?: true)
        assertEquals(1080, parsed1080?.targetHeight)

        val legacyVideo = com.example.data.network.YouTubeStreamSpec.buildVideoUri("dQw4w9WgXcQ")
        assertEquals("yt-dlp://youtube/dQw4w9WgXcQ", legacyVideo)
        val parsedLegacy = com.example.data.network.YouTubeStreamSpec.parse(legacyVideo)
        assertEquals("dQw4w9WgXcQ", parsedLegacy?.videoId)
        assertFalse(parsedLegacy?.isAudioOnly ?: true)
        assertEquals(null, parsedLegacy?.targetHeight)

        val audio = com.example.data.network.YouTubeStreamSpec.buildAudioUri("dQw4w9WgXcQ")
        assertEquals("yt-dlp://youtube/audio/dQw4w9WgXcQ", audio)
        val parsedAudio = com.example.data.network.YouTubeStreamSpec.parse(audio)
        assertEquals("dQw4w9WgXcQ", parsedAudio?.videoId)
        assertTrue(parsedAudio?.isAudioOnly ?: false)
    }

    @Test
    fun `youtube extractor exposes per-resolution streams with accurate sizes and duration`() {
        val app = android.app.Application()
        val dl = com.example.data.network.YoutubeDlRuntime(app)
        val extractor = com.example.data.network.YouTubeExtractor(
            youtubeDlRuntime = dl,
            youtubeCookieProvider = com.example.data.network.YoutubeCookieProvider(app)
        )
        val formats = listOf(
            com.example.data.network.YouTubeExtractor.RawYouTubeFormat(
                ext = "m4a", protocol = "https", vcodec = "none", acodec = "mp4a.40.2",
                width = 0, height = 0, fps = 0.0, tbr = 128.0, abr = 128.0, audioChannels = 2,
                filesize = 2_000_000L, filesizeApprox = 0L
            ),
            com.example.data.network.YouTubeExtractor.RawYouTubeFormat(
                ext = "m4a", protocol = "https", vcodec = "none", acodec = "mp4a.40.2",
                width = 0, height = 0, fps = 0.0, tbr = 384.0, abr = 384.0, audioChannels = 6,
                filesize = 6_000_000L, filesizeApprox = 0L
            ),
            com.example.data.network.YouTubeExtractor.RawYouTubeFormat(
                ext = "mp4", protocol = "https", vcodec = "avc1.4d401f", acodec = "none",
                width = 1280, height = 720, fps = 30.0, tbr = 1500.0, abr = 0.0, audioChannels = 0,
                filesize = 22_000_000L, filesizeApprox = 0L
            ),
            com.example.data.network.YouTubeExtractor.RawYouTubeFormat(
                ext = "mp4", protocol = "https", vcodec = "avc1.640028", acodec = "none",
                width = 1920, height = 1080, fps = 30.0, tbr = 3000.0, abr = 0.0, audioChannels = 0,
                filesize = 45_000_000L, filesizeApprox = 0L
            ),
            com.example.data.network.YouTubeExtractor.RawYouTubeFormat(
                ext = "webm", protocol = "https", vcodec = "vp9", acodec = "none",
                width = 3840, height = 2160, fps = 60.0, tbr = 18000.0, abr = 0.0, audioChannels = 0,
                filesize = 300_000_000L, filesizeApprox = 0L
            )
        )

        val streams = extractor.buildStreamOptions("dQw4w9WgXcQ", formats, durationSeconds = 120.5)
        assertEquals(4, streams.size)

        val p2160 = streams[0]
        val p1080 = streams[1]
        val p720 = streams[2]
        val mp3 = streams[3]

        assertEquals(2160, p2160.resolutionHeight)
        assertTrue(p2160.isHighestQuality)
        assertEquals(302_000_000L, p2160.estimatedBytes)

        assertEquals(1080, p1080.resolutionHeight)
        assertEquals(47_000_000L, p1080.estimatedBytes)
        assertEquals("yt-dlp://youtube/dQw4w9WgXcQ?height=1080", p1080.streamUrl)

        assertEquals(720, p720.resolutionHeight)
        assertEquals(24_000_000L, p720.estimatedBytes)

        assertTrue(mp3.isAudioOnly)
        assertEquals(2_000_000L, mp3.estimatedBytes)
    }

    @Test
    fun `yt-dlp tracker ignores stale callbackPercent on track2 destination and non-download lines`() {
        val tracker = YtDlpProgressTracker(expectedBytes = 90L * 1024 * 1024)
        tracker.update(-1f, 0L, "[info] dQw4w9WgXcQ: Downloading 1 format(s): 137+140")
        tracker.update(-1f, 0L, "[download] Destination: /tmp/id.f137.mp4")
        tracker.update(100f, 0L, "[download] 100% of   80.00MiB in 00:00:20 at 4.00MiB/s")

        // Stale 100f callback on [info] / Destination of Track 2 must NOT trigger finalizing or 100% on Track 2
        val betweenTracks = tracker.update(100f, 0L, "[info] Switching to audio stream")
        assertFalse(betweenTracks.finalizing)
        assertTrue(betweenTracks.percent < 98)

        val track2Dest = tracker.update(100f, 0L, "[download] Destination: /tmp/id.f140.m4a")
        assertFalse(track2Dest.finalizing)
        assertTrue(track2Dest.percent < 98)

        val track2Mid = tracker.update(50f, 1L, "[download]  50.0% of   10.00MiB at  2.00MiB/s ETA 00:02")
        assertEquals(90L * 1024 * 1024, track2Mid.totalBytes)
        assertEquals(85L * 1024 * 1024, track2Mid.bytesDownloaded)
        assertEquals(94, track2Mid.percent)
        assertFalse(track2Mid.finalizing)

        val merging = tracker.update(100f, 0L, "[Merger] Merging formats into \"/tmp/id.mp4\"")
        assertTrue(merging.finalizing)
        assertEquals(99, merging.percent)
        assertEquals(0L, merging.speedBytesPerSec)
    }

    @Test
    fun `yt-dlp tracker ignores thumbnail destinations and bloated initial estimates`() {
        val tracker = YtDlpProgressTracker(expectedBytes = 500L * 1024 * 1024)
        tracker.update(-1f, 0L, "[info] dQw4w9WgXcQ: Downloading 1 format(s): 137+140")
        tracker.update(-1f, 0L, "[download] Destination: /tmp/id.f137.mp4")
        val videoMid = tracker.update(50f, 5L, "[download]  50.0% of   40.00MiB at  4.00MiB/s ETA 00:05")
        // Bloated 500 MiB estimate must not distort progress once 40 MiB video track is observed
        assertEquals(40L * 1024 * 1024, videoMid.totalBytes)
        assertEquals(20L * 1024 * 1024, videoMid.bytesDownloaded)
        assertEquals(45, videoMid.percent)

        // Thumbnail fetch should not become an extra track
        tracker.update(-1f, 0L, "[download] Destination: /tmp/id.webp")
        val thumbLine = tracker.update(100f, 0L, "[download] 100% of 50.00KiB in 00:00:01")
        assertEquals(40L * 1024 * 1024, thumbLine.totalBytes)
    }

    @Test
    fun `dimension quality tier and youtube itag height lookup handle portrait and standard formats`() {
        assertEquals(VideoQualityTier.P1080, VideoQualityTier.fromDimensions(width = 1080, height = 1920))
        assertEquals(VideoQualityTier.P1080, VideoQualityTier.fromDimensions(width = 1920, height = 800))
        assertEquals(VideoQualityTier.P2160, VideoQualityTier.fromDimensions(width = 3840, height = 2160))
        assertEquals(1080, com.example.data.network.YouTubeStreamSpec.resolveHeightFromFormatSpec("137+140"))
        assertEquals(2160, com.example.data.network.YouTubeStreamSpec.resolveHeightFromFormatSpec("313+251"))
        assertEquals(720, com.example.data.network.YouTubeStreamSpec.resolveHeightFromFormatSpec("22"))
    }

    @Test
    fun `youtube audio metadata extractor returns clean song title and singer from track artist and description`() {
        val extractor = com.example.data.network.YouTubeAudioMetadataExtractor

        // 1. Content ID / YouTube Music track + artist present alongside noisy video title
        val ytMusic = extractor.extract(
            rawTitle = "Ed Sheeran - Shape of You (Official Music Video) [4K]",
            rawTrack = "Shape of You",
            rawArtist = "Ed Sheeran",
            uploader = "Atlantic Records"
        )
        assertEquals("Shape Of You", ytMusic.songTitle)
        assertEquals("Ed Sheeran", ytMusic.songArtist)

        // 2. Bollywood label upload with description credits and pipe-heavy video title
        val bollywoodLabel = extractor.extract(
            rawTitle = "Tum Hi Ho (Full Video) | Aashiqui 2 | Aditya Roy Kapur, Shraddha Kapoor",
            uploader = "T-Series",
            description = "Presenting the full video of Tum Hi Ho\nSong : Tum Hi Ho\nSinger : Arijit Singh\nMusic : Mithoon"
        )
        assertEquals("Tum Hi Ho", bollywoodLabel.songTitle)
        assertEquals("Arijit Singh", bollywoodLabel.songArtist)

        // 3. Auto-generated Topic upload with "Provided to YouTube by..." description
        val topicTrack = extractor.extract(
            rawTitle = "Blinding Lights",
            uploader = "The Weeknd - Topic",
            description = "Provided to YouTube by Republic Records\n\nBlinding Lights · The Weeknd\n\nAfter Hours"
        )
        assertEquals("Blinding Lights", topicTrack.songTitle)
        assertEquals("The Weeknd", topicTrack.songArtist)

        // 4. Standard "Artist - Song (Official Video) ft. Guest" without track/artist metadata
        val dashTitleWithFeat = extractor.extract(
            rawTitle = "The Chainsmokers - Closer (Lyric Video) ft. Halsey [HD]",
            uploader = "TheChainsmokersVEVO"
        )
        assertEquals("Closer", dashTitleWithFeat.songTitle)
        assertEquals("The Chainsmokers, Halsey", dashTitleWithFeat.songArtist)

        // 5. Rejects record label uploader as artist when no singer is provided
        val unknownSingerOnLabel = extractor.extract(
            rawTitle = "Instrumental Theme (Official Audio)",
            uploader = "Zee Music Company"
        )
        assertEquals("Instrumental Theme", unknownSingerOnLabel.songTitle)
        assertEquals(null, unknownSingerOnLabel.songArtist)
    }

    @Test
    fun `youtube audio metadata extractor formats title case and keeps all performing artists`() {
        val extractor = com.example.data.network.YouTubeAudioMetadataExtractor

        // Title Case normalization ("first letter capital and rest small", e.g., "I Love You")
        assertEquals("I Love You", extractor.formatAudioTitleCase("i love you"))
        assertEquals("I Love You", extractor.formatAudioTitleCase("I LOVE YOU"))
        assertEquals("I Love You", extractor.formatAudioTitleCase("i LoVe YoU"))
        assertEquals("Don't Let Me Down", extractor.formatAudioTitleCase("DON'T LET ME DOWN"))

        val allCapsTrack = extractor.extract(
            rawTitle = "I LOVE YOU (OFFICIAL VIDEO)",
            rawTrack = "I LOVE YOU",
            rawArtist = "Bodyguard"
        )
        assertEquals("I Love You", allCapsTrack.songTitle)

        // Single-line pipe-separated description credits & multiple singers without dropping after '|'
        val pipeCredits = extractor.extract(
            rawTitle = "VE KAMLEYA | Rocky Aur Rani Kii Prem Kahaani",
            uploader = "Saregama Music",
            description = "Song : VE KAMLEYA | Singers : Arijit Singh | Shreya Ghoshal | Music : Pritam"
        )
        assertEquals("Ve Kamleya", pipeCredits.songTitle)
        assertEquals("Arijit Singh, Shreya Ghoshal", pipeCredits.songArtist)

        // Multi-line singer credits (Male Singer + Female Singer + Rap)
        val multiLineSingers = extractor.extract(
            rawTitle = "DIL NE YEH KAHA HAIN DIL SE (Full Song)",
            uploader = "Tips Official",
            description = "🎤 Song : DIL NE YEH KAHA HAIN DIL SE\nMale Singer : Udit Narayan\nFemale Singer : Alka Yagnik\nRap : Kumar Sanu"
        )
        assertEquals("Dil Ne Yeh Kaha Hain Dil Se", multiLineSingers.songTitle)
        assertEquals("Udit Narayan, Alka Yagnik, Kumar Sanu", multiLineSingers.songArtist)

        // Partial rawArtist enriched by co-artists in title and "(with ...)"
        val partialRawWithTitleCoArtists = extractor.extract(
            rawTitle = "Alan Walker, Sabrina Carpenter & Farruko - ON MY WAY (with K-391)",
            rawArtist = "Alan Walker"
        )
        assertEquals("On My Way", partialRawWithTitleCoArtists.songTitle)
        assertEquals("Alan Walker, Sabrina Carpenter, Farruko, K-391", partialRawWithTitleCoArtists.songArtist)

        // Collaboration using 'x' separator and "Artist A ft. Artist B - Song"
        val collabXAndLeftFeat = extractor.extract(
            rawTitle = "AP Dhillon x Gurinder Gill ft. Shinda Kahlon - EXCUSES (Official Audio)"
        )
        assertEquals("Excuses", collabXAndLeftFeat.songTitle)
        assertEquals("AP Dhillon, Gurinder Gill, Shinda Kahlon", collabXAndLeftFeat.songArtist)

        // Pipe-separated co-artist segment recovered when overlapping partial rawArtist
        val pipeCoArtists = extractor.extract(
            rawTitle = "TERA HONE LAGA HOON | Atif Aslam & Alisha Chinai | Ajab Prem Ki Ghazab Kahani",
            rawTrack = "TERA HONE LAGA HOON",
            rawArtist = "Atif Aslam",
            uploader = "Tips Official"
        )
        assertEquals("Tera Hone Laga Hoon", pipeCoArtists.songTitle)
        assertEquals("Atif Aslam, Alisha Chinai", pipeCoArtists.songArtist)
    }


    @Test
    fun `lrc parser parses synced timestamps and tracks active playback line`() {
        val rawLrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [00:05.50] First line
            [00:12.00][00:40.250] Repeated chorus line
            [00:20.00]
            [00:25.10] Second verse
        """.trimIndent()

        val lines = com.example.domain.model.LrcParser.parse(rawLrc)
        assertEquals(4, lines.size)
        assertEquals(5_500L, lines[0].timestampMs)
        assertEquals("First line", lines[0].text)
        assertEquals(12_000L, lines[1].timestampMs)
        assertEquals("Repeated chorus line", lines[1].text)
        assertEquals(25_100L, lines[2].timestampMs)
        assertEquals("Second verse", lines[2].text)
        assertEquals(40_250L, lines[3].timestampMs)
        assertEquals("Repeated chorus line", lines[3].text)

        assertEquals(-1, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 1_000L))
        assertEquals(0, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 6_000L))
        assertEquals(1, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 12_000L))
        assertEquals(2, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 30_000L))
        assertEquals(3, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 42_000L))

        // Sync offset: delaying by -2000ms keeps line 0 active until 14s
        assertEquals(0, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 12_000L, offsetMs = -2_000L))
        // Sync offset: advancing by +2000ms activates line 2 at 23.5s
        assertEquals(2, com.example.domain.model.LrcParser.findActiveLineIndex(lines, 23_500L, offsetMs = 2_000L))
    }

    @Test
    fun `lrc parser applies offset header tag and strips enhanced inline word timestamps`() {
        val enhancedLrc = """
            [offset:+1000]
            [00:05.00] <00:05.00>Hello <00:05.40>world
            [00:10.00] Next line
        """.trimIndent()

        val lines = com.example.domain.model.LrcParser.parse(enhancedLrc)
        assertEquals(2, lines.size)
        assertEquals(4_000L, lines[0].timestampMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals(9_000L, lines[1].timestampMs)
        assertEquals("Next line", lines[1].text)
    }

    @Test
    fun `youtube runtime detects bot verification messages accurately`() {
        val runtime = com.example.data.network.YoutubeDlRuntime
        assertTrue(runtime.isBotVerificationError("ERROR: [youtube] abc: Sign in to confirm you’re not a bot."))
        assertTrue(runtime.isBotVerificationError("Sign in to confirm you're not a bot"))
        assertFalse(runtime.isBotVerificationError("Video unavailable"))
    }
}
