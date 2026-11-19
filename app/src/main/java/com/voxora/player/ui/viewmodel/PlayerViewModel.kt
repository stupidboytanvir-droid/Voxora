package com.voxora.player.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.local.power.BatteryMonitor
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.dubbing.AiDubbingPipeline
import com.voxora.player.dubbing.DubbingCapabilityStatus
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.BatterySaverEvaluation
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.BatterySavingPolicy
import com.voxora.player.model.BatteryStatus
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VideoItem
import com.voxora.player.player.PlayerPlaybackState
import com.voxora.player.player.VoxoraPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerScreenUiState(
    val currentVideo: VideoItem? = null,
    val videoTitle: String = "",
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val isLocked: Boolean = false,
    val isFullScreen: Boolean = true,
    val resumedFromPositionMs: Long? = null,
    val activeSubtitleText: String? = null,
    val fileMissingError: String? = null,
    val subtitleLoadFeedback: String? = null
)

class PlayerViewModel(
    app: VoxoraApplication,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val batteryMonitor: BatteryMonitor,
    private val aiDubbingPipeline: AiDubbingPipeline
) : ViewModel() {

    val playerEngine = VoxoraPlayerEngine(app)
    val playbackState: StateFlow<PlayerPlaybackState> = playerEngine.state

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

    val batterySavingState: StateFlow<BatterySaverEvaluation> = combine(
        preferences,
        batteryStatus
    ) { prefs, status ->
        BatterySavingPolicy.evaluate(prefs, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BatterySavingPolicy.evaluate(preferences.value, batteryStatus.value)
    )

    private val _uiState = MutableStateFlow(PlayerScreenUiState())
    val uiState: StateFlow<PlayerScreenUiState> = _uiState.asStateFlow()

    val dubbingStatus: DubbingCapabilityStatus = aiDubbingPipeline.getCapabilityStatus()
    val plannedDubbingLanguages: List<DubbingLanguage> = aiDubbingPipeline.getSupportedTargetLanguages()

    private var activeUriString: String? = null
    private var lastSavedPositionMs: Long = -1L

    init {
        viewModelScope.launch {
            while (isActive) {
                playerEngine.pollPosition()
                val cueText = playerEngine.getCurrentSubtitleText()
                if (cueText != _uiState.value.activeSubtitleText) {
                    _uiState.value = _uiState.value.copy(activeSubtitleText = cueText)
                }
                val delayMs = if (batterySavingState.value.isActive) 350L else 150L
                delay(delayMs)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                persistProgressIfChanged()
            }
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
        playerEngine.seekTo(0L)
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun dismissResumeBanner() {
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun togglePlayPause() = playerEngine.togglePlayPause()

    fun pausePlayback() {
        playerEngine.pause()
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    fun seekTo(positionMs: Long) = playerEngine.seekTo(positionMs)

    fun seekBySeconds(seconds: Int) = playerEngine.seekBy(seconds * 1_000L)

    fun setPlaybackSpeed(speed: Float) {
        playerEngine.setPlaybackSpeed(speed)
        val uri = activeUriString ?: return
        viewModelScope.launch {
            historyRepository.savePreferredSpeed(uri, speed)
        }
    }

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

    fun selectSubtitleTrack(track: SubtitleTrackInfo) = playerEngine.selectEmbeddedSubtitle(track)

    fun disableSubtitles() = playerEngine.disableSubtitles()

    fun loadExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            mediaRepository.readSubtitleContent(uri).fold(
                onSuccess = { (name, content) ->
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
                        _uiState.value = _uiState.value.copy(
                            subtitleLoadFeedback = "Loaded subtitle: $name"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            subtitleLoadFeedback = "No valid SRT or VTT cues found in $name"
                        )
                    }
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        subtitleLoadFeedback = "Unable to read the selected subtitle file."
                    )
                }
            )
        }
    }

    fun clearSubtitleFeedback() {
        _uiState.value = _uiState.value.copy(subtitleLoadFeedback = null)
    }

    fun adjustSubtitleSync(deltaMs: Long) {
        val next = (playerEngine.state.value.subtitleSyncOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
        setSubtitleSync(next)
    }

    fun setSubtitleSync(offsetMs: Long) {
        playerEngine.setSubtitleSyncOffset(offsetMs)
        val uri = activeUriString ?: return
        viewModelScope.launch {
            historyRepository.saveSubtitleSyncOffset(uri, offsetMs)
        }
    }

    fun setSubtitleFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleFontSizeSp(sizeSp) }
    }

    fun setSubtitleBottomPadding(fraction: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBottomPaddingFraction(fraction) }
    }

    fun playNextInLibraryIfEnabled(): Boolean {
        val prefs = preferences.value
        val current = _uiState.value.currentVideo ?: return false
        if (!prefs.autoPlayNext) return false
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
            speed = s.playbackSpeed
        )
    }

    fun setBatterySaverMode(mode: BatterySaverMode) {
        viewModelScope.launch { settingsRepository.setBatterySaverMode(mode) }
    }

    fun setBatterySaverCapRefreshRate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverCapRefreshRate(enabled) }
    }

    fun setBatterySaverDisableAnimations(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverDisableAnimations(enabled) }
    }

    override fun onCleared() {
        playerEngine.release()
        super.onCleared()
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(
                app = app,
                mediaRepository = app.mediaRepository,
                historyRepository = app.historyRepository,
                settingsRepository = app.settingsRepository,
                batteryMonitor = app.batteryMonitor,
                aiDubbingPipeline = app.aiDubbingPipeline
            ) as T
        }
    }
}
