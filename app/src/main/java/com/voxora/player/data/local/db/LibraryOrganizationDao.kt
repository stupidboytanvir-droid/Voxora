package com.voxora.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SceneBookmarkDao {
    @Query("SELECT * FROM scene_bookmarks WHERE videoUri = :videoUri ORDER BY timestampMs ASC")
    fun observeForVideo(videoUri: String): Flow<List<SceneBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: SceneBookmarkEntity): Long

    @Query("DELETE FROM scene_bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isWatchLater DESC, createdAtMs DESC")
    fun observeAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isWatchLater = 1 LIMIT 1")
    suspend fun getWatchLaterPlaylist(): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId AND isWatchLater = 0")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_items ORDER BY addedAtMs DESC")
    fun observeAllPlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY addedAtMs DESC")
    fun observeItemsForPlaylist(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity): Long

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoUri = :videoUri")
    suspend fun removeVideoFromPlaylist(playlistId: Long, videoUri: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearPlaylistItems(playlistId: Long)
}

@Dao
interface VaultVideoDao {
    @Query("SELECT * FROM vault_videos ORDER BY addedAtMs DESC")
    fun observeAllVaultVideos(): Flow<List<VaultVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultVideoEntity): Long

    @Query("DELETE FROM vault_videos WHERE id = :id")
    suspend fun deleteById(id: Long)
}
