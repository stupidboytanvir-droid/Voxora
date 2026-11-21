package com.voxora.player.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.AudioTrackItem
import com.voxora.player.model.MusicRepeatMode
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.ui.viewmodel.SleepTimerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MusicPlayerState(
    val queue: List<AudioTrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    val volumeBoostPercent: Int = 0,
    val eqPreset: AudioEqPreset = AudioEqPreset.NORMAL,
    val sleepTimer: SleepTimerUiState = SleepTimerUiState()
) {
    val currentTrack: AudioTrackItem?
        get() = queue.getOrNull(currentIndex)

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

/**
 * App-scoped Local Audio / Music Player controller powered by Media3 [ExoPlayer] + [AudioFxController]
 * (200% Volume Booster & 5-Band Equalizer) + Shuffle / Repeat / Sleep Timer + Foreground Media Service.
 * Persists smoothly across tab switches and background playback.
 */
class MusicPlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioFxController = AudioFxController()

    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startProgressLoop()
                    resumeSleepTimerIfNeeded()
                } else {
                    stopProgressLoop()
                    pauseSleepTimer()
                }
                if (_state.value.currentTrack != null) {
                    MusicPlaybackService.startOrUpdate(appContext, this@MusicPlayerController)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = player.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
                _state.value = _state.value.copy(durationMs = dur)
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                val dur = player.duration.takeIf { it > 0L }
                    ?: _state.value.queue.getOrNull(idx)?.durationMs
                    ?: 0L
                if (idx in _state.value.queue.indices) {
                    _state.value = _state.value.copy(
                        currentIndex = idx,
                        currentPositionMs = 0L,
                        durationMs = dur
                    )
                    MusicPlaybackService.startOrUpdate(appContext, this@MusicPlayerController)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId > 0) {
                    audioFxController.attachToAudioSession(audioSessionId)
                    audioFxController.applyVolumeBoost(_state.value.volumeBoostPercent)
                    audioFxController.applyEqPreset(_state.value.eqPreset)
                }
            }
        })

        exoPlayer = player
        return player
    }

    fun playQueue(tracks: List<AudioTrackItem>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        val player = getOrCreatePlayer()
        val mediaItems = tracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }

        _state.value = _state.value.copy(
            queue = tracks,
            currentIndex = safeIdx,
            currentPositionMs = 0L,
            durationMs = tracks[safeIdx].durationMs
        )

        player.setMediaItems(mediaItems, safeIdx, 0L)
        player.shuffleModeEnabled = _state.value.shuffleEnabled
        player.repeatMode = toExoRepeatMode(_state.value.repeatMode)
        player.prepare()
        player.play()

        if (player.audioSessionId > 0) {
            audioFxController.attachToAudioSession(player.audioSessionId)
        }
        MusicPlaybackService.startOrUpdate(appContext, this)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (_state.value.currentTrack == null) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        val clamped = positionMs.coerceIn(0L, _state.value.durationMs.coerceAtLeast(1L))
        player.seekTo(clamped)
        _state.value = _state.value.copy(currentPositionMs = clamped)
    }

    fun skipToNext() {
        val player = exoPlayer ?: return
        val q = _state.value.queue
        if (q.isEmpty()) return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(0, 0L)
        }
        player.play()
    }

    fun skipToPrevious() {
        val player = exoPlayer ?: return
        val q = _state.value.queue
        if (q.isEmpty()) return
        if (player.currentPosition > 3_500L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(q.lastIndex, 0L)
        }
        player.play()
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffleEnabled
        _state.value = _state.value.copy(shuffleEnabled = next)
        exoPlayer?.shuffleModeEnabled = next
    }

    fun cycleRepeatMode() {
        val next = _state.value.repeatMode.next()
        _state.value = _state.value.copy(repeatMode = next)
        exoPlayer?.repeatMode = toExoRepeatMode(next)
    }

    fun setVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        _state.value = _state.value.copy(volumeBoostPercent = clamped)
        exoPlayer?.audioSessionId?.takeIf { it > 0 }?.let {
            audioFxController.attachToAudioSession(it)
        }
        audioFxController.applyVolumeBoost(clamped)
    }

    fun setEqPreset(preset: AudioEqPreset) {
        _state.value = _state.value.copy(eqPreset = preset)
        exoPlayer?.audioSessionId?.takeIf { it > 0 }?.let {
            audioFxController.attachToAudioSession(it)
        }
        audioFxController.applyEqPreset(preset)
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        when (option) {
            SleepTimerOption.OFF -> {
                _state.value = _state.value.copy(sleepTimer = SleepTimerUiState())
            }
            SleepTimerOption.END_OF_VIDEO -> {
                _state.value = _state.value.copy(
                    sleepTimer = SleepTimerUiState(
                        selectedOption = SleepTimerOption.END_OF_VIDEO,
                        remainingSeconds = 0
                    )
                )
            }
            else -> {
                _state.value = _state.value.copy(
                    sleepTimer = SleepTimerUiState(
                        selectedOption = option,
                        remainingSeconds = option.durationMinutes * 60
                    )
                )
                if (_state.value.isPlaying) {
                    startSleepTimerCountdown()
                }
            }
        }
    }

    fun stopAndClear() {
        stopProgressLoop()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _state.value = _state.value.copy(
            queue = emptyList(),
            currentIndex = -1,
            isPlaying = false,
            currentPositionMs = 0L,
            durationMs = 0L,
            sleepTimer = SleepTimerUiState()
        )
        MusicPlaybackService.stop(appContext)
    }

    private fun handleTrackEnded() {
        if (_state.value.sleepTimer.selectedOption == SleepTimerOption.END_OF_VIDEO) {
            _state.value = _state.value.copy(sleepTimer = SleepTimerUiState())
            exoPlayer?.pause()
        }
    }

    private fun startProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive && exoPlayer?.isPlaying == true) {
                val p = exoPlayer ?: break
                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration.takeIf { it > 0L } ?: _state.value.currentTrack?.durationMs ?: 0L
                _state.value = _state.value.copy(
                    currentPositionMs = pos,
                    durationMs = dur
                )
                delay(250L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun resumeSleepTimerIfNeeded() {
        val st = _state.value.sleepTimer
        if (st.isActive && st.selectedOption != SleepTimerOption.END_OF_VIDEO &&
            st.remainingSeconds > 0 && sleepTimerJob?.isActive != true
        ) {
            startSleepTimerCountdown()
        }
    }

    private fun pauseSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun startSleepTimerCountdown() {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            while (isActive && _state.value.isPlaying) {
                val rem = _state.value.sleepTimer.remainingSeconds
                if (rem <= 0) {
                    _state.value = _state.value.copy(sleepTimer = SleepTimerUiState())
                    exoPlayer?.pause()
                    break
                }
                delay(1_000L)
                val next = (_state.value.sleepTimer.remainingSeconds - 1).coerceAtLeast(0)
                _state.value = _state.value.copy(
                    sleepTimer = _state.value.sleepTimer.copy(remainingSeconds = next)
                )
            }
        }
    }

    private fun toExoRepeatMode(mode: MusicRepeatMode): Int = when (mode) {
        MusicRepeatMode.OFF -> Player.REPEAT_MODE_OFF
        MusicRepeatMode.ALL -> Player.REPEAT_MODE_ALL
        MusicRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    fun release() {
        stopAndClear()
        audioFxController.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
