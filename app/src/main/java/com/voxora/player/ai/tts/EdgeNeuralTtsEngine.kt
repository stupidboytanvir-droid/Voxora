package com.voxora.player.ai.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.VoiceGender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
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
 * Free, Expressive, Human-Like Bengali Neural TTS engine powered by Microsoft Edge Read-Aloud
 * Neural TTS WebSocket (`bn-BD-NabanitaNeural`, `bn-BD-PradeepNeural`, `bn-IN-TanishaaNeural`,
 * `bn-IN-BashkarNeural`).
 *
 * Key guarantees:
 * - Zero API keys, 100% free, near-zero RAM footprint.
 * - Uses OkHttp WebSocket outputting `audio-24khz-48kbitrate-mono-mp3`, decoded cleanly on-device
 *   via Android [MediaExtractor] + [MediaCodec] into standard 16-bit mono PCM WAV files.
 * - Applies expressive SSML `<prosody rate="..." pitch="..." volume="...">` from original audio
 *   energy/pitch analysis ([SegmentAcousticProfile]) and strict lip-sync rate calculation
 *   clamped to `-10% .. +35%`.
 * - Automatically falls back to [AndroidSystemBengaliTtsEngine] if the device has no internet
 *   connection or if the WebSocket fails/times out.
 */
class EdgeNeuralTtsEngine(
    context: Context,
    private val offlineFallbackEngine: AndroidSystemBengaliTtsEngine
) {
    private val appContext = context.applicationContext
    private val cancelRequested = AtomicBoolean(false)
    private val activeSocketRef = AtomicReference<WebSocket?>(null)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
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

    /**
     * Checks whether the device currently has an active internet connection suitable for
     * Microsoft Edge Neural TTS WebSocket streaming.
     */
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
     * Synthesizes all [translatedSegments] into per-line WAV files using Microsoft Edge Neural
     * Bengali TTS by default, with automatic per-line or full-batch fallback to offline Android
     * [AndroidSystemBengaliTtsEngine] when offline or if the WebSocket fails.
     */
    suspend fun synthesizeSegments(
        videoUri: String,
        translatedSegments: List<TranslatedSegment>,
        characters: List<CharacterVoiceProfile>,
        segmentCharacterOverrides: Map<Int, String> = emptyMap(),
        acousticProfiles: Map<Int, SegmentAcousticProfile> = emptyMap(),
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

        // If device is completely offline, seamlessly delegate straight to Android Offline Bengali TTS
        if (!isInternetAvailable()) {
            onStatusUpdate("No internet detected · Using offline Android Bengali TTS fallback...")
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
        val videoOutputDir = getVideoDubCacheDir(videoUri).apply { mkdirs() }
        val sortedSegments = translatedSegments.sortedBy { it.startMs }
        val total = sortedSegments.size
        val results = mutableListOf<DubbedAudioSegment>()
        var consecutiveNetworkFailures = 0

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

                // Automatic Male/Female Voice Detection:
                // When acoustic F0 analysis is available and user did not manually pin a character,
                // honor the detected F0 gender (> 165 Hz -> Female, else Male).
                val effectiveGender = when {
                    acoustic != null && manualCharOverride == null -> acoustic.detectedGender
                    baseProfile != null -> baseProfile.gender
                    else -> VoiceGender.MALE
                }

                val voiceName = resolveEdgeNeuralVoice(
                    gender = effectiveGender,
                    characterId = resolvedCharId,
                    voiceIdHint = baseProfile?.voiceId
                )

                // Strict timing window: respect both dialogue duration and next segment start
                val rawTargetMs = (segment.endMs - segment.startMs).coerceAtLeast(350L)
                val nextStartMs = sortedSegments.getOrNull(idx + 1)?.startMs
                val maxSlotMs = if (nextStartMs != null && nextStartMs > segment.startMs + 250L) {
                    nextStartMs - segment.startMs
                } else {
                    rawTargetMs
                }
                val strictTargetMs = minOf(rawTargetMs, maxSlotMs).coerceAtLeast(350L)

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

                val targetWav = File(videoOutputDir, "seg_${segment.index}.wav")
                val genderTag = if (effectiveGender == VoiceGender.FEMALE) "Female" else "Male"

                val durationMs: Long = if (consecutiveNetworkFailures < MAX_CONSECUTIVE_WS_FAILURES) {
                    onStatusUpdate(
                        "Synthesizing Bengali Neural voice ${idx + 1}/$total ($genderTag · ${shortVoiceLabel(voiceName)})..."
                    )
                    val edgeResult = runCatching {
                        synthesizeEdgeLineToWav(
                            text = bengaliText,
                            voiceName = voiceName,
                            ratePercent = ratePercent,
                            pitchHz = pitchHz,
                            volume = volumeStr,
                            outputWavFile = targetWav
                        )
                    }
                    edgeResult.fold(
                        onSuccess = { wavDurMs ->
                            consecutiveNetworkFailures = 0
                            wavDurMs
                        },
                        onFailure = { err ->
                            if (err is CancellationException) throw err
                            consecutiveNetworkFailures++
                            onStatusUpdate(
                                "Edge Neural TTS unavailable (${err.message?.take(48) ?: "network"}) · Falling back to offline Android TTS..."
                            )
                            synthesizeSingleLineViaOfflineFallback(
                                videoUri = videoUri,
                                segment = segment,
                                effectiveGender = effectiveGender,
                                baseProfile = baseProfile,
                                targetWav = targetWav
                            )
                        }
                    )
                } else {
                    onStatusUpdate("Offline Android Bengali TTS fallback (${idx + 1}/$total)...")
                    synthesizeSingleLineViaOfflineFallback(
                        videoUri = videoUri,
                        segment = segment,
                        effectiveGender = effectiveGender,
                        baseProfile = baseProfile,
                        targetWav = targetWav
                    )
                }

                val appliedSpeed = (1.0f + ratePercent / 100f).coerceIn(0.90f, 1.35f)
                val appliedPitch = (1.0f + pitchHz / 40f).coerceIn(0.75f, 1.25f)

                val dubbed = DubbedAudioSegment(
                    segmentIndex = segment.index,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    characterId = resolvedCharId,
                    audioFilePath = targetWav.absolutePath,
                    durationMs = durationMs,
                    text = segment.translatedText,
                    appliedSpeed = appliedSpeed,
                    appliedPitch = appliedPitch
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
     * Synthesizes a human-like Bengali Neural preview sample for Character Management auditioning,
     * falling back to offline Android TTS if offline or WebSocket fails.
     */
    suspend fun synthesizeCharacterPreview(
        profile: CharacterVoiceProfile,
        sampleText: String = OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        if (!isInternetAvailable()) {
            return@withContext offlineFallbackEngine.synthesizeCharacterPreview(profile, sampleText)
        }

        val cleanText = OfflineBengaliTtsEngine.sanitizeBengaliText(sampleText)
            .ifBlank { OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE }
        val previewDir = File(appContext.cacheDir, "voxora_dub/previews").apply { mkdirs() }
        val safeId = profile.characterId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outputFile = File(previewDir, "preview_${safeId}.wav")

        val voiceName = resolveEdgeNeuralVoice(
            gender = profile.gender,
            characterId = profile.characterId,
            voiceIdHint = profile.voiceId
        )
        val ratePercent = (((profile.speakingRate - 1.0f) * 100f).roundToInt()).coerceIn(-10, 35)
        val pitchHz = computeEffectivePitchHz(profile = profile, acoustic = null)

        val edgeResult = runCatching {
            synthesizeEdgeLineToWav(
                text = cleanText,
                voiceName = voiceName,
                ratePercent = ratePercent,
                pitchHz = pitchHz,
                volume = "+0%",
                outputWavFile = outputFile
            )
            outputFile
        }

        edgeResult.fold(
            onSuccess = { Result.success(it) },
            onFailure = { err ->
                if (err is CancellationException) throw err
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
     * Streams a single Bengali utterance over Microsoft Edge Neural TTS WebSocket, receives
     * `audio-24khz-48kbitrate-mono-mp3`, decodes it cleanly into a 16-bit mono PCM WAV file,
     * and returns its exact duration in milliseconds.
     */
    private suspend fun synthesizeEdgeLineToWav(
        text: String,
        voiceName: String,
        ratePercent: Int,
        pitchHz: Int,
        volume: String,
        outputWavFile: File
    ): Long {
        coroutineContext.ensureActive()
        val ssml = buildSsml(
            text = text,
            voiceName = voiceName,
            ratePercent = ratePercent,
            pitchHz = pitchHz,
            volume = volume
        )

        val mp3Bytes = fetchEdgeMp3Bytes(ssml)
        if (mp3Bytes.size < 128) {
            throw IllegalStateException("Edge Neural TTS returned empty MP3 stream.")
        }

        val tempMp3 = File(outputWavFile.parentFile, "${outputWavFile.name}.${UUID.randomUUID()}.mp3")
        val tempWav = File(outputWavFile.parentFile, "${outputWavFile.name}.${UUID.randomUUID()}.wav.part")
        try {
            tempMp3.writeBytes(mp3Bytes)
            val durationMs = decodeMp3FileToMono16BitWav(tempMp3, tempWav)
            if (outputWavFile.exists()) runCatching { outputWavFile.delete() }
            if (!tempWav.renameTo(outputWavFile)) {
                tempWav.copyTo(outputWavFile, overwrite = true)
                runCatching { tempWav.delete() }
            }
            return durationMs
        } finally {
            runCatching { if (tempMp3.exists()) tempMp3.delete() }
            runCatching { if (tempWav.exists()) tempWav.delete() }
        }
    }

    private suspend fun fetchEdgeMp3Bytes(ssml: String): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val wsUrl = buildEdgeWebSocketUrl(connectionId = connectionId)
        val timestamp = formatEdgeTimestamp()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", EDGE_ORIGIN)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .header("User-Agent", EDGE_USER_AGENT)
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
                                append("X-Timestamp:").append(timestamp).append("Z\r\n")
                                append("Path:ssml\r\n\r\n")
                                append(ssml)
                            }
                            webSocket.send(configMessage)
                            webSocket.send(ssmlMessage)
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            if (completed.get()) return
                            val raw = bytes.toByteArray()
                            if (raw.size < 2) return
                            val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                            val payloadStart = 2 + headerLen
                            if (payloadStart >= raw.size) return
                            val headerText = String(raw, 2, headerLen, Charsets.UTF_8)
                            if (headerText.contains("Path:audio", ignoreCase = true)) {
                                synchronized(audioOut) {
                                    audioOut.write(raw, payloadStart, raw.size - payloadStart)
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
                                    if (resultBytes.size >= 128) {
                                        cont.resume(resultBytes)
                                    } else {
                                        cont.cancel(
                                            IllegalStateException("Edge Neural TTS closed early ($code: $reason)")
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
                                    "Edge Neural TTS HTTP $httpCode: ${t.message ?: response.message}"
                                } else {
                                    "Edge Neural TTS connection failed: ${t.message ?: "Network error"}"
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
            throw IllegalStateException("Edge Neural TTS timed out after ${EDGE_LINE_TIMEOUT_MS / 1000}s.", te)
        }
    }

    /**
     * Decodes an MP3 file (`audio-24khz-48kbitrate-mono-mp3`) into a standard 16-bit mono PCM WAV
     * file using Android's built-in [MediaExtractor] + [MediaCodec], returning duration in ms.
     */
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
                throw IllegalStateException("Decoded Edge TTS stream contained no audio track.")
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
                // Placeholder 44-byte WAV header (patched once total PCM byte length is known)
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
                                    // Downmix interleaved 16-bit LE stereo to mono
                                    val bb = java.nio.ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
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

        const val MIN_PROSODY_RATE_PERCENT = -10
        const val MAX_PROSODY_RATE_PERCENT = 35
        const val EDGE_LINE_TIMEOUT_MS = 12_000L

        private const val MAX_CONSECUTIVE_WS_FAILURES = 2
        private const val EDGE_SEC_MS_GEC_VERSION = "1-130.0.2849.68"
        private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

        private const val SPEECH_CONFIG_JSON =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

        // Natural Bengali Neural TTS pacing (~13.5 chars/sec -> ~74 ms/char + 180 ms lead/trail)
        private const val BENGALI_MS_PER_CHAR = 74L
        private const val BENGALI_FIXED_OVERHEAD_MS = 180L
        private const val WIN_EPOCH_OFFSET_SECONDS = 11_644_473_600L

        /**
         * Resolves the expressive Microsoft Edge Bengali Neural voice based on gender and optional
         * character slot so multiple speakers of the same gender can also sound distinct.
         */
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
            VOICE_FEMALE_PRIMARY -> "Nabanita (bn-BD)"
            VOICE_FEMALE_SECONDARY -> "Tanishaa (bn-IN)"
            VOICE_MALE_PRIMARY -> "Pradeep (bn-BD)"
            VOICE_MALE_SECONDARY -> "Bashkar (bn-IN)"
            else -> voiceName
        }

        /**
         * Estimates natural Bengali speech duration from character count and computes the dynamic
         * SSML `<prosody rate="...">` percentage clamped strictly within `-10% .. +35%` so dubbed
         * audio finishes inside [targetMs] and never spills into the next dialogue line.
         */
        fun computeStrictTimingRatePercent(
            bengaliText: String,
            targetMs: Long,
            characterSpeakingRate: Float = 1.0f,
            emotionRateBonusPercent: Int = 0
        ): Int {
            val charCount = bengaliText.count { !it.isWhitespace() }.coerceAtLeast(1)
            val estimatedNaturalMs = BENGALI_FIXED_OVERHEAD_MS + (charCount * BENGALI_MS_PER_CHAR)
            val safeTargetMs = targetMs.coerceAtLeast(350L)

            val timingDeltaPercent = if (estimatedNaturalMs > safeTargetMs) {
                // Speed up proportionally so the Bengali line fits comfortably inside targetMs
                val ratio = estimatedNaturalMs.toFloat() / safeTargetMs.toFloat()
                ((ratio - 1.0f) * 100f).roundToInt()
            } else if (estimatedNaturalMs * 1.45f < safeTargetMs) {
                // Slightly relax delivery when there is generous breathing room
                -6
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

        /**
         * Builds valid SSML 1.0 markup with expressive `<prosody rate="..." pitch="..." volume="...">`
         * and properly XML-escaped Bengali text.
         */
        fun buildSsml(
            text: String,
            voiceName: String,
            ratePercent: Int,
            pitchHz: Int,
            volume: String
        ): String {
            val clampedRate = ratePercent.coerceIn(MIN_PROSODY_RATE_PERCENT, MAX_PROSODY_RATE_PERCENT)
            val rateAttr = if (clampedRate >= 0) "+${clampedRate}%" else "${clampedRate}%"
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

        /**
         * Generates the Edge Read-Aloud `Sec-MS-GEC` SHA-256 token for the current 5-minute
         * Windows FILETIME window so the Edge WebSocket handshake succeeds reliably.
         */
        fun generateSecMsGec(unixTimeMillis: Long = System.currentTimeMillis()): String {
            var ticksSeconds = (unixTimeMillis / 1_000L) + WIN_EPOCH_OFFSET_SECONDS
            ticksSeconds -= (ticksSeconds % 300L)
            val ticks100Ns = ticksSeconds * 10_000_000L
            val payload = "${ticks100Ns}$TRUSTED_CLIENT_TOKEN"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }

        fun buildEdgeWebSocketUrl(
            connectionId: String = UUID.randomUUID().toString().replace("-", ""),
            unixTimeMillis: Long = System.currentTimeMillis()
        ): String {
            val secMsGec = generateSecMsGec(unixTimeMillis)
            return "$EDGE_TTS_BASE_WSS_URL&ConnectionId=$connectionId&Sec-MS-GEC=$secMsGec&Sec-MS-GEC-Version=$EDGE_SEC_MS_GEC_VERSION"
        }

        private fun formatEdgeTimestamp(): String {
            val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}
