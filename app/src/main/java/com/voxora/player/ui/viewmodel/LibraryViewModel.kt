package com.voxora.player.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.repository.LibraryOrganizationRepository
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.PlaylistEntryItem
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.SortOption
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VaultVideoItem
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.util.MediaToolkit
import com.voxora.player.util.PrivateVaultManager
import com.voxora.player.util.StorageCleanerEngine
import com.voxora.player.util.StorageCleanerReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val permissionState: MediaPermissionState = MediaPermissionState.DENIED,
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val folders: List<VideoFolder> = emptyList(),
    val recentlyPlayed: List<PlaybackHistoryItem> = emptyList(),
    val continueWatching: List<PlaybackHistoryItem> = emptyList(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.DATE_DESC,
    val isScanning: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
    val errorMessage: String? = null
)

class LibraryViewModel(
    private val app: VoxoraApplication,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val organizationRepository: LibraryOrganizationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")

    val uiState: StateFlow<LibraryUiState> = combine(
        mediaRepository.libraryState,
        historyRepository.recentlyPlayed,
        historyRepository.continueWatching,
        settingsRepository.preferencesFlow,
        searchQueryFlow
    ) { library, recent, continueList, prefs, query ->
        val sorted = mediaRepository.sortVideos(library.videos, prefs.sortOption)
        val filtered = if (query.isBlank()) {
            sorted
        } else {
            val q = query.trim().lowercase()
            sorted.filter {
                it.title.lowercase().contains(q) ||
                    it.displayName.lowercase().contains(q) ||
                    it.folderName.lowercase().contains(q)
            }
        }
        LibraryUiState(
            permissionState = library.permissionState,
            allVideos = sorted,
            filteredVideos = filtered,
            folders = library.folders,
            recentlyPlayed = recent,
            continueWatching = continueList,
            searchQuery = query,
            sortOption = prefs.sortOption,
            isScanning = library.isScanning,
            preferences = prefs,
            errorMessage = library.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryUiState(
            permissionState = mediaRepository.currentPermissionState()
        )
    )

    val playlists: StateFlow<List<PlaylistSummary>> = organizationRepository.playlists
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val allPlaylistItems: StateFlow<List<PlaylistEntryItem>> = organizationRepository.allPlaylistItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    val vaultVideos: StateFlow<List<VaultVideoItem>> = organizationRepository.vaultVideos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    private val _cleanerReport = MutableStateFlow(StorageCleanerReport())
    val cleanerReport: StateFlow<StorageCleanerReport> = _cleanerReport.asStateFlow()

    private val _libraryMessage = MutableStateFlow<String?>(null)
    val libraryMessage: StateFlow<String?> = _libraryMessage.asStateFlow()

    private val _isBusyOperation = MutableStateFlow(false)
    val isBusyOperation: StateFlow<Boolean> = _isBusyOperation.asStateFlow()

    init {
        refreshLibrary()
        viewModelScope.launch {
            organizationRepository.ensureWatchLaterExists()
        }
    }

    fun getRequiredPermissions(): Array<String> = mediaRepository.getRequiredMediaPermissions()

    fun refreshLibrary() {
        viewModelScope.launch {
            val prefs = uiState.value.preferences
            mediaRepository.refreshLibrary(
                minDurationSec = prefs.minVideoDurationSec,
                showHiddenFolders = prefs.showHiddenFolders
            )
        }
    }

    fun onPermissionResult() {
        refreshLibrary()
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun onSortOptionSelected(option: SortOption) {
        viewModelScope.launch {
            settingsRepository.setSortOption(option)
        }
    }

    fun openSafVideo(uri: Uri, onResolved: (VideoItem) -> Unit) {
        viewModelScope.launch {
            val item = mediaRepository.addSafVideo(uri)
            onResolved(item)
        }
    }

    fun videosInFolder(bucketId: String): List<VideoItem> {
        return uiState.value.allVideos.filter { it.folderId == bucketId }
    }

    fun removeHistoryItem(uriString: String) {
        viewModelScope.launch {
            historyRepository.removeEntry(uriString)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearAllHistory()
        }
    }

    // --- Custom Playlists & Watch Later ---

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            organizationRepository.createPlaylist(name)
            _libraryMessage.value = "Created playlist \"$name\""
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            organizationRepository.deletePlaylist(playlistId)
            _libraryMessage.value = "Playlist removed"
        }
    }

    fun addToWatchLater(video: VideoItem) {
        viewModelScope.launch {
            organizationRepository.addVideoToWatchLater(video)
            _libraryMessage.value = "Added \"${video.title}\" to Watch Later"
        }
    }

    fun addToPlaylist(playlistId: Long, playlistName: String, video: VideoItem) {
        viewModelScope.launch {
            organizationRepository.addVideoToPlaylist(playlistId, video)
            _libraryMessage.value = "Added to \"$playlistName\""
        }
    }

    fun removeFromPlaylist(playlistId: Long, videoUri: String) {
        viewModelScope.launch {
            organizationRepository.removeVideoFromPlaylist(playlistId, videoUri)
        }
    }

    // --- Video-to-Audio Extraction from Library ---

    fun extractAudioForVideo(video: VideoItem) {
        if (_isBusyOperation.value) return
        viewModelScope.launch {
            _isBusyOperation.value = true
            _libraryMessage.value = "Extracting audio from \"${video.title}\"..."
            MediaToolkit.extractAudioTrack(app, video.uriString, video.title).fold(
                onSuccess = { path ->
                    _libraryMessage.value = "Audio saved: $path"
                },
                onFailure = { err ->
                    _libraryMessage.value = err.message ?: "Failed to extract audio"
                }
            )
            _isBusyOperation.value = false
        }
    }

    // --- Private Vault Folder (PIN + Optional Biometric) ---

    fun setVaultPin(pin: String) {
        if (pin.length != 4) return
        val hash = PrivateVaultManager.hashPin(pin)
        viewModelScope.launch {
            settingsRepository.setVaultPinSha256(hash)
            _libraryMessage.value = "Private Vault PIN configured"
        }
    }

    fun setVaultBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVaultBiometricEnabled(enabled)
        }
    }

    fun hideVideoInVault(video: VideoItem) {
        if (_isBusyOperation.value) return
        viewModelScope.launch {
            _isBusyOperation.value = true
            PrivateVaultManager.copyVideoIntoVault(app, video).fold(
                onSuccess = { vaultFile ->
                    organizationRepository.recordVaultVideo(
                        originalTitle = video.title,
                        filePath = vaultFile.absolutePath,
                        durationMs = video.durationMs,
                        sizeBytes = vaultFile.length()
                    )
                    _libraryMessage.value = "Secured \"${video.title}\" in Private Vault"
                },
                onFailure = { err ->
                    _libraryMessage.value = err.message ?: "Unable to copy video to Private Vault"
                }
            )
            _isBusyOperation.value = false
        }
    }

    fun restoreVaultVideo(item: VaultVideoItem) {
        if (_isBusyOperation.value) return
        viewModelScope.launch {
            _isBusyOperation.value = true
            PrivateVaultManager.restoreVideoFromVault(app, item).fold(
                onSuccess = { path ->
                    organizationRepository.removeVaultRecord(item.id)
                    refreshLibrary()
                    _libraryMessage.value = "Restored to $path"
                },
                onFailure = { err ->
                    _libraryMessage.value = err.message ?: "Failed to restore video"
                }
            )
            _isBusyOperation.value = false
        }
    }

    fun deleteVaultVideo(item: VaultVideoItem) {
        viewModelScope.launch {
            PrivateVaultManager.deleteVaultFile(item.filePath)
            organizationRepository.removeVaultRecord(item.id)
            _libraryMessage.value = "Deleted from Private Vault"
        }
    }

    // --- Duplicate & Large File (> 500 MB) Storage Cleaner ---

    fun scanStorageCleaner() {
        viewModelScope.launch {
            _cleanerReport.value = _cleanerReport.value.copy(isScanning = true)
            val videos = uiState.value.allVideos
            val report = StorageCleanerEngine.scanLibrary(app, videos)
            _cleanerReport.value = report
        }
    }

    fun deleteCleanerVideosLegacy(uris: List<Uri>) {
        viewModelScope.launch {
            val count = StorageCleanerEngine.deleteDirectlyLegacy(app, uris)
            refreshLibrary()
            scanStorageCleaner()
            _libraryMessage.value = if (count > 0) "Deleted $count video(s)" else "Could not delete selected files"
        }
    }

    fun onCleanerDeleteConfirmed() {
        viewModelScope.launch {
            refreshLibrary()
            scanStorageCleaner()
            _libraryMessage.value = "Selected videos removed safely"
        }
    }

    fun showMessage(message: String) {
        _libraryMessage.value = message
    }

    fun clearLibraryMessage() {
        _libraryMessage.value = null
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(
                app = app,
                mediaRepository = app.mediaRepository,
                historyRepository = app.historyRepository,
                organizationRepository = app.libraryOrganizationRepository,
                settingsRepository = app.settingsRepository
            ) as T
        }
    }
}
