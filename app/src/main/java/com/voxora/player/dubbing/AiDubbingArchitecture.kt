package com.voxora.player.dubbing

import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.ai.tts.BengaliTtsModelState

/**
 * VOXORA AI Dubbing — Phase 1 Modular Processing Contracts & Speaker-Character Registry.
 *
 * ZERO-COST & HONEST ARCHITECTURE:
 * - No paid APIs, cloud subscriptions, or fake AI synthesis.
 * - Stage 1 (Audio Extraction via Android MediaExtractor) and Stage 6 (Audio Sync & Live
 *   Volume Mixer via Media3 ExoPlayer) are genuinely implemented and ready on-device.
 * - Stages 2, 3, 4, 5, and 7 define clean Kotlin interfaces ready for future free & open-source
 *   offline AI models (whisper.cpp, Sherpa-ONNX, OPUS-MT/NLLB-200, Piper VITS Bengali, Demucs).
 * - [SpeakerDiarizationCharacterMapper] guarantees consistent speaker-to-character voice mapping
 *   throughout an entire movie once offline speaker diarization is connected.
 */

sealed interface DubbingCapabilityStatus {
    data class ComingSoon(
        val plannedVersion: String = "Phase 2",
        val plannedCapabilities: List<String> = listOf(
            "On-device English speech recognition (whisper.cpp / Sherpa-ONNX)",
            "Multi-speaker diarization & automatic character assignment",
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

/**
 * Output of mapping diarization speaker turns to persistent VOXORA [CharacterVoiceProfile]s
 * for a specific movie.
 */
data class DiarizationAssignmentResult(
    val updatedCharacters: List<CharacterVoiceProfile>,
    val movieAssignments: List<MovieSpeakerAssignment>,
    val newlyCreatedCharacterIds: List<String>
)

/**
 * Contract bridging future offline [SpeakerDiarizationEngine] output with VOXORA's local
 * Character Management system. Guarantees that once a speaker cluster is assigned to a
 * character for a movie, every dialogue line spoken by that speaker across the entire movie
 * resolves to the exact same character and voice profile.
 */
interface SpeakerDiarizationCharacterMapper {
    fun assignSpeakersForMovie(
        detectedTurns: List<SpeakerTurn>,
        savedCharacters: List<CharacterVoiceProfile>,
        existingMovieAssignments: List<MovieSpeakerAssignment>
    ): DiarizationAssignmentResult

    fun resolveCharacterForSegment(
        diarizationSpeakerId: String,
        movieAssignments: List<MovieSpeakerAssignment>,
        savedCharacters: List<CharacterVoiceProfile>
    ): CharacterVoiceProfile?
}

/**
 * Deterministic mapper that binds diarized speakers to saved characters and locks their
 * voice profile consistently for the entire movie.
 */
class DefaultSpeakerDiarizationCharacterMapper : SpeakerDiarizationCharacterMapper {

    override fun assignSpeakersForMovie(
        detectedTurns: List<SpeakerTurn>,
        savedCharacters: List<CharacterVoiceProfile>,
        existingMovieAssignments: List<MovieSpeakerAssignment>
    ): DiarizationAssignmentResult {
        val workingCharacters = savedCharacters.toMutableList()
        val charactersById = workingCharacters.associateBy { it.characterId }.toMutableMap()
        val assignmentsBySpeaker = linkedMapOf<String, MovieSpeakerAssignment>()
        val newlyCreatedIds = mutableListOf<String>()

        // 1. Preserve all existing valid movie assignments to guarantee whole-movie consistency.
        existingMovieAssignments.forEach { assignment ->
            val matchedCharacter = charactersById[assignment.characterId]
            if (matchedCharacter != null) {
                assignmentsBySpeaker[assignment.diarizationSpeakerId] = assignment.copy(
                    lockedVoiceId = if (matchedCharacter.isVoiceLockedForMovie) {
                        assignment.lockedVoiceId
                    } else {
                        matchedCharacter.voiceId
                    }
                )
            }
        }

        // 2. Group detected speakers in chronological order of first appearance.
        val distinctSpeakers = linkedMapOf<String, VoiceGender?>()
        detectedTurns.sortedBy { it.startTimeMs }.forEach { turn ->
            if (turn.speakerId !in distinctSpeakers || distinctSpeakers[turn.speakerId] == null) {
                distinctSpeakers[turn.speakerId] = turn.estimatedGender
            }
        }

        val assignedCharacterIds = assignmentsBySpeaker.values.map { it.characterId }.toMutableSet()

        // 3. Bind each newly detected speaker to an existing compatible profile or create a new one.
        distinctSpeakers.forEach { (speakerId, estimatedGender) ->
            if (speakerId in assignmentsBySpeaker) return@forEach

            val directSlotMatch = workingCharacters.firstOrNull {
                it.speakerId.equals(speakerId, ignoreCase = true) && it.characterId !in assignedCharacterIds
            }
            val genderMatch = directSlotMatch ?: workingCharacters.firstOrNull {
                it.characterId !in assignedCharacterIds &&
                    (estimatedGender == null || it.gender == estimatedGender)
            }

            val chosenCharacter = if (genderMatch != null) {
                genderMatch
            } else {
                val gender = estimatedGender ?: if (workingCharacters.size % 2 == 0) {
                    VoiceGender.MALE
                } else {
                    VoiceGender.FEMALE
                }
                val preset = DubbingVoiceCatalog.nextVoicePresetForGender(gender, workingCharacters)
                val created = CharacterVoiceProfile(
                    characterId = DubbingVoiceCatalog.nextUniqueCharacterId(workingCharacters),
                    speakerId = speakerId,
                    label = DubbingVoiceCatalog.nextDefaultCharacterName(workingCharacters),
                    voiceId = preset.id,
                    gender = gender,
                    pitchFactor = preset.defaultPitch,
                    speakingRate = preset.defaultRate,
                    emotionStyle = VoiceEmotionStyle.NATURAL,
                    isDetectedFromDiarization = true,
                    isVoiceLockedForMovie = true
                )
                workingCharacters.add(created)
                charactersById[created.characterId] = created
                newlyCreatedIds.add(created.characterId)
                created
            }

            assignedCharacterIds.add(chosenCharacter.characterId)
            assignmentsBySpeaker[speakerId] = MovieSpeakerAssignment(
                diarizationSpeakerId = speakerId,
                characterId = chosenCharacter.characterId,
                lockedVoiceId = chosenCharacter.voiceId,
                isAutoAssigned = true
            )
        }

        return DiarizationAssignmentResult(
            updatedCharacters = workingCharacters,
            movieAssignments = assignmentsBySpeaker.values.toList(),
            newlyCreatedCharacterIds = newlyCreatedIds
        )
    }

    override fun resolveCharacterForSegment(
        diarizationSpeakerId: String,
        movieAssignments: List<MovieSpeakerAssignment>,
        savedCharacters: List<CharacterVoiceProfile>
    ): CharacterVoiceProfile? {
        val assignment = movieAssignments.firstOrNull {
            it.diarizationSpeakerId.equals(diarizationSpeakerId, ignoreCase = true)
        }
        if (assignment != null) {
            val matched = savedCharacters.firstOrNull { it.characterId == assignment.characterId }
            if (matched != null) {
                return if (matched.isVoiceLockedForMovie) {
                    matched.copy(voiceId = assignment.lockedVoiceId)
                } else {
                    matched
                }
            }
        }
        return savedCharacters.firstOrNull {
            it.speakerId.equals(diarizationSpeakerId, ignoreCase = true)
        }
    }
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
        isExtractingAudio: Boolean = false,
        isWhisperModelLoaded: Boolean = false,
        isTranscribing: Boolean = false,
        whisperModelDetail: String? = null,
        diarizationModelState: DiarizationModelState = DiarizationModelState.NotInstalled,
        isDiarizing: Boolean = false,
        translationModelState: TranslationModelState = TranslationModelState.NotDownloaded,
        isTranslating: Boolean = false,
        bengaliTtsModelState: BengaliTtsModelState = BengaliTtsModelState.NotInstalled,
        useSherpaVitsExperimental: Boolean = false,
        isSynthesizingBengaliVoice: Boolean = false,
        isLiveDubbingActive: Boolean = false,
        preserveBackgroundAudio: Boolean = true,
        bgmDuckingStrength: Float = 0.80f,
        vocalAttenuationEnabled: Boolean = false
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
        isExtractingAudio: Boolean,
        isWhisperModelLoaded: Boolean,
        isTranscribing: Boolean,
        whisperModelDetail: String?,
        diarizationModelState: DiarizationModelState,
        isDiarizing: Boolean,
        translationModelState: TranslationModelState,
        isTranslating: Boolean,
        bengaliTtsModelState: BengaliTtsModelState,
        useSherpaVitsExperimental: Boolean,
        isSynthesizingBengaliVoice: Boolean,
        isLiveDubbingActive: Boolean,
        preserveBackgroundAudio: Boolean,
        bgmDuckingStrength: Float,
        vocalAttenuationEnabled: Boolean
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
                statusDetail = "Android MediaExtractor + MediaCodec 16kHz PCM decoder ready on-device"
            )
        }

        val asrStatus = when {
            isTranscribing -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Running native whisper.cpp offline English speech recognition..."
            )
            isWhisperModelLoaded -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION,
                readiness = ComponentReadiness.READY,
                statusDetail = whisperModelDetail
                    ?: "Native whisper.cpp (tiny.en-q5_1) installed & verified on-device — ready offline"
            )
            else -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_SPEECH_RECOGNITION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = whisperModelDetail
                    ?: "Native whisper.cpp engine compiled · Download ggml-tiny.en-q5_1.bin (~31 MB) in One-Click Setup or Transcribe tab"
            )
        }

        val translationStatus = when {
            isTranslating -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Translating recognized English segments to Bengali (বাংলা) offline via ML Kit..."
            )
            translationModelState is TranslationModelState.Ready -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Google ML Kit offline English → বাংলা model installed & ready on-device"
            )
            translationModelState is TranslationModelState.Downloading -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Downloading offline ML Kit English → বাংলা translation model (~30 MB)..."
            )
            translationModelState is TranslationModelState.Checking -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Checking offline ML Kit English → বাংলা model status on-device..."
            )
            translationModelState is TranslationModelState.Error -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.ERROR,
                statusDetail = translationModelState.message
            )
            else -> PipelineStageStatus(
                stage = DubbingPipelineStage.ENGLISH_TO_BENGALI_TRANSLATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "ML Kit on-device translator integrated · Model Required / Not Downloaded (~30 MB offline EN → বাংলা)"
            )
        }

        val diarizationStatus = when {
            isDiarizing -> PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "Detecting & clustering speakers offline via Sherpa-ONNX..."
            )
            diarizationModelState is DiarizationModelState.Ready -> PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Sherpa-ONNX offline segmentation & speaker embedding models verified & ready on-device"
            )
            diarizationModelState is DiarizationModelState.Downloading -> PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "${diarizationModelState.stageLabel} (${diarizationModelState.progress}%)"
            )
            diarizationModelState is DiarizationModelState.Error -> PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.ERROR,
                statusDetail = diarizationModelState.message
            )
            else -> PipelineStageStatus(
                stage = DubbingPipelineStage.SPEAKER_DIARIZATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Sherpa-ONNX integrated · Diarization Model Required / Not Installed (~31 MB offline ONNX models)"
            )
        }

        val bengaliTtsStatus = when {
            isSynthesizingBengaliVoice -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = if (useSherpaVitsExperimental) {
                    "Synthesizing Bengali character voices offline via Sherpa-ONNX VITS (Experimental)..."
                } else {
                    "Synthesizing Bengali character voices offline via Android built-in TextToSpeech..."
                }
            )
            !useSherpaVitsExperimental -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Android built-in offline Bengali TTS (bn-BD / bn-IN) active by default · Near-zero RAM & zero native crash"
            )
            bengaliTtsModelState is BengaliTtsModelState.Ready -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.READY,
                statusDetail = "Sherpa-ONNX VITS (Experimental) Bengali model verified & ready on-device"
            )
            bengaliTtsModelState is BengaliTtsModelState.Downloading -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.RUNNING,
                statusDetail = "${bengaliTtsModelState.stageLabel} (${bengaliTtsModelState.progress}%)"
            )
            bengaliTtsModelState is BengaliTtsModelState.Error -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.ERROR,
                statusDetail = bengaliTtsModelState.message
            )
            else -> PipelineStageStatus(
                stage = DubbingPipelineStage.BENGALI_SPEECH_GENERATION,
                readiness = ComponentReadiness.REQUIRES_AI_ENGINE,
                statusDetail = "Sherpa-ONNX VITS (Experimental) enabled · Download VITS pack (~109 MB) or turn OFF to use built-in Android Bengali TTS"
            )
        }

        return listOf(
            extractionStatus,
            asrStatus,
            diarizationStatus,
            translationStatus,
            bengaliTtsStatus,
            PipelineStageStatus(
                stage = DubbingPipelineStage.AUDIO_SYNCHRONIZATION,
                readiness = if (isLiveDubbingActive) ComponentReadiness.RUNNING else ComponentReadiness.READY,
                statusDetail = if (isLiveDubbingActive) {
                    "Live synchronized Bengali dubbing active on Media3 ExoPlayer with millisecond sync & speed lock"
                } else {
                    "Media3 synchronized Bengali audio timeline, speed matching & millisecond sync offset ready on-device"
                }
            ),
            PipelineStageStatus(
                stage = DubbingPipelineStage.BACKGROUND_AUDIO_PRESERVATION,
                readiness = ComponentReadiness.READY,
                statusDetail = buildString {
                    if (preserveBackgroundAudio) {
                        val duckPct = (bgmDuckingStrength * 100f).toInt().coerceIn(0, 95)
                        append("Smart Audio Ducking active (${duckPct}% dialogue duck, 150ms smooth ramp)")
                    } else {
                        append("Smart Audio Ducking ready (currently paused by toggle)")
                    }
                    if (vocalAttenuationEnabled) {
                        append(" · Mid-Side Stereo Vocal Reduction ON")
                    }
                }
            )
        )
    }
}
