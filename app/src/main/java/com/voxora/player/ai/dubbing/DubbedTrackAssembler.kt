package com.voxora.player.ai.dubbing

import android.content.Context
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.ai.tts.OfflineBengaliTtsEngine
import com.voxora.player.dubbing.whisper.PcmResampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Decoded PCM WAV metadata & mono float samples for an individual synthesized Bengali segment.
 */
data class DecodedWavPcm(
    val sampleRate: Int,
    val channels: Int,
    val monoSamples: FloatArray
)

/**
 * Result of assembling timeline-aligned Bengali WAV dialogue segments into a single
 * continuous PCM WAV track for real-time Media3 playback or offline export mixing.
 */
data class AssembledDubTrack(
    val wavFile: File,
    val sampleRate: Int,
    val totalDurationMs: Long,
    val totalFrames: Long,
    val segmentCount: Int
)

/**
 * Lightweight 30-second rolling chunk WAV track (~1.32 MB at 22.05 kHz mono) aligned to
 * `[startMs, startMs + durationMs)` on the main video timeline for instant live streaming playback.
 */
data class AssembledChunkTrack(
    val chunkIndex: Int,
    val startMs: Long,
    val durationMs: Long,
    val wavFile: File,
    val sampleRate: Int,
    val segmentCount: Int
)

/**
 * Assembles Phase 5 synthesized Bengali WAV segments ([DubbedAudioSegment]) onto a
 * timeline-accurate continuous 16-bit mono PCM WAV track in streaming chunks (low RAM!).
 *
 * - Places each dialogue line at exact frame `((segment.startMs + syncOffsetMs) * sampleRate) / 1000`.
 * - Smoothly sums overlapping speech lines with soft clipping so fast back-and-forth exchanges
 *   never clip or drop audio.
 * - Uses fixed 2-second streaming windows (~88 KB RAM) so even 2-hour movies assemble fast
 *   without ever causing OutOfMemoryError on mid-range phones.
 */
class DubbedTrackAssembler(private val context: Context) {

    /**
     * Assembles all [segments] into `cacheDir/voxora_dub/<videoHash>/assembled_dub_track.wav`.
     *
     * Note: For live Media3 playback, pass `syncOffsetMs = 0L` so [DubbingPlaybackController]
     * can adjust live Sync Offset (ms) instantaneously without re-assembling the WAV file.
     */
    suspend fun assembleTimelineWav(
        videoUri: String,
        segments: List<DubbedAudioSegment>,
        totalDurationMsHint: Long = 0L,
        syncOffsetMs: Long = 0L,
        targetSampleRate: Int = DEFAULT_LIVE_SAMPLE_RATE,
        outputFileOverride: File? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<AssembledDubTrack> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No synthesized Bengali audio segments available to assemble.")
            )
        }

        val targetFile = outputFileOverride ?: getAssembledTrackFile(videoUri)
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        if (tempFile.exists()) tempFile.delete()

        try {
            coroutineContext.ensureActive()

            // Index valid existing segment files and inspect their sample counts without holding all PCM in RAM
            val indexed = segments.mapNotNull { seg ->
                val wavFile = File(seg.audioFilePath)
                if (!wavFile.exists() || wavFile.length() < 44L) return@mapNotNull null
                val header = inspectWavHeader(wavFile) ?: return@mapNotNull null
                val startMs = (seg.startMs + syncOffsetMs).coerceAtLeast(0L)
                val startFrame = (startMs * targetSampleRate.toLong()) / 1_000L
                val estFrames = ((header.totalFrames.toDouble() * targetSampleRate.toDouble()) / header.sampleRate.toDouble())
                    .toLong()
                    .coerceAtLeast(1L)
                IndexedSegmentFile(
                    file = wavFile,
                    startFrame = startFrame,
                    endFrame = startFrame + estFrames
                )
            }.sortedBy { it.startFrame }

            if (indexed.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Synthesized Bengali WAV files were missing from cache. Regenerate Bengali voice lines first.")
                )
            }

            val maxSegEndFrame = indexed.maxOf { it.endFrame }
            val hintFrames = (totalDurationMsHint.coerceAtLeast(0L) * targetSampleRate.toLong()) / 1_000L
            val totalFrames = maxOf(maxSegEndFrame + (targetSampleRate / 2L), hintFrames)
            val totalDurationMs = (totalFrames * 1_000L) / targetSampleRate.toLong()

            // Streaming chunked assembly (2-second mix buffer)
            val chunkFrames = targetSampleRate * 2
            val mixBuffer = FloatArray(chunkFrames)
            val pcmByteBuffer = ByteArray(chunkFrames * 2)

            // Small LRU-like cache of currently active decoded segments overlapping the current 2s chunk
            val activeDecoded = mutableMapOf<Int, FloatArray>()

            BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { out ->
                // Write placeholder 44-byte RIFF/WAVE header (patched after streaming finishes)
                out.write(buildWavHeaderBytes(dataBytes = 0L, sampleRate = targetSampleRate, channels = 1))

                var cursorFrame = 0L
                while (cursorFrame < totalFrames) {
                    coroutineContext.ensureActive()
                    val framesThisChunk = minOf(chunkFrames.toLong(), totalFrames - cursorFrame).toInt()
                    val chunkEndFrame = cursorFrame + framesThisChunk

                    mixBuffer.fill(0f, 0, framesThisChunk)

                    // Evict decoded segments that have completely ended before cursorFrame
                    val iter = activeDecoded.entries.iterator()
                    while (iter.hasNext()) {
                        val idx = iter.next().key
                        if (indexed[idx].endFrame <= cursorFrame) {
                            iter.remove()
                        }
                    }

                    // Mix any segment overlapping [cursorFrame, chunkEndFrame), hard-stopping cleanly
                    // with a 25ms fade-out before the next dialogue segment begins so lines never clash.
                    val fadeFrames = ((targetSampleRate * CUTOFF_FADE_MS) / 1_000L).toInt().coerceAtLeast(1)
                    for (idx in indexed.indices) {
                        val meta = indexed[idx]
                        if (meta.startFrame >= chunkEndFrame) break
                        if (meta.endFrame <= cursorFrame) continue

                        val samples = activeDecoded.getOrPut(idx) {
                            decodeWavToMonoFloatAtRate(meta.file, targetSampleRate)
                        }
                        val nextStartFrame = indexed.getOrNull(idx + 1)?.startFrame
                        val naturalEnd = meta.startFrame + samples.size
                        val cappedEnd = if (nextStartFrame != null && nextStartFrame > meta.startFrame) {
                            minOf(naturalEnd, nextStartFrame)
                        } else {
                            naturalEnd
                        }
                        val wasTruncated = cappedEnd < naturalEnd
                        val overlapStart = maxOf(cursorFrame, meta.startFrame)
                        val overlapEnd = minOf(chunkEndFrame, cappedEnd)
                        if (overlapStart < overlapEnd) {
                            val count = (overlapEnd - overlapStart).toInt()
                            val dstStart = (overlapStart - cursorFrame).toInt()
                            val srcStart = (overlapStart - meta.startFrame).toInt()
                            val fadeStartAbs = (cappedEnd - fadeFrames).coerceAtLeast(meta.startFrame)
                            for (k in 0 until count) {
                                val absFrame = overlapStart + k
                                var sample = samples[srcStart + k]
                                if (wasTruncated && absFrame >= fadeStartAbs) {
                                    val rem = (cappedEnd - absFrame).coerceAtLeast(0L)
                                    val gain = (rem.toFloat() / fadeFrames.toFloat()).coerceIn(0f, 1f)
                                    sample *= gain
                                }
                                mixBuffer[dstStart + k] += sample
                            }
                        }
                    }

                    // Convert float mix to 16-bit LE PCM with soft-clipping
                    var bPos = 0
                    for (i in 0 until framesThisChunk) {
                        val clipped = BackgroundAudioPreserver.softClip(mixBuffer[i])
                        val s16 = (clipped * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                        pcmByteBuffer[bPos++] = (s16 and 0xFF).toByte()
                        pcmByteBuffer[bPos++] = ((s16 ushr 8) and 0xFF).toByte()
                    }
                    out.write(pcmByteBuffer, 0, framesThisChunk * 2)

                    cursorFrame += framesThisChunk
                    onProgress((cursorFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.99f))
                }
                out.flush()
            }

            // Patch exact RIFF/data byte lengths in header
            val totalDataBytes = totalFrames * 2L
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(0L)
                raf.write(buildWavHeaderBytes(dataBytes = totalDataBytes, sampleRate = targetSampleRate, channels = 1))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(1f)
            Result.success(
                AssembledDubTrack(
                    wavFile = targetFile,
                    sampleRate = targetSampleRate,
                    totalDurationMs = totalDurationMs,
                    totalFrames = totalFrames,
                    segmentCount = indexed.size
                )
            )
        } catch (ce: CancellationException) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            throw ce
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            Result.failure(t)
        }
    }

    /**
     * Assembles ONLY a bounded 30-second chunk window (`[windowStartMs, windowStartMs + windowDurationMs)`)
     * into `cacheDir/voxora_dub/<videoHash>/chunk_<chunkIndex>.wav` (~1.32 MB at 22.05 kHz mono).
     *
     * Runs in milliseconds and uses < 3 MB RAM, allowing Chunk 1 playback to start immediately
     * while subsequent chunks are pre-dubbed in the background without ever interrupting playback.
     */
    suspend fun assembleChunkWindowWav(
        videoUri: String,
        chunkIndex: Int,
        windowStartMs: Long,
        windowDurationMs: Long,
        segments: List<DubbedAudioSegment>,
        targetSampleRate: Int = DEFAULT_LIVE_SAMPLE_RATE
    ): Result<AssembledChunkTrack> = withContext(Dispatchers.IO) {
        val safeStartMs = windowStartMs.coerceAtLeast(0L)
        val safeDurationMs = windowDurationMs.coerceIn(1_000L, 45_000L)
        val targetFile = getChunkTrackFile(videoUri, chunkIndex)
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        if (tempFile.exists()) tempFile.delete()

        try {
            coroutineContext.ensureActive()

            val totalFrames = ((safeDurationMs * targetSampleRate.toLong()) / 1_000L)
                .toInt()
                .coerceAtLeast(targetSampleRate)
            // Bounded 30s mix buffer: 30 * 22050 = 661,500 floats (~2.6 MB max)
            val mixBuffer = FloatArray(totalFrames)
            var mixedCount = 0

            val sortedSegments = segments.sortedBy { it.startMs }
            val fadeFrames = ((targetSampleRate * CUTOFF_FADE_MS) / 1_000L).toInt().coerceAtLeast(1)

            for (idx in sortedSegments.indices) {
                coroutineContext.ensureActive()
                val seg = sortedSegments[idx]
                val wavFile = File(seg.audioFilePath)
                if (!wavFile.exists() || wavFile.length() < 44L) continue
                val samples = decodeWavToMonoFloatAtRate(wavFile, targetSampleRate)
                if (samples.isEmpty()) continue

                val relStartMs = seg.startMs - safeStartMs
                val startFrame = ((relStartMs * targetSampleRate.toLong()) / 1_000L).toInt()
                val srcStart = if (startFrame < 0) -startFrame else 0
                val dstStart = if (startFrame < 0) 0 else startFrame
                if (dstStart >= totalFrames || srcStart >= samples.size) continue

                // Hard-stop strictly before the next segment starts (with clean 25ms fade-out)
                val nextSeg = sortedSegments.getOrNull(idx + 1)
                val nextDstFrame = if (nextSeg != null && nextSeg.startMs > seg.startMs) {
                    (((nextSeg.startMs - safeStartMs) * targetSampleRate.toLong()) / 1_000L)
                        .toInt()
                        .coerceIn(dstStart, totalFrames)
                } else {
                    totalFrames
                }

                val naturalLen = samples.size - srcStart
                val availableBeforeNext = (nextDstFrame - dstStart).coerceAtLeast(0)
                val copyLen = minOf(naturalLen, totalFrames - dstStart, availableBeforeNext)
                if (copyLen <= 0) continue

                val wasTruncatedByNext = copyLen < naturalLen
                val fadeStartOffset = (copyLen - fadeFrames).coerceAtLeast(0)
                for (i in 0 until copyLen) {
                    var sample = samples[srcStart + i]
                    if (wasTruncatedByNext && i >= fadeStartOffset) {
                        val rem = (copyLen - i).coerceAtLeast(0)
                        val gain = (rem.toFloat() / fadeFrames.toFloat()).coerceIn(0f, 1f)
                        sample *= gain
                    }
                    mixBuffer[dstStart + i] += sample
                }
                mixedCount++
            }

            val totalDataBytes = totalFrames.toLong() * 2L
            BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024).use { out ->
                out.write(buildWavHeaderBytes(dataBytes = totalDataBytes, sampleRate = targetSampleRate, channels = 1))
                val pcmChunkBytes = ByteArray(8192)
                var frameIdx = 0
                while (frameIdx < totalFrames) {
                    var bPos = 0
                    while (frameIdx < totalFrames && bPos + 2 <= pcmChunkBytes.size) {
                        val clipped = BackgroundAudioPreserver.softClip(mixBuffer[frameIdx++])
                        val s16 = (clipped * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                        pcmChunkBytes[bPos++] = (s16 and 0xFF).toByte()
                        pcmChunkBytes[bPos++] = ((s16 ushr 8) and 0xFF).toByte()
                    }
                    out.write(pcmChunkBytes, 0, bPos)
                }
                out.flush()
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(
                AssembledChunkTrack(
                    chunkIndex = chunkIndex,
                    startMs = safeStartMs,
                    durationMs = safeDurationMs,
                    wavFile = targetFile,
                    sampleRate = targetSampleRate,
                    segmentCount = mixedCount
                )
            )
        } catch (ce: CancellationException) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            throw ce
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            Result.failure(t)
        }
    }

    fun getAssembledTrackFile(videoUri: String): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(context.cacheDir, "voxora_dub/$hash/assembled_dub_track.wav")
    }

    fun getChunkTrackFile(videoUri: String, chunkIndex: Int): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(context.cacheDir, "voxora_dub/$hash/chunk_${chunkIndex}.wav")
    }

    private data class IndexedSegmentFile(
        val file: File,
        val startFrame: Long,
        val endFrame: Long
    )

    companion object {
        const val DEFAULT_LIVE_SAMPLE_RATE = 22_050
        const val EXPORT_SAMPLE_RATE = 44_100
        const val CUTOFF_FADE_MS = 25L

        data class WavHeaderInfo(
            val sampleRate: Int,
            val channels: Int,
            val bitsPerSample: Int,
            val dataOffset: Int,
            val dataSize: Int
        ) {
            val totalFrames: Long
                get() {
                    val bytesPerFrame = (channels.coerceAtLeast(1) * (bitsPerSample.coerceAtLeast(8) / 8))
                    return if (bytesPerFrame > 0) dataSize.toLong() / bytesPerFrame.toLong() else 0L
                }
        }

        fun inspectWavHeader(wavFile: File): WavHeaderInfo? {
            if (!wavFile.exists() || wavFile.length() < 44L) return null
            val headerBytes = ByteArray(minOf(wavFile.length().toInt(), 512))
            FileInputStream(wavFile).use { input ->
                val n = input.read(headerBytes)
                if (n < 44) return null
            }
            return parseWavHeaderBytes(headerBytes, wavFile.length().toInt())
        }

        fun parseWavHeaderBytes(headerBytes: ByteArray, fileLength: Int = headerBytes.size): WavHeaderInfo? {
            if (headerBytes.size < 44) return null
            val riff = String(headerBytes, 0, 4, Charsets.US_ASCII)
            val wave = String(headerBytes, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") return null

            val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var channels = 1
            var sampleRate = DEFAULT_LIVE_SAMPLE_RATE
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0

            while (offset + 8 <= headerBytes.size) {
                val chunkId = String(headerBytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = bb.getInt(offset + 4)
                val bodyStart = offset + 8
                when (chunkId) {
                    "fmt " -> {
                        if (bodyStart + 16 > headerBytes.size) return null
                        channels = bb.getShort(bodyStart + 2).toInt() and 0xFFFF
                        sampleRate = bb.getInt(bodyStart + 4)
                        bitsPerSample = bb.getShort(bodyStart + 14).toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = bodyStart
                        dataSize = chunkSize.coerceAtMost((fileLength - bodyStart).coerceAtLeast(0))
                        break
                    }
                }
                if (chunkSize < 0) break
                offset = bodyStart + chunkSize + (chunkSize and 1)
            }

            if (dataOffset < 0 || dataSize <= 0 || sampleRate <= 0 || channels <= 0) return null
            return WavHeaderInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset,
                dataSize = dataSize
            )
        }

        fun decodeWavToMonoFloatAtRate(wavFile: File, targetSampleRate: Int): FloatArray {
            val bytes = FileInputStream(wavFile).use { it.readBytes() }
            val info = parseWavHeaderBytes(bytes, bytes.size) ?: return FloatArray(0)
            val channels = info.channels.coerceAtLeast(1)
            val bytesPerFrame = channels * 2
            val availableBytes = minOf(info.dataSize, (bytes.size - info.dataOffset).coerceAtLeast(0))
            val frameCount = availableBytes / bytesPerFrame
            if (frameCount <= 0) return FloatArray(0)

            val mono = FloatArray(frameCount)
            val bb = ByteBuffer.wrap(bytes, info.dataOffset, frameCount * bytesPerFrame)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                var sum = 0f
                for (ch in 0 until channels) {
                    sum += bb.short.toFloat() / 32768.0f
                }
                mono[i] = (sum / channels).coerceIn(-1.0f, 1.0f)
            }
            return if (info.sampleRate == targetSampleRate) {
                mono
            } else {
                PcmResampler.resampleMonoLinear(mono, info.sampleRate, targetSampleRate)
            }
        }

        fun buildWavHeaderBytes(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
            val safeData = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong() - 36L).toInt()
            val byteRate = sampleRate * channels * 2
            val blockAlign = (channels * 2).toShort()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + safeData)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(safeData)
            return header.array()
        }
    }
}
