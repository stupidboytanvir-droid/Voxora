package com.voxora.player.ai.tts

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lightweight, pure-Kotlin PCM audio utilities for Phase 5 offline Bengali voice generation:
 * - Resamples Float32 PCM by a pitch ratio to deliver clean Male/Female timbre distinction when
 *   paired with Sherpa-ONNX speed compensation (zero granular buzz, preserved speaking duration).
 * - Writes standard 16-bit mono PCM RIFF/WAVE `.wav` files to app cache.
 * - Computes bounded adaptive speaking speeds so long Bengali lines fit closely within video timing.
 */
object WavAudioProcessor {

    const val MIN_PITCH_FACTOR = 0.72f
    const val MAX_PITCH_FACTOR = 1.32f
    const val MIN_SPEED_FACTOR = 0.65f
    const val MAX_SPEED_FACTOR = 1.50f

    /**
     * Computes the Sherpa-ONNX synthesis `speed` parameter that compensates for downstream pitch
     * resampling (`pitchFactor`) so final playback duration stays faithful to `desiredSpeed`.
     *
     * Proof:
     * - Resampling a buffer of length `N` by `pitchFactor` produces `N / pitchFactor` samples,
     *   shifting pitch by `pitchFactor` and multiplying duration by `1 / pitchFactor`.
     * - Synthesizing at `sherpaSpeed = desiredSpeed * pitchFactor` yields duration
     *   `T0 / (desiredSpeed * pitchFactor)`.
     * - After resampling by `pitchFactor`, final duration = `T0 / desiredSpeed` and pitch = `pitchFactor`.
     */
    fun computeCompensatedSherpaSpeed(
        desiredSpeed: Float,
        pitchFactor: Float
    ): Float {
        val safeSpeed = desiredSpeed.coerceIn(MIN_SPEED_FACTOR, MAX_SPEED_FACTOR)
        val safePitch = pitchFactor.coerceIn(MIN_PITCH_FACTOR, MAX_PITCH_FACTOR)
        return (safeSpeed * safePitch).coerceIn(0.55f, 1.80f)
    }

    /**
     * Computes an adjusted speed when the initial synthesized Bengali audio significantly exceeds
     * the original English dialogue window (`targetWindowMs`), returning `null` if timing is already good.
     */
    fun computeTimingAdjustedSpeed(
        currentSpeed: Float,
        generatedDurationMs: Long,
        targetWindowMs: Long,
        maxAllowedSpeed: Float = 1.45f
    ): Float? {
        if (targetWindowMs < 750L || generatedDurationMs <= 0L) return null
        val ratio = generatedDurationMs.toFloat() / targetWindowMs.toFloat()
        // Only tighten speed if Bengali audio overflows the dialogue window by > 18%
        if (ratio <= 1.18f) return null
        // Gently speed up toward the dialogue window while preserving natural Bengali intelligibility
        val targetSpeed = (currentSpeed * ratio.coerceAtMost(1.38f)).coerceIn(MIN_SPEED_FACTOR, maxAllowedSpeed)
        return if (targetSpeed - currentSpeed >= 0.06f) targetSpeed else null
    }

    /**
     * Applies smooth linear-interpolated resampling by [pitchFactor] (at the same sample rate)
     * plus light peak normalization so Male/Female character voices sound clear and consistent.
     */
    fun resampleForPitch(
        samples: FloatArray,
        pitchFactor: Float
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val safePitch = pitchFactor.coerceIn(MIN_PITCH_FACTOR, MAX_PITCH_FACTOR)
        if (abs(safePitch - 1.0f) < 0.015f) {
            return normalizePeak(samples)
        }

        val outLength = (samples.size / safePitch).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        val lastIdx = samples.lastIndex

        for (i in 0 until outLength) {
            val srcPos = i * safePitch
            val idx0 = floor(srcPos).toInt().coerceIn(0, lastIdx)
            val idx1 = ceil(srcPos).toInt().coerceIn(0, lastIdx)
            val frac = srcPos - idx0
            out[i] = samples[idx0] * (1f - frac) + samples[idx1] * frac
        }
        return normalizePeak(out)
    }

    /**
     * Normalizes peak amplitude gently to 0.92f if peak exceeds 0.95f (prevents clipping)
     * or boosts very quiet waveforms without amplifying pure silence.
     */
    fun normalizePeak(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        if (peak < 1e-4f) return samples
        val gain = when {
            peak > 0.95f -> 0.92f / peak
            peak < 0.35f -> (0.75f / peak).coerceAtMost(2.2f)
            else -> 1.0f
        }
        if (abs(gain - 1.0f) < 0.01f) return samples
        return FloatArray(samples.size) { i -> (samples[i] * gain).coerceIn(-1f, 1f) }
    }

    /**
     * Writes Mono 16-bit PCM RIFF/WAVE audio to [targetFile] and returns duration in milliseconds.
     */
    fun writeMono16BitWav(
        samples: FloatArray,
        sampleRate: Int,
        targetFile: File
    ): Long {
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        targetFile.parentFile?.mkdirs()

        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val dataByteSize = samples.size * 2
        val riffChunkSize = 36 + dataByteSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1.toShort()) // PCM format = 1
            putShort(numChannels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataByteSize)
        }

        val pcmBuffer = ByteBuffer.allocate(dataByteSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcm16 = (clamped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer.putShort(pcm16.toShort())
        }

        BufferedOutputStream(FileOutputStream(targetFile)).use { out ->
            out.write(header.array())
            out.write(pcmBuffer.array())
            out.flush()
        }

        return ((samples.size.toDouble() / sampleRate.toDouble()) * 1_000.0).roundToLong().coerceAtLeast(0L)
    }
}
