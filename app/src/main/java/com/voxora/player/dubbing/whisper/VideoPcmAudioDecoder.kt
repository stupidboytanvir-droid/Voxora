package com.voxora.player.dubbing.whisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

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
 * Pure Kotlin PCM resampling and WAV decoding utilities for whisper.cpp's exact input format:
 * 16,000 Hz, mono, 32-bit float normalized to [-1.0, 1.0].
 */
object PcmResampler {
    const val WHISPER_SAMPLE_RATE = 16_000

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
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerFrame = channels * 2
        val totalFrames = byteLength / bytesPerFrame
        if (totalFrames <= 0) return FloatArray(0)

        val monoSource = FloatArray(totalFrames)
        val bb = ByteBuffer.wrap(pcmBytes, 0, totalFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += bb.short.toFloat() / 32768.0f
            }
            monoSource[frame] = (sum / channels).coerceIn(-1.0f, 1.0f)
        }

        return resampleMonoLinear(monoSource, sourceSampleRate, WHISPER_SAMPLE_RATE)
    }

    /**
     * Linearly resamples mono float audio from [sourceRate] to [targetRate].
     */
    fun resampleMonoLinear(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int = WHISPER_SAMPLE_RATE
    ): FloatArray {
        if (input.isEmpty() || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
        if (sourceRate == targetRate) return input

        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        val outLength = ((input.size.toDouble() / ratio).toInt()).coerceAtLeast(1)
        val output = FloatArray(outLength)
        val lastIndex = input.size - 1

        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx0 = floor(srcPos).toInt().coerceIn(0, lastIndex)
            val idx1 = (idx0 + 1).coerceAtMost(lastIndex)
            val frac = (srcPos - idx0).toFloat()
            output[i] = input[idx0] + (input[idx1] - input[idx0]) * frac
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
 * - Reads audio directly from any local video URI without modifying the video file.
 * - Never interrupts or modifies active Media3 ExoPlayer playback.
 * - Converts decoded audio into 16 kHz Mono Float32 PCM chunks (default 30s per chunk)
 *   so long movies process cleanly without exhausting RAM.
 */
class VideoPcmAudioDecoder(private val context: Context) {

    /**
     * Streams decoded 16 kHz mono Float32 PCM chunks from [mediaUriString] to [onChunkDecoded].
     * Returns total decoded audio duration in milliseconds.
     */
    suspend fun decodeVideoTo16kChunks(
        mediaUriString: String,
        chunkDurationSeconds: Int = DEFAULT_CHUNK_SECONDS,
        onDecodeProgress: (decodedMs: Long, totalDurationMs: Long) -> Unit = { _, _ -> },
        onChunkDecoded: suspend (PcmAudioChunk) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(mediaUriString), null)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/", ignoreCase = true)) {
                        audioTrackIndex = i
                        inputFormat = format
                        break
                    }
                }

                if (audioTrackIndex < 0 || inputFormat == null) {
                    throw IllegalStateException("No audio track found in the selected video.")
                }

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

                                val resampled = PcmResampler.pcm16LeTo16kMonoFloat(
                                    pcmBytes = scratchBytes,
                                    byteLength = bufferInfo.size,
                                    sourceSampleRate = currentSampleRate,
                                    channelCount = currentChannels
                                )

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
     * Never loads an entire movie into memory: a 30-second 16 kHz mono Float32 buffer uses
     * only ~1.92 MB of RAM, keeping total audio processing well under 20 MB even on 3-hour movies.
     */
    suspend fun decodeWindowTo16kPcm(
        mediaUriString: String,
        startMs: Long,
        windowDurationMs: Long = DEFAULT_CHUNK_SECONDS * 1_000L,
        chunkIndex: Int = 0
    ): Result<PcmAudioChunk> = withContext(Dispatchers.IO) {
        runCatching {
            val safeStartMs = startMs.coerceAtLeast(0L)
            val safeWindowMs = windowDurationMs.coerceIn(5_000L, 60_000L)
            val endMs = safeStartMs + safeWindowMs
            val maxSamples = ((safeWindowMs * PcmResampler.WHISPER_SAMPLE_RATE) / 1_000L).toInt()
                .coerceAtLeast(PcmResampler.WHISPER_SAMPLE_RATE)

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, Uri.parse(mediaUriString), null)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/", ignoreCase = true)) {
                        audioTrackIndex = i
                        inputFormat = format
                        break
                    }
                }

                if (audioTrackIndex < 0 || inputFormat == null) {
                    throw IllegalStateException("No audio track found in the selected video.")
                }

                val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Audio track MIME type is missing.")
                var currentSampleRate = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }.getOrDefault(48_000)
                var currentChannels = runCatching {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(2)

                extractor.selectTrack(audioTrackIndex)
                if (safeStartMs > 0L) {
                    extractor.seekTo(safeStartMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                codec = MediaCodec.createDecoderByType(mimeType)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val accumulator = FloatArray(maxSamples)
                var accumulatedCount = 0
                var firstEmittedSampleMs = -1L
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var scratchBytes = ByteArray(64 * 1024)
                val endUs = endMs * 1_000L

                while (!sawOutputEos && accumulatedCount < maxSamples) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIndex = codec.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                            if (inputBuffer != null) {
                                val sampleTimeUs = extractor.sampleTime
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                // Stop feeding input once we are safely past the end of the 30s window
                                if (sampleSize < 0 || (sampleTimeUs >= 0L && sampleTimeUs > endUs + 500_000L)) {
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
                        }
                        outIndex >= 0 -> {
                            val outBuffer = codec.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                val frameStartMs = (bufferInfo.presentationTimeUs / 1_000L).coerceAtLeast(0L)
                                if (scratchBytes.size < bufferInfo.size) {
                                    scratchBytes = ByteArray(bufferInfo.size)
                                }
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outBuffer.get(scratchBytes, 0, bufferInfo.size)

                                val resampled = PcmResampler.pcm16LeTo16kMonoFloat(
                                    pcmBytes = scratchBytes,
                                    byteLength = bufferInfo.size,
                                    sourceSampleRate = currentSampleRate,
                                    channelCount = currentChannels
                                )

                                if (resampled.isNotEmpty()) {
                                    val frameDurMs = (resampled.size.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                                    val frameEndMs = frameStartMs + frameDurMs

                                    // Discard pre-roll frames prior to safeStartMs after SEEK_TO_PREVIOUS_SYNC
                                    if (frameEndMs > safeStartMs && frameStartMs < endMs) {
                                        val skipMs = (safeStartMs - frameStartMs).coerceAtLeast(0L)
                                        val skipSamples = ((skipMs * PcmResampler.WHISPER_SAMPLE_RATE) / 1_000L)
                                            .toInt()
                                            .coerceIn(0, resampled.size)
                                        val available = resampled.size - skipSamples
                                        val spaceLeft = maxSamples - accumulatedCount
                                        val copyCount = minOf(available, spaceLeft)
                                        if (copyCount > 0) {
                                            if (firstEmittedSampleMs < 0L) {
                                                firstEmittedSampleMs = frameStartMs +
                                                    ((skipSamples.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE)
                                            }
                                            System.arraycopy(resampled, skipSamples, accumulator, accumulatedCount, copyCount)
                                            accumulatedCount += copyCount
                                        }
                                    }
                                    if (frameStartMs >= endMs) {
                                        sawOutputEos = true
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

                val samplesOut = if (accumulatedCount == accumulator.size) {
                    accumulator
                } else {
                    accumulator.copyOf(accumulatedCount)
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
