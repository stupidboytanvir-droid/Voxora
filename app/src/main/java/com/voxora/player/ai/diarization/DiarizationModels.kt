package com.voxora.player.ai.diarization

/**
 * Real lifecycle state of the two offline Sherpa-ONNX Speaker Diarization models:
 * 1. Pyannote segmentation model (`model.onnx`)
 * 2. Speaker embedding model (`3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` or `nemo_en_titanet_small.onnx`)
 *
 * Strictly honest: never reports [Ready] unless BOTH ONNX files are present and pass integrity checks.
 */
sealed interface DiarizationModelState {
    data object NotInstalled : DiarizationModelState
    data class Downloading(
        val progress: Int,
        val stageLabel: String = "Downloading offline diarization models..."
    ) : DiarizationModelState
    data object Ready : DiarizationModelState
    data class Error(val message: String) : DiarizationModelState
}

/**
 * Which of the two required ONNX models is being imported locally via SAF.
 */
enum class DiarizationModelSlot(
    val displayName: String,
    val canonicalFileName: String
) {
    SEGMENTATION(
        displayName = "Pyannote Segmentation (.onnx)",
        canonicalFileName = "pyannote-segmentation-3-0.onnx"
    ),
    EMBEDDING(
        displayName = "Speaker Embedding (.onnx)",
        canonicalFileName = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
    )
}

/**
 * Specification for a downloadable offline Sherpa-ONNX diarization model file.
 */
data class DiarizationModelSpec(
    val slot: DiarizationModelSlot,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val minValidSizeBytes: Long
)

/**
 * Verified local paths and sizes of the installed diarization ONNX models.
 */
data class DiarizationModelFiles(
    val segmentationModelPath: String,
    val embeddingModelPath: String,
    val segmentationSizeBytes: Long,
    val embeddingSizeBytes: Long
) {
    val totalSizeBytes: Long
        get() = segmentationSizeBytes + embeddingSizeBytes
}

/**
 * Partial installation status (useful when a user imports 1 of the 2 local `.onnx` models).
 */
data class DiarizationSlotAvailability(
    val hasSegmentationModel: Boolean = false,
    val segmentationSizeBytes: Long = 0L,
    val hasEmbeddingModel: Boolean = false,
    val embeddingSizeBytes: Long = 0L
) {
    val isComplete: Boolean
        get() = hasSegmentationModel && hasEmbeddingModel
}

/**
 * A single time-aligned speaker turn detected offline by Sherpa-ONNX `OfflineSpeakerDiarization`.
 *
 * @property startMs Turn start timestamp in milliseconds.
 * @property endMs Turn end timestamp in milliseconds.
 * @property speakerIndex 0-based speaker cluster index (`0`, `1`, `2`, ...).
 * @property confidence Optional Sherpa-ONNX cluster confidence score (`-2f` if unavailable).
 */
data class SpeakerSegment(
    val startMs: Long,
    val endMs: Long,
    val speakerIndex: Int,
    val confidence: Float = 0f
) {
    /** 1-based canonical speaker slot ID matching VOXORA Character Management (`SPEAKER_01`, `SPEAKER_02`, ...). */
    val speakerId: String
        get() = "SPEAKER_${(speakerIndex + 1).toString().padStart(2, '0')}"

    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Explicit exception thrown when diarization is invoked without installed ONNX models.
 */
class DiarizationModelNotInstalledException(
    message: String = "Diarization Model Required / Not Installed: Download or import the offline Sherpa-ONNX segmentation and speaker embedding models first."
) : IllegalStateException(message)

/**
 * Summary statistics for a detected speaker cluster displayed in the AI Dubbing Studio.
 */
data class DetectedSpeakerSummary(
    val speakerIndex: Int,
    val speakerId: String,
    val segmentCount: Int,
    val totalSpeakingMs: Long,
    val assignedCharacterId: String?
)

/**
 * UI state for the Phase 4 Offline Speaker Diarization & Automatic Character Mapping section.
 */
data class SpeakerDiarizationUiState(
    val isRunning: Boolean = false,
    val progressFraction: Float = 0f,
    val statusMessage: String = "Idle",
    val errorMessage: String? = null,
    val activeVideoUri: String = "",
    val numClustersConfig: Int = -1,
    val thresholdConfig: Float = 0.5f,
    val detectedSegments: List<SpeakerSegment> = emptyList(),
    val speakerToCharacterMap: Map<Int, String> = emptyMap(),
    val segmentCharacterMap: Map<Int, String> = emptyMap(),
    val autoCreatedCharacterCount: Int = 0
) {
    val detectedSpeakerSummaries: List<DetectedSpeakerSummary>
        get() = detectedSegments
            .groupBy { it.speakerIndex }
            .toSortedMap()
            .map { (speakerIdx, segs) ->
                DetectedSpeakerSummary(
                    speakerIndex = speakerIdx,
                    speakerId = "SPEAKER_${(speakerIdx + 1).toString().padStart(2, '0')}",
                    segmentCount = segs.size,
                    totalSpeakingMs = segs.sumOf { it.durationMs },
                    assignedCharacterId = speakerToCharacterMap[speakerIdx]
                )
            }
}
