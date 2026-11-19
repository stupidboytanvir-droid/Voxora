package com.voxora.player.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.SortOption
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
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

    init {
        refreshLibrary()
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

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(
                mediaRepository = app.mediaRepository,
                historyRepository = app.historyRepository,
                settingsRepository = app.settingsRepository
            ) as T
        }
    }
}
