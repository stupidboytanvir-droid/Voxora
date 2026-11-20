package com.voxora.player.data.repository

import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.data.local.db.TranslatedSegmentEntity
import com.voxora.player.data.local.db.TranslationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository abstracting Room local cache operations for offline English -> Bengali translations.
 */
class TranslationCacheRepository(
    private val translationDao: TranslationDao
) {
    fun observeCachedTranslation(videoUri: String): Flow<List<TranslatedSegment>> {
        return translationDao.observeSegmentsForVideo(videoUri).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getCachedTranslation(videoUri: String): List<TranslatedSegment> {
        if (videoUri.isBlank()) return emptyList()
        return translationDao.getSegmentsForVideo(videoUri).map { it.toDomain() }
    }

    suspend fun saveTranslation(videoUri: String, segments: List<TranslatedSegment>) {
        if (videoUri.isBlank()) return
        val timestamp = System.currentTimeMillis()
        val entities = segments.map { segment ->
            TranslatedSegmentEntity.fromDomain(
                videoUri = videoUri,
                segment = segment,
                updatedAtMs = timestamp
            )
        }
        translationDao.replaceSegmentsForVideo(videoUri, entities)
    }

    suspend fun clearTranslationForVideo(videoUri: String) {
        if (videoUri.isBlank()) return
        translationDao.deleteSegmentsForVideo(videoUri)
    }
}
