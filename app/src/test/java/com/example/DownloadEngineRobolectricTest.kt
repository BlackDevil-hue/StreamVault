package com.example

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.DirectMediaExtractor
import com.example.data.network.UrlSecurityValidator
import com.example.data.preferences.UserPreferencesDataStore
import com.example.data.storage.StorageManager
import com.example.domain.model.AppSettings
import com.example.domain.source.ExtractionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadEngineRobolectricTest {

    @Test
    fun `direct media extractor inspects headers and detects range resume support`() = runTest {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "video/mp4")
                    .header("Content-Length", "5242880")
                    .header("Accept-Ranges", "bytes")
                    .header("ETag", "\"v1\"")
                    .body("".toResponseBody("video/mp4".toMediaType()))
                    .build()
            })
            .build()

        val extractor = DirectMediaExtractor(fakeClient)
        val url = "https://cdn.example.org/samples/Ocean_Wave_1080p.mp4"
        assertTrue(extractor.canHandle(url))

        val outcome = extractor.extract(url)
        assertTrue(outcome is ExtractionOutcome.Success)
        val result = (outcome as ExtractionOutcome.Success).result
        assertEquals("Ocean Wave 1080p", result.title)
        assertEquals(1, result.streams.size)
        val stream = result.streams.first()
        assertEquals("1080p Full HD", stream.qualityLabel)
        assertEquals("MP4", stream.format)
        assertEquals(5242880L, stream.estimatedBytes)
        assertTrue(stream.supportsRange)
        assertTrue(stream.isHighestQuality)
    }

    @Test
    fun `storage manager commits file computes sha256 and deletes cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)
        val staging = storageManager.getStagingFile("test-dl-1")
        staging.writeBytes("sample-video-payload-bytes".toByteArray())

        val committed = storageManager.commitDownloadedFile(
            stagingFile = staging,
            fileName = "Sample_Test_720p.mp4",
            mimeType = "video/mp4",
            resolutionLabel = "720p HD",
            resolutionHeight = 720,
            isAudioOnly = false,
            settings = AppSettings()
        )

        assertFalse(staging.exists())
        assertTrue(committed.finalBytes > 0L)
        assertEquals(64, committed.sha256.length)
        assertTrue(storageManager.fileExists(committed.uriString, committed.displayPath))

        val deleted = storageManager.deleteMediaFile(committed.uriString, committed.displayPath)
        assertTrue(deleted)
    }

    @Test
    fun `preferences datastore persists settings updates`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        val prefs = UserPreferencesDataStore(ds)
        prefs.setMaxConcurrentDownloads(5)
        prefs.setWifiOnly(true)
        val current = prefs.getCurrent()
        assertEquals(5, current.maxConcurrentDownloads)
        assertTrue(current.wifiOnly)
        testFile.delete()
    }
}
