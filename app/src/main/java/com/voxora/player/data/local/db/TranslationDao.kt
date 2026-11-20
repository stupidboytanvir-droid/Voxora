package com.voxora.player.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {

    @Query("SELECT * FROM translated_segments WHERE videoUri = :videoUri ORDER BY segmentIndex ASC")
    fun observeSegmentsForVideo(videoUri: String): Flow<List<TranslatedSegmentEntity>>

    @Query("SELECT * FROM translated_segments WHERE videoUri = :videoUri ORDER BY segmentIndex ASC")
    suspend fun getSegmentsForVideo(videoUri: String): List<TranslatedSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranslatedSegmentEntity>)

    @Query("DELETE FROM translated_segments WHERE videoUri = :videoUri")
    suspend fun deleteSegmentsForVideo(videoUri: String)

    @Query("SELECT COUNT(*) FROM translated_segments WHERE videoUri = :videoUri")
    suspend fun countSegmentsForVideo(videoUri: String): Int

    @Transaction
    suspend fun replaceSegmentsForVideo(
        videoUri: String,
        segments: List<TranslatedSegmentEntity>
    ) {
        deleteSegmentsForVideo(videoUri)
        if (segments.isNotEmpty()) {
            insertSegments(segments)
        }
    }
}
