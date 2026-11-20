package com.voxora.player.ai.diarization

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Official open-source Sherpa-ONNX diarization models downloaded on-demand to private app storage.
 * Zero API keys, zero cloud calls, 100% offline once downloaded or imported.
 */
object DiarizationModelCatalog {
    val SEGMENTATION = DiarizationModelSpec(
        slot = DiarizationModelSlot.SEGMENTATION,
        displayName = "Pyannote Segmentation 3.0",
        fileName = DiarizationModelSlot.SEGMENTATION.canonicalFileName,
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx",
        expectedSizeBytes = 5_992_913L,
        minValidSizeBytes = 500_000L
    )

    val EMBEDDING = DiarizationModelSpec(
        slot = DiarizationModelSlot.EMBEDDING,
        displayName = "3D-Speaker ERes2Net (EN 16kHz)",
        fileName = DiarizationModelSlot.EMBEDDING.canonicalFileName,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
        expectedSizeBytes = 26_485_264L,
        minValidSizeBytes = 5_000_000L
    )

    val totalExpectedBytes: Long
        get() = SEGMENTATION.expectedSizeBytes + EMBEDDING.expectedSizeBytes
}

/**
 * Pure ONNX binary integrity verifier that rejects missing, truncated, or HTML/JSON error files.
 */
object OnnxModelIntegrityVerifier {

    fun verifyOnnxFile(file: File, minBytes: Long): Pair<Boolean, String?> {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return false to "Model file is missing or unreadable."
        }
        val length = file.length()
        if (length < minBytes) {
            return false to "File is too small (${length / 1024} KB) to be a valid ONNX diarization model."
        }
        val header = ByteArray(16)
        val bytesRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)
        if (bytesRead < 4) {
            return false to "Unable to read ONNX model header."
        }

        // Reject accidental HTML / JSON / plain-text downloads
        val headerAscii = String(header, 0, bytesRead, Charsets.US_ASCII).trimStart()
        if (headerAscii.startsWith("<") || headerAscii.startsWith("{") || headerAscii.startsWith("HTTP/")) {
            return false to "Downloaded file is not a valid ONNX binary (received text/HTML response)."
        }

        // Valid ONNX ModelProto protobuf begins with varint (wire type 0) or length-delimited (wire type 2) tag
        val wireType = header[0].toInt() and 0x07
        if (wireType != 0 && wireType != 2) {
            return false to "Invalid ONNX protobuf header. Please select a genuine Sherpa-ONNX .onnx model file."
        }

        return true to null
    }
}

/**
 * Manages the two offline Sherpa-ONNX diarization models inside app-specific storage
 * (`filesDir/models/diarization/`):
 * 1. Segmentation model (`pyannote-segmentation-3-0.onnx`, ~5.7 MB)
 * 2. Speaker embedding model (`3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx`, ~25.3 MB)
 */
class DiarizationModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models/diarization").apply { mkdirs() }

    private val _modelState = MutableStateFlow<DiarizationModelState>(DiarizationModelState.NotInstalled)
    val modelState: StateFlow<DiarizationModelState> = _modelState.asStateFlow()

    private val _slotAvailability = MutableStateFlow(DiarizationSlotAvailability())
    val slotAvailability: StateFlow<DiarizationSlotAvailability> = _slotAvailability.asStateFlow()

    private val _installedFiles = MutableStateFlow<DiarizationModelFiles?>(null)
    val installedFiles: StateFlow<DiarizationModelFiles?> = _installedFiles.asStateFlow()

    fun getSegmentationFile(): File = File(modelsDir, DiarizationModelCatalog.SEGMENTATION.fileName)

    fun getEmbeddingFile(): File = File(modelsDir, DiarizationModelCatalog.EMBEDDING.fileName)

    /**
     * Synchronously returns verified paths of both required models, or null if either is missing/corrupt.
     */
    fun getInstalledModelPaths(): DiarizationModelFiles? {
        val segFile = getSegmentationFile()
        val embFile = getEmbeddingFile()
        val (segOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(
            segFile,
            DiarizationModelCatalog.SEGMENTATION.minValidSizeBytes
        )
        val (embOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(
            embFile,
            DiarizationModelCatalog.EMBEDDING.minValidSizeBytes
        )
        if (!segOk || !embOk) return null
        return DiarizationModelFiles(
            segmentationModelPath = segFile.absolutePath,
            embeddingModelPath = embFile.absolutePath,
            segmentationSizeBytes = segFile.length(),
            embeddingSizeBytes = embFile.length()
        )
    }

    /**
     * Inspects `filesDir/models/diarization/` on [Dispatchers.IO] and updates [modelState].
     */
    suspend fun checkModelStatus(): DiarizationModelState = withContext(Dispatchers.IO) {
        val segFile = getSegmentationFile()
        val embFile = getEmbeddingFile()
        val (segOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(
            segFile,
            DiarizationModelCatalog.SEGMENTATION.minValidSizeBytes
        )
        val (embOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(
            embFile,
            DiarizationModelCatalog.EMBEDDING.minValidSizeBytes
        )

        _slotAvailability.value = DiarizationSlotAvailability(
            hasSegmentationModel = segOk,
            segmentationSizeBytes = if (segOk) segFile.length() else 0L,
            hasEmbeddingModel = embOk,
            embeddingSizeBytes = if (embOk) embFile.length() else 0L
        )

        if (segOk && embOk) {
            _installedFiles.value = DiarizationModelFiles(
                segmentationModelPath = segFile.absolutePath,
                embeddingModelPath = embFile.absolutePath,
                segmentationSizeBytes = segFile.length(),
                embeddingSizeBytes = embFile.length()
            )
            _modelState.value = DiarizationModelState.Ready
        } else {
            _installedFiles.value = null
            _modelState.value = DiarizationModelState.NotInstalled
        }
        _modelState.value
    }

    /**
     * Downloads both required offline ONNX models (~31 MB total) into `filesDir/models/diarization/`
     * with real byte progress and ONNX binary integrity verification.
     */
    suspend fun downloadModels(): Result<DiarizationModelFiles> = withContext(Dispatchers.IO) {
        _modelState.value = DiarizationModelState.Downloading(
            progress = 0,
            stageLabel = "Preparing offline Sherpa-ONNX model download..."
        )

        val segSpec = DiarizationModelCatalog.SEGMENTATION
        val embSpec = DiarizationModelCatalog.EMBEDDING
        val totalExpected = DiarizationModelCatalog.totalExpectedBytes

        val segFile = getSegmentationFile()
        val embFile = getEmbeddingFile()

        try {
            var completedBaseBytes = 0L

            // 1. Download segmentation model if missing or invalid
            val (segAlreadyOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(segFile, segSpec.minValidSizeBytes)
            if (!segAlreadyOk) {
                downloadSingleModel(
                    spec = segSpec,
                    targetFile = segFile,
                    onBytesProgress = { downloaded, _ ->
                        val overall = (((completedBaseBytes + downloaded).toDouble() / totalExpected.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 99)
                        _modelState.value = DiarizationModelState.Downloading(
                            progress = overall,
                            stageLabel = "Downloading ${segSpec.displayName} (1/2)..."
                        )
                    }
                )
            }
            completedBaseBytes = segFile.length().coerceAtLeast(segSpec.expectedSizeBytes)

            // 2. Download speaker embedding model if missing or invalid
            val (embAlreadyOk, _) = OnnxModelIntegrityVerifier.verifyOnnxFile(embFile, embSpec.minValidSizeBytes)
            if (!embAlreadyOk) {
                downloadSingleModel(
                    spec = embSpec,
                    targetFile = embFile,
                    onBytesProgress = { downloaded, specTotal ->
                        val denom = (completedBaseBytes + specTotal).coerceAtLeast(totalExpected)
                        val overall = (((completedBaseBytes + downloaded).toDouble() / denom.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(1, 99)
                        _modelState.value = DiarizationModelState.Downloading(
                            progress = overall,
                            stageLabel = "Downloading ${embSpec.displayName} (2/2)..."
                        )
                    }
                )
            }

            checkModelStatus()
            val files = getInstalledModelPaths()
                ?: throw IllegalStateException("Diarization models failed verification after download.")
            Result.success(files)
        } catch (ce: CancellationException) {
            checkModelStatus()
            throw ce
        } catch (t: Throwable) {
            val message = t.message ?: "Failed to download offline Sherpa-ONNX diarization models."
            _modelState.value = DiarizationModelState.Error(message)
            Result.failure(t)
        }
    }

    /**
     * Imports a user-selected local `.onnx` file into the requested diarization slot
     * (`SEGMENTATION` or `EMBEDDING`) and validates ONNX integrity.
     */
    suspend fun importLocalModel(
        slot: DiarizationModelSlot,
        sourceUri: Uri,
        displayName: String? = null
    ): Result<DiarizationSlotAvailability> = withContext(Dispatchers.IO) {
        val spec = when (slot) {
            DiarizationModelSlot.SEGMENTATION -> DiarizationModelCatalog.SEGMENTATION
            DiarizationModelSlot.EMBEDDING -> DiarizationModelCatalog.EMBEDDING
        }
        val targetFile = File(modelsDir, spec.fileName)
        val tempFile = File(modelsDir, "${spec.fileName}.import.part")

        try {
            if (tempFile.exists()) tempFile.delete()
            _modelState.value = DiarizationModelState.Downloading(
                progress = 15,
                stageLabel = "Importing ${displayName ?: slot.displayName}..."
            )

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Unable to open the selected ONNX model file.")

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReportMs = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 100L) {
                            lastReportMs = now
                            val pct = ((copied.toDouble() / spec.expectedSizeBytes.toDouble()) * 90.0)
                                .toInt()
                                .coerceIn(10, 95)
                            _modelState.value = DiarizationModelState.Downloading(
                                progress = pct,
                                stageLabel = "Importing ${slot.displayName}..."
                            )
                        }
                    }
                    output.flush()
                }
            }

            val (valid, reason) = OnnxModelIntegrityVerifier.verifyOnnxFile(tempFile, spec.minValidSizeBytes)
            if (!valid) {
                tempFile.delete()
                throw IllegalStateException(reason ?: "Selected file is not a valid ONNX model.")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            checkModelStatus()
            Result.success(_slotAvailability.value)
        } catch (ce: CancellationException) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            checkModelStatus()
            throw ce
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            _modelState.value = DiarizationModelState.Error(
                t.message ?: "Failed to import local ONNX diarization model."
            )
            Result.failure(t)
        }
    }

    /**
     * Deletes all downloaded/imported diarization ONNX models from app storage to free space.
     */
    suspend fun deleteModels() = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
        _installedFiles.value = null
        _slotAvailability.value = DiarizationSlotAvailability()
        _modelState.value = DiarizationModelState.NotInstalled
    }

    private suspend fun downloadSingleModel(
        spec: DiarizationModelSpec,
        targetFile: File,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val tempFile = File(modelsDir, "${spec.fileName}.part")
        if (tempFile.exists()) tempFile.delete()

        val connection = openFollowingRedirects(spec.downloadUrl)
        try {
            val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: spec.expectedSizeBytes
            var downloaded = 0L
            var lastReportMs = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 120L || downloaded >= contentLength) {
                            lastReportMs = now
                            onBytesProgress(downloaded, contentLength)
                        }
                    }
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            throw t
        } finally {
            connection.disconnect()
        }

        coroutineContext.ensureActive()
        val (valid, reason) = OnnxModelIntegrityVerifier.verifyOnnxFile(tempFile, spec.minValidSizeBytes)
        if (!valid) {
            runCatching { tempFile.delete() }
            throw IllegalStateException(reason ?: "Downloaded ${spec.displayName} failed ONNX integrity check.")
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun openFollowingRedirects(urlString: String, maxRedirects: Int = 6): HttpURLConnection {
        var currentUrl = urlString
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "VOXORA-Android/1.5")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect location missing (HTTP $code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("ONNX model download failed with HTTP $code")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading ONNX model.")
    }
}
