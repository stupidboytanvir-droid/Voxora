package com.voxora.player.ai.dubbing

/**
 * VOXORA Phase 6 (v2.0.0) — Models for Background Audio Preservation,
 * Real-Time Synchronized Bengali Dubbing Playback, One-Click Auto-Dub Pipeline,
 * and Offline MP4/M4A/WAV Dubbed Export.
 */

enum class DubbingExportFormat(
    val label: String,
    val shortLabel: String,
    val extension: String,
    val mimeType: String
) {
    MP4_VIDEO(
        label = "Dubbed Video (MP4)",
        shortLabel = "MP4 Video",
        extension = "mp4",
        mimeType = "video/mp4"
    ),
    M4A_AUDIO(
        label = "Dubbed Audio (M4A)",
        shortLabel = "M4A Audio",
        extension = "m4a",
        mimeType = "audio/mp4"
    ),
    WAV_AUDIO(
        label = "Dubbed Audio (WAV)",
        shortLabel = "WAV Audio",
        extension = "wav",
        mimeType = "audio/wav"
    )
}

/**
 * Time interval on the video timeline during which Bengali dubbed dialogue is speaking
 * and the original video audio should be smoothly ducked.
 */
data class DialogueDuckingWindow(
    val startMs: Long,
    val endMs: Long
)

/**
 * Live synchronized dubbing playback state exposed to the Player & AI Dubbing Studio UI.
 */
data class LiveDubbingPlaybackState(
    val isActive: Boolean = false,
    val isTrackReady: Boolean = false,
    val isAssemblingTrack: Boolean = false,
    val isOriginalDucked: Boolean = false,
    val activeSegmentIndex: Int? = null,
    val activeCharacterId: String? = null,
    val activeBengaliLine: String? = null,
    val effectiveOriginalVolume: Float = 1.0f,
    val effectiveDubbedVolume: Float = 0.0f,
    val assembledTrackPath: String? = null,
    val assembledSegmentCount: Int = 0,
    val statusDetail: String = "No synthesized Bengali track loaded"
)

enum class AutoDubStage(val stageNumber: Int, val label: String) {
    IDLE(0, "Ready"),
    TRANSCRIBING(1, "Extracting audio & transcribing English (whisper.cpp)..."),
    DIARIZING(2, "Detecting speakers & mapping characters (Sherpa-ONNX)..."),
    TRANSLATING(3, "Translating dialogue to Bengali (ML Kit)..."),
    SYNTHESIZING_BENGALI(4, "Generating offline Bengali character voices (VITS)..."),
    ASSEMBLING_LIVE_TRACK(4, "Assembling synchronized Bengali dub track..."),
    COMPLETED(4, "Bengali dubbing ready & active!"),
    ERROR(0, "Auto-Dub failed")
}

data class AutoDubPipelineState(
    val isRunning: Boolean = false,
    val currentStage: AutoDubStage = AutoDubStage.IDLE,
    val stageIndex: Int = 0,
    val totalStages: Int = 4,
    val stageLabel: String = "Ready",
    val stageProgressFraction: Float = 0f,
    val errorMessage: String? = null
) {
    val overallProgressFraction: Float
        get() = when {
            currentStage == AutoDubStage.COMPLETED -> 1f
            !isRunning || totalStages <= 0 -> 0f
            else -> (((stageIndex - 1).coerceAtLeast(0) + stageProgressFraction.coerceIn(0f, 1f)) / totalStages.toFloat())
                .coerceIn(0.01f, 0.99f)
        }
}

data class DubbedExportState(
    val isExporting: Boolean = false,
    val activeFormat: DubbingExportFormat? = null,
    val progressFraction: Float = 0f,
    val statusMessage: String = "Idle",
    val exportedUriString: String? = null,
    val exportedDisplayName: String? = null,
    val exportedLocationLabel: String? = null,
    val exportedMimeType: String? = null,
    val errorMessage: String? = null
) {
    val hasCompletedExport: Boolean
        get() = !isExporting && !exportedUriString.isNullOrBlank() && errorMessage.isNullOrBlank()
}
