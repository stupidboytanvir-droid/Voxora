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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.R
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.PlaylistEntryItem
import com.voxora.player.model.PlaylistSummary
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.components.ContinueWatchingCard
import com.voxora.player.ui.components.EmptyLibraryView
import com.voxora.player.ui.components.EpisodeBadgePill
import com.voxora.player.ui.components.FolderGridCard
import com.voxora.player.ui.components.MediaPermissionBanner
import com.voxora.player.ui.components.UpdateAvailableBanner
import com.voxora.player.ui.components.VideoRowCard
import com.voxora.player.ui.components.VideoThumbnail
import com.voxora.player.ui.components.VoxoraTopHeader
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraObsidian
import com.voxora.player.ui.viewmodel.LibraryUiState
import com.voxora.player.util.FormatUtils
import com.voxora.player.util.TitleFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: LibraryUiState,
    playlists: List<PlaylistSummary> = emptyList(),
    allPlaylistItems: List<PlaylistEntryItem> = emptyList(),
    libraryMessage: String? = null,
    updateState: UpdateUiState = UpdateUiState.Idle,
    onOpenUpdateDialog: () -> Unit = {},
    onPlayVideo: (VideoItem) -> Unit,
    onPlayPlaylistEntry: (PlaylistEntryItem) -> Unit = {},
    onResumeHistory: (PlaybackHistoryItem) -> Unit,
    onOpenFolder: (VideoFolder) -> Unit,
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
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val historyByUri = remember(uiState.recentlyPlayed) {
        uiState.recentlyPlayed.associateBy { it.uriString }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var optionsTargetVideo by remember { mutableStateOf<VideoItem?>(null) }
    var showPlaylistsSheet by remember { mutableStateOf(false) }

    val heroHistory = uiState.continueWatching.firstOrNull() ?: uiState.recentlyPlayed.firstOrNull()
    val heroFallbackVideo = if (heroHistory == null) uiState.allVideos.firstOrNull() else null
    val upNextHistory = remember(uiState.continueWatching, uiState.recentlyPlayed, heroHistory) {
        val combined = (uiState.continueWatching + uiState.recentlyPlayed).distinctBy { it.uriString }
        if (heroHistory != null) combined.filterNot { it.uriString == heroHistory.uriString } else combined
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

    if (showPlaylistsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPlaylistsSheet = false },
            sheetState = sheetState,
            containerColor = VoxoraCardCharcoal,
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(540.dp)
            ) {
                Text(
                    text = "Playlists & Watch Later",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                PlaylistsWatchLaterTabContent(
                    playlists = playlists,
                    allPlaylistItems = allPlaylistItems,
                    onCreatePlaylist = onCreatePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onPlayEntry = { entry ->
                        showPlaylistsSheet = false
                        onPlayPlaylistEntry(entry)
                    },
                    onRemoveEntry = onRemoveFromPlaylist,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal Flagship Header: VOXORA brand wordmark + Search icon only
            VoxoraTopHeader(onSearchClick = onSearchClick)

            if (uiState.isScanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    color = VoxoraAmber,
                    trackColor = VoxoraHairlineBorder
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 6.dp, bottom = 118.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
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

                // Cinematic 16:9 Hero Spotlight ("Continue Watching" or Featured Latest Video)
                if (heroHistory != null) {
                    item(key = "hero_history_${heroHistory.uriString}") {
                        CinemaHeroSpotlightCard(
                            title = heroHistory.title,
                            subtitle = "${FormatUtils.formatDuration(heroHistory.lastPositionMs)} of ${FormatUtils.formatDuration(heroHistory.durationMs)} · ${heroHistory.folderName}",
                            uriString = heroHistory.uriString,
                            frameTimeMs = heroHistory.lastPositionMs.coerceAtLeast(6_000L),
                            durationMs = heroHistory.durationMs,
                            progressFraction = heroHistory.progressFraction,
                            eyebrow = "CONTINUE WATCHING",
                            ctaLabel = "Resume",
                            onClick = { onResumeHistory(heroHistory) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else if (heroFallbackVideo != null) {
                    item(key = "hero_video_${heroFallbackVideo.uriString}") {
                        CinemaHeroSpotlightCard(
                            title = heroFallbackVideo.title,
                            subtitle = "${heroFallbackVideo.folderName} · ${FormatUtils.formatDuration(heroFallbackVideo.durationMs)}",
                            uriString = heroFallbackVideo.uriString,
                            frameTimeMs = 8_000L,
                            durationMs = heroFallbackVideo.durationMs,
                            progressFraction = 0f,
                            eyebrow = "FEATURED IN LIBRARY",
                            ctaLabel = "Play Now",
                            onClick = { onPlayVideo(heroFallbackVideo) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Horizontal Poster Rail: Up Next & Recent
                if (upNextHistory.isNotEmpty()) {
                    item(key = "rail_up_next") {
                        CinemaSectionHeader(
                            title = "Up Next & Recent",
                            actionLabel = "History",
                            onActionClick = onSeeAllHistory
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(upNextHistory.take(12), key = { "upnext_${it.uriString}" }) { item ->
                                ContinueWatchingCard(
                                    item = item,
                                    onClick = { onResumeHistory(item) }
                                )
                            }
                        }
                    }
                }

                // Horizontal Visual Folders Rail (real thumbnail previews)
                if (uiState.folders.isNotEmpty()) {
                    item(key = "rail_folders") {
                        CinemaSectionHeader(
                            title = "Folders",
                            actionLabel = "All (${uiState.folders.size})",
                            onActionClick = onSeeAllFolders
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(uiState.folders.take(10), key = { "home_folder_${it.bucketId}" }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    onClick = { onOpenFolder(folder) },
                                    modifier = Modifier.width(178.dp)
                                )
                            }
                        }
                    }
                }

                // Compact Playlists & Watch Later Access Strip (zero clutter)
                item(key = "rail_playlists_strip") {
                    PlaylistsAccessStrip(
                        playlistCount = playlists.size,
                        totalEntries = allPlaylistItems.size,
                        onClick = { showPlaylistsSheet = true },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Library Videos Preview Section
                item(key = "header_library") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("home_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.cd_refresh),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            if (uiState.allVideos.isNotEmpty()) {
                                TextButton(onClick = onSeeAllVideos) {
                                    Text(
                                        text = "See All",
                                        color = VoxoraAmberBright,
                                        fontWeight = FontWeight.SemiBold
                                    )
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
                    items(uiState.allVideos.take(12), key = { it.uriString }) { video ->
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 104.dp)
        )
    }
}

@Composable
private fun CinemaHeroSpotlightCard(
    title: String,
    subtitle: String,
    uriString: String,
    frameTimeMs: Long,
    durationMs: Long,
    progressFraction: Float,
    eyebrow: String,
    ctaLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatted = remember(title) { TitleFormatter.format(title) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("home_hero_spotlight_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = VoxoraCardCharcoal),
        border = BorderStroke(0.9.dp, VoxoraHairlineBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9.4f)
        ) {
            VideoThumbnail(
                uriString = uriString,
                contentDescription = formatted.cleanTitle,
                frameTimeMs = frameTimeMs,
                durationMs = durationMs,
                modifier = Modifier.fillMaxSize()
            )

            // Ambient cinematic gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0x40050608),
                                0.42f to Color(0x28050608),
                                0.72f to Color(0xBF050608),
                                1.0f to VoxoraObsidian.copy(alpha = 0.96f)
                            )
                        )
                    )
            )

            // Top-left eyebrow & episode pill
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = Color(0xB8050608),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.7.dp, Color.White.copy(alpha = 0.16f))
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        ),
                        color = VoxoraAmberBright,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
                formatted.episodeBadge?.let { badge ->
                    EpisodeBadgePill(badgeText = badge, filled = true)
                }
            }

            // Bottom content: clean title, metadata, Resume button & thin Amber progress bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatted.cleanTitle,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = TabularTimeStyle,
                            color = Color.White.copy(alpha = 0.76f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onClick,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VoxoraAmber,
                            contentColor = Color(0xFF140D00)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("home_hero_resume_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = ctaLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                if (progressFraction > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.5.dp)
                            .background(Color.White.copy(alpha = 0.16f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .background(VoxoraAmber)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsAccessStrip(
    playlistCount: Int,
    totalEntries: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = VoxoraCardCharcoal,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_playlists_strip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(VoxoraAmber.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = null,
                    tint = VoxoraAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Playlists & Watch Later",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$playlistCount collections · $totalEntries saved videos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                contentDescription = "Open playlists",
                tint = VoxoraAmberBright,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CinemaSectionHeader(
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
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        TextButton(onClick = onActionClick) {
            Text(
                text = actionLabel,
                color = VoxoraAmberBright,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
