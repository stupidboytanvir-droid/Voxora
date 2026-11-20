package com.voxora.player.dubbing.whisper

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class WhisperModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String?,
    val languageLabel: String
)

data class InstalledWhisperModel(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val matchesOfficialChecksum: Boolean,
    val headerFormat: String
)

data class ModelIntegrityResult(
    val isValid: Boolean,
    val sizeBytes: Long,
    val headerFormat: String,
    val sha256: String,
    val matchesExpectedChecksum: Boolean,
    val failureReason: String? = null
)

sealed interface WhisperModelStatus {
    data object NotInstalled : WhisperModelStatus
    data class Downloading(
        val progressFraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : WhisperModelStatus

    data class Importing(
        val progressFraction: Float,
        val copiedBytes: Long
    ) : WhisperModelStatus

    data class Verifying(val message: String) : WhisperModelStatus
    data class InstalledUnloaded(val model: InstalledWhisperModel) : WhisperModelStatus
    data class Loading(val model: InstalledWhisperModel) : WhisperModelStatus
    data class LoadedReady(
        val model: InstalledWhisperModel,
        val nativeSystemInfo: String
    ) : WhisperModelStatus

    data class Error(
        val message: String,
        val installedModel: InstalledWhisperModel? = null
    ) : WhisperModelStatus
}

object WhisperModelCatalog {
    /**
     * Fast quantized Whisper Tiny English (`ggml-tiny.en-q5_1.bin`, ~31 MB) from whisper.cpp.
     * ~5x faster on mobile ARM64 CPUs with ~35 MB RAM footprint for real-time 30s movie chunk dubbing.
     */
    val TINY_EN_Q5_1 = WhisperModelSpec(
        id = "tiny.en-q5_1",
        displayName = "Whisper Tiny Fast English (tiny.en-q5_1 · 5x Faster)",
        fileName = "ggml-tiny.en-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
        expectedSizeBytes = 32_166_155L,
        expectedSha256 = null,
        languageLabel = "English (Fast Offline)"
    )

    /**
     * Legacy Whisper Base English (`base.en`, ~141 MB) retained for backward compatibility with
     * existing user installations or manual imports.
     */
    val BASE_EN = WhisperModelSpec(
        id = "base.en",
        displayName = "Whisper Base English (base.en)",
        fileName = "ggml-base.en.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
        expectedSizeBytes = 147_951_465L,
        expectedSha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
        languageLabel = "English (Offline)"
    )

    val DEFAULT: WhisperModelSpec = TINY_EN_Q5_1

    fun matchingOfficialSpec(fileName: String): WhisperModelSpec? = when {
        fileName.equals(TINY_EN_Q5_1.fileName, ignoreCase = true) -> TINY_EN_Q5_1
        fileName.equals(BASE_EN.fileName, ignoreCase = true) -> BASE_EN
        else -> null
    }
}

/**
 * Pure verifier for Whisper GGML/GGUF model binaries.
 * Rejects truncated files, HTML error downloads, or corrupted binaries before passing to native C++.
 */
object WhisperModelIntegrityVerifier {

    // Minimum valid size for any real Whisper model (> 25 MB; quantized tiny.en-q5_1 is ~31 MB)
    const val MIN_REAL_MODEL_BYTES: Long = 25L * 1024L * 1024L

    /**
     * Checks non-HTML/JSON content, > 25 MB size, 4-byte GGML magic header, and computes SHA-256 digest.
     */
    fun verifyFile(
        file: File,
        expectedSha256: String? = null,
        minBytes: Long = MIN_REAL_MODEL_BYTES
    ): ModelIntegrityResult {
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = 0L,
                headerFormat = "missing",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "Model file does not exist or cannot be read."
            )
        }

        val size = file.length()
        val headSample = ByteArray(64)
        val headRead = FileInputStream(file).use { fis -> fis.read(headSample) }
        if (headRead < 4) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "unreadable",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "Unable to read model binary header."
            )
        }

        if (looksLikeHtmlOrJsonError(headSample, headRead)) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "html_or_json",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "Downloaded file is an HTML/JSON error page instead of a valid Whisper GGML model."
            )
        }

        if (size <= minBytes) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "too_small",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "File is too small (${size / (1024 * 1024)} MB; must be > ${minBytes / (1024 * 1024)} MB) to be a valid Whisper GGML model."
            )
        }

        val headerFormat = detectGgmlMagic(headSample)
        if (headerFormat == null) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "invalid",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "Invalid model header. Expected a genuine whisper.cpp GGML binary (ggml/lmgg/ggjt/ggmf)."
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val checksumMatches = expectedSha256 == null || sha256.equals(expectedSha256, ignoreCase = true)

        if (expectedSha256 != null && !checksumMatches) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = headerFormat,
                sha256 = sha256,
                matchesExpectedChecksum = false,
                failureReason = "SHA-256 integrity check failed for ${file.name}. Download may be corrupted."
            )
        }

        return ModelIntegrityResult(
            isValid = true,
            sizeBytes = size,
            headerFormat = headerFormat,
            sha256 = sha256,
            matchesExpectedChecksum = checksumMatches
        )
    }

    /**
     * Official whisper.cpp models start with little-endian `0x67676d6c` (`"lmgg"`),
     * big-endian `"ggml"`, `"ggmf"`, `"ggjt"`, or `"GGUF"`.
     */
    fun detectGgmlMagic(header: ByteArray): String? {
        if (header.size < 4) return null
        val ascii = String(header, 0, 4, Charsets.US_ASCII)
        return when (ascii) {
            "lmgg" -> "GGML (little-endian)"
            "ggml" -> "GGML"
            "fmgg", "ggmf" -> "GGMF"
            "tjgg", "ggjt" -> "GGJT"
            "GGUF" -> "GGUF"
            else -> null
        }
    }

    fun looksLikeHtmlOrJsonError(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val count = length.coerceAtMost(bytes.size)
        if (count <= 0) return false
        val preview = String(bytes, 0, count, Charsets.UTF_8).trimStart().lowercase()
        return preview.startsWith("<!doctype") ||
            preview.startsWith("<html") ||
            preview.startsWith("<?xml") ||
            preview.startsWith("<head") ||
            preview.startsWith("<body") ||
            preview.startsWith("{") ||
            preview.startsWith("[")
    }
}

/**
 * Manages app-specific offline storage, HTTP download, SAF local import, and integrity
 * verification for Whisper models (`ggml-base.en.bin`).
 */
class WhisperModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "whisper_models").apply { mkdirs() }

    fun getDefaultModelFile(spec: WhisperModelSpec = WhisperModelCatalog.DEFAULT): File {
        return File(modelsDir, spec.fileName)
    }

    /**
     * Fast disk-only check verifying whether a valid Whisper GGML `.bin` model file exists in
     * private app storage (`filesDir/whisper_models/`), completely independent of whether the
     * native C++ context is currently loaded in RAM.
     */
    fun hasInstalledModelOnDisk(): Boolean {
        val candidates = modelsDir.listFiles()
            ?.filter { it.isFile && it.canRead() && it.name.endsWith(".bin", ignoreCase = true) }
            .orEmpty()
        for (file in candidates) {
            if (file.length() <= WhisperModelIntegrityVerifier.MIN_REAL_MODEL_BYTES) continue
            val header = ByteArray(4)
            val read = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(0)
            if (read == 4 && WhisperModelIntegrityVerifier.detectGgmlMagic(header) != null) {
                return true
            }
        }
        return false
    }

    /**
     * Scans app-specific storage for an already installed Whisper `.bin` model and verifies its header.
     * Prefers the fast quantized `ggml-tiny.en-q5_1.bin` when present, and also accepts existing
     * `ggml-base.en.bin` or user-imported GGML models without forcing re-download.
     */
    suspend fun findInstalledModel(
        spec: WhisperModelSpec = WhisperModelCatalog.DEFAULT
    ): InstalledWhisperModel? = withContext(Dispatchers.IO) {
        val preferred = getDefaultModelFile(spec)
        val candidate = when {
            preferred.exists() && preferred.isFile -> preferred
            else -> modelsDir.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
        } ?: return@withContext null

        val matchedSpec = WhisperModelCatalog.matchingOfficialSpec(candidate.name)
        val verification = WhisperModelIntegrityVerifier.verifyFile(
            file = candidate,
            expectedSha256 = matchedSpec?.expectedSha256
        )
        if (!verification.isValid) return@withContext null

        InstalledWhisperModel(
            fileName = candidate.name,
            absolutePath = candidate.absolutePath,
            sizeBytes = verification.sizeBytes,
            sha256 = verification.sha256,
            matchesOfficialChecksum = verification.matchesExpectedChecksum && matchedSpec != null,
            headerFormat = verification.headerFormat
        )
    }

    /**
     * Downloads the official open-source `ggml-tiny.en-q5_1.bin` (~31 MB) model into app-specific
     * storage, cleaning up any stale `.part` files first, verifying HTTP 200 non-HTML/JSON content,
     * > 25 MB size, and valid GGML binary magic header before committing.
     */
    suspend fun downloadModel(
        spec: WhisperModelSpec = WhisperModelCatalog.DEFAULT,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit
    ): Result<InstalledWhisperModel> = withContext(Dispatchers.IO) {
        val targetFile = getDefaultModelFile(spec)
        val tempFile = File(modelsDir, "${spec.fileName}.part")

        runCatching {
            cleanupLeftoverPartFiles()

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
                                onProgress(downloaded, contentLength)
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                connection.disconnect()
            }

            coroutineContext.ensureActive()
            onVerifying()

            val verification = WhisperModelIntegrityVerifier.verifyFile(
                file = tempFile,
                expectedSha256 = spec.expectedSha256
            )
            if (!verification.isValid) {
                tempFile.delete()
                throw IllegalStateException(
                    verification.failureReason ?: "Downloaded model failed integrity check."
                )
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            InstalledWhisperModel(
                fileName = targetFile.name,
                absolutePath = targetFile.absolutePath,
                sizeBytes = verification.sizeBytes,
                sha256 = verification.sha256,
                matchesOfficialChecksum = verification.matchesExpectedChecksum,
                headerFormat = verification.headerFormat
            )
        }.onFailure {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    /**
     * Imports a user-selected local `.bin` Whisper model file via Android Storage Access Framework
     * into app-specific private storage and verifies GGML integrity before accepting it.
     */
    suspend fun importLocalModel(
        sourceUri: Uri,
        suggestedFileName: String? = null,
        onProgress: (copiedBytes: Long) -> Unit,
        onVerifying: () -> Unit
    ): Result<InstalledWhisperModel> = withContext(Dispatchers.IO) {
        val safeFileName = (suggestedFileName?.takeIf { it.isNotBlank() } ?: WhisperModelCatalog.DEFAULT.fileName)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .let { if (it.endsWith(".bin", ignoreCase = true)) it else "$it.bin" }

        val targetFile = File(modelsDir, safeFileName)
        val tempFile = File(modelsDir, "$safeFileName.import.part")

        runCatching {
            cleanupLeftoverPartFiles()

            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Cannot open the selected local model file.")

            var copied = 0L
            var lastReportMs = 0L
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 100L) {
                            lastReportMs = now
                            onProgress(copied)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(copied)

            coroutineContext.ensureActive()
            onVerifying()

            // For local imports, verify GGML magic header and minimum valid size
            val verification = WhisperModelIntegrityVerifier.verifyFile(
                file = tempFile,
                expectedSha256 = null
            )
            if (!verification.isValid) {
                tempFile.delete()
                throw IllegalStateException(
                    verification.failureReason ?: "Selected file is not a valid Whisper GGML model."
                )
            }

            // Replace previous model files to keep app-specific storage clean
            modelsDir.listFiles()?.forEach { existing ->
                if (existing.isFile && existing != tempFile && existing.name.endsWith(".bin", ignoreCase = true)) {
                    runCatching { existing.delete() }
                }
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val matchesOfficial =
                (WhisperModelCatalog.TINY_EN_Q5_1.expectedSha256?.let {
                    verification.sha256.equals(it, ignoreCase = true)
                } == true) ||
                (WhisperModelCatalog.BASE_EN.expectedSha256?.let {
                    verification.sha256.equals(it, ignoreCase = true)
                } == true) ||
                targetFile.name.equals(WhisperModelCatalog.TINY_EN_Q5_1.fileName, ignoreCase = true)
            InstalledWhisperModel(
                fileName = targetFile.name,
                absolutePath = targetFile.absolutePath,
                sizeBytes = verification.sizeBytes,
                sha256 = verification.sha256,
                matchesOfficialChecksum = matchesOfficial,
                headerFormat = verification.headerFormat
            )
        }.onFailure {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    fun cleanupLeftoverPartFiles() {
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".part", ignoreCase = true)) {
                runCatching { file.delete() }
            }
        }
    }

    suspend fun deleteInstalledModels() = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }

    private fun openFollowingRedirects(urlString: String, maxRedirects: Int = 10): HttpURLConnection {
        var currentUrl = urlString
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", "VOXORA-Media-Player/2.1.1")
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
                val contentType = conn.contentType.orEmpty().lowercase()
                if (contentType.contains("text/html") ||
                    contentType.contains("application/json") ||
                    contentType.contains("application/xhtml")
                ) {
                    conn.disconnect()
                    throw IllegalStateException(
                        "Model download returned unexpected content type ($contentType) instead of a GGML binary."
                    )
                }
                return conn
            } else {
                conn.disconnect()
                throw IllegalStateException("Model download failed with HTTP $code")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading model.")
    }
}
