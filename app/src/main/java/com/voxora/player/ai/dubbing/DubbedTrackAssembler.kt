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

                    // Mix any segment overlapping [cursorFrame, chunkEndFrame)
                    for (idx in indexed.indices) {
                        val meta = indexed[idx]
                        if (meta.startFrame >= chunkEndFrame) break
                        if (meta.endFrame <= cursorFrame) continue

                        val samples = activeDecoded.getOrPut(idx) {
                            decodeWavToMonoFloatAtRate(meta.file, targetSampleRate)
                        }
                        val actualEnd = meta.startFrame + samples.size
                        val overlapStart = maxOf(cursorFrame, meta.startFrame)
                        val overlapEnd = minOf(chunkEndFrame, actualEnd)
                        if (overlapStart < overlapEnd) {
                            val count = (overlapEnd - overlapStart).toInt()
                            val dstStart = (overlapStart - cursorFrame).toInt()
                            val srcStart = (overlapStart - meta.startFrame).toInt()
                            for (k in 0 until count) {
                                mixBuffer[dstStart + k] += samples[srcStart + k]
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

    fun getAssembledTrackFile(videoUri: String): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(context.cacheDir, "voxora_dub/$hash/assembled_dub_track.wav")
    }

    private data class IndexedSegmentFile(
        val file: File,
        val startFrame: Long,
        val endFrame: Long
    )

    companion object {
        const val DEFAULT_LIVE_SAMPLE_RATE = 22_050
        const val EXPORT_SAMPLE_RATE = 44_100

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
