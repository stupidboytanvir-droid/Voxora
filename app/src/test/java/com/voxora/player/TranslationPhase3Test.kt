package com.voxora.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.ai.translation.TranslationExportFormat
import com.voxora.player.ai.translation.TranslationExporter
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.data.local.db.VoxoraDatabase
import com.voxora.player.data.repository.TranslationCacheRepository
import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DubbingPipelineStage
import com.voxora.player.dubbing.Version1AiDubbingPipeline
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
class TranslationPhase3Test {

    private lateinit var database: VoxoraDatabase
    private lateinit var cacheRepository: TranslationCacheRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VoxoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheRepository = TranslationCacheRepository(database.translationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `room translation cache saves retrieves replaces and clears segments by videoUri`() = runTest {
        val videoUri = "content://media/external/video/media/777"
        val segments = listOf(
            TranslatedSegment(
                index = 0,
                startMs = 1_000L,
                endMs = 3_500L,
                originalText = "Good morning, everyone.",
                translatedText = "সবাইকে শুভ সকাল।",
                characterId = "CHAR_001"
            ),
            TranslatedSegment(
                index = 1,
                startMs = 4_000L,
                endMs = 6_200L,
                originalText = "Let us begin.",
                translatedText = "চলো শুরু করি।",
                characterId = null
            )
        )

        cacheRepository.saveTranslation(videoUri, segments)
        val loaded = cacheRepository.getCachedTranslation(videoUri)

        assertEquals(2, loaded.size)
        assertEquals(0, loaded[0].index)
        assertEquals("সবাইকে শুভ সকাল।", loaded[0].translatedText)
        assertEquals("CHAR_001", loaded[0].characterId)
        assertEquals(1, loaded[1].index)
        assertEquals("চলো শুরু করি।", loaded[1].translatedText)

        // Replace with single updated segment
        cacheRepository.saveTranslation(videoUri, listOf(segments[0]))
        assertEquals(1, cacheRepository.getCachedTranslation(videoUri).size)

        // Clear cache
        cacheRepository.clearTranslationForVideo(videoUri)
        assertTrue(cacheRepository.getCachedTranslation(videoUri).isEmpty())
    }

    @Test
    fun `translation exporter generates valid Bengali SRT, Bilingual SRT, TXT, and JSON`() {
        val segments = listOf(
            TranslatedSegment(
                index = 0,
                startMs = 1_250L,
                endMs = 4_500L,
                originalText = "Hello world",
                translatedText = "হ্যালো বিশ্ব"
            )
        )

        val bnSrt = TranslationExporter.format(TranslationExportFormat.BENGALI_SRT, "Demo", segments)
        assertTrue(bnSrt.contains("00:00:01,250 --> 00:00:04,500"))
        assertTrue(bnSrt.contains("হ্যালো বিশ্ব"))
        assertTrue(!bnSrt.contains("Hello world"))

        val biSrt = TranslationExporter.format(TranslationExportFormat.BILINGUAL_SRT, "Demo", segments)
        assertTrue(biSrt.contains("Hello world"))
        assertTrue(biSrt.contains("হ্যালো বিশ্ব"))

        val txt = TranslationExporter.format(TranslationExportFormat.TXT, "Demo", segments)
        assertTrue(txt.contains("EN: Hello world"))
        assertTrue(txt.contains("বাংলা: হ্যালো বিশ্ব"))

        val json = TranslationExporter.format(TranslationExportFormat.JSON, "Demo", segments)
        assertTrue(json.contains("\"sourceLanguage\": \"en\""))
        assertTrue(json.contains("\"targetLanguage\": \"bn\""))
        assertTrue(json.contains("হ্যালো বিশ্ব"))
    }

    @Test
    fun `pipeline registry honestly reflects real ML Kit translation model states`() {
        val pipeline = Version1AiDubbingPipeline()

        val notDownloaded = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            translationModelState = TranslationModelState.NotDownloaded
        ).first { it.stage == DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION }
        assertEquals(ComponentReadiness.REQUIRES_AI_ENGINE, notDownloaded.readiness)
        assertTrue(notDownloaded.statusDetail.contains("Model Required", ignoreCase = true))

        val downloading = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            translationModelState = TranslationModelState.Downloading
        ).first { it.stage == DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION }
        assertEquals(ComponentReadiness.RUNNING, downloading.readiness)

        val ready = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            translationModelState = TranslationModelState.Ready
        ).first { it.stage == DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION }
        assertEquals(ComponentReadiness.READY, ready.readiness)

        val translating = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            translationModelState = TranslationModelState.Ready,
            isTranslating = true
        ).first { it.stage == DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION }
        assertEquals(ComponentReadiness.RUNNING, translating.readiness)

        val error = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            translationModelState = TranslationModelState.Error("Offline storage full")
        ).first { it.stage == DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION }
        assertEquals(ComponentReadiness.ERROR, error.readiness)
        assertEquals("Offline storage full", error.statusDetail)
    }
}
