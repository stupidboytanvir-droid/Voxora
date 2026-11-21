package com.voxora.player.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.voxora.player.R
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.AudioTrackItem
import com.voxora.player.model.MusicRepeatMode
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.player.MusicPlayerState
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraObsidian
import com.voxora.player.ui.theme.VoxoraSunsetAmber
import com.voxora.player.ui.theme.VoxoraSunsetBrush
import com.voxora.player.ui.theme.VoxoraSunsetCoral
import com.voxora.player.ui.theme.VoxoraSunsetHorizontalBrush
import com.voxora.player.ui.theme.VoxoraSunsetOrange
import com.voxora.player.util.FormatUtils

/**
 * Album artwork square with automatic Sunset Amber-to-Coral waveform placeholder fallback.
 */
@Composable
fun MusicAlbumArtwork(
    albumArtUri: String?,
    contentDescription: String,
    cornerRadius: Dp = 12.dp,
    iconSize: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadFailed by remember(albumArtUri) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        VoxoraSunsetAmber.copy(alpha = 0.28f),
                        VoxoraSunsetOrange.copy(alpha = 0.22f),
                        VoxoraSunsetCoral.copy(alpha = 0.26f),
                        VoxoraElevatedCharcoal
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = VoxoraAmberBright.copy(alpha = 0.85f),
            modifier = Modifier.size(iconSize)
        )
        if (!albumArtUri.isNullOrBlank() && !loadFailed) {
            AsyncImage(
                model = remember(albumArtUri) {
                    ImageRequest.Builder(context)
                        .data(Uri.parse(albumArtUri))
                        .crossfade(200)
                        .build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Compact glassmorphic Floating Mini-Player Bar docked neatly above the bottom navigation dock
 * whenever local audio is active/playing.
 */
@Composable
fun FloatingMusicMiniPlayer(
    playerState: MusicPlayerState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack ?: return

    Surface(
        modifier = modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onExpand)
            .testTag("music_mini_player_bar"),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF011131B),
        border = BorderStroke(0.85.dp, VoxoraSunsetOrange.copy(alpha = 0.38f)),
        shadowElevation = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top thin Sunset Amber-to-Coral progress line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(playerState.progressFraction)
                        .background(VoxoraSunsetHorizontalBrush)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MusicAlbumArtwork(
                    albumArtUri = track.albumArtUri,
                    contentDescription = track.title,
                    cornerRadius = 11.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.size(44.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    Text(
                        text = "${track.artist} · ${track.formatBadge}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA0A7B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(VoxoraSunsetBrush)
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("mini_player_play_pause"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play_pause),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("mini_player_next")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.cd_music_next),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("mini_player_close")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close music player",
                        tint = Color(0xFF98A0B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Full-Screen Now Playing Sheet:
 * Large rounded album art over a glowing Sunset Amber-to-Coral obsidian ambient backdrop,
 * seekbar, Shuffle, Repeat (Off/All/One), Sleep Timer, 200% Volume Booster & 5-Band Equalizer,
 * and Up Next Queue drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenNowPlayingSheet(
    visible: Boolean,
    playerState: MusicPlayerState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onVolumeBoostChange: (Int) -> Unit,
    onSelectEqPreset: (AudioEqPreset) -> Unit,
    onSelectSleepTimer: (SleepTimerOption) -> Unit
) {
    var showAudioFxSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible && playerState.currentTrack != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val track = playerState.currentTrack ?: return@AnimatedVisibility
        BackHandler(enabled = true) { onDismiss() }

        var isScrubbing by remember { mutableStateOf(false) }
        var scrubFraction by remember { mutableFloatStateOf(0f) }
        val safeDuration = playerState.durationMs.coerceAtLeast(1L)
        val sliderValue = if (isScrubbing) scrubFraction else playerState.progressFraction
        val displayPositionMs = if (isScrubbing) (scrubFraction * safeDuration).toLong() else playerState.currentPositionMs

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VoxoraObsidian)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VoxoraSunsetOrange.copy(alpha = 0.28f),
                            VoxoraSunsetCoral.copy(alpha = 0.14f),
                            VoxoraObsidian
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("music_now_playing_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_collapse")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize player",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            ),
                            color = VoxoraAmberBright
                        )
                        Text(
                            text = track.folderName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { showQueueSheet = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_queue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = "Up next queue",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.6f))

                // Large Rounded Album Art with subtle Sunset Border Glow
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .widthIn(max = 320.dp)
                        .aspectRatio(1f)
                        .border(
                            width = 1.2.dp,
                            brush = VoxoraSunsetBrush,
                            shape = RoundedCornerShape(28.dp)
                        )
                ) {
                    MusicAlbumArtwork(
                        albumArtUri = track.albumArtUri,
                        contentDescription = track.title,
                        cornerRadius = 28.dp,
                        iconSize = 84.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.weight(0.6f))

                // Track Metadata + Format Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${track.artist} · ${track.album}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(VoxoraSunsetBrush)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = track.formatBadge,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Sunset Seekbar
                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        isScrubbing = true
                        scrubFraction = value
                    },
                    onValueChangeFinished = {
                        onSeekTo((scrubFraction * safeDuration).toLong())
                        isScrubbing = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraSunsetCoral,
                        activeTrackColor = VoxoraSunsetOrange,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("now_playing_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = FormatUtils.formatDuration(displayPositionMs),
                        style = TabularTimeStyle,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Text(
                        text = FormatUtils.formatDuration(playerState.durationMs),
                        style = TabularTimeStyle,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Primary Transport Controls: Shuffle | Prev | Play/Pause | Next | Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_shuffle")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.cd_music_shuffle),
                            tint = if (playerState.shuffleEnabled) VoxoraSunsetOrange else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("now_playing_prev")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.cd_music_prev),
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(VoxoraSunsetBrush)
                            .clickable(onClick = onTogglePlayPause)
                            .testTag("now_playing_play_pause"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.cd_play_pause),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("now_playing_next")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.cd_music_next),
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = onCycleRepeat,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_repeat")
                    ) {
                        val repeatIcon = if (playerState.repeatMode == MusicRepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        }
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = stringResource(R.string.cd_music_repeat),
                            tint = if (playerState.repeatMode != MusicRepeatMode.OFF) VoxoraSunsetCoral else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Bottom Pro Quick Action Pills: 200% Boost & EQ | Sleep Timer | Queue
                val fxActive = playerState.volumeBoostPercent > 0 || playerState.eqPreset != AudioEqPreset.NORMAL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = { showAudioFxSheet = true },
                        color = if (fxActive) VoxoraSunsetOrange.copy(alpha = 0.2f) else VoxoraCardCharcoal,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            0.85.dp,
                            if (fxActive) VoxoraSunsetOrange else VoxoraHairlineBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("now_playing_audio_fx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Equalizer,
                                contentDescription = null,
                                tint = if (fxActive) VoxoraAmberBright else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (playerState.volumeBoostPercent > 0) {
                                    "Boost +${playerState.volumeBoostPercent}%"
                                } else {
                                    "EQ & Boost"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        onClick = { showSleepTimerSheet = true },
                        color = if (playerState.sleepTimer.isActive) VoxoraSunsetCoral.copy(alpha = 0.2f) else VoxoraCardCharcoal,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            0.85.dp,
                            if (playerState.sleepTimer.isActive) VoxoraSunsetCoral else VoxoraHairlineBorder
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("now_playing_sleep_timer")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bedtime,
                                contentDescription = null,
                                tint = if (playerState.sleepTimer.isActive) VoxoraAmberBright else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (playerState.sleepTimer.isActive) {
                                    "Sleep ${playerState.sleepTimer.badgeText()}"
                                } else {
                                    "Sleep Timer"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (showAudioFxSheet) {
            AudioFxSheet(
                volumeBoostPercent = playerState.volumeBoostPercent,
                selectedPreset = playerState.eqPreset,
                onVolumeBoostChange = onVolumeBoostChange,
                onSelectPreset = onSelectEqPreset,
                onDismiss = { showAudioFxSheet = false }
            )
        }

        if (showSleepTimerSheet) {
            SleepTimerSheet(
                state = playerState.sleepTimer,
                onSelectOption = onSelectSleepTimer,
                onDismiss = { showSleepTimerSheet = false }
            )
        }

        if (showQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                containerColor = VoxoraCharcoal,
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    Text(
                        text = "Up Next (${playerState.queue.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(playerState.queue, key = { idx, item -> "${idx}_${item.id}" }) { idx, item ->
                            val isCurrent = idx == playerState.currentIndex
                            Surface(
                                onClick = {
                                    onSelectQueueIndex(idx)
                                    showQueueSheet = false
                                },
                                color = if (isCurrent) VoxoraSunsetOrange.copy(alpha = 0.16f) else VoxoraCardCharcoal,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    0.8.dp,
                                    if (isCurrent) VoxoraSunsetOrange else VoxoraHairlineBorder
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    MusicAlbumArtwork(
                                        albumArtUri = item.albumArtUri,
                                        contentDescription = item.title,
                                        cornerRadius = 8.dp,
                                        iconSize = 18.dp,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isCurrent) VoxoraAmberBright else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFA0A7B8),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = "Now playing",
                                            tint = VoxoraSunsetCoral,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Text(
                                            text = FormatUtils.formatDuration(item.durationMs),
                                            style = TabularTimeStyle,
                                            color = Color(0xFF98A0B8)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
