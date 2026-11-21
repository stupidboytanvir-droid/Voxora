package com.voxora.player.util

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class DuplicateVideoGroup(
    val hashKey: String,
    val sizeBytes: Long,
    val videos: List<VideoItem>
) {
    val reclaimableBytes: Long
        get() = sizeBytes * (videos.size - 1).coerceAtLeast(0)
}

data class StorageCleanerReport(
    val duplicateGroups: List<DuplicateVideoGroup> = emptyList(),
    val largeVideos: List<VideoItem> = emptyList(),
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false
) {
    val totalDuplicateReclaimableBytes: Long
        get() = duplicateGroups.sumOf { it.reclaimableBytes }

    val totalLargeVideosBytes: Long
        get() = largeVideos.sumOf { it.sizeBytes }
}

/**
 * Lightweight Storage Cleaner scanning local videos for:
 * 1. Exact duplicate videos (matched by file size + partial SHA-256 hash of first/middle/last 64KB).
 * 2. Large videos exceeding 500 MB.
 */
object StorageCleanerEngine {

    const val LARGE_VIDEO_THRESHOLD_BYTES: Long = 500L * 1024L * 1024L
    private const val PARTIAL_READ_BYTES = 64 * 1024

    suspend fun scanLibrary(
        context: Context,
        videos: List<VideoItem>
    ): StorageCleanerReport = withContext(Dispatchers.IO) {
        val largeVideos = videos
            .filter { it.sizeBytes > LARGE_VIDEO_THRESHOLD_BYTES }
            .sortedByDescending { it.sizeBytes }

        // Group candidates with identical non-zero file size before hashing
        val sameSizeBuckets = videos
            .filter { it.sizeBytes > 0L && !it.isFromSaf }
            .groupBy { it.sizeBytes }
            .filterValues { it.size > 1 }

        val duplicateGroups = mutableListOf<DuplicateVideoGroup>()
        for ((sizeBytes, bucket) in sameSizeBuckets) {
            val hashedBuckets = linkedMapOf<String, MutableList<VideoItem>>()
            for (video in bucket) {
                val hash = computePartialFileHash(context, video.uriString, sizeBytes) ?: continue
                hashedBuckets.getOrPut(hash) { mutableListOf() }.add(video)
            }
            for ((hash, dupes) in hashedBuckets) {
                if (dupes.size > 1) {
                    duplicateGroups.add(
                        DuplicateVideoGroup(
                            hashKey = hash,
                            sizeBytes = sizeBytes,
                            videos = dupes.sortedByDescending { it.dateModifiedSec }
                        )
                    )
                }
            }
        }

        StorageCleanerReport(
            duplicateGroups = duplicateGroups.sortedByDescending { it.reclaimableBytes },
            largeVideos = largeVideos,
            isScanning = false,
            hasScanned = true
        )
    }

    private fun computePartialFileHash(
        context: Context,
        uriString: String,
        sizeBytes: Long
    ): String? {
        return runCatching {
            val uri = Uri.parse(uriString)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(sizeBytes.toString().toByteArray(Charsets.UTF_8))

            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val fis = afd.createInputStream()
                fis.use { stream ->
                    val channel = stream.channel
                    val totalSize = channel.size()
                    val buf = ByteArray(PARTIAL_READ_BYTES)

                    // First 64KB
                    val firstRead = stream.read(buf, 0, PARTIAL_READ_BYTES)
                    if (firstRead > 0) md.update(buf, 0, firstRead)

                    // Middle 64KB
                    if (totalSize > PARTIAL_READ_BYTES * 3L) {
                        channel.position(totalSize / 2L)
                        val midRead = stream.read(buf, 0, PARTIAL_READ_BYTES)
                        if (midRead > 0) md.update(buf, 0, midRead)
                    }

                    // Last 64KB
                    if (totalSize > PARTIAL_READ_BYTES * 2L) {
                        channel.position((totalSize - PARTIAL_READ_BYTES).coerceAtLeast(0L))
                        val endRead = stream.read(buf, 0, PARTIAL_READ_BYTES)
                        if (endRead > 0) md.update(buf, 0, endRead)
                    }
                }
            } ?: return null

            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * Attempts direct deletion for app-owned files or returns a scoped-storage [IntentSender]
     * on Android 11+ (`MediaStore.createDeleteRequest`) for user-confirmed safe deletion.
     */
    fun buildDeleteRequestIntentSender(
        context: Context,
        uris: List<Uri>
    ): IntentSender? {
        if (uris.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            }.getOrNull()
        } else {
            null
        }
    }

    suspend fun deleteDirectlyLegacy(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        for (uri in uris) {
            val count = runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0)
            if (count > 0) deleted++
        }
        deleted
    }
}
