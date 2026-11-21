package com.voxora.player.data.repository

import com.voxora.player.data.local.db.PlaylistDao
import com.voxora.player.data.local.db.PlaylistEntity
import com.voxora.player.data.local.db.PlaylistItemEntity
import com.voxora.player.data.local.db.SceneBookmarkDao
import com.voxora.player.data.local.db.SceneBookmarkEntity
import com.voxora.player.data.local.db.VaultVideoDao
import com.voxora.player.data.local.db.VaultVideoEntity
import com.voxora.player.model.PlaylistEntryItem
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.SceneBookmark
import com.voxora.player.model.VaultVideoItem
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LibraryOrganizationRepository(
    private val bookmarkDao: SceneBookmarkDao,
    private val playlistDao: PlaylistDao,
    private val vaultVideoDao: VaultVideoDao
) {

    // --- Scene Bookmarks ---

    fun observeBookmarksForVideo(videoUri: String): Flow<List<SceneBookmark>> {
        return bookmarkDao.observeForVideo(videoUri).map { entities ->
            entities.map {
                SceneBookmark(
                    id = it.id,
                    videoUri = it.videoUri,
                    title = it.title,
                    timestampMs = it.timestampMs,
                    createdAtMs = it.createdAtMs
                )
            }
        }
    }

    suspend fun addBookmark(videoUri: String, title: String, timestampMs: Long) {
        val cleanTitle = title.trim().ifBlank { "Scene Bookmark" }
        bookmarkDao.insert(
            SceneBookmarkEntity(
                videoUri = videoUri,
                title = cleanTitle,
                timestampMs = timestampMs.coerceAtLeast(0L)
            )
        )
    }

    suspend fun deleteBookmark(id: Long) {
        bookmarkDao.deleteById(id)
    }

    // --- Custom Playlists & Watch Later ---

    val playlists: Flow<List<PlaylistSummary>> = combine(
        playlistDao.observeAllPlaylists(),
        playlistDao.observeAllPlaylistItems()
    ) { playlistEntities, itemEntities ->
        val byPlaylist = itemEntities.groupBy { it.playlistId }
        playlistEntities.map { pl ->
            val items = byPlaylist[pl.id].orEmpty()
            PlaylistSummary(
                id = pl.id,
                name = pl.name,
                isWatchLater = pl.isWatchLater,
                videoCount = items.size,
                previewUriString = items.firstOrNull()?.videoUri,
                createdAtMs = pl.createdAtMs
            )
        }
    }

    val allPlaylistItems: Flow<List<PlaylistEntryItem>> = playlistDao.observeAllPlaylistItems().map { list ->
        list.map {
            PlaylistEntryItem(
                id = it.id,
                playlistId = it.playlistId,
                videoUri = it.videoUri,
                title = it.title,
                durationMs = it.durationMs,
                sizeBytes = it.sizeBytes,
                folderName = it.folderName,
                addedAtMs = it.addedAtMs
            )
        }
    }

    suspend fun ensureWatchLaterExists(): Long {
        val existing = playlistDao.getWatchLaterPlaylist()
        if (existing != null) return existing.id
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = "Watch Later", isWatchLater = true)
        )
    }

    suspend fun createPlaylist(name: String): Long {
        val clean = name.trim().ifBlank { "Untitled Playlist" }
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = clean, isWatchLater = false)
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.clearPlaylistItems(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addVideoToWatchLater(video: VideoItem) {
        val watchLaterId = ensureWatchLaterExists()
        addVideoToPlaylist(watchLaterId, video)
    }

    suspend fun addVideoToPlaylist(playlistId: Long, video: VideoItem) {
        playlistDao.insertPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                videoUri = video.uriString,
                title = video.title,
                durationMs = video.durationMs,
                sizeBytes = video.sizeBytes,
                folderName = video.folderName
            )
        )
    }

    suspend fun removeVideoFromPlaylist(playlistId: Long, videoUri: String) {
        playlistDao.removeVideoFromPlaylist(playlistId, videoUri)
    }

    // --- Private Vault Metadata ---

    val vaultVideos: Flow<List<VaultVideoItem>> = vaultVideoDao.observeAllVaultVideos().map { list ->
        list.map {
            VaultVideoItem(
                id = it.id,
                originalTitle = it.originalTitle,
                filePath = it.filePath,
                durationMs = it.durationMs,
                sizeBytes = it.sizeBytes,
                addedAtMs = it.addedAtMs
            )
        }
    }

    suspend fun recordVaultVideo(
        originalTitle: String,
        filePath: String,
        durationMs: Long,
        sizeBytes: Long
    ): Long {
        return vaultVideoDao.insert(
            VaultVideoEntity(
                originalTitle = originalTitle,
                filePath = filePath,
                durationMs = durationMs,
                sizeBytes = sizeBytes
            )
        )
    }

    suspend fun removeVaultRecord(id: Long) {
        vaultVideoDao.deleteById(id)
    }
}
