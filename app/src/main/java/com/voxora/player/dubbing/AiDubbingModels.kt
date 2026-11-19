package com.voxora.player.dubbing

import kotlinx.serialization.Serializable

/**
 * VOXORA AI Dubbing — Phase 1 Models (English -> Bengali / বাংলা).
 * Designed for zero-cost, offline-first modular expansion to additional languages
 * and on-device neural models in subsequent phases.
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
    val description: String
)

@Serializable
data class CharacterVoiceProfile(
    val speakerId: String,
    val label: String,
    val voiceId: String,
    val gender: VoiceGender = VoiceGender.MALE,
    val pitchFactor: Float = 1.0f,
    val speakingRate: Float = 1.0f,
    val emotionStyle: VoiceEmotionStyle = VoiceEmotionStyle.NATURAL,
    val isDetectedFromDiarization: Boolean = false
)

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
        openSourceTarget = "OPUS-MT en-bn / NLLB-200 Distilled (Offline)"
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
    val pipelineStages: List<PipelineStageStatus> = emptyList()
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
    val bengaliPresets: List<BengaliVoicePreset> = listOf(
        BengaliVoicePreset(
            id = "bn_male_aarav",
            name = "Aarav",
            nativeDisplayName = "আরভ · Deep Cinema",
            gender = VoiceGender.MALE,
            styleTag = "Deep Lead",
            defaultPitch = 0.94f,
            defaultRate = 0.98f,
            description = "Rich, grounded baritone designed for lead male movie dialogue"
        ),
        BengaliVoicePreset(
            id = "bn_male_rafiq",
            name = "Rafiq",
            nativeDisplayName = "রফিক · Conversational",
            gender = VoiceGender.MALE,
            styleTag = "Natural",
            defaultPitch = 1.0f,
            defaultRate = 1.0f,
            description = "Warm everyday Bengali voice for natural dialogue scenes"
        ),
        BengaliVoicePreset(
            id = "bn_male_kabir",
            name = "Kabir",
            nativeDisplayName = "কবীর · Narrator",
            gender = VoiceGender.MALE,
            styleTag = "Documentary",
            defaultPitch = 0.98f,
            defaultRate = 0.95f,
            description = "Clear articulated voice suited for narration and calm dialogue"
        ),
        BengaliVoicePreset(
            id = "bn_female_ananya",
            name = "Ananya",
            nativeDisplayName = "অনন্যা · Expressive Lead",
            gender = VoiceGender.FEMALE,
            styleTag = "Cinematic Lead",
            defaultPitch = 1.02f,
            defaultRate = 1.0f,
            description = "Natural, emotionally nuanced Bengali voice for lead female roles"
        ),
        BengaliVoicePreset(
            id = "bn_female_meera",
            name = "Meera",
            nativeDisplayName = "মীরা · Studio Clear",
            gender = VoiceGender.FEMALE,
            styleTag = "Warm Studio",
            defaultPitch = 1.0f,
            defaultRate = 0.97f,
            description = "Balanced, gentle cadence suited for conversational and voiceover tracks"
        ),
        BengaliVoicePreset(
            id = "bn_female_tania",
            name = "Tania",
            nativeDisplayName = "তানিয়া · Dynamic",
            gender = VoiceGender.FEMALE,
            styleTag = "Energetic",
            defaultPitch = 1.06f,
            defaultRate = 1.04f,
            description = "Bright expressive timbre ideal for younger or energetic characters"
        )
    )

    fun presetsForGender(gender: VoiceGender): List<BengaliVoicePreset> {
        return bengaliPresets.filter { it.gender == gender }
    }

    fun findById(id: String): BengaliVoicePreset {
        return bengaliPresets.firstOrNull { it.id == id } ?: bengaliPresets.first()
    }

    fun defaultCharacters(): List<CharacterVoiceProfile> = listOf(
        CharacterVoiceProfile(
            speakerId = "SPEAKER_01",
            label = "Character 1 (Lead Male)",
            voiceId = "bn_male_aarav",
            gender = VoiceGender.MALE,
            pitchFactor = 0.94f,
            speakingRate = 0.98f,
            emotionStyle = VoiceEmotionStyle.NATURAL
        ),
        CharacterVoiceProfile(
            speakerId = "SPEAKER_02",
            label = "Character 2 (Lead Female)",
            voiceId = "bn_female_ananya",
            gender = VoiceGender.FEMALE,
            pitchFactor = 1.02f,
            speakingRate = 1.0f,
            emotionStyle = VoiceEmotionStyle.WARM
        )
    )
}
