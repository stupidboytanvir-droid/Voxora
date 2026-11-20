package com.voxora.player

import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.diarization.OnnxModelIntegrityVerifier
import com.voxora.player.ai.diarization.SpeakerCharacterMapper
import com.voxora.player.ai.diarization.SpeakerSegment
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DubbingPipelineStage
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.RecognizedSpeechSegment
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiarizationPhase4Test {

    @Test
    fun `speaker alignment selects maximum overlap and falls back cleanly within tolerance`() {
        val speakerTurns = listOf(
            SpeakerSegment(startMs = 0L, endMs = 4_000L, speakerIndex = 0),
            SpeakerSegment(startMs = 4_200L, endMs = 9_000L, speakerIndex = 1),
            SpeakerSegment(startMs = 12_000L, endMs = 15_000L, speakerIndex = 2)
        )

        // Overlaps mostly with speaker 0 (3000ms vs 800ms with speaker 1)
        assertEquals(
            0,
            SpeakerCharacterMapper.findBestSpeakerIndexForInterval(1_000L, 5_000L, speakerTurns)
        )

        // Overlaps strictly with speaker 1
        assertEquals(
            1,
            SpeakerCharacterMapper.findBestSpeakerIndexForInterval(5_000L, 8_000L, speakerTurns)
        )

        // Small boundary gap (300ms after speaker 1 ends) falls back to nearest speaker 1
        assertEquals(
            1,
            SpeakerCharacterMapper.findBestSpeakerIndexForInterval(9_100L, 9_500L, speakerTurns)
        )

        // Far outside tolerance returns null (never random)
        assertEquals(
            null,
            SpeakerCharacterMapper.findBestSpeakerIndexForInterval(30_000L, 32_000L, speakerTurns)
        )
    }

    @Test
    fun `auto maps speakers to existing characters and auto creates Character 3 and Character 4`() {
        val defaultCharacters = DubbingVoiceCatalog.defaultCharacters()
        assertEquals(2, defaultCharacters.size)

        val detectedTurns = listOf(
            SpeakerSegment(startMs = 0L, endMs = 3_000L, speakerIndex = 0),
            SpeakerSegment(startMs = 3_100L, endMs = 6_000L, speakerIndex = 1),
            SpeakerSegment(startMs = 6_100L, endMs = 9_000L, speakerIndex = 2),
            SpeakerSegment(startMs = 9_100L, endMs = 12_000L, speakerIndex = 3)
        )

        val dialogues = listOf(
            500L to 2_500L,
            3_500L to 5_500L,
            6_500L to 8_500L,
            9_500L to 11_500L
        )

        val result = SpeakerCharacterMapper.mapSpeakersToCharacters(
            speakerSegments = detectedTurns,
            dialogueIntervals = dialogues,
            savedCharacters = defaultCharacters,
            existingMovieAssignments = emptyList()
        )

        // 2 default characters + 2 auto-created characters = 4 total
        assertEquals(4, result.updatedCharacters.size)
        assertEquals(2, result.newlyCreatedCharacterIds.size)
        assertEquals("Character 3", result.updatedCharacters[2].label)
        assertEquals("Character 4", result.updatedCharacters[3].label)

        // All 4 speakers mapped distinctly and aligned to the 4 dialogues
        assertEquals(4, result.speakerToCharacterMap.size)
        assertEquals(4, result.speakerToCharacterMap.values.distinct().size)
        assertEquals(result.speakerToCharacterMap[0], result.segmentIndexToCharacterId[0])
        assertEquals(result.speakerToCharacterMap[1], result.segmentIndexToCharacterId[1])
        assertEquals(result.speakerToCharacterMap[2], result.segmentIndexToCharacterId[2])
        assertEquals(result.speakerToCharacterMap[3], result.segmentIndexToCharacterId[3])
    }

    @Test
    fun `manual segment overrides and movie locks are preserved during re-mapping`() {
        val characters = DubbingVoiceCatalog.defaultCharacters()
        val charA = characters[0]
        val charB = characters[1]

        // Lock SPEAKER_01 explicitly to charB
        val existingLocks = listOf(
            MovieSpeakerAssignment(
                diarizationSpeakerId = "SPEAKER_01",
                characterId = charB.characterId,
                lockedVoiceId = charB.voiceId,
                isAutoAssigned = false
            )
        )

        val turns = listOf(
            SpeakerSegment(startMs = 0L, endMs = 4_000L, speakerIndex = 0),
            SpeakerSegment(startMs = 4_100L, endMs = 8_000L, speakerIndex = 1)
        )
        val dialogues = listOf(
            500L to 3_000L,
            4_500L to 7_000L
        )

        // User manually overrode line 0 to charA
        val result = SpeakerCharacterMapper.mapSpeakersToCharacters(
            speakerSegments = turns,
            dialogueIntervals = dialogues,
            savedCharacters = characters,
            existingMovieAssignments = existingLocks,
            existingManualOverrides = mapOf(0 to charA.characterId)
        )

        assertEquals(charB.characterId, result.speakerToCharacterMap[0])
        assertEquals(charA.characterId, result.speakerToCharacterMap[1])
        // Line 0 keeps manual override charA
        assertEquals(charA.characterId, result.segmentIndexToCharacterId[0])
        assertEquals(charA.characterId, result.segmentIndexToCharacterId[1])

        // Applying onto TranslatedSegment and RecognizedSpeechSegment updates character/speaker IDs
        val translated = listOf(
            TranslatedSegment(0, 500L, 3_000L, "Hello", "হ্যালো", characterId = null),
            TranslatedSegment(1, 4_500L, 7_000L, "Hi", "হাই", characterId = null)
        )
        val updatedTranslated = SpeakerCharacterMapper.applyCharactersToTranslatedSegments(
            translated,
            result.segmentIndexToCharacterId
        )
        assertEquals(charA.characterId, updatedTranslated[0].characterId)
        assertEquals(charA.characterId, updatedTranslated[1].characterId)

        val transcripts = listOf(
            RecognizedSpeechSegment("s0", 500L, 3_000L, "Hello", null, 0.9f),
            RecognizedSpeechSegment("s1", 4_500L, 7_000L, "Hi", null, 0.9f)
        )
        val updatedTranscripts = SpeakerCharacterMapper.applyCharactersToTranscriptSegments(
            transcripts,
            result.segmentIndexToCharacterId
        )
        assertEquals(charA.characterId, updatedTranscripts[0].speakerId)
        assertEquals(charA.characterId, updatedTranscripts[1].speakerId)
    }

    @Test
    fun `onnx integrity verifier rejects HTML error pages and tiny files while accepting valid protobuf header`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "voxora_onnx_test_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val htmlFile = File(tempDir, "error.onnx").apply {
                writeText("<html><body>404 Not Found</body></html>" + " ".repeat(2048))
            }
            val (htmlOk, htmlReason) = OnnxModelIntegrityVerifier.verifyOnnxFile(htmlFile, minBytes = 1024L)
            assertFalse(htmlOk)
            assertNotNull(htmlReason)

            val tinyFile = File(tempDir, "tiny.onnx").apply {
                writeBytes(byteArrayOf(0x08, 0x07, 0x12, 0x04))
            }
            val (tinyOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(tinyFile, minBytes = 1024L)
            assertFalse(tinyOk)

            // Valid ONNX ModelProto starts with ir_version field 1 varint tag 0x08 (wire type 0)
            val validFile = File(tempDir, "valid.onnx").apply {
                val bytes = ByteArray(2048)
                bytes[0] = 0x08
                bytes[1] = 0x07
                bytes[2] = 0x12
                bytes[3] = 0x07
                writeBytes(bytes)
            }
            val (validOk, validReason) = OnnxModelIntegrityVerifier.verifyOnnxFile(validFile, minBytes = 1024L)
            assertTrue(validOk)
            assertEquals(null, validReason)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `pipeline registry honestly reflects Sherpa-ONNX speaker diarization states`() {
        val pipeline = Version1AiDubbingPipeline()

        val notInstalled = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            diarizationModelState = DiarizationModelState.NotInstalled
        ).first { it.stage == DubbingPipelineStage.SPEAKER_DIARIZATION }
        assertEquals(ComponentReadiness.REQUIRES_AI_ENGINE, notInstalled.readiness)
        assertTrue(notInstalled.statusDetail.contains("Model Required", ignoreCase = true))

        val downloading = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            diarizationModelState = DiarizationModelState.Downloading(45, "Downloading model...")
        ).first { it.stage == DubbingPipelineStage.SPEAKER_DIARIZATION }
        assertEquals(ComponentReadiness.RUNNING, downloading.readiness)

        val ready = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            diarizationModelState = DiarizationModelState.Ready
        ).first { it.stage == DubbingPipelineStage.SPEAKER_DIARIZATION }
        assertEquals(ComponentReadiness.READY, ready.readiness)

        val running = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            diarizationModelState = DiarizationModelState.Ready,
            isDiarizing = true
        ).first { it.stage == DubbingPipelineStage.SPEAKER_DIARIZATION }
        assertEquals(ComponentReadiness.RUNNING, running.readiness)

        val error = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            diarizationModelState = DiarizationModelState.Error("Checksum mismatch")
        ).first { it.stage == DubbingPipelineStage.SPEAKER_DIARIZATION }
        assertEquals(ComponentReadiness.ERROR, error.readiness)
        assertEquals("Checksum mismatch", error.statusDetail)
    }
}
