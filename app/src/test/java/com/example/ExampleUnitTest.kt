package com.example

import com.example.data.network.UrlSecurityValidator
import com.example.data.network.UrlValidationResult
import com.example.domain.model.DownloadError
import com.example.domain.model.VideoQualityTier
import com.example.ui.components.FormatUtils
import org.junit.Assert.assertEquals
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
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("2.5 MB/s", FormatUtils.formatSpeed((2.5 * 1024 * 1024).toLong()))
        assertEquals("2m 5s", FormatUtils.formatEta(125L))
        assertEquals("01:05", FormatUtils.formatDuration(65_000L))
    }
}
