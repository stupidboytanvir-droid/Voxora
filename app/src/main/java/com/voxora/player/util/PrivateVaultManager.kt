package com.voxora.player.util

import android.content.ContentValues
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.voxora.player.model.VaultVideoItem
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Private Vault Folder engine secured by SHA-256 4-digit PIN and optional Android BiometricPrompt.
 * Stores hidden videos inside app-private internal storage (`filesDir/private_vault/`) which is
 * invisible to MediaStore / device gallery apps.
 */
object PrivateVaultManager {

    private const val SALT = "VOXORA_VAULT_V240_SALT::"

    fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$SALT$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(inputPin: String, storedHash: String): Boolean {
        if (storedHash.isBlank() || inputPin.length != 4) return false
        return hashPin(inputPin).equals(storedHash, ignoreCase = true)
    }

    fun isBiometricAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val bm = context.getSystemService(BiometricManager::class.java) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
        }.getOrDefault(false)
    }

    fun promptBiometricUnlock(
        context: Context,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onError("Biometric unlock requires Android 9 or newer.")
            return
        }
        runCatching {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt.Builder(context)
                .setTitle("Unlock VOXORA Private Vault")
                .setSubtitle("Verify with fingerprint or face to access hidden videos")
                .setNegativeButton("Use PIN", executor) { _, _ -> }
                .build()
            val cancellationSignal = CancellationSignal()
            prompt.authenticate(
                cancellationSignal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON
                        ) {
                            onError(errString?.toString() ?: "Biometric verification failed")
                        }
                    }
                }
            )
        }.onFailure { err ->
            onError(err.message ?: "Biometric authentication unavailable")
        }
    }

    private fun vaultDir(context: Context): File =
        File(context.filesDir, "private_vault").apply { mkdirs() }

    /**
     * Copies a selected video into VOXORA's private internal vault directory (`filesDir/private_vault/`)
     * and returns the stored file path + size. Original deletion is handled cleanly via MediaStore if requested.
     */
    suspend fun copyVideoIntoVault(
        context: Context,
        video: VideoItem
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = video.displayName.substringAfterLast('.', "mp4")
                .replace(Regex("[^A-Za-z0-9]"), "")
                .ifBlank { "mp4" }
            val dstFile = File(vaultDir(context), "vault_${System.currentTimeMillis()}.$ext")
            val uri = Uri.parse(video.uriString)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dstFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Cannot read selected video to move into vault.")
            if (dstFile.length() <= 0L) {
                dstFile.delete()
                throw IllegalStateException("Copied vault file is empty.")
            }
            dstFile
        }
    }

    /**
     * Restores (unhides) a private vault video back to `Movies/VOXORA/Restored/` and removes the vault copy.
     */
    suspend fun restoreVideoFromVault(
        context: Context,
        item: VaultVideoItem
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = File(item.filePath)
            if (!srcFile.exists()) throw IllegalStateException("Vault file no longer exists on disk.")
            val ext = srcFile.extension.ifBlank { "mp4" }
            val safeName = item.originalTitle
                .replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
                .trim()
                .take(48)
                .ifBlank { "VOXORA_Restored" }
            val displayName = if (safeName.endsWith(".$ext", ignoreCase = true)) safeName else "$safeName.$ext"

            val restoredPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VOXORA/Restored")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val dstUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to restore video to Movies/VOXORA/Restored")
                resolver.openOutputStream(dstUri)?.use { out ->
                    srcFile.inputStream().use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(dstUri, values, null, null)
                "Movies/VOXORA/Restored/$displayName"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "VOXORA/Restored"
                ).apply { mkdirs() }
                val outFile = File(dir, displayName)
                srcFile.copyTo(outFile, overwrite = true)
                outFile.absolutePath
            }
            srcFile.delete()
            restoredPath
        }
    }

    fun deleteVaultFile(filePath: String) {
        runCatching { File(filePath).delete() }
    }
}
