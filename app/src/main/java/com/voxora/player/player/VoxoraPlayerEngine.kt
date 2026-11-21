package com.voxora.player.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.voxora.player.data.local.subtitles.SubtitleParser
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.SubtitleCue
import com.voxora.player.model.SubtitleTrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class PlayerPlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoRotationDegrees: Int = 0,
    val effectiveVideoWidth: Int = 0,
    val effectiveVideoHeight: Int = 0,
    val frameRate: Float = 30f,
    val audioSessionId: Int = 0,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val activeEmbeddedCueText: String? = null,
    val externalSubtitleName: String? = null,
    val externalSubtitleUri: String? = null,
    val useExternalSubtitle: Boolean = false,
    val subtitleSyncOffsetMs: Long = 0L,
    val isSoftwareDecoder: Boolean = false,
    val activeAudioLanguageCode: String? = null,
    val errorMessage: String? = null
)

/**
 * Flagship Media3 ExoPlayer engine for VOXORA v2.5.1:
 * - Instant HW (Hardware MediaCodec) / SW (Software fallback) decoder switching without losing playback position
 * - Smart effective video dimension computation (accounting for `unappliedRotationDegrees`)
 * - Normalized active audio track ISO-639-1 language exposure for synced Whisper AI Subtitles
 * - Audio FX integration (`LoudnessEnhancer` 0-200% boost + 5-band `Equalizer`)
 * - Direct local files + network streams (MP4, MKV, HLS `.m3u8`, DASH `.mpd`)
 * - Frame-by-frame stepping (`-1 Frame` / `+1 Frame`) when paused
 */
@OptIn(UnstableApi::class)
class VoxoraPlayerEngine(context: Context) {

    private val appContext = context.applicationContext

    private val _activePlayer = MutableStateFlow(buildExoPlayer(useSoftwareDecoder = false))
    val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    val exoPlayer: ExoPlayer
        get() = _activePlayer.value

    val audioFxController = AudioFxController()

    private val _state = MutableStateFlow(PlayerPlaybackState())
    val state: StateFlow<PlayerPlaybackState> = _state.asStateFlow()

    private var currentMediaUriString: String? = null
    private var externalCues: List<SubtitleCue> = emptyList()
    private var currentTracks: Tracks = Tracks.EMPTY
    private var isReleased = false

    private fun buildExoPlayer(useSoftwareDecoder: Boolean): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(
                if (useSoftwareDecoder) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                }
            )
            .setEnableDecoderFallback(true)
            .apply {
                if (useSoftwareDecoder) {
                    setMediaCodecSelector(
                        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                            val allDecoders = MediaCodecUtil.getDecoderInfos(
                                mimeType,
                                requiresSecureDecoder,
                                requiresTunnelingDecoder
                            )
                            val softwareDecoders = allDecoders.filter { info ->
                                info.softwareOnly ||
                                    info.name.startsWith("c2.android.", ignoreCase = true) ||
                                    info.name.startsWith("OMX.google.", ignoreCase = true)
                            }
                            if (softwareDecoders.isNotEmpty()) softwareDecoders else allDecoders
                        }
                    )
                }
            }

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val fps = exoPlayer.videoFormat?.frameRate?.takeIf { it > 1f } ?: _state.value.frameRate
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isEnded = playbackState == Player.STATE_ENDED,
                durationMs = exoPlayer.duration.takeIf { it > 0L } ?: _state.value.durationMs,
                frameRate = fps
            )
            if (playbackState == Player.STATE_READY) {
                val sid = exoPlayer.audioSessionId
                if (sid > 0 && sid != _state.value.audioSessionId) {
                    audioFxController.attachToAudioSession(sid)
                    _state.value = _state.value.copy(audioSessionId = sid)
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId > 0) {
                audioFxController.attachToAudioSession(audioSessionId)
                _state.value = _state.value.copy(audioSessionId = audioSessionId)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.value = _state.value.copy(playbackSpeed = playbackParameters.speed)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val rotation = exoPlayer.videoFormat?.rotationDegrees
                ?.takeIf { it != 0 }
                ?: videoSize.unappliedRotationDegrees
            val (effW, effH) = computeEffectiveVideoDimensions(
                width = videoSize.width,
                height = videoSize.height,
                rotationDegrees = rotation
            )
            val fps = exoPlayer.videoFormat?.frameRate?.takeIf { it > 1f } ?: _state.value.frameRate
            _state.value = _state.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
                videoRotationDegrees = rotation,
                effectiveVideoWidth = effW,
                effectiveVideoHeight = effH,
                frameRate = fps
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            currentTracks = tracks
            rebuildTrackLists(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues
                .mapNotNull { it.text?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .ifBlank { null }
            _state.value = _state.value.copy(activeEmbeddedCueText = text)
        }

        override fun onPlayerError(error: PlaybackException) {
            val friendlyMessage = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    "Unable to access this video file. It may have been moved, deleted, or permission was revoked."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Unable to connect to the network stream. Check the URL and your internet connection."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                    "This video codec or container format is not supported on this device."
                else -> error.localizedMessage ?: "Playback failed due to an unexpected media error."
            }
            _state.value = _state.value.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = friendlyMessage
            )
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    fun prepareMedia(
        uriString: String,
        startPositionMs: Long = 0L,
        playbackSpeed: Float = 1.0f,
        autoPlay: Boolean = true
    ) {
        if (isReleased) return
        currentMediaUriString = uriString
        _state.value = PlayerPlaybackState(
            playbackSpeed = playbackSpeed,
            subtitleSyncOffsetMs = _state.value.subtitleSyncOffsetMs,
            isSoftwareDecoder = _state.value.isSoftwareDecoder
        )
        val mediaItem = MediaItem.fromUri(Uri.parse(uriString))
        exoPlayer.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        exoPlayer.setPlaybackSpeed(playbackSpeed.coerceIn(0.25f, 3.0f))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = autoPlay
    }

    /**
     * Smoothly switches between HW (Hardware MediaCodec) and SW (Software/Extension fallback)
     * decoders while preserving exact playback position, play/pause state, speed, and volume.
     *
     * @return `true` if now using SW (Software) decoder, `false` if HW (Hardware).
     */
    fun toggleDecoderMode(): Boolean {
        if (isReleased) return _state.value.isSoftwareDecoder
        val nextUseSoftware = !_state.value.isSoftwareDecoder
        val oldPlayer = _activePlayer.value
        val savedPositionMs = oldPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = oldPlayer.playWhenReady || oldPlayer.isPlaying
        val savedSpeed = _state.value.playbackSpeed.coerceIn(0.25f, 3.0f)
        val savedVolume = oldPlayer.volume
        val useExternalSub = _state.value.useExternalSubtitle

        val newPlayer = buildExoPlayer(useSoftwareDecoder = nextUseSoftware)
        newPlayer.addListener(listener)

        runCatching {
            oldPlayer.removeListener(listener)
            oldPlayer.release()
        }

        _activePlayer.value = newPlayer
        _state.value = _state.value.copy(
            isSoftwareDecoder = nextUseSoftware,
            errorMessage = null
        )

        val uri = currentMediaUriString
        if (!uri.isNullOrBlank()) {
            if (useExternalSub) {
                newPlayer.trackSelectionParameters = newPlayer.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            newPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uri)), savedPositionMs)
            newPlayer.setPlaybackSpeed(savedSpeed)
            newPlayer.volume = savedVolume
            newPlayer.prepare()
            newPlayer.playWhenReady = wasPlaying
        }

        return nextUseSoftware
    }

    fun pollPosition() {
        if (isReleased) return
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: _state.value.durationMs
        if (pos != _state.value.currentPositionMs || dur != _state.value.durationMs) {
            _state.value = _state.value.copy(
                currentPositionMs = pos,
                durationMs = dur
            )
        }
    }

    fun togglePlayPause() {
        if (isReleased) return
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0L)
            exoPlayer.play()
        } else if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun pause() {
        if (!isReleased && exoPlayer.isPlaying) {
            exoPlayer.pause()
        }
    }

    fun play() {
        if (!isReleased) {
            exoPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (isReleased) return
        val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, maxDur)
        exoPlayer.seekTo(clamped)
        _state.value = _state.value.copy(currentPositionMs = clamped)
    }

    fun seekBy(deltaMs: Long) {
        if (isReleased) return
        seekTo(exoPlayer.currentPosition + deltaMs)
    }

    /**
     * Frame-by-frame stepping (`-1 Frame` / `+1 Frame`) when paused.
     */
    fun stepFrame(forward: Boolean) {
        if (isReleased) return
        if (exoPlayer.isPlaying) exoPlayer.pause()
        val fps = _state.value.frameRate.coerceIn(12f, 120f)
        val frameDurationMs = (1_000f / fps).toLong().coerceAtLeast(16L)
        val delta = if (forward) frameDurationMs else -frameDurationMs
        seekTo(exoPlayer.currentPosition + delta)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (isReleased) return
        val clamped = speed.coerceIn(0.25f, 3.0f)
        exoPlayer.setPlaybackSpeed(clamped)
        _state.value = _state.value.copy(playbackSpeed = clamped)
    }

    fun setOriginalAudioVolume(volume: Float) {
        if (isReleased) return
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    fun applyVolumeBoost(percent: Int) {
        val sid = exoPlayer.audioSessionId
        if (sid > 0) audioFxController.attachToAudioSession(sid)
        audioFxController.applyVolumeBoost(percent)
    }

    fun applyEqPreset(preset: AudioEqPreset) {
        val sid = exoPlayer.audioSessionId
        if (sid > 0) audioFxController.attachToAudioSession(sid)
        audioFxController.applyEqPreset(preset)
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        if (isReleased) return
        val groups = currentTracks.groups
        if (track.groupIndex !in groups.indices) return
        val mediaGroup = groups[track.groupIndex].mediaTrackGroup
        val params = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(mediaGroup, track.trackIndex))
            .build()
        exoPlayer.trackSelectionParameters = params
    }

    fun selectEmbeddedSubtitle(track: SubtitleTrackInfo) {
        if (isReleased) return
        if (track.isExternal) {
            _state.value = _state.value.copy(useExternalSubtitle = true)
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            rebuildTrackLists(currentTracks)
            return
        }

        val groups = currentTracks.groups
        if (track.groupIndex !in groups.indices) return
        val mediaGroup = groups[track.groupIndex].mediaTrackGroup
        _state.value = _state.value.copy(useExternalSubtitle = false)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(mediaGroup, track.trackIndex))
            .build()
        rebuildTrackLists(currentTracks)
    }

    fun disableSubtitles() {
        if (isReleased) return
        _state.value = _state.value.copy(
            useExternalSubtitle = false,
            activeEmbeddedCueText = null
        )
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        rebuildTrackLists(currentTracks)
    }

    fun loadExternalSubtitle(
        uriString: String,
        name: String,
        rawContent: String,
        syncOffsetMs: Long = _state.value.subtitleSyncOffsetMs
    ): Boolean {
        val parsed = SubtitleParser.parse(rawContent, name)
        externalCues = parsed
        _state.value = _state.value.copy(
            externalSubtitleUri = uriString,
            externalSubtitleName = name,
            useExternalSubtitle = true,
            subtitleSyncOffsetMs = syncOffsetMs
        )
        if (!isReleased) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            rebuildTrackLists(currentTracks)
        }
        return parsed.isNotEmpty()
    }

    fun clearExternalSubtitle() {
        externalCues = emptyList()
        _state.value = _state.value.copy(
            externalSubtitleUri = null,
            externalSubtitleName = null,
            useExternalSubtitle = false
        )
        rebuildTrackLists(currentTracks)
    }

    fun setSubtitleSyncOffset(offsetMs: Long) {
        _state.value = _state.value.copy(subtitleSyncOffsetMs = offsetMs.coerceIn(-10_000L, 10_000L))
    }

    fun getCurrentSubtitleText(): String? {
        val s = _state.value
        return if (s.useExternalSubtitle) {
            SubtitleParser.findActiveCueText(
                cues = externalCues,
                playbackPositionMs = s.currentPositionMs,
                syncOffsetMs = s.subtitleSyncOffsetMs
            )
        } else {
            s.activeEmbeddedCueText
        }
    }

    fun release() {
        if (!isReleased) {
            isReleased = true
            audioFxController.release()
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    private fun rebuildTrackLists(tracks: Tracks) {
        val audioList = mutableListOf<AudioTrackInfo>()
        val subList = mutableListOf<SubtitleTrackInfo>()
        val s = _state.value
        var selectedAudioLangCode: String? = null

        tracks.groups.forEachIndexed { groupIndex, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val langCode = format.language ?: "und"
                        val langName = formatLanguageName(langCode)
                        val isTrackSelected = group.isTrackSelected(i)
                        if (isTrackSelected) {
                            selectedAudioLangCode = normalizeWhisperLanguageCode(langCode)
                        }
                        val label = format.label?.takeIf { it.isNotBlank() }
                            ?: if (format.channelCount > 0) "$langName (${format.channelCount}ch)" else langName
                        audioList.add(
                            AudioTrackInfo(
                                groupIndex = groupIndex,
                                trackIndex = i,
                                label = label,
                                language = langName,
                                channelCount = format.channelCount.coerceAtLeast(0),
                                isSelected = isTrackSelected
                            )
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val langCode = format.language ?: "und"
                        val langName = formatLanguageName(langCode)
                        val label = format.label?.takeIf { it.isNotBlank() } ?: langName
                        subList.add(
                            SubtitleTrackInfo(
                                id = "emb_${groupIndex}_$i",
                                groupIndex = groupIndex,
                                trackIndex = i,
                                label = label,
                                language = langName,
                                isSelected = !s.useExternalSubtitle && group.isTrackSelected(i),
                                isExternal = false
                            )
                        )
                    }
                }
            }
        }

        if (s.externalSubtitleName != null) {
            subList.add(
                0,
                SubtitleTrackInfo(
                    id = "external_sub",
                    groupIndex = -1,
                    trackIndex = -1,
                    label = "${s.externalSubtitleName} (External)",
                    language = "External",
                    isSelected = s.useExternalSubtitle,
                    isExternal = true
                )
            )
        }

        _state.value = _state.value.copy(
            audioTracks = audioList,
            subtitleTracks = subList,
            activeAudioLanguageCode = selectedAudioLangCode
        )
    }

    private fun formatLanguageName(code: String): String {
        if (code == "und" || code.isBlank()) return "Unknown"
        val locale = Locale.forLanguageTag(code)
        return locale.displayLanguage.takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
    }

    companion object {
        /**
         * Computes the true visual (width, height) after applying rotation metadata
         * (`unappliedRotationDegrees` / format rotation). Swaps width and height for 90°/270°.
         */
        fun computeEffectiveVideoDimensions(
            width: Int,
            height: Int,
            rotationDegrees: Int
        ): Pair<Int, Int> {
            val norm = ((rotationDegrees % 360) + 360) % 360
            return if (norm == 90 || norm == 270) {
                height to width
            } else {
                width to height
            }
        }

        fun isLandscapeVideo(effectiveWidth: Int, effectiveHeight: Int): Boolean {
            return effectiveWidth > 0 && effectiveHeight > 0 && effectiveWidth > effectiveHeight
        }

        fun isNetworkStreamUri(uriString: String): Boolean {
            val lower = uriString.trim().lowercase(Locale.ROOT)
            return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("rtsp://")
        }

        private val ISO3_TO_WHISPER_ISO2 = mapOf(
            "eng" to "en",
            "kor" to "ko",
            "zho" to "zh",
            "chi" to "zh",
            "cmn" to "zh",
            "yue" to "zh",
            "hin" to "hi",
            "jpn" to "ja",
            "ben" to "bn",
            "spa" to "es",
            "fra" to "fr",
            "fre" to "fr",
            "deu" to "de",
            "ger" to "de",
            "rus" to "ru",
            "ara" to "ar",
            "por" to "pt",
            "ita" to "it",
            "tur" to "tr",
            "ind" to "id",
            "vie" to "vi",
            "tha" to "th",
            "urd" to "ur",
            "tam" to "ta",
            "tel" to "te",
            "msa" to "ms",
            "may" to "ms",
            "fas" to "fa",
            "per" to "fa",
            "nld" to "nl",
            "dut" to "nl",
            "pol" to "pl",
            "ukr" to "uk",
            "swe" to "sv",
            "nor" to "no",
            "dan" to "da",
            "fin" to "fi",
            "ell" to "el",
            "gre" to "el",
            "heb" to "he",
            "ces" to "cs",
            "cze" to "cs",
            "ron" to "ro",
            "rum" to "ro",
            "hun" to "hu",
            "fil" to "tl",
            "tgl" to "tl",
            "mar" to "mr",
            "guj" to "gu",
            "pan" to "pa",
            "kan" to "kn",
            "mal" to "ml"
        )

        /**
         * Normalizes ExoPlayer track language tags (BCP-47 or MKV 3-letter ISO-639-2 like
         * `"eng"`, `"kor"`, `"zho"`, `"en-US"`) into 2-letter Whisper ISO-639-1 codes (`"en"`,
         * `"ko"`, `"zh"`), returning `null` when unknown/`"und"` so Whisper uses `"auto"`.
         */
        fun normalizeWhisperLanguageCode(rawCode: String?): String? {
            val cleaned = rawCode?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (cleaned.isBlank() || cleaned == "und" || cleaned == "unknown" || cleaned == "mul" || cleaned == "zxx") {
                return null
            }
            val primary = cleaned.substringBefore('-').substringBefore('_')
            if (primary.isBlank() || primary == "und") return null
            ISO3_TO_WHISPER_ISO2[primary]?.let { return it }
            if (primary.length == 2 && primary.all { it in 'a'..'z' }) {
                return primary
            }
            val localeLang = Locale.forLanguageTag(cleaned).language.lowercase(Locale.ROOT)
            return if (localeLang.length == 2 && localeLang != "un" && localeLang.all { it in 'a'..'z' }) {
                localeLang
            } else {
                null
            }
        }
    }
}
