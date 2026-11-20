package com.voxora.player

import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.dubbing.AutoDubPipelineOrchestrator
import com.voxora.player.ai.dubbing.AutoDubPipelineState
import com.voxora.player.ai.dubbing.AutoDubStage
import com.voxora.player.ai.dubbing.BackgroundAudioPreserver
import com.voxora.player.ai.dubbing.DialogueDuckingWindow
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.ai.tts.AndroidSystemBengaliTtsEngine
import com.voxora.player.ai.tts.AudioProsodyAnalyzer
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.ai.tts.EdgeNeuralTtsEngine
import com.voxora.player.ai.tts.SegmentEnergyLevel
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingSessionConfig
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import com.voxora.player.dubbing.VoiceGender
import com.voxora.player.dubbing.whisper.WhisperModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure JVM unit tests for VOXORA Phase 6 (v2.0.0):
 * - Smart Audio Ducking 150ms raised-cosine fade-in / fade-out & BGM restore
 * - Mid-Side Stereo Vocal Attenuation preserving stereo BGM/SFX
 * - Dialogue window building with syncOffsetMs & merging
 * - WAV header encode/decode round-trip
 * - 7/7 AI Pipeline stage readiness (zero Pending stages left)
 */
class DubbingPhase6Test {

    @Test
    fun smartAudioDucking_smoothlyRampsDownAndRestoresBgm() {
        val windows = listOf(DialogueDuckingWindow(startMs = 1_000L, endMs = 3_000L))
        val strength = 0.80f // floor = 0.20f

        // Far before dialogue -> 100% original BGM
        assertEquals(1.0f, BackgroundAudioPreserver.computeDuckingGain(500L, windows, strength, 150L), 0.001f)

        // Midpoint of 150ms fade-in ramp (925ms) -> halfway (~0.60f)
        val fadeInMid = BackgroundAudioPreserver.computeDuckingGain(925L, windows, strength, 150L)
        assertTrue("Expected smooth midpoint ducking gain around 0.60, got $fadeInMid", fadeInMid in 0.55f..0.65f)

        // Inside dialogue window -> 0.20f floor
        assertEquals(0.20f, BackgroundAudioPreserver.computeDuckingGain(2_000L, windows, strength, 150L), 0.001f)

        // Midpoint of 150ms fade-out ramp (3075ms) -> halfway (~0.60f)
        val fadeOutMid = BackgroundAudioPreserver.computeDuckingGain(3_075L, windows, strength, 150L)
        assertTrue("Expected smooth fade-out gain around 0.60, got $fadeOutMid", fadeOutMid in 0.55f..0.65f)

        // After fade-out completes -> 100% restored BGM
        assertEquals(1.0f, BackgroundAudioPreserver.computeDuckingGain(3_300L, windows, strength, 150L), 0.001f)
    }

    @Test
    fun midSideVocalAttenuation_reducesCenterMonoWhilePreservingStereoSide() {
        // Pure center-panned mono English dialogue (L == R)
        val (monoL, monoR) = BackgroundAudioPreserver.applyMidSideVocalAttenuation(
            left = 0.8f,
            right = 0.8f,
            attenuationAmount = 0.75f
        )
        assertEquals(0.2f, monoL, 0.001f)
        assertEquals(0.2f, monoR, 0.001f)

        // Pure stereo Side signal (L == -R) representing stereo ambience / wide BGM
        val (sideL, sideR) = BackgroundAudioPreserver.applyMidSideVocalAttenuation(
            left = 0.6f,
            right = -0.6f,
            attenuationAmount = 0.75f
        )
        assertEquals(0.6f, sideL, 0.001f)
        assertEquals(-0.6f, sideR, 0.001f)
    }

    @Test
    fun buildDuckingWindows_shiftsBySyncOffsetAndMergesAdjacentLines() {
        val segs = listOf(
            DubbedAudioSegment(0, 1000L, 2000L, "CHAR_001", "/tmp/a.wav", 1200L, "হ্যালো"),
            DubbedAudioSegment(1, 2250L, 3200L, "CHAR_002", "/tmp/b.wav", 1000L, "কেমন আছো"),
            DubbedAudioSegment(2, 6000L, 7000L, "CHAR_001", "/tmp/c.wav", 900L, "ভালো")
        )
        val windows = BackgroundAudioPreserver.buildDuckingWindows(segs, syncOffsetMs = 200L, mergeGapMs = 120L)
        // Seg 0 shifted: 1200..2400; Seg 1 shifted: 2450..3450 -> gap 50ms <= 120ms -> merged into 1200..3450
        assertEquals(2, windows.size)
        assertEquals(1200L, windows[0].startMs)
        assertEquals(3450L, windows[0].endMs)
        assertEquals(6200L, windows[1].startMs)
        assertEquals(7200L, windows[1].endMs)
    }

    @Test
    fun wavHeaderBuildAndParse_roundTripMatchesExactMetadata() {
        val header = DubbedTrackAssembler.buildWavHeaderBytes(
            dataBytes = 88_200L,
            sampleRate = 44_100,
            channels = 2
        )
        val parsed = DubbedTrackAssembler.parseWavHeaderBytes(header, fileLength = 44 + 88_200)
        assertNotNull(parsed)
        assertEquals(44_100, parsed!!.sampleRate)
        assertEquals(2, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertEquals(44, parsed.dataOffset)
        assertEquals(88_200, parsed.dataSize)
        assertEquals(22_050L, parsed.totalFrames)
    }

    @Test
    fun autoDubPipelineState_overallProgressAdvancesCleanlyAcross4Stages() {
        val stage3Mid = AutoDubPipelineState(
            isRunning = true,
            currentStage = AutoDubStage.TRANSLATING,
            stageIndex = 3,
            totalStages = 4,
            stageProgressFraction = 0.5f
        )
        assertEquals(0.625f, stage3Mid.overallProgressFraction, 0.001f)

        val done = AutoDubPipelineState(
            isRunning = false,
            currentStage = AutoDubStage.COMPLETED,
            stageIndex = 4,
            totalStages = 4,
            stageProgressFraction = 1f
        )
        assertEquals(1.0f, done.overallProgressFraction, 0.001f)
    }

    @Test
    fun pipelineRegistry_reportsAll7Of7StagesReadyWhenModelsInstalled() {
        val pipeline = Version1AiDubbingPipeline()
        val stages = pipeline.buildStageStatuses(
            extractedAudioInfo = null,
            isExtractingAudio = false,
            isWhisperModelLoaded = true,
            isTranscribing = false,
            diarizationModelState = DiarizationModelState.Ready,
            isDiarizing = false,
            translationModelState = TranslationModelState.Ready,
            isTranslating = false,
            bengaliTtsModelState = BengaliTtsModelState.Ready,
            isSynthesizingBengaliVoice = false,
            isLiveDubbingActive = false,
            preserveBackgroundAudio = true,
            bgmDuckingStrength = 0.80f,
            vocalAttenuationEnabled = true
        )
        assertEquals(7, stages.size)
        val readyCount = stages.count { it.readiness == ComponentReadiness.READY }
        assertEquals("Expected all 7/7 stages READY in v2.0.0", 7, readyCount)
    }

    @Test
    fun v210_fastMovieModeDefaultsOn_andWhisperDefaultIsTinyEnQ5() {
        val defaultConfig = DubbingSessionConfig()
        assertTrue("Fast Movie Mode must default to ON in v2.1.0", defaultConfig.fastMovieModeEnabled)
        assertEquals("ggml-tiny.en-q5_1.bin", WhisperModelCatalog.DEFAULT.fileName)
        assertTrue(
            "tiny.en-q5_1 should be compact (~31 MB)",
            WhisperModelCatalog.DEFAULT.expectedSizeBytes in 25_000_000L..40_000_000L
        )
    }

    @Test
    fun v210_rolling30sChunkBoundariesAndUniqueSegmentIndices() {
        assertEquals(0, AutoDubPipelineOrchestrator.chunkIndexForPositionMs(0L))
        assertEquals(0, AutoDubPipelineOrchestrator.chunkIndexForPositionMs(29_999L))
        assertEquals(1, AutoDubPipelineOrchestrator.chunkIndexForPositionMs(30_000L))
        assertEquals(4, AutoDubPipelineOrchestrator.chunkIndexForPositionMs(125_000L))

        assertEquals(0L, AutoDubPipelineOrchestrator.chunkWindowStartMs(0))
        assertEquals(60_000L, AutoDubPipelineOrchestrator.chunkWindowStartMs(2))

        assertEquals(1, AutoDubPipelineOrchestrator.totalChunksForDurationMs(15_000L))
        assertEquals(2, AutoDubPipelineOrchestrator.totalChunksForDurationMs(60_000L))
        assertEquals(3, AutoDubPipelineOrchestrator.totalChunksForDurationMs(60_001L))

        // Chunk 0 and Chunk 1 segment indices never collide in WAV cache
        val c0s0 = AutoDubPipelineOrchestrator.globalSegmentIndex(0, 0)
        val c1s0 = AutoDubPipelineOrchestrator.globalSegmentIndex(1, 0)
        assertEquals(0, c0s0)
        assertEquals(1000, c1s0)
    }

    @Test
    fun v212_systemBengaliTtsDefaultsOn_andDistinctMaleFemalePitch() {
        val defaultConfig = DubbingSessionConfig()
        assertFalse(
            "Experimental Sherpa-ONNX VITS must default to OFF in v2.1.2",
            defaultConfig.useSherpaVitsExperimental
        )

        val maleProfile = CharacterVoiceProfile(
            speakerId = "SPEAKER_01",
            label = "Male Lead",
            voiceId = "bn_male_01",
            gender = VoiceGender.MALE,
            pitchFactor = 1.0f,
            speakingRate = 1.0f,
            characterId = "CHAR_001"
        )
        val femaleProfile = CharacterVoiceProfile(
            speakerId = "SPEAKER_02",
            label = "Female Lead",
            voiceId = "bn_female_01",
            gender = VoiceGender.FEMALE,
            pitchFactor = 1.0f,
            speakingRate = 1.0f,
            characterId = "CHAR_002"
        )
        val malePitch = AndroidSystemBengaliTtsEngine.computeSystemPitch(maleProfile)
        val femalePitch = AndroidSystemBengaliTtsEngine.computeSystemPitch(femaleProfile)
        assertTrue("Expected distinct pitch (male=$malePitch < female=$femalePitch)", malePitch < femalePitch)
        assertEquals(10_000L, AndroidSystemBengaliTtsEngine.LINE_SYNTHESIS_TIMEOUT_MS)
    }

    @Test
    fun v220_edgeNeuralTts_voicesSsmlTimingClampingAndGecToken() {
        assertEquals(
            EdgeNeuralTtsEngine.VOICE_FEMALE_PRIMARY,
            EdgeNeuralTtsEngine.resolveEdgeNeuralVoice(VoiceGender.FEMALE, "CHAR_001")
        )
        assertEquals(
            EdgeNeuralTtsEngine.VOICE_MALE_PRIMARY,
            EdgeNeuralTtsEngine.resolveEdgeNeuralVoice(VoiceGender.MALE, "CHAR_001")
        )
        assertEquals(
            EdgeNeuralTtsEngine.VOICE_FEMALE_SECONDARY,
            EdgeNeuralTtsEngine.resolveEdgeNeuralVoice(VoiceGender.FEMALE, "CHAR_004")
        )
        assertEquals(
            EdgeNeuralTtsEngine.VOICE_MALE_SECONDARY,
            EdgeNeuralTtsEngine.resolveEdgeNeuralVoice(VoiceGender.MALE, "CHAR_004")
        )

        // Strict timing rate clamping [-10%, +35%]
        val fastRate = EdgeNeuralTtsEngine.computeStrictTimingRatePercent(
            bengaliText = "আমি এখনই খুব তাড়াতাড়ি বাড়ি ফিরে আসছি, তুমি চিন্তা করো না।",
            targetMs = 1_200L
        )
        assertEquals(35, fastRate)

        val relaxedRate = EdgeNeuralTtsEngine.computeStrictTimingRatePercent(
            bengaliText = "হ্যালো",
            targetMs = 4_000L,
            emotionRateBonusPercent = -3
        )
        assertTrue("Expected rate in [-10, 0], got $relaxedRate", relaxedRate in -10..0)

        // Valid SSML with XML escaping and prosody tags
        val ssml = EdgeNeuralTtsEngine.buildSsml(
            text = "তুমি & আমি <বন্ধু>",
            voiceName = EdgeNeuralTtsEngine.VOICE_FEMALE_PRIMARY,
            ratePercent = 18,
            pitchHz = 4,
            volume = "+15%"
        )
        assertTrue(ssml.contains("bn-BD-NabanitaNeural"))
        assertTrue(ssml.contains("pitch='+4Hz'"))
        assertTrue(ssml.contains("rate='+18%'"))
        assertTrue(ssml.contains("volume='+15%'"))
        assertTrue(ssml.contains("তুমি &amp; আমি &lt;বন্ধু&gt;"))

        // Sec-MS-GEC token is 64-char uppercase SHA-256 hex
        val gec = EdgeNeuralTtsEngine.generateSecMsGec(1_730_000_000_000L)
        assertEquals(64, gec.length)
        assertTrue(gec.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun v220_audioProsodyAnalyzer_detectsMaleFemalePitchAndLoudSoftEnergy() {
        val sr = AudioProsodyAnalyzer.SAMPLE_RATE_HZ
        // 0.6s synthetic 115 Hz sine wave at high amplitude (0.28 -> loud Male)
        val loudMale = FloatArray((sr * 0.6f).toInt()) { i ->
            (0.28 * sin(2.0 * PI * 115.0 * i / sr)).toFloat()
        }
        val maleProfile = AudioProsodyAnalyzer.analyzeSlice(loudMale, segmentIndex = 0)
        assertEquals(VoiceGender.MALE, maleProfile.detectedGender)
        assertEquals(SegmentEnergyLevel.HIGH, maleProfile.energyLevel)
        assertEquals("+15%", maleProfile.ssmlVolume)
        assertEquals(4, maleProfile.ssmlPitchDeltaHz)

        // 0.6s synthetic 225 Hz sine wave at soft amplitude (0.032 -> soft Female)
        val softFemale = FloatArray((sr * 0.6f).toInt()) { i ->
            (0.032 * sin(2.0 * PI * 225.0 * i / sr)).toFloat()
        }
        val femaleProfile = AudioProsodyAnalyzer.analyzeSlice(softFemale, segmentIndex = 1)
        assertEquals(VoiceGender.FEMALE, femaleProfile.detectedGender)
        assertEquals(SegmentEnergyLevel.SOFT, femaleProfile.energyLevel)
        assertEquals("-10%", femaleProfile.ssmlVolume)
        assertEquals(-2, femaleProfile.ssmlPitchDeltaHz)
    }

    @Test
    fun v220_smartDuckingDefaultsToMixMode_5PercentDialogueAnd85PercentSilence() {
        val defaultConfig = DubbingSessionConfig()
        assertEquals(DubbingAudioOutputMode.BENGALI_DUBBED_MIX, defaultConfig.audioOutputMode)
        assertEquals(0.95f, defaultConfig.bgmDuckingStrength, 0.001f)

        val windows = listOf(DialogueDuckingWindow(startMs = 2_000L, endMs = 4_000L))
        // During silence before dialogue -> 0.85f (85% BGM)
        assertEquals(
            0.85f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(500L, windows, 0.95f),
            0.001f
        )
        // During active Bengali line -> 0.05f (5% original audio so English never clashes)
        assertEquals(
            0.05f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(3_000L, windows, 0.95f),
            0.001f
        )
        // After dialogue fade-out -> smoothly restored to 0.85f
        assertEquals(
            0.85f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(4_500L, windows, 0.95f),
            0.001f
        )
    }
}
