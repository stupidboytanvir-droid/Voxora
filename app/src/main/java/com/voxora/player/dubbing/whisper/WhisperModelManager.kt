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
     * Official open-source Whisper Base English (`base.en`) model from whisper.cpp.
     * Downloaded on demand to app-specific storage (never bundled in APK).
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
}

/**
 * Pure verifier for Whisper GGML/GGUF model binaries.
 * Rejects truncated files, HTML error downloads, or corrupted binaries before passing to native C++.
 */
object WhisperModelIntegrityVerifier {

    // Minimum valid size for any real Whisper model (even quantized tiny.en is ~31 MB)
    const val MIN_REAL_MODEL_BYTES: Long = 20L * 1024L * 1024L

    /**
     * Checks 4-byte GGML magic header and computes real streaming SHA-256 digest.
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
        if (size < minBytes) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "too_small",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "File is too small (${size / (1024 * 1024)} MB) to be a valid Whisper GGML model."
            )
        }

        val header = ByteArray(4)
        FileInputStream(file).use { fis ->
            val read = fis.read(header)
            if (read < 4) {
                return ModelIntegrityResult(
                    isValid = false,
                    sizeBytes = size,
                    headerFormat = "unreadable",
                    sha256 = "",
                    matchesExpectedChecksum = false,
                    failureReason = "Unable to read model binary header."
                )
            }
        }

        val headerFormat = detectGgmlMagic(header)
        if (headerFormat == null) {
            return ModelIntegrityResult(
                isValid = false,
                sizeBytes = size,
                headerFormat = "invalid",
                sha256 = "",
                matchesExpectedChecksum = false,
                failureReason = "Invalid model header. Expected a genuine whisper.cpp GGML binary (ggml-base.en.bin)."
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
                failureReason = "SHA-256 integrity check failed for ggml-base.en.bin. Download may be corrupted."
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
}

/**
 * Manages app-specific offline storage, HTTP download, SAF local import, and integrity
 * verification for Whisper models (`ggml-base.en.bin`).
 */
class WhisperModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "whisper_models").apply { mkdirs() }

    fun getDefaultModelFile(spec: WhisperModelSpec = WhisperModelCatalog.BASE_EN): File {
        return File(modelsDir, spec.fileName)
    }

    /**
     * Scans app-specific storage for an already installed Whisper `.bin` model and verifies its header.
     */
    suspend fun findInstalledModel(
        spec: WhisperModelSpec = WhisperModelCatalog.BASE_EN
    ): InstalledWhisperModel? = withContext(Dispatchers.IO) {
        val preferred = getDefaultModelFile(spec)
        val candidate = when {
            preferred.exists() && preferred.isFile -> preferred
            else -> modelsDir.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
        } ?: return@withContext null

        val isOfficialBase = candidate.name.equals(spec.fileName, ignoreCase = true)
        val verification = WhisperModelIntegrityVerifier.verifyFile(
            file = candidate,
            expectedSha256 = if (isOfficialBase) spec.expectedSha256 else null
        )
        if (!verification.isValid) return@withContext null

        InstalledWhisperModel(
            fileName = candidate.name,
            absolutePath = candidate.absolutePath,
            sizeBytes = verification.sizeBytes,
            sha256 = verification.sha256,
            matchesOfficialChecksum = verification.matchesExpectedChecksum && isOfficialBase,
            headerFormat = verification.headerFormat
        )
    }

    /**
     * Downloads the official open-source `ggml-base.en.bin` model into app-specific storage,
     * streaming progress and verifying SHA-256 integrity before committing.
     */
    suspend fun downloadModel(
        spec: WhisperModelSpec = WhisperModelCatalog.BASE_EN,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit
    ): Result<InstalledWhisperModel> = withContext(Dispatchers.IO) {
        val targetFile = getDefaultModelFile(spec)
        val tempFile = File(modelsDir, "${spec.fileName}.part")

        runCatching {
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
        val safeFileName = (suggestedFileName?.takeIf { it.isNotBlank() } ?: WhisperModelCatalog.BASE_EN.fileName)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .let { if (it.endsWith(".bin", ignoreCase = true)) it else "$it.bin" }

        val targetFile = File(modelsDir, safeFileName)
        val tempFile = File(modelsDir, "$safeFileName.import.part")

        runCatching {
            if (tempFile.exists()) tempFile.delete()

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

            // For local imports, verify GGML magic header and minimum valid size; also note if official base.en SHA matches
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

            InstalledWhisperModel(
                fileName = targetFile.name,
                absolutePath = targetFile.absolutePath,
                sizeBytes = verification.sizeBytes,
                sha256 = verification.sha256,
                matchesOfficialChecksum = verification.sha256.equals(
                    WhisperModelCatalog.BASE_EN.expectedSha256,
                    ignoreCase = true
                ),
                headerFormat = verification.headerFormat
            )
        }.onFailure {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    suspend fun deleteInstalledModels() = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }

    private fun openFollowingRedirects(urlString: String, maxRedirects: Int = 6): HttpURLConnection {
        var currentUrl = urlString
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "VOXORA-Android/1.2")
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
                throw IllegalStateException("Model download failed with HTTP $code")
            }
        }
        throw IllegalStateException("Too many HTTP redirects while downloading model.")
    }
}
