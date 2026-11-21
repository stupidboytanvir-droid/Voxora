package com.voxora.player.data.local.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.voxora.player.model.AudioAlbumGroup
import com.voxora.player.model.AudioArtistGroup
import com.voxora.player.model.AudioFolderItem
import com.voxora.player.model.AudioTrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class AudioLibrarySnapshot(
    val tracks: List<AudioTrackItem> = emptyList(),
    val folders: List<AudioFolderItem> = emptyList(),
    val artists: List<AudioArtistGroup> = emptyList(),
    val albums: List<AudioAlbumGroup> = emptyList()
)

/**
 * Queries local songs, podcasts, and audio files cleanly via Android Scoped Storage
 * (`MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`) and groups them by Folder, Artist, and Album.
 */
class MediaStoreAudioScanner(private val context: Context) {

    fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    suspend fun scanAudioLibrary(): AudioLibrarySnapshot = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) return@withContext AudioLibrarySnapshot()

        val tracks = mutableListOf<AudioTrackItem>()
        val contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.IS_MUSIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.BUCKET_ID)
                add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        // Include music and audio tracks >= 5 seconds (filters out short UI clicks/notifications)
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf("5000")
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        runCatching {
            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val bucketIdCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_ID)
                } else -1
                val bucketNameCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                } else -1
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1
                @Suppress("DEPRECATION")
                val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                    val cleanTitle = rawTitle.ifBlank {
                        displayName.substringBeforeLast('.').ifBlank { "Untitled Audio" }
                    }
                    val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else ""
                    val artist = if (rawArtist.isBlank() || rawArtist.equals("<unknown>", ignoreCase = true)) {
                        "Unknown Artist"
                    } else rawArtist
                    val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                    val album = if (rawAlbum.isBlank() || rawAlbum.equals("<unknown>", ignoreCase = true)) {
                        "Unknown Album"
                    } else rawAlbum
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                    val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateModifiedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else "audio/*"

                    val (folderId, folderName) = resolveFolder(
                        bucketId = if (bucketIdCol >= 0) cursor.getString(bucketIdCol) else null,
                        bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) else null,
                        relativePath = if (relPathCol >= 0) cursor.getString(relPathCol) else null,
                        legacyDataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    )

                    val trackUri = ContentUris.withAppendedId(contentUri, id).toString()
                    val albumArtUri = if (albumId > 0L) {
                        ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                    } else null

                    tracks.add(
                        AudioTrackItem(
                            id = id,
                            uriString = trackUri,
                            title = cleanTitle,
                            artist = artist,
                            album = album,
                            albumId = albumId,
                            albumArtUri = albumArtUri,
                            durationMs = durationMs.coerceAtLeast(0L),
                            sizeBytes = sizeBytes.coerceAtLeast(0L),
                            dateModifiedSec = dateModifiedSec,
                            folderId = folderId,
                            folderName = folderName,
                            displayName = displayName.ifBlank { cleanTitle },
                            mimeType = mimeType.ifBlank { "audio/*" },
                            formatBadge = resolveAudioFormatBadge(displayName, mimeType)
                        )
                    )
                }
            }
        }

        buildSnapshot(tracks)
    }

    private fun resolveFolder(
        bucketId: String?,
        bucketName: String?,
        relativePath: String?,
        legacyDataPath: String?
    ): Pair<String, String> {
        if (!bucketId.isNullOrBlank() && !bucketName.isNullOrBlank()) {
            return bucketId to bucketName
        }
        val rel = relativePath?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (rel != null) {
            return rel.lowercase(Locale.ROOT) to rel
        }
        val parent = legacyDataPath?.let { File(it).parentFile }
        if (parent != null) {
            return parent.absolutePath to parent.name.ifBlank { "Music" }
        }
        return "audio_root" to "Music"
    }

    companion object {
        fun resolveAudioFormatBadge(displayName: String, mimeType: String): String {
            val ext = displayName.substringAfterLast('.', "").trim().uppercase(Locale.ROOT)
            if (ext in setOf("MP3", "FLAC", "M4A", "WAV", "OGG", "OPUS", "AAC", "WMA", "ALAC", "AMR", "MID")) {
                return ext
            }
            val mime = mimeType.lowercase(Locale.ROOT)
            return when {
                mime.contains("flac") -> "FLAC"
                mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "M4A"
                mime.contains("wav") || mime.contains("x-wav") -> "WAV"
                mime.contains("opus") -> "OPUS"
                mime.contains("ogg") -> "OGG"
                else -> "AUDIO"
            }
        }

        fun buildSnapshot(tracks: List<AudioTrackItem>): AudioLibrarySnapshot {
            val folders = tracks
                .groupBy { it.folderId }
                .map { (folderId, list) ->
                    AudioFolderItem(
                        folderId = folderId,
                        name = list.first().folderName,
                        trackCount = list.size,
                        totalDurationMs = list.sumOf { it.durationMs },
                        previewAlbumArtUri = list.firstNotNullOfOrNull { it.albumArtUri }
                    )
                }
                .sortedByDescending { it.trackCount }

            val artists = tracks
                .groupBy { it.artist }
                .map { (artistName, list) ->
                    AudioArtistGroup(
                        name = artistName,
                        trackCount = list.size,
                        albumCount = list.map { it.album }.distinct().size,
                        previewAlbumArtUri = list.firstNotNullOfOrNull { it.albumArtUri }
                    )
                }
                .sortedByDescending { it.trackCount }

            val albums = tracks
                .groupBy { "${it.albumId}|${it.album}" }
                .map { (_, list) ->
                    val first = list.first()
                    AudioAlbumGroup(
                        albumId = first.albumId,
                        name = first.album,
                        artist = first.artist,
                        trackCount = list.size,
                        albumArtUri = list.firstNotNullOfOrNull { it.albumArtUri }
                    )
                }
                .sortedByDescending { it.trackCount }

            return AudioLibrarySnapshot(
                tracks = tracks,
                folders = folders,
                artists = artists,
                albums = albums
            )
        }
    }
}
