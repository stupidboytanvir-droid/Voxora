package com.voxora.player.ai.tts

import android.content.Context
import android.net.Uri
import com.voxora.player.ai.diarization.OnnxModelIntegrityVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Official open-source Sherpa-ONNX VITS Bengali (`vits-coqui-bn-custom_female`) model catalog.
 *
 * Uses direct Bengali Unicode character tokenization (`frontend = characters`), requiring ONLY
 * `model.onnx` + `tokens.txt` with ZERO `espeak-ng-data` dependency and 100% offline synthesis.
 */
object BengaliTtsModelCatalog {
    val MODEL_ONNX = BengaliTtsFileSpec(
        slot = BengaliTtsModelSlot.MODEL_ONNX,
        displayName = "VITS Bengali Neural Voice (model.onnx)",
        fileName = BengaliTtsModelSlot.MODEL_ONNX.canonicalFileName,
        downloadUrl = "https://huggingface.co/csukuangfj/vits-coqui-bn-custom_female/resolve/main/model.onnx",
        expectedSizeBytes = 114_287_329L,
        minValidSizeBytes = 10_000_000L
    )

    val TOKENS_TXT = BengaliTtsFileSpec(
        slot = BengaliTtsModelSlot.TOKENS_TXT,
        displayName = "Bengali Character Tokens (tokens.txt)",
        fileName = BengaliTtsModelSlot.TOKENS_TXT.canonicalFileName,
        downloadUrl = "https://huggingface.co/csukuangfj/vits-coqui-bn-custom_female/resolve/main/tokens.txt",
        expectedSizeBytes = 585L,
        minValidSizeBytes = 64L
    )

    val totalExpectedBytes: Long
        get() = MODEL_ONNX.expectedSizeBytes + TOKENS_TXT.expectedSizeBytes
}

/**
 * Pure integrity verification for the offline Sherpa-ONNX VITS Bengali model pack.
 */
object BengaliTtsIntegrityVerifier {

    fun verifyModelOnnx(file: File, minBytes: Long = BengaliTtsModelCatalog.MODEL_ONNX.minValidSizeBytes): Pair<Boolean, String?> {
        return OnnxModelIntegrityVerifier.verifyOnnxFile(file, minBytes)
    }

    fun verifyTokensFile(file: File, minBytes: Long = BengaliTtsModelCatalog.TOKENS_TXT.minValidSizeBytes): Pair<Boolean, String?> {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return false to "Bengali tokens.txt file is missing or unreadable."
        }
        val length = file.length()
        if (length < minBytes) {
            return false to "tokens.txt is too small ($length bytes) to be a valid Sherpa-ONNX token table."
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return false to "Unable to read tokens.txt as UTF-8."
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<") || trimmed.startsWith("{") || trimmed.startsWith("HTTP/")) {
            return false to "Downloaded tokens.txt is an HTML/JSON error page instead of a token table."
        }
        val validLines = text.lineSequence().count { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.size >= 2 && parts.last().toIntOrNull() != null
        }
        if (validLines < 10) {
            return false to "tokens.txt does not contain valid Sherpa-ONNX `<token> <id>` entries."
        }
        return true to null
    }
}

/**
 * Manages the offline Sherpa-ONNX VITS Bengali TTS model pack inside private app storage
 * (`filesDir/models/tts-bengali/`):
 * - `model.onnx` (~109 MB)
 * - `tokens.txt` (~585 B)
 *
 * Supports in-app download with real byte progress, local SAF file import, integrity checks,
 * and model deletion to reclaim storage.
 */
class BengaliTtsModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models/tts-bengali").apply { mkdirs() }

    private val _modelState = MutableStateFlow<BengaliTtsModelState>(BengaliTtsModelState.NotInstalled)
    val modelState: StateFlow<BengaliTtsModelState> = _modelState.asStateFlow()

    private val _slotAvailability = MutableStateFlow(BengaliTtsSlotAvailability())
    val slotAvailability: StateFlow<BengaliTtsSlotAvailability> = _slotAvailability.asStateFlow()

    private val _installedFiles = MutableStateFlow<BengaliTtsModelFiles?>(null)
    val installedFiles: StateFlow<BengaliTtsModelFiles?> = _installedFiles.asStateFlow()

    fun getModelOnnxFile(): File = File(modelsDir, BengaliTtsModelCatalog.MODEL_ONNX.fileName)

    fun getTokensTxtFile(): File = File(modelsDir, BengaliTtsModelCatalog.TOKENS_TXT.fileName)

    /**
     * Synchronously returns verified local file paths if BOTH `model.onnx` and `tokens.txt`
     * pass integrity validation, or `null` otherwise.
     */
    fun getInstalledModelFiles(): BengaliTtsModelFiles? {
        val modelFile = getModelOnnxFile()
        val tokensFile = getTokensTxtFile()
        val (onnxOk, _) = BengaliTtsIntegrityVerifier.verifyModelOnnx(modelFile)
        val (tokensOk, _) = BengaliTtsIntegrityVerifier.verifyTokensFile(tokensFile)
        if (!onnxOk || !tokensOk) return null
        return BengaliTtsModelFiles(
            modelDirPath = modelsDir.absolutePath,
            modelOnnxPath = modelFile.absolutePath,
            tokensTxtPath = tokensFile.absolutePath,
            modelSizeBytes = modelFile.length(),
            tokensSizeBytes = tokensFile.length()
        )
    }

    /**
     * Checks `filesDir/models/tts-bengali/` on [Dispatchers.IO] and refreshes [modelState].
     */
    suspend fun checkModelStatus(): BengaliTtsModelState = withContext(Dispatchers.IO) {
        val modelFile = getModelOnnxFile()
        val tokensFile = getTokensTxtFile()
        val (onnxOk, _) = BengaliTtsIntegrityVerifier.verifyModelOnnx(modelFile)
        val (tokensOk, _) = BengaliTtsIntegrityVerifier.verifyTokensFile(tokensFile)

        _slotAvailability.value = BengaliTtsSlotAvailability(
            hasModelOnnx = onnxOk,
            modelSizeBytes = if (onnxOk) modelFile.length() else 0L,
            hasTokensTxt = tokensOk,
            tokensSizeBytes = if (tokensOk) tokensFile.length() else 0L
        )

        if (onnxOk && tokensOk) {
            _installedFiles.value = BengaliTtsModelFiles(
                modelDirPath = modelsDir.absolutePath,
                modelOnnxPath = modelFile.absolutePath,
                tokensTxtPath = tokensFile.absolutePath,
                modelSizeBytes = modelFile.length(),
                tokensSizeBytes = tokensFile.length()
            )
            _modelState.value = BengaliTtsModelState.Ready
        } else {
            _installedFiles.value = null
            _modelState.value = BengaliTtsModelState.NotInstalled
        }
        _modelState.value
    }

    /**
     * Downloads both `tokens.txt` and `model.onnx` into `filesDir/models/tts-bengali/` with
     * real byte progress and integrity validation.
     */
    suspend fun downloadModels(): Result<BengaliTtsModelFiles> = withContext(Dispatchers.IO) {
        _modelState.value = BengaliTtsModelState.Downloading(
            progress = 0,
            stageLabel = "Preparing offline VITS Bengali voice download..."
        )

        val tokensSpec = BengaliTtsModelCatalog.TOKENS_TXT
        val onnxSpec = BengaliTtsModelCatalog.MODEL_ONNX
        val tokensFile = getTokensTxtFile()
        val onnxFile = getModelOnnxFile()

        try {
            // 1. Download tokens.txt (tiny ~585B)
            val (tokensOk, _) = BengaliTtsIntegrityVerifier.verifyTokensFile(tokensFile)
            if (!tokensOk) {
                downloadSingleFile(
                    spec = tokensSpec,
                    targetFile = tokensFile,
                    validator = { BengaliTtsIntegrityVerifier.verifyTokensFile(it) },
                    onBytesProgress = { _, _ ->
                        _modelState.value = BengaliTtsModelState.Downloading(
                            progress = 1,
                            stageLabel = "Downloading Bengali tokens (1/2)..."
                        )
                    }
                )
            }

            // 2. Download VITS model.onnx (~109 MB)
            val (onnxOk, _) = BengaliTtsIntegrityVerifier.verifyModelOnnx(onnxFile)
            if (!onnxOk) {
                downloadSingleFile(
                    spec = onnxSpec,
                    targetFile = onnxFile,
                    validator = { BengaliTtsIntegrityVerifier.verifyModelOnnx(it) },
                    onBytesProgress = { downloaded, total ->
                        val denom = total.coerceAtLeast(onnxSpec.expectedSizeBytes)
                        val pct = (1 + ((downloaded.toDouble() / denom.toDouble()) * 98.0).toInt()).coerceIn(1, 99)
                        _modelState.value = BengaliTtsModelState.Downloading(
                            progress = pct,
                            stageLabel = "Downloading VITS Bengali voice model (2/2)..."
                        )
                    }
                )
            }

            checkModelStatus()
            val verified = getInstalledModelFiles()
                ?: throw IllegalStateException("Bengali TTS model pack failed verification after download.")
            Result.success(verified)
        } catch (ce: CancellationException) {
            checkModelStatus()
            throw ce
        } catch (t: Throwable) {
            val msg = t.message ?: "Failed to download offline VITS Bengali TTS model."
            _modelState.value = BengaliTtsModelState.Error(msg)
            Result.failure(t)
        }
    }

    /**
     * Imports a user-selected local `model.onnx` or `tokens.txt` via SAF into `filesDir/models/tts-bengali/`.
     */
    suspend fun importLocalFile(
        slot: BengaliTtsModelSlot,
        sourceUri: Uri,
        displayName: String? = null
    ): Result<BengaliTtsSlotAvailability> = withContext(Dispatchers.IO) {
        val spec = when (slot) {
            BengaliTtsModelSlot.MODEL_ONNX -> BengaliTtsModelCatalog.MODEL_ONNX
            BengaliTtsModelSlot.TOKENS_TXT -> BengaliTtsModelCatalog.TOKENS_TXT
        }
        val targetFile = File(modelsDir, spec.fileName)
        val tempFile = File(modelsDir, "${spec.fileName}.import.part")

        try {
            if (tempFile.exists()) tempFile.delete()
            _modelState.value = BengaliTtsModelState.Downloading(
                progress = 10,
                stageLabel = "Importing ${displayName ?: slot.displayName}..."
            )

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Unable to open selected Bengali TTS file.")

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
                            _modelState.value = BengaliTtsModelState.Downloading(
                                progress = pct,
                                stageLabel = "Importing ${slot.displayName}..."
                            )
                        }
                    }
                    output.flush()
                }
            }

            val (valid, reason) = when (slot) {
                BengaliTtsModelSlot.MODEL_ONNX -> BengaliTtsIntegrityVerifier.verifyModelOnnx(tempFile)
                BengaliTtsModelSlot.TOKENS_TXT -> BengaliTtsIntegrityVerifier.verifyTokensFile(tempFile)
            }
            if (!valid) {
                tempFile.delete()
                throw IllegalStateException(reason ?: "Selected file failed Bengali TTS validation.")
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
            _modelState.value = BengaliTtsModelState.Error(
                t.message ?: "Failed to import local Bengali TTS file."
            )
            Result.failure(t)
        }
    }

    /**
     * Removes all offline Bengali TTS model files from private app storage to free space.
     */
    suspend fun deleteModels() = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
        _installedFiles.value = null
        _slotAvailability.value = BengaliTtsSlotAvailability()
        _modelState.value = BengaliTtsModelState.NotInstalled
    }

    private suspend fun downloadSingleFile(
        spec: BengaliTtsFileSpec,
        targetFile: File,
        validator: (File) -> Pair<Boolean, String?>,
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
        val (valid, reason) = validator(tempFile)
        if (!valid) {
            runCatching { tempFile.delete() }
            throw IllegalStateException(reason ?: "Downloaded ${spec.displayName} failed integrity check.")
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
                readTimeout = 45_000
                setRequestProperty("User-Agent", "VOXORA-Android/1.6")
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
                throw IllegalStateException("Bengali TTS model download failed with HTTP $code")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading Bengali TTS model.")
    }
}
