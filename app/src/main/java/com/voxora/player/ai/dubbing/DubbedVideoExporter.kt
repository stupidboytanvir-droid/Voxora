package com.voxora.player.ai.dubbing

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingSessionConfig
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
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Completed offline export result ready for immediate Open or Share actions.
 */
data class ExportedDubbedMedia(
    val uri: Uri,
    val displayName: String,
    val locationLabel: String,
    val mimeType: String,
    val format: DubbingExportFormat,
    val fileSizeBytes: Long
)

/**
 * Real offline exporter for VOXORA Media Player v2.0.0.
 *
 * - Mixes the original video's stereo audio (with **Smart Audio Ducking** and optional
 *   **Mid-Side Stereo Vocal Reduction**) together with the timeline-aligned Bengali dubbed
 *   audio track in low-memory streaming chunks on [Dispatchers.IO].
 * - Supports three real offline outputs:
 *   1. **MP4 Video** (`Movies/VOXORA/`): Video stream **pass-through** (ZERO slow video
 *      re-encoding!) muxed with the newly encoded 192 kbps stereo AAC-LC Bengali+BGM track.
 *   2. **M4A Audio** (`Music/VOXORA/`): Mixed stereo AAC-LC Bengali+BGM audio track.
 *   3. **WAV Audio** (`Music/VOXORA/`): Lossless 16-bit stereo 44.1 kHz PCM mixed track.
 * - Uses scoped `MediaStore` on Android 10+ (API 29+) requiring ZERO storage permissions,
 *   and gracefully saves to app external `VOXORA_Exports/` via `FileProvider` on API 24–28.
 */
class DubbedVideoExporter(
    private val context: Context,
    private val trackAssembler: DubbedTrackAssembler
) {

    suspend fun exportDubbedMedia(
        videoUriString: String,
        videoTitle: String,
        videoDurationMsHint: Long,
        dubbedSegments: List<DubbedAudioSegment>,
        config: DubbingSessionConfig,
        format: DubbingExportFormat,
        onProgress: (fraction: Float, statusMessage: String) -> Unit
    ): Result<ExportedDubbedMedia> = withContext(Dispatchers.IO) {
        if (videoUriString.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No local video is currently loaded."))
        }
        if (dubbedSegments.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No synthesized Bengali voice lines exist for this video yet.")
            )
        }

        val workDir = File(context.cacheDir, "voxora_dub/export_work").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val tempDubMonoWav = File(workDir, "dub_mono_${stamp}.wav")
        val tempMixedStereoWav = File(workDir, "mixed_stereo_${stamp}.wav")
        val tempAacM4a = File(workDir, "mixed_aac_${stamp}.m4a")
        val tempMuxedMp4 = File(workDir, "dubbed_video_${stamp}.mp4")

        try {
            coroutineContext.ensureActive()

            // Step 1: Assemble timeline-aligned Bengali dubbed track at 44.1 kHz with user's syncOffsetMs
            onProgress(0.05f, "Aligning Bengali character voices on 44.1 kHz timeline...")
            val assembledDub = trackAssembler.assembleTimelineWav(
                videoUri = videoUriString,
                segments = dubbedSegments,
                totalDurationMsHint = videoDurationMsHint,
                syncOffsetMs = config.syncOffsetMs,
                targetSampleRate = DubbedTrackAssembler.EXPORT_SAMPLE_RATE,
                outputFileOverride = tempDubMonoWav,
                onProgress = { p ->
                    onProgress(0.05f + p * 0.15f, "Aligning Bengali character voices (${(p * 100).toInt()}%)...")
                }
            ).getOrThrow()

            // Step 2: Stream-decode original video audio and mix with Smart Ducking + optional Vocal Reduction
            coroutineContext.ensureActive()
            onProgress(0.20f, "Mixing original BGM/SFX with Bengali voices...")
            val duckingWindows = BackgroundAudioPreserver.buildDuckingWindows(
                segments = dubbedSegments,
                syncOffsetMs = config.syncOffsetMs
            )

            mixOriginalAndBengaliToStereoWav(
                videoUriString = videoUriString,
                dubMonoWavFile = assembledDub.wavFile,
                outputStereoWav = tempMixedStereoWav,
                duckingWindows = duckingWindows,
                config = config,
                totalDurationMsHint = maxOf(videoDurationMsHint, assembledDub.totalDurationMs),
                onProgress = { p ->
                    val endPct = if (format == DubbingExportFormat.WAV_AUDIO) 0.88f else 0.55f
                    onProgress(
                        0.20f + p * (endPct - 0.20f),
                        "Mixing BGM ducking & Bengali dialogue (${(p * 100).toInt()}%)..."
                    )
                }
            )

            // Step 3: Encode / Mux depending on target format
            val safeBase = sanitizeFileName(videoTitle)
            val outputFileName = "${safeBase}_bengali_dub.${format.extension}"

            val finalTempFile: File = when (format) {
                DubbingExportFormat.WAV_AUDIO -> {
                    tempMixedStereoWav
                }
                DubbingExportFormat.M4A_AUDIO -> {
                    coroutineContext.ensureActive()
                    onProgress(0.58f, "Encoding mixed Bengali audio to AAC-LC (M4A)...")
                    encodeStereoWavToAacM4a(
                        stereoWavFile = tempMixedStereoWav,
                        outputM4aFile = tempAacM4a,
                        onProgress = { p ->
                            onProgress(0.58f + p * 0.32f, "Encoding AAC-LC audio (${(p * 100).toInt()}%)...")
                        }
                    )
                    tempAacM4a
                }
                DubbingExportFormat.MP4_VIDEO -> {
                    coroutineContext.ensureActive()
                    onProgress(0.56f, "Encoding mixed Bengali + BGM audio track (AAC-LC)...")
                    encodeStereoWavToAacM4a(
                        stereoWavFile = tempMixedStereoWav,
                        outputM4aFile = tempAacM4a,
                        onProgress = { p ->
                            onProgress(0.56f + p * 0.22f, "Encoding mixed audio to AAC (${(p * 100).toInt()}%)...")
                        }
                    )
                    coroutineContext.ensureActive()
                    onProgress(0.78f, "Muxing original video (pass-through) + Bengali audio into MP4...")
                    muxVideoPassThroughWithAudio(
                        sourceVideoUri = Uri.parse(videoUriString),
                        aacAudioFile = tempAacM4a,
                        outputMp4File = tempMuxedMp4,
                        onProgress = { p ->
                            onProgress(0.78f + p * 0.14f, "Muxing MP4 video pass-through (${(p * 100).toInt()}%)...")
                        }
                    )
                    tempMuxedMp4
                }
            }

            // Step 4: Save finished file to public Movies/VOXORA or Music/VOXORA (MediaStore on API 29+)
            coroutineContext.ensureActive()
            onProgress(0.93f, "Saving $outputFileName to device storage...")
            val published = publishExportedFile(
                sourceFile = finalTempFile,
                displayName = outputFileName,
                format = format
            )

            onProgress(1.0f, "Export complete: ${published.locationLabel}/${published.displayName}")
            Result.success(published)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { if (tempDubMonoWav.exists()) tempDubMonoWav.delete() }
            runCatching { if (tempMixedStereoWav.exists()) tempMixedStereoWav.delete() }
            runCatching { if (tempAacM4a.exists()) tempAacM4a.delete() }
            runCatching { if (tempMuxedMp4.exists()) tempMuxedMp4.delete() }
        }
    }

    /**
     * Streams original video audio decoded via [MediaExtractor] + [MediaCodec], applies optional
     * Mid-Side Stereo Vocal Attenuation & smooth Smart Audio Ducking, and sums with the
     * 44.1 kHz mono Bengali dubbed WAV file into a 44.1 kHz 16-bit stereo PCM WAV file.
     */
    private suspend fun mixOriginalAndBengaliToStereoWav(
        videoUriString: String,
        dubMonoWavFile: File,
        outputStereoWav: File,
        duckingWindows: List<DialogueDuckingWindow>,
        config: DubbingSessionConfig,
        totalDurationMsHint: Long,
        onProgress: (Float) -> Unit
    ) {
        val targetRate = DubbedTrackAssembler.EXPORT_SAMPLE_RATE
        val dubHeader = DubbedTrackAssembler.inspectWavHeader(dubMonoWavFile)
            ?: throw IllegalStateException("Assembled Bengali WAV header is unreadable.")

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var dubInputStream: FileInputStream? = null

        try {
            extractor.setDataSource(context, Uri.parse(videoUriString), null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            dubInputStream = FileInputStream(dubMonoWavFile)
            skipExactBytes(dubInputStream, dubHeader.dataOffset.toLong())
            var dubFramesRemaining = dubHeader.totalFrames

            var totalMixedFrames = 0L

            BufferedOutputStream(FileOutputStream(outputStereoWav), 64 * 1024).use { wavOut ->
                wavOut.write(DubbedTrackAssembler.buildWavHeaderBytes(0L, targetRate, 2))

                if (audioTrackIndex >= 0 && inputFormat != null) {
                    val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                        ?: throw IllegalStateException("Source audio MIME missing.")
                    val detectedDurMs = runCatching { inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L }
                        .getOrDefault(totalDurationMsHint)
                    val totalDurMs = maxOf(detectedDurMs, totalDurationMsHint, 1L)

                    var srcRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(targetRate)
                    var srcChannels = runCatching { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)

                    extractor.selectTrack(audioTrackIndex)
                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()

                    val bufInfo = MediaCodec.BufferInfo()
                    var sawInputEos = false
                    var sawOutputEos = false
                    var scratchBytes = ByteArray(64 * 1024)
                    var dubReadBytes = ByteArray(32 * 1024)
                    var outStereoBytes = ByteArray(64 * 1024)
                    var lastProgressMs = 0L

                    while (!sawOutputEos) {
                        coroutineContext.ensureActive()

                        if (!sawInputEos) {
                            val inIdx = decoder.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                val inBuf = decoder.getInputBuffer(inIdx)
                                if (inBuf != null) {
                                    val size = extractor.readSampleData(inBuf, 0)
                                    if (size < 0) {
                                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        sawInputEos = true
                                    } else {
                                        val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                                        decoder.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000L)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val fmt = decoder.outputFormat
                                srcRate = runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(srcRate)
                                srcChannels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(srcChannels)
                            }
                            outIdx >= 0 -> {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null && bufInfo.size > 0) {
                                    if (scratchBytes.size < bufInfo.size) scratchBytes = ByteArray(bufInfo.size)
                                    outBuf.position(bufInfo.offset)
                                    outBuf.limit(bufInfo.offset + bufInfo.size)
                                    outBuf.get(scratchBytes, 0, bufInfo.size)

                                    val (origL, origR) = decodeAndResampleToStereoFloat(
                                        pcmBytes = scratchBytes,
                                        byteLength = bufInfo.size,
                                        srcSampleRate = srcRate,
                                        srcChannels = srcChannels,
                                        dstSampleRate = targetRate
                                    )
                                    val frameCount = origL.size
                                    if (frameCount > 0) {
                                        if (dubReadBytes.size < frameCount * 2) dubReadBytes = ByteArray(frameCount * 2)
                                        if (outStereoBytes.size < frameCount * 4) outStereoBytes = ByteArray(frameCount * 4)

                                        val dubFramesToRead = minOf(frameCount.toLong(), dubFramesRemaining).toInt()
                                        val dubBytesRead = if (dubFramesToRead > 0) {
                                            readUpTo(dubInputStream, dubReadBytes, dubFramesToRead * 2)
                                        } else {
                                            0
                                        }
                                        val actualDubFrames = dubBytesRead / 2
                                        dubFramesRemaining = (dubFramesRemaining - actualDubFrames).coerceAtLeast(0L)

                                        mixStereoChunk(
                                            origL = origL,
                                            origR = origR,
                                            dubMonoPcm16 = dubReadBytes,
                                            dubFrameCount = actualDubFrames,
                                            chunkStartFrame = totalMixedFrames,
                                            sampleRate = targetRate,
                                            duckingWindows = duckingWindows,
                                            config = config,
                                            outInterleavedStereo16 = outStereoBytes
                                        )
                                        wavOut.write(outStereoBytes, 0, frameCount * 4)
                                        totalMixedFrames += frameCount

                                        val currentMs = (totalMixedFrames * 1_000L) / targetRate
                                        if (currentMs - lastProgressMs >= 250L) {
                                            lastProgressMs = currentMs
                                            onProgress((currentMs.toFloat() / totalDurMs.toFloat()).coerceIn(0f, 0.95f))
                                        }
                                    }
                                }
                                decoder.releaseOutputBuffer(outIdx, false)
                                if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    sawOutputEos = true
                                }
                            }
                        }
                    }
                }

                // If Bengali dubbed track extends slightly past original audio (or video had no audio track), append tail
                val tailChunkFrames = 4096
                val tailDubBytes = ByteArray(tailChunkFrames * 2)
                val tailStereoBytes = ByteArray(tailChunkFrames * 4)
                val dubGain = config.dubbedAudioVolume.coerceIn(0f, 1f)
                while (dubFramesRemaining > 0L) {
                    coroutineContext.ensureActive()
                    val toRead = minOf(tailChunkFrames.toLong(), dubFramesRemaining).toInt()
                    val nBytes = readUpTo(dubInputStream, tailDubBytes, toRead * 2)
                    val framesRead = nBytes / 2
                    if (framesRead <= 0) break
                    dubFramesRemaining -= framesRead

                    var outPos = 0
                    for (i in 0 until framesRead) {
                        val lo = tailDubBytes[i * 2].toInt() and 0xFF
                        val hi = tailDubBytes[i * 2 + 1].toInt()
                        val rawSample = ((hi shl 8) or lo).toShort().toFloat() / 32768.0f
                        val scaled = BackgroundAudioPreserver.softClip(rawSample * dubGain)
                        val s16 = (scaled * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                        val b0 = (s16 and 0xFF).toByte()
                        val b1 = ((s16 ushr 8) and 0xFF).toByte()
                        tailStereoBytes[outPos++] = b0
                        tailStereoBytes[outPos++] = b1
                        tailStereoBytes[outPos++] = b0
                        tailStereoBytes[outPos++] = b1
                    }
                    wavOut.write(tailStereoBytes, 0, framesRead * 4)
                    totalMixedFrames += framesRead
                }
                wavOut.flush()
            }

            // Patch final WAV header byte sizes
            val totalDataBytes = totalMixedFrames * 4L
            RandomAccessFile(outputStereoWav, "rw").use { raf ->
                raf.seek(0L)
                raf.write(DubbedTrackAssembler.buildWavHeaderBytes(totalDataBytes, targetRate, 2))
            }
            onProgress(1f)
        } finally {
            runCatching { dubInputStream?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun mixStereoChunk(
        origL: FloatArray,
        origR: FloatArray,
        dubMonoPcm16: ByteArray,
        dubFrameCount: Int,
        chunkStartFrame: Long,
        sampleRate: Int,
        duckingWindows: List<DialogueDuckingWindow>,
        config: DubbingSessionConfig,
        outInterleavedStereo16: ByteArray
    ) {
        val frameCount = origL.size
        val origUserVol = config.originalAudioVolume.coerceIn(0f, 1f)
        val dubUserVol = config.dubbedAudioVolume.coerceIn(0f, 1f)
        val useDucking = config.preserveOriginalBackgroundAudio
        val useVocalCut = config.vocalAttenuationEnabled
        val isDubOnly = config.audioOutputMode == DubbingAudioOutputMode.BENGALI_DUBBED_ONLY

        // Recompute smooth ducking gain every 128 samples (~2.9ms) for silky ramps & fast throughput
        var currentDuckGain = 1.0f
        var outPos = 0

        for (i in 0 until frameCount) {
            if ((i and 0x7F) == 0) {
                val tMs = ((chunkStartFrame + i) * 1_000L) / sampleRate
                currentDuckGain = if (useDucking) {
                    BackgroundAudioPreserver.computeDuckingGain(
                        positionMs = tMs,
                        windows = duckingWindows,
                        duckingStrength = config.bgmDuckingStrength,
                        fadeMs = BackgroundAudioPreserver.DEFAULT_FADE_MS
                    )
                } else {
                    1.0f
                }
            }

            var l = origL[i]
            var r = origR[i]

            if (useVocalCut) {
                val (attL, attR) = BackgroundAudioPreserver.applyMidSideVocalAttenuation(
                    left = l,
                    right = r,
                    attenuationAmount = BackgroundAudioPreserver.DEFAULT_VOCAL_ATTENUATION
                )
                l = attL
                r = attR
            }

            val origGain = when {
                isDubOnly && !useDucking -> 0f
                isDubOnly && useDucking -> {
                    val scale = if (currentDuckGain < 0.95f) 0.06f else 0.30f
                    origUserVol * scale
                }
                else -> origUserVol * currentDuckGain
            }

            val dubSample = if (i < dubFrameCount) {
                val lo = dubMonoPcm16[i * 2].toInt() and 0xFF
                val hi = dubMonoPcm16[i * 2 + 1].toInt()
                (((hi shl 8) or lo).toShort().toFloat() / 32768.0f) * dubUserVol
            } else {
                0f
            }

            val mixedL = BackgroundAudioPreserver.softClip(l * origGain + dubSample)
            val mixedR = BackgroundAudioPreserver.softClip(r * origGain + dubSample)

            val sL = (mixedL * 32767.0f).roundToInt().coerceIn(-32768, 32767)
            val sR = (mixedR * 32767.0f).roundToInt().coerceIn(-32768, 32767)
            outInterleavedStereo16[outPos++] = (sL and 0xFF).toByte()
            outInterleavedStereo16[outPos++] = ((sL ushr 8) and 0xFF).toByte()
            outInterleavedStereo16[outPos++] = (sR and 0xFF).toByte()
            outInterleavedStereo16[outPos++] = ((sR ushr 8) and 0xFF).toByte()
        }
    }

    /**
     * Encodes a 16-bit stereo PCM WAV file to 192 kbps AAC-LC inside an M4A container via
     * Android platform [MediaCodec] + [MediaMuxer].
     */
    private suspend fun encodeStereoWavToAacM4a(
        stereoWavFile: File,
        outputM4aFile: File,
        onProgress: (Float) -> Unit
    ) {
        val wavInfo = DubbedTrackAssembler.inspectWavHeader(stereoWavFile)
            ?: throw IllegalStateException("Mixed stereo WAV header is invalid.")
        val sampleRate = wavInfo.sampleRate
        val channels = wavInfo.channels.coerceIn(1, 2)
        val totalBytes = wavInfo.dataSize.toLong().coerceAtLeast(1L)
        val bytesPerFrame = channels * 2

        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var audioTrackIndex = -1

        FileInputStream(stereoWavFile).use { pcmIn ->
            try {
                skipExactBytes(pcmIn, wavInfo.dataOffset.toLong())
                encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                var totalFramesRead = 0L
                var totalBytesRead = 0L
                val readChunk = ByteArray(8192)

                while (!sawOutputEos) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIdx = encoder.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val maxToRead = minOf(inBuf.capacity(), readChunk.size)
                                val n = pcmIn.read(readChunk, 0, maxToRead)
                                if (n <= 0) {
                                    val eosPtsUs = (totalFramesRead * 1_000_000L) / sampleRate
                                    encoder.queueInputBuffer(inIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    inBuf.clear()
                                    inBuf.put(readChunk, 0, n)
                                    val ptsUs = (totalFramesRead * 1_000_000L) / sampleRate
                                    encoder.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                    totalBytesRead += n
                                    totalFramesRead += (n / bytesPerFrame)
                                    onProgress((totalBytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.96f))
                                }
                            }
                        }
                    }

                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (outBuf != null && bufferInfo.size > 0 && !isConfig && muxerStarted && audioTrackIndex >= 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
                onProgress(1f)
            } finally {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }
    }

    /**
     * Pass-through MP4 video muxer: copies the original compressed video stream verbatim
     * (ZERO slow video re-encoding!) and pairs it with the newly encoded mixed Bengali+BGM AAC track.
     */
    private suspend fun muxVideoPassThroughWithAudio(
        sourceVideoUri: Uri,
        aacAudioFile: File,
        outputMp4File: File,
        onProgress: (Float) -> Unit
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(context, sourceVideoUri, null)
            audioExtractor.setDataSource(aacAudioFile.absolutePath)

            var srcVideoTrack = -1
            var srcVideoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/", ignoreCase = true)) {
                    srcVideoTrack = i
                    srcVideoFormat = fmt
                    break
                }
            }
            if (srcVideoTrack < 0 || srcVideoFormat == null) {
                throw IllegalStateException("Original file contains no video track for MP4 export.")
            }

            var srcAudioTrack = -1
            var srcAudioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val fmt = audioExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    srcAudioTrack = i
                    srcAudioFormat = fmt
                    break
                }
            }
            if (srcAudioTrack < 0 || srcAudioFormat == null) {
                throw IllegalStateException("Encoded Bengali AAC audio track could not be read for muxing.")
            }

            // Preserve original video rotation metadata so portrait/landscape playback matches source
            val rotation = resolveVideoRotation(sourceVideoUri, srcVideoFormat)
            if (rotation != 0) {
                runCatching { muxer.setOrientationHint(rotation) }
            }

            val totalDurationUs = runCatching { srcVideoFormat.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(1L)
                .coerceAtLeast(1L)

            val outVideoTrack = try {
                muxer.addTrack(srcVideoFormat)
            } catch (t: Throwable) {
                val videoMime = srcVideoFormat.getString(MediaFormat.KEY_MIME) ?: "unknown"
                throw IllegalStateException(
                    "MP4 pass-through does not support video codec ($videoMime). Export as M4A or WAV audio instead.",
                    t
                )
            }
            val outAudioTrack = muxer.addTrack(srcAudioFormat)
            muxer.start()
            muxerStarted = true

            videoExtractor.selectTrack(srcVideoTrack)
            audioExtractor.selectTrack(srcAudioTrack)

            val maxInputSize = runCatching { srcVideoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrDefault(1024 * 1024)
                .coerceIn(256 * 1024, 4 * 1024 * 1024)
            val copyBuffer = ByteBuffer.allocateDirect(maxInputSize)
            val info = MediaCodec.BufferInfo()

            // 1. Copy compressed video frames verbatim (pass-through)
            while (true) {
                coroutineContext.ensureActive()
                copyBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(copyBuffer, 0)
                if (sampleSize < 0) break
                val ptsUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = ptsUs
                info.flags = mapExtractorFlagsToCodecFlags(videoExtractor.sampleFlags)
                muxer.writeSampleData(outVideoTrack, copyBuffer, info)
                videoExtractor.advance()
                onProgress(((ptsUs.toFloat() / totalDurationUs.toFloat()) * 0.70f).coerceIn(0f, 0.70f))
            }

            // 2. Copy encoded Bengali+BGM AAC frames verbatim
            while (true) {
                coroutineContext.ensureActive()
                copyBuffer.clear()
                val sampleSize = audioExtractor.readSampleData(copyBuffer, 0)
                if (sampleSize < 0) break
                val ptsUs = audioExtractor.sampleTime.coerceAtLeast(0L)
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = ptsUs
                info.flags = mapExtractorFlagsToCodecFlags(audioExtractor.sampleFlags)
                muxer.writeSampleData(outAudioTrack, copyBuffer, info)
                audioExtractor.advance()
                onProgress((0.70f + (ptsUs.toFloat() / totalDurationUs.toFloat()) * 0.29f).coerceIn(0.70f, 0.99f))
            }
            onProgress(1f)
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun resolveVideoRotation(sourceUri: Uri, format: MediaFormat): Int {
        runCatching {
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                return format.getInteger(MediaFormat.KEY_ROTATION)
            }
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun mapExtractorFlagsToCodecFlags(extractorFlags: Int): Int {
        var flags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    private fun publishExportedFile(
        sourceFile: File,
        displayName: String,
        format: DubbingExportFormat
    ): ExportedDubbedMedia {
        val sizeBytes = sourceFile.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isVideo = format == DubbingExportFormat.MP4_VIDEO
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = if (isVideo) {
                "${Environment.DIRECTORY_MOVIES}/VOXORA"
            } else {
                "${Environment.DIRECTORY_MUSIC}/VOXORA"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val insertedUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore declined creating $relativePath/$displayName")

            try {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                    out.flush()
                } ?: throw IllegalStateException("Unable to open output stream for $displayName")

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(insertedUri, doneValues, null, null)

                return ExportedDubbedMedia(
                    uri = insertedUri,
                    displayName = displayName,
                    locationLabel = relativePath,
                    mimeType = format.mimeType,
                    format = format,
                    fileSizeBytes = sizeBytes
                )
            } catch (t: Throwable) {
                runCatching { resolver.delete(insertedUri, null, null) }
                throw t
            }
        } else {
            val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "VOXORA_Exports").apply {
                mkdirs()
            }
            val destFile = File(exportDir, displayName)
            sourceFile.copyTo(destFile, overwrite = true)
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
            return ExportedDubbedMedia(
                uri = shareUri,
                displayName = displayName,
                locationLabel = "VOXORA_Exports",
                mimeType = format.mimeType,
                format = format,
                fileSizeBytes = destFile.length()
            )
        }
    }

    private fun decodeAndResampleToStereoFloat(
        pcmBytes: ByteArray,
        byteLength: Int,
        srcSampleRate: Int,
        srcChannels: Int,
        dstSampleRate: Int
    ): Pair<FloatArray, FloatArray> {
        val ch = srcChannels.coerceAtLeast(1)
        val bytesPerFrame = ch * 2
        val srcFrames = byteLength / bytesPerFrame
        if (srcFrames <= 0) return FloatArray(0) to FloatArray(0)

        val srcL = FloatArray(srcFrames)
        val srcR = FloatArray(srcFrames)
        val bb = ByteBuffer.wrap(pcmBytes, 0, srcFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until srcFrames) {
            if (ch == 1) {
                val m = (bb.short.toFloat() / 32768.0f).coerceIn(-1f, 1f)
                srcL[i] = m
                srcR[i] = m
            } else {
                val l = (bb.short.toFloat() / 32768.0f).coerceIn(-1f, 1f)
                val r = (bb.short.toFloat() / 32768.0f).coerceIn(-1f, 1f)
                // Skip extra surround channels if >2ch (5.1/7.1)
                for (extra in 2 until ch) {
                    bb.short
                }
                srcL[i] = l
                srcR[i] = r
            }
        }

        if (srcSampleRate <= 0 || srcSampleRate == dstSampleRate) {
            return srcL to srcR
        }

        val ratio = srcSampleRate.toDouble() / dstSampleRate.toDouble()
        val dstFrames = (srcFrames.toDouble() / ratio).toInt().coerceAtLeast(1)
        val dstL = FloatArray(dstFrames)
        val dstR = FloatArray(dstFrames)
        val lastIdx = srcFrames - 1
        for (i in 0 until dstFrames) {
            val pos = i * ratio
            val idx0 = floor(pos).toInt().coerceIn(0, lastIdx)
            val idx1 = (idx0 + 1).coerceAtMost(lastIdx)
            val frac = (pos - idx0).toFloat()
            dstL[i] = srcL[idx0] + (srcL[idx1] - srcL[idx0]) * frac
            dstR[i] = srcR[idx0] + (srcR[idx1] - srcR[idx0]) * frac
        }
        return dstL to dstR
    }

    private fun skipExactBytes(input: FileInputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() < 0) break
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readUpTo(input: FileInputStream, buffer: ByteArray, targetBytes: Int): Int {
        var total = 0
        while (total < targetBytes) {
            val n = input.read(buffer, total, targetBytes - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(40)
        return cleaned.ifBlank { "voxora_video" }
    }
}
