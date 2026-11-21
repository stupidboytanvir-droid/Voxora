package com.voxora.player.ui.viewmodel

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.ai.subtitles.AutoSubtitleManager
import com.voxora.player.ai.subtitles.AutoSubtitleUiState
import com.voxora.player.ai.subtitles.DialogueSearchResult
import com.voxora.player.data.repository.LibraryOrganizationRepository
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.model.ABLoopState
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.DisplayFilterSettings
import com.voxora.player.model.SceneBookmark
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VideoItem
import com.voxora.player.player.BackgroundAudioPlaybackService
import com.voxora.player.player.PlayerPlaybackState
import com.voxora.player.player.VoxoraPlayerEngine
import com.voxora.player.util.MediaToolkit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SleepTimerUiState(
    val selectedOption: SleepTimerOption = SleepTimerOption.OFF,
    val remainingSeconds: Int = 0
) {
    val isActive: Boolean
        get() = selectedOption != SleepTimerOption.OFF

    fun badgeText(): String = when (selectedOption) {
        SleepTimerOption.OFF -> ""
        SleepTimerOption.END_OF_VIDEO -> "End"
        else -> {
            val mins = remainingSeconds / 60
            val secs = remainingSeconds % 60
            if (mins > 0) "${mins}m" else "${secs}s"
        }
    }
}

data class UpNextUiState(
    val nextVideo: VideoItem? = null,
    val remainingSeconds: Int = 0
) {
    val isVisible: Boolean
        get() = nextVideo != null && remainingSeconds > 0
}

data class PlayerScreenUiState(
    val currentVideo: VideoItem? = null,
    val videoTitle: String = "",
    val isNetworkStream: Boolean = false,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val isLocked: Boolean = false,
    val isKidsLockActive: Boolean = false,
    val isFullScreen: Boolean = true,
    val isBackgroundPlayActive: Boolean = false,
    val resumedFromPositionMs: Long? = null,
    val activeSubtitleText: String? = null,
    val activeSecondarySubtitleText: String? = null,
    val isHoldSpeedActive: Boolean = false,
    val activeHoldSpeed: Float = 2.0f,
    val abLoop: ABLoopState = ABLoopState(),
    val displayFilters: DisplayFilterSettings = DisplayFilterSettings(),
    val isClipCutting: Boolean = false,
    val isExtractingAudio: Boolean = false,
    val isExportingSrt: Boolean = false,
    val fileMissingError: String? = null,
    val feedbackMessage: String? = null
)

/**
 * Flagship PlayerViewModel for VOXORA v2.4.0.
 *
 * - AI Audio Dubbing remains completely disabled ("Coming Soon").
 * - VAD-trimmed Live Auto AI Subtitles + Dual Subtitles + .SRT Export + Dialogue Search.
 * - 200% Volume Booster + 5-Band Equalizer presets via [VoxoraPlayerEngine.audioFxController].
 * - Background Play (Audio Mode) foreground service, A-B Loop, Up Next 5s auto-next,
 *   Eye Care / Brightness / Contrast / Saturation / Mirror / 90° Rotate, Frame-by-Frame step,
 *   Scene Bookmarks, Screenshot, Lossless Clip Cutter, Extract Audio, and Kids Lock.
 */
class PlayerViewModel(
    private val app: VoxoraApplication,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val organizationRepository: LibraryOrganizationRepository,
    private val settingsRepository: SettingsRepository,
    private val autoSubtitleManager: AutoSubtitleManager
) : ViewModel() {

    val playerEngine = VoxoraPlayerEngine(app)
    val playbackState: StateFlow<PlayerPlaybackState> = playerEngine.state
    val autoSubtitleState: StateFlow<AutoSubtitleUiState> = autoSubtitleManager.state

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    private val _uiState = MutableStateFlow(PlayerScreenUiState())
    val uiState: StateFlow<PlayerScreenUiState> = _uiState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerUiState())
    val sleepTimerState: StateFlow<SleepTimerUiState> = _sleepTimerState.asStateFlow()

    private val _upNextState = MutableStateFlow(UpNextUiState())
    val upNextState: StateFlow<UpNextUiState> = _upNextState.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<SceneBookmark>>(emptyList())
    val bookmarks: StateFlow<List<SceneBookmark>> = _bookmarks.asStateFlow()

    private var activeUriString: String? = null
    private var lastSavedPositionMs: Long = -1L
    private var preHoldPlaybackSpeed: Float = 1.0f

    private var activePlaybackLoopJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var upNextJob: Job? = null
    private var bookmarksJob: Job? = null

    init {
        playerEngine.setOriginalAudioVolume(1.0f)

        // Start/stop 80ms frame-synced subtitle, A-B loop & position polling STRICTLY while playing
        viewModelScope.launch {
            playbackState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                    if (isPlaying) {
                        startActivePlaybackLoop()
                        resumeSleepTimerIfNeeded()
                    } else {
                        stopActivePlaybackLoop()
                        autoSubtitleManager.onPlaybackStateUpdated(
                            positionMs = playbackState.value.currentPositionMs,
                            isPlaying = false
                        )
                        pauseSleepTimerCountdown()
                        refreshSubtitleCueNow()
                    }
                }
        }

        // Apply saved Volume Boost + EQ presets whenever audioSessionId or preferences change
        viewModelScope.launch {
            playbackState
                .map { it.audioSessionId }
                .distinctUntilChanged()
                .collect { sid ->
                    if (sid > 0) {
                        val prefs = preferences.value
                        playerEngine.applyVolumeBoost(prefs.volumeBoostPercent)
                        playerEngine.applyEqPreset(prefs.audioEqPreset)
                    }
                }
        }

        // Propagate detected media duration to AutoSubtitleManager
        viewModelScope.launch {
            playbackState
                .map { it.durationMs }
                .distinctUntilChanged()
                .collect { dur ->
                    if (dur > 0L) autoSubtitleManager.updateDuration(dur)
                }
        }

        // Sync active embedded audio track ISO language code to Whisper for higher accuracy
        viewModelScope.launch {
            playbackState
                .map { it.activeAudioLanguageCode }
                .distinctUntilChanged()
                .collect { langCode ->
                    autoSubtitleManager.setAudioTrackLanguageHint(langCode)
                }
        }

        // Handle Sleep Timer "End of Video" and 5-second cancelable "Up Next" auto-play
        viewModelScope.launch {
            playbackState
                .map { it.isEnded }
                .distinctUntilChanged()
                .collect { ended ->
                    if (!ended) {
                        cancelUpNext()
                        return@collect
                    }
                    if (_sleepTimerState.value.selectedOption == SleepTimerOption.END_OF_VIDEO) {
                        clearSleepTimer()
                        playerEngine.pause()
                        _uiState.value = _uiState.value.copy(
                            feedbackMessage = "Sleep timer paused playback at end of video"
                        )
                        return@collect
                    }
                    startUpNextCountdownIfAvailable()
                }
        }
    }

    private fun startActivePlaybackLoop() {
        if (activePlaybackLoopJob?.isActive == true) return
        activePlaybackLoopJob = viewModelScope.launch {
            var tickCounter = 0
            while (isActive && playbackState.value.isPlaying) {
                playerEngine.pollPosition()
                val pos = playbackState.value.currentPositionMs

                // Seamless A-B Loop check
                val ab = _uiState.value.abLoop
                if (ab.isLooping && ab.pointBMs != null && ab.pointAMs != null && pos >= ab.pointBMs) {
                    seekTo(ab.pointAMs)
                } else {
                    autoSubtitleManager.onPlaybackStateUpdated(positionMs = pos, isPlaying = true)
                    refreshSubtitleCueNow()
                }

                tickCounter++
                if (tickCounter >= 40) {
                    tickCounter = 0
                    persistProgressIfChanged()
                }
                delay(SUBTITLE_SYNC_POLL_MS)
            }
        }
    }

    private fun stopActivePlaybackLoop() {
        activePlaybackLoopJob?.cancel()
        activePlaybackLoopJob = null
    }

    private fun refreshSubtitleCueNow() {
        val state = playbackState.value
        val (primaryText, secondaryText) = if (autoSubtitleState.value.isEnabled) {
            val dual = autoSubtitleManager.findActiveDualCue(
                playbackPositionMs = state.currentPositionMs,
                syncOffsetMs = state.subtitleSyncOffsetMs
            )
            dual.primaryText to dual.secondaryText
        } else {
            playerEngine.getCurrentSubtitleText() to null
        }
        val cur = _uiState.value
        if (primaryText != cur.activeSubtitleText || secondaryText != cur.activeSecondarySubtitleText) {
            _uiState.value = cur.copy(
                activeSubtitleText = primaryText,
                activeSecondarySubtitleText = secondaryText
            )
        }
    }

    fun initializeMedia(
        uriString: String,
        fallbackTitle: String,
        explicitStartMs: Long = -1L
    ) {
        if (activeUriString == uriString && _uiState.value.currentVideo != null) {
            return
        }
        activeUriString = uriString
        lastSavedPositionMs = -1L
        cancelUpNext()
        autoSubtitleManager.pauseProcessing()

        // Observe Scene Bookmarks for this video URI
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch {
            organizationRepository.observeBookmarksForVideo(uriString).collect { list ->
                _bookmarks.value = list
            }
        }

        viewModelScope.launch {
            val prefs = settingsRepository.preferencesFlow.first()
            val isNetwork = VoxoraPlayerEngine.isNetworkStreamUri(uriString)
            if (!isNetwork) {
                val accessible = mediaRepository.isVideoAccessible(uriString)
                if (!accessible) {
                    _uiState.value = PlayerScreenUiState(
                        videoTitle = fallbackTitle,
                        aspectRatioMode = prefs.defaultAspectRatio,
                        fileMissingError = "This video file could not be opened. It may have been moved, deleted, or storage permission was revoked."
                    )
                    return@launch
                }
            }

            val resolvedVideo = if (isNetwork) {
                VideoItem(
                    id = uriString.hashCode().toLong(),
                    uriString = uriString,
                    title = fallbackTitle.ifBlank { Uri.parse(uriString).lastPathSegment ?: "Network Stream" },
                    displayName = fallbackTitle.ifBlank { "stream" },
                    durationMs = 0L,
                    sizeBytes = 0L,
                    dateModifiedSec = System.currentTimeMillis() / 1000L,
                    folderId = "network_stream",
                    folderName = "Network Stream",
                    relativePath = "",
                    mimeType = "video/*",
                    isFromSaf = true
                )
            } else {
                mediaRepository.findVideoByUri(uriString)
                    ?: mediaRepository.addSafVideo(Uri.parse(uriString))
            }

            val history = historyRepository.getHistoryForUri(uriString)
            val resumeMs = when {
                explicitStartMs >= 0L -> explicitStartMs
                prefs.resumePlaybackEnabled && history != null && history.isContinueWatching ->
                    history.lastPositionMs
                else -> 0L
            }
            val speed = history?.preferredSpeed ?: prefs.defaultPlaybackSpeed
            val syncOffset = history?.subtitleSyncOffsetMs ?: prefs.subtitleDefaultSyncOffsetMs

            _uiState.value = PlayerScreenUiState(
                currentVideo = resolvedVideo,
                videoTitle = resolvedVideo.title.ifBlank { fallbackTitle },
                isNetworkStream = isNetwork,
                aspectRatioMode = prefs.defaultAspectRatio,
                isLocked = false,
                isKidsLockActive = false,
                isFullScreen = true,
                resumedFromPositionMs = resumeMs.takeIf { it > 2_000L },
                displayFilters = DisplayFilterSettings(eyeCareEnabled = prefs.eyeCareEnabled)
            )

            autoSubtitleManager.attachMedia(
                mediaUriString = uriString,
                durationMs = resolvedVideo.durationMs,
                preferredTargetLanguageCode = prefs.autoSubtitleTargetLanguageCode,
                dualEnabled = prefs.dualSubtitlesEnabled,
                secondaryLanguageCode = prefs.secondarySubtitleLanguageCode
            )

            playerEngine.setOriginalAudioVolume(1.0f)
            playerEngine.setSubtitleSyncOffset(syncOffset)
            playerEngine.prepareMedia(
                uriString = uriString,
                startPositionMs = resumeMs,
                playbackSpeed = speed,
                autoPlay = true
            )
            playerEngine.applyVolumeBoost(prefs.volumeBoostPercent)
            playerEngine.applyEqPreset(prefs.audioEqPreset)

            if (!isNetwork && !history?.externalSubtitleUri.isNullOrBlank()) {
                val subUri = Uri.parse(history!!.externalSubtitleUri!!)
                mediaRepository.readSubtitleContent(subUri).onSuccess { (name, content) ->
                    playerEngine.loadExternalSubtitle(
                        uriString = history.externalSubtitleUri,
                        name = history.externalSubtitleName ?: name,
                        rawContent = content,
                        syncOffsetMs = syncOffset
                    )
                }
            }
        }
    }

    // --- Playback, Up Next & Background Audio ---

    fun startFromBeginning() {
        seekTo(0L)
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun dismissResumeBanner() {
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun togglePlayPause() {
        cancelUpNext()
        playerEngine.togglePlayPause()
    }

    fun pausePlayback() {
        if (_uiState.value.isHoldSpeedActive) endHoldSpeed()
        playerEngine.pause()
        stopActivePlaybackLoop()
        autoSubtitleManager.pauseProcessing()
        pauseSleepTimerCountdown()
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    fun onActivityBackgrounded(isInPipMode: Boolean) {
        if (isInPipMode || _uiState.value.isBackgroundPlayActive) {
            // Keep audio playing seamlessly in PiP or Background Audio mode
            autoSubtitleManager.pauseProcessing()
            viewModelScope.launch { persistProgressIfChanged(force = true) }
            return
        }
        pausePlayback()
    }

    fun startBackgroundAudioMode() {
        val title = _uiState.value.videoTitle.ifBlank { "VOXORA Media" }
        BackgroundAudioPlaybackService.start(app, playerEngine, title)
        if (!playbackState.value.isPlaying) {
            playerEngine.play()
        }
        _uiState.value = _uiState.value.copy(
            isBackgroundPlayActive = true,
            feedbackMessage = "Background Audio Mode active — audio keeps playing when you leave"
        )
    }

    fun stopBackgroundAudioMode() {
        BackgroundAudioPlaybackService.stop(app)
        _uiState.value = _uiState.value.copy(
            isBackgroundPlayActive = false,
            feedbackMessage = "Background Audio Mode turned off"
        )
    }

    fun toggleBackgroundAudioMode() {
        if (_uiState.value.isBackgroundPlayActive) {
            stopBackgroundAudioMode()
        } else {
            startBackgroundAudioMode()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelUpNext()
        playerEngine.seekTo(positionMs)
        autoSubtitleManager.onSeekTo(positionMs)
        refreshSubtitleCueNow()
    }

    fun seekBySeconds(seconds: Int) {
        val targetMs = (playerEngine.state.value.currentPositionMs + seconds * 1_000L).coerceAtLeast(0L)
        seekTo(targetMs)
    }

    fun stepFrameForward() {
        playerEngine.stepFrame(forward = true)
        refreshSubtitleCueNow()
    }

    fun stepFrameBackward() {
        playerEngine.stepFrame(forward = false)
        refreshSubtitleCueNow()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        preHoldPlaybackSpeed = clamped
        playerEngine.setPlaybackSpeed(clamped)
        val uri = activeUriString ?: return
        viewModelScope.launch { historyRepository.savePreferredSpeed(uri, clamped) }
    }

    fun toggleDecoderMode() {
        val isSw = playerEngine.toggleDecoderMode()
        val prefs = preferences.value
        playerEngine.applyVolumeBoost(prefs.volumeBoostPercent)
        playerEngine.applyEqPreset(prefs.audioEqPreset)
        _uiState.value = _uiState.value.copy(
            feedbackMessage = if (isSw) {
                "Decoder switched to SW (Software)"
            } else {
                "Decoder switched to HW (Hardware)"
            }
        )
    }

    // --- Press & Hold Fast-Forward (2.0x / 3.0x) ---

    fun beginHoldSpeed(speedOverride: Float? = null) {
        if (_uiState.value.isHoldSpeedActive) return
        preHoldPlaybackSpeed = playbackState.value.playbackSpeed
        val target = (speedOverride ?: preferences.value.holdSpeedMultiplier).coerceIn(2.0f, 3.0f)
        playerEngine.setPlaybackSpeed(target)
        _uiState.value = _uiState.value.copy(
            isHoldSpeedActive = true,
            activeHoldSpeed = target
        )
    }

    fun updateHoldSpeed(speed: Float) {
        if (!_uiState.value.isHoldSpeedActive) return
        val clamped = speed.coerceIn(2.0f, 3.0f)
        if (kotlin.math.abs(_uiState.value.activeHoldSpeed - clamped) < 0.05f) return
        playerEngine.setPlaybackSpeed(clamped)
        _uiState.value = _uiState.value.copy(activeHoldSpeed = clamped)
    }

    fun endHoldSpeed() {
        if (!_uiState.value.isHoldSpeedActive) return
        val restore = preHoldPlaybackSpeed.coerceIn(0.25f, 3.0f)
        playerEngine.setPlaybackSpeed(restore)
        _uiState.value = _uiState.value.copy(isHoldSpeedActive = false)
    }

    fun setHoldSpeedMultiplier(multiplier: Float) {
        viewModelScope.launch { settingsRepository.setHoldSpeedMultiplier(multiplier) }
    }

    // --- A-B Loop (Repeat Segment) ---

    fun setLoopPointA() {
        val pos = playbackState.value.currentPositionMs.coerceAtLeast(0L)
        val currentB = _uiState.value.abLoop.pointBMs?.takeIf { it > pos + 300L }
        _uiState.value = _uiState.value.copy(
            abLoop = ABLoopState(pointAMs = pos, pointBMs = currentB),
            feedbackMessage = "Loop Point A set"
        )
    }

    fun setLoopPointB() {
        val pos = playbackState.value.currentPositionMs.coerceAtLeast(0L)
        val pointA = _uiState.value.abLoop.pointAMs ?: 0L
        if (pos <= pointA + 300L) {
            _uiState.value = _uiState.value.copy(
                feedbackMessage = "Point B must be after Point A"
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            abLoop = ABLoopState(pointAMs = pointA, pointBMs = pos),
            feedbackMessage = "A–B Loop active"
        )
        seekTo(pointA)
    }

    fun clearABLoop() {
        _uiState.value = _uiState.value.copy(
            abLoop = ABLoopState(),
            feedbackMessage = "A–B Loop cleared"
        )
    }

    // --- Audio FX (200% Volume Booster + Equalizer Presets) ---

    fun setVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        playerEngine.applyVolumeBoost(clamped)
        viewModelScope.launch { settingsRepository.setVolumeBoostPercent(clamped) }
    }

    fun setAudioEqPreset(preset: AudioEqPreset) {
        playerEngine.applyEqPreset(preset)
        viewModelScope.launch { settingsRepository.setAudioEqPreset(preset) }
    }

    // --- Visual & Display Controls ---

    fun setEyeCareEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            displayFilters = _uiState.value.displayFilters.copy(eyeCareEnabled = enabled)
        )
        viewModelScope.launch { settingsRepository.setEyeCareEnabled(enabled) }
    }

    fun setVideoBrightnessOffset(offset: Float) {
        _uiState.value = _uiState.value.copy(
            displayFilters = _uiState.value.displayFilters.copy(
                brightnessOffset = offset.coerceIn(-0.5f, 0.5f)
            )
        )
    }

    fun setVideoContrast(contrast: Float) {
        _uiState.value = _uiState.value.copy(
            displayFilters = _uiState.value.displayFilters.copy(
                contrast = contrast.coerceIn(0.5f, 1.8f)
            )
        )
    }

    fun setVideoSaturation(saturation: Float) {
        _uiState.value = _uiState.value.copy(
            displayFilters = _uiState.value.displayFilters.copy(
                saturation = saturation.coerceIn(0f, 2.0f)
            )
        )
    }

    fun toggleVideoMirror() {
        val cur = _uiState.value.displayFilters
        _uiState.value = _uiState.value.copy(
            displayFilters = cur.copy(isMirrored = !cur.isMirrored),
            feedbackMessage = if (!cur.isMirrored) "Horizontal Mirror ON" else "Horizontal Mirror OFF"
        )
    }

    fun rotateVideo90() {
        val cur = _uiState.value.displayFilters
        val nextDeg = (cur.manualRotationDegrees + 90) % 360
        _uiState.value = _uiState.value.copy(
            displayFilters = cur.copy(manualRotationDegrees = nextDeg),
            feedbackMessage = "Video rotated ${nextDeg}°"
        )
    }

    fun resetDisplayFilters() {
        _uiState.value = _uiState.value.copy(
            displayFilters = DisplayFilterSettings(),
            feedbackMessage = "Display settings reset"
        )
        viewModelScope.launch { settingsRepository.setEyeCareEnabled(false) }
    }

    // --- Scene Bookmarks ---

    fun addSceneBookmark(title: String) {
        val uri = activeUriString ?: return
        val pos = playbackState.value.currentPositionMs.coerceAtLeast(0L)
        viewModelScope.launch {
            organizationRepository.addBookmark(uri, title, pos)
            _uiState.value = _uiState.value.copy(feedbackMessage = "Bookmark saved")
        }
    }

    fun deleteSceneBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            organizationRepository.deleteBookmark(bookmarkId)
        }
    }

    // --- Pro AI Subtitles (VAD, Dual, Export .SRT, Dialogue Search) ---

    fun toggleAutoSubtitles(enabled: Boolean) {
        if (enabled) {
            playerEngine.disableSubtitles()
            autoSubtitleManager.enableAndPrepare(
                currentPositionMs = playbackState.value.currentPositionMs,
                isPlayingNow = playbackState.value.isPlaying
            )
        } else {
            autoSubtitleManager.disable()
            refreshSubtitleCueNow()
        }
    }

    fun setAutoSubtitleTargetLanguage(languageCode: String) {
        autoSubtitleManager.setTargetLanguage(languageCode)
        viewModelScope.launch { settingsRepository.setAutoSubtitleTargetLanguageCode(languageCode) }
    }

    fun setDualSubtitlesEnabled(enabled: Boolean) {
        val secCode = preferences.value.secondarySubtitleLanguageCode
        autoSubtitleManager.setDualSubtitlesConfig(enabled, secCode)
        viewModelScope.launch { settingsRepository.setDualSubtitlesEnabled(enabled) }
    }

    fun setSecondarySubtitleLanguage(languageCode: String) {
        val dualEnabled = preferences.value.dualSubtitlesEnabled
        autoSubtitleManager.setDualSubtitlesConfig(dualEnabled, languageCode)
        viewModelScope.launch { settingsRepository.setSecondarySubtitleLanguageCode(languageCode) }
    }

    fun setSecondarySubtitleTextColor(color: SubtitleTextColor) {
        viewModelScope.launch { settingsRepository.setSecondarySubtitleTextColor(color) }
    }

    fun setSecondarySubtitleFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSecondarySubtitleFontSizeSp(sizeSp) }
    }

    fun searchSubtitleDialogue(query: String): List<DialogueSearchResult> {
        return autoSubtitleManager.searchDialogue(query)
    }

    fun exportAutoSubtitlesToSrt() {
        val title = _uiState.value.videoTitle.ifBlank { "VOXORA" }
        if (_uiState.value.isExportingSrt) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExportingSrt = true)
            autoSubtitleManager.exportSubtitlesToSrt(title).fold(
                onSuccess = { path ->
                    _uiState.value = _uiState.value.copy(
                        isExportingSrt = false,
                        feedbackMessage = "Saved .SRT: $path"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isExportingSrt = false,
                        feedbackMessage = err.message ?: "Failed to export .SRT"
                    )
                }
            )
        }
    }

    // --- Quick Screenshot, Lossless Clip Cutter & Video-to-Audio ---

    fun captureScreenshot(preCapturedBitmap: Bitmap? = null) {
        val uri = activeUriString ?: return
        val title = _uiState.value.videoTitle.ifBlank { "VOXORA" }
        val pos = playbackState.value.currentPositionMs
        viewModelScope.launch {
            MediaToolkit.captureVideoScreenshot(
                context = app,
                mediaUriString = uri,
                videoTitle = title,
                positionMs = pos,
                preCapturedBitmap = preCapturedBitmap
            ).fold(
                onSuccess = { path ->
                    _uiState.value = _uiState.value.copy(feedbackMessage = "Screenshot saved: $path")
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        feedbackMessage = err.message ?: "Could not capture screenshot"
                    )
                }
            )
        }
    }

    fun cutAndSaveClip(startMs: Long, endMs: Long) {
        val uri = activeUriString ?: return
        val title = _uiState.value.videoTitle.ifBlank { "VOXORA" }
        if (_uiState.value.isClipCutting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClipCutting = true)
            MediaToolkit.trimVideoLossless(app, uri, title, startMs, endMs).fold(
                onSuccess = { path ->
                    _uiState.value = _uiState.value.copy(
                        isClipCutting = false,
                        feedbackMessage = "Clip saved: $path"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isClipCutting = false,
                        feedbackMessage = err.message ?: "Clip cut failed"
                    )
                }
            )
        }
    }

    fun extractAudioToMusic() {
        val uri = activeUriString ?: return
        val title = _uiState.value.videoTitle.ifBlank { "VOXORA" }
        if (_uiState.value.isExtractingAudio) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExtractingAudio = true)
            MediaToolkit.extractAudioTrack(app, uri, title).fold(
                onSuccess = { path ->
                    _uiState.value = _uiState.value.copy(
                        isExtractingAudio = false,
                        feedbackMessage = "Audio extracted: $path"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isExtractingAudio = false,
                        feedbackMessage = err.message ?: "Audio extraction failed"
                    )
                }
            )
        }
    }

    // --- Sleep Timer ---

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        when (option) {
            SleepTimerOption.OFF -> {
                _sleepTimerState.value = SleepTimerUiState()
                _uiState.value = _uiState.value.copy(feedbackMessage = "Sleep timer turned off")
            }
            SleepTimerOption.END_OF_VIDEO -> {
                _sleepTimerState.value = SleepTimerUiState(
                    selectedOption = SleepTimerOption.END_OF_VIDEO,
                    remainingSeconds = 0
                )
                _uiState.value = _uiState.value.copy(feedbackMessage = "Sleep timer set for End of Video")
            }
            else -> {
                val totalSec = option.durationMinutes * 60
                _sleepTimerState.value = SleepTimerUiState(
                    selectedOption = option,
                    remainingSeconds = totalSec
                )
                _uiState.value = _uiState.value.copy(feedbackMessage = "Sleep timer set for ${option.label}")
                if (playbackState.value.isPlaying) {
                    startSleepTimerCountdown()
                }
            }
        }
    }

    private fun resumeSleepTimerIfNeeded() {
        val current = _sleepTimerState.value
        if (current.isActive &&
            current.selectedOption != SleepTimerOption.END_OF_VIDEO &&
            current.remainingSeconds > 0 &&
            sleepTimerJob?.isActive != true
        ) {
            startSleepTimerCountdown()
        }
    }

    private fun pauseSleepTimerCountdown() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun startSleepTimerCountdown() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            while (isActive && playbackState.value.isPlaying) {
                val rem = _sleepTimerState.value.remainingSeconds
                if (rem <= 0) {
                    clearSleepTimer()
                    playerEngine.pause()
                    _uiState.value = _uiState.value.copy(
                        feedbackMessage = "Sleep timer finished · Playback paused"
                    )
                    break
                }
                delay(1_000L)
                val next = (_sleepTimerState.value.remainingSeconds - 1).coerceAtLeast(0)
                _sleepTimerState.value = _sleepTimerState.value.copy(remainingSeconds = next)
            }
        }
    }

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerUiState()
    }

    // --- Up Next 5s Auto-Play ---

    private fun findNextVideo(): VideoItem? {
        val prefs = preferences.value
        if (!prefs.autoPlayNext) return null
        val current = _uiState.value.currentVideo ?: return null
        if (_uiState.value.isNetworkStream) return null

        val allSorted = mediaRepository.sortVideos(
            mediaRepository.libraryState.value.videos,
            prefs.sortOption
        )
        // Prefer next video within the same folder first, then fall back to library order
        val folderVideos = allSorted.filter { it.folderId == current.folderId }
        val folderIdx = folderVideos.indexOfFirst { it.uriString == current.uriString }
        if (folderIdx in 0 until folderVideos.lastIndex) {
            return folderVideos[folderIdx + 1]
        }
        val libIdx = allSorted.indexOfFirst { it.uriString == current.uriString }
        return if (libIdx in 0 until allSorted.lastIndex) allSorted[libIdx + 1] else null
    }

    private fun startUpNextCountdownIfAvailable() {
        val next = findNextVideo() ?: return
        upNextJob?.cancel()
        upNextJob = viewModelScope.launch {
            for (sec in 5 downTo 1) {
                _upNextState.value = UpNextUiState(nextVideo = next, remainingSeconds = sec)
                delay(1_000L)
            }
            _upNextState.value = UpNextUiState()
            activeUriString = null
            initializeMedia(next.uriString, next.title, explicitStartMs = 0L)
        }
    }

    fun playUpNextNow() {
        val next = _upNextState.value.nextVideo ?: return
        cancelUpNext()
        activeUriString = null
        initializeMedia(next.uriString, next.title, explicitStartMs = 0L)
    }

    fun cancelUpNext() {
        upNextJob?.cancel()
        upNextJob = null
        if (_upNextState.value.nextVideo != null) {
            _upNextState.value = UpNextUiState()
        }
    }

    // --- Tracks, Aspect Ratio, Screen Lock & Kids Lock ---

    fun setAspectRatio(mode: AspectRatioMode) {
        _uiState.value = _uiState.value.copy(aspectRatioMode = mode)
    }

    fun toggleScreenLock() {
        val cur = _uiState.value
        if (cur.isKidsLockActive) return
        _uiState.value = cur.copy(isLocked = !cur.isLocked)
    }

    fun enableKidsLock() {
        _uiState.value = _uiState.value.copy(
            isLocked = true,
            isKidsLockActive = true,
            feedbackMessage = "Kids Lock enabled · Hold corner lock 3s to unlock"
        )
    }

    fun unlockKidsLock() {
        _uiState.value = _uiState.value.copy(
            isLocked = false,
            isKidsLockActive = false,
            feedbackMessage = "Kids Lock unlocked"
        )
    }

    fun toggleFullScreen() {
        _uiState.value = _uiState.value.copy(isFullScreen = !_uiState.value.isFullScreen)
    }

    fun selectAudioTrack(track: AudioTrackInfo) = playerEngine.selectAudioTrack(track)

    fun selectSubtitleTrack(track: SubtitleTrackInfo) {
        if (autoSubtitleState.value.isEnabled) autoSubtitleManager.disable()
        playerEngine.selectEmbeddedSubtitle(track)
        refreshSubtitleCueNow()
    }

    fun disableSubtitles() {
        if (autoSubtitleState.value.isEnabled) autoSubtitleManager.disable()
        playerEngine.disableSubtitles()
        refreshSubtitleCueNow()
    }

    fun loadExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            mediaRepository.readSubtitleContent(uri).fold(
                onSuccess = { (name, content) ->
                    if (autoSubtitleState.value.isEnabled) autoSubtitleManager.disable()
                    val sync = playerEngine.state.value.subtitleSyncOffsetMs
                    val loaded = playerEngine.loadExternalSubtitle(
                        uriString = uri.toString(),
                        name = name,
                        rawContent = content,
                        syncOffsetMs = sync
                    )
                    if (loaded) {
                        persistProgressIfChanged(force = true)
                        activeUriString?.let { videoUri ->
                            historyRepository.saveExternalSubtitle(
                                uriString = videoUri,
                                subtitleUri = uri.toString(),
                                subtitleName = name,
                                syncOffsetMs = sync
                            )
                        }
                        refreshSubtitleCueNow()
                        _uiState.value = _uiState.value.copy(feedbackMessage = "Loaded subtitle: $name")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            feedbackMessage = "No valid SRT or VTT cues found in $name"
                        )
                    }
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        feedbackMessage = "Unable to read the selected subtitle file."
                    )
                }
            )
        }
    }

    fun showFeedbackMessage(message: String) {
        _uiState.value = _uiState.value.copy(feedbackMessage = message)
    }

    fun clearFeedbackMessage() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null)
    }

    fun adjustSubtitleSync(deltaMs: Long) {
        val next = (playerEngine.state.value.subtitleSyncOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
        setSubtitleSync(next)
    }

    fun setSubtitleSync(offsetMs: Long) {
        playerEngine.setSubtitleSyncOffset(offsetMs)
        refreshSubtitleCueNow()
        val uri = activeUriString ?: return
        viewModelScope.launch { historyRepository.saveSubtitleSyncOffset(uri, offsetMs) }
    }

    fun setSubtitleFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleFontSizeSp(sizeSp) }
    }

    fun setSubtitleVerticalPosition(fraction: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleVerticalPositionFraction(fraction) }
    }

    fun setSubtitleTextColor(color: SubtitleTextColor) {
        viewModelScope.launch { settingsRepository.setSubtitleTextColor(color) }
    }

    fun setSubtitleBackgroundOpacity(opacity: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBackgroundOpacity(opacity) }
    }

    fun retryLoad() {
        val uri = activeUriString ?: return
        val title = _uiState.value.videoTitle
        activeUriString = null
        initializeMedia(uri, title)
    }

    fun persistProgressSync() {
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    private suspend fun persistProgressIfChanged(force: Boolean = false) {
        val video = _uiState.value.currentVideo ?: return
        val s = playerEngine.state.value
        if (s.errorMessage != null) return
        val pos = s.currentPositionMs
        if (!force && pos == lastSavedPositionMs) return
        if (pos <= 0L && !force) return
        lastSavedPositionMs = pos
        historyRepository.recordOrUpdateProgress(
            video = video,
            positionMs = pos,
            durationMs = s.durationMs,
            speed = if (_uiState.value.isHoldSpeedActive) preHoldPlaybackSpeed else s.playbackSpeed
        )
    }

    override fun onCleared() {
        stopActivePlaybackLoop()
        sleepTimerJob?.cancel()
        upNextJob?.cancel()
        bookmarksJob?.cancel()
        autoSubtitleManager.pauseProcessing()
        BackgroundAudioPlaybackService.stop(app)
        playerEngine.release()
        super.onCleared()
    }

    companion object {
        private const val SUBTITLE_SYNC_POLL_MS = 80L
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(
                app = app,
                mediaRepository = app.mediaRepository,
                historyRepository = app.historyRepository,
                organizationRepository = app.libraryOrganizationRepository,
                settingsRepository = app.settingsRepository,
                autoSubtitleManager = app.autoSubtitleManager
            ) as T
        }
    }
}
