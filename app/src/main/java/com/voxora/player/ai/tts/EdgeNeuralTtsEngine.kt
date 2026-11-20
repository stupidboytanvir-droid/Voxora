package com.voxora.player.ai.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.VoiceGender
import com.voxora.player.dubbing.VoiceGenderOverrideMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Production-grade Free Microsoft Edge Neural Bengali TTS engine (`bn-BD-NabanitaNeural`,
 * `bn-BD-PradeepNeural`, `bn-IN-TanishaaNeural`, `bn-IN-BashkarNeural`).
 *
 * - Authenticates with Microsoft's dynamic `Sec-MS-GEC` SHA-256 token and exact Edge browser headers.
 * - Extracts binary MP3 audio frames (`Path:audio`) into verified `.mp3` files (`> 1000` bytes)
 *   in `cacheDir/dub_chunks/` for instant, zero-overlap per-clip playback, plus optional WAV export copy.
 * - Publishes explicit real-time engine diagnostics ([diagnosticState]) so UI always displays
 *   either `"Active Voice: Edge Neural (Nabanita / Pradeep)"` or `"Fallback: Android TTS (Reason: ...)"`.
 * - Never fails silently: automatically falls back to offline Android [AndroidSystemBengaliTtsEngine]
 *   with the exact HTTP/WebSocket failure reason logged to UI.
 */
class EdgeNeuralTtsEngine(
    context: Context,
    private val offlineFallbackEngine: AndroidSystemBengaliTtsEngine
) {
    private val appContext = context.applicationContext
    private val cancelRequested = AtomicBoolean(false)
    private val activeSocketRef = AtomicReference<WebSocket?>(null)

    private val _diagnosticState = MutableStateFlow(TtsEngineDiagnosticState())
    val diagnosticState: StateFlow<TtsEngineDiagnosticState> = _diagnosticState.asStateFlow()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun requestCancel() {
        cancelRequested.set(true)
        activeSocketRef.getAndSet(null)?.cancel()
        offlineFallbackEngine.requestCancel()
    }

    fun release() {
        cancelRequested.set(true)
        activeSocketRef.getAndSet(null)?.cancel()
    }

    fun isInternetAvailable(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        }.getOrDefault(false)
    }

    /**
     * Synthesizes all [translatedSegments] into verified `.mp3` clips in `cacheDir/dub_chunks/`
     * (and sibling `.wav` files for export compatibility), applying:
     * - Manual [voiceGenderOverride] (`Auto` | `Female (Nabanita)` | `Male (Pradeep)`) or F0 gender detection.
     * - Expressive RMS/pitch SSML `<prosody volume="..." pitch="...">`.
     * - Dynamic lip-sync SSML `<prosody rate="+0%..+35%">` fitted to each segment's `targetMs`.
     */
    suspend fun synthesizeSegments(
        videoUri: String,
        translatedSegments: List<TranslatedSegment>,
        characters: List<CharacterVoiceProfile>,
        segmentCharacterOverrides: Map<Int, String> = emptyMap(),
        acousticProfiles: Map<Int, SegmentAcousticProfile> = emptyMap(),
        voiceGenderOverride: VoiceGenderOverrideMode = VoiceGenderOverrideMode.AUTO,
        onSegmentSynthesized: (completedCount: Int, totalCount: Int, segment: DubbedAudioSegment) -> Unit = { _, _, _ -> },
        onStatusUpdate: (String) -> Unit = {}
    ): Result<List<DubbedAudioSegment>> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No local video is currently loaded."))
        }
        if (translatedSegments.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No Bengali translated segments available. Translate the video first.")
            )
        }

        cancelRequested.set(false)

        if (!isInternetAvailable()) {
            val reason = "No internet connection"
            _diagnosticState.value = TtsEngineDiagnosticState(
                activeVoiceLabel = "Fallback: Android TTS (Reason: $reason)",
                isFallback = true,
                fallbackReason = reason
            )
            onStatusUpdate("Fallback: Android TTS (Reason: $reason)")
            return@withContext offlineFallbackEngine.synthesizeSegments(
                videoUri = videoUri,
                translatedSegments = translatedSegments,
                characters = characters,
                segmentCharacterOverrides = segmentCharacterOverrides,
                onSegmentSynthesized = onSegmentSynthesized,
                onStatusUpdate = onStatusUpdate
            )
        }

        val charactersById = characters.associateBy { it.characterId }
        val fallbackCharacter = characters.firstOrNull()
        val mp3Dir = getDubChunksDir(videoUri).apply { mkdirs() }
        val wavDir = getVideoDubCacheDir(videoUri).apply { mkdirs() }
        val sortedSegments = translatedSegments.sortedBy { it.startMs }
        val total = sortedSegments.size
        val results = mutableListOf<DubbedAudioSegment>()
        var consecutiveNetworkFailures = 0
        var lastFailureReason: String? = null

        try {
            sortedSegments.forEachIndexed { idx, segment ->
                coroutineContext.ensureActive()
                if (cancelRequested.get()) {
                    throw CancellationException("Bengali voice generation stopped by user.")
                }

                val bengaliText = OfflineBengaliTtsEngine.sanitizeBengaliText(segment.translatedText)
                if (bengaliText.isBlank()) {
                    return@forEachIndexed
                }

                val manualCharOverride = segmentCharacterOverrides[segment.index]
                val resolvedCharId = manualCharOverride
                    ?: segment.characterId
                    ?: fallbackCharacter?.characterId
                    ?: "DEFAULT_CHARACTER"
                val baseProfile = charactersById[resolvedCharId] ?: fallbackCharacter
                val acoustic = acousticProfiles[segment.index]

                // Priority: 1. Quick UI Voice Gender Override (Female/Male) -> 2. Manual character override
                // -> 3. Auto F0 gender detection (>= 155 Hz Female / < 155 Hz Male) -> 4. Profile gender
                val effectiveGender = resolveEffectiveGender(
                    overrideMode = voiceGenderOverride,
                    manualCharacterAssigned = manualCharOverride != null,
                    acoustic = acoustic,
                    profile = baseProfile
                )

                val voiceName = when (voiceGenderOverride) {
                    VoiceGenderOverrideMode.FEMALE -> VOICE_FEMALE_PRIMARY
                    VoiceGenderOverrideMode.MALE -> VOICE_MALE_PRIMARY
                    VoiceGenderOverrideMode.AUTO -> resolveEdgeNeuralVoice(
                        gender = effectiveGender,
                        characterId = resolvedCharId,
                        voiceIdHint = baseProfile?.voiceId
                    )
                }

                // Strict timing window: minimum 800ms, capped cleanly before next segment start
                val rawTargetMs = (segment.endMs - segment.startMs).coerceAtLeast(MIN_TARGET_DURATION_MS)
                val nextStartMs = sortedSegments.getOrNull(idx + 1)?.startMs
                val strictTargetMs = if (nextStartMs != null && nextStartMs > segment.startMs + 350L) {
                    minOf(rawTargetMs, nextStartMs - segment.startMs).coerceAtLeast(350L)
                } else {
                    rawTargetMs
                }

                val ratePercent = computeStrictTimingRatePercent(
                    bengaliText = bengaliText,
                    targetMs = strictTargetMs,
                    characterSpeakingRate = baseProfile?.speakingRate ?: 1.0f,
                    emotionRateBonusPercent = acoustic?.emotionRateBonusPercent ?: 0
                )
                val pitchHz = computeEffectivePitchHz(
                    profile = baseProfile,
                    acoustic = acoustic
                )
                val volumeStr = acoustic?.ssmlVolume ?: "+0%"

                val targetMp3 = File(mp3Dir, "seg_${segment.index}.mp3")
                val targetWav = File(wavDir, "seg_${segment.index}.wav")

                var clipPathForPlayback: String
                var wavPathForExport: String
                var actualDurationMs: Long

                if (consecutiveNetworkFailures < MAX_CONSECUTIVE_WS_FAILURES) {
                    onStatusUpdate(
                        "Edge Neural (${shortVoiceLabel(voiceName)}) · Line ${idx + 1}/$total..."
                    )
                    val edgeResult = runCatching {
                        synthesizeEdgeLineToMp3AndWav(
                            text = bengaliText,
                            voiceName = voiceName,
                            ratePercent = ratePercent,
                            pitchHz = pitchHz,
                            volume = volumeStr,
                            outputMp3File = targetMp3,
                            outputWavFile = targetWav
                        )
                    }
                    edgeResult.fold(
                        onSuccess = { (durMs, hasWav) ->
                            consecutiveNetworkFailures = 0
                            lastFailureReason = null
                            val activeLabel = when (voiceGenderOverride) {
                                VoiceGenderOverrideMode.FEMALE -> "Active Voice: Edge Neural (Nabanita)"
                                VoiceGenderOverrideMode.MALE -> "Active Voice: Edge Neural (Pradeep)"
                                VoiceGenderOverrideMode.AUTO -> "Active Voice: Edge Neural (Nabanita / Pradeep)"
                            }
                            _diagnosticState.value = TtsEngineDiagnosticState(
                                activeVoiceLabel = activeLabel,
                                isFallback = false,
                                fallbackReason = null
                            )
                            clipPathForPlayback = targetMp3.absolutePath
                            wavPathForExport = if (hasWav && targetWav.exists()) {
                                targetWav.absolutePath
                            } else {
                                targetMp3.absolutePath
                            }
                            actualDurationMs = durMs
                        },
                        onFailure = { err ->
                            if (err is CancellationException) throw err
                            consecutiveNetworkFailures++
                            val reason = (err.message ?: err.javaClass.simpleName).take(120)
                            lastFailureReason = reason
                            _diagnosticState.value = TtsEngineDiagnosticState(
                                activeVoiceLabel = "Fallback: Android TTS (Reason: $reason)",
                                isFallback = true,
                                fallbackReason = reason
                            )
                            onStatusUpdate("Fallback: Android TTS (Reason: $reason)")
                            val fbDur = synthesizeSingleLineViaOfflineFallback(
                                videoUri = videoUri,
                                segment = segment,
                                effectiveGender = effectiveGender,
                                baseProfile = baseProfile,
                                targetWav = targetWav
                            )
                            clipPathForPlayback = targetWav.absolutePath
                            wavPathForExport = targetWav.absolutePath
                            actualDurationMs = fbDur
                        }
                    )
                } else {
                    val reason = lastFailureReason ?: "Edge WebSocket unavailable"
                    onStatusUpdate("Fallback: Android TTS (${idx + 1}/$total · $reason)")
                    val fbDur = synthesizeSingleLineViaOfflineFallback(
                        videoUri = videoUri,
                        segment = segment,
                        effectiveGender = effectiveGender,
                        baseProfile = baseProfile,
                        targetWav = targetWav
                    )
                    clipPathForPlayback = targetWav.absolutePath
                    wavPathForExport = targetWav.absolutePath
                    actualDurationMs = fbDur
                }

                val appliedSpeed = (1.0f + ratePercent / 100f).coerceIn(1.0f, 1.35f)
                val appliedPitch = (1.0f + pitchHz / 40f).coerceIn(0.75f, 1.25f)

                val dubbed = DubbedAudioSegment(
                    segmentIndex = segment.index,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    characterId = resolvedCharId,
                    audioFilePath = wavPathForExport,
                    durationMs = actualDurationMs,
                    text = segment.translatedText,
                    appliedSpeed = appliedSpeed,
                    appliedPitch = appliedPitch,
                    clipFilePath = clipPathForPlayback
                )
                results.add(dubbed)
                onSegmentSynthesized(results.size, total, dubbed)
            }

            Result.success(results)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Auditions a Character Voice profile using Microsoft Edge Neural Bengali TTS (falling back
     * visibly to Android TTS if offline or if WebSocket fails).
     */
    suspend fun synthesizeCharacterPreview(
        profile: CharacterVoiceProfile,
        sampleText: String = OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        if (!isInternetAvailable()) {
            val reason = "No internet connection"
            _diagnosticState.value = TtsEngineDiagnosticState(
                activeVoiceLabel = "Fallback: Android TTS (Reason: $reason)",
                isFallback = true,
                fallbackReason = reason
            )
            return@withContext offlineFallbackEngine.synthesizeCharacterPreview(profile, sampleText)
        }

        val cleanText = OfflineBengaliTtsEngine.sanitizeBengaliText(sampleText)
            .ifBlank { OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE }
        val previewDir = File(appContext.cacheDir, "dub_chunks/previews").apply { mkdirs() }
        val safeId = profile.characterId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outputMp3 = File(previewDir, "preview_${safeId}.mp3")
        val outputWav = File(previewDir, "preview_${safeId}.wav")

        val voiceName = resolveEdgeNeuralVoice(
            gender = profile.gender,
            characterId = profile.characterId,
            voiceIdHint = profile.voiceId
        )
        val ratePercent = (((profile.speakingRate - 1.0f) * 100f).roundToInt())
            .coerceIn(MIN_PROSODY_RATE_PERCENT, MAX_PROSODY_RATE_PERCENT)
        val pitchHz = computeEffectivePitchHz(profile = profile, acoustic = null)

        val edgeResult = runCatching {
            synthesizeEdgeLineToMp3AndWav(
                text = cleanText,
                voiceName = voiceName,
                ratePercent = ratePercent,
                pitchHz = pitchHz,
                volume = "+0%",
                outputMp3File = outputMp3,
                outputWavFile = outputWav
            )
            outputMp3
        }

        edgeResult.fold(
            onSuccess = { mp3File ->
                _diagnosticState.value = TtsEngineDiagnosticState(
                    activeVoiceLabel = "Active Voice: Edge Neural (${shortVoiceLabel(voiceName)})",
                    isFallback = false,
                    fallbackReason = null
                )
                Result.success(mp3File)
            },
            onFailure = { err ->
                if (err is CancellationException) throw err
                val reason = (err.message ?: err.javaClass.simpleName).take(120)
                _diagnosticState.value = TtsEngineDiagnosticState(
                    activeVoiceLabel = "Fallback: Android TTS (Reason: $reason)",
                    isFallback = true,
                    fallbackReason = reason
                )
                offlineFallbackEngine.synthesizeCharacterPreview(profile, cleanText)
            }
        )
    }

    private suspend fun synthesizeSingleLineViaOfflineFallback(
        videoUri: String,
        segment: TranslatedSegment,
        effectiveGender: VoiceGender,
        baseProfile: CharacterVoiceProfile?,
        targetWav: File
    ): Long {
        val fallbackProfile = (baseProfile ?: CharacterVoiceProfile(
            speakerId = "SPEAKER_01",
            label = if (effectiveGender == VoiceGender.FEMALE) "Female Voice" else "Male Voice",
            voiceId = if (effectiveGender == VoiceGender.FEMALE) VOICE_FEMALE_PRIMARY else VOICE_MALE_PRIMARY,
            gender = effectiveGender,
            characterId = segment.characterId ?: "CHAR_001"
        )).copy(gender = effectiveGender)

        val fallbackList = offlineFallbackEngine.synthesizeSegments(
            videoUri = videoUri,
            translatedSegments = listOf(segment),
            characters = listOf(fallbackProfile),
            segmentCharacterOverrides = mapOf(segment.index to fallbackProfile.characterId)
        ).getOrThrow()

        val synthesized = fallbackList.firstOrNull()
            ?: throw IllegalStateException("Offline fallback Bengali TTS produced no audio.")
        val producedFile = File(synthesized.audioFilePath)
        if (producedFile.absolutePath != targetWav.absolutePath && producedFile.exists()) {
            producedFile.copyTo(targetWav, overwrite = true)
        }
        return synthesized.durationMs
    }

    /**
     * Streams MP3 bytes over Edge Neural TTS WebSocket, saves verified `.mp3` (`> 1000` bytes)
     * in `cacheDir/dub_chunks/`, decodes a sibling `.wav` for offline export, and returns
     * `(actualDurationMs, wavExportReady)`.
     */
    private suspend fun synthesizeEdgeLineToMp3AndWav(
        text: String,
        voiceName: String,
        ratePercent: Int,
        pitchHz: Int,
        volume: String,
        outputMp3File: File,
        outputWavFile: File
    ): Pair<Long, Boolean> {
        coroutineContext.ensureActive()
        val ssml = buildSsml(
            text = text,
            voiceName = voiceName,
            ratePercent = ratePercent,
            pitchHz = pitchHz,
            volume = volume
        )

        val mp3Bytes = fetchEdgeMp3Bytes(ssml)
        if (mp3Bytes.size <= MIN_VALID_MP3_BYTES) {
            throw IllegalStateException("Edge Neural TTS returned incomplete MP3 (${mp3Bytes.size} bytes <= $MIN_VALID_MP3_BYTES).")
        }

        outputMp3File.parentFile?.mkdirs()
        val tempMp3 = File(outputMp3File.parentFile, "${outputMp3File.name}.${UUID.randomUUID()}.part")
        try {
            tempMp3.writeBytes(mp3Bytes)
            if (tempMp3.length() <= MIN_VALID_MP3_BYTES) {
                throw IllegalStateException("Saved Edge MP3 failed size check (${tempMp3.length()} bytes).")
            }
            if (outputMp3File.exists()) runCatching { outputMp3File.delete() }
            if (!tempMp3.renameTo(outputMp3File)) {
                tempMp3.copyTo(outputMp3File, overwrite = true)
                runCatching { tempMp3.delete() }
            }
        } finally {
            runCatching { if (tempMp3.exists()) tempMp3.delete() }
        }

        if (!outputMp3File.exists() || outputMp3File.length() <= MIN_VALID_MP3_BYTES) {
            throw IllegalStateException("Verified Edge MP3 file missing or <= $MIN_VALID_MP3_BYTES bytes.")
        }

        // Best-effort WAV sibling decode for offline MP4 export; live playback uses the verified MP3 directly
        val tempWav = File(outputWavFile.parentFile, "${outputWavFile.name}.${UUID.randomUUID()}.wav.part")
        var wavReady = false
        var durationMs = 0L
        try {
            outputWavFile.parentFile?.mkdirs()
            val decodedDur = decodeMp3FileToMono16BitWav(outputMp3File, tempWav)
            if (outputWavFile.exists()) runCatching { outputWavFile.delete() }
            if (tempWav.renameTo(outputWavFile) || runCatching { tempWav.copyTo(outputWavFile, overwrite = true); true }.getOrDefault(false)) {
                wavReady = outputWavFile.exists() && outputWavFile.length() > 44L
                durationMs = decodedDur
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Live playback plays the verified MP3 directly even if OEM MediaCodec refuses WAV decode
            wavReady = false
        } finally {
            runCatching { if (tempWav.exists()) tempWav.delete() }
        }

        if (durationMs <= 0L) {
            durationMs = measureAudioFileDurationMs(outputMp3File)
                ?: ((text.length * BENGALI_MS_PER_CHAR).coerceAtLeast(450L))
        }

        return durationMs to wavReady
    }

    private fun measureAudioFileDurationMs(audioFile: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun fetchEdgeMp3Bytes(ssml: String): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val wsUrl = buildEdgeWebSocketUrl(connectionId = connectionId)
        val timestamp = formatEdgeTimestamp()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", EDGE_ORIGIN)
            .header("User-Agent", EDGE_USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .build()

        return try {
            withTimeout(EDGE_LINE_TIMEOUT_MS) {
                suspendCancellableCoroutine<ByteArray> { cont ->
                    val audioOut = ByteArrayOutputStream(24 * 1024)
                    val completed = AtomicBoolean(false)

                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (cancelRequested.get()) {
                                webSocket.cancel()
                                return
                            }
                            val configMessage = buildString {
                                append("X-Timestamp:").append(timestamp).append("\r\n")
                                append("Content-Type:application/json; charset=utf-8\r\n")
                                append("Path:speech.config\r\n\r\n")
                                append(SPEECH_CONFIG_JSON)
                                append("\r\n")
                            }
                            val ssmlMessage = buildString {
                                append("X-RequestId:").append(requestId).append("\r\n")
                                append("Content-Type:application/ssml+xml\r\n")
                                append("Path:ssml\r\n\r\n")
                                append(ssml)
                            }
                            webSocket.send(configMessage)
                            webSocket.send(ssmlMessage)
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            if (completed.get()) return
                            if (bytes.size < 2) return
                            val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                            val payloadStart = 2 + headerLen
                            if (headerLen <= 0 || payloadStart >= bytes.size) return
                            val headerText = bytes.substring(2, payloadStart).utf8()
                            if (headerText.contains("Path:audio", ignoreCase = true)) {
                                val audioSlice = bytes.substring(payloadStart).toByteArray()
                                synchronized(audioOut) {
                                    audioOut.write(audioSlice)
                                }
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("Path:turn.end", ignoreCase = true)) {
                                if (completed.compareAndSet(false, true)) {
                                    activeSocketRef.compareAndSet(webSocket, null)
                                    val resultBytes = synchronized(audioOut) { audioOut.toByteArray() }
                                    runCatching { webSocket.close(1000, "turn.end") }
                                    if (cont.isActive) {
                                        cont.resume(resultBytes)
                                    }
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            runCatching { webSocket.close(code, reason) }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            activeSocketRef.compareAndSet(webSocket, null)
                            if (completed.compareAndSet(false, true)) {
                                val resultBytes = synchronized(audioOut) { audioOut.toByteArray() }
                                if (cont.isActive) {
                                    if (resultBytes.size > MIN_VALID_MP3_BYTES) {
                                        cont.resume(resultBytes)
                                    } else {
                                        cont.cancel(
                                            IllegalStateException("WS closed $code (${reason.ifBlank { "no audio" }})")
                                        )
                                    }
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            activeSocketRef.compareAndSet(webSocket, null)
                            if (completed.compareAndSet(false, true)) {
                                val httpCode = response?.code
                                val msg = if (httpCode != null) {
                                    "HTTP $httpCode ${response.message}".trim()
                                } else {
                                    t.message ?: t.javaClass.simpleName
                                }
                                if (cont.isActive) {
                                    cont.cancel(IllegalStateException(msg, t))
                                }
                            }
                        }
                    }

                    val ws = okHttpClient.newWebSocket(request, listener)
                    activeSocketRef.set(ws)
                    cont.invokeOnCancellation {
                        completed.set(true)
                        activeSocketRef.compareAndSet(ws, null)
                        runCatching { ws.cancel() }
                    }
                }
            }
        } catch (te: TimeoutCancellationException) {
            throw IllegalStateException("WS timeout (${EDGE_LINE_TIMEOUT_MS / 1000}s)", te)
        }
    }

    private fun decodeMp3FileToMono16BitWav(mp3File: File, wavOutFile: File): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(mp3File.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                throw IllegalStateException("Decoded Edge MP3 contained no audio track.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                24_000
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var totalMonoFrames = 0L

            BufferedOutputStream(FileOutputStream(wavOutFile), 32 * 1024).use { out ->
                out.write(DubbedTrackAssembler.buildWavHeaderBytes(0L, sampleRate, 1))

                while (!sawOutputEos) {
                    if (cancelRequested.get()) {
                        throw CancellationException("Bengali voice generation stopped by user.")
                    }

                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    val pts = extractor.sampleTime.coerceAtLeast(0L)
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = codec.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunkBytes = ByteArray(bufferInfo.size)
                                outBuf.get(chunkBytes)

                                if (channelCount <= 1) {
                                    out.write(chunkBytes)
                                    totalMonoFrames += (chunkBytes.size / 2).toLong()
                                } else {
                                    val bb = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
                                    val frames = chunkBytes.size / (2 * channelCount)
                                    val monoOut = ByteArray(frames * 2)
                                    var pos = 0
                                    for (f in 0 until frames) {
                                        var sum = 0
                                        for (c in 0 until channelCount) {
                                            sum += bb.short.toInt()
                                        }
                                        val avg = (sum / channelCount).coerceIn(-32768, 32767)
                                        monoOut[pos++] = (avg and 0xFF).toByte()
                                        monoOut[pos++] = ((avg ushr 8) and 0xFF).toByte()
                                    }
                                    out.write(monoOut)
                                    totalMonoFrames += frames.toLong()
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
                out.flush()
            }

            if (totalMonoFrames <= 0L) {
                throw IllegalStateException("Edge Neural TTS MP3 decoded to zero PCM frames.")
            }

            val totalDataBytes = totalMonoFrames * 2L
            RandomAccessFile(wavOutFile, "rw").use { raf ->
                raf.seek(0L)
                raf.write(DubbedTrackAssembler.buildWavHeaderBytes(totalDataBytes, sampleRate, 1))
            }

            return ((totalMonoFrames.toDouble() / sampleRate.toDouble()) * 1_000.0)
                .toLong()
                .coerceAtLeast(100L)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun getDubChunksDir(videoUri: String): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(appContext.cacheDir, "dub_chunks/$hash")
    }

    private fun getVideoDubCacheDir(videoUri: String): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(appContext.cacheDir, "voxora_dub/$hash")
    }

    companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val EDGE_TTS_BASE_WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

        const val VOICE_FEMALE_PRIMARY = "bn-BD-NabanitaNeural"
        const val VOICE_FEMALE_SECONDARY = "bn-IN-TanishaaNeural"
        const val VOICE_MALE_PRIMARY = "bn-BD-PradeepNeural"
        const val VOICE_MALE_SECONDARY = "bn-IN-BashkarNeural"

        const val MIN_PROSODY_RATE_PERCENT = 0
        const val MAX_PROSODY_RATE_PERCENT = 35
        const val MIN_TARGET_DURATION_MS = 800L
        const val MIN_VALID_MP3_BYTES = 1_000L
        const val EDGE_LINE_TIMEOUT_MS = 12_000L
        const val BENGALI_MS_PER_CHAR = 65L

        private const val MAX_CONSECUTIVE_WS_FAILURES = 2
        private const val EDGE_SEC_MS_GEC_VERSION = "1-130.0.2849.68"
        private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

        private const val SPEECH_CONFIG_JSON =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

        private const val WIN_EPOCH_OFFSET_SECONDS = 11_644_473_600L

        fun resolveEffectiveGender(
            overrideMode: VoiceGenderOverrideMode,
            manualCharacterAssigned: Boolean,
            acoustic: SegmentAcousticProfile?,
            profile: CharacterVoiceProfile?
        ): VoiceGender {
            return when (overrideMode) {
                VoiceGenderOverrideMode.FEMALE -> VoiceGender.FEMALE
                VoiceGenderOverrideMode.MALE -> VoiceGender.MALE
                VoiceGenderOverrideMode.AUTO -> when {
                    acoustic != null && !manualCharacterAssigned -> acoustic.detectedGender
                    profile != null -> profile.gender
                    else -> VoiceGender.MALE
                }
            }
        }

        fun resolveEdgeNeuralVoice(
            gender: VoiceGender,
            characterId: String = "CHAR_001",
            voiceIdHint: String? = null
        ): String {
            val hint = voiceIdHint.orEmpty()
            return when (gender) {
                VoiceGender.FEMALE -> {
                    when {
                        hint.contains("Tanishaa", ignoreCase = true) -> VOICE_FEMALE_SECONDARY
                        hint.contains("Nabanita", ignoreCase = true) -> VOICE_FEMALE_PRIMARY
                        isSecondaryCharacterSlot(characterId, hint) -> VOICE_FEMALE_SECONDARY
                        else -> VOICE_FEMALE_PRIMARY
                    }
                }
                VoiceGender.MALE -> {
                    when {
                        hint.contains("Bashkar", ignoreCase = true) -> VOICE_MALE_SECONDARY
                        hint.contains("Pradeep", ignoreCase = true) -> VOICE_MALE_PRIMARY
                        isSecondaryCharacterSlot(characterId, hint) -> VOICE_MALE_SECONDARY
                        else -> VOICE_MALE_PRIMARY
                    }
                }
            }
        }

        private fun isSecondaryCharacterSlot(characterId: String, voiceIdHint: String): Boolean {
            if (voiceIdHint.endsWith("_02") || voiceIdHint.endsWith("_04")) return true
            val digits = characterId.filter { it.isDigit() }.toIntOrNull() ?: return false
            return digits >= 3 && digits % 2 == 0
        }

        fun shortVoiceLabel(voiceName: String): String = when (voiceName) {
            VOICE_FEMALE_PRIMARY -> "Nabanita"
            VOICE_FEMALE_SECONDARY -> "Tanishaa"
            VOICE_MALE_PRIMARY -> "Pradeep"
            VOICE_MALE_SECONDARY -> "Bashkar"
            else -> voiceName
        }

        /**
         * Estimates Bengali speech duration (`bengaliText.length * 65L` ms) and dynamically sets
         * SSML `<prosody rate="...">` strictly between `+0%` and `+35%` so dubbed audio fits inside
         * `targetMs = (segment.endMs - segment.startMs).coerceAtLeast(800L)`.
         */
        fun computeStrictTimingRatePercent(
            bengaliText: String,
            targetMs: Long,
            characterSpeakingRate: Float = 1.0f,
            emotionRateBonusPercent: Int = 0
        ): Int {
            val estimatedMs = (bengaliText.length.coerceAtLeast(1).toLong() * BENGALI_MS_PER_CHAR)
            val safeTargetMs = targetMs.coerceAtLeast(MIN_TARGET_DURATION_MS)

            val timingDeltaPercent = if (estimatedMs > safeTargetMs) {
                val ratio = estimatedMs.toFloat() / safeTargetMs.toFloat()
                ((ratio - 1.0f) * 100f).roundToInt()
            } else {
                0
            }

            val characterDeltaPercent = ((characterSpeakingRate - 1.0f) * 100f).roundToInt()
            return (timingDeltaPercent + characterDeltaPercent + emotionRateBonusPercent)
                .coerceIn(MIN_PROSODY_RATE_PERCENT, MAX_PROSODY_RATE_PERCENT)
        }

        fun computeEffectivePitchHz(
            profile: CharacterVoiceProfile?,
            acoustic: SegmentAcousticProfile?
        ): Int {
            val profilePitchDelta = if (profile != null && abs(profile.pitchFactor - 1.0f) > 0.02f) {
                ((profile.pitchFactor - 1.0f) * 18f).roundToInt()
            } else {
                0
            }
            val emotionDelta = acoustic?.ssmlPitchDeltaHz ?: 0
            return (profilePitchDelta + emotionDelta).coerceIn(-10, 12)
        }

        fun buildSsml(
            text: String,
            voiceName: String,
            ratePercent: Int,
            pitchHz: Int,
            volume: String
        ): String {
            val clampedRate = ratePercent.coerceIn(MIN_PROSODY_RATE_PERCENT, MAX_PROSODY_RATE_PERCENT)
            val rateAttr = "+${clampedRate}%"
            val pitchAttr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
            val safeVolume = volume.ifBlank { "+0%" }
            val xmlLang = if (voiceName.startsWith("bn-IN", ignoreCase = true)) "bn-IN" else "bn-BD"
            val escapedText = escapeXml(text)
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$xmlLang'>" +
                "<voice name='$voiceName'>" +
                "<prosody pitch='$pitchAttr' rate='$rateAttr' volume='$safeVolume'>$escapedText</prosody>" +
                "</voice></speak>"
        }

        fun escapeXml(raw: String): String = buildString(raw.length + 16) {
            for (ch in raw) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun generateSecMsGec(unixTimeMillis: Long = System.currentTimeMillis()): String {
            val winEpochSec = (unixTimeMillis / 1_000L) + WIN_EPOCH_OFFSET_SECONDS
            val roundedTicks = (winEpochSec - (winEpochSec % 300L)) * 10_000_000L
            return sha256Hex("${roundedTicks}$TRUSTED_CLIENT_TOKEN").uppercase(Locale.US)
        }

        fun buildEdgeWebSocketUrl(
            connectionId: String = UUID.randomUUID().toString().replace("-", ""),
            unixTimeMillis: Long = System.currentTimeMillis()
        ): String {
            val secMsGec = generateSecMsGec(unixTimeMillis)
            return "$EDGE_TTS_BASE_WSS_URL&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$EDGE_SEC_MS_GEC_VERSION&ConnectionId=$connectionId"
        }

        private fun formatEdgeTimestamp(): String {
            val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}
