package com.voxora.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.PlaylistEntryItem
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.ContinueWatchingCard
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.UpdateAvailableBanner
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.components.VideoThumbnail
import com.voxora.player.ui.components.VoxoraTopHeader
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.viewmodel.LibraryUiState
import com.voxora.player.util.FormatUtils
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    uiState: LibraryUiState,
    playlists: List<PlaylistSummary> = emptyList(),
    allPlaylistItems: List<PlaylistEntryItem> = emptyList(),
    libraryMessage: String? = null,
    updateState: UpdateUiState = UpdateUiState.Idle,
    onOpenUpdateDialog: () -> Unit = {},
    onPlayVideo: (VideoItem) -> Unit,
    onPlayStreamUrl: (url: String, title: String) -> Unit = { _, _ -> },
    onPlayPlaylistEntry: (PlaylistEntryItem) -> Unit = {},
    onResumeHistory: (PlaybackHistoryItem) -> Unit,
    onOpenFolder: (VideoFolder) -> Unit,
    onOpenVault: () -> Unit = {},
    onOpenCleaner: () -> Unit = {},
    onCreatePlaylist: (String) -> Unit = {},
    onDeletePlaylist: (Long) -> Unit = {},
    onAddToWatchLater: (VideoItem) -> Unit = {},
    onAddToPlaylist: (Long, String, VideoItem) -> Unit = { _, _, _ -> },
    onRemoveFromPlaylist: (Long, String) -> Unit = { _, _ -> },
    onExtractAudio: (VideoItem) -> Unit = {},
    onHideInVault: (VideoItem) -> Unit = {},
    onClearLibraryMessage: () -> Unit = {},
    onSeeAllVideos: () -> Unit,
    onSeeAllFolders: () -> Unit,
    onSeeAllHistory: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val historyByUri = uiState.recentlyPlayed.associateBy { it.uriString }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoonMessage = stringResource(R.string.ai_dubbing_coming_soon_message)

    var selectedHomeTab by remember { mutableIntStateOf(0) } // 0 = Library, 1 = Playlists & Watch Later
    var showStreamUrlDialog by remember { mutableStateOf(false) }
    var optionsTargetVideo by remember { mutableStateOf<VideoItem?>(null) }

    LaunchedEffect(libraryMessage) {
        if (libraryMessage != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(libraryMessage)
            onClearLibraryMessage()
        }
    }

    if (showStreamUrlDialog) {
        StreamUrlDialog(
            onPlayUrl = onPlayStreamUrl,
            onDismiss = { showStreamUrlDialog = false }
        )
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
            VoxoraTopHeader(
                onSearchClick = onSearchClick,
                onOpenFileClick = onOpenSafVideo,
                onSettingsClick = onSettingsClick
            )
            if (uiState.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Segmented Tabs: Library | Playlists & Watch Later
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HomeSegmentTabChip(
                    label = "Library",
                    selected = selectedHomeTab == 0,
                    onClick = { selectedHomeTab = 0 },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_tab_library")
                )
                HomeSegmentTabChip(
                    label = "Playlists & Watch Later",
                    selected = selectedHomeTab == 1,
                    onClick = { selectedHomeTab = 1 },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_tab_playlists")
                )
            }

            if (selectedHomeTab == 1) {
                PlaylistsWatchLaterTabContent(
                    playlists = playlists,
                    allPlaylistItems = allPlaylistItems,
                    onCreatePlaylist = onCreatePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onPlayEntry = onPlayPlaylistEntry,
                    onRemoveEntry = onRemoveFromPlaylist,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Quick Flagship Utilities Row: Stream URL | Private Vault | Storage Cleaner
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QuickUtilityPill(
                                icon = Icons.Rounded.Link,
                                label = "Stream URL",
                                contentDescription = stringResource(R.string.cd_stream_url),
                                onClick = { showStreamUrlDialog = true },
                                testTag = "home_stream_url_button"
                            )
                            QuickUtilityPill(
                                icon = Icons.Rounded.Security,
                                label = "Private Vault",
                                contentDescription = stringResource(R.string.cd_private_vault),
                                onClick = onOpenVault,
                                testTag = "home_private_vault_button"
                            )
                            QuickUtilityPill(
                                icon = Icons.Rounded.CleaningServices,
                                label = "Storage Cleaner",
                                contentDescription = stringResource(R.string.cd_storage_cleaner),
                                onClick = onOpenCleaner,
                                testTag = "home_storage_cleaner_button"
                            )
                        }
                    }

                    if (updateState is UpdateUiState.UpdateAvailable ||
                        updateState is UpdateUiState.Downloading ||
                        updateState is UpdateUiState.ReadyToInstall
                    ) {
                        item {
                            UpdateAvailableBanner(
                                state = updateState,
                                onClick = onOpenUpdateDialog,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    item {
                        MediaPermissionBanner(
                            permissionState = uiState.permissionState,
                            onRequestPermission = onRequestPermission,
                            onOpenSafVideo = onOpenSafVideo,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Sleek Locked AI Audio Dubbing — Coming Soon Badge Card
                    item {
                        AiDubbingComingSoonCard(
                            onClick = {
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(comingSoonMessage)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Continue Watching Section
                    if (uiState.continueWatching.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Continue Watching",
                                actionLabel = "History",
                                onActionClick = onSeeAllHistory
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uiState.continueWatching, key = { it.uriString }) { item ->
                                    ContinueWatchingCard(
                                        item = item,
                                        onClick = { onResumeHistory(item) }
                                    )
                                }
                            }
                        }
                    }

                    // Recently Played Section
                    if (uiState.recentlyPlayed.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Recently Played",
                                actionLabel = "See All",
                                onActionClick = onSeeAllHistory
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uiState.recentlyPlayed.take(10), key = { "recent_${it.uriString}" }) { item ->
                                    RecentlyPlayedTile(
                                        item = item,
                                        onClick = { onResumeHistory(item) }
                                    )
                                }
                            }
                        }
                    }

                    // Folders Section
                    if (uiState.folders.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Folders",
                                actionLabel = "All (${uiState.folders.size})",
                                onActionClick = onSeeAllFolders
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uiState.folders.take(10), key = { it.bucketId }) { folder ->
                                    QuickFolderTile(
                                        folder = folder,
                                        onClick = { onOpenFolder(folder) }
                                    )
                                }
                            }
                        }
                    }

                    // All Videos Section
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "All Videos (${uiState.allVideos.size})",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onRefresh,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("home_refresh_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.cd_refresh),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (uiState.allVideos.isNotEmpty()) {
                                    TextButton(onClick = onSeeAllVideos) {
                                        Text("See All")
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.allVideos.isEmpty() && !uiState.isScanning) {
                        item {
                            EmptyLibraryView(
                                title = "No Videos Found Yet",
                                subtitle = "Scan your device video folders or open any video directly using the system picker.",
                                onRefresh = onRefresh,
                                onOpenFile = onOpenSafVideo
                            )
                        }
                    } else {
                        items(uiState.allVideos.take(15), key = { it.uriString }) { video ->
                            VideoRowCard(
                                video = video,
                                lastPositionMs = historyByUri[video.uriString]?.lastPositionMs,
                                onClick = { onPlayVideo(video) },
                                onMoreClick = { optionsTargetVideo = video },
                                modifier = Modifier.padding(horizontal = 16.dp)
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
                .padding(16.dp)
        )
    }
}

@Composable
private fun HomeSegmentTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) VoxoraElectricPurple.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) VoxoraCyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickUtilityPill(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String
) {
    val voxoraColors = LocalVoxoraColors.current
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.85f)),
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = VoxoraCyan,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AiDubbingComingSoonCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("home_ai_dubbing_coming_soon_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    VoxoraElectricPurple.copy(alpha = 0.55f),
                    VoxoraCyan.copy(alpha = 0.45f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = VoxoraElectricPurple.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = VoxoraElectricPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "AI Audio Dubbing",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = "Live Auto AI Subtitles, Dual Subtitles & .SRT export active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = VoxoraCyan.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "Coming Soon",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoxoraCyan,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        TextButton(onClick = onActionClick) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun RecentlyPlayedTile(
    item: PlaybackHistoryItem,
    onClick: () -> Unit
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = Modifier
            .width(164.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.7f))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
            ) {
                VideoThumbnail(
                    uriString = item.uriString,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Surface(
                        color = Color(0xBF09090D),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .padding(3.dp)
                                .size(14.dp)
                        )
                    }
                }
                if (item.durationMs > 0L) {
                    Surface(
                        color = Color(0xCC09090D),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(item.durationMs),
                            style = TabularTimeStyle,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = FormatUtils.formatRelativePlayedAt(item.lastPlayedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = voxoraColors.blueHighlight
                )
            }
        }
    }
}

@Composable
private fun QuickFolderTile(
    folder: VideoFolder,
    onClick: () -> Unit
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${folder.videoCount} videos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
