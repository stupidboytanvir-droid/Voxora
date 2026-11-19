package com.voxora.player

import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DefaultDubbingAudioSynchronizer
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingLanguageRegistry
import com.voxora.player.dubbing.DubbingPipelineStage
import com.voxora.player.dubbing.DubbingSessionConfig
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.ExtractedAudioTrackInfo
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import com.voxora.player.dubbing.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDubbingPhase1Test {

    @Test
    fun `phase 1 defaults to English source and Bengali target with expansion languages ready`() {
        val config = DubbingSessionConfig()
        assertEquals("en", config.sourceLanguage.code)
        assertEquals("English", config.sourceLanguage.displayName)
        assertTrue(config.sourceLanguage.isPhase1Active)

        assertEquals("bn", config.targetLanguage.code)
        assertEquals("Bengali", config.targetLanguage.displayName)
        assertEquals("বাংলা", config.targetLanguage.nativeName)
        assertTrue(config.targetLanguage.isPhase1Active)

        // Architecture includes planned languages for future expansion
        assertTrue(DubbingLanguageRegistry.targetLanguages.size > 1)
        assertTrue(DubbingLanguageRegistry.sourceLanguages.size > 1)
    }

    @Test
    fun `bengali voice catalog provides distinct male and female voice profiles`() {
        val maleVoices = DubbingVoiceCatalog.presetsForGender(VoiceGender.MALE)
        val femaleVoices = DubbingVoiceCatalog.presetsForGender(VoiceGender.FEMALE)

        assertTrue(maleVoices.size >= 2)
        assertTrue(femaleVoices.size >= 2)
        assertTrue(maleVoices.all { it.languageCode == "bn" && it.gender == VoiceGender.MALE })
        assertTrue(femaleVoices.all { it.languageCode == "bn" && it.gender == VoiceGender.FEMALE })

        val defaultChars = DubbingVoiceCatalog.defaultCharacters()
        assertEquals(2, defaultChars.size)
        assertTrue(defaultChars.any { it.gender == VoiceGender.MALE })
        assertTrue(defaultChars.any { it.gender == VoiceGender.FEMALE })
    }

    @Test
    fun `pipeline honestly reports 2 ready on-device stages and 5 engine-pending stages`() {
        val pipeline = Version1AiDubbingPipeline()
        val sampleAudio = ExtractedAudioTrackInfo(
            uriString = "content://media/external/video/media/99",
            trackIndex = 1,
            mimeType = "audio/mp4a-latm",
            codecDisplayName = "AAC",
            sampleRateHz = 48_000,
            channelCount = 2,
            bitrateBps = 192_000,
            durationMs = 180_000L
        )
        val stages = pipeline.buildStageStatuses(sampleAudio, isExtractingAudio = false)

        assertEquals(7, stages.size)
        assertEquals(DubbingPipelineStage.entries.size, stages.size)

        val readyStages = stages.filter { it.readiness == ComponentReadiness.READY }.map { it.stage }
        assertTrue(DubbingPipelineStage.AUDIO_EXTRACTION in readyStages)
        assertTrue(DubbingPipelineStage.AUDIO_SYNCHRONIZATION in readyStages)
        assertEquals(2, readyStages.size)

        val pendingStages = stages.filter { it.readiness == ComponentReadiness.REQUIRES_AI_ENGINE }.map { it.stage }
        assertEquals(5, pendingStages.size)
        assertTrue(DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION in pendingStages)
        assertTrue(DubbingPipelineStage.SPEAKER_DIARIZATION in pendingStages)
        assertTrue(DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION in pendingStages)
        assertTrue(DubbingPipelineStage.BENGALI_SPEECH_GENERATION in pendingStages)
        assertTrue(DubbingPipelineStage.BACKGROUND_AUDIO_PRESERVATION in pendingStages)
    }

    @Test
    fun `audio synchronizer never mutes normal playback when dubbing disabled or unsynthesized`() {
        val sync = DefaultDubbingAudioSynchronizer()

        // When dubbing disabled, always full original volume regardless of slider
        val disabledConfig = DubbingSessionConfig(
            isDubbingEnabled = false,
            originalAudioVolume = 0.2f
        )
        assertEquals(1.0f, sync.computeEffectiveOriginalVolume(disabledConfig, hasSynthesizedDubTrack = false), 0.001f)

        // When dubbing enabled without synthesized Bengali track, slider controls volume directly (never forced silent)
        val enabledNoTrack = DubbingSessionConfig(
            isDubbingEnabled = true,
            audioOutputMode = DubbingAudioOutputMode.BENGALI_DUBBED_ONLY,
            originalAudioVolume = 0.65f
        )
        assertEquals(0.65f, sync.computeEffectiveOriginalVolume(enabledNoTrack, hasSynthesizedDubTrack = false), 0.001f)

        // Once a real synthesized Bengali track exists, Bengali Only ducks original to 25% if background preservation is on
        val withTrackPreserved = enabledNoTrack.copy(preserveOriginalBackgroundAudio = true)
        assertEquals(0.1625f, sync.computeEffectiveOriginalVolume(withTrackPreserved, hasSynthesizedDubTrack = true), 0.001f)

        val withTrackMuted = enabledNoTrack.copy(preserveOriginalBackgroundAudio = false)
        assertEquals(0.0f, sync.computeEffectiveOriginalVolume(withTrackMuted, hasSynthesizedDubTrack = true), 0.001f)
        assertFalse(withTrackMuted.preserveOriginalBackgroundAudio)
    }
}
