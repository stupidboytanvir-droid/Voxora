package com.voxora.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.viewmodel.LibraryUiState

@Composable
fun VideosScreen(
    uiState: LibraryUiState,
    playlists: List<PlaylistSummary> = emptyList(),
    libraryMessage: String? = null,
    onSearchQueryChange: (String) -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onAddToWatchLater: (VideoItem) -> Unit = {},
    onAddToPlaylist: (Long, String, VideoItem) -> Unit = { _, _, _ -> },
    onExtractAudio: (VideoItem) -> Unit = {},
    onHideInVault: (VideoItem) -> Unit = {},
    onClearLibraryMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var optionsTargetVideo by remember { mutableStateOf<VideoItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val historyByUri = uiState.recentlyPlayed.associateBy { it.uriString }

    LaunchedEffect(libraryMessage) {
        if (libraryMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(libraryMessage)
            onClearLibraryMessage()
        }
    }

    optionsTargetVideo?.let { video ->
        VideoOptionsBottomSheet(
            video = video,
            playlists = playlists,
            onAddToWatchLater = onAddToWatchLater,
            onAddToPlaylist = onAddToPlaylist,
            onExtractAudio = onExtractAudio,
            onHideInVault = onHideInVault,
            onDismiss = { optionsTargetVideo = null }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Videos",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${uiState.filteredVideos.size} items · ${uiState.sortOption.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenSafVideo,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("videos_open_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.cd_pick_file),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("videos_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("videos_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sort,
                                contentDescription = "Sort videos",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (option == uiState.sortOption) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSortSelected(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Search Field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search videos or folders...") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("videos_search_input")
            )

            if (uiState.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    MediaPermissionBanner(
                        permissionState = uiState.permissionState,
                        onRequestPermission = onRequestPermission,
                        onOpenSafVideo = onOpenSafVideo
                    )
                }

                if (uiState.filteredVideos.isEmpty() && !uiState.isScanning) {
                    item {
                        val isSearching = uiState.searchQuery.isNotBlank()
                        EmptyLibraryView(
                            title = if (isSearching) "No Matching Videos" else "Your Video Library Is Empty",
                            subtitle = if (isSearching) {
                                "Try a different filename or folder keyword."
                            } else {
                                "Tap Refresh after granting video access, or pick any video file directly."
                            },
                            onRefresh = onRefresh,
                            onOpenFile = onOpenSafVideo
                        )
                    }
                } else {
                    items(uiState.filteredVideos, key = { it.uriString }) { video ->
                        VideoRowCard(
                            video = video,
                            lastPositionMs = historyByUri[video.uriString]?.lastPositionMs,
                            onClick = { onPlayVideo(video) },
                            onMoreClick = { optionsTargetVideo = video }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}
