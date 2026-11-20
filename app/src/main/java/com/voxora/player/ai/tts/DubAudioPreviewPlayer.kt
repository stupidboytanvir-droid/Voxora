package com.voxora.player.ai.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Lightweight, safe local WAV audio player for auditioning synthesized Bengali dialogue lines
 * and offline Bengali VITS character previews directly inside AI Dubbing Studio.
 */
class DubAudioPreviewPlayer {

    private var mediaPlayer: MediaPlayer? = null

    fun playWavFile(
        filePath: String,
        onCompleted: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        stop()
        val file = File(filePath)
        if (!file.exists() || !file.isFile || file.length() <= 44L) {
            onError("Synthesized audio file is missing or incomplete.")
            return false
        }

        return runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    stop()
                    onError("Unable to play Bengali audio (error $what/$extra).")
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            true
        }.getOrElse { err ->
            stop()
            onError(err.message ?: "Unable to start Bengali audio playback.")
            false
        }
    }

    fun stop() {
        val current = mediaPlayer
        mediaPlayer = null
        if (current != null) {
            runCatching {
                if (current.isPlaying) {
                    current.stop()
                }
            }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
    }

    fun release() {
        stop()
    }
}
