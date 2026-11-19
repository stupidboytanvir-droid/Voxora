package com.voxora.player.data.repository

import android.net.Uri
import com.voxora.player.data.local.storage.MediaStoreVideoScanner
import com.voxora.player.data.local.storage.StorageAccessManager
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaLibrarySnapshot(
    val permissionState: MediaPermissionState = MediaPermissionState.DENIED,
    val videos: List<VideoItem> = emptyList(),
    val folders: List<VideoFolder> = emptyList(),
    val safVideos: List<VideoItem> = emptyList(),
    val isScanning: Boolean = false,
    val errorMessage: String? = null
)

class MediaRepository(
    private val scanner: MediaStoreVideoScanner,
    private val storageAccessManager: StorageAccessManager
) {
    private val _libraryState = MutableStateFlow(MediaLibrarySnapshot())
    val libraryState: StateFlow<MediaLibrarySnapshot> = _libraryState.asStateFlow()

    fun getRequiredMediaPermissions(): Array<String> =
        storageAccessManager.getRequiredMediaPermissions()

    fun currentPermissionState(): MediaPermissionState =
        storageAccessManager.getMediaPermissionState()

    suspend fun refreshLibrary(
        minDurationSec: Int = 0,
        showHiddenFolders: Boolean = false
    ) {
        val permState = storageAccessManager.getMediaPermissionState()
        _libraryState.value = _libraryState.value.copy(
            permissionState = permState,
            isScanning = true,
            errorMessage = null
        )

        val scanned = if (permState != MediaPermissionState.DENIED) {
            scanner.scanVideos(
                minDurationSec = minDurationSec,
                showHiddenFolders = showHiddenFolders
            )
        } else {
            emptyList()
        }

        val currentSaf = _libraryState.value.safVideos.filter { safItem ->
            scanned.none { it.uriString == safItem.uriString }
        }
        val combined = (currentSaf + scanned).distinctBy { it.uriString }
        val folders = scanner.groupIntoFolders(combined)

        _libraryState.value = _libraryState.value.copy(
            permissionState = permState,
            videos = combined,
            folders = folders,
            safVideos = currentSaf,
            isScanning = false,
            errorMessage = null
        )
    }

    suspend fun addSafVideo(uri: Uri): VideoItem {
        val item = storageAccessManager.resolveSafVideoItem(uri)
        val updatedSaf = (_libraryState.value.safVideos.filterNot { it.uriString == item.uriString } + item)
        val combined = (updatedSaf + _libraryState.value.videos.filterNot { it.isFromSaf })
            .distinctBy { it.uriString }
        _libraryState.value = _libraryState.value.copy(
            safVideos = updatedSaf,
            videos = combined,
            folders = scanner.groupIntoFolders(combined)
        )
        return item
    }

    suspend fun readSubtitleContent(uri: Uri): Result<Pair<String, String>> {
        val name = storageAccessManager.resolveDisplayName(uri) ?: "subtitle.srt"
        return storageAccessManager.readTextFromUri(uri).map { content ->
            name to content
        }
    }

    suspend fun isVideoAccessible(uriString: String): Boolean {
        return storageAccessManager.isUriAccessible(uriString)
    }

    fun sortVideos(videos: List<VideoItem>, sortOption: SortOption): List<VideoItem> {
        return when (sortOption) {
            SortOption.NAME_ASC -> videos.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> videos.sortedByDescending { it.title.lowercase() }
            SortOption.DATE_DESC -> videos.sortedByDescending { it.dateModifiedSec }
            SortOption.DATE_ASC -> videos.sortedBy { it.dateModifiedSec }
            SortOption.DURATION_DESC -> videos.sortedByDescending { it.durationMs }
            SortOption.SIZE_DESC -> videos.sortedByDescending { it.sizeBytes }
        }
    }

    fun findVideoByUri(uriString: String): VideoItem? {
        return _libraryState.value.videos.firstOrNull { it.uriString == uriString }
    }
}
