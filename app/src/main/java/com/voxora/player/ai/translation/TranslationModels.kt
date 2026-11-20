package com.voxora.player.ai.translation

import com.voxora.player.dubbing.TranslatedSegment as PipelineTranslatedSegment

/**
 * Real lifecycle state of the on-device ML Kit English -> Bengali offline translation model.
 * Never reports [Ready] unless the actual ML Kit remote model is verified as downloaded on-device.
 */
sealed interface TranslationModelState {
    data object NotDownloaded : TranslationModelState
    data object Checking : TranslationModelState
    data object Downloading : TranslationModelState
    data object Ready : TranslationModelState
    data class Error(val message: String) : TranslationModelState
}

/**
 * A single time-aligned segment translated offline from English to Bengali (বাংলা).
 *
 * @property index 0-based sequential index matching the input Whisper transcript order.
 * @property startMs Segment start timestamp in milliseconds.
 * @property endMs Segment end timestamp in milliseconds.
 * @property originalText Recognized English source text from whisper.cpp.
 * @property translatedText Offline ML Kit Bengali (বাংলা) translation.
 * @property characterId Optional VOXORA character ID reserved for Phase 4 Speaker Diarization.
 */
data class TranslatedSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String,
    val characterId: String? = null
) {
    fun toPipelineSegment(segmentId: String = "TR_${(index + 1).toString().padStart(4, '0')}"): PipelineTranslatedSegment {
        return PipelineTranslatedSegment(
            segmentId = segmentId,
            startTimeMs = startMs,
            endTimeMs = endMs,
            sourceLanguageCode = "en",
            targetLanguageCode = "bn",
            originalText = originalText,
            translatedText = translatedText,
            speakerId = characterId ?: "SPEAKER_01"
        )
    }
}

/**
 * Thrown (and surfaced via [Result.failure]) when translation is requested before the
 * on-device ML Kit English -> Bengali model has been downloaded.
 */
class TranslationModelNotDownloadedException(
    message: String = "Model Required / Not Downloaded: Download the offline English → Bengali (~30 MB) ML Kit model before translating."
) : IllegalStateException(message)

/**
 * UI state for the Phase 3 Offline English -> Bengali Translation Studio tab.
 */
data class BengaliTranslationUiState(
    val isTranslating: Boolean = false,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val activeVideoUri: String = "",
    val activeVideoTitle: String = "",
    val segments: List<TranslatedSegment> = emptyList(),
    val isLoadedFromCache: Boolean = false,
    val statusMessage: String = "Idle",
    val errorMessage: String? = null,
    val exportFeedback: String? = null
) {
    val progressFraction: Float
        get() = if (totalSegments > 0) {
            (completedSegments.toFloat() / totalSegments.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val fullBengaliText: String
        get() = segments.mapNotNull { it.translatedText.trim().takeIf(String::isNotEmpty) }
            .joinToString(" ")
}
