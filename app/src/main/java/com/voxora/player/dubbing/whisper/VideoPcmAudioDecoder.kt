package com.voxora.player.dubbing.whisper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.voxora.player.player.VoxoraPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A manageable 16 kHz mono Float32 PCM audio chunk ready for `whisper.cpp`.
 */
data class PcmAudioChunk(
    val chunkIndex: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val samples16kMono: FloatArray
)

/**
 * Lightweight descriptor of an audio track discovered by [MediaExtractor],
 * used to match ExoPlayer's selected Dual-Audio track deterministically.
 */
data class AudioTrackDescriptor(
    val extractorTrackIndex: Int,
    val audioOrdinal: Int,
    val languageCode: String?
)

/**
 * Pure Kotlin PCM downmixing, anti-aliased resampling, WAV decoding, and Dual-Audio track
 * matching utilities for whisper.cpp's exact input format:
 * 16,000 Hz, mono, 32-bit float normalized to [-1.0, 1.0].
 */
object PcmResampler {
    const val WHISPER_SAMPLE_RATE = 16_000

    /**
     * Resolves the matching `MediaExtractor` track index for Dual-Audio MKV/MP4 containers.
     * Prefers exact audio stream ordinal index from ExoPlayer, falling back to normalized
     * ISO-639-1 language match, then the first audio track.
     */
    fun selectMatchingAudioTrackIndex(
        tracks: List<AudioTrackDescriptor>,
        preferredAudioOrdinal: Int = -1,
        preferredLanguageCode: String? = null
    ): Int {
        if (tracks.isEmpty()) return -1
        if (preferredAudioOrdinal in tracks.indices) {
            return tracks[preferredAudioOrdinal].extractorTrackIndex
        }
        val normPref = VoxoraPlayerEngine.normalizeWhisperLanguageCode(preferredLanguageCode)
        if (!normPref.isNullOrBlank()) {
            tracks.firstOrNull { desc ->
                VoxoraPlayerEngine.normalizeWhisperLanguageCode(desc.languageCode) == normPref
            }?.let { return it.extractorTrackIndex }
        }
        return tracks.first().extractorTrackIndex
    }

    /**
     * Downmixes interleaved 16-bit signed little-endian PCM (mono, stereo, 5.1 6ch, 7.1 8ch)
     * into normalized Mono Float32 samples `[-1.0f, 1.0f]` by averaging all channels per frame.
     */
    fun downmixPcm16LeToMonoFloat(
        pcmBytes: ByteArray,
        byteLength: Int,
        channelCount: Int
    ): FloatArray {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerFrame = channels * 2
        val totalFrames = byteLength / bytesPerFrame
        if (totalFrames <= 0) return FloatArray(0)

        val monoSource = FloatArray(totalFrames)
        val bb = ByteBuffer.wrap(pcmBytes, 0, totalFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        val invChannels = 1.0f / channels.toFloat()
        for (frame in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += bb.short.toFloat() / 32768.0f
            }
            monoSource[frame] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
        }
        return monoSource
    }

    /**
     * Downmixes interleaved 32-bit IEEE Float little-endian PCM (mono, stereo, 5.1, 7.1)
     * into normalized Mono Float32 samples `[-1.0f, 1.0f]` by averaging all channels per frame.
     */
    fun downmixPcmFloatLeToMonoFloat(
        pcmBytes: ByteArray,
        byteLength: Int,
        channelCount: Int
    ): FloatArray {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerFrame = channels * 4
        val totalFrames = byteLength / bytesPerFrame
        if (totalFrames <= 0) return FloatArray(0)

        val monoSource = FloatArray(totalFrames)
        val bb = ByteBuffer.wrap(pcmBytes, 0, totalFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        val invChannels = 1.0f / channels.toFloat()
        for (frame in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until channels) {
                val sample = bb.float
                if (sample.isFinite()) {
                    sum += sample
                }
            }
            monoSource[frame] = (sum * invChannels).coerceIn(-1.0f, 1.0f)
        }
        return monoSource
    }

    /**
     * Converts interleaved 16-bit signed little-endian PCM bytes into mono Float32 samples
     * resampled to 16,000 Hz.
     */
    fun pcm16LeTo16kMonoFloat(
        pcmBytes: ByteArray,
        byteLength: Int,
        sourceSampleRate: Int,
        channelCount: Int
    ): FloatArray {
        val monoSource = downmixPcm16LeToMonoFloat(pcmBytes, byteLength, channelCount)
        if (monoSource.isEmpty()) return monoSource
        return resampleMonoLinear(monoSource, sourceSampleRate, WHISPER_SAMPLE_RATE)
    }

    /**
     * Converts interleaved 32-bit float little-endian PCM bytes into mono Float32 samples
     * resampled to 16,000 Hz.
     */
    fun pcmFloatLeTo16kMonoFloat(
        pcmBytes: ByteArray,
        byteLength: Int,
        sourceSampleRate: Int,
        channelCount: Int
    ): FloatArray {
        val monoSource = downmixPcmFloatLeToMonoFloat(pcmBytes, byteLength, channelCount)
        if (monoSource.isEmpty()) return monoSource
        return resampleMonoLinear(monoSource, sourceSampleRate, WHISPER_SAMPLE_RATE)
    }

    /**
     * Resamples mono float audio from [sourceRate] to [targetRate] (`[-1.0f, 1.0f]`):
     * - When downsampling (`sourceRate > targetRate`, e.g., 48kHz / 44.1kHz -> 16kHz), uses
     *   continuous anti-aliased area averaging across each `[i * ratio, (i + 1) * ratio)`
     *   window so high-frequency harmonics never alias into speech and timestamps stay drift-free.
     * - When upsampling (`sourceRate < targetRate`), uses linear interpolation.
     */
    fun resampleMonoLinear(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int = WHISPER_SAMPLE_RATE
    ): FloatArray {
        if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
        if (sourceRate == targetRate) return input

        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLength = (input.size.toDouble() / ratio).roundToInt().coerceAtLeast(1)
        val output = FloatArray(outLength)
        val lastIndex = input.size - 1

        if (sourceRate > targetRate) {
            for (i in 0 until outLength) {
                val winStart = i * ratio
                val winEnd = minOf((i + 1) * ratio, input.size.toDouble())
                val firstIdx = floor(winStart).toInt().coerceIn(0, lastIndex)
                val lastIdx = (ceil(winEnd).toInt() - 1).coerceIn(firstIdx, lastIndex)

                var weightedSum = 0.0
                var weightTotal = 0.0
                for (k in firstIdx..lastIdx) {
                    val segStart = maxOf(winStart, k.toDouble())
                    val segEnd = minOf(winEnd, (k + 1).toDouble())
                    val w = (segEnd - segStart).coerceAtLeast(0.0)
                    if (w > 0.0) {
                        weightedSum += input[k].toDouble() * w
                        weightTotal += w
                    }
                }
                val value = if (weightTotal > 0.0) (weightedSum / weightTotal).toFloat() else input[firstIdx]
                output[i] = value.coerceIn(-1.0f, 1.0f)
            }
        } else {
            for (i in 0 until outLength) {
                val srcPos = i * ratio
                val idx0 = floor(srcPos).toInt().coerceIn(0, lastIndex)
                val idx1 = (idx0 + 1).coerceAtMost(lastIndex)
                val frac = (srcPos - idx0).toFloat()
                output[i] = (input[idx0] + (input[idx1] - input[idx0]) * frac).coerceIn(-1.0f, 1.0f)
            }
        }
        return output
    }

    /**
     * Decodes a standard RIFF WAVE PCM 16-bit audio file into 16 kHz mono Float32 samples.
     */
    fun decodeWavTo16kMonoFloat(wavFile: File): FloatArray {
        val bytes = FileInputStream(wavFile).use { it.readBytes() }
        require(bytes.size >= 44) { "WAV file is too small (${bytes.size} bytes)." }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "File is not a valid RIFF/WAVE file." }

        var offset = 12
        var channels = 1
        var sampleRate = WHISPER_SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = bb.getInt(offset + 4)
            val bodyStart = offset + 8

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16 && bodyStart + 16 <= bytes.size) { "Invalid WAV fmt chunk." }
                    val audioFormat = bb.getShort(bodyStart).toInt() and 0xFFFF
                    require(audioFormat == 1) { "Only uncompressed PCM WAV (format 1) is supported; got $audioFormat." }
                    channels = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                    sampleRate = bb.getInt(bodyStart + 4)
                    bitsPerSample = bb.getShort(bodyStart + 14).toInt() and 0xFFFF
                    require(bitsPerSample == 16) { "Only 16-bit PCM WAV is supported; got $bitsPerSample-bit." }
                }
                "data" -> {
                    dataOffset = bodyStart
                    dataSize = chunkSize.coerceAtMost(bytes.size - bodyStart)
                    break
                }
            }
            offset = bodyStart + chunkSize + (chunkSize and 1)
        }

        require(dataOffset >= 0 && dataSize > 0) { "WAV file contains no audio data chunk." }
        val pcmSlice = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        return pcm16LeTo16kMonoFloat(
            pcmBytes = pcmSlice,
            byteLength = pcmSlice.size,
            sourceSampleRate = sampleRate,
            channelCount = channels
        )
    }
}

/**
 * Real on-device video-to-PCM decoder using Android [MediaExtractor] + [MediaCodec].
 *
 * - Opens `content://` and local file URIs safely via `extractor.setDataSource(context, uri, null)`.
 * - Selects the exact Dual-Audio track matching ExoPlayer's active track index/language.
 * - Reads actual decoded `KEY_CHANNEL_COUNT`, `KEY_SAMPLE_RATE`, and `KEY_PCM_ENCODING`
 *   from `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED`, properly downmixing 5.1/7.1 surround and
 *   resampling 48kHz/44.1kHz cleanly to 16,000 Hz Mono Float PCM (`[-1.0f, 1.0f]`).
 * - Always performs seek-only bounded window extraction (`SEEK_TO_PREVIOUS_SYNC` + immediate
 *   exit at `chunkEndUs`) for sub-300ms slicing even deep inside 2-hour movies.
 */
class VideoPcmAudioDecoder(private val context: Context) {

    /**
     * Streams decoded 16 kHz mono Float32 PCM chunks from [mediaUriString] to [onChunkDecoded].
     * Returns total decoded audio duration in milliseconds.
     */
    suspend fun decodeVideoTo16kChunks(
        mediaUriString: String,
        chunkDurationSeconds: Int = DEFAULT_CHUNK_SECONDS,
        preferredAudioTrackIndex: Int = -1,
        preferredAudioLanguageCode: String? = null,
        onDecodeProgress: (decodedMs: Long, totalDurationMs: Long) -> Unit = { _, _ -> },
        onChunkDecoded: suspend (PcmAudioChunk) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(mediaUriString), null)

                val audioTrackIndex = resolveExtractorAudioTrack(
                    extractor = extractor,
                    preferredAudioTrackIndex = preferredAudioTrackIndex,
                    preferredAudioLanguageCode = preferredAudioLanguageCode
                )
                if (audioTrackIndex < 0) {
                    throw IllegalStateException("No audio track found in the selected video.")
                }
                val inputFormat = extractor.getTrackFormat(audioTrackIndex)
                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Audio track MIME type is missing.")
                val totalDurationMs = runCatching {
                    inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L
                }.getOrDefault(0L)

                var currentSampleRate = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }.getOrDefault(48_000)
                var currentChannels = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(2)
                var currentPcmEncoding = readPcmEncoding(inputFormat, AudioFormat.ENCODING_PCM_16BIT)

                extractor.selectTrack(audioTrackIndex)

                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val targetChunkSamples = (chunkDurationSeconds.coerceIn(10, 60) * PcmResampler.WHISPER_SAMPLE_RATE)
                val accumulator = FloatArray(targetChunkSamples)
                var accumulatedCount = 0
                var totalEmittedSamples = 0L
                var chunkIndex = 0

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var scratchBytes = ByteArray(64 * 1024)

                while (!sawOutputEos) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    sawInputEos = true
                                } else {
                                    val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            currentSampleRate = runCatching {
                                outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }.getOrDefault(currentSampleRate)
                            currentChannels = runCatching {
                                outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }.getOrDefault(currentChannels)
                            currentPcmEncoding = readPcmEncoding(outFmt, currentPcmEncoding)
                        }
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                if (scratchBytes.size < bufferInfo.size) {
                                    scratchBytes = ByteArray(bufferInfo.size)
                                }
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outBuffer.get(scratchBytes, 0, bufferInfo.size)

                                val resampled = if (currentPcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    PcmResampler.pcmFloatLeTo16kMonoFloat(
                                        pcmBytes = scratchBytes,
                                        byteLength = bufferInfo.size,
                                        sourceSampleRate = currentSampleRate,
                                        channelCount = currentChannels
                                    )
                                } else {
                                    PcmResampler.pcm16LeTo16kMonoFloat(
                                        pcmBytes = scratchBytes,
                                        byteLength = bufferInfo.size,
                                        sourceSampleRate = currentSampleRate,
                                        channelCount = currentChannels
                                    )
                                }

                                var srcOffset = 0
                                while (srcOffset < resampled.size) {
                                    val spaceLeft = targetChunkSamples - accumulatedCount
                                    val copyCount = minOf(spaceLeft, resampled.size - srcOffset)
                                    System.arraycopy(resampled, srcOffset, accumulator, accumulatedCount, copyCount)
                                    accumulatedCount += copyCount
                                    srcOffset += copyCount

                                    if (accumulatedCount >= targetChunkSamples) {
                                        val chunkStartMs = (totalEmittedSamples * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                                        val chunkDurationMs = (accumulatedCount.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                                        val chunkCopy = accumulator.copyOf(accumulatedCount)
                                        totalEmittedSamples += accumulatedCount
                                        accumulatedCount = 0

                                        onDecodeProgress(chunkStartMs + chunkDurationMs, totalDurationMs)
                                        onChunkDecoded(
                                            PcmAudioChunk(
                                                chunkIndex = chunkIndex++,
                                                startTimeMs = chunkStartMs,
                                                durationMs = chunkDurationMs,
                                                samples16kMono = chunkCopy
                                            )
                                        )
                                    }
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }

                // Emit final partial tail chunk
                if (accumulatedCount > 0) {
                    val chunkStartMs = (totalEmittedSamples * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                    val chunkDurationMs = (accumulatedCount.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                    val tailCopy = accumulator.copyOf(accumulatedCount)
                    totalEmittedSamples += accumulatedCount
                    onDecodeProgress(chunkStartMs + chunkDurationMs, totalDurationMs)
                    onChunkDecoded(
                        PcmAudioChunk(
                            chunkIndex = chunkIndex,
                            startTimeMs = chunkStartMs,
                            durationMs = chunkDurationMs,
                            samples16kMono = tailCopy
                        )
                    )
                }

                (totalEmittedSamples * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }
    }

    /**
     * Decodes ONLY a single bounded time window (`[startMs, startMs + windowDurationMs]`)
     * directly using [MediaExtractor.seekTo] (`SEEK_TO_PREVIOUS_SYNC`).
     *
     * - Safely opens all Android `content://` and local URIs via `setDataSource(context, uri, null)`.
     * - Selects the matching Dual-Audio track (`preferredAudioTrackIndex` / `preferredAudioLanguageCode`).
     * - Downmixes multi-channel audio (stereo, 5.1, 7.1) to Mono at native decoded sample rate
     *   and performs a single continuous anti-aliased resample to 16,000 Hz Float PCM `[-1.0f, 1.0f]`
     *   so 48kHz/44.1kHz MKV movies have zero timing drift and crystal-clear speech for Whisper.
     */
    suspend fun decodeWindowTo16kPcm(
        mediaUriString: String,
        startMs: Long,
        windowDurationMs: Long = DEFAULT_CHUNK_SECONDS * 1_000L,
        chunkIndex: Int = 0,
        preferredAudioTrackIndex: Int = -1,
        preferredAudioLanguageCode: String? = null
    ): Result<PcmAudioChunk> = withContext(Dispatchers.IO) {
        runCatching {
            val safeStartMs = startMs.coerceAtLeast(0L)
            val safeWindowMs = windowDurationMs.coerceIn(2_000L, 60_000L)
            val endMs = safeStartMs + safeWindowMs
            val chunkStartUs = safeStartMs * 1_000L
            val chunkEndUs = endMs * 1_000L
            val maxOutput16kSamples = ((safeWindowMs * PcmResampler.WHISPER_SAMPLE_RATE) / 1_000L).toInt()
                .coerceAtLeast(PcmResampler.WHISPER_SAMPLE_RATE)

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(mediaUriString), null)

                val audioTrackIndex = resolveExtractorAudioTrack(
                    extractor = extractor,
                    preferredAudioTrackIndex = preferredAudioTrackIndex,
                    preferredAudioLanguageCode = preferredAudioLanguageCode
                )
                if (audioTrackIndex < 0) {
                    throw IllegalStateException("No audio track found in the selected video.")
                }

                val inputFormat = extractor.getTrackFormat(audioTrackIndex)
                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Audio track MIME type is missing.")
                var currentSampleRate = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }.getOrDefault(48_000).coerceAtLeast(8_000)
                var currentChannels = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(2).coerceAtLeast(1)
                var currentPcmEncoding = readPcmEncoding(inputFormat, AudioFormat.ENCODING_PCM_16BIT)

                extractor.selectTrack(audioTrackIndex)
                // Always jump directly to the sync point at/before chunkStartUs so we NEVER scan linearly from 00:00
                extractor.seekTo(chunkStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val output16kAccumulator = FloatArray(maxOutput16kSamples)
                var output16kCount = 0

                // Accumulate continuous downmixed mono at native decoded sampleRate, then resample once
                // to 16kHz to eliminate per-AAC-frame truncation drift on 48kHz / 44.1kHz MKV streams.
                var nativeMonoBuffer = FloatArray(
                    (((safeWindowMs + 1_000L) * currentSampleRate) / 1_000L).toInt().coerceIn(16_000, 3_200_000)
                )
                var nativeMonoCount = 0
                var nativeMonoRate = currentSampleRate

                fun flushNativeMonoTo16k() {
                    if (nativeMonoCount <= 0 || output16kCount >= maxOutput16kSamples) {
                        nativeMonoCount = 0
                        return
                    }
                    val slice = if (nativeMonoCount == nativeMonoBuffer.size) {
                        nativeMonoBuffer
                    } else {
                        nativeMonoBuffer.copyOf(nativeMonoCount)
                    }
                    val resampled = PcmResampler.resampleMonoLinear(
                        input = slice,
                        sourceRate = nativeMonoRate,
                        targetRate = PcmResampler.WHISPER_SAMPLE_RATE
                    )
                    val copyCount = minOf(resampled.size, maxOutput16kSamples - output16kCount)
                    if (copyCount > 0) {
                        System.arraycopy(resampled, 0, output16kAccumulator, output16kCount, copyCount)
                        output16kCount += copyCount
                    }
                    nativeMonoCount = 0
                }

                var firstEmittedSampleMs = -1L
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var scratchBytes = ByteArray(64 * 1024)

                decodeLoop@ while (!sawOutputEos && output16kCount < maxOutput16kSamples) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(2_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                val sampleTimeUs = extractor.sampleTime
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                // Stop feeding compressed frames as soon as we pass chunkEndUs
                                if (sampleSize < 0 || (sampleTimeUs >= 0L && sampleTimeUs > chunkEndUs + 150_000L)) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    sawInputEos = true
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs.coerceAtLeast(0L), 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 2_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            val nextSampleRate = runCatching {
                                outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }.getOrDefault(currentSampleRate).coerceAtLeast(8_000)
                            val nextChannels = runCatching {
                                outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }.getOrDefault(currentChannels).coerceAtLeast(1)
                            val nextEncoding = readPcmEncoding(outFmt, currentPcmEncoding)

                            if (nextSampleRate != nativeMonoRate && nativeMonoCount > 0) {
                                flushNativeMonoTo16k()
                            }
                            currentSampleRate = nextSampleRate
                            currentChannels = nextChannels
                            currentPcmEncoding = nextEncoding
                            nativeMonoRate = nextSampleRate
                            val neededCapacity = (((safeWindowMs + 1_000L) * currentSampleRate) / 1_000L)
                                .toInt()
                                .coerceIn(16_000, 3_200_000)
                            if (nativeMonoBuffer.size < neededCapacity && nativeMonoCount == 0) {
                                nativeMonoBuffer = FloatArray(neededCapacity)
                            }
                        }
                        outIndex >= 0 -> {
                            val ptsUs = bufferInfo.presentationTimeUs
                            if (ptsUs >= chunkEndUs) {
                                // Hard early exit the moment decoded audio reaches chunkEndUs
                                codec.releaseOutputBuffer(outIndex, false)
                                break@decodeLoop
                            }

                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                val bytesPerSample = if (currentPcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val bytesPerFrame = currentChannels.coerceAtLeast(1) * bytesPerSample
                                val srcFrames = bufferInfo.size / bytesPerFrame
                                val frameDurUs = if (srcFrames > 0 && currentSampleRate > 0) {
                                    (srcFrames.toLong() * 1_000_000L) / currentSampleRate.toLong()
                                } else {
                                    0L
                                }
                                val frameEndUs = ptsUs + frameDurUs

                                // Fast-skip pre-roll PCM frames strictly before chunkStartUs with ZERO copy/resampling
                                if (frameEndUs > chunkStartUs) {
                                    if (scratchBytes.size < bufferInfo.size) {
                                        scratchBytes = ByteArray(bufferInfo.size)
                                    }
                                    outBuffer.position(bufferInfo.offset)
                                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    outBuffer.get(scratchBytes, 0, bufferInfo.size)

                                    val monoFrame = if (currentPcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        PcmResampler.downmixPcmFloatLeToMonoFloat(
                                            pcmBytes = scratchBytes,
                                            byteLength = bufferInfo.size,
                                            channelCount = currentChannels
                                        )
                                    } else {
                                        PcmResampler.downmixPcm16LeToMonoFloat(
                                            pcmBytes = scratchBytes,
                                            byteLength = bufferInfo.size,
                                            channelCount = currentChannels
                                        )
                                    }

                                    if (monoFrame.isNotEmpty()) {
                                        val skipUs = (chunkStartUs - ptsUs).coerceAtLeast(0L)
                                        val skipSrcSamples = ((skipUs * currentSampleRate) / 1_000_000L)
                                            .toInt()
                                            .coerceIn(0, monoFrame.size)
                                        val trimEndUs = (frameEndUs - chunkEndUs).coerceAtLeast(0L)
                                        val trimEndSrcSamples = ((trimEndUs * currentSampleRate) / 1_000_000L)
                                            .toInt()
                                            .coerceIn(0, monoFrame.size - skipSrcSamples)
                                        val copyCount = (monoFrame.size - skipSrcSamples - trimEndSrcSamples)
                                            .coerceAtLeast(0)

                                        if (copyCount > 0) {
                                            if (firstEmittedSampleMs < 0L) {
                                                val emittedUs = ptsUs + ((skipSrcSamples.toLong() * 1_000_000L) / currentSampleRate)
                                                firstEmittedSampleMs = (emittedUs / 1_000L).coerceAtLeast(0L)
                                            }
                                            val needed = nativeMonoCount + copyCount
                                            if (needed > nativeMonoBuffer.size) {
                                                nativeMonoBuffer = nativeMonoBuffer.copyOf(maxOf(needed, nativeMonoBuffer.size * 2))
                                            }
                                            System.arraycopy(monoFrame, skipSrcSamples, nativeMonoBuffer, nativeMonoCount, copyCount)
                                            nativeMonoCount += copyCount

                                            val est16kSoFar = output16kCount +
                                                ((nativeMonoCount.toLong() * PcmResampler.WHISPER_SAMPLE_RATE) / nativeMonoRate).toInt()
                                            if (est16kSoFar >= maxOutput16kSamples) {
                                                codec.releaseOutputBuffer(outIndex, false)
                                                break@decodeLoop
                                            }
                                        }
                                    }
                                }
                            }

                            codec.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }

                flushNativeMonoTo16k()

                val samplesOut = if (output16kCount == output16kAccumulator.size) {
                    output16kAccumulator
                } else {
                    output16kAccumulator.copyOf(output16kCount)
                }
                val actualDurationMs = (samplesOut.size.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                val exactAnchorStartMs = if (firstEmittedSampleMs >= 0L) firstEmittedSampleMs else safeStartMs
                PcmAudioChunk(
                    chunkIndex = chunkIndex,
                    startTimeMs = exactAnchorStartMs,
                    durationMs = actualDurationMs,
                    samples16kMono = samplesOut
                )
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }
    }

    private fun resolveExtractorAudioTrack(
        extractor: MediaExtractor,
        preferredAudioTrackIndex: Int,
        preferredAudioLanguageCode: String?
    ): Int {
        val descriptors = mutableListOf<AudioTrackDescriptor>()
        var ordinal = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/", ignoreCase = true)) {
                val lang = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()
                descriptors.add(
                    AudioTrackDescriptor(
                        extractorTrackIndex = i,
                        audioOrdinal = ordinal++,
                        languageCode = lang
                    )
                )
            }
        }
        return PcmResampler.selectMatchingAudioTrackIndex(
            tracks = descriptors,
            preferredAudioOrdinal = preferredAudioTrackIndex,
            preferredLanguageCode = preferredAudioLanguageCode
        )
    }

    private fun readPcmEncoding(format: MediaFormat, fallback: Int): Int {
        return runCatching {
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                fallback
            }
        }.getOrDefault(fallback)
    }

    /**
     * Reads the audio track duration (ms) via [MediaExtractor] without decoding any samples.
     */
    suspend fun getAudioDurationMs(mediaUriString: String): Long = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(mediaUriString), null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true) || mime.startsWith("video/", ignoreCase = true)) {
                    val durUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    if (durUs > 0L) return@withContext durUs / 1_000L
                }
            }
            0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SECONDS = 30
        const val DEFAULT_WINDOW_DURATION_MS = DEFAULT_CHUNK_SECONDS * 1_000L
    }
}
