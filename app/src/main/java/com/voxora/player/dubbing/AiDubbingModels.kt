package com.voxora.player.dubbing

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * VOXORA AI Dubbing — Phase 1 Models (English -> Bengali / বাংলা) & Character Management System.
 * Designed for zero-cost, offline-first modular expansion to additional languages,
 * multi-character neural voices, and speaker diarization in subsequent phases.
 */

@Serializable
data class DubbingLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val isPhase1Active: Boolean = false,
    val supportedAsSource: Boolean = false,
    val supportedAsTarget: Boolean = true
)

@Serializable
enum class DubbingAudioOutputMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    ORIGINAL_AUDIO(
        label = "Original Audio",
        shortLabel = "Original",
        description = "Plays the unmodified original video audio track"
    ),
    BENGALI_DUBBED_MIX(
        label = "Bengali Dubbed + Original Mix",
        shortLabel = "বাংলা Mix",
        description = "Layers Bengali character voices over preserved background audio"
    ),
    BENGALI_DUBBED_ONLY(
        label = "Bengali Dubbed Audio",
        shortLabel = "বাংলা Only",
        description = "Prioritizes synthesized Bengali dialogue with minimal original dialogue"
    )
}

@Serializable
enum class VoiceGender(val label: String) {
    MALE("Male"),
    FEMALE("Female")
}

@Serializable
enum class VoiceEmotionStyle(
    val label: String,
    val description: String
) {
    NATURAL("Natural", "Balanced, human-like conversational delivery"),
    WARM("Warm", "Empathetic and calm studio tone"),
    DRAMATIC("Dramatic", "Expressive cinematic pacing for intense scenes"),
    EXCITED("Excited", "Energetic cadence with elevated inflection"),
    WHISPER("Whisper", "Soft, close-mic intimate tone"),
    SOMBER("Somber", "Measured, reflective emotional delivery")
}

@Serializable
data class BengaliVoicePreset(
    val id: String,
    val name: String,
    val nativeDisplayName: String,
    val gender: VoiceGender,
    val languageCode: String = "bn",
    val styleTag: String,
    val defaultPitch: Float = 1.0f,
    val defaultRate: Float = 1.0f,
    val description: String,
    val isNeuralModelInstalled: Boolean = false
)

/**
 * Persistent character voice profile saved locally on device.
 *
 * - [characterId]: Immutable unique identifier (e.g. "CHAR_001") guaranteed collision-free
 *   across unlimited character additions and deletions.
 * - [speakerId]: Speaker diarization cluster slot (e.g. "SPEAKER_01") used to bind detected
 *   movie speakers consistently throughout the entire video in Phase 2.
 */
@Serializable
data class CharacterVoiceProfile(
    val speakerId: String,
    val label: String,
    val voiceId: String,
    val gender: VoiceGender = VoiceGender.MALE,
    val pitchFactor: Float = 1.0f,
    val speakingRate: Float = 1.0f,
    val emotionStyle: VoiceEmotionStyle = VoiceEmotionStyle.NATURAL,
    val isDetectedFromDiarization: Boolean = false,
    val characterId: String = speakerId,
    val emotionIntensity: Float = 0.5f,
    val isVoiceLockedForMovie: Boolean = true,
    val createdAtMs: Long = 0L
)

/**
 * Per-movie binding that locks a detected or manually configured diarization speaker ID
 * to a specific [CharacterVoiceProfile] so the character's voice never drifts mid-movie.
 */
@Serializable
data class MovieSpeakerAssignment(
    val diarizationSpeakerId: String,
    val characterId: String,
    val lockedVoiceId: String,
    val isAutoAssigned: Boolean = false
)

/**
 * Live status of the real on-device Android TextToSpeech engine.
 * Preview controls remain strictly disabled unless [canPreview] is true.
 */
data class TtsPreviewState(
    val isChecking: Boolean = true,
    val isRealTtsEngineAvailable: Boolean = false,
    val isBengaliVoiceSupported: Boolean = false,
    val enginePackageName: String? = null,
    val matchedLocaleTag: String? = null,
    val statusMessage: String = "Checking device Text-to-Speech engine...",
    val activePreviewCharacterId: String? = null
) {
    val canPreview: Boolean
        get() = !isChecking && isRealTtsEngineAvailable && isBengaliVoiceSupported
}

@Serializable
data class DubbingSessionConfig(
    val mediaUriString: String = "",
    val isDubbingEnabled: Boolean = false,
    val sourceLanguage: DubbingLanguage = DubbingLanguageRegistry.ENGLISH,
    val targetLanguage: DubbingLanguage = DubbingLanguageRegistry.BENGALI,
    val audioOutputMode: DubbingAudioOutputMode = DubbingAudioOutputMode.ORIGINAL_AUDIO,
    val originalAudioVolume: Float = 1.0f,
    val dubbedAudioVolume: Float = 1.0f,
    val syncOffsetMs: Long = 0L,
    val preserveOriginalBackgroundAudio: Boolean = true,
    val autoDetectSourceLanguage: Boolean = false,
    val characterVoices: List<CharacterVoiceProfile> = DubbingVoiceCatalog.defaultCharacters()
)

data class RecognizedSpeechSegment(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val originalText: String,
    val detectedLanguageCode: String,
    val speakerId: String,
    val confidence: Float = 0f
)

data class SpeakerTurn(
    val speakerId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val estimatedGender: VoiceGender? = null
)

data class TranslatedSegment(
    val segmentId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sourceLanguageCode: String = "en",
    val targetLanguageCode: String = "bn",
    val originalText: String = "",
    val translatedText: String,
    val speakerId: String,
    val targetDurationMs: Long = (endTimeMs - startTimeMs).coerceAtLeast(0L)
)

data class ExtractedAudioTrackInfo(
    val uriString: String,
    val trackIndex: Int,
    val mimeType: String,
    val codecDisplayName: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitrateBps: Int,
    val durationMs: Long,
    val extractedFilePath: String? = null,
    val extractedBytes: Long = 0L
)

enum class DubbingPipelineStage(
    val order: Int,
    val title: String,
    val openSourceTarget: String
) {
    AUDIO_EXTRACTION(
        order = 1,
        title = "Audio Extraction",
        openSourceTarget = "Android MediaExtractor (On-Device, Free)"
    ),
    ENGLISH_SPEECH_RECOGNITION(
        order = 2,
        title = "English Speech Recognition (ASR)",
        openSourceTarget = "whisper.cpp / Sherpa-ONNX Whisper (Offline)"
    ),
    SPEAKER_DIARIZATION(
        order = 3,
        title = "Speaker Diarization",
        openSourceTarget = "Sherpa-ONNX Speaker Embeddings (Offline)"
    ),
    ENGLISH_TO_BENGALI_TRANSLATION(
        order = 4,
        title = "English → Bengali Translation",
        openSourceTarget = "Google ML Kit On-Device Translation (Offline, Free)"
    ),
    BENGALI_SPEECH_GENERATION(
        order = 5,
        title = "Bengali Natural Voice Generation",
        openSourceTarget = "Piper VITS / MMS-TTS Bengali Multi-Voice (Offline)"
    ),
    AUDIO_SYNCHRONIZATION(
        order = 6,
        title = "Audio Sync & Live Volume Mixer",
        openSourceTarget = "AndroidX Media3 ExoPlayer Audio Engine"
    ),
    BACKGROUND_AUDIO_PRESERVATION(
        order = 7,
        title = "Background Music & SFX Preservation",
        openSourceTarget = "Demucs / Spleeter Stem Separation (Offline)"
    )
}

enum class ComponentReadiness(val badgeLabel: String) {
    READY("Ready"),
    REQUIRES_AI_ENGINE("Requires AI Engine"),
    RUNNING("Running"),
    ERROR("Error")
}

data class PipelineStageStatus(
    val stage: DubbingPipelineStage,
    val readiness: ComponentReadiness,
    val statusDetail: String
)

data class PlayerDubbingUiState(
    val config: DubbingSessionConfig = DubbingSessionConfig(),
    val extractedAudioInfo: ExtractedAudioTrackInfo? = null,
    val isInspectingAudio: Boolean = false,
    val isExtractingAudio: Boolean = false,
    val extractionMessage: String? = null,
    val hasSynthesizedBengaliTrack: Boolean = false,
    val pipelineStages: List<PipelineStageStatus> = emptyList(),
    val movieSpeakerAssignments: List<MovieSpeakerAssignment> = emptyList(),
    val ttsPreviewState: TtsPreviewState = TtsPreviewState()
) {
    val readyComponentCount: Int
        get() = pipelineStages.count { it.readiness == ComponentReadiness.READY }

    val totalComponentCount: Int
        get() = pipelineStages.size.coerceAtLeast(DubbingPipelineStage.entries.size)
}

object DubbingLanguageRegistry {
    val ENGLISH = DubbingLanguage(
        code = "en",
        displayName = "English",
        nativeName = "English",
        isPhase1Active = true,
        supportedAsSource = true,
        supportedAsTarget = false
    )

    val BENGALI = DubbingLanguage(
        code = "bn",
        displayName = "Bengali",
        nativeName = "বাংলা",
        isPhase1Active = true,
        supportedAsSource = false,
        supportedAsTarget = true
    )

    val sourceLanguages: List<DubbingLanguage> = listOf(
        ENGLISH,
        DubbingLanguage("hi", "Hindi", "हिन्दी", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("es", "Spanish", "Español", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("ja", "Japanese", "日本語", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("ko", "Korean", "한국어", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true)
    )

    val targetLanguages: List<DubbingLanguage> = listOf(
        BENGALI,
        DubbingLanguage("hi", "Hindi", "हिन्दी", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("es", "Spanish", "Español", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("ur", "Urdu", "اردو", isPhase1Active = false, supportedAsSource = false, supportedAsTarget = true),
        DubbingLanguage("ar", "Arabic", "العربية", isPhase1Active = false, supportedAsSource = false, supportedAsTarget = true),
        DubbingLanguage("fr", "French", "Français", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("de", "German", "Deutsch", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true),
        DubbingLanguage("ja", "Japanese", "日本語", isPhase1Active = false, supportedAsSource = true, supportedAsTarget = true)
    )
}

object DubbingVoiceCatalog {
    /**
     * Bengali voice profile slots prepared for Phase 2 offline neural voice models.
     * [BengaliVoicePreset.isNeuralModelInstalled] is explicitly false until real voice models
     * are bundled or downloaded on-device.
     */
    val bengaliPresets: List<BengaliVoicePreset> = listOf(
        BengaliVoicePreset(
            id = "bn_male_aarav",
            name = "Male Profile A · Deep",
            nativeDisplayName = "পুরুষ প্রোফাইল A · Deep Cinema",
            gender = VoiceGender.MALE,
            styleTag = "Deep Lead Slot",
            defaultPitch = 0.94f,
            defaultRate = 0.98f,
            description = "Voice slot configured for deep lead male dialogue (awaiting Phase 2 neural Bengali voice model)",
            isNeuralModelInstalled = false
        ),
        BengaliVoicePreset(
            id = "bn_male_rafiq",
            name = "Male Profile B · Natural",
            nativeDisplayName = "পুরুষ প্রোফাইল B · Conversational",
            gender = VoiceGender.MALE,
            styleTag = "Natural Slot",
            defaultPitch = 1.0f,
            defaultRate = 1.0f,
            description = "Voice slot configured for everyday conversational male dialogue (awaiting Phase 2 neural model)",
            isNeuralModelInstalled = false
        ),
        BengaliVoicePreset(
            id = "bn_male_kabir",
            name = "Male Profile C · Narrator",
            nativeDisplayName = "পুরুষ প্রোফাইল C · Calm Narrator",
            gender = VoiceGender.MALE,
            styleTag = "Narrator Slot",
            defaultPitch = 0.98f,
            defaultRate = 0.95f,
            description = "Voice slot configured for clear narration and calm dialogue (awaiting Phase 2 neural model)",
            isNeuralModelInstalled = false
        ),
        BengaliVoicePreset(
            id = "bn_female_ananya",
            name = "Female Profile A · Expressive",
            nativeDisplayName = "নারী প্রোফাইল A · Expressive Lead",
            gender = VoiceGender.FEMALE,
            styleTag = "Cinematic Lead Slot",
            defaultPitch = 1.02f,
            defaultRate = 1.0f,
            description = "Voice slot configured for expressive lead female dialogue (awaiting Phase 2 neural Bengali voice model)",
            isNeuralModelInstalled = false
        ),
        BengaliVoicePreset(
            id = "bn_female_meera",
            name = "Female Profile B · Studio",
            nativeDisplayName = "নারী প্রোফাইল B · Studio Clear",
            gender = VoiceGender.FEMALE,
            styleTag = "Warm Studio Slot",
            defaultPitch = 1.0f,
            defaultRate = 0.97f,
            description = "Voice slot configured for balanced gentle female dialogue (awaiting Phase 2 neural model)",
            isNeuralModelInstalled = false
        ),
        BengaliVoicePreset(
            id = "bn_female_tania",
            name = "Female Profile C · Dynamic",
            nativeDisplayName = "নারী প্রোফাইল C · Dynamic",
            gender = VoiceGender.FEMALE,
            styleTag = "Energetic Slot",
            defaultPitch = 1.06f,
            defaultRate = 1.04f,
            description = "Voice slot configured for bright, energetic female roles (awaiting Phase 2 neural model)",
            isNeuralModelInstalled = false
        )
    )

    fun presetsForGender(gender: VoiceGender): List<BengaliVoicePreset> {
        return bengaliPresets.filter { it.gender == gender }
    }

    fun findById(id: String): BengaliVoicePreset {
        return bengaliPresets.firstOrNull { it.id == id } ?: bengaliPresets.first()
    }

    fun nextVoicePresetForGender(
        gender: VoiceGender,
        existing: List<CharacterVoiceProfile>
    ): BengaliVoicePreset {
        val candidates = presetsForGender(gender)
        val sameGenderCount = existing.count { it.gender == gender }
        return candidates[sameGenderCount % candidates.size]
    }

    /**
     * Generates a strictly unique character ID (CHAR_001, CHAR_002, CHAR_003...) that never
     * collides with existing profiles even after arbitrary deletions and additions.
     */
    fun nextUniqueCharacterId(existing: List<CharacterVoiceProfile>): String {
        val existingIds = existing.map { it.characterId }.toSet()
        val maxNumeric = existing.mapNotNull { profile ->
            Regex("""^CHAR_(\d+)$""").matchEntire(profile.characterId)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: existing.size

        var candidateNum = (maxNumeric + 1).coerceAtLeast(1)
        var candidate = "CHAR_${candidateNum.toString().padStart(3, '0')}"
        while (candidate in existingIds) {
            candidateNum++
            candidate = "CHAR_${candidateNum.toString().padStart(3, '0')}"
        }
        return candidate.ifBlank { "CHAR_${UUID.randomUUID().toString().take(8).uppercase()}" }
    }

    /**
     * Generates the next unused diarization speaker slot ID (SPEAKER_01, SPEAKER_02, SPEAKER_03...).
     */
    fun nextAvailableSpeakerId(existing: List<CharacterVoiceProfile>): String {
        val existingSlots = existing.map { it.speakerId.uppercase() }.toSet()
        val maxNumeric = existing.mapNotNull { profile ->
            Regex("""^SPEAKER_(\d+)$""").matchEntire(profile.speakerId.uppercase())?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: existing.size

        var candidateNum = (maxNumeric + 1).coerceAtLeast(1)
        var candidate = "SPEAKER_${candidateNum.toString().padStart(2, '0')}"
        while (candidate in existingSlots) {
            candidateNum++
            candidate = "SPEAKER_${candidateNum.toString().padStart(2, '0')}"
        }
        return candidate
    }

    /**
     * Suggests clean sequential names for additional characters: "Character 3", "Character 4", etc.
     */
    fun nextDefaultCharacterName(existing: List<CharacterVoiceProfile>): String {
        val usedNames = existing.map { it.label.trim().lowercase() }.toSet()
        val highestNumbered = existing.mapNotNull { profile ->
            Regex("""^Character\s+(\d+)$""", RegexOption.IGNORE_CASE)
                .matchEntire(profile.label.trim())
                ?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 2

        var nextNum = maxOf(highestNumbered + 1, existing.size + 1, 3)
        var candidate = "Character $nextNum"
        while (candidate.lowercase() in usedNames) {
            nextNum++
            candidate = "Character $nextNum"
        }
        return candidate
    }

    /**
     * Two default example character profiles: Male Character & Female Character.
     */
    fun defaultCharacters(): List<CharacterVoiceProfile> = listOf(
        CharacterVoiceProfile(
            characterId = "CHAR_001",
            speakerId = "SPEAKER_01",
            label = "Male Character",
            voiceId = "bn_male_aarav",
            gender = VoiceGender.MALE,
            pitchFactor = 0.94f,
            speakingRate = 0.98f,
            emotionStyle = VoiceEmotionStyle.NATURAL,
            emotionIntensity = 0.5f,
            isVoiceLockedForMovie = true
        ),
        CharacterVoiceProfile(
            characterId = "CHAR_002",
            speakerId = "SPEAKER_02",
            label = "Female Character",
            voiceId = "bn_female_ananya",
            gender = VoiceGender.FEMALE,
            pitchFactor = 1.02f,
            speakingRate = 1.0f,
            emotionStyle = VoiceEmotionStyle.WARM,
            emotionIntensity = 0.5f,
            isVoiceLockedForMovie = true
        )
    )
}
