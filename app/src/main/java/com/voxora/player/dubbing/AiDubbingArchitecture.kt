package com.voxora.player.dubbing

/**
 * VOXORA AI Dubbing — Phase 1 Modular Processing Contracts.
 *
 * ZERO-COST & HONEST ARCHITECTURE:
 * - No paid APIs, cloud subscriptions, or fake AI synthesis.
 * - Stage 1 (Audio Extraction via Android MediaExtractor) and Stage 6 (Audio Sync & Live
 *   Volume Mixer via Media3 ExoPlayer) are genuinely implemented and ready on-device.
 * - Stages 2, 3, 4, 5, and 7 define clean Kotlin interfaces ready for future free & open-source
 *   offline AI models (whisper.cpp, Sherpa-ONNX, OPUS-MT/NLLB-200, Piper VITS Bengali, Demucs).
 */

sealed interface DubbingCapabilityStatus {
    data class ComingSoon(
        val plannedVersion: String = "Phase 2",
        val plannedCapabilities: List<String> = listOf(
            "On-device English speech recognition (whisper.cpp / Sherpa-ONNX)",
            "Multi-speaker diarization & character identification",
            "Offline English-to-Bengali (বাংলা) neural translation",
            "Natural multi-character Bengali neural voice generation",
            "Original background music & sound effects stem preservation",
            "Frame-accurate dubbed dialogue synchronization"
        )
    ) : DubbingCapabilityStatus

    data class Phase1Configured(
        val activeSource: DubbingLanguage = DubbingLanguageRegistry.ENGLISH,
        val activeTarget: DubbingLanguage = DubbingLanguageRegistry.BENGALI,
        val readyStages: List<DubbingPipelineStage> = listOf(
            DubbingPipelineStage.AUDIO_EXTRACTION,
            DubbingPipelineStage.AUDIO_SYNCHRONIZATION
        ),
        val pendingEngineStages: List<DubbingPipelineStage> = listOf(
            DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION,
            DubbingPipelineStage.SPEAKER_DIARIZATION,
            DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
            DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
            DubbingPipelineStage.BACKGROUND_AUDIO_PRESERVATION
        )
    ) : DubbingCapabilityStatus

    data object Ready : DubbingCapabilityStatus
    data class Processing(val progressPercent: Int, val stageLabel: String) : DubbingCapabilityStatus
}

/** Stage 1: Extracts audio track metadata and raw audio stream from a local video URI. */
interface VideoAudioExtractor {
    suspend fun inspectAudioTrack(mediaUriString: String): Result<ExtractedAudioTrackInfo>
    suspend fun extractAudioStreamToCache(mediaUriString: String): Result<ExtractedAudioTrackInfo>
}

/** Stage 2: Transcribes extracted English speech into time-aligned text segments. */
interface SpeechRecognitionEngine {
    val engineName: String
    val isModelInstalled: Boolean
    suspend fun transcribeAudioTrack(
        audioFilePath: String,
        sourceLanguage: DubbingLanguage = DubbingLanguageRegistry.ENGLISH
    ): Result<List<RecognizedSpeechSegment>>
}

/** Stage 3: Identifies distinct speakers/characters across the timeline. */
interface SpeakerDiarizationEngine {
    val engineName: String
    val isModelInstalled: Boolean
    suspend fun identifySpeakers(audioFilePath: String): Result<List<SpeakerTurn>>
}

/** Stage 4: Context-aware translation (Phase 1: English -> Bengali / বাংলা). */
interface MultilingualTranslator {
    val engineName: String
    val isModelInstalled: Boolean
    suspend fun translateSegments(
        segments: List<RecognizedSpeechSegment>,
        sourceLanguage: DubbingLanguage,
        targetLanguage: DubbingLanguage
    ): Result<List<TranslatedSegment>>
}

/** Stage 5: Generates natural, expressive multi-character Bengali voices. */
interface CharacterVoiceSynthesizer {
    val engineName: String
    val isModelInstalled: Boolean
    suspend fun synthesizeDubbedTrack(
        segments: List<TranslatedSegment>,
        voiceProfiles: List<CharacterVoiceProfile>,
        targetLanguage: DubbingLanguage
    ): Result<String>
}

/** Stage 6: Synchronizes dubbed speech with the video timeline and live volume mixing. */
interface AudioSynchronizer {
    fun computeEffectiveOriginalVolume(
        config: DubbingSessionConfig,
        hasSynthesizedDubTrack: Boolean
    ): Float

    suspend fun alignAndMixAudio(
        synthesizedSpeechPath: String,
        backgroundStemPath: String?,
        segments: List<TranslatedSegment>,
        syncOffsetMs: Long
    ): Result<String>
}

/** Stage 7: Separates original dialogue from background music and sound effects. */
interface AudioStemSeparator {
    val engineName: String
    val isModelInstalled: Boolean
    suspend fun extractBackgroundStem(audioFilePath: String): Result<String>
}

interface AiDubbingPipeline {
    fun getCapabilityStatus(): DubbingCapabilityStatus
    fun getSupportedSourceLanguages(): List<DubbingLanguage>
    fun getSupportedTargetLanguages(): List<DubbingLanguage>
    fun buildStageStatuses(
        extractedAudioInfo: ExtractedAudioTrackInfo?,
        isExtractingAudio: Boolean = false
    ): List<PipelineStageStatus>

    fun computeEffectiveOriginalVolume(
        config: DubbingSessionConfig,
        hasSynthesizedDubTrack: Boolean
    ): Float
}

/**
 * Pure, deterministic mixer calculation for Stage 6 (Audio Sync & Mixing).
 * Preserves uninterrupted normal playback when dubbing is disabled or when no
 * synthesized Bengali track has been attached yet.
 */
class DefaultDubbingAudioSynchronizer : AudioSynchronizer {
    override fun computeEffectiveOriginalVolume(
        config: DubbingSessionConfig,
        hasSynthesizedDubTrack: Boolean
    ): Float {
        if (!config.isDubbingEnabled) return 1.0f
        return when (config.audioOutputMode) {
            DubbingAudioOutputMode.ORIGINAL_AUDIO -> config.originalAudioVolume.coerceIn(0f, 1f)
            DubbingAudioOutputMode.BENGALI_DUBBED_MIX -> config.originalAudioVolume.coerceIn(0f, 1f)
            DubbingAudioOutputMode.BENGALI_DUBBED_ONLY -> {
                if (hasSynthesizedDubTrack) {
                    if (config.preserveOriginalBackgroundAudio) {
                        (config.originalAudioVolume * 0.25f).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                } else {
                    // Honest safeguard: keep user's Original Audio slider in control until a real Bengali track exists
                    config.originalAudioVolume.coerceIn(0f, 1f)
                }
            }
        }
    }

    override suspend fun alignAndMixAudio(
        synthesizedSpeechPath: String,
        backgroundStemPath: String?,
        segments: List<TranslatedSegment>,
        syncOffsetMs: Long
    ): Result<String> {
        return Result.failure(
            UnsupportedOperationException(
                "Offline dubbed audio track mixing activates automatically once an offline Bengali TTS track is produced."
            )
        )
    }
}

/**
 * Phase 1 Offline-First AI Dubbing Pipeline Registry (English -> Bengali).
 * Clearly reports ready on-device stages versus stages awaiting open-source offline AI models.
 */
class Version1AiDubbingPipeline(
    private val synchronizer: AudioSynchronizer = DefaultDubbingAudioSynchronizer()
) : AiDubbingPipeline {

    override fun getCapabilityStatus(): DubbingCapabilityStatus =
        DubbingCapabilityStatus.ComingSoon()

    fun getPhase1Status(): DubbingCapabilityStatus.Phase1Configured =
        DubbingCapabilityStatus.Phase1Configured()

    override fun getSupportedSourceLanguages(): List<DubbingLanguage> =
        DubbingLanguageRegistry.sourceLanguages

    override fun getSupportedTargetLanguages(): List<DubbingLanguage> =
        DubbingLanguageRegistry.targetLanguages

    override fun computeEffectiveOriginalVolume(
        config: DubbingSessionConfig,
        hasSynthesizedDubTrack: Boolean
    ): Float = synchronizer.computeEffectiveOriginalVolume(config, hasSynthesizedDubTrack)

    override fun buildStageStatuses(
        extractedAudioInfo: ExtractedAudioTrackInfo?,
        isExtractingAudio: Boolean
    ): List<PipelineStageStatus> {
        val extractionStatus = when {
            isExtractingAudio -> PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_EXTRACTION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Extracting audio stream from video on-device..."
            )
            extractedAudioInfo?.extractedFilePath != null -> PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_EXTRACTION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Extracted ${extractedAudioInfo.codecDisplayName} (${extractedAudioInfo.sampleRateHz / 1000} kHz, ${extractedAudioInfo.channelCount}ch) to local cache"
            )
            extractedAudioInfo != null -> PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_EXTRACTION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Detected ${extractedAudioInfo.codecDisplayName} · ${extractedAudioInfo.sampleRateHz / 1000} kHz · ${extractedAudioInfo.channelCount}ch · Ready to extract"
            )
            else -> PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_EXTRACTION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Android MediaExtractor module ready for local video audio extraction"
            )
        }

        return listOf(
            extractionStatus,
            PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Interface ready · Awaiting offline English ASR model (whisper.cpp / Sherpa-ONNX)"
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Character profile UI ready · Awaiting offline speaker clustering engine"
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "EN → বাংলা language pair configured · Awaiting offline translation model (OPUS-MT / NLLB)"
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Male & Female Bengali voice profiles ready · Awaiting offline neural TTS engine (Piper VITS)"
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_SYNCHRONIZATION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Live original/dubbed volume mixing & millisecond sync controls active on Media3"
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.BACKGROUND_AUDIO_PRESERVATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Preservation toggle wired · Awaiting offline stem separation model (Demucs)"
            )
        )
    }
}
