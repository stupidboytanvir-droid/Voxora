package com.voxora.player.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.local.power.BatteryMonitor
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.BatterySaverEvaluation
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.BatterySavingPolicy
import com.voxora.player.model.BatteryStatus
import com.voxora.player.model.FolderViewMode
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.ThemeMode
import com.voxora.player.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val mediaRepository: MediaRepository,
    private val batteryMonitor: BatteryMonitor,
    private val whisperSpeechEngine: com.voxora.player.dubbing.whisper.WhisperCppSpeechRecognitionEngine
) : ViewModel() {

    private val _isWhisperModelInstalled = MutableStateFlow(whisperSpeechEngine.isModelInstalledOnDisk())
    val isWhisperModelInstalled: StateFlow<Boolean> = _isWhisperModelInstalled.asStateFlow()

    fun refreshOfflineModelStatus() {
        _isWhisperModelInstalled.value = whisperSpeechEngine.isModelInstalledOnDisk()
    }

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    val batteryStatus: StateFlow<BatteryStatus> = batteryMonitor.batteryStatusFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = batteryMonitor.currentBatteryStatus()
        )

    val batterySavingEvaluation: StateFlow<BatterySaverEvaluation> = combine(
        preferences,
        batteryStatus
    ) { prefs, status ->
        BatterySavingPolicy.evaluate(prefs, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BatterySavingPolicy.evaluate(preferences.value, batteryStatus.value)
    )

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(speed) }
    }

    fun setResumePlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setResumePlaybackEnabled(enabled) }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPlayNext(enabled) }
    }

    fun setDefaultAspectRatio(mode: AspectRatioMode) {
        viewModelScope.launch { settingsRepository.setDefaultAspectRatio(mode) }
    }

    fun setBrightnessGestureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBrightnessGestureEnabled(enabled) }
    }

    fun setVolumeGestureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVolumeGestureEnabled(enabled) }
    }

    fun setDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setDoubleTapSeekSeconds(seconds) }
    }

    fun setSubtitleFontSizeSp(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleFontSizeSp(sizeSp) }
    }

    fun setSubtitleBottomPaddingFraction(fraction: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBottomPaddingFraction(fraction) }
    }

    fun setSubtitleVerticalPositionFraction(fraction: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleVerticalPositionFraction(fraction) }
    }

    fun setSubtitleTextColor(color: SubtitleTextColor) {
        viewModelScope.launch { settingsRepository.setSubtitleTextColor(color) }
    }

    fun setHoldSpeedMultiplier(multiplier: Float) {
        viewModelScope.launch { settingsRepository.setHoldSpeedMultiplier(multiplier) }
    }

    fun setSubtitleBackgroundOpacity(opacity: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBackgroundOpacity(opacity) }
    }

    fun setSubtitleDefaultSyncOffsetMs(offsetMs: Long) {
        viewModelScope.launch { settingsRepository.setSubtitleDefaultSyncOffsetMs(offsetMs) }
    }

    fun setMinVideoDurationSec(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setMinVideoDurationSec(seconds)
            mediaRepository.refreshLibrary(
                minDurationSec = seconds,
                showHiddenFolders = preferences.value.showHiddenFolders
            )
        }
    }

    fun setShowHiddenFolders(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowHiddenFolders(show)
            mediaRepository.refreshLibrary(
                minDurationSec = preferences.value.minVideoDurationSec,
                showHiddenFolders = show
            )
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            mediaRepository.refreshLibrary(
                minDurationSec = preferences.value.minVideoDurationSec,
                showHiddenFolders = preferences.value.showHiddenFolders
            )
            _statusBanner.value = "Video library refreshed"
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearAllHistory()
            _statusBanner.value = "Playback history cleared"
        }
    }

    fun setBatterySaverMode(mode: BatterySaverMode) {
        viewModelScope.launch { settingsRepository.setBatterySaverMode(mode) }
    }

    fun setBatterySaverThresholdPercent(percent: Int) {
        viewModelScope.launch { settingsRepository.setBatterySaverThresholdPercent(percent) }
    }

    fun setBatterySaverCapRefreshRate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverCapRefreshRate(enabled) }
    }

    fun setBatterySaverDisableAnimations(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverDisableAnimations(enabled) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(enabled) }
    }

    fun setAutoSubtitleTargetLanguageCode(code: String) {
        viewModelScope.launch { settingsRepository.setAutoSubtitleTargetLanguageCode(code) }
    }

    fun setDualSubtitlesEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDualSubtitlesEnabled(enabled) }
    }

    fun setSecondarySubtitleLanguageCode(code: String) {
        viewModelScope.launch { settingsRepository.setSecondarySubtitleLanguageCode(code) }
    }

    fun setSecondarySubtitleTextColor(color: SubtitleTextColor) {
        viewModelScope.launch { settingsRepository.setSecondarySubtitleTextColor(color) }
    }

    fun setSecondarySubtitleFontSizeSp(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSecondarySubtitleFontSizeSp(sizeSp) }
    }

    fun setVolumeBoostPercent(percent: Int) {
        viewModelScope.launch { settingsRepository.setVolumeBoostPercent(percent) }
    }

    fun setAudioEqPreset(preset: AudioEqPreset) {
        viewModelScope.launch { settingsRepository.setAudioEqPreset(preset) }
    }

    fun setEyeCareEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEyeCareEnabled(enabled) }
    }

    fun setVaultPinSha256(hash: String) {
        viewModelScope.launch { settingsRepository.setVaultPinSha256(hash) }
    }

    fun setVaultBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVaultBiometricEnabled(enabled) }
    }

    fun setFolderViewMode(mode: FolderViewMode) {
        viewModelScope.launch { settingsRepository.setFolderViewMode(mode) }
    }

    fun toggleFolderViewMode() {
        val next = preferences.value.folderViewMode.toggled()
        setFolderViewMode(next)
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository = app.settingsRepository,
                historyRepository = app.historyRepository,
                mediaRepository = app.mediaRepository,
                batteryMonitor = app.batteryMonitor,
                whisperSpeechEngine = app.whisperSpeechEngine
            ) as T
        }
    }
}
