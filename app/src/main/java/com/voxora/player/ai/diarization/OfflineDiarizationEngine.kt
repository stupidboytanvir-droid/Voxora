package com.voxora.player.ai.diarization

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.voxora.player.dubbing.whisper.PcmResampler
import com.voxora.player.dubbing.whisper.VideoPcmAudioDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * Real offline Speaker Diarization engine backed by Sherpa-ONNX (`OfflineSpeakerDiarization`).
 *
 * - Reuses VOXORA's existing [VideoPcmAudioDecoder] (16 kHz Mono Float32 PCM) without disturbing
 *   Media3 video playback.
 * - Runs exclusively on [Dispatchers.IO].
 * - Never fabricates speaker turns: if models are missing, returns [Result.failure] with
 *   [DiarizationModelNotInstalledException].
 * - Supports configurable `numClusters` (`-1` for auto-detect or explicit speaker count) and
 *   clustering `threshold` (default `0.5f`), clean coroutine cancellation, and deterministic native
 *   resource [release].
 */
class OfflineDiarizationEngine(
    context: Context,
    private val modelManager: DiarizationModelManager,
    private val videoDecoder: VideoPcmAudioDecoder = VideoPcmAudioDecoder(context)
) {
    private val nativeMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var activeDiarizer: OfflineSpeakerDiarization? = null

    @Volatile
    private var activeConfigSignature: String? = null

    /**
     * Signals any active Sherpa-ONNX chunk callback to stop cleanly.
     */
    fun requestCancel() {
        cancelRequested.set(true)
    }

    /**
     * Runs offline speaker diarization directly on a local video URI.
     *
     * @param mediaUriString Content or file URI of the currently loaded video.
     * @param numClusters `-1` to auto-detect speaker count, or `>= 1` to fix speaker cluster count.
     * @param threshold Cosine distance clustering threshold (`0.3f..0.85f`, default `0.5f`).
     * @param onProgress Progress callback `(fraction: Float, statusLabel: String)` invoked off-main-thread.
     */
    suspend fun diarizeVideo(
        mediaUriString: String,
        numClusters: Int = -1,
        threshold: Float = 0.5f,
        onProgress: (fraction: Float, statusLabel: String) -> Unit = { _, _ -> }
    ): Result<List<SpeakerSegment>> = withContext(Dispatchers.IO) {
        if (mediaUriString.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No video is currently loaded."))
        }

        val modelFiles = modelManager.getInstalledModelPaths()
            ?: return@withContext Result.failure(DiarizationModelNotInstalledException())

        cancelRequested.set(false)

        try {
            coroutineContext.ensureActive()
            onProgress(0.03f, "Decoding video audio to 16 kHz mono PCM...")

            // Accumulate 16 kHz mono Float32 PCM chunks in bounded windows so long movies don't OOM.
            val windowSamplesList = mutableListOf<FloatArray>()
            var currentWindowSampleCount = 0
            val detectedSegments = mutableListOf<SpeakerSegment>()
            var windowStartMs = 0L
            var totalDecodedMs = 0L

            val decodeResult = videoDecoder.decodeVideoTo16kChunks(
                mediaUriString = mediaUriString,
                chunkDurationSeconds = VideoPcmAudioDecoder.DEFAULT_CHUNK_SECONDS,
                onDecodeProgress = { decodedMs, totalDurationMs ->
                    totalDecodedMs = decodedMs
                    val decodeFrac = if (totalDurationMs > 0L) {
                        (decodedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) * 0.35f
                    } else {
                        0.15f
                    }
                    onProgress(
                        0.03f + decodeFrac,
                        "Decoding 16 kHz mono audio (${decodedMs / 1000}s)..."
                    )
                },
                onChunkDecoded = { chunk ->
                    coroutineContext.ensureActive()
                    if (cancelRequested.get()) {
                        throw CancellationException("Speaker diarization cancelled by user.")
                    }
                    windowSamplesList.add(chunk.samples16kMono)
                    currentWindowSampleCount += chunk.samples16kMono.size

                    // Flush if audio reaches max safe in-memory window (~20 minutes = ~76 MB FloatArray)
                    if (currentWindowSampleCount >= MAX_WINDOW_SAMPLES) {
                        val merged = mergeFloatArrays(windowSamplesList, currentWindowSampleCount)
                        windowSamplesList.clear()
                        currentWindowSampleCount = 0

                        val windowResults = runNativeDiarization(
                            modelFiles = modelFiles,
                            samples = merged,
                            timeOffsetMs = windowStartMs,
                            numClusters = numClusters,
                            threshold = threshold,
                            onChunkProgress = { processed, total ->
                                val innerFrac = if (total > 0) processed.toFloat() / total.toFloat() else 0.5f
                                onProgress(
                                    (0.40f + innerFrac * 0.55f).coerceIn(0.40f, 0.96f),
                                    "Detecting speakers (window chunk $processed/$total)..."
                                )
                            }
                        )
                        detectedSegments.addAll(windowResults)
                        windowStartMs += (merged.size.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
                    }
                }
            )

            decodeResult.getOrThrow()
            coroutineContext.ensureActive()

            if (cancelRequested.get()) {
                throw CancellationException("Speaker diarization cancelled by user.")
            }

            // Process remaining full audio buffer (for videos <= 20 minutes, this processes the entire video in one global clustering pass)
            if (currentWindowSampleCount > 0) {
                val merged = mergeFloatArrays(windowSamplesList, currentWindowSampleCount)
                windowSamplesList.clear()
                currentWindowSampleCount = 0

                onProgress(0.42f, "Initializing Sherpa-ONNX speaker diarization...")
                val windowResults = runNativeDiarization(
                    modelFiles = modelFiles,
                    samples = merged,
                    timeOffsetMs = windowStartMs,
                    numClusters = numClusters,
                    threshold = threshold,
                    onChunkProgress = { processed, total ->
                        val innerFrac = if (total > 0) processed.toFloat() / total.toFloat() else 0.5f
                        onProgress(
                            (0.42f + innerFrac * 0.55f).coerceIn(0.42f, 0.98f),
                            "Clustering speaker embeddings ($processed / $total)..."
                        )
                    }
                )
                detectedSegments.addAll(windowResults)
            }

            if (cancelRequested.get()) {
                throw CancellationException("Speaker diarization cancelled by user.")
            }

            val sorted = detectedSegments.sortedBy { it.startMs }
            onProgress(1.0f, "Detected ${sorted.map { it.speakerIndex }.distinct().size} speaker(s)")
            Result.success(sorted)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: UnsatisfiedLinkError) {
            Result.failure(
                IllegalStateException(
                    "Native Sherpa-ONNX library could not be loaded on this device ABI: ${t.message}",
                    t
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun runNativeDiarization(
        modelFiles: DiarizationModelFiles,
        samples: FloatArray,
        timeOffsetMs: Long,
        numClusters: Int,
        threshold: Float,
        onChunkProgress: (processed: Int, total: Int) -> Unit
    ): List<SpeakerSegment> = nativeMutex.withLock {
        coroutineContext.ensureActive()
        if (samples.isEmpty()) return@withLock emptyList()

        val threads = recommendedThreadCount()
        val safeThreshold = threshold.coerceIn(0.1f, 1.5f)
        val safeClusters = if (numClusters <= 0) -1 else numClusters

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = modelFiles.segmentationModelPath
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = modelFiles.embeddingModelPath,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            clustering = FastClusteringConfig(
                numClusters = safeClusters,
                threshold = safeThreshold,
                computeConfidence = true
            ),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f
        )

        val modelSignature = "${modelFiles.segmentationModelPath}|${modelFiles.embeddingModelPath}|$threads"
        val diarizer = if (activeDiarizer != null && activeConfigSignature == modelSignature) {
            activeDiarizer!!.also { it.setConfig(config) }
        } else {
            runCatching { activeDiarizer?.release() }
            activeDiarizer = null
            activeConfigSignature = null
            OfflineSpeakerDiarization(
                assetManager = null,
                config = config
            ).also {
                activeDiarizer = it
                activeConfigSignature = modelSignature
            }
        }

        val rawSegments = diarizer.processWithCallback(
            samples = samples,
            callback = { numProcessedChunks, numTotalChunks, _ ->
                onChunkProgress(numProcessedChunks, numTotalChunks)
                if (cancelRequested.get()) 1 else 0
            }
        )

        rawSegments.map { seg ->
            val startMs = timeOffsetMs + (seg.start.toDouble() * 1_000.0).roundToLong().coerceAtLeast(0L)
            val endMs = timeOffsetMs + (seg.end.toDouble() * 1_000.0).roundToLong().coerceAtLeast(0L)
            SpeakerSegment(
                startMs = startMs,
                endMs = endMs.coerceAtLeast(startMs),
                speakerIndex = seg.speaker.coerceAtLeast(0),
                confidence = seg.confidence
            )
        }
    }

    /**
     * Releases native Sherpa-ONNX diarization memory cleanly to prevent native leaks.
     */
    fun release() {
        cancelRequested.set(true)
        val instance = activeDiarizer
        activeDiarizer = null
        activeConfigSignature = null
        if (instance != null) {
            runCatching { instance.release() }
        }
    }

    private fun mergeFloatArrays(chunks: List<FloatArray>, totalLength: Int): FloatArray {
        val merged = FloatArray(totalLength)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        return merged
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 3
            cores >= 2 -> 2
            else -> 1
        }
    }

    companion object {
        // 20 minutes @ 16 kHz mono = 19,200,000 floats (~76 MB) — balances global accuracy & mobile RAM safety
        private const val MAX_WINDOW_SAMPLES = 20 * 60 * PcmResampler.WHISPER_SAMPLE_RATE
    }
}
