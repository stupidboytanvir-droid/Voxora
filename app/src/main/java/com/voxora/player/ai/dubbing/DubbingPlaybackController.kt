package com.voxora.player.ai.dubbing

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.MediaPlayer
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
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
 * Production-grade Non-Overlapping Real-Time Bengali Dubbing Playback Controller for Media3 [ExoPlayer].
 *
 * Strictly enforces:
 * 1. **Zero Original English Audio Leak**:
 *    - `"Bengali Dubbed Audio"` ([DubbingAudioOutputMode.BENGALI_DUBBED_ONLY]): locks `mainExoPlayer.volume = 0.0f`
 *      immediately and keeps it locked at `0.0f` for the entire playback — zero English audio leaks through.
 *    - `"Bengali Dubbed + Smart Ducking"` ([DubbingAudioOutputMode.BENGALI_DUBBED_MIX]): forces
 *      `mainExoPlayer.volume = 0.03f` (3%) whenever a Bengali dubbed line is actively playing, and smoothly
 *      restores `mainExoPlayer.volume = 0.80f` during silent gaps between lines.
 *    - Never resets `mainExoPlayer.volume` back to `1.0f` on periodic refreshes.
 * 2. **Hard Non-Overlapping Per-Clip Playback**:
 *    - Plays each synthesized `.mp3` (or `.wav`) clip using a dedicated [MediaPlayer] synced to
 *      `mainExoPlayer.currentPosition`.
 *    - Strictly allows at most ONE Bengali clip to play at a time: before starting a new segment at
 *      `segment.startMs`, immediately stops and releases any previous clip still playing.
 * 3. **Lip-Sync Speed Fitting**:
 *    - If a clip's actual duration exceeds `targetMs = (segment.endMs - segment.startMs).coerceAtLeast(800L)`,
 *      applies `playbackParams.setSpeed((actualMs / targetMs.toFloat()).coerceIn(1.0f, 1.35f))` (combined with
 *      video playback speed) so every Bengali line finishes right on time with the speaker's mouth.
 */
@OptIn(UnstableApi::class)
class DubbingPlaybackController(
    context: Context,
    private val mainExoPlayer: ExoPlayer,
    @Suppress("UNUSED_PARAMETER") private val trackAssembler: DubbedTrackAssembler
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LiveDubbingPlaybackState())
    val state: StateFlow<LiveDubbingPlaybackState> = _state.asStateFlow()

    private var currentConfig: DubbingSessionConfig = DubbingSessionConfig()
    private var currentSegments: List<DubbedAudioSegment> = emptyList()
    private var currentDuckingWindows: List<DialogueDuckingWindow> = emptyList()
    private val readyChunkIndices = linkedSetOf<Int>()
    private var lastLoadedVideoUri: String = ""
    private var isReleased = false
    private var syncJob: Job? = null

    // Dedicated single-instance per-clip MediaPlayer (Hard Non-Overlap Rule: max 1 clip active at any time)
    private var activeClipPlayer: MediaPlayer? = null
    private var activeClipSegmentIndex: Int? = null
    private var activeClipIsPlaying: Boolean = false
    private var activeClipFitSpeed: Float = 1.0f
    private var lastCompletedSegmentIndex: Int? = null
    private var lastSyncedVideoPosMs: Long = -1L

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
            // Reset completion guard on user seek so replaying a previous line works immediately
            lastCompletedSegmentIndex = null
            stopAndReleaseActiveClip()
            syncImmediately(forceSeek = true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!isReleased) {
                applySpeedToActiveClip(playbackParameters.speed)
            }
        }
    }

    init {
        mainExoPlayer.addListener(mainPlayerListener)
        startSyncLoop()
    }

    /**
     * Updates live dubbing settings (enable toggle, output mode, volumes, ducking strength,
     * sync offset) and applies `mainExoPlayer.volume` immediately without interrupting playback.
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
            lastCompletedSegmentIndex = null
            stopAndReleaseActiveClip()
        }
        syncImmediately(forceSeek = offsetChanged)
    }

    /**
     * Attaches synthesized Bengali dialogue segments for direct non-overlapping per-clip playback.
     */
    fun attachDubbedSegments(
        videoUri: String,
        segments: List<DubbedAudioSegment>,
        @Suppress("UNUSED_PARAMETER") videoDurationMsHint: Long = 0L,
        preAssembledTrack: AssembledDubTrack? = null
    ) {
        if (isReleased) return
        if (segments.isEmpty()) {
            clearTrack()
            return
        }

        stopAndReleaseActiveClip()
        lastCompletedSegmentIndex = null
        lastLoadedVideoUri = videoUri
        readyChunkIndices.clear()
        currentSegments = segments.sortedBy { it.startMs }
        currentDuckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
            segments = currentSegments,
            syncOffsetMs = currentConfig.syncOffsetMs
        )

        _state.value = _state.value.copy(
            isTrackReady = true,
            isAssemblingTrack = false,
            isChunkStreamingMode = false,
            readyChunkCount = 0,
            assembledTrackPath = preAssembledTrack?.wavFile?.absolutePath
                ?: currentSegments.firstOrNull()?.clipFilePath,
            assembledSegmentCount = currentSegments.size,
            statusDetail = "Synchronized Bengali dubbing ready (${currentSegments.size} lines)"
        )
        syncImmediately(forceSeek = true)
    }

    /**
     * Attaches or updates a newly dubbed 30-second rolling chunk without interrupting active playback
     * of the current clip.
     */
    fun attachOrUpdateRollingChunk(
        videoUri: String,
        chunkTrack: AssembledChunkTrack,
        allAccumulatedSegments: List<DubbedAudioSegment>
    ) {
        if (isReleased) return
        if (lastLoadedVideoUri.isNotBlank() && lastLoadedVideoUri != videoUri) {
            stopAndReleaseActiveClip()
            lastCompletedSegmentIndex = null
            readyChunkIndices.clear()
        }

        lastLoadedVideoUri = videoUri
        readyChunkIndices.add(chunkTrack.chunkIndex)
        currentSegments = allAccumulatedSegments.sortedBy { it.startMs }
        currentDuckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
            segments = currentSegments,
            syncOffsetMs = currentConfig.syncOffsetMs
        )

        _state.value = _state.value.copy(
            isTrackReady = true,
            isAssemblingTrack = false,
            isChunkStreamingMode = true,
            readyChunkCount = readyChunkIndices.size,
            assembledTrackPath = chunkTrack.wavFile.absolutePath,
            assembledSegmentCount = currentSegments.size,
            statusDetail = "Live Bengali streaming · ${readyChunkIndices.size} chunk(s) ready (${currentSegments.size} lines)"
        )
        syncImmediately(forceSeek = false)
    }

    fun clearTrack() {
        stopAndReleaseActiveClip()
        lastCompletedSegmentIndex = null
        lastSyncedVideoPosMs = -1L
        lastLoadedVideoUri = ""
        currentSegments = emptyList()
        currentDuckingWindows = emptyList()
        readyChunkIndices.clear()
        if (!isReleased) {
            applyModeVolumeWhenNoDubActive(currentConfig)
        }
        _state.value = LiveDubbingPlaybackState()
    }

    /**
     * Core 35ms real-time sync loop & immediate state update handler:
     * 1. Enforces Output Mode volume lock (including 0.0f lock for Bengali Dubbed Audio even before clips load).
     * 2. Triggers strictly non-overlapping per-clip playback synced to `mainExoPlayer.currentPosition`.
     * 3. Applies 3% (0.03f) dialogue ducking and 80% (0.80f) silence BGM restore in Smart Ducking mode.
     */
    fun syncImmediately(forceSeek: Boolean = false) {
        if (isReleased) return
        val config = currentConfig

        // Case A: Dubbing master toggle OFF or Original Audio selected -> full original audio, stop any clip
        if (!config.isDubbingEnabled || config.audioOutputMode == DubbingAudioOutputMode.ORIGINAL_AUDIO) {
            stopAndReleaseActiveClip()
            val normalVol = applyModeVolumeWhenNoDubActive(config)
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

        // Case B: Bengali Dubbed Audio (Only) ALWAYS locks mainExoPlayer.volume = 0.0f immediately — zero leak!
        if (config.audioOutputMode == DubbingAudioOutputMode.BENGALI_DUBBED_ONLY) {
            if (mainExoPlayer.volume != 0.0f) {
                mainExoPlayer.volume = 0.0f
            }
        }

        // If no segments are loaded yet, keep Bengali Only locked at 0.0f and avoid resetting volume
        if (currentSegments.isEmpty()) {
            stopAndReleaseActiveClip()
            val vol = if (config.audioOutputMode == DubbingAudioOutputMode.BENGALI_DUBBED_ONLY) {
                0.0f
            } else {
                config.originalAudioVolume.coerceIn(0f, 1f)
            }
            mainExoPlayer.volume = vol
            val prev = _state.value
            if (prev.isActive || abs(prev.effectiveOriginalVolume - vol) > 0.01f) {
                _state.value = prev.copy(
                    isActive = false,
                    isOriginalDucked = false,
                    activeSegmentIndex = null,
                    activeCharacterId = null,
                    activeBengaliLine = null,
                    effectiveOriginalVolume = vol,
                    effectiveDubbedVolume = 0f
                )
            }
            return
        }

        val videoPosMs = mainExoPlayer.currentPosition.coerceAtLeast(0L)
        val mainPlaying = mainExoPlayer.isPlaying
        val mainSpeed = mainExoPlayer.playbackParameters.speed.coerceIn(0.25f, 3.0f)

        // Detect backward jumps / timeline scrubs
        if (lastSyncedVideoPosMs >= 0L && abs(videoPosMs - lastSyncedVideoPosMs) > 650L) {
            lastCompletedSegmentIndex = null
        }
        lastSyncedVideoPosMs = videoPosMs

        val effectiveTimelineMs = videoPosMs - config.syncOffsetMs
        val targetEntry = findActiveSegmentWithHardBoundary(effectiveTimelineMs)

        // Manage dedicated single-clip MediaPlayer (Hard Non-Overlap Rule)
        if (targetEntry == null) {
            if (activeClipPlayer != null) {
                stopAndReleaseActiveClip()
            }
        } else {
            val (segment, hardEndMs) = targetEntry
            if (activeClipSegmentIndex != segment.segmentIndex) {
                // Only trigger if this segment hasn't already finished cleanly in the same pass
                if (lastCompletedSegmentIndex != segment.segmentIndex) {
                    // Hard Non-Overlap Rule: immediately stop & release any previous clip before starting the new one
                    stopAndReleaseActiveClip()
                    if (mainPlaying) {
                        startClipForSegment(
                            segment = segment,
                            hardEndMs = hardEndMs,
                            effectiveTimelineMs = effectiveTimelineMs,
                            mainSpeed = mainSpeed,
                            dubVolume = config.dubbedAudioVolume.coerceIn(0f, 1f)
                        )
                    }
                }
            } else {
                // Same segment already loaded in activeClipPlayer
                val player = activeClipPlayer
                if (player != null) {
                    val dubVol = config.dubbedAudioVolume.coerceIn(0f, 1f)
                    runCatching { player.setVolume(dubVol, dubVol) }
                    if (mainPlaying && !activeClipIsPlaying) {
                        runCatching {
                            player.start()
                            activeClipIsPlaying = true
                        }
                    } else if (!mainPlaying && activeClipIsPlaying) {
                        runCatching {
                            if (player.isPlaying) player.pause()
                            activeClipIsPlaying = false
                        }
                    } else if (forceSeek && mainPlaying) {
                        val elapsedMs = (effectiveTimelineMs - segment.startMs).coerceAtLeast(0L)
                        val seekMs = (elapsedMs * activeClipFitSpeed).toInt()
                        runCatching {
                            if (seekMs < player.duration) {
                                player.seekTo(seekMs)
                            }
                        }
                    }
                }
            }
        }

        val isLineActivelyPlaying = activeClipIsPlaying && activeClipPlayer != null && targetEntry != null
        val effectiveOrigVol = computeEffectiveMainVolume(
            config = config,
            videoPosMs = videoPosMs,
            isBengaliLineActivelyPlaying = isLineActivelyPlaying
        )
        val effectiveDubVol = if (targetEntry != null) config.dubbedAudioVolume.coerceIn(0f, 1f) else 0f

        mainExoPlayer.volume = effectiveOrigVol

        val isDuckedNow = when (config.audioOutputMode) {
            DubbingAudioOutputMode.BENGALI_DUBBED_ONLY -> true
            DubbingAudioOutputMode.BENGALI_DUBBED_MIX -> {
                isLineActivelyPlaying || effectiveOrigVol < (BackgroundAudioPreserver.DEFAULT_SILENCE_BGM_VOLUME * config.originalAudioVolume - 0.05f)
            }
            DubbingAudioOutputMode.ORIGINAL_AUDIO -> false
        }

        val activeSeg = targetEntry?.first
        val prev = _state.value
        if (!prev.isActive ||
            prev.isOriginalDucked != isDuckedNow ||
            prev.activeSegmentIndex != activeSeg?.segmentIndex ||
            abs(prev.effectiveOriginalVolume - effectiveOrigVol) > 0.02f ||
            abs(prev.effectiveDubbedVolume - effectiveDubVol) > 0.02f
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

    /**
     * Resolves the exact segment active at [effectiveTimelineMs] along with its hard non-overlap
     * end timestamp (strictly capped at `nextSegment.startMs`).
     */
    private fun findActiveSegmentWithHardBoundary(effectiveTimelineMs: Long): Pair<DubbedAudioSegment, Long>? {
        if (effectiveTimelineMs < 0L || currentSegments.isEmpty()) return null
        for (i in currentSegments.indices) {
            val seg = currentSegments[i]
            val start = seg.startMs
            if (effectiveTimelineMs < start) break

            val targetMs = (seg.endMs - seg.startMs).coerceAtLeast(MIN_SEGMENT_TARGET_MS)
            val naturalEnd = start + maxOf(targetMs, seg.durationMs)
            val nextStart = currentSegments.getOrNull(i + 1)?.startMs
            val hardEnd = if (nextStart != null && nextStart > start) {
                minOf(naturalEnd, nextStart)
            } else {
                naturalEnd
            }

            if (effectiveTimelineMs in start until hardEnd) {
                return seg to hardEnd
            }
        }
        return null
    }

    /**
     * Starts a dedicated [MediaPlayer] for [segment] using its verified `.mp3` (or `.wav` fallback)
     * file, applying lip-sync speed fitting `(actualMs / targetMs).coerceIn(1.0f, 1.35f)` whenever
     * the clip exceeds `targetMs`.
     */
    private fun startClipForSegment(
        segment: DubbedAudioSegment,
        hardEndMs: Long,
        effectiveTimelineMs: Long,
        mainSpeed: Float,
        dubVolume: Float
    ) {
        val clipFile = resolvePlayableClipFile(segment) ?: return

        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AndroidAudioAttributes.Builder()
                        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(clipFile.absolutePath)
                prepare()
            }
        }.getOrNull() ?: return

        val targetMs = minOf(
            (segment.endMs - segment.startMs).coerceAtLeast(MIN_SEGMENT_TARGET_MS),
            (hardEndMs - segment.startMs).coerceAtLeast(350L)
        ).coerceAtLeast(350L)
        val actualMs = player.duration.toLong().takeIf { it > 0L } ?: segment.durationMs

        // Lip-Sync Speed Fitting: if clip exceeds targetMs, gently accelerate up to 1.35x so it finishes on time
        val fitSpeed = computeLipSyncFitSpeed(actualMs = actualMs, targetMs = targetMs)
        activeClipFitSpeed = fitSpeed
        val combinedSpeed = (fitSpeed * mainSpeed).coerceIn(0.5f, 2.0f)

        val elapsedIntoSegMs = (effectiveTimelineMs - segment.startMs).coerceAtLeast(0L)
        if (elapsedIntoSegMs > 120L) {
            val seekTargetMs = (elapsedIntoSegMs * fitSpeed).toInt()
            if (seekTargetMs >= player.duration - 60) {
                runCatching { player.release() }
                lastCompletedSegmentIndex = segment.segmentIndex
                return
            }
            runCatching { player.seekTo(seekTargetMs) }
        }

        player.setVolume(dubVolume, dubVolume)
        player.setOnCompletionListener { mp ->
            if (activeClipPlayer === mp) {
                lastCompletedSegmentIndex = segment.segmentIndex
                stopAndReleaseActiveClip()
            }
        }
        player.setOnErrorListener { mp, _, _ ->
            if (activeClipPlayer === mp) {
                lastCompletedSegmentIndex = segment.segmentIndex
                stopAndReleaseActiveClip()
            }
            true
        }

        activeClipPlayer = player
        activeClipSegmentIndex = segment.segmentIndex

        // On Android 23+, assigning playbackParams with non-zero speed may start playback immediately.
        runCatching {
            val params = player.playbackParams
            params.speed = combinedSpeed
            player.playbackParams = params
        }

        val started = runCatching {
            if (!player.isPlaying) {
                player.start()
            }
            true
        }.getOrDefault(false)
        activeClipIsPlaying = started
        if (!started) {
            stopAndReleaseActiveClip()
        }
    }

    private fun resolvePlayableClipFile(segment: DubbedAudioSegment): File? {
        val primary = File(segment.clipFilePath)
        if (primary.exists() && primary.isFile && primary.length() > 44L) {
            return primary
        }
        val fallback = File(segment.audioFilePath)
        if (fallback.exists() && fallback.isFile && fallback.length() > 44L) {
            return fallback
        }
        return null
    }

    private fun applySpeedToActiveClip(mainSpeed: Float) {
        val player = activeClipPlayer ?: return
        val combinedSpeed = (activeClipFitSpeed * mainSpeed.coerceIn(0.25f, 3.0f)).coerceIn(0.5f, 2.0f)
        runCatching {
            val params = player.playbackParams
            params.speed = combinedSpeed
            player.playbackParams = params
        }
    }

    private fun stopAndReleaseActiveClip() {
        val player = activeClipPlayer
        activeClipPlayer = null
        activeClipSegmentIndex = null
        activeClipIsPlaying = false
        activeClipFitSpeed = 1.0f
        if (player != null) {
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    private fun applyModeVolumeWhenNoDubActive(config: DubbingSessionConfig): Float {
        val vol = when {
            !config.isDubbingEnabled -> 1.0f
            config.audioOutputMode == DubbingAudioOutputMode.BENGALI_DUBBED_ONLY -> 0.0f
            else -> config.originalAudioVolume.coerceIn(0f, 1f)
        }
        mainExoPlayer.volume = vol
        return vol
    }

    private fun computeEffectiveMainVolume(
        config: DubbingSessionConfig,
        videoPosMs: Long,
        isBengaliLineActivelyPlaying: Boolean
    ): Float {
        return when (config.audioOutputMode) {
            DubbingAudioOutputMode.BENGALI_DUBBED_ONLY -> 0.0f
            DubbingAudioOutputMode.BENGALI_DUBBED_MIX -> {
                if (isBengaliLineActivelyPlaying) {
                    // Force original audio down to 3% (0.03f) whenever a Bengali line is actively speaking
                    minOf(SMART_DUCK_ACTIVE_DIALOGUE_VOLUME, config.originalAudioVolume.coerceIn(0f, 1f))
                } else {
                    // Smoothly restore to 80% (0.80f) during silent gaps between lines for BGM & SFX
                    val curveVol = BackgroundAudioPreserver.computeSmartDuckingVolume(
                        positionMs = videoPosMs,
                        windows = currentDuckingWindows,
                        duckingStrength = config.bgmDuckingStrength,
                        silenceBgmVolume = SMART_DUCK_SILENCE_BGM_VOLUME,
                        fadeMs = BackgroundAudioPreserver.DEFAULT_FADE_MS
                    )
                    (curveVol * config.originalAudioVolume.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                }
            }
            DubbingAudioOutputMode.ORIGINAL_AUDIO -> config.originalAudioVolume.coerceIn(0f, 1f)
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = controllerScope.launch {
            while (isActive && !isReleased) {
                val activeMode = currentConfig.isDubbingEnabled &&
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
        stopAndReleaseActiveClip()
        runCatching { mainExoPlayer.removeListener(mainPlayerListener) }
        controllerScope.cancel()
    }

    companion object {
        const val SMART_DUCK_ACTIVE_DIALOGUE_VOLUME = 0.03f
        const val SMART_DUCK_SILENCE_BGM_VOLUME = 0.80f
        const val ROLLING_CHUNK_DURATION_MS = 30_000L

        private const val ACTIVE_SYNC_INTERVAL_MS = 35L
        private const val IDLE_SYNC_INTERVAL_MS = 200L
        private const val MIN_SEGMENT_TARGET_MS = 800L

        /**
         * Computes the playback speed multiplier to fit a synthesized clip's [actualMs] cleanly
         * inside [targetMs], clamped strictly to `1.0f..1.35f`.
         */
        fun computeLipSyncFitSpeed(actualMs: Long, targetMs: Long): Float {
            if (actualMs <= 0L || targetMs <= 0L || actualMs <= targetMs) return 1.0f
            return (actualMs.toFloat() / targetMs.toFloat()).coerceIn(1.0f, 1.35f)
        }
    }
}
