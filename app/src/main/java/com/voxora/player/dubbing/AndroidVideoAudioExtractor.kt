package com.voxora.player.dubbing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Genuine, zero-cost, 100% offline Stage 1 Audio Extractor built on Android's native
 * MediaExtractor API. Inspects and extracts audio tracks from local videos without
 * interrupting Media3 ExoPlayer playback.
 */
class AndroidVideoAudioExtractor(
    private val context: Context
) : VideoAudioExtractor {

    override suspend fun inspectAudioTrack(mediaUriString: String): Result<ExtractedAudioTrackInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, Uri.parse(mediaUriString), null)
                    val audioTrack = findPrimaryAudioTrack(extractor)
                        ?: throw IllegalStateException("No audio track found in this video file.")
                    val (trackIndex, format) = audioTrack
                    buildTrackInfo(mediaUriString, trackIndex, format)
                } finally {
                    runCatching { extractor.release() }
                }
            }
        }

    override suspend fun extractAudioStreamToCache(mediaUriString: String): Result<ExtractedAudioTrackInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, Uri.parse(mediaUriString), null)
                    val audioTrack = findPrimaryAudioTrack(extractor)
                        ?: throw IllegalStateException("No audio track found in this video file.")
                    val (trackIndex, format) = audioTrack
                    val baseInfo = buildTrackInfo(mediaUriString, trackIndex, format)

                    extractor.selectTrack(trackIndex)

                    val extractDir = File(context.cacheDir, "dubbing_extract").apply { mkdirs() }
                    cleanupPreviousExtracts(extractDir)

                    val extension = extensionForMime(baseInfo.mimeType)
                    val outFile = File(extractDir, "voxora_audio_${System.currentTimeMillis()}.$extension")
                    val maxInputSize = runCatching {
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }.getOrDefault(256 * 1024).coerceIn(64 * 1024, 1024 * 1024)

                    val buffer = ByteBuffer.allocate(maxInputSize)
                    val byteArray = ByteArray(maxInputSize)
                    var totalWritten = 0L

                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) break
                            buffer.position(0)
                            buffer.get(byteArray, 0, sampleSize)
                            fos.write(byteArray, 0, sampleSize)
                            totalWritten += sampleSize
                            buffer.clear()
                            if (!extractor.advance()) break
                        }
                        fos.flush()
                    }

                    baseInfo.copy(
                        extractedFilePath = outFile.absolutePath,
                        extractedBytes = totalWritten
                    )
                } finally {
                    runCatching { extractor.release() }
                }
            }
        }

    private fun findPrimaryAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/", ignoreCase = true)) {
                return index to format
            }
        }
        return null
    }

    private fun buildTrackInfo(
        uriString: String,
        trackIndex: Int,
        format: MediaFormat
    ): ExtractedAudioTrackInfo {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48_000)
        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
        val bitrate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
        val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

        return ExtractedAudioTrackInfo(
            uriString = uriString,
            trackIndex = trackIndex,
            mimeType = mime,
            codecDisplayName = friendlyCodecName(mime),
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitrateBps = bitrate,
            durationMs = durationUs / 1_000L
        )
    }

    private fun friendlyCodecName(mime: String): String = when (mime.lowercase()) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "Dolby AC-3"
        "audio/eac3" -> "Dolby E-AC-3"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter("/").uppercase()
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "audio/mp4a-latm" -> "aac"
        "audio/mpeg" -> "mp3"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "ogg"
        "audio/flac" -> "flac"
        else -> "bin"
    }

    private fun cleanupPreviousExtracts(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }
}
