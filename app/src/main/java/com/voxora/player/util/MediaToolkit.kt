package com.voxora.player.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, Android-native Media Toolkit (ZERO FFmpeg binaries):
 * 1. Quick Screenshot capture (`Pictures/VOXORA/`) via MediaMetadataRetriever / Bitmap save.
 * 2. Lossless Clip Cutter (`Movies/VOXORA/Clips/`) via `MediaExtractor` + `MediaMuxer`.
 * 3. Video to Audio Extractor (`Music/VOXORA/`) via `MediaExtractor` + `MediaMuxer` (`.m4a`).
 */
object MediaToolkit {

    private fun timestampSlug(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun sanitizeBaseName(title: String, fallback: String): String {
        return title
            .replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
            .trim()
            .take(40)
            .ifBlank { fallback }
    }

    /**
     * Captures the video frame at [positionMs] cleanly and saves it as a PNG in `Pictures/VOXORA/`.
     */
    suspend fun captureVideoScreenshot(
        context: Context,
        mediaUriString: String,
        videoTitle: String,
        positionMs: Long,
        preCapturedBitmap: Bitmap? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = preCapturedBitmap ?: extractFrameBitmap(context, mediaUriString, positionMs)
                ?: throw IllegalStateException("Could not capture frame from this video stream.")

            val fileName = "${sanitizeBaseName(videoTitle, "VOXORA")}_${timestampSlug()}.png"
            val savedPath = saveBitmapToPictures(context, bitmap, fileName)
            if (preCapturedBitmap == null) {
                bitmap.recycle()
            }
            savedPath
        }
    }

    private fun extractFrameBitmap(
        context: Context,
        mediaUriString: String,
        positionMs: Long
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(mediaUriString)
            if (mediaUriString.startsWith("http://", true) || mediaUriString.startsWith("https://", true)) {
                retriever.setDataSource(mediaUriString, emptyMap())
            } else {
                retriever.setDataSource(context, uri)
            }
            val timeUs = (positionMs.coerceAtLeast(0L)) * 1_000L
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveBitmapToPictures(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VOXORA")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create image entry in Pictures/VOXORA")
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Failed to encode screenshot PNG")
                }
            } ?: throw IllegalStateException("Unable to write screenshot")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Pictures/VOXORA/$fileName"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "VOXORA"
            ).apply { mkdirs() }
            val outFile = File(dir, fileName)
            outFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        }
    }

    /**
     * Lossless, instant Clip Cutter using Android `MediaExtractor` + `MediaMuxer` (zero re-encoding).
     * Saves trimmed clip to `Movies/VOXORA/Clips/`.
     */
    suspend fun trimVideoLossless(
        context: Context,
        mediaUriString: String,
        videoTitle: String,
        startMs: Long,
        endMs: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(endMs > startMs + 500L) { "Clip end time must be at least 0.5s after start time." }
            val tempOut = File(context.cacheDir, "clip_${timestampSlug()}.mp4")
            try {
                remuxRange(
                    context = context,
                    mediaUriString = mediaUriString,
                    outputFile = tempOut,
                    startUs = startMs.coerceAtLeast(0L) * 1_000L,
                    endUs = endMs * 1_000L,
                    audioOnly = false
                )
                val fileName = "${sanitizeBaseName(videoTitle, "VOXORA_Clip")}_${timestampSlug()}.mp4"
                publishFileToMediaStore(
                    context = context,
                    sourceFile = tempOut,
                    displayName = fileName,
                    mimeType = "video/mp4",
                    relativePath = "${Environment.DIRECTORY_MOVIES}/VOXORA/Clips",
                    collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    legacyDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "VOXORA/Clips"
                    )
                )
            } finally {
                runCatching { tempOut.delete() }
            }
        }
    }

    /**
     * One-tap Video to Audio extractor using Android `MediaExtractor` + `MediaMuxer`.
     * Extracts the original audio track losslessly to `.m4a` in `Music/VOXORA/`.
     */
    suspend fun extractAudioTrack(
        context: Context,
        mediaUriString: String,
        videoTitle: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tempOut = File(context.cacheDir, "audio_${timestampSlug()}.m4a")
            try {
                remuxRange(
                    context = context,
                    mediaUriString = mediaUriString,
                    outputFile = tempOut,
                    startUs = 0L,
                    endUs = Long.MAX_VALUE,
                    audioOnly = true
                )
                val fileName = "${sanitizeBaseName(videoTitle, "VOXORA_Audio")}_${timestampSlug()}.m4a"
                publishFileToMediaStore(
                    context = context,
                    sourceFile = tempOut,
                    displayName = fileName,
                    mimeType = "audio/mp4",
                    relativePath = "${Environment.DIRECTORY_MUSIC}/VOXORA",
                    collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    legacyDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "VOXORA"
                    )
                )
            } finally {
                runCatching { tempOut.delete() }
            }
        }
    }

    private fun remuxRange(
        context: Context,
        mediaUriString: String,
        outputFile: File,
        startUs: Long,
        endUs: Long,
        audioOnly: Boolean
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val uri = Uri.parse(mediaUriString)
            if (mediaUriString.startsWith("http://", true) || mediaUriString.startsWith("https://", true)) {
                extractor.setDataSource(mediaUriString, emptyMap())
            } else {
                extractor.setDataSource(context, uri, null)
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackIndexMap = mutableMapOf<Int, Int>()
            var maxBufferSize = 512 * 1024

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")

                if (audioOnly && !isAudio) continue
                if (!audioOnly && !isVideo && !isAudio) continue

                // Skip codecs incompatible with MPEG-4 container gracefully
                val dstTrack = runCatching { muxer.addTrack(format) }.getOrNull() ?: continue
                extractor.selectTrack(i)
                trackIndexMap[i] = dstTrack

                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBufferSize = maxOf(maxBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (isVideo && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    runCatching { muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION)) }
                }
            }

            if (trackIndexMap.isEmpty()) {
                throw IllegalStateException(
                    if (audioOnly) "No compatible AAC/MP4 audio track found in this video."
                    else "No compatible video/audio tracks found for lossless trimming."
                )
            }

            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(maxBufferSize.coerceIn(256 * 1024, 4 * 1024 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()
            var basePtsUs: Long = -1L

            while (true) {
                val sampleTrack = extractor.sampleTrackIndex
                if (sampleTrack < 0) break
                val dstTrack = trackIndexMap[sampleTrack]
                if (dstTrack == null) {
                    extractor.advance()
                    continue
                }

                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (endUs != Long.MAX_VALUE && sampleTimeUs > endUs) {
                    break
                }
                if (sampleTimeUs < 0L) {
                    extractor.advance()
                    continue
                }

                if (basePtsUs < 0L) basePtsUs = sampleTimeUs
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                val sampleFlags = extractor.sampleFlags
                var codecFlags = 0
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                bufferInfo.flags = codecFlags

                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
            if (muxer != null) {
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
        }
    }

    private fun publishFileToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        collectionUri: Uri,
        legacyDir: File
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val dstUri = resolver.insert(collectionUri, values)
                ?: throw IllegalStateException("Could not create output file in $relativePath")
            resolver.openOutputStream(dstUri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Unable to write output file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(dstUri, values, null, null)
            "$relativePath/$displayName"
        } else {
            legacyDir.mkdirs()
            val outFile = File(legacyDir, displayName)
            sourceFile.copyTo(outFile, overwrite = true)
            outFile.absolutePath
        }
    }
}
