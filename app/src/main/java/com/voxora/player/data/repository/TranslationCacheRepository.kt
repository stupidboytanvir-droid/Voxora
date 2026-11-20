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

    suspend fun updateSegmentCharacterId(
        videoUri: String,
        segmentIndex: Int,
        characterId: String?
    ) {
        if (videoUri.isBlank()) return
        translationDao.updateSegmentCharacterId(
            videoUri = videoUri,
            segmentIndex = segmentIndex,
            characterId = characterId
        )
    }

    suspend fun updateSegmentCharacterIds(
        videoUri: String,
        assignments: Map<Int, String?>
    ) {
        if (videoUri.isBlank() || assignments.isEmpty()) return
        translationDao.updateSegmentCharacterIds(
            videoUri = videoUri,
            assignments = assignments
        )
    }

    suspend fun clearTranslationForVideo(videoUri: String) {
        if (videoUri.isBlank()) return
        translationDao.deleteSegmentsForVideo(videoUri)
    }
}
