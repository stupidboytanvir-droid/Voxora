package com.voxora.player.data.local.db

import androidx.room.Entity
import androidx.room.Index
import com.voxora.player.ai.translation.TranslatedSegment

/**
 * Local Room cache entry for an offline-translated English -> Bengali segment keyed by [videoUri].
 * Prevents re-translating the same video on subsequent playback sessions.
 */
@Entity(
    tableName = "translated_segments",
    primaryKeys = ["videoUri", "segmentIndex"],
    indices = [Index(value = ["videoUri"])]
)
data class TranslatedSegmentEntity(
    val videoUri: String,
    val segmentIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String,
    val characterId: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): TranslatedSegment = TranslatedSegment(
        index = segmentIndex,
        startMs = startMs,
        endMs = endMs,
        originalText = originalText,
        translatedText = translatedText,
        characterId = characterId
    )

    companion object {
        fun fromDomain(
            videoUri: String,
            segment: TranslatedSegment,
            updatedAtMs: Long = System.currentTimeMillis()
        ): TranslatedSegmentEntity = TranslatedSegmentEntity(
            videoUri = videoUri,
            segmentIndex = segment.index,
            startMs = segment.startMs,
            endMs = segment.endMs,
            originalText = segment.originalText,
            translatedText = segment.translatedText,
            characterId = segment.characterId,
            updatedAtMs = updatedAtMs
        )
    }
}
