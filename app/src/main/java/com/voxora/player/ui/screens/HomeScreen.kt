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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.R
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.model.PlaybackHistoryItem
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
    updateState: UpdateUiState = UpdateUiState.Idle,
    onOpenUpdateDialog: () -> Unit = {},
    onPlayVideo: (VideoItem) -> Unit,
    onResumeHistory: (PlaybackHistoryItem) -> Unit,
    onOpenFolder: (VideoFolder) -> Unit,
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

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
        item {
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
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
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
                    text = "Live Auto AI Subtitles (59+ languages) active in player",
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
