package com.voxora.player.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.voxora.player.model.MediaPermissionState
import com.voxora.player.model.PlaybackHistoryItem
import com.voxora.player.model.VideoFolder
import com.voxora.player.model.VideoItem
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils

@Composable
fun VideoThumbnail(
    uriString: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    frameTimeMs: Long = 1_500L
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(28.dp)
        )
        if (!uriString.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Uri.parse(uriString))
                    .videoFrameMillis(frameTimeMs)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    item: PlaybackHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = modifier
            .width(248.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("continue_watching_card_${item.uriString.hashCode()}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                VideoThumbnail(
                    uriString = item.uriString,
                    contentDescription = item.title,
                    frameTimeMs = item.lastPositionMs.coerceAtLeast(1_000L),
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xCC09090D))
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(VoxoraElectricPurple.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Time remaining pill
                Surface(
                    color = Color(0xB309090D),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${FormatUtils.formatDuration(item.lastPositionMs)} / ${FormatUtils.formatDuration(item.durationMs)}",
                        style = TabularTimeStyle,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Bottom progress track
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progressFraction)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(VoxoraElectricPurple, VoxoraSubtleBlue)
                                )
                            )
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.sizeBytes > 0L) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = FormatUtils.formatFileSize(item.sizeBytes),
                            style = TabularTimeStyle,
                            color = voxoraColors.blueHighlight
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoRowCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lastPositionMs: Long? = null
) {
    val voxoraColors = LocalVoxoraColors.current
    val resolution = FormatUtils.formatResolution(video.width, video.height)
    val sizeText = FormatUtils.formatFileSize(video.sizeBytes)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("video_item_${video.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(124.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                VideoThumbnail(
                    uriString = video.uriString,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize()
                )
                if (video.durationMs > 0L) {
                    Surface(
                        color = Color(0xCC09090D),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(video.durationMs),
                            style = TabularTimeStyle,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (lastPositionMs != null && lastPositionMs > 2_000L && video.durationMs > 0L) {
                    val progress = (lastPositionMs.toFloat() / video.durationMs.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(VoxoraElectricPurple)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (resolution != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = resolution,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = video.folderName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (sizeText.isNotBlank()) {
                        Text(
                            text = sizeText,
                            style = TabularTimeStyle,
                            color = voxoraColors.textMuted
                        )
                    }
                    Text(
                        text = video.displayName.substringAfterLast('.', "").uppercase(),
                        style = TabularTimeStyle,
                        color = voxoraColors.blueHighlight
                    )
                }
            }
        }
    }
}

@Composable
fun FolderCard(
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
            .testTag("folder_card_${folder.bucketId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (folder.previewUriString != null) {
                    VideoThumbnail(
                        uriString = folder.previewUriString,
                        contentDescription = folder.name,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x7309090D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = folder.path.ifBlank { "Device Storage" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "${folder.videoCount} ${if (folder.videoCount == 1) "video" else "videos"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (folder.totalSizeBytes > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = FormatUtils.formatFileSize(folder.totalSizeBytes),
                        style = TabularTimeStyle,
                        color = voxoraColors.textMuted
                    )
                }
            }
        }
    }
}

@Composable
fun MediaPermissionBanner(
    permissionState: MediaPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSafVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (permissionState == MediaPermissionState.FULL_ACCESS) return
    val voxoraColors = LocalVoxoraColors.current
    val isPartial = permissionState == MediaPermissionState.PARTIAL_ACCESS

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    VoxoraElectricPurple.copy(alpha = 0.6f),
                    VoxoraSubtleBlue.copy(alpha = 0.5f)
                )
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isPartial) "Partial Video Access Active" else "Access Local Videos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isPartial) {
                    "VOXORA currently sees only the videos you selected. Grant full video access or manage your selection anytime."
                } else {
                    "Allow VOXORA to scan your local video folders via scoped storage, or open any individual video without library permission."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grant_permission_button")
                ) {
                    Text(if (isPartial) "Manage Access" else "Grant Access")
                }
                OutlinedButton(
                    onClick = onOpenSafVideo,
                    border = BorderStroke(1.dp, voxoraColors.cardBorder),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_saf_video_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open File")
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryView(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("empty_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Refresh")
            }
            Button(
                onClick = onOpenFile,
                modifier = Modifier.testTag("empty_open_file_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick Video")
            }
        }
    }
}
