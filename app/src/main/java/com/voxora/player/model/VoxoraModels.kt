package com.voxora.player.model

/**
 * Represents a local video file discovered via MediaStore or opened via Storage Access Framework.
 */
data class VideoItem(
    val id: Long,
    val uriString: String,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val folderId: String,
    val folderName: String,
    val relativePath: String,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "video/*",
    val isFromSaf: Boolean = false
)

/**
 * Represents a device folder grouping local videos.
 */
data class VideoFolder(
    val bucketId: String,
    val name: String,
    val path: String,
    val videoCount: Int,
    val totalSizeBytes: Long,
    val latestTimestampSec: Long,
    val previewUriString: String?
)

enum class SortOption(val label: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    DURATION_DESC("Longest First"),
    SIZE_DESC("Largest First")
}

enum class ThemeMode(val label: String, val description: String) {
    DARK_CHARCOAL("Dark Charcoal", "Signature VOXORA cinematic charcoal"),
    AMOLED_BLACK("AMOLED Black", "Pure pitch black for OLED displays"),
    LIGHT("Light", "Clean studio light appearance")
}

enum class AspectRatioMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ZOOM("Zoom"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    STRETCH("Stretch")
}

data class AudioTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String,
    val channelCount: Int,
    val isSelected: Boolean
)

data class SubtitleTrackInfo(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String,
    val isSelected: Boolean,
    val isExternal: Boolean = false
)

data class SubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

data class PlaybackHistoryItem(
    val uriString: String,
    val mediaStoreId: Long,
    val title: String,
    val displayName: String,
    val folderName: String,
    val durationMs: Long,
    val lastPositionMs: Long,
    val sizeBytes: Long,
    val lastPlayedAtMs: Long,
    val externalSubtitleUri: String? = null,
    val externalSubtitleName: String? = null,
    val subtitleSyncOffsetMs: Long = 0L,
    val preferredSpeed: Float = 1.0f
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val isContinueWatching: Boolean
        get() = lastPositionMs > 2_000L && progressFraction < 0.95f
}

enum class BatterySaverMode(val label: String, val description: String) {
    AUTO("Auto (Low Battery)", "Activates when battery is low or system Battery Saver is enabled"),
    ALWAYS_ON("Always On", "Always reduce refresh rate and non-essential animations in player"),
    OFF("Off", "Keep full refresh rate and animations")
}

data class BatteryStatus(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isSystemPowerSave: Boolean = false
) {
    fun isLowBattery(thresholdPercent: Int): Boolean {
        return !isCharging && batteryPercent <= thresholdPercent
    }
}

data class BatterySaverEvaluation(
    val isActive: Boolean = false,
    val capRefreshRate: Boolean = false,
    val disableAnimations: Boolean = false,
    val reasonLabel: String = "Off"
)

object BatterySavingPolicy {
    fun evaluate(
        preferences: UserPreferences,
        batteryStatus: BatteryStatus
    ): BatterySaverEvaluation {
        val isActive: Boolean
        val reason: String
        when (preferences.batterySaverMode) {
            BatterySaverMode.OFF -> {
                isActive = false
                reason = "Off"
            }
            BatterySaverMode.ALWAYS_ON -> {
                isActive = true
                reason = "Always On"
            }
            BatterySaverMode.AUTO -> {
                val lowBattery = batteryStatus.isLowBattery(preferences.batterySaverThresholdPercent)
                when {
                    batteryStatus.isSystemPowerSave -> {
                        isActive = true
                        reason = "System Battery Saver"
                    }
                    lowBattery -> {
                        isActive = true
                        reason = "Low Battery (${batteryStatus.batteryPercent}%)"
                    }
                    else -> {
                        isActive = false
                        reason = if (batteryStatus.isCharging) {
                            "Charging (${batteryStatus.batteryPercent}%)"
                        } else {
                            "Standby (${batteryStatus.batteryPercent}%)"
                        }
                    }
                }
            }
        }
        return BatterySaverEvaluation(
            isActive = isActive,
            capRefreshRate = isActive && preferences.batterySaverCapRefreshRate,
            disableAnimations = isActive && preferences.batterySaverDisableAnimations,
            reasonLabel = reason
        )
    }
}

enum class SubtitleTextColor(val label: String, val colorHex: Long) {
    WHITE("White", 0xFFFFFFFFL),
    YELLOW("Yellow", 0xFFFACC15L),
    CYAN("Cyan", 0xFF22D3EEL)
}

enum class SleepTimerOption(val label: String, val durationMinutes: Int) {
    OFF("Off", 0),
    MIN_15("15 min", 15),
    MIN_30("30 min", 30),
    MIN_45("45 min", 45),
    MIN_60("60 min", 60),
    END_OF_VIDEO("End of Video", -1)
}

enum class AudioEqPreset(val label: String, val bandOffsetsMb: List<Short>) {
    NORMAL("Normal", listOf(0, 0, 0, 0, 0)),
    DIALOGUE_BOOST("Dialogue Boost", listOf(-200, 100, 450, 500, 200)),
    BASS_BOOST("Bass Boost", listOf(600, 400, 0, 100, 150)),
    CINEMA("Cinema", listOf(350, 150, 250, 300, 400)),
    MUSIC("Music", listOf(300, 100, -100, 250, 400))
}

data class DisplayFilterSettings(
    val eyeCareEnabled: Boolean = false,
    val brightnessOffset: Float = 0f,     // -0.5f .. +0.5f
    val contrast: Float = 1.0f,           // 0.5f .. 1.8f
    val saturation: Float = 1.0f,         // 0.0f .. 2.0f
    val isMirrored: Boolean = false,
    val manualRotationDegrees: Int = 0    // 0, 90, 180, 270
) {
    val hasActiveFilter: Boolean
        get() = eyeCareEnabled ||
            kotlin.math.abs(brightnessOffset) > 0.02f ||
            kotlin.math.abs(contrast - 1.0f) > 0.02f ||
            kotlin.math.abs(saturation - 1.0f) > 0.02f ||
            isMirrored ||
            manualRotationDegrees != 0
}

data class ABLoopState(
    val pointAMs: Long? = null,
    val pointBMs: Long? = null
) {
    val isLooping: Boolean
        get() = pointAMs != null && pointBMs != null && pointBMs > pointAMs
}

data class SceneBookmark(
    val id: Long = 0L,
    val videoUri: String,
    val title: String,
    val timestampMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val isWatchLater: Boolean,
    val videoCount: Int,
    val previewUriString: String?,
    val createdAtMs: Long
)

data class PlaylistEntryItem(
    val id: Long,
    val playlistId: Long,
    val videoUri: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderName: String,
    val addedAtMs: Long
)

data class VaultVideoItem(
    val id: Long = 0L,
    val originalTitle: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtMs: Long = System.currentTimeMillis()
)

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.DARK_CHARCOAL,
    val defaultPlaybackSpeed: Float = 1.0f,
    val holdSpeedMultiplier: Float = 2.0f,
    val resumePlaybackEnabled: Boolean = true,
    val autoPlayNext: Boolean = true,
    val defaultAspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val brightnessGestureEnabled: Boolean = true,
    val volumeGestureEnabled: Boolean = true,
    val doubleTapSeekSeconds: Int = 10,
    val subtitleFontSizeSp: Float = 15f,
    val subtitleBottomPaddingFraction: Float = 0.08f,
    val subtitleVerticalPositionFraction: Float = 0.88f,
    val subtitleTextColor: SubtitleTextColor = SubtitleTextColor.WHITE,
    val subtitleBackgroundOpacity: Float = 0.65f,
    val subtitleDefaultSyncOffsetMs: Long = 0L,
    val autoSubtitleTargetLanguageCode: String = "en",
    val dualSubtitlesEnabled: Boolean = false,
    val secondarySubtitleLanguageCode: String = "bn",
    val secondarySubtitleTextColor: SubtitleTextColor = SubtitleTextColor.YELLOW,
    val secondarySubtitleFontSizeSp: Float = 14f,
    val volumeBoostPercent: Int = 0,
    val audioEqPreset: AudioEqPreset = AudioEqPreset.NORMAL,
    val eyeCareEnabled: Boolean = false,
    val vaultPinSha256: String = "",
    val vaultBiometricEnabled: Boolean = true,
    val minVideoDurationSec: Int = 0,
    val showHiddenFolders: Boolean = false,
    val sortOption: SortOption = SortOption.DATE_DESC,
    val batterySaverMode: BatterySaverMode = BatterySaverMode.AUTO,
    val batterySaverThresholdPercent: Int = 20,
    val batterySaverCapRefreshRate: Boolean = true,
    val batterySaverDisableAnimations: Boolean = true,
    val autoCheckUpdates: Boolean = true
)

enum class MediaPermissionState {
    FULL_ACCESS,
    PARTIAL_ACCESS,
    DENIED
}
