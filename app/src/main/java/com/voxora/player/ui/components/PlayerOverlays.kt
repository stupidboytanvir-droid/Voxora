package com.voxora.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.R
import com.voxora.player.dubbing.DubbingCapabilityStatus
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.BatterySaverEvaluation
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.BatteryStatus
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.model.UserPreferences
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils

@Composable
fun BoxScope.SubtitleOverlay(
    subtitleText: String?,
    fontSizeSp: Float,
    bottomPaddingFraction: Float,
    backgroundOpacity: Float,
    animationsEnabled: Boolean = true
) {
    val overlayModifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(
            start = 24.dp,
            end = 24.dp,
            bottom = (bottomPaddingFraction * 320f).dp.coerceAtLeast(16.dp)
        )
    val isVisible = !subtitleText.isNullOrBlank()
    if (animationsEnabled) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = overlayModifier
        ) {
            SubtitleSurface(
                subtitleText = subtitleText.orEmpty(),
                fontSizeSp = fontSizeSp,
                backgroundOpacity = backgroundOpacity
            )
        }
    } else if (isVisible) {
        Box(modifier = overlayModifier) {
            SubtitleSurface(
                subtitleText = subtitleText.orEmpty(),
                fontSizeSp = fontSizeSp,
                backgroundOpacity = backgroundOpacity
            )
        }
    }
}

@Composable
private fun SubtitleSurface(
    subtitleText: String,
    fontSizeSp: Float,
    backgroundOpacity: Float
) {
    Surface(
        color = Color.Black.copy(alpha = backgroundOpacity.coerceIn(0f, 1f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = subtitleText,
            color = Color.White,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.32f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
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
    state: GestureHudState,
    animationsEnabled: Boolean = true
) {
    val isVisible = state !is GestureHudState.Hidden
    if (animationsEnabled) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            GestureHudContent(state = state)
        }
    } else if (isVisible) {
        Box(modifier = Modifier.align(Alignment.Center)) {
            GestureHudContent(state = state)
        }
    }
}

@Composable
private fun GestureHudContent(state: GestureHudState) {
    when (state) {
        is GestureHudState.Brightness -> {
            VerticalLevelHud(
                icon = Icons.Rounded.BrightnessHigh,
                label = "${(state.fraction * 100).toInt()}%",
                fraction = state.fraction,
                accentColor = VoxoraSubtleBlue
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
                color = Color(0xCC09090D),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.5f))
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
                        color = VoxoraSubtleBlue
                    )
                }
            }
        }
        is GestureHudState.DoubleTapSeek -> {
            Surface(
                color = Color(0xCC09090D),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (state.isForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                        contentDescription = null,
                        tint = VoxoraElectricPurpleBright
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

@Composable
private fun VerticalLevelHud(
    icon: ImageVector,
    label: String,
    fraction: Float,
    accentColor: Color
) {
    Surface(
        color = Color(0xCC09090D),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
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
                    .background(Color.White.copy(alpha = 0.18f)),
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
    animationsEnabled: Boolean = true,
    batterySavingActive: Boolean = false,
    batteryCapRefreshRate: Boolean = false,
    batteryPercent: Int = 100,
    aiDubbingActive: Boolean = false,
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
    onOpenAiDubbing: () -> Unit,
    onOpenBatterySaver: () -> Unit = {},
    onStartFromBeginning: () -> Unit,
    onDismissResumeBanner: () -> Unit,
    onUserInteraction: () -> Unit
) {
    // Locked state shows only subtle unlock pill when tapped
    if (isLocked) {
        val lockedPill: @Composable () -> Unit = {
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
                    color = Color(0xCC121218),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .testTag("player_unlock_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.cd_unlock_screen),
                            tint = VoxoraElectricPurpleBright,
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
        if (animationsEnabled) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                lockedPill()
            }
        } else if (visible) {
            lockedPill()
        }
        return
    }

    val controlsContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB809090D),
                            Color(0x2609090D),
                            Color(0x2609090D),
                            Color(0xD909090D)
                        )
                    )
                )
                .safeDrawingPadding()
        ) {
            // Top Bar
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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

                    // Dedicated AI Dubbing control (Phase 1: English -> Bengali Studio)
                    Surface(
                        onClick = {
                            onUserInteraction()
                            onOpenAiDubbing()
                        },
                        color = if (aiDubbingActive) Color(0xE6341E66) else Color(0xCC241942),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            width = if (aiDubbingActive) 1.5.dp else 1.dp,
                            brush = Brush.horizontalGradient(listOf(VoxoraElectricPurple, VoxoraSubtleBlue))
                        ),
                        modifier = Modifier.testTag("player_ai_dubbing_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = stringResource(R.string.cd_ai_dubbing),
                                tint = if (aiDubbingActive) VoxoraElectricPurpleBright else VoxoraSubtleBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (aiDubbingActive) "EN → বাংলা" else "AI Dubbing",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }
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

                    IconButton(
                        onClick = {
                            onUserInteraction()
                            onOpenSubtitles()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("player_subtitles_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Subtitles,
                            contentDescription = stringResource(R.string.cd_subtitles),
                            tint = Color.White
                        )
                    }
                }

                if (batterySavingActive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val saverTags = buildList {
                        if (batteryCapRefreshRate) add("60Hz")
                        if (!animationsEnabled) add("Animations Off")
                    }.joinToString(" · ")
                    val badgeLabel = if (saverTags.isNotEmpty()) {
                        "Battery Saver ($batteryPercent%) · $saverTags"
                    } else {
                        "Battery Saver ($batteryPercent%)"
                    }
                    Surface(
                        onClick = {
                            onUserInteraction()
                            onOpenBatterySaver()
                        },
                        color = Color(0xCC0D241C),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.55f)),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("player_battery_saver_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BatterySaver,
                                contentDescription = stringResource(R.string.cd_battery_saver),
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                if (resumedFromMs != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = Color(0xCC121218),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.5f)),
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
                                Text("Start Over", color = VoxoraSubtleBlue)
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
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                IconButton(
                    onClick = {
                        onUserInteraction()
                        onRewind()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0x6609090D))
                        .testTag("player_rewind_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay10,
                        contentDescription = stringResource(R.string.cd_rewind),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(
                    onClick = {
                        onUserInteraction()
                        onTogglePlayPause()
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(VoxoraElectricPurple, Color(0xFF6366F1)))
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
                        .background(Color(0x6609090D))
                        .testTag("player_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forward10,
                        contentDescription = stringResource(R.string.cd_forward),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Bottom Bar: Seekbar + Action Strip
            var isScrubbing by remember { mutableStateOf(false) }
            var scrubFraction by remember { mutableFloatStateOf(0f) }
            val safeDuration = durationMs.coerceAtLeast(1L)
            val displayPos = if (isScrubbing) {
                (scrubFraction * safeDuration).toLong()
            } else {
                currentPositionMs
            }
            val sliderValue = if (isScrubbing) {
                scrubFraction
            } else {
                (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
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
                            thumbColor = VoxoraElectricPurpleBright,
                            activeTrackColor = VoxoraElectricPurple,
                            inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                            .testTag("player_seek_bar")
                    )
                    Text(
                        text = FormatUtils.formatDuration(durationMs),
                        style = TabularTimeStyle,
                        color = Color.White.copy(alpha = 0.85f)
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
                                tint = VoxoraSubtleBlue,
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
                        IconButton(
                            onClick = {
                                onUserInteraction()
                                onOpenBatterySaver()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_battery_saver_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BatterySaver,
                                contentDescription = stringResource(R.string.cd_battery_saver),
                                tint = if (batterySavingActive) Color(0xFF4ADE80) else Color.White
                            )
                        }

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

    if (animationsEnabled) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            controlsContent()
        }
    } else if (visible) {
        controlsContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSettingsSheet(
    tracks: List<SubtitleTrackInfo>,
    fontSizeSp: Float,
    bottomPaddingFraction: Float,
    syncOffsetMs: Long,
    onSelectTrack: (SubtitleTrackInfo) -> Unit,
    onDisableSubtitles: () -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onPositionChange: (Float) -> Unit,
    onAdjustSync: (Long) -> Unit,
    onResetSync: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtitles & Sync", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = onPickExternalSubtitle,
                    border = BorderStroke(1.dp, VoxoraElectricPurple),
                    modifier = Modifier.testTag("pick_external_subtitle_button")
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Load .SRT / .VTT")
                }
            }

            // Track Selection
            val anySelected = tracks.any { it.isSelected }
            TrackOptionRow(
                label = "Subtitles Off",
                subtitle = "Disable subtitle display",
                selected = !anySelected,
                icon = Icons.Rounded.SubtitlesOff,
                onClick = onDisableSubtitles
            )

            tracks.forEach { track ->
                TrackOptionRow(
                    label = track.label,
                    subtitle = if (track.isExternal) "External file · Supports millisecond sync" else track.language,
                    selected = track.isSelected,
                    icon = Icons.Rounded.Subtitles,
                    onClick = { onSelectTrack(track) }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            // Font Size
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Text Size", style = MaterialTheme.typography.titleSmall)
                    Text("${fontSizeSp.toInt()} sp", style = TabularTimeStyle, color = VoxoraSubtleBlue)
                }
                Slider(
                    value = fontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = 12f..34f,
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraElectricPurpleBright,
                        activeTrackColor = VoxoraElectricPurple
                    )
                )
            }

            // Position Adjustment
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Vertical Position", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${(bottomPaddingFraction * 100).toInt()}% from bottom",
                        style = TabularTimeStyle,
                        color = VoxoraSubtleBlue
                    )
                }
                Slider(
                    value = bottomPaddingFraction,
                    onValueChange = onPositionChange,
                    valueRange = 0.02f..0.35f,
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraElectricPurpleBright,
                        activeTrackColor = VoxoraElectricPurple
                    )
                )
            }

            // Sync Adjustment (for external SRT/VTT)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Subtitle Sync Delay", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = FormatUtils.formatSyncOffset(syncOffsetMs),
                        style = TabularTimeStyle,
                        color = VoxoraElectricPurpleBright
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAdjustSync(-500L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("-0.5s") }
                    OutlinedButton(
                        onClick = { onAdjustSync(-100L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("-0.1s") }
                    OutlinedButton(
                        onClick = onResetSync,
                        modifier = Modifier.weight(1f)
                    ) { Text("0.0s") }
                    OutlinedButton(
                        onClick = { onAdjustSync(100L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("+0.1s") }
                    OutlinedButton(
                        onClick = { onAdjustSync(500L) },
                        modifier = Modifier.weight(1f)
                    ) { Text("+0.5s") }
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
                    TrackOptionRow(
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
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
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
                    color = VoxoraSubtleBlue
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
fun AiDubbingComingSoonSheet(
    status: DubbingCapabilityStatus,
    plannedLanguages: List<DubbingLanguage>,
    onDismiss: () -> Unit
) {
    val comingSoon = status as? DubbingCapabilityStatus.ComingSoon ?: DubbingCapabilityStatus.ComingSoon()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraSubtleBlue))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("AI Dubbing", style = MaterialTheme.typography.titleLarge)
                        Surface(
                            color = VoxoraElectricPurple.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, VoxoraElectricPurple)
                        ) {
                            Text(
                                text = "COMING SOON",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = VoxoraElectricPurpleBright,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "Play Beyond Language · Architectural Preview",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoxoraSubtleBlue
                    )
                }
            }

            Text(
                text = "VOXORA Version 1.0 focuses on fast, private local playback. Our audio and subtitle pipeline is engineered for seamless integration of live AI dubbing in a future update:",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )

            Surface(
                color = Color(0xFF1A1A24),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    comingSoon.plannedCapabilities.forEach { capability ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(VoxoraSubtleBlue)
                            )
                            Text(
                                text = capability,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            Text(
                text = "Planned Dubbing Languages",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                plannedLanguages.forEach { lang ->
                    Surface(
                        color = Color(0xFF1F1F2C),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Text(
                            text = "${lang.displayName} (${lang.nativeName})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
            ) {
                Text("Got It")
            }
        }
    }
}

@Composable
private fun TrackOptionRow(
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
                color = if (selected) VoxoraElectricPurple else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(14.dp)
            ),
        color = if (selected) VoxoraElectricPurple.copy(alpha = 0.18f) else Color(0xFF1A1A24),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) VoxoraElectricPurpleBright else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
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
                    tint = VoxoraSubtleBlue
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatterySaverSheet(
    preferences: UserPreferences,
    batteryStatus: BatteryStatus,
    evaluation: BatterySaverEvaluation,
    onSelectMode: (BatterySaverMode) -> Unit,
    onToggleCapRefreshRate: (Boolean) -> Unit,
    onToggleDisableAnimations: (Boolean) -> Unit,
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
                .verticalScroll(rememberScrollState())
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatterySaver,
                        contentDescription = null,
                        tint = if (evaluation.isActive) Color(0xFF4ADE80) else VoxoraSubtleBlue,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text("Player Battery Saver", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = buildString {
                                append("Battery: ${batteryStatus.batteryPercent}%")
                                if (batteryStatus.isCharging) append(" · Charging")
                                if (batteryStatus.isSystemPowerSave) append(" · System Saver")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                Surface(
                    color = if (evaluation.isActive) {
                        Color(0xFF4ADE80).copy(alpha = 0.18f)
                    } else {
                        Color.White.copy(alpha = 0.1f)
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (evaluation.isActive) "Active · ${evaluation.reasonLabel}" else evaluation.reasonLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (evaluation.isActive) Color(0xFF4ADE80) else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BatterySaverMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.batterySaverMode == mode,
                        onClick = { onSelectMode(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.testTag("player_battery_mode_${mode.name}")
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Cap Display Refresh Rate (60Hz)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Limits window refresh rate to 60Hz and lowers player UI polling frequency",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f)
                    )
                }
                Switch(
                    checked = preferences.batterySaverCapRefreshRate,
                    onCheckedChange = onToggleCapRefreshRate,
                    modifier = Modifier.testTag("player_battery_cap_refresh_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Disable Non-Essential Animations", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Turns off player overlay, subtitle, and gesture HUD fade animations",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f)
                    )
                }
                Switch(
                    checked = preferences.batterySaverDisableAnimations,
                    onCheckedChange = onToggleDisableAnimations,
                    modifier = Modifier.testTag("player_battery_disable_anims_switch")
                )
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
            ) {
                Text("Done")
            }
        }
    }
}
