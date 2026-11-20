package com.voxora.player.ai.dubbing

import com.voxora.player.ai.tts.DubbedAudioSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tanh

/**
 * Pure Kotlin Background Audio (BGM / SFX) Preservation DSP engine.
 *
 * Implements two real, zero-cost, on-device audio preservation techniques:
 * 1. **Smart Audio Ducking**: Smoothly lowers the original video's audio gain ONLY during
 *    active Bengali dialogue windows using 150ms raised-cosine fade-in/fade-out ramps,
 *    restoring original BGM & SFX to 100% between lines.
 * 2. **Mid-Side Stereo Vocal Attenuation** (optional for offline mixing/export): Attenuates
 *    center-panned mono English dialogue (`Mid = (L+R)/2`) while keeping stereo ambience,
 *    score, and sound effects (`Side = (L-R)/2`) completely intact.
 */
object BackgroundAudioPreserver {

    const val DEFAULT_FADE_MS = 150L
    const val DEFAULT_DUCKING_STRENGTH = 0.95f
    const val DEFAULT_DUCKED_DIALOGUE_VOLUME = 0.05f
    const val DEFAULT_SILENCE_BGM_VOLUME = 0.85f
    const val MIN_DUCKING_STRENGTH = 0.0f
    const val MAX_DUCKING_STRENGTH = 0.95f
    const val DEFAULT_VOCAL_ATTENUATION = 0.65f

    /**
     * Builds sorted, non-overlapping [DialogueDuckingWindow] intervals from synthesized
     * Bengali [segments], shifted by [syncOffsetMs] and padded gently to cover each spoken line.
     */
    fun buildDuckingWindows(
        segments: List<DubbedAudioSegment>,
        syncOffsetMs: Long = 0L,
        mergeGapMs: Long = 120L
    ): List<DialogueDuckingWindow> {
        if (segments.isEmpty()) return emptyList()

        val rawWindows = segments.mapNotNull { seg ->
            val start = (seg.startMs + syncOffsetMs).coerceAtLeast(0L)
            val synthesizedDur = seg.durationMs.coerceAtLeast(0L)
            val originalDur = (seg.endMs - seg.startMs).coerceAtLeast(0L)
            val effectiveDur = maxOf(synthesizedDur, originalDur)
            if (effectiveDur <= 0L) return@mapNotNull null
            DialogueDuckingWindow(
                startMs = start,
                endMs = start + effectiveDur
            )
        }.sortedBy { it.startMs }

        if (rawWindows.isEmpty()) return emptyList()

        val merged = mutableListOf<DialogueDuckingWindow>()
        var current = rawWindows.first()
        for (i in 1 until rawWindows.size) {
            val next = rawWindows[i]
            if (next.startMs <= current.endMs + mergeGapMs) {
                current = current.copy(endMs = maxOf(current.endMs, next.endMs))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    /**
     * Computes the smooth original-audio multiplier in `[1 - duckingStrength, 1.0]`
     * at timeline timestamp [positionMs].
     *
     * - Inside a dialogue window: returns `duckedFloor = 1.0f - duckingStrength` (e.g., 0.20f at 80% strength).
     * - Within [fadeMs] before a window's start: smoothly ramps down with a raised-cosine curve.
     * - Within [fadeMs] after a window's end: smoothly ramps back up to 1.0f.
     * - Outside all dialogue windows: returns `1.0f` (100% original BGM & SFX).
     */
    fun computeDuckingGain(
        positionMs: Long,
        windows: List<DialogueDuckingWindow>,
        duckingStrength: Float = DEFAULT_DUCKING_STRENGTH,
        fadeMs: Long = DEFAULT_FADE_MS
    ): Float {
        val clampedStrength = duckingStrength.coerceIn(MIN_DUCKING_STRENGTH, MAX_DUCKING_STRENGTH)
        if (windows.isEmpty() || clampedStrength <= 0.001f) return 1.0f

        val duckedFloor = (1.0f - clampedStrength).coerceIn(0.05f, 1.0f)
        val effectiveFade = fadeMs.coerceAtLeast(1L)

        var minGain = 1.0f
        for (window in windows) {
            val fadeInStart = window.startMs - effectiveFade
            val fadeOutEnd = window.endMs + effectiveFade

            if (positionMs < fadeInStart) {
                // Windows are sorted by startMs; no later window can affect positionMs.
                break
            }
            if (positionMs > fadeOutEnd) {
                continue
            }

            val windowGain = when {
                positionMs in window.startMs..window.endMs -> duckedFloor
                positionMs < window.startMs -> {
                    // Smooth fade down from 1.0 -> duckedFloor over [fadeInStart, startMs]
                    val t = (positionMs - fadeInStart).toDouble() / effectiveFade.toDouble()
                    val smooth = 0.5 * (1.0 + cos(PI * t.coerceIn(0.0, 1.0))) // 1 -> 0
                    (duckedFloor + (1.0f - duckedFloor) * smooth.toFloat())
                }
                else -> {
                    // Smooth fade up from duckedFloor -> 1.0 over [endMs, fadeOutEnd]
                    val t = (positionMs - window.endMs).toDouble() / effectiveFade.toDouble()
                    val smooth = 0.5 * (1.0 - cos(PI * t.coerceIn(0.0, 1.0))) // 0 -> 1
                    (duckedFloor + (1.0f - duckedFloor) * smooth.toFloat())
                }
            }

            if (windowGain < minGain) {
                minGain = windowGain
            }
            if (minGain <= duckedFloor + 0.0005f) {
                return duckedFloor
            }
        }
        return minGain.coerceIn(duckedFloor, 1.0f)
    }

    /**
     * Computes clean non-overlapping Smart Ducking volume for live Bengali Dubbed playback:
     * - During active Bengali dialogue windows: drops aggressively to `duckedFloor = (1 - duckingStrength)`
     *   (default `0.05f` = 5% at 95% strength) so original English speech never clashes with Bengali audio.
     * - During silence / BGM between lines: smoothly restores to [silenceBgmVolume] (default `0.85f` = 85%).
     */
    fun computeSmartDuckingVolume(
        positionMs: Long,
        windows: List<DialogueDuckingWindow>,
        duckingStrength: Float = DEFAULT_DUCKING_STRENGTH,
        silenceBgmVolume: Float = DEFAULT_SILENCE_BGM_VOLUME,
        fadeMs: Long = DEFAULT_FADE_MS
    ): Float {
        val ceiling = silenceBgmVolume.coerceIn(0.05f, 1.0f)
        val clampedStrength = duckingStrength.coerceIn(MIN_DUCKING_STRENGTH, MAX_DUCKING_STRENGTH)
        if (windows.isEmpty() || clampedStrength <= 0.001f) return ceiling

        val duckedFloor = (1.0f - clampedStrength).coerceIn(DEFAULT_DUCKED_DIALOGUE_VOLUME, ceiling)
        val rawGain = computeDuckingGain(
            positionMs = positionMs,
            windows = windows,
            duckingStrength = clampedStrength,
            fadeMs = fadeMs
        )
        // Map normalized [duckedFloor, 1.0f] cleanly onto [duckedFloor, ceiling]
        val normalized = if (1.0f - duckedFloor > 0.0005f) {
            ((rawGain - duckedFloor) / (1.0f - duckedFloor)).coerceIn(0f, 1f)
        } else {
            0f
        }
        return (duckedFloor + (ceiling - duckedFloor) * normalized).coerceIn(duckedFloor, ceiling)
    }

    /**
     * Mid-Side Stereo Vocal Attenuation:
     * Movie dialogue is overwhelmingly mixed center-panned (Mid), while BGM and stereo SFX
     * carry rich stereo separation (Side). Reducing the Mid channel by [attenuationAmount]
     * cleanly pushes back English vocals while keeping stereo music & effects alive.
     */
    fun applyMidSideVocalAttenuation(
        left: Float,
        right: Float,
        attenuationAmount: Float = DEFAULT_VOCAL_ATTENUATION
    ): Pair<Float, Float> {
        val amount = attenuationAmount.coerceIn(0f, 0.85f)
        if (amount <= 0.001f) return left to right
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val attenuatedMid = mid * (1.0f - amount)
        val outLeft = (attenuatedMid + side).coerceIn(-1.0f, 1.0f)
        val outRight = (attenuatedMid - side).coerceIn(-1.0f, 1.0f)
        return outLeft to outRight
    }

    /**
     * Gentle soft-clipping limiter so combining original BGM + Bengali dubbed speech
     * never causes harsh digital distortion.
     */
    fun softClip(sample: Float): Float {
        if (sample in -0.92f..0.92f) return sample
        return tanh(sample.toDouble()).toFloat().coerceIn(-0.999f, 0.999f)
    }
}
