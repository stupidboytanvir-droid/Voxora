package com.voxora.player.ai.tts

import com.voxora.player.dubbing.VoiceGender
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Coarse expressive vocal energy classification derived from the original English PCM slice.
 */
enum class SegmentEnergyLevel {
    SOFT,
    NORMAL,
    HIGH
}

/**
 * Acoustic prosody & gender estimation computed directly from the 16 kHz mono PCM slice
 * of an original English dialogue segment (zero extra PCM buffer allocation).
 */
data class SegmentAcousticProfile(
    val segmentIndex: Int,
    val detectedGender: VoiceGender,
    val medianF0Hz: Float,
    val rmsEnergy: Float,
    val pitchVarianceHz: Float,
    val energyLevel: SegmentEnergyLevel,
    val ssmlVolume: String,
    val ssmlPitchDeltaHz: Int,
    val emotionRateBonusPercent: Int
)

/**
 * Pure-Kotlin, zero-extra-RAM acoustic analyzer for 16 kHz mono float PCM audio.
 *
 * Performs two lightweight per-segment analyses directly on slices of the existing 30-second
 * 16 kHz PCM chunk:
 * 1. **Automatic Male/Female Voice Detection**: Estimates fundamental frequency (F0) via
 *    normalized autocorrelation across human vocal lags (75 Hz – 320 Hz) with zero-crossing
 *    fallback. Median F0 > 165 Hz -> [VoiceGender.FEMALE], else [VoiceGender.MALE].
 * 2. **Expressive Emotion & Prosody Matching**: Measures RMS loudness and F0 pitch variance
 *    to map high-energy/shouting or soft/calm/whisper deliveries into expressive SSML prosody.
 */
object AudioProsodyAnalyzer {

    const val SAMPLE_RATE_HZ = 16_000
    const val FEMALE_F0_THRESHOLD_HZ = 155.0f

    private const val MIN_VOICE_F0_HZ = 75.0f
    private const val MAX_VOICE_F0_HZ = 320.0f
    private const val FRAME_SIZE_SAMPLES = 480 // 30 ms at 16 kHz
    private const val MAX_FRAMES_PER_SEGMENT = 48
    private const val MIN_VOICED_FRAME_RMS = 0.012f
    private const val MIN_AUTOCORR_CONFIDENCE = 0.30f

    /**
     * Analyzes all `(segmentIndex, startMs, endMs)` dialogue intervals inside a single 30-second
     * 16 kHz mono PCM chunk (`[windowStartMs, windowStartMs + chunkDuration)`).
     *
     * Uses relative loudness calibration across the chunk alongside absolute RMS thresholds so
     * loud/angry and soft/whispered lines are recognized accurately regardless of movie mastering.
     */
    fun analyzeChunkSegments(
        samples16kMono: FloatArray,
        windowStartMs: Long,
        segments: List<Triple<Int, Long, Long>>,
        sampleRateHz: Int = SAMPLE_RATE_HZ
    ): Map<Int, SegmentAcousticProfile> {
        if (samples16kMono.isEmpty() || segments.isEmpty() || sampleRateHz <= 0) {
            return emptyMap()
        }

        // Pass 1: measure raw RMS, median F0, and pitch standard deviation per segment
        data class RawMetrics(
            val segmentIndex: Int,
            val rms: Float,
            val medianF0: Float,
            val pitchStdHz: Float
        )

        val rawList = segments.map { (segIdx, startMs, endMs) ->
            val relStartMs = (startMs - windowStartMs).coerceAtLeast(0L)
            val relEndMs = (endMs - windowStartMs).coerceAtLeast(relStartMs + 100L)
            val startSample = ((relStartMs * sampleRateHz.toLong()) / 1_000L)
                .toInt()
                .coerceIn(0, samples16kMono.size)
            val endSample = ((relEndMs * sampleRateHz.toLong()) / 1_000L)
                .toInt()
                .coerceIn(startSample, samples16kMono.size)

            val (rms, medianF0, pitchStd) = measureSliceMetrics(
                samples = samples16kMono,
                startSample = startSample,
                endSample = endSample,
                sampleRateHz = sampleRateHz
            )
            RawMetrics(segIdx, rms, medianF0, pitchStd)
        }

        // Compute median non-silent RMS across this chunk for relative energy normalization
        val activeRmsValues = rawList.map { it.rms }.filter { it >= MIN_VOICED_FRAME_RMS }.sorted()
        val chunkMedianRms = if (activeRmsValues.isNotEmpty()) {
            activeRmsValues[activeRmsValues.size / 2]
        } else {
            0.07f
        }

        // Pass 2: classify gender and expressive SSML prosody
        val result = linkedMapOf<Int, SegmentAcousticProfile>()
        for (m in rawList) {
            result[m.segmentIndex] = buildProfile(
                segmentIndex = m.segmentIndex,
                rmsEnergy = m.rms,
                medianF0Hz = m.medianF0,
                pitchVarianceHz = m.pitchStdHz,
                chunkReferenceRms = chunkMedianRms
            )
        }
        return result
    }

    /**
     * Analyzes a standalone 16 kHz mono PCM slice directly (useful for unit tests and single-line checks).
     */
    fun analyzeSlice(
        samples: FloatArray,
        segmentIndex: Int = 0,
        startSample: Int = 0,
        endSample: Int = samples.size,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        chunkReferenceRms: Float = 0.07f
    ): SegmentAcousticProfile {
        val safeStart = startSample.coerceIn(0, samples.size)
        val safeEnd = endSample.coerceIn(safeStart, samples.size)
        val (rms, medianF0, pitchStd) = measureSliceMetrics(samples, safeStart, safeEnd, sampleRateHz)
        return buildProfile(
            segmentIndex = segmentIndex,
            rmsEnergy = rms,
            medianF0Hz = medianF0,
            pitchVarianceHz = pitchStd,
            chunkReferenceRms = chunkReferenceRms
        )
    }

    private fun buildProfile(
        segmentIndex: Int,
        rmsEnergy: Float,
        medianF0Hz: Float,
        pitchVarianceHz: Float,
        chunkReferenceRms: Float
    ): SegmentAcousticProfile {
        val detectedGender = if (medianF0Hz >= FEMALE_F0_THRESHOLD_HZ) {
            VoiceGender.FEMALE
        } else {
            VoiceGender.MALE
        }

        val isHighEnergy = rmsEnergy >= 0.14f ||
            (chunkReferenceRms >= 0.015f && rmsEnergy >= chunkReferenceRms * 1.42f && rmsEnergy >= 0.065f) ||
            (rmsEnergy >= 0.095f && pitchVarianceHz >= 36.0f)

        val isSoftEnergy = !isHighEnergy && (
            rmsEnergy <= 0.036f ||
                (chunkReferenceRms >= 0.025f && rmsEnergy <= chunkReferenceRms * 0.56f)
            )

        return when {
            isHighEnergy -> SegmentAcousticProfile(
                segmentIndex = segmentIndex,
                detectedGender = detectedGender,
                medianF0Hz = medianF0Hz,
                rmsEnergy = rmsEnergy,
                pitchVarianceHz = pitchVarianceHz,
                energyLevel = SegmentEnergyLevel.HIGH,
                ssmlVolume = "+15%",
                ssmlPitchDeltaHz = 4,
                emotionRateBonusPercent = 5
            )
            isSoftEnergy -> SegmentAcousticProfile(
                segmentIndex = segmentIndex,
                detectedGender = detectedGender,
                medianF0Hz = medianF0Hz,
                rmsEnergy = rmsEnergy,
                pitchVarianceHz = pitchVarianceHz,
                energyLevel = SegmentEnergyLevel.SOFT,
                ssmlVolume = "-8%",
                ssmlPitchDeltaHz = -2,
                emotionRateBonusPercent = 0
            )
            else -> SegmentAcousticProfile(
                segmentIndex = segmentIndex,
                detectedGender = detectedGender,
                medianF0Hz = medianF0Hz,
                rmsEnergy = rmsEnergy,
                pitchVarianceHz = pitchVarianceHz,
                energyLevel = SegmentEnergyLevel.NORMAL,
                ssmlVolume = "+0%",
                ssmlPitchDeltaHz = 0,
                emotionRateBonusPercent = 0
            )
        }
    }

    /**
     * Computes `(rmsEnergy, medianF0Hz, pitchStdDevHz)` directly on `samples[startSample until endSample]`.
     */
    private fun measureSliceMetrics(
        samples: FloatArray,
        startSample: Int,
        endSample: Int,
        sampleRateHz: Int
    ): Triple<Float, Float, Float> {
        val len = endSample - startSample
        if (len <= 0) return Triple(0f, 0f, 0f)

        // Overall RMS loudness across the dialogue slice
        var sumSq = 0.0
        for (i in startSample until endSample) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        val overallRms = sqrt(sumSq / len.toDouble()).toFloat()

        val frameSize = minOf(FRAME_SIZE_SAMPLES, len)
        if (frameSize < 128) {
            return Triple(overallRms, 0f, 0f)
        }

        val minLag = (sampleRateHz / MAX_VOICE_F0_HZ).toInt().coerceAtLeast(16)
        val maxLag = (sampleRateHz / MIN_VOICE_F0_HZ).toInt().coerceAtMost(frameSize / 2)
        if (maxLag <= minLag) {
            return Triple(overallRms, 0f, 0f)
        }

        val availableStartSpan = (len - frameSize).coerceAtLeast(0)
        val frameCount = if (availableStartSpan == 0) {
            1
        } else {
            minOf(MAX_FRAMES_PER_SEGMENT, (availableStartSpan / (frameSize / 2)) + 1)
        }
        val hop = if (frameCount <= 1) frameSize else (availableStartSpan / (frameCount - 1)).coerceAtLeast(1)

        val f0Estimates = ArrayList<Float>(frameCount)
        val zcrEstimates = ArrayList<Float>(frameCount)

        for (f in 0 until frameCount) {
            val offset = startSample + (f * hop)
            if (offset + frameSize > endSample) break

            // Compute frame mean (DC removal) and RMS
            var mean = 0.0
            for (i in 0 until frameSize) {
                mean += samples[offset + i]
            }
            mean /= frameSize.toDouble()

            var energy = 0.0
            var zeroCrossings = 0
            var prevCentered = samples[offset] - mean
            for (i in 0 until frameSize) {
                val c = samples[offset + i] - mean
                energy += c * c
                if (i > 0 && ((prevCentered >= 0.0 && c < 0.0) || (prevCentered < 0.0 && c >= 0.0))) {
                    zeroCrossings++
                }
                prevCentered = c
            }
            val frameRms = sqrt(energy / frameSize.toDouble()).toFloat()
            if (frameRms < MIN_VOICED_FRAME_RMS || energy <= 1e-9) continue

            // Secondary ZCR-based fundamental estimate (capped to human speech range)
            val zcrHz = ((zeroCrossings.toFloat() * sampleRateHz.toFloat()) / (2.0f * frameSize.toFloat()))
            if (zcrHz in MIN_VOICE_F0_HZ..MAX_VOICE_F0_HZ) {
                zcrEstimates.add(zcrHz)
            }

            // Primary normalized autocorrelation pitch detection across [minLag, maxLag]
            val lagCount = maxLag - minLag + 1
            val corrs = DoubleArray(lagCount)
            var bestLag = -1
            var bestCorr = 0.0
            for (lag in minLag..maxLag) {
                val n = frameSize - lag
                var num = 0.0
                var denA = 0.0
                var denB = 0.0
                for (i in 0 until n) {
                    val a = samples[offset + i] - mean
                    val b = samples[offset + i + lag] - mean
                    num += a * b
                    denA += a * a
                    denB += b * b
                }
                val denom = sqrt(denA * denB)
                val corr = if (denom > 1e-9) num / denom else 0.0
                corrs[lag - minLag] = corr
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestLag = lag
                }
            }

            // First-strong-peak rule: prevents octave-halving (picking 2*T0 instead of T0 on female voices)
            if (bestCorr >= MIN_AUTOCORR_CONFIDENCE) {
                val peakThreshold = bestCorr * 0.92
                for (idx in 1 until (lagCount - 1)) {
                    val c = corrs[idx]
                    if (c >= peakThreshold && c >= corrs[idx - 1] && c >= corrs[idx + 1]) {
                        bestLag = minLag + idx
                        break
                    }
                }
            }

            if (bestLag > 0 && bestCorr >= MIN_AUTOCORR_CONFIDENCE) {
                val f0 = sampleRateHz.toFloat() / bestLag.toFloat()
                if (f0 in MIN_VOICE_F0_HZ..MAX_VOICE_F0_HZ) {
                    f0Estimates.add(f0)
                }
            }
        }

        val activeF0s = when {
            f0Estimates.isNotEmpty() -> f0Estimates
            zcrEstimates.isNotEmpty() -> zcrEstimates
            else -> return Triple(overallRms, 0f, 0f)
        }

        activeF0s.sort()
        val medianF0 = activeF0s[activeF0s.size / 2]

        var varianceSum = 0.0
        for (hz in activeF0s) {
            val diff = (hz - medianF0).toDouble()
            varianceSum += diff * diff
        }
        val pitchStdDev = sqrt(varianceSum / activeF0s.size.toDouble()).toFloat()

        return Triple(overallRms, medianF0, abs(pitchStdDev))
    }
}
