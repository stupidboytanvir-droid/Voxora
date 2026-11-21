package com.voxora.player.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.ai.subtitles.AutoSubtitleManager
import com.voxora.player.ai.subtitles.AutoSubtitleUiState
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VideoItem
import com.voxora.player.player.PlayerPlaybackState
import com.voxora.player.player.VoxoraPlayerEngine
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

data class PlayerScreenUiState(
    val currentVideo: VideoItem? = null,
    val videoTitle: String = "",
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val isLocked: Boolean = false,
    val isFullScreen: Boolean = true,
    val resumedFromPositionMs: Long? = null,
    val activeSubtitleText: String? = null,
    val isHoldSpeedActive: Boolean = false,
    val activeHoldSpeed: Float = 2.0f,
    val fileMissingError: String? = null,
    val feedbackMessage: String? = null
)

/**
 * Streamlined, battery-efficient PlayerViewModel for VOXORA v2.3.0.
 *
 * - AI Audio Dubbing, TTS synthesis, and audio ducking are completely disabled (zero RAM/battery overhead).
 * - Live on-device Auto AI Subtitles run via [AutoSubtitleManager] and sync every 80ms strictly while playing.
 * - All polling loops, sleep countdowns, and background Whisper inference pause immediately on pause/background.
 */
class PlayerViewModel(
    private val app: VoxoraApplication,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
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

    private var activeUriString: String? = null
    private var lastSavedPositionMs: Long = -1L
    private var preHoldPlaybackSpeed: Float = 1.0f

    private var activePlaybackLoopJob: Job? = null
    private var sleepTimerJob: Job? = null

    init {
        // Ensure full original audio volume (no audio ducking ever runs in v2.3.0)
        playerEngine.setOriginalAudioVolume(1.0f)

        // Start/stop 80ms frame-synced subtitle & position loop STRICTLY while playing (zero idle polling!)
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

        // Propagate detected media duration to AutoSubtitleManager
        viewModelScope.launch {
            playbackState
                .map { it.durationMs }
                .distinctUntilChanged()
                .collect { dur ->
                    if (dur > 0L) autoSubtitleManager.updateDuration(dur)
                }
        }

        // Handle Sleep Timer "End of Video" cleanly when playback reaches STATE_ENDED
        viewModelScope.launch {
            playbackState
                .map { it.isEnded }
                .distinctUntilChanged()
                .collect { ended ->
                    if (ended && _sleepTimerState.value.selectedOption == SleepTimerOption.END_OF_VIDEO) {
                        clearSleepTimer()
                        playerEngine.pause()
                        _uiState.value = _uiState.value.copy(
                            feedbackMessage = "Sleep timer paused playback at end of video"
                        )
                    }
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
                autoSubtitleManager.onPlaybackStateUpdated(positionMs = pos, isPlaying = true)
                refreshSubtitleCueNow()

                tickCounter++
                // Persist playback resume history every ~3.2s (40 * 80ms) only while playing
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
        val cueText = if (autoSubtitleState.value.isEnabled) {
            autoSubtitleManager.findActiveCueText(
                playbackPositionMs = state.currentPositionMs,
                syncOffsetMs = state.subtitleSyncOffsetMs
            )
        } else {
            playerEngine.getCurrentSubtitleText()
        }
        if (cueText != _uiState.value.activeSubtitleText) {
            _uiState.value = _uiState.value.copy(activeSubtitleText = cueText)
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
        autoSubtitleManager.pauseProcessing()

        viewModelScope.launch {
            val prefs = settingsRepository.preferencesFlow.first()
            val accessible = mediaRepository.isVideoAccessible(uriString)
            if (!accessible) {
                _uiState.value = PlayerScreenUiState(
                    videoTitle = fallbackTitle,
                    aspectRatioMode = prefs.defaultAspectRatio,
                    fileMissingError = "This video file could not be opened. It may have been moved, deleted, or storage permission was revoked."
                )
                return@launch
            }

            val resolvedVideo = mediaRepository.findVideoByUri(uriString)
                ?: mediaRepository.addSafVideo(Uri.parse(uriString))

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
                aspectRatioMode = prefs.defaultAspectRatio,
                isLocked = false,
                isFullScreen = true,
                resumedFromPositionMs = resumeMs.takeIf { it > 2_000L }
            )

            autoSubtitleManager.attachMedia(
                mediaUriString = uriString,
                durationMs = resolvedVideo.durationMs,
                preferredTargetLanguageCode = prefs.autoSubtitleTargetLanguageCode
            )

            playerEngine.setOriginalAudioVolume(1.0f)
            playerEngine.setSubtitleSyncOffset(syncOffset)
            playerEngine.prepareMedia(
                uriString = uriString,
                startPositionMs = resumeMs,
                playbackSpeed = speed,
                autoPlay = true
            )

            if (!history?.externalSubtitleUri.isNullOrBlank()) {
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

    fun startFromBeginning() {
        seekTo(0L)
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun dismissResumeBanner() {
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun togglePlayPause() = playerEngine.togglePlayPause()

    fun pausePlayback() {
        if (_uiState.value.isHoldSpeedActive) {
            endHoldSpeed()
        }
        playerEngine.pause()
        stopActivePlaybackLoop()
        autoSubtitleManager.pauseProcessing()
        pauseSleepTimerCountdown()
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    fun onActivityBackgrounded(isInPipMode: Boolean) {
        if (!isInPipMode) {
            pausePlayback()
        }
    }

    fun seekTo(positionMs: Long) {
        playerEngine.seekTo(positionMs)
        autoSubtitleManager.onSeekTo(positionMs)
        refreshSubtitleCueNow()
    }

    fun seekBySeconds(seconds: Int) {
        val targetMs = (playerEngine.state.value.currentPositionMs + seconds * 1_000L).coerceAtLeast(0L)
        seekTo(targetMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        preHoldPlaybackSpeed = clamped
        playerEngine.setPlaybackSpeed(clamped)
        val uri = activeUriString ?: return
        viewModelScope.launch {
            historyRepository.savePreferredSpeed(uri, clamped)
        }
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
        viewModelScope.launch {
            settingsRepository.setHoldSpeedMultiplier(multiplier)
        }
    }

    // --- Auto AI Subtitles (100% Free On-Device) ---

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
        viewModelScope.launch {
            settingsRepository.setAutoSubtitleTargetLanguageCode(languageCode)
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

    // --- Tracks, Aspect Ratio, Lock, Subtitles ---

    fun setAspectRatio(mode: AspectRatioMode) {
        _uiState.value = _uiState.value.copy(aspectRatioMode = mode)
    }

    fun toggleScreenLock() {
        _uiState.value = _uiState.value.copy(isLocked = !_uiState.value.isLocked)
    }

    fun toggleFullScreen() {
        _uiState.value = _uiState.value.copy(isFullScreen = !_uiState.value.isFullScreen)
    }

    fun selectAudioTrack(track: AudioTrackInfo) = playerEngine.selectAudioTrack(track)

    fun selectSubtitleTrack(track: SubtitleTrackInfo) {
        if (autoSubtitleState.value.isEnabled) {
            autoSubtitleManager.disable()
        }
        playerEngine.selectEmbeddedSubtitle(track)
        refreshSubtitleCueNow()
    }

    fun disableSubtitles() {
        if (autoSubtitleState.value.isEnabled) {
            autoSubtitleManager.disable()
        }
        playerEngine.disableSubtitles()
        refreshSubtitleCueNow()
    }

    fun loadExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            mediaRepository.readSubtitleContent(uri).fold(
                onSuccess = { (name, content) ->
                    if (autoSubtitleState.value.isEnabled) {
                        autoSubtitleManager.disable()
                    }
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
                        _uiState.value = _uiState.value.copy(
                            feedbackMessage = "Loaded subtitle: $name"
                        )
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
        viewModelScope.launch {
            historyRepository.saveSubtitleSyncOffset(uri, offsetMs)
        }
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

    fun playNextInLibraryIfEnabled(): Boolean {
        val prefs = preferences.value
        val current = _uiState.value.currentVideo ?: return false
        if (!prefs.autoPlayNext) return false
        if (_sleepTimerState.value.selectedOption == SleepTimerOption.END_OF_VIDEO) return false
        val library = mediaRepository.sortVideos(
            mediaRepository.libraryState.value.videos,
            prefs.sortOption
        )
        val idx = library.indexOfFirst { it.uriString == current.uriString }
        if (idx in 0 until library.lastIndex) {
            val next = library[idx + 1]
            activeUriString = null
            initializeMedia(next.uriString, next.title, explicitStartMs = 0L)
            return true
        }
        return false
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
        autoSubtitleManager.pauseProcessing()
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
                settingsRepository = app.settingsRepository,
                autoSubtitleManager = app.autoSubtitleManager
            ) as T
        }
    }
}
