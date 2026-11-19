package com.voxora.player.data.local.storage

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class StorageAccessManager(private val context: Context) {

    fun getRequiredMediaPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    fun getMediaPermissionState(): MediaPermissionState {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasFull = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                val hasPartial = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
                when {
                    hasFull -> MediaPermissionState.FULL_ACCESS
                    hasPartial -> MediaPermissionState.PARTIAL_ACCESS
                    else -> MediaPermissionState.DENIED
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasFull = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFull) MediaPermissionState.FULL_ACCESS else MediaPermissionState.DENIED
            }
            else -> {
                val hasFull = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFull) MediaPermissionState.FULL_ACCESS else MediaPermissionState.DENIED
            }
        }
    }

    fun takePersistableReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Transient URI grant (e.g., from external viewer intent) - still readable during session.
        } catch (_: IllegalArgumentException) {
            // Non-document URI (e.g., MediaStore) does not require persistable grants.
        }
    }

    suspend fun resolveDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return@withContext uri.lastPathSegment
        }
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@withContext cursor.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        uri.lastPathSegment
    }

    suspend fun resolveSafVideoItem(uri: Uri): VideoItem = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        var displayName = uri.lastPathSegment ?: "Video"
        var sizeBytes = 0L
        var mimeType = context.contentResolver.getType(uri) ?: "video/*"

        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }

        var durationMs = 0L
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val title = displayName.substringBeforeLast('.')
        VideoItem(
            id = uri.toString().hashCode().toLong(),
            uriString = uri.toString(),
            title = title.ifBlank { displayName },
            displayName = displayName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateModifiedSec = System.currentTimeMillis() / 1000L,
            folderId = "saf_opened",
            folderName = "Opened Files",
            relativePath = "Storage Access Framework",
            width = width,
            height = height,
            mimeType = mimeType,
            isFromSaf = true
        )
    }

    suspend fun readTextFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: error("Unable to open subtitle file")
        }
    }

    suspend fun isUriAccessible(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
