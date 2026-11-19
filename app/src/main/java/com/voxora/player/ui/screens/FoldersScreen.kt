package com.voxora.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.FolderCard
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.viewmodel.LibraryUiState

@Composable
fun FoldersScreen(
    uiState: LibraryUiState,
    onOpenFolder: (VideoFolder) -> Unit,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.folders.size} video folders on device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(
                    onClick = onOpenSafVideo,
                    modifier = Modifier.size(48.dp)
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
                        .testTag("folders_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        if (uiState.isScanning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary
            )
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

            if (uiState.folders.isEmpty() && !uiState.isScanning) {
                item {
                    EmptyLibraryView(
                        title = "No Video Folders",
                        subtitle = "Folders appear automatically once local videos are accessible.",
                        onRefresh = onRefresh,
                        onOpenFile = onOpenSafVideo
                    )
                }
            } else {
                items(uiState.folders, key = { it.bucketId }) { folder ->
                    FolderCard(
                        folder = folder,
                        onClick = { onOpenFolder(folder) }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderDetailScreen(
    folderName: String,
    videos: List<VideoItem>,
    uiState: LibraryUiState,
    onBack: () -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val historyByUri = uiState.recentlyPlayed.associateBy { it.uriString }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("folder_detail_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${videos.size} ${if (videos.size == 1) "video" else "videos"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sort,
                        contentDescription = "Sort folder videos",
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(videos, key = { it.uriString }) { video ->
                VideoRowCard(
                    video = video,
                    lastPositionMs = historyByUri[video.uriString]?.lastPositionMs,
                    onClick = { onPlayVideo(video) }
                )
            }
        }
    }
}
