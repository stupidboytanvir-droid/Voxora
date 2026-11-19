package com.voxora.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voxora.player.data.local.db.VoxoraDatabase
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.dubbing.DubbingCapabilityStatus
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = VoxoraApplication::class)
class VoxoraRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: VoxoraDatabase
    private lateinit var historyRepository: PlaybackHistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VoxoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepository = PlaybackHistoryRepository(database.playbackHistoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `app identity and version 1 AI dubbing status are accurate`() {
        assertEquals("VOXORA", context.getString(R.string.app_name))
        assertEquals("Play Beyond Language", context.getString(R.string.app_tagline))

        val pipeline = Version1AiDubbingPipeline()
        val status = pipeline.getCapabilityStatus()
        assertTrue(status is DubbingCapabilityStatus.ComingSoon)
        assertTrue(pipeline.getSupportedTargetLanguages().isNotEmpty())
    }

    @Test
    fun `playback history saves resume position and supports clear`() = runTest {
        val video = VideoItem(
            id = 42L,
            uriString = "content://media/external/video/media/42",
            title = "Sample Cinema",
            displayName = "Sample Cinema.mp4",
            durationMs = 120_000L,
            sizeBytes = 25_000_000L,
            dateModifiedSec = 1_700_000_000L,
            folderId = "movies",
            folderName = "Movies",
            relativePath = "Movies"
        )

        historyRepository.recordOrUpdateProgress(
            video = video,
            positionMs = 45_000L,
            durationMs = 120_000L,
            speed = 1.25f
        )

        val recent = historyRepository.recentlyPlayed.first()
        val continueWatching = historyRepository.continueWatching.first()

        assertEquals(1, recent.size)
        assertEquals(45_000L, recent.first().lastPositionMs)
        assertEquals(1.25f, recent.first().preferredSpeed)
        assertEquals(1, continueWatching.size)

        historyRepository.clearAllHistory()
        assertTrue(historyRepository.recentlyPlayed.first().isEmpty())
    }
}
