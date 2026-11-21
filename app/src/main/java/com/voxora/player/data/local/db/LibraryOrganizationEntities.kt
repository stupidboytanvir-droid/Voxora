package com.voxora.player.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scene_bookmarks",
    indices = [Index(value = ["videoUri"])]
)
data class SceneBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoUri: String,
    val title: String,
    val timestampMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val isWatchLater: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_items",
    indices = [Index(value = ["playlistId"]), Index(value = ["playlistId", "videoUri"], unique = true)]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val videoUri: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderName: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "vault_videos")
data class VaultVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val originalTitle: String,
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtMs: Long = System.currentTimeMillis()
)
