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
}
