package com.voxora.player.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewAgenda
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.VideoGridCard
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
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
    var isGridView by rememberSaveable { mutableStateOf(true) }
    var optionsTargetVideo by remember { mutableStateOf<VideoItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val historyByUri = remember(uiState.recentlyPlayed) {
        uiState.recentlyPlayed.associateBy { it.uriString }
    }

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
            // Top Header with 1-tap List / Grid Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Videos",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${uiState.filteredVideos.size} items · ${uiState.sortOption.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 1-Tap List / Grid View Toggle
                    Surface(
                        onClick = { isGridView = !isGridView },
                        color = VoxoraCardCharcoal,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("videos_view_toggle_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isGridView) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                                contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view",
                                tint = VoxoraAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSafVideo,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("videos_open_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.cd_pick_file),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("videos_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("videos_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sort,
                                contentDescription = "Sort videos",
                                tint = VoxoraAmber,
                                modifier = Modifier.size(22.dp)
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
                                                tint = VoxoraAmber
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

            // Frosted Glass Search Input
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search movies, episodes, folders...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    focusedContainerColor = VoxoraCardCharcoal,
                    unfocusedContainerColor = VoxoraCardCharcoal,
                    focusedBorderColor = VoxoraAmber,
                    unfocusedBorderColor = VoxoraHairlineBorder
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
                    color = VoxoraAmber,
                    trackColor = VoxoraHairlineBorder
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 118.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MediaPermissionBanner(
                            permissionState = uiState.permissionState,
                            onRequestPermission = onRequestPermission,
                            onOpenSafVideo = onOpenSafVideo
                        )
                    }

                    if (uiState.filteredVideos.isEmpty() && !uiState.isScanning) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
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
                            VideoGridCard(
                                video = video,
                                lastPositionMs = historyByUri[video.uriString]?.lastPositionMs,
                                onClick = { onPlayVideo(video) },
                                onMoreClick = { optionsTargetVideo = video }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 118.dp),
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
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 104.dp)
        )
    }
}
