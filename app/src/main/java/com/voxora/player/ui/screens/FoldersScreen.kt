package com.voxora.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.model.FolderViewMode
import com.voxora.player.model.SortOption
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.FolderGridCard
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.VideoGridCard
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.components.VideoThumbnail
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraSunsetOrange
import com.voxora.player.ui.viewmodel.LibraryUiState
import com.voxora.player.util.FormatUtils

@Composable
fun FoldersScreen(
    uiState: LibraryUiState,
    folderViewMode: FolderViewMode = FolderViewMode.LIST,
    onToggleFolderViewMode: () -> Unit = {},
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
                .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Folders",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.folders.size} media collections · ${folderViewMode.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 1-Tap List / Grid Layout Switcher (persisted in DataStore)
                Surface(
                    onClick = onToggleFolderViewMode,
                    color = VoxoraCardCharcoal,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.85.dp, VoxoraSunsetOrange.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("folders_layout_toggle_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (folderViewMode == FolderViewMode.LIST) {
                                Icons.Rounded.GridView
                            } else {
                                Icons.AutoMirrored.Rounded.ViewList
                            },
                            contentDescription = stringResource(R.string.cd_layout_toggle),
                            tint = VoxoraAmberBright,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSafVideo,
                    modifier = Modifier.size(44.dp)
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
                        .size(44.dp)
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
                color = VoxoraSunsetOrange,
                trackColor = VoxoraHairlineBorder
            )
        }

        if (folderViewMode == FolderViewMode.LIST) {
            // Clean Horizontal Folder List View (stacked one after another)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("folders_list_view"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 148.dp),
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
                        FolderListRowCard(
                            folder = folder,
                            onClick = { onOpenFolder(folder) }
                        )
                    }
                }
            }
        } else {
            // 2-Column Visual Media Folder Grid View
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("folders_grid_view"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 148.dp),
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
}

@Composable
private fun FolderListRowCard(
    folder: VideoFolder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("folder_list_card_${folder.bucketId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(0.8.dp, voxoraColors.cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(VoxoraElevatedCharcoal),
                contentAlignment = Alignment.Center
            ) {
                if (folder.previewUriString != null) {
                    VideoThumbnail(
                        uriString = folder.previewUriString,
                        contentDescription = folder.name,
                        frameTimeMs = 7_000L,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = VoxoraSunsetOrange.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val countLabel = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos"
                val sizeLabel = if (folder.totalSizeBytes > 0L) " · ${FormatUtils.formatFileSize(folder.totalSizeBytes)}" else ""
                Text(
                    text = "$countLabel$sizeLabel",
                    style = TabularTimeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                color = VoxoraSunsetOrange.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.7.dp, VoxoraSunsetOrange.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "${folder.videoCount}",
                    style = TabularTimeStyle.copy(fontWeight = FontWeight.Bold),
                    color = VoxoraAmberBright,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 148.dp),
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 148.dp),
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
