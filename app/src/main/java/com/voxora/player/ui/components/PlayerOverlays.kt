package com.voxora.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.R
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.SleepTimerOption
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraGlassBorder
import com.voxora.player.ui.theme.VoxoraGlassDark
import com.voxora.player.ui.viewmodel.SleepTimerUiState
import com.voxora.player.util.FormatUtils
import kotlin.math.roundToInt

/**
 * Draggable, customizable Subtitle Overlay positioned dynamically from 5% (Top) to 95% (Bottom).
 * Users can drag directly on the subtitle box vertically to reposition it smoothly on screen.
 */
@Composable
fun DraggableSubtitleOverlay(
    subtitleText: String?,
    fontSizeSp: Float,
    verticalPositionFraction: Float,
    textColor: SubtitleTextColor,
    backgroundOpacity: Float,
    isLocked: Boolean,
    onVerticalPositionChange: (Float) -> Unit
) {
    val isVisible = !subtitleText.isNullOrBlank()
    if (!isVisible) return

    var isDragging by remember { mutableStateOf(false) }
    var liveFraction by remember(verticalPositionFraction) {
        mutableFloatStateOf(verticalPositionFraction.coerceIn(0.05f, 0.95f))
    }
    val currentOnPositionChange by rememberUpdatedState(onVerticalPositionChange)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val estimatedBoxHeightPx = with(density) { 80.dp.toPx() }
        val usableHeightPx = (totalHeightPx - estimatedBoxHeightPx).coerceAtLeast(1f)
        val displayFraction = if (isDragging) liveFraction else verticalPositionFraction.coerceIn(0.05f, 0.95f)
        val offsetYPx = (usableHeightPx * displayFraction).roundToInt()

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, offsetYPx) }
                .padding(horizontal = 24.dp)
                .pointerInput(isLocked, usableHeightPx) {
                    if (isLocked) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            liveFraction = verticalPositionFraction.coerceIn(0.05f, 0.95f)
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnPositionChange(liveFraction)
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val deltaFraction = dragAmount / usableHeightPx
                        liveFraction = (liveFraction + deltaFraction).coerceIn(0.05f, 0.95f)
                        currentOnPositionChange(liveFraction)
                    }
                }
                .testTag("player_subtitle_box"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isDragging) {
                Surface(
                    color = VoxoraGlassDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "Position ${(liveFraction * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoxoraCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Surface(
                color = Color.Black.copy(alpha = backgroundOpacity.coerceIn(0f, 1f)),
                shape = RoundedCornerShape(12.dp),
                border = if (isDragging) BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.8f)) else null
            ) {
                Text(
                    text = subtitleText.orEmpty(),
                    color = Color(textColor.colorHex),
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.32f).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }
    }
}

/**
 * Sleek top pill indicator shown while Press & Hold Fast-Forward (2.0x / 3.0x) is active.
 */
@Composable
fun BoxScope.HoldSpeedIndicatorPill(
    visible: Boolean,
    speed: Float
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .safeDrawingPadding()
            .padding(top = 18.dp)
    ) {
        Surface(
            color = VoxoraGlassDark,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(VoxoraElectricPurple, VoxoraCyan))
            ),
            modifier = Modifier.testTag("player_hold_speed_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = VoxoraCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${String.format(java.util.Locale.US, "%.1f", speed)}x Speed >>",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = if (speed < 2.5f) "· Slide up for 3.0x" else "· Slide down for 2.0x",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

sealed interface GestureHudState {
    data object Hidden : GestureHudState
    data class Brightness(val fraction: Float) : GestureHudState
    data class Volume(val fraction: Float, val percent: Int) : GestureHudState
    data class SeekPreview(val targetMs: Long, val deltaMs: Long, val durationMs: Long) : GestureHudState
    data class DoubleTapSeek(val isForward: Boolean, val seconds: Int) : GestureHudState
}

@Composable
fun BoxScope.GestureHudOverlay(
    state: GestureHudState
) {
    AnimatedVisibility(
        visible = state !is GestureHudState.Hidden,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        when (state) {
            is GestureHudState.Brightness -> {
                VerticalLevelHud(
                    icon = Icons.Rounded.BrightnessHigh,
                    label = "${(state.fraction * 100).toInt()}%",
                    fraction = state.fraction,
                    accentColor = VoxoraCyan
                )
            }
            is GestureHudState.Volume -> {
                VerticalLevelHud(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    label = "${state.percent}%",
                    fraction = state.fraction,
                    accentColor = VoxoraElectricPurpleBright
                )
            }
            is GestureHudState.SeekPreview -> {
                Surface(
                    color = VoxoraGlassDark,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, VoxoraGlassBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val sign = if (state.deltaMs >= 0) "+" else "-"
                        val deltaSec = kotlin.math.abs(state.deltaMs / 1000L)
                        Text(
                            text = FormatUtils.formatDuration(state.targetMs),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = TabularTimeStyle.fontFamily
                            ),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$sign${deltaSec}s  •  ${FormatUtils.formatDuration(state.durationMs)}",
                            style = TabularTimeStyle,
                            color = VoxoraCyan
                        )
                    }
                }
            }
            is GestureHudState.DoubleTapSeek -> {
                Surface(
                    color = VoxoraGlassDark,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                            contentDescription = null,
                            tint = VoxoraCyan
                        )
                        Text(
                            text = if (state.isForward) "+${state.seconds}s" else "-${state.seconds}s",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }
            GestureHudState.Hidden -> Unit
        }
    }
}

@Composable
private fun VerticalLevelHud(
    icon: ImageVector,
    label: String,
    fraction: Float,
    accentColor: Color
) {
    Surface(
        color = VoxoraGlassDark,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, VoxoraGlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(110.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                style = TabularTimeStyle,
                color = Color.White
            )
        }
    }
}

@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    isLocked: Boolean,
    title: String,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    aspectRatioMode: AspectRatioMode,
    isFullScreen: Boolean,
    resumedFromMs: Long?,
    autoSubtitleActive: Boolean,
    autoSubtitleBadgeText: String,
    sleepTimerState: SleepTimerUiState,
    canEnterPip: Boolean,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onRotateScreen: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenAspectRatio: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onEnterPip: () -> Unit,
    onTapAiDubbingComingSoon: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onDismissResumeBanner: () -> Unit,
    onUserInteraction: () -> Unit
) {
    if (isLocked) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp)
            ) {
                Surface(
                    onClick = {
                        onUserInteraction()
                        onToggleLock()
                    },
                    color = VoxoraGlassDark,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .testTag("player_unlock_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.cd_unlock_screen),
                            tint = VoxoraCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Locked · Tap to unlock",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
        return
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xC80B0D12),
                            Color(0x1F0B0D12),
                            Color(0x1F0B0D12),
                            Color(0xDE0B0D12)
                        )
                    )
                )
                .safeDrawingPadding()
        ) {
            // Top Glass Bar
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = Color.White
                        )
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )

                    // Live Auto AI Subtitles Pill
                    Surface(
                        onClick = {
                            onUserInteraction()
                            onOpenSubtitles()
                        },
                        color = if (autoSubtitleActive) VoxoraElectricPurple.copy(alpha = 0.35f) else VoxoraGlassDark,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (autoSubtitleActive) VoxoraCyan else VoxoraGlassBorder
                        ),
                        modifier = Modifier.testTag("player_subtitles_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (autoSubtitleActive) Icons.Rounded.AutoAwesome else Icons.Rounded.Subtitles,
                                contentDescription = stringResource(R.string.cd_auto_subtitles),
                                tint = if (autoSubtitleActive) VoxoraCyan else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = autoSubtitleBadgeText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }
                    }

                    if (canEnterPip) {
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onEnterPip()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureInPictureAlt,
                                contentDescription = stringResource(R.string.cd_pip),
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onUserInteraction()
                            onOpenSleepTimer()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_sleep_timer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bedtime,
                            contentDescription = stringResource(R.string.cd_sleep_timer),
                            tint = if (sleepTimerState.isActive) VoxoraCyan else Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            onUserInteraction()
                            onOpenAudioTracks()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Audiotrack,
                            contentDescription = stringResource(R.string.cd_audio_track),
                            tint = Color.White
                        )
                    }
                }

                // Secondary subtle chips row (Sleep Timer active pill / Locked AI Dubbing Coming Soon pill)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sleepTimerState.isActive) {
                        Surface(
                            onClick = {
                                onUserInteraction()
                                onOpenSleepTimer()
                            },
                            color = VoxoraGlassDark,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bedtime,
                                    contentDescription = null,
                                    tint = VoxoraCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Sleep: ${sleepTimerState.badgeText()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    // Locked "AI Audio Dubbing — Coming Soon" pill
                    Surface(
                        onClick = {
                            onUserInteraction()
                            onTapAiDubbingComingSoon()
                        },
                        color = VoxoraGlassDark,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.testTag("player_ai_dubbing_coming_soon_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = stringResource(R.string.cd_ai_dubbing),
                                tint = VoxoraElectricPurpleBright,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "AI Dubbing · Soon",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                }

                if (resumedFromMs != null) {
                    Surface(
                        color = VoxoraGlassDark,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.45f)),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Resumed from ${FormatUtils.formatDuration(resumedFromMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                            TextButton(
                                onClick = {
                                    onUserInteraction()
                                    onStartFromBeginning()
                                }
                            ) {
                                Text("Start Over", color = VoxoraCyan)
                            }
                            IconButton(
                                onClick = onDismissResumeBanner,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Center Transport Controls
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                IconButton(
                    onClick = {
                        onUserInteraction()
                        onRewind()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(VoxoraGlassDark)
                        .border(1.dp, VoxoraGlassBorder, CircleShape)
                        .testTag("player_rewind_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = stringResource(R.string.cd_rewind),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                IconButton(
                    onClick = {
                        onUserInteraction()
                        onTogglePlayPause()
                    },
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraCyan))
                        )
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play_pause),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = {
                        onUserInteraction()
                        onForward()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(VoxoraGlassDark)
                        .border(1.dp, VoxoraGlassBorder, CircleShape)
                        .testTag("player_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = stringResource(R.string.cd_forward),
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // Bottom Bar: Seekbar + Action Strip (No Battery Optimizer Icon)
            var isScrubbing by remember { mutableStateOf(false) }
            var scrubFraction by remember { mutableFloatStateOf(0f) }
            val safeDuration = durationMs.coerceAtLeast(1L)
            val displayPos = if (isScrubbing) (scrubFraction * safeDuration).toLong() else currentPositionMs
            val sliderValue = if (isScrubbing) {
                scrubFraction
            } else {
                (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = FormatUtils.formatDuration(displayPos),
                        style = TabularTimeStyle,
                        color = Color.White
                    )
                    Slider(
                        value = sliderValue,
                        onValueChange = { value ->
                            onUserInteraction()
                            isScrubbing = true
                            scrubFraction = value
                        },
                        onValueChangeFinished = {
                            onSeekTo((scrubFraction * safeDuration).toLong())
                            isScrubbing = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraCyan,
                            activeTrackColor = VoxoraElectricPurple,
                            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                            .testTag("player_seek_bar")
                    )
                    Text(
                        text = FormatUtils.formatDuration(durationMs),
                        style = TabularTimeStyle,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onToggleLock()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_lock_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = stringResource(R.string.cd_lock_screen),
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onRotateScreen()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_rotate_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ScreenRotation,
                                contentDescription = stringResource(R.string.cd_rotate_screen),
                                tint = Color.White
                            )
                        }
                        TextButton(
                            onClick = {
                                onUserInteraction()
                                onOpenSpeed()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.testTag("player_speed_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = stringResource(R.string.cd_speed),
                                tint = VoxoraCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${playbackSpeed}x",
                                style = TabularTimeStyle,
                                color = Color.White
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                onUserInteraction()
                                onOpenAspectRatio()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.testTag("player_aspect_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AspectRatio,
                                contentDescription = stringResource(R.string.cd_aspect_ratio),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = aspectRatioMode.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onToggleFullScreen()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_fullscreen_button")
                        ) {
                            Icon(
                                imageVector = if (isFullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(
    tracks: List<AudioTrackInfo>,
    onSelectTrack: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Audio Tracks", style = MaterialTheme.typography.titleLarge)
            if (tracks.isEmpty()) {
                Text(
                    text = "No selectable audio tracks reported for this media file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                tracks.forEach { track ->
                    PlayerSheetOptionRow(
                        label = track.label,
                        subtitle = track.language,
                        selected = track.isSelected,
                        icon = Icons.Rounded.Audiotrack,
                        onClick = {
                            onSelectTrack(track)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpeedSelectorSheet(
    currentSpeed: Float,
    holdSpeedMultiplier: Float,
    onSelectSpeed: (Float) -> Unit,
    onSelectHoldSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Playback Speed", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${currentSpeed}x",
                    style = TabularTimeStyle,
                    color = VoxoraCyan
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { speed ->
                    val selected = kotlin.math.abs(currentSpeed - speed) < 0.01f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onSelectSpeed(speed)
                            onDismiss()
                        },
                        label = { Text("${speed}x") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.85f)
                        )
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = VoxoraCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Press & Hold Fast-Forward Speed", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Long-press anywhere on video to boost speed. Slide finger up while holding for 3.0x.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(2.0f, 3.0f).forEach { holdSpd ->
                        FilterChip(
                            selected = kotlin.math.abs(holdSpeedMultiplier - holdSpd) < 0.05f,
                            onClick = { onSelectHoldSpeed(holdSpd) },
                            label = { Text("${holdSpd}x Hold") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VoxoraCyan,
                                selectedLabelColor = Color(0xFF041F29),
                                labelColor = Color.White.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier.testTag("hold_speed_${holdSpd.toInt()}x_chip")
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AspectRatioSheet(
    currentMode: AspectRatioMode,
    onSelectMode: (AspectRatioMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Aspect Ratio", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AspectRatioMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == currentMode,
                        onClick = {
                            onSelectMode(mode)
                            onDismiss()
                        },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.85f)
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerUiState,
    onSelectOption: (SleepTimerOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = VoxoraCyan
                    )
                    Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
                }
                if (state.isActive) {
                    Text(
                        text = state.badgeText(),
                        style = TabularTimeStyle,
                        color = VoxoraCyan
                    )
                }
            }
            Text(
                text = "Automatically pause playback when the timer finishes.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SleepTimerOption.entries.forEach { option ->
                    FilterChip(
                        selected = state.selectedOption == option,
                        onClick = {
                            onSelectOption(option)
                            onDismiss()
                        },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.testTag("sleep_timer_${option.name}")
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSheetOptionRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (selected) VoxoraElectricPurple else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)
            ),
        color = if (selected) VoxoraElectricPurple.copy(alpha = 0.18f) else VoxoraCardCharcoal,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) VoxoraCyan else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = VoxoraCyan
                )
            }
        }
    }
}
