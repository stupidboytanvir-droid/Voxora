package com.voxora.player.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.FolderGridCard
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.VideoGridCard
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
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
                .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.folders.size} media collections on device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(
                    onClick = onOpenSafVideo,
                    modifier = Modifier.size(46.dp)
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
                        .size(46.dp)
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
                color = VoxoraAmber,
                trackColor = VoxoraHairlineBorder
            )
        }

        // Sleek 2-column Visual Media Folder Grid
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

            if (uiState.folders.isEmpty() && !uiState.isScanning) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibraryView(
                        title = "No Video Folders",
                        subtitle = "Folders appear automatically once local videos are accessible.",
                        onRefresh = onRefresh,
                        onOpenFile = onOpenSafVideo
                    )
                }
            } else {
                items(uiState.folders, key = { it.bucketId }) { folder ->
                    FolderGridCard(
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
    var isGridView by rememberSaveable { mutableStateOf(false) }
    val historyByUri = remember(uiState.recentlyPlayed) {
        uiState.recentlyPlayed.associateBy { it.uriString }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
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
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
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

            Surface(
                onClick = { isGridView = !isGridView },
                color = VoxoraCardCharcoal,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGridView) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                        contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view",
                        tint = VoxoraAmber,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sort,
                        contentDescription = "Sort folder videos",
                        tint = VoxoraAmber
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

        if (isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 118.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(videos, key = { it.uriString }) { video ->
                    VideoGridCard(
                        video = video,
                        lastPositionMs = historyByUri[video.uriString]?.lastPositionMs,
                        onClick = { onPlayVideo(video) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 118.dp),
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
}
