package com.voxora.player.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.voxora.player.model.PlaybackHistoryItem

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey val uriString: String,
    val mediaStoreId: Long,
    val title: String,
    val displayName: String,
    val folderName: String,
    val durationMs: Long,
    val lastPositionMs: Long,
    val sizeBytes: Long,
    val lastPlayedAtMs: Long,
    val externalSubtitleUri: String? = null,
    val externalSubtitleName: String? = null,
    val subtitleSyncOffsetMs: Long = 0L,
    val preferredSpeed: Float = 1.0f
) {
    fun toDomain(): PlaybackHistoryItem = PlaybackHistoryItem(
        uriString = uriString,
        mediaStoreId = mediaStoreId,
        title = title,
        displayName = displayName,
        folderName = folderName,
        durationMs = durationMs,
        lastPositionMs = lastPositionMs,
        sizeBytes = sizeBytes,
        lastPlayedAtMs = lastPlayedAtMs,
        externalSubtitleUri = externalSubtitleUri,
        externalSubtitleName = externalSubtitleName,
        subtitleSyncOffsetMs = subtitleSyncOffsetMs,
        preferredSpeed = preferredSpeed
    )

    companion object {
        fun fromDomain(item: PlaybackHistoryItem): PlaybackHistoryEntity = PlaybackHistoryEntity(
            uriString = item.uriString,
            mediaStoreId = item.mediaStoreId,
            title = item.title,
            displayName = item.displayName,
            folderName = item.folderName,
            durationMs = item.durationMs,
            lastPositionMs = item.lastPositionMs,
            sizeBytes = item.sizeBytes,
            lastPlayedAtMs = item.lastPlayedAtMs,
            externalSubtitleUri = item.externalSubtitleUri,
            externalSubtitleName = item.externalSubtitleName,
            subtitleSyncOffsetMs = item.subtitleSyncOffsetMs,
            preferredSpeed = item.preferredSpeed
        )
    }
}
