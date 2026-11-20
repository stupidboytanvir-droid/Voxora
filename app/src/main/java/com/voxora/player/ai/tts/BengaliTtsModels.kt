package com.voxora.player.ai.tts

/**
 * Real lifecycle state of the offline Sherpa-ONNX VITS Bengali TTS model pack
 * (`model.onnx` + `tokens.txt` stored in `filesDir/models/tts-bengali/`).
 *
 * Strictly honest: never reports [Ready] unless ALL required files are present and verified.
 */
sealed interface BengaliTtsModelState {
    data object NotInstalled : BengaliTtsModelState
    data class Downloading(
        val progress: Int,
        val stageLabel: String = "Downloading offline Bengali TTS model..."
    ) : BengaliTtsModelState
    data object Ready : BengaliTtsModelState
    data class Error(val message: String) : BengaliTtsModelState
}

/**
 * Importable file slots for the offline Sherpa-ONNX VITS Bengali model pack.
 */
enum class BengaliTtsModelSlot(
    val displayName: String,
    val canonicalFileName: String
) {
    MODEL_ONNX(
        displayName = "VITS Bengali Model (.onnx)",
        canonicalFileName = "model.onnx"
    ),
    TOKENS_TXT(
        displayName = "Bengali Tokens (tokens.txt)",
        canonicalFileName = "tokens.txt"
    )
}

/**
 * Download specification for a file inside the offline Sherpa-ONNX VITS Bengali pack.
 */
data class BengaliTtsFileSpec(
    val slot: BengaliTtsModelSlot,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val minValidSizeBytes: Long
)

/**
 * Verified on-device file paths for the installed Bengali VITS TTS model pack.
 */
data class BengaliTtsModelFiles(
    val modelDirPath: String,
    val modelOnnxPath: String,
    val tokensTxtPath: String,
    val modelSizeBytes: Long,
    val tokensSizeBytes: Long
) {
    val totalSizeBytes: Long
        get() = modelSizeBytes + tokensSizeBytes
}

/**
 * Partial installation visibility when importing local Bengali TTS files one by one.
 */
data class BengaliTtsSlotAvailability(
    val hasModelOnnx: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val hasTokensTxt: Boolean = false,
    val tokensSizeBytes: Long = 0L
) {
    val isComplete: Boolean
        get() = hasModelOnnx && hasTokensTxt
}

/**
 * A single synthesized offline Bengali WAV dialogue line mapped to a VOXORA Character Profile.
 */
data class DubbedAudioSegment(
    val segmentIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val characterId: String,
    val audioFilePath: String,
    val durationMs: Long,
    val text: String = "",
    val appliedSpeed: Float = 1.0f,
    val appliedPitch: Float = 1.0f
) {
    val targetWindowMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Explicit exception thrown when offline Bengali TTS synthesis is requested without installed models.
 */
class BengaliTtsModelNotInstalledException(
    message: String = "Bengali TTS Model Required / Not Installed: Download or import the offline Sherpa-ONNX VITS Bengali model pack first."
) : IllegalStateException(message)

/**
 * Live UI state for the Phase 5 Offline Bengali Voice Generation (TTS) tab.
 */
data class BengaliVoiceGenerationUiState(
    val isSynthesizing: Boolean = false,
    val synthesizedCount: Int = 0,
    val totalCount: Int = 0,
    val statusMessage: String = "Idle",
    val errorMessage: String? = null,
    val activeVideoUri: String = "",
    val segments: List<DubbedAudioSegment> = emptyList(),
    val currentlyPlayingSegmentIndex: Int? = null
) {
    val progressFraction: Float
        get() = if (totalCount > 0) (synthesizedCount.toFloat() / totalCount.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Readiness snapshot across all 4 offline VOXORA AI Dubbing models for the One-Click Setup card.
 */
data class OneClickAiSetupState(
    val isWhisperReady: Boolean = false,
    val isDiarizationReady: Boolean = false,
    val isTranslationReady: Boolean = false,
    val isBengaliTtsReady: Boolean = false,
    val isRunning: Boolean = false,
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentStepLabel: String = "",
    val currentStepProgressPercent: Int = 0,
    val errorMessage: String? = null
) {
    val readyModelCount: Int
        get() = listOf(isWhisperReady, isDiarizationReady, isTranslationReady, isBengaliTtsReady).count { it }

    val missingModelCount: Int
        get() = 4 - readyModelCount

    val allModelsReady: Boolean
        get() = missingModelCount == 0

    val overallProgressFraction: Float
        get() {
            if (totalSteps <= 0) return if (allModelsReady) 1f else 0f
            val completedFraction = ((currentStepIndex - 1).coerceAtLeast(0)).toFloat() / totalSteps.toFloat()
            val activeStepFraction = (currentStepProgressPercent.coerceIn(0, 100) / 100f) / totalSteps.toFloat()
            return (completedFraction + activeStepFraction).coerceIn(0f, 1f)
        }
}
