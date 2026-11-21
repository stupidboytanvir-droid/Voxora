package com.voxora.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.model.AudioAlbumGroup
import com.voxora.player.model.AudioArtistGroup
import com.voxora.player.model.AudioFolderItem
import com.voxora.player.model.AudioTrackItem
import com.voxora.player.player.MusicPlayerState
import com.voxora.player.ui.components.MusicAlbumArtwork
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraSunsetBrush
import com.voxora.player.ui.theme.VoxoraSunsetCoral
import com.voxora.player.ui.theme.VoxoraSunsetOrange
import com.voxora.player.ui.viewmodel.MusicLibrarySubTab
import com.voxora.player.ui.viewmodel.MusicLibraryUiState
import com.voxora.player.util.FormatUtils

@Composable
fun MusicScreen(
    uiState: MusicLibraryUiState,
    playerState: MusicPlayerState,
    drillDownTracks: List<AudioTrackItem>,
    onRequestAudioPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectSubTab: (MusicLibrarySubTab) -> Unit,
    onOpenFolder: (AudioFolderItem) -> Unit,
    onOpenArtist: (AudioArtistGroup) -> Unit,
    onOpenAlbum: (AudioAlbumGroup) -> Unit,
    onClearDrillDown: () -> Unit,
    onPlayTrackFromList: (List<AudioTrackItem>, Int) -> Unit,
    onShuffleAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isInDrillDown = uiState.selectedFolderId != null ||
        uiState.selectedArtistName != null ||
        uiState.selectedAlbumKey != null

    if (isInDrillDown) {
        BackHandler { onClearDrillDown() }
    }

    val bottomPadding = if (playerState.currentTrack != null) 186.dp else 118.dp

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isInDrillDown) 8.dp else 18.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (isInDrillDown) {
                    IconButton(
                        onClick = onClearDrillDown,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("music_drilldown_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to music library",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                Column {
                    val headerTitle = when {
                        uiState.selectedFolderName != null -> uiState.selectedFolderName
                        uiState.selectedArtistName != null -> uiState.selectedArtistName
                        uiState.selectedAlbumName != null -> uiState.selectedAlbumName
                        else -> "Music"
                    }
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isInDrillDown) {
                            "${drillDownTracks.size} tracks"
                        } else {
                            "${uiState.allTracks.size} local songs & audio files"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.allTracks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(VoxoraSunsetBrush)
                            .clickable(onClick = onShuffleAll)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("music_shuffle_all_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Shuffle",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("music_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh music library",
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

        if (!uiState.hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = VoxoraCardCharcoal),
                    border = BorderStroke(0.9.dp, VoxoraSunsetOrange.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(VoxoraSunsetBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Access Local Music & Audio",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Grant audio library access so VOXORA can scan your local MP3, FLAC, M4A, and WAV songs for gapless playback with 200% Booster & 5-Band EQ.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onRequestAudioPermission,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VoxoraSunsetOrange,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("music_grant_permission_button")
                        ) {
                            Text("Allow Music Access", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            return
        }

        // Search Bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search songs, artists, albums, folders...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = VoxoraCardCharcoal,
                unfocusedContainerColor = VoxoraCardCharcoal,
                focusedBorderColor = VoxoraSunsetOrange,
                unfocusedBorderColor = VoxoraHairlineBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("music_search_input")
        )

        // Sub-Tabs: All Songs | Folders | Artists | Albums
        if (!isInDrillDown) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(MusicLibrarySubTab.entries) { tab ->
                    val selected = tab == uiState.selectedTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(VoxoraSunsetBrush)
                                } else {
                                    Modifier.background(VoxoraCardCharcoal)
                                }
                            )
                            .clickable { onSelectSubTab(tab) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("music_subtab_${tab.name}")
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Content List
        if (isInDrillDown) {
            TrackLazyList(
                tracks = drillDownTracks,
                activeTrackId = playerState.currentTrack?.id,
                isPlaying = playerState.isPlaying,
                bottomPadding = bottomPadding,
                onPlay = { idx -> onPlayTrackFromList(drillDownTracks, idx) }
            )
        } else {
            when (uiState.selectedTab) {
                MusicLibrarySubTab.ALL_SONGS -> {
                    if (uiState.filteredTracks.isEmpty() && !uiState.isScanning) {
                        EmptyMusicState(onRefresh = onRefresh)
                    } else {
                        TrackLazyList(
                            tracks = uiState.filteredTracks,
                            activeTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = bottomPadding,
                            onPlay = { idx -> onPlayTrackFromList(uiState.filteredTracks, idx) }
                        )
                    }
                }
                MusicLibrarySubTab.FOLDERS -> {
                    if (uiState.folders.isEmpty()) {
                        EmptyMusicState(onRefresh = onRefresh)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.folders, key = { it.folderId }) { folder ->
                                MusicGroupRowCard(
                                    title = folder.name,
                                    subtitle = "${folder.trackCount} songs · ${FormatUtils.formatDuration(folder.totalDurationMs)}",
                                    albumArtUri = folder.previewAlbumArtUri,
                                    fallbackIcon = Icons.Rounded.Folder,
                                    onClick = { onOpenFolder(folder) }
                                )
                            }
                        }
                    }
                }
                MusicLibrarySubTab.ARTISTS -> {
                    if (uiState.artists.isEmpty()) {
                        EmptyMusicState(onRefresh = onRefresh)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.artists, key = { it.name }) { artist ->
                                MusicGroupRowCard(
                                    title = artist.name,
                                    subtitle = "${artist.trackCount} songs · ${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}",
                                    albumArtUri = artist.previewAlbumArtUri,
                                    fallbackIcon = Icons.Rounded.Person,
                                    onClick = { onOpenArtist(artist) }
                                )
                            }
                        }
                    }
                }
                MusicLibrarySubTab.ALBUMS -> {
                    if (uiState.albums.isEmpty()) {
                        EmptyMusicState(onRefresh = onRefresh)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.albums, key = { "${it.albumId}_${it.name}" }) { album ->
                                MusicGroupRowCard(
                                    title = album.name,
                                    subtitle = "${album.artist} · ${album.trackCount} tracks",
                                    albumArtUri = album.albumArtUri,
                                    fallbackIcon = Icons.Rounded.Album,
                                    onClick = { onOpenAlbum(album) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackLazyList(
    tracks: List<AudioTrackItem>,
    activeTrackId: Long?,
    isPlaying: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPlay: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { idx, track ->
            val isCurrent = track.id == activeTrackId
            Surface(
                onClick = { onPlay(idx) },
                color = if (isCurrent) VoxoraSunsetOrange.copy(alpha = 0.14f) else VoxoraCardCharcoal,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    0.85.dp,
                    if (isCurrent) VoxoraSunsetOrange.copy(alpha = 0.7f) else VoxoraHairlineBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("music_track_row_${track.id}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MusicAlbumArtwork(
                        albumArtUri = track.albumArtUri,
                        contentDescription = track.title,
                        cornerRadius = 11.dp,
                        iconSize = 22.dp,
                        modifier = Modifier.size(50.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            color = if (isCurrent) VoxoraAmberBright else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                color = if (isCurrent) VoxoraSunsetCoral.copy(alpha = 0.24f) else VoxoraElevatedCharcoal,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    0.6.dp,
                                    if (isCurrent) VoxoraSunsetCoral else VoxoraHairlineBorder
                                )
                            ) {
                                Text(
                                    text = track.formatBadge,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (isCurrent) Color.White else VoxoraAmberBright,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isCurrent && isPlaying) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Playing",
                                tint = VoxoraSunsetCoral,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = FormatUtils.formatDuration(track.durationMs),
                            style = TabularTimeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicGroupRowCard(
    title: String,
    subtitle: String,
    albumArtUri: String?,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = VoxoraCardCharcoal,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!albumArtUri.isNullOrBlank()) {
                MusicAlbumArtwork(
                    albumArtUri = albumArtUri,
                    contentDescription = title,
                    cornerRadius = 12.dp,
                    iconSize = 22.dp,
                    modifier = Modifier.size(50.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VoxoraSunsetOrange.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = VoxoraAmberBright,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyMusicState(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VoxoraSunsetOrange.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    tint = VoxoraSunsetOrange,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "No Local Audio Found",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Songs, podcasts, and extracted audio files on your device will appear here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoxoraSunsetOrange,
                    contentColor = Color.White
                )
            ) {
                Text("Rescan Audio")
            }
        }
    }
}
