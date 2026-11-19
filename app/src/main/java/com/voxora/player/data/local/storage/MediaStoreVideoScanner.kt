package com.voxora.player.data.local.storage

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreVideoScanner(private val context: Context) {

    suspend fun scanVideos(
        minDurationSec: Int = 0,
        showHiddenFolders: Boolean = false
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.TITLE)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketIdCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val title = if (titleCol >= 0) {
                        cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: displayName.substringBeforeLast('.')
                    } else {
                        displayName.substringBeforeLast('.')
                    }
                    val durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).coerceAtLeast(0L) else 0L
                    val dateModifiedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) ?: "root" else "root"
                    val folderName = if (bucketNameCol >= 0) {
                        cursor.getString(bucketNameCol)?.takeIf { it.isNotBlank() } ?: "Device Storage"
                    } else {
                        "Device Storage"
                    }
                    val relativePath = if (relPathCol >= 0) {
                        cursor.getString(relPathCol) ?: folderName
                    } else {
                        folderName
                    }
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/*" else "video/*"

                    if (minDurationSec > 0 && durationMs in 1L until (minDurationSec * 1000L)) {
                        continue
                    }
                    if (!showHiddenFolders && (folderName.startsWith(".") || relativePath.contains("/."))) {
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    videos.add(
                        VideoItem(
                            id = id,
                            uriString = contentUri.toString(),
                            title = title,
                            displayName = displayName,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            dateModifiedSec = dateModifiedSec,
                            folderId = bucketId,
                            folderName = folderName,
                            relativePath = relativePath.trimEnd('/'),
                            width = width,
                            height = height,
                            mimeType = mimeType
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        videos
    }

    fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
        return videos.groupBy { it.folderId }
            .map { (bucketId, items) ->
                val first = items.first()
                VideoFolder(
                    bucketId = bucketId,
                    name = first.folderName,
                    path = first.relativePath,
                    videoCount = items.size,
                    totalSizeBytes = items.sumOf { it.sizeBytes },
                    latestTimestampSec = items.maxOfOrNull { it.dateModifiedSec } ?: 0L,
                    previewUriString = items.maxByOrNull { it.dateModifiedSec }?.uriString
                )
            }
            .sortedByDescending { it.latestTimestampSec }
    }
}
