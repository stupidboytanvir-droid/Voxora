package com.voxora.player.player

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.voxora.player.model.AudioEqPreset

/**
 * Lightweight Android-native Audio FX controller attaching [LoudnessEnhancer] (0% to +200% boost)
 * and 5-band [Equalizer] presets directly to ExoPlayer's `audioSessionId`.
 * All AudioEffect calls are safely guarded for devices/emulators that restrict audio effects.
 */
class AudioFxController {

    private var attachedSessionId: Int = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null

    private var currentBoostPercent: Int = 0
    private var currentPreset: AudioEqPreset = AudioEqPreset.NORMAL

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == attachedSessionId) return
        releaseEffectsOnly()
        attachedSessionId = audioSessionId

        runCatching {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
        }
        runCatching {
            equalizer = Equalizer(0, audioSessionId)
        }

        applyVolumeBoost(currentBoostPercent)
        applyEqPreset(currentPreset)
    }

    fun applyVolumeBoost(percent: Int) {
        currentBoostPercent = percent.coerceIn(0, 200)
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            if (currentBoostPercent <= 0) {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
            } else {
                // Smooth gain mapping: +200% maps to +2200 mB (~+22 dB safe peak boost)
                val targetMb = (currentBoostPercent * 11).coerceIn(0, 2200)
                enhancer.setTargetGain(targetMb)
                enhancer.enabled = true
            }
        }
    }

    fun applyEqPreset(preset: AudioEqPreset) {
        currentPreset = preset
        val eq = equalizer ?: return
        runCatching {
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) return@runCatching
            val range = eq.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()

            if (preset == AudioEqPreset.NORMAL) {
                for (b in 0 until bands) {
                    eq.setBandLevel(b.toShort(), 0.toShort())
                }
                eq.enabled = false
                return@runCatching
            }

            val offsets = preset.bandOffsetsMb
            for (b in 0 until bands) {
                val mappedIdx = ((b.toFloat() / bands.coerceAtLeast(1)) * offsets.size)
                    .toInt()
                    .coerceIn(0, offsets.lastIndex)
                val level = offsets[mappedIdx].toInt().coerceIn(minLevel, maxLevel).toShort()
                eq.setBandLevel(b.toShort(), level)
            }
            eq.enabled = true
        }
    }

    private fun releaseEffectsOnly() {
        runCatching { loudnessEnhancer?.release() }
        runCatching { equalizer?.release() }
        loudnessEnhancer = null
        equalizer = null
        attachedSessionId = 0
    }

    fun release() {
        releaseEffectsOnly()
    }
}
