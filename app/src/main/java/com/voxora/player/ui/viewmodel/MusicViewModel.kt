package com.voxora.player.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.local.storage.MediaStoreAudioScanner
import com.voxora.player.model.AudioAlbumGroup
import com.voxora.player.model.AudioArtistGroup
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.AudioFolderItem
import com.voxora.player.model.AudioTrackItem
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.player.MusicPlayerController
import com.voxora.player.player.MusicPlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class MusicLibrarySubTab(val label: String) {
    ALL_SONGS("All Songs"),
    FOLDERS("Folders"),
    ARTISTS("Artists"),
    ALBUMS("Albums")
}

data class MusicLibraryUiState(
    val hasPermission: Boolean = false,
    val isScanning: Boolean = false,
    val searchQuery: String = "",
    val selectedTab: MusicLibrarySubTab = MusicLibrarySubTab.ALL_SONGS,
    val allTracks: List<AudioTrackItem> = emptyList(),
    val filteredTracks: List<AudioTrackItem> = emptyList(),
    val folders: List<AudioFolderItem> = emptyList(),
    val artists: List<AudioArtistGroup> = emptyList(),
    val albums: List<AudioAlbumGroup> = emptyList(),
    val selectedFolderId: String? = null,
    val selectedFolderName: String? = null,
    val selectedArtistName: String? = null,
    val selectedAlbumKey: String? = null,
    val selectedAlbumName: String? = null
)

class MusicViewModel(
    private val audioScanner: MediaStoreAudioScanner,
    val playerController: MusicPlayerController
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MusicLibraryUiState(hasPermission = audioScanner.hasAudioPermission())
    )
    val uiState: StateFlow<MusicLibraryUiState> = _uiState.asStateFlow()

    val playerState: StateFlow<MusicPlayerState> = playerController.state

    init {
        refreshAudioLibrary()
    }

    fun getRequiredAudioPermission(): String = audioScanner.getRequiredAudioPermission()

    fun onPermissionResult() {
        refreshAudioLibrary()
    }

    fun refreshAudioLibrary() {
        val granted = audioScanner.hasAudioPermission()
        _uiState.value = _uiState.value.copy(hasPermission = granted)
        if (!granted) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val snapshot = audioScanner.scanAudioLibrary()
            val query = _uiState.value.searchQuery
            _uiState.value = _uiState.value.copy(
                hasPermission = true,
                isScanning = false,
                allTracks = snapshot.tracks,
                filteredTracks = filterTracks(snapshot.tracks, query),
                folders = snapshot.folders,
                artists = snapshot.artists,
                albums = snapshot.albums
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        val cur = _uiState.value
        _uiState.value = cur.copy(
            searchQuery = query,
            filteredTracks = filterTracks(cur.allTracks, query)
        )
    }

    fun selectTab(tab: MusicLibrarySubTab) {
        _uiState.value = _uiState.value.copy(
            selectedTab = tab,
            selectedFolderId = null,
            selectedFolderName = null,
            selectedArtistName = null,
            selectedAlbumKey = null,
            selectedAlbumName = null
        )
    }

    fun openFolder(folder: AudioFolderItem) {
        _uiState.value = _uiState.value.copy(
            selectedFolderId = folder.folderId,
            selectedFolderName = folder.name
        )
    }

    fun openArtist(artist: AudioArtistGroup) {
        _uiState.value = _uiState.value.copy(selectedArtistName = artist.name)
    }

    fun openAlbum(album: AudioAlbumGroup) {
        _uiState.value = _uiState.value.copy(
            selectedAlbumKey = "${album.albumId}|${album.name}",
            selectedAlbumName = album.name
        )
    }

    fun clearDrillDown() {
        _uiState.value = _uiState.value.copy(
            selectedFolderId = null,
            selectedFolderName = null,
            selectedArtistName = null,
            selectedAlbumKey = null,
            selectedAlbumName = null
        )
    }

    fun tracksForDrillDown(): List<AudioTrackItem> {
        val s = _uiState.value
        val base = when {
            s.selectedFolderId != null -> s.allTracks.filter { it.folderId == s.selectedFolderId }
            s.selectedArtistName != null -> s.allTracks.filter { it.artist == s.selectedArtistName }
            s.selectedAlbumKey != null -> s.allTracks.filter { "${it.albumId}|${it.album}" == s.selectedAlbumKey }
            else -> s.filteredTracks
        }
        return filterTracks(base, s.searchQuery)
    }

    fun playTrackFromList(tracks: List<AudioTrackItem>, index: Int) {
        playerController.playQueue(tracks, index)
    }

    fun shuffleAll() {
        val list = _uiState.value.filteredTracks.ifEmpty { _uiState.value.allTracks }
        if (list.isEmpty()) return
        if (!playerController.state.value.shuffleEnabled) {
            playerController.toggleShuffle()
        }
        playerController.playQueue(list.shuffled(), 0)
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun skipToNext() = playerController.skipToNext()
    fun skipToPrevious() = playerController.skipToPrevious()
    fun seekTo(ms: Long) = playerController.seekTo(ms)
    fun toggleShuffle() = playerController.toggleShuffle()
    fun cycleRepeatMode() = playerController.cycleRepeatMode()
    fun setVolumeBoostPercent(pct: Int) = playerController.setVolumeBoostPercent(pct)
    fun setEqPreset(preset: AudioEqPreset) = playerController.setEqPreset(preset)
    fun setSleepTimer(option: SleepTimerOption) = playerController.setSleepTimer(option)
    fun stopPlayback() = playerController.stopAndClear()

    private fun filterTracks(tracks: List<AudioTrackItem>, query: String): List<AudioTrackItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return tracks
        return tracks.filter {
            it.title.lowercase(Locale.ROOT).contains(q) ||
                it.artist.lowercase(Locale.ROOT).contains(q) ||
                it.album.lowercase(Locale.ROOT).contains(q) ||
                it.folderName.lowercase(Locale.ROOT).contains(q)
        }
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusicViewModel(
                audioScanner = app.audioScanner,
                playerController = app.musicPlayerController
            ) as T
        }
    }
}
