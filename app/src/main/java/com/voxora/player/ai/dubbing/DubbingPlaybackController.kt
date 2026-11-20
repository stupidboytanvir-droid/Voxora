package com.voxora.player.ai.dubbing

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Real-Time Synchronized Bengali Dubbing Playback Controller for Media3 [ExoPlayer].
 *
 * - Runs a dedicated secondary [ExoPlayer] (with `handleAudioFocus = false`) bound to the
 *   assembled timeline-accurate Bengali dubbed WAV track (`assembled_dub_track.wav`).
 * - Synchronizes `play()`, `pause()`, `seekTo()`, `playbackSpeed`, and live `syncOffsetMs`
 *   with the primary video [ExoPlayer] on a tight 40ms main-thread sync loop.
 * - Applies smooth 150ms raised-cosine **Smart Audio Ducking** to the primary video's audio
 *   volume ONLY during active Bengali dialogue windows so background music & SFX stay rich.
 * - Supports instant switching between Original Audio, Bengali Mix, and Bengali Only modes
 *   without ever reloading or interrupting video playback.
 */
@OptIn(UnstableApi::class)
class DubbingPlaybackController(
    context: Context,
    private val mainExoPlayer: ExoPlayer,
    private val trackAssembler: DubbedTrackAssembler
) {
    private val appContext = context.applicationContext
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val dubExoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            false // Never steal audio focus from primary video ExoPlayer
        )
        .build()

    private val _state = MutableStateFlow(LiveDubbingPlaybackState())
    val state: StateFlow<LiveDubbingPlaybackState> = _state.asStateFlow()

    private var currentConfig: DubbingSessionConfig = DubbingSessionConfig()
    private var currentSegments: List<DubbedAudioSegment> = emptyList()
    private var currentDuckingWindows: List<DialogueDuckingWindow> = emptyList()
    private var assembledTrackDurationMs: Long = 0L
    private var assembledTrackFilePath: String? = null
    private val chunkTracks = linkedMapOf<Int, AssembledChunkTrack>()
    private var loadedChunkIndex: Int = -1
    private var lastLoadedVideoUri: String = ""
    private var isReleased = false
    private var syncJob: Job? = null
    private var assembleJob: Job? = null

    private val mainPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncImmediately()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncImmediately()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            syncImmediately(forceSeek = true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            if (!isReleased) {
                dubExoPlayer.setPlaybackSpeed(playbackParameters.speed.coerceIn(0.25f, 3.0f))
            }
        }
    }

    init {
        mainExoPlayer.addListener(mainPlayerListener)
        startSyncLoop()
    }

    /**
     * Updates live dubbing settings (enable toggle, output mode, volumes, ducking strength,
     * sync offset) and applies them immediately without interrupting playback.
     */
    fun updateConfig(config: DubbingSessionConfig) {
        if (isReleased) return
        val offsetChanged = config.syncOffsetMs != currentConfig.syncOffsetMs
        currentConfig = config
        if (offsetChanged && currentSegments.isNotEmpty()) {
            currentDuckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
                segments = currentSegments,
                syncOffsetMs = config.syncOffsetMs
            )
        }
        syncImmediately(forceSeek = offsetChanged)
    }

    /**
     * Assembles [segments] into a continuous timeline WAV on [Dispatchers.IO] and loads it into
     * the synchronized Bengali dub player. Base assembly uses `syncOffsetMs = 0L` so subsequent
     * live Sync Offset slider adjustments take effect instantaneously with zero re-assembly.
     */
    fun attachDubbedSegments(
        videoUri: String,
        segments: List<DubbedAudioSegment>,
        videoDurationMsHint: Long = 0L,
        preAssembledTrack: AssembledDubTrack? = null
    ) {
        if (isReleased) return
        assembleJob?.cancel()

        if (segments.isEmpty()) {
            clearTrack()
            return
        }

        lastLoadedVideoUri = videoUri
        chunkTracks.clear()
        loadedChunkIndex = -1
        currentSegments = segments
        currentDuckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
            segments = segments,
            syncOffsetMs = currentConfig.syncOffsetMs
        )

        if (preAssembledTrack != null && preAssembledTrack.wavFile.exists()) {
            loadAssembledTrackIntoDubPlayer(preAssembledTrack, segments.size)
            return
        }

        _state.value = _state.value.copy(
            isAssemblingTrack = true,
            statusDetail = "Assembling synchronized Bengali dub track (${segments.size} lines)..."
        )

        assembleJob = controllerScope.launch {
            val result = trackAssembler.assembleTimelineWav(
                videoUri = videoUri,
                segments = segments,
                totalDurationMsHint = videoDurationMsHint,
                syncOffsetMs = 0L,
                targetSampleRate = DubbedTrackAssembler.DEFAULT_LIVE_SAMPLE_RATE
            )
            result.fold(
                onSuccess = { assembled ->
                    if (!isReleased && lastLoadedVideoUri == videoUri) {
                        loadAssembledTrackIntoDubPlayer(assembled, segments.size)
                    }
                },
                onFailure = { err ->
                    if (!isReleased) {
                        _state.value = _state.value.copy(
                            isAssemblingTrack = false,
                            isTrackReady = false,
                            statusDetail = err.message ?: "Failed to assemble synchronized Bengali dub track"
                        )
                    }
                }
            )
        }
    }

    private fun loadAssembledTrackIntoDubPlayer(track: AssembledDubTrack, segmentCount: Int) {
        assembledTrackDurationMs = track.totalDurationMs
        assembledTrackFilePath = track.wavFile.absolutePath
        dubExoPlayer.stop()
        dubExoPlayer.clearMediaItems()
        dubExoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(track.wavFile)))
        dubExoPlayer.setPlaybackSpeed(mainExoPlayer.playbackParameters.speed.coerceIn(0.25f, 3.0f))
        dubExoPlayer.prepare()
        dubExoPlayer.playWhenReady = false

        _state.value = _state.value.copy(
            isTrackReady = true,
            isAssemblingTrack = false,
            isChunkStreamingMode = false,
            readyChunkCount = 0,
            assembledTrackPath = track.wavFile.absolutePath,
            assembledSegmentCount = segmentCount,
            statusDetail = "Synchronized Bengali track ready ($segmentCount lines)"
        )
        syncImmediately(forceSeek = true)
    }

    /**
     * Attaches or updates a single 30-second rolling chunk WAV track without interrupting
     * active playback of the currently playing chunk. As soon as Chunk 1 arrives, live
     * synchronized Bengali playback begins immediately; subsequent pre-dubbed chunks seamlessly
     * queue up for zero-gap transitions as the video advances or when the user seeks.
     */
    fun attachOrUpdateRollingChunk(
        videoUri: String,
        chunkTrack: AssembledChunkTrack,
        allAccumulatedSegments: List<DubbedAudioSegment>
    ) {
        if (isReleased) return
        assembleJob?.cancel()
        assembleJob = null

        if (lastLoadedVideoUri.isNotBlank() && lastLoadedVideoUri != videoUri) {
            chunkTracks.clear()
            loadedChunkIndex = -1
            dubExoPlayer.stop()
            dubExoPlayer.clearMediaItems()
        }

        lastLoadedVideoUri = videoUri
        assembledTrackFilePath = null
        assembledTrackDurationMs = 0L
        chunkTracks[chunkTrack.chunkIndex] = chunkTrack
        currentSegments = allAccumulatedSegments
        currentDuckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
            segments = allAccumulatedSegments,
            syncOffsetMs = currentConfig.syncOffsetMs
        )

        _state.value = _state.value.copy(
            isTrackReady = true,
            isAssemblingTrack = false,
            isChunkStreamingMode = true,
            readyChunkCount = chunkTracks.size,
            assembledTrackPath = chunkTrack.wavFile.absolutePath,
            assembledSegmentCount = allAccumulatedSegments.size,
            statusDetail = "Live Bengali streaming · ${chunkTracks.size} chunk(s) ready (${allAccumulatedSegments.size} lines)"
        )

        // Only triggers media item load if the player's current position is inside a newly ready chunk
        syncImmediately(forceSeek = false)
    }

    /**
     * Clears the loaded dubbed track when switching videos or clearing cached audio.
     */
    fun clearTrack() {
        assembleJob?.cancel()
        assembleJob = null
        lastLoadedVideoUri = ""
        currentSegments = emptyList()
        currentDuckingWindows = emptyList()
        assembledTrackDurationMs = 0L
        assembledTrackFilePath = null
        chunkTracks.clear()
        loadedChunkIndex = -1
        if (!isReleased) {
            dubExoPlayer.stop()
            dubExoPlayer.clearMediaItems()
            applyNormalOriginalVolume()
        }
        _state.value = LiveDubbingPlaybackState()
    }

    /**
     * Forces an immediate position/speed/volume sync (e.g., on explicit seek or mode toggle).
     */
    fun syncImmediately(forceSeek: Boolean = false) {
        if (isReleased) return
        val config = currentConfig
        val isChunkMode = chunkTracks.isNotEmpty()
        val trackReady = isChunkMode || (assembledTrackFilePath != null && currentSegments.isNotEmpty())
        val dubPlaybackWanted = config.isDubbingEnabled &&
            trackReady &&
            config.audioOutputMode != DubbingAudioOutputMode.ORIGINAL_AUDIO

        if (!dubPlaybackWanted) {
            if (dubExoPlayer.isPlaying || dubExoPlayer.playWhenReady) {
                dubExoPlayer.pause()
            }
            dubExoPlayer.volume = 0f
            val normalVol = applyNormalOriginalVolume()
            val prev = _state.value
            if (prev.isActive || prev.isOriginalDucked || abs(prev.effectiveOriginalVolume - normalVol) > 0.01f) {
                _state.value = prev.copy(
                    isActive = false,
                    isOriginalDucked = false,
                    activeSegmentIndex = null,
                    activeCharacterId = null,
                    activeBengaliLine = null,
                    effectiveOriginalVolume = normalVol,
                    effectiveDubbedVolume = 0f
                )
            }
            return
        }

        val videoPosMs = mainExoPlayer.currentPosition.coerceAtLeast(0L)
        val mainPlaying = mainExoPlayer.isPlaying
        val mainSpeed = mainExoPlayer.playbackParameters.speed.coerceIn(0.25f, 3.0f)

        // Expected dub track position accounts for user's live Sync Offset (ms):
        // Positive syncOffsetMs delays Bengali audio (plays later), so dubPos = videoPos - syncOffsetMs.
        val rawExpectedDubPosMs = videoPosMs - config.syncOffsetMs

        if (abs(dubExoPlayer.playbackParameters.speed - mainSpeed) > 0.01f) {
            dubExoPlayer.setPlaybackSpeed(mainSpeed)
        }

        val inDubTrackRange: Boolean
        if (isChunkMode) {
            val targetChunk = findChunkForTimelinePosition(rawExpectedDubPosMs)
            inDubTrackRange = targetChunk != null && targetChunk.wavFile.exists()
            if (targetChunk != null && targetChunk.wavFile.exists()) {
                val localExpectedPosMs = (rawExpectedDubPosMs - targetChunk.startMs)
                    .coerceIn(0L, targetChunk.durationMs)
                if (loadedChunkIndex != targetChunk.chunkIndex) {
                    loadedChunkIndex = targetChunk.chunkIndex
                    dubExoPlayer.stop()
                    dubExoPlayer.clearMediaItems()
                    dubExoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(targetChunk.wavFile)))
                    dubExoPlayer.setPlaybackSpeed(mainSpeed)
                    dubExoPlayer.prepare()
                    dubExoPlayer.seekTo(localExpectedPosMs)
                    if (mainPlaying) {
                        dubExoPlayer.playWhenReady = true
                        dubExoPlayer.play()
                    } else {
                        dubExoPlayer.playWhenReady = false
                    }
                } else {
                    val dubPosMs = dubExoPlayer.currentPosition.coerceAtLeast(0L)
                    val driftMs = abs(dubPosMs - localExpectedPosMs)
                    if (forceSeek || driftMs > DRIFT_TOLERANCE_MS) {
                        dubExoPlayer.seekTo(localExpectedPosMs)
                    }
                    if (mainPlaying && !dubExoPlayer.playWhenReady) {
                        dubExoPlayer.playWhenReady = true
                        dubExoPlayer.play()
                    } else if (!mainPlaying && (dubExoPlayer.isPlaying || dubExoPlayer.playWhenReady)) {
                        dubExoPlayer.pause()
                    }
                }
            } else {
                if (dubExoPlayer.isPlaying || dubExoPlayer.playWhenReady) {
                    dubExoPlayer.pause()
                }
            }
        } else {
            inDubTrackRange = rawExpectedDubPosMs in 0L..assembledTrackDurationMs
            if (inDubTrackRange) {
                val dubPosMs = dubExoPlayer.currentPosition.coerceAtLeast(0L)
                val driftMs = abs(dubPosMs - rawExpectedDubPosMs)
                if (forceSeek || driftMs > DRIFT_TOLERANCE_MS) {
                    dubExoPlayer.seekTo(rawExpectedDubPosMs)
                }
                if (mainPlaying && !dubExoPlayer.playWhenReady) {
                    dubExoPlayer.playWhenReady = true
                    dubExoPlayer.play()
                } else if (!mainPlaying && (dubExoPlayer.isPlaying || dubExoPlayer.playWhenReady)) {
                    dubExoPlayer.pause()
                }
            } else {
                if (dubExoPlayer.isPlaying || dubExoPlayer.playWhenReady) {
                    dubExoPlayer.pause()
                }
            }
        }

        // Smart Audio Ducking calculation at current video position (only duck when dubbed chunk is available):
        // Drops aggressively to 0.05f (5%) during every Bengali dubbed line and smoothly restores to 0.85f during silence/BGM.
        val smartDuckVol = if (inDubTrackRange && config.preserveOriginalBackgroundAudio) {
            BackgroundAudioPreserver.computeSmartDuckingVolume(
                positionMs = videoPosMs,
                windows = currentDuckingWindows,
                duckingStrength = config.bgmDuckingStrength,
                silenceBgmVolume = BackgroundAudioPreserver.DEFAULT_SILENCE_BGM_VOLUME,
                fadeMs = BackgroundAudioPreserver.DEFAULT_FADE_MS
            )
        } else {
            BackgroundAudioPreserver.DEFAULT_SILENCE_BGM_VOLUME
        }

        val isDuckedNow = inDubTrackRange &&
            config.preserveOriginalBackgroundAudio &&
            smartDuckVol < (BackgroundAudioPreserver.DEFAULT_SILENCE_BGM_VOLUME - 0.05f)

        val effectiveOrigVol = when (config.audioOutputMode) {
            DubbingAudioOutputMode.BENGALI_DUBBED_MIX -> {
                if (inDubTrackRange) {
                    if (config.preserveOriginalBackgroundAudio) {
                        (config.originalAudioVolume * smartDuckVol).coerceIn(0f, 1f)
                    } else {
                        // Even if BGM preservation is toggled off, suppress English clash during active dialogue
                        val floor = if (findActiveSegmentAt(videoPosMs, config.syncOffsetMs) != null) {
                            BackgroundAudioPreserver.DEFAULT_DUCKED_DIALOGUE_VOLUME
                        } else {
                            BackgroundAudioPreserver.DEFAULT_SILENCE_BGM_VOLUME
                        }
                        (config.originalAudioVolume * floor).coerceIn(0f, 1f)
                    }
                } else {
                    config.originalAudioVolume.coerceIn(0f, 1f)
                }
            }
            DubbingAudioOutputMode.BENGALI_DUBBED_ONLY -> {
                if (config.preserveOriginalBackgroundAudio) {
                    // Gently keep BGM between dialogue while suppressing English dialogue lines
                    val bgmScale = if (isDuckedNow) 0.05f else 0.30f
                    (config.originalAudioVolume * bgmScale).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            DubbingAudioOutputMode.ORIGINAL_AUDIO -> config.originalAudioVolume.coerceIn(0f, 1f)
        }
        val effectiveDubVol = if (inDubTrackRange) config.dubbedAudioVolume.coerceIn(0f, 1f) else 0f

        mainExoPlayer.volume = effectiveOrigVol
        dubExoPlayer.volume = effectiveDubVol

        // Resolve active Bengali dialogue segment for subtle UI feedback
        val activeSeg = findActiveSegmentAt(videoPosMs, config.syncOffsetMs)
        val prev = _state.value
        if (!prev.isActive ||
            prev.isOriginalDucked != isDuckedNow ||
            prev.activeSegmentIndex != activeSeg?.segmentIndex ||
            abs(prev.effectiveOriginalVolume - effectiveOrigVol) > 0.03f ||
            abs(prev.effectiveDubbedVolume - effectiveDubVol) > 0.03f
        ) {
            _state.value = prev.copy(
                isActive = true,
                isOriginalDucked = isDuckedNow,
                activeSegmentIndex = activeSeg?.segmentIndex,
                activeCharacterId = activeSeg?.characterId,
                activeBengaliLine = activeSeg?.text,
                effectiveOriginalVolume = effectiveOrigVol,
                effectiveDubbedVolume = effectiveDubVol
            )
        }
    }

    private fun applyNormalOriginalVolume(): Float {
        val vol = if (currentConfig.isDubbingEnabled) {
            currentConfig.originalAudioVolume.coerceIn(0f, 1f)
        } else {
            1.0f
        }
        mainExoPlayer.volume = vol
        return vol
    }

    private fun findActiveSegmentAt(videoPosMs: Long, syncOffsetMs: Long): DubbedAudioSegment? {
        for (seg in currentSegments) {
            val start = (seg.startMs + syncOffsetMs).coerceAtLeast(0L)
            val dur = maxOf(seg.durationMs, seg.endMs - seg.startMs).coerceAtLeast(100L)
            if (videoPosMs in start..(start + dur)) {
                return seg
            }
        }
        return null
    }

    private fun findChunkForTimelinePosition(timelinePosMs: Long): AssembledChunkTrack? {
        if (timelinePosMs < 0L) return null
        val estimatedIdx = (timelinePosMs / ROLLING_CHUNK_DURATION_MS).toInt()
        val direct = chunkTracks[estimatedIdx]
        if (direct != null && timelinePosMs >= direct.startMs && timelinePosMs < direct.startMs + direct.durationMs) {
            return direct
        }
        return chunkTracks.values.firstOrNull {
            timelinePosMs >= it.startMs && timelinePosMs < (it.startMs + it.durationMs)
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = controllerScope.launch {
            while (isActive && !isReleased) {
                val trackReady = chunkTracks.isNotEmpty() || (assembledTrackFilePath != null && currentSegments.isNotEmpty())
                val activeMode = currentConfig.isDubbingEnabled &&
                    trackReady &&
                    currentConfig.audioOutputMode != DubbingAudioOutputMode.ORIGINAL_AUDIO

                if (activeMode) {
                    syncImmediately(forceSeek = false)
                    delay(if (mainExoPlayer.isPlaying) ACTIVE_SYNC_INTERVAL_MS else IDLE_SYNC_INTERVAL_MS)
                } else {
                    delay(IDLE_SYNC_INTERVAL_MS)
                }
            }
        }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        syncJob?.cancel()
        assembleJob?.cancel()
        runCatching { mainExoPlayer.removeListener(mainPlayerListener) }
        runCatching { dubExoPlayer.stop() }
        runCatching { dubExoPlayer.release() }
        controllerScope.cancel()
    }

    companion object {
        private const val ACTIVE_SYNC_INTERVAL_MS = 35L
        private const val IDLE_SYNC_INTERVAL_MS = 250L
        private const val DRIFT_TOLERANCE_MS = 85L
        const val ROLLING_CHUNK_DURATION_MS = 30_000L
    }
}
