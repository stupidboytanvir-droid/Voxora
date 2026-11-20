package com.voxora.player

import com.voxora.player.ai.tts.BengaliTtsIntegrityVerifier
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.OfflineBengaliTtsEngine
import com.voxora.player.ai.tts.OneClickAiSetupState
import com.voxora.player.ai.tts.WavAudioProcessor
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DubbingPipelineStage
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import com.voxora.player.dubbing.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BengaliTtsPhase5Test {

    @Test
    fun `wav audio processor writes valid RIFF WAVE header and pitch resampling preserves male female distinction`() {
        val sampleRate = 22050
        val samples = FloatArray(sampleRate) { idx ->
            kotlin.math.sin(2.0 * Math.PI * 220.0 * idx / sampleRate).toFloat() * 0.5f
        }

        // Male effective pitch (< 1.0) produces lower pitch / longer resampled array
        val maleProfile = CharacterVoiceProfile("SPEAKER_01", "Male Lead", "bn_male_1", VoiceGender.MALE, pitchFactor = 1.0f)
        val femaleProfile = CharacterVoiceProfile("SPEAKER_02", "Female Lead", "bn_female_1", VoiceGender.FEMALE, pitchFactor = 1.0f)

        val malePitch = OfflineBengaliTtsEngine.computeEffectivePitch(maleProfile)
        val femalePitch = OfflineBengaliTtsEngine.computeEffectivePitch(femaleProfile)
        assertTrue(malePitch < femalePitch)

        val resampledMale = WavAudioProcessor.resampleForPitch(samples, malePitch)
        assertTrue(resampledMale.size > samples.size)

        val tempDir = File(System.getProperty("java.io.tmpdir"), "voxora_wav_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val wavFile = File(tempDir, "seg_0.wav")
            val durationMs = WavAudioProcessor.writeMono16BitWav(samples, sampleRate, wavFile)
            assertEquals(1000L, durationMs)
            assertEquals(44L + samples.size * 2L, wavFile.length())

            val header = wavFile.readBytes().copyOfRange(0, 44)
            assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
            val parsedRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(sampleRate, parsedRate)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `timing speed adaptation gently accelerates only when Bengali speech overflows window`() {
        // Within window -> no speed adjustment
        assertNull(
            WavAudioProcessor.computeTimingAdjustedSpeed(
                currentSpeed = 1.0f,
                generatedDurationMs = 2_600L,
                targetWindowMs = 2_500L
            )
        )

        // Significantly longer than window (4.0s vs 2.5s = 1.6x) -> bounded acceleration
        val faster = WavAudioProcessor.computeTimingAdjustedSpeed(
            currentSpeed = 1.0f,
            generatedDurationMs = 4_000L,
            targetWindowMs = 2_500L
        )
        assertNotNull(faster)
        assertTrue(faster!! in 1.25f..1.45f)

        // Compensated Sherpa speed cancels pitch resampling duration drift
        val comp = WavAudioProcessor.computeCompensatedSherpaSpeed(desiredSpeed = 1.1f, pitchFactor = 0.86f)
        assertEquals(1.1f * 0.86f, comp, 0.001f)
    }

    @Test
    fun `tokens integrity verifier accepts valid Bengali token table and rejects HTML error pages`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "voxora_tokens_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val htmlFile = File(tempDir, "bad_tokens.txt").apply {
                writeText("<html><body>502 Bad Gateway</body></html>" + " ".repeat(200))
            }
            val (htmlOk, _) = BengaliTtsIntegrityVerifier.verifyTokensFile(htmlFile)
            assertFalse(htmlOk)

            val goodTokens = File(tempDir, "tokens.txt").apply {
                writeText(
                    buildString {
                        appendLine("<PAD> 0")
                        appendLine("। 1")
                        appendLine("<BOS> 2")
                        appendLine("<BLNK> 3")
                        appendLine("অ 7")
                        appendLine("আ 8")
                        appendLine("ই 9")
                        appendLine("ক 18")
                        appendLine("খ 19")
                        appendLine("গ 20")
                        appendLine("ন 37")
                        appendLine("ম 42")
                    }
                )
            }
            val (goodOk, reason) = BengaliTtsIntegrityVerifier.verifyTokensFile(goodTokens)
            assertTrue(goodOk)
            assertNull(reason)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `one click setup state tracks missing model counts and overall progress accurately`() {
        val partial = OneClickAiSetupState(
            isWhisperReady = true,
            isDiarizationReady = false,
            isTranslationReady = true,
            isBengaliTtsReady = false,
            isRunning = true,
            currentStepIndex = 2,
            totalSteps = 2,
            currentStepLabel = "Bengali TTS",
            currentStepProgressPercent = 50
        )
        assertEquals(2, partial.readyModelCount)
        assertEquals(2, partial.missingModelCount)
        assertFalse(partial.allModelsReady)
        assertEquals(0.75f, partial.overallProgressFraction, 0.001f)

        val allReady = OneClickAiSetupState(
            isWhisperReady = true,
            isDiarizationReady = true,
            isTranslationReady = true,
            isBengaliTtsReady = true
        )
        assertTrue(allReady.allModelsReady)
        assertEquals(0, allReady.missingModelCount)
        assertEquals(4, allReady.readyModelCount)
    }

    @Test
    fun `pipeline registry honestly reflects real Bengali VITS TTS model states`() {
        val pipeline = Version1AiDubbingPipeline()

        val notInstalled = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            bengaliTtsModelState = BengaliTtsModelState.NotInstalled
        ).first { it.stage == DubbingPipelineStage.BENGALI_SPEECH_GENERATION }
        assertEquals(ComponentReadiness.REQUIRES_AI_ENGINE, notInstalled.readiness)
        assertTrue(notInstalled.statusDetail.contains("Model Required", ignoreCase = true))

        val downloading = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            bengaliTtsModelState = BengaliTtsModelState.Downloading(60, "Downloading VITS...")
        ).first { it.stage == DubbingPipelineStage.BENGALI_SPEECH_GENERATION }
        assertEquals(ComponentReadiness.RUNNING, downloading.readiness)

        val ready = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            bengaliTtsModelState = BengaliTtsModelState.Ready
        ).first { it.stage == DubbingPipelineStage.BENGALI_SPEECH_GENERATION }
        assertEquals(ComponentReadiness.READY, ready.readiness)

        val synthesizing = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            bengaliTtsModelState = BengaliTtsModelState.Ready,
            isSynthesizingBengaliVoice = true
        ).first { it.stage == DubbingPipelineStage.BENGALI_SPEECH_GENERATION }
        assertEquals(ComponentReadiness.RUNNING, synthesizing.readiness)

        val error = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            bengaliTtsModelState = BengaliTtsModelState.Error("Storage full")
        ).first { it.stage == DubbingPipelineStage.BENGALI_SPEECH_GENERATION }
        assertEquals(ComponentReadiness.ERROR, error.readiness)
        assertEquals("Storage full", error.statusDetail)
    }
}
