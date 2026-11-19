package com.voxora.player.data.repository

import com.voxora.player.data.local.db.PlaybackHistoryDao
import com.voxora.player.data.local.db.PlaybackHistoryEntity
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaybackHistoryRepository(
    private val dao: PlaybackHistoryDao
) {
    val recentlyPlayed: Flow<List<PlaybackHistoryItem>> = dao.observeAllHistory()
        .map { entities -> entities.map { it.toDomain() } }

    val continueWatching: Flow<List<PlaybackHistoryItem>> = recentlyPlayed
        .map { items -> items.filter { it.isContinueWatching } }

    suspend fun getHistoryForUri(uriString: String): PlaybackHistoryItem? {
        return dao.getByUri(uriString)?.toDomain()
    }

    suspend fun recordOrUpdateProgress(
        video: VideoItem,
        positionMs: Long,
        durationMs: Long,
        speed: Float
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getByUri(video.uriString)
        val safeDuration = if (durationMs > 0L) durationMs else video.durationMs
        val entity = if (existing != null) {
            existing.copy(
                title = video.title,
                displayName = video.displayName,
                folderName = video.folderName,
                durationMs = safeDuration,
                lastPositionMs = positionMs.coerceAtLeast(0L),
                sizeBytes = if (video.sizeBytes > 0L) video.sizeBytes else existing.sizeBytes,
                lastPlayedAtMs = now,
                preferredSpeed = speed
            )
        } else {
            PlaybackHistoryEntity(
                uriString = video.uriString,
                mediaStoreId = video.id,
                title = video.title,
                displayName = video.displayName,
                folderName = video.folderName,
                durationMs = safeDuration,
                lastPositionMs = positionMs.coerceAtLeast(0L),
                sizeBytes = video.sizeBytes,
                lastPlayedAtMs = now,
                preferredSpeed = speed
            )
        }
        dao.upsert(entity)
    }

    suspend fun saveExternalSubtitle(
        uriString: String,
        subtitleUri: String?,
        subtitleName: String?,
        syncOffsetMs: Long
    ) {
        val existing = dao.getByUri(uriString)
        if (existing != null) {
            dao.updateExternalSubtitle(uriString, subtitleUri, subtitleName, syncOffsetMs)
        }
    }

    suspend fun saveSubtitleSyncOffset(uriString: String, syncOffsetMs: Long) {
        dao.updateSubtitleSyncOffset(uriString, syncOffsetMs)
    }

    suspend fun savePreferredSpeed(uriString: String, speed: Float) {
        dao.updatePreferredSpeed(uriString, speed)
    }

    suspend fun removeEntry(uriString: String) {
        dao.deleteByUri(uriString)
    }

    suspend fun clearAllHistory() {
        dao.clearAll()
    }
}
