package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.DownloadEntity
import com.example.domain.model.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("StreamVault", appName)
    }

    @Test
    fun `room database persists download progress and completion`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.downloadDao()

        val entity = DownloadEntity(
            id = "dl-1",
            sourceUrl = "https://example.org/sample.mp4",
            normalizedUrl = "https://example.org/sample.mp4",
            streamUrl = "https://example.org/sample.mp4",
            title = "Sample Video",
            thumbnailUrl = null,
            durationMs = 12_000L,
            resolutionLabel = "1080p Full HD",
            resolutionHeight = 1080,
            format = "MP4",
            mimeType = "video/mp4",
            isAudioOnly = false,
            status = DownloadStatus.DOWNLOADING.name,
            bytesDownloaded = 0L,
            totalBytes = 10_000L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            supportsRange = true,
            eTag = "\"abc\"",
            localUri = null,
            localPath = null,
            fileName = "Sample Video.mp4",
            sha256 = null,
            errorMessage = null,
            workRequestId = null,
            createdAt = 1000L,
            completedAt = null
        )

        dao.insert(entity)
        dao.updateProgress("dl-1", 5_000L, 10_000L, 2_500L, 2L, DownloadStatus.DOWNLOADING.name)
        val active = dao.observeActiveAndQueued().first()
        assertEquals(1, active.size)
        assertEquals(5_000L, active.first().bytesDownloaded)

        dao.markCompleted(
            id = "dl-1",
            finalBytes = 10_000L,
            localUri = "content://downloads/1",
            localPath = "Download/StreamVault/Sample Video.mp4",
            fileName = "Sample Video.mp4",
            sha256 = "deadbeef",
            resolutionLabel = "1080p Full HD",
            resolutionHeight = 1080,
            durationMs = 12_000L,
            completedAt = 2000L
        )

        val completed = dao.observeCompleted().first()
        assertEquals(1, completed.size)
        assertNotNull(dao.findDuplicateCompleted("https://example.org/sample.mp4", "https://example.org/sample.mp4"))
        db.close()
    }

    @Test
    fun `home screen renders controls and navigates across all four tabs`() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("url_input_field").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("paste_link_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("analyze_video_button").performScrollTo().assertIsDisplayed()

        // Verify invalid URL error feedback
        composeRule.onNodeWithTag("url_input_field").performTextInput("https://127.0.0.1/test.mp4")
        composeRule.onNodeWithTag("analyze_video_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("Local network and private IP addresses are not allowed.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Local network and private IP addresses are not allowed.")
            .performScrollTo()
            .assertIsDisplayed()

        // Navigate across Downloads, History, Settings, and back to Home
        composeRule.onNodeWithTag("nav_downloads").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No Active Downloads").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_history").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_search_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_default_quality").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("url_input_field").performScrollTo().assertIsDisplayed()
    }
}
