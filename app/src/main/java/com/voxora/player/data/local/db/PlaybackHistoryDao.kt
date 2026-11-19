package com.voxora.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedAtMs DESC")
    fun observeAllHistory(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackHistoryEntity)

    @Query(
        "UPDATE playback_history SET lastPositionMs = :positionMs, durationMs = :durationMs, lastPlayedAtMs = :playedAtMs WHERE uriString = :uriString"
    )
    suspend fun updatePosition(uriString: String, positionMs: Long, durationMs: Long, playedAtMs: Long)

    @Query(
        "UPDATE playback_history SET externalSubtitleUri = :subUri, externalSubtitleName = :subName, subtitleSyncOffsetMs = :syncOffsetMs WHERE uriString = :uriString"
    )
    suspend fun updateExternalSubtitle(
        uriString: String,
        subUri: String?,
        subName: String?,
        syncOffsetMs: Long
    )

    @Query("UPDATE playback_history SET subtitleSyncOffsetMs = :syncOffsetMs WHERE uriString = :uriString")
    suspend fun updateSubtitleSyncOffset(uriString: String, syncOffsetMs: Long)

    @Query("UPDATE playback_history SET preferredSpeed = :speed WHERE uriString = :uriString")
    suspend fun updatePreferredSpeed(uriString: String, speed: Float)

    @Query("DELETE FROM playback_history WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}
