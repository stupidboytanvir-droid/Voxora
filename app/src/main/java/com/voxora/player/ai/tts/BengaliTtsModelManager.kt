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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Official open-source Sherpa-ONNX VITS Bengali (`vits-coqui-bn-custom_female`) model catalog.
 *
 * Uses direct Bengali Unicode character tokenization (`frontend = characters`), requiring ONLY
 * `model.onnx` + `tokens.txt` with ZERO `espeak-ng-data` dependency and 100% offline synthesis.
 *
 * Verified live endpoints:
 * - Primary direct URLs (Hugging Face): `csukuangfj/vits-coqui-bn-custom_female` (`?download=true`)
 * - Secondary direct mirror (`hf-mirror.com`)
 * - Fallback GitHub Release archive: `k2-fsa/sherpa-onnx` `vits-coqui-bn-custom_female.tar.bz2`
 */
object BengaliTtsModelCatalog {
    const val USER_AGENT = "VOXORA-Media-Player/2.0.1"

    val MODEL_ONNX = BengaliTtsFileSpec(
        slot = BengaliTtsModelSlot.MODEL_ONNX,
        displayName = "VITS Bengali Neural Voice (model.onnx)",
        fileName = BengaliTtsModelSlot.MODEL_ONNX.canonicalFileName,
        downloadUrl = "https://huggingface.co/csukuangfj/vits-coqui-bn-custom_female/resolve/main/model.onnx?download=true",
        fallbackUrls = listOf(
            "https://hf-mirror.com/csukuangfj/vits-coqui-bn-custom_female/resolve/main/model.onnx?download=true"
        ),
        expectedSizeBytes = 114_287_329L,
        minValidSizeBytes = 10_000_000L
    )

    val TOKENS_TXT = BengaliTtsFileSpec(
        slot = BengaliTtsModelSlot.TOKENS_TXT,
        displayName = "Bengali Character Tokens (tokens.txt)",
        fileName = BengaliTtsModelSlot.TOKENS_TXT.canonicalFileName,
        downloadUrl = "https://huggingface.co/csukuangfj/vits-coqui-bn-custom_female/resolve/main/tokens.txt?download=true",
        fallbackUrls = listOf(
            "https://hf-mirror.com/csukuangfj/vits-coqui-bn-custom_female/resolve/main/tokens.txt?download=true"
        ),
        expectedSizeBytes = 585L,
        minValidSizeBytes = 64L
    )

    /**
     * Verified official Sherpa-ONNX GitHub Release `.tar.bz2` archive containing both
     * `model.onnx` and `tokens.txt` used automatically if direct Hugging Face downloads fail.
     */
    const val FALLBACK_ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-coqui-bn-custom_female.tar.bz2"
    const val FALLBACK_ARCHIVE_EXPECTED_BYTES = 108_053_596L

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
        val lower = trimmed.lowercase()

        // Strictly reject HTML / XML / JSON / HTTP error pages while allowing valid Sherpa-ONNX
        // special tokens such as `<PAD> 0`, `<BOS> 2`, `<BLNK> 3`, `<EOS>`, `<UNK>` at line 1.
        val isHtmlOrJsonError = lower.startsWith("<!doctype") ||
            lower.startsWith("<html") ||
            lower.startsWith("<head") ||
            lower.startsWith("<body") ||
            lower.startsWith("<title") ||
            lower.startsWith("<?xml") ||
            lower.startsWith("<error") ||
            trimmed.startsWith("{") ||
            trimmed.startsWith("[") ||
            trimmed.startsWith("HTTP/")

        if (isHtmlOrJsonError) {
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
 * Supports multi-tier in-app download (Hugging Face direct → HF-Mirror direct → Official GitHub
 * Release `.tar.bz2` archive extraction) with HTTP 200 verification, redirect following,
 * real byte progress, local SAF file import, integrity checks, and model deletion.
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
     * real byte progress, HTTP 200 verification, integrity validation, and automatic fallback
     * to the official Sherpa-ONNX GitHub Release `.tar.bz2` archive if direct downloads fail.
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
            var directError: Throwable? = null
            try {
                // 1. Download tokens.txt (tiny ~585B) with primary -> mirror fallback
                val (tokensOk, _) = BengaliTtsIntegrityVerifier.verifyTokensFile(tokensFile)
                if (!tokensOk) {
                    downloadSingleFileWithFallbacks(
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

                // 2. Download VITS model.onnx (~109 MB) with primary -> mirror fallback
                val (onnxOk, _) = BengaliTtsIntegrityVerifier.verifyModelOnnx(onnxFile)
                if (!onnxOk) {
                    downloadSingleFileWithFallbacks(
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                directError = t
            }

            // 3. Fallback to official Sherpa-ONNX GitHub Release .tar.bz2 archive if direct download failed
            if (directError != null) {
                coroutineContext.ensureActive()
                try {
                    downloadAndExtractGitHubArchive(
                        archiveUrl = BengaliTtsModelCatalog.FALLBACK_ARCHIVE_URL,
                        expectedBytes = BengaliTtsModelCatalog.FALLBACK_ARCHIVE_EXPECTED_BYTES,
                        targetModelFile = onnxFile,
                        targetTokensFile = tokensFile
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (archiveError: Throwable) {
                    throw IllegalStateException(
                        "Direct download failed (${directError.message}) and GitHub archive fallback failed (${archiveError.message}).",
                        archiveError
                    )
                }
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

            replaceAtomically(tempFile, targetFile)

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

    private suspend fun downloadSingleFileWithFallbacks(
        spec: BengaliTtsFileSpec,
        targetFile: File,
        validator: (File) -> Pair<Boolean, String?>,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val candidateUrls = listOf(spec.downloadUrl) + spec.fallbackUrls
        var lastError: Throwable? = null
        for (url in candidateUrls) {
            coroutineContext.ensureActive()
            try {
                downloadSingleFileFromUrl(
                    urlString = url,
                    spec = spec,
                    targetFile = targetFile,
                    validator = validator,
                    onBytesProgress = onBytesProgress
                )
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Failed to download ${spec.displayName}.")
    }

    private suspend fun downloadSingleFileFromUrl(
        urlString: String,
        spec: BengaliTtsFileSpec,
        targetFile: File,
        validator: (File) -> Pair<Boolean, String?>,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val tempFile = File(modelsDir, "${spec.fileName}.part")
        if (tempFile.exists()) tempFile.delete()

        val connection = openFollowingRedirects(urlString)
        try {
            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                throw IllegalStateException(
                    "Server returned unexpected $contentType instead of ${spec.fileName}."
                )
            }

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

        replaceAtomically(tempFile, targetFile)
    }

    /**
     * Downloads the official Sherpa-ONNX GitHub Release `.tar.bz2` archive and extracts
     * `model.onnx` and `tokens.txt` cleanly into `filesDir/models/tts-bengali/`.
     */
    private suspend fun downloadAndExtractGitHubArchive(
        archiveUrl: String,
        expectedBytes: Long,
        targetModelFile: File,
        targetTokensFile: File
    ) {
        val archiveFile = File(modelsDir, "vits_bengali_pack.tar.bz2.part")
        val tempModelFile = File(modelsDir, "${targetModelFile.name}.extract.part")
        val tempTokensFile = File(modelsDir, "${targetTokensFile.name}.extract.part")
        if (archiveFile.exists()) archiveFile.delete()
        if (tempModelFile.exists()) tempModelFile.delete()
        if (tempTokensFile.exists()) tempTokensFile.delete()

        try {
            _modelState.value = BengaliTtsModelState.Downloading(
                progress = 2,
                stageLabel = "Switching to official GitHub Release mirror..."
            )
            val connection = openFollowingRedirects(archiveUrl)
            try {
                val contentType = connection.contentType.orEmpty().lowercase()
                if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                    throw IllegalStateException("GitHub fallback returned $contentType instead of model archive.")
                }
                val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: expectedBytes
                var downloaded = 0L
                var lastReportMs = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(archiveFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= 150L || downloaded >= contentLength) {
                                lastReportMs = now
                                val pct = (2 + ((downloaded.toDouble() / contentLength.toDouble()) * 80.0).toInt())
                                    .coerceIn(2, 82)
                                _modelState.value = BengaliTtsModelState.Downloading(
                                    progress = pct,
                                    stageLabel = "Downloading GitHub Bengali VITS archive..."
                                )
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                connection.disconnect()
            }

            coroutineContext.ensureActive()
            _modelState.value = BengaliTtsModelState.Downloading(
                progress = 85,
                stageLabel = "Extracting model.onnx & tokens.txt from archive..."
            )

            var extractedModel = false
            var extractedTokens = false

            BufferedInputStream(FileInputStream(archiveFile), 64 * 1024).use { fileIn ->
                BZip2CompressorInputStream(fileIn).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val entry = tarIn.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val baseName = entry.name.substringAfterLast('/')
                            val destFile = when (baseName) {
                                "model.onnx" -> tempModelFile
                                "tokens.txt" -> tempTokensFile
                                else -> null
                            }
                            if (destFile != null) {
                                FileOutputStream(destFile).use { out ->
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val read = tarIn.read(buffer)
                                        if (read <= 0) break
                                        out.write(buffer, 0, read)
                                    }
                                    out.flush()
                                }
                                if (baseName == "model.onnx") extractedModel = true
                                if (baseName == "tokens.txt") extractedTokens = true
                                _modelState.value = BengaliTtsModelState.Downloading(
                                    progress = if (extractedModel && extractedTokens) 96 else 90,
                                    stageLabel = "Extracted $baseName..."
                                )
                            }
                        }
                    }
                }
            }

            if (!extractedModel || !extractedTokens) {
                throw IllegalStateException("Official archive did not contain both model.onnx and tokens.txt.")
            }

            val (tokensValid, tokensReason) = BengaliTtsIntegrityVerifier.verifyTokensFile(tempTokensFile)
            if (!tokensValid) {
                throw IllegalStateException(tokensReason ?: "Extracted tokens.txt failed validation.")
            }
            val (modelValid, modelReason) = BengaliTtsIntegrityVerifier.verifyModelOnnx(tempModelFile)
            if (!modelValid) {
                throw IllegalStateException(modelReason ?: "Extracted model.onnx failed validation.")
            }

            replaceAtomically(tempTokensFile, targetTokensFile)
            replaceAtomically(tempModelFile, targetModelFile)
        } finally {
            runCatching { if (archiveFile.exists()) archiveFile.delete() }
            runCatching { if (tempModelFile.exists()) tempModelFile.delete() }
            runCatching { if (tempTokensFile.exists()) tempTokensFile.delete() }
        }
    }

    private fun replaceAtomically(tempFile: File, targetFile: File) {
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    /**
     * Opens [urlString] and explicitly follows HTTP 301/302/303/307/308 redirects across hosts
     * (Android's built-in `instanceFollowRedirects` does not follow 307/308 or cross-scheme hops),
     * verifying HTTP 200 OK before returning the live stream connection.
     */
    private fun openFollowingRedirects(urlString: String, maxRedirects: Int = 10): HttpURLConnection {
        var currentUrl = urlString
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", BengaliTtsModelCatalog.USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect location missing (HTTP $code)")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else if (code == HttpURLConnection.HTTP_OK) {
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("Bengali TTS model download failed with HTTP $code")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading Bengali TTS model.")
    }
}
