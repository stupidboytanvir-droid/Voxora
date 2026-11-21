package com.voxora.player

import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.dubbing.AutoDubPipelineOrchestrator
import com.voxora.player.ai.dubbing.AutoDubPipelineState
import com.voxora.player.ai.dubbing.AutoDubStage
import com.voxora.player.ai.dubbing.BackgroundAudioPreserver
import com.voxora.player.ai.dubbing.DialogueDuckingWindow
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.dubbing.DubbingPlaybackController
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
import com.voxora.player.dubbing.VoiceGenderOverrideMode
import com.voxora.player.ai.subtitles.AutoSubtitleLanguageCatalog
import com.voxora.player.ai.subtitles.AutoSubtitleManager
import com.voxora.player.dubbing.whisper.WhisperModelCatalog
import com.voxora.player.dubbing.whisper.WhisperNativeBridge
import com.voxora.player.model.ABLoopState
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.model.SubtitleCue
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.UserPreferences
import com.voxora.player.player.VoxoraPlayerEngine
import com.voxora.player.util.PrivateVaultManager
import com.voxora.player.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun v210_fastMovieModeDefaultsOn_andWhisperDefaultIsMultilingualTinyQ5() {
        val defaultConfig = DubbingSessionConfig()
        assertTrue("Fast Movie Mode must default to ON in v2.1.0", defaultConfig.fastMovieModeEnabled)
        assertEquals("ggml-tiny-q5_1.bin", WhisperModelCatalog.DEFAULT.fileName)
        assertTrue(
            "tiny-q5_1 should be compact (~31 MB)",
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
    fun v221_edgeNeuralTts_voicesSsmlTimingClampingAndExactSecMsGecUrl() {
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

        // Manual gender override takes precedence over detected gender
        assertEquals(
            VoiceGender.FEMALE,
            EdgeNeuralTtsEngine.resolveEffectiveGender(
                overrideMode = VoiceGenderOverrideMode.FEMALE,
                manualCharacterAssigned = false,
                acoustic = null,
                profile = null
            )
        )
        assertEquals(
            VoiceGender.MALE,
            EdgeNeuralTtsEngine.resolveEffectiveGender(
                overrideMode = VoiceGenderOverrideMode.MALE,
                manualCharacterAssigned = false,
                acoustic = null,
                profile = null
            )
        )

        // Strict timing rate clamping [+0%, +35%]
        val fastRate = EdgeNeuralTtsEngine.computeStrictTimingRatePercent(
            bengaliText = "আমি এখনই খুব তাড়াতাড়ি বাড়ি ফিরে আসছি, তুমি চিন্তা করো না।",
            targetMs = 1_200L
        )
        assertEquals(35, fastRate)

        val relaxedRate = EdgeNeuralTtsEngine.computeStrictTimingRatePercent(
            bengaliText = "হ্যালো",
            targetMs = 4_000L,
            emotionRateBonusPercent = 0
        )
        assertEquals(0, relaxedRate)

        // Lip-sync playback speed fitting clamped strictly to [1.0f, 1.35f]
        assertEquals(1.0f, DubbingPlaybackController.computeLipSyncFitSpeed(1_500L, 2_000L), 0.001f)
        assertEquals(1.25f, DubbingPlaybackController.computeLipSyncFitSpeed(2_500L, 2_000L), 0.001f)
        assertEquals(1.35f, DubbingPlaybackController.computeLipSyncFitSpeed(4_000L, 1_500L), 0.001f)

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

        // Sec-MS-GEC token is 64-char uppercase SHA-256 hex & URL matches exact Edge protocol format
        val gec = EdgeNeuralTtsEngine.generateSecMsGec(1_730_000_000_000L)
        assertEquals(64, gec.length)
        assertTrue(gec.all { it in '0'..'9' || it in 'A'..'F' })

        val wsUrl = EdgeNeuralTtsEngine.buildEdgeWebSocketUrl(
            connectionId = "abcdef0123456789abcdef0123456789",
            unixTimeMillis = 1_730_000_000_000L
        )
        assertEquals(
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=1-130.0.2849.68" +
                "&ConnectionId=abcdef0123456789abcdef0123456789",
            wsUrl
        )
    }

    @Test
    fun v221_audioProsodyAnalyzer_detects155HzGenderThresholdAndExpressiveVolume() {
        val sr = AudioProsodyAnalyzer.SAMPLE_RATE_HZ
        assertEquals(155.0f, AudioProsodyAnalyzer.FEMALE_F0_THRESHOLD_HZ, 0.001f)

        // 0.6s synthetic 115 Hz sine wave at high amplitude (0.28 -> loud Male)
        val loudMale = FloatArray((sr * 0.6f).toInt()) { i ->
            (0.28 * sin(2.0 * PI * 115.0 * i / sr)).toFloat()
        }
        val maleProfile = AudioProsodyAnalyzer.analyzeSlice(loudMale, segmentIndex = 0)
        assertEquals(VoiceGender.MALE, maleProfile.detectedGender)
        assertEquals(SegmentEnergyLevel.HIGH, maleProfile.energyLevel)
        assertEquals("+15%", maleProfile.ssmlVolume)
        assertEquals(4, maleProfile.ssmlPitchDeltaHz)

        // 0.6s synthetic 165 Hz sine wave (>= 155 Hz threshold -> Female) at soft amplitude (0.032 -> soft)
        val softFemale = FloatArray((sr * 0.6f).toInt()) { i ->
            (0.032 * sin(2.0 * PI * 165.0 * i / sr)).toFloat()
        }
        val femaleProfile = AudioProsodyAnalyzer.analyzeSlice(softFemale, segmentIndex = 1)
        assertEquals(VoiceGender.FEMALE, femaleProfile.detectedGender)
        assertEquals(SegmentEnergyLevel.SOFT, femaleProfile.energyLevel)
        assertEquals("-8%", femaleProfile.ssmlVolume)
        assertEquals(-2, femaleProfile.ssmlPitchDeltaHz)
    }

    @Test
    fun v221_smartDuckingDefaultsToMixMode_3PercentDialogueAnd80PercentSilence() {
        val defaultConfig = DubbingSessionConfig()
        assertEquals(DubbingAudioOutputMode.BENGALI_DUBBED_MIX, defaultConfig.audioOutputMode)
        assertEquals(0.97f, defaultConfig.bgmDuckingStrength, 0.001f)
        assertEquals(VoiceGenderOverrideMode.AUTO, defaultConfig.voiceGenderOverride)

        val windows = listOf(DialogueDuckingWindow(startMs = 2_000L, endMs = 4_000L))
        // During silence before dialogue -> 0.80f (80% BGM)
        assertEquals(
            0.80f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(500L, windows, 0.97f),
            0.001f
        )
        // During active Bengali line -> 0.03f (3% original audio so English never clashes)
        assertEquals(
            0.03f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(3_000L, windows, 0.97f),
            0.001f
        )
        // After dialogue fade-out -> smoothly restored to 0.80f
        assertEquals(
            0.80f,
            BackgroundAudioPreserver.computeSmartDuckingVolume(4_500L, windows, 0.97f),
            0.001f
        )
    }

    @Test
    fun v230_autoSubtitlesCatalogFrameSyncThreadCapAndPlayerModels() {
        // Battery-optimized Whisper CPU thread cap (max 2 threads)
        val threads = WhisperNativeBridge.recommendedThreadCount()
        assertTrue("Whisper thread count must be capped to 1..2, got $threads", threads in 1..2)

        // 59+ ML Kit languages + Original Spoken Language
        val languages = AutoSubtitleLanguageCatalog.allLanguages
        assertTrue("Expected at least 59 subtitle languages, got ${languages.size}", languages.size >= 59)
        val codes = languages.map { it.code }.toSet()
        assertTrue(codes.contains(AutoSubtitleLanguageCatalog.CODE_ORIGINAL))
        assertTrue(codes.contains("en"))
        assertTrue(codes.contains("bn"))
        assertTrue(codes.contains("hi"))
        assertTrue(codes.contains("es"))
        assertTrue(codes.contains("ja"))
        assertTrue(codes.contains("ko"))

        // Exact frame-synced binary-search lookup & silence clearing
        val cues = listOf(
            SubtitleCue(startTimeMs = 1_000L, endTimeMs = 3_200L, text = "Hello world"),
            SubtitleCue(startTimeMs = 4_500L, endTimeMs = 6_000L, text = "Second line")
        )
        assertNull(AutoSubtitleManager.findExactCueForPosition(cues, 500L))
        assertEquals("Hello world", AutoSubtitleManager.findExactCueForPosition(cues, 2_000L))
        assertNull(AutoSubtitleManager.findExactCueForPosition(cues, 3_800L))
        assertEquals("Second line", AutoSubtitleManager.findExactCueForPosition(cues, 5_000L))
        assertNull(AutoSubtitleManager.findExactCueForPosition(cues, 6_500L))

        // Whisper bracketed artifact cleaning
        assertEquals("Hello there", AutoSubtitleManager.cleanWhisperText("[MUSIC] Hello   there [BLANK_AUDIO]"))
        assertEquals("", AutoSubtitleManager.cleanWhisperText("[BLANK_AUDIO]"))

        // Subtitle text colors & sleep timer options
        assertEquals(listOf("White", "Yellow", "Cyan"), SubtitleTextColor.entries.map { it.label })
        assertEquals(6, SleepTimerOption.entries.size)
        assertEquals(15, SleepTimerOption.MIN_15.durationMinutes)
        assertEquals(60, SleepTimerOption.MIN_60.durationMinutes)
    }

    @Test
    fun v240_flagshipUpgrade_vadTrimmingAutoOrientationDefaultFontSrtAndVault() {
        // 1. Default subtitle font size is 15sp (clean, uncluttered)
        assertEquals(15f, UserPreferences().subtitleFontSizeSp, 0.001f)

        // 2. VAD speech-onset trimming eliminates premature subtitles during 5-second silence gaps
        val sr = 16_000
        val totalSeconds = 8
        val pcm = FloatArray(sr * totalSeconds) { i ->
            val timeMs = (i * 1000L) / sr
            if (timeMs in 5_000L..6_800L) {
                (0.25 * sin(2.0 * PI * 220.0 * i / sr)).toFloat()
            } else {
                0.0005f // quiet background floor
            }
        }
        val trimmed = AutoSubtitleManager.trimSegmentWithVad(
            chunkPcm = pcm,
            chunkStartMs = 0L,
            rawStartMs = 0L,
            rawEndMs = 8_000L
        )
        assertNotNull("Expected voiced region to survive VAD trim", trimmed)
        val (vadStartMs, vadEndMs) = trimmed!!
        assertTrue("Expected VAD start near 5000ms (got $vadStartMs)", vadStartMs in 4_900L..5_100L)
        assertTrue("Expected VAD end near 6800ms (got $vadEndMs)", vadEndMs in 6_750L..6_950L)

        // Zero look-ahead during the 5-second silence gap
        val syncedCues = listOf(SubtitleCue(startTimeMs = vadStartMs, endTimeMs = vadEndMs, text = "Hello now"))
        assertNull(AutoSubtitleManager.findExactCueForPosition(syncedCues, 2_500L))
        assertNull(AutoSubtitleManager.findExactCueForPosition(syncedCues, vadStartMs - 1L))
        assertEquals("Hello now", AutoSubtitleManager.findExactCueForPosition(syncedCues, 5_500L))
        assertNull(AutoSubtitleManager.findExactCueForPosition(syncedCues, vadEndMs + 1L))

        // 3. Smart Auto-Orientation handles rotation metadata cleanly
        assertEquals(1920 to 1080, VoxoraPlayerEngine.computeEffectiveVideoDimensions(1920, 1080, 0))
        assertEquals(1080 to 1920, VoxoraPlayerEngine.computeEffectiveVideoDimensions(1920, 1080, 90))
        assertEquals(1080 to 1920, VoxoraPlayerEngine.computeEffectiveVideoDimensions(1920, 1080, 270))
        assertEquals(1920 to 1080, VoxoraPlayerEngine.computeEffectiveVideoDimensions(1920, 1080, 180))

        // 4. SRT export and parser round-trip
        val srtText = AutoSubtitleManager.formatAsSrt(
            listOf(
                SubtitleCue(startTimeMs = 1_250L, endTimeMs = 3_500L, text = "First line"),
                SubtitleCue(startTimeMs = 65_000L, endTimeMs = 68_120L, text = "Second line")
            )
        )
        val reparsed = SubtitleParser.parse(srtText, "movie.srt")
        assertEquals(2, reparsed.size)
        assertEquals(1_250L, reparsed[0].startTimeMs)
        assertEquals(3_500L, reparsed[0].endTimeMs)
        assertEquals("First line", reparsed[0].text)
        assertEquals(65_000L, reparsed[1].startTimeMs)
        assertEquals("Second line", reparsed[1].text)

        // 5. Private Vault SHA-256 PIN hashing & verification
        val pinHash = PrivateVaultManager.hashPin("2468")
        assertEquals(64, pinHash.length)
        assertTrue(PrivateVaultManager.verifyPin("2468", pinHash))
        assertFalse(PrivateVaultManager.verifyPin("0000", pinHash))

        // 6. Pro Audio EQ presets & A-B Loop validation
        assertEquals(5, AudioEqPreset.entries.size)
        assertTrue(AudioEqPreset.entries.all { it.bandOffsetsMb.size == 5 })
        assertFalse(ABLoopState(pointAMs = 5_000L, pointBMs = null).isLooping)
        assertFalse(ABLoopState(pointAMs = 5_000L, pointBMs = 3_000L).isLooping)
        assertTrue(ABLoopState(pointAMs = 5_000L, pointBMs = 12_000L).isLooping)
    }
}
