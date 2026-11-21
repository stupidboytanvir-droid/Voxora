package com.voxora.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.model.ABLoopState
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.DisplayFilterSettings
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.viewmodel.SleepTimerUiState

/**
 * MX-Style Translucent Right-Side Slide-Out Glassmorphic Settings Drawer for Video Player.
 * Slides in smoothly from the right edge WITHOUT pausing playback.
 * Organizes all advanced controls into 3 clean tabs: Playback, Audio & Display, and Tools.
 */
@Composable
fun PlayerSettingsDrawer(
    visible: Boolean,
    playbackSpeed: Float,
    abLoop: ABLoopState,
    isBackgroundPlayActive: Boolean,
    sleepTimerState: SleepTimerUiState,
    isFullScreen: Boolean,
    volumeBoostPercent: Int,
    eqPreset: AudioEqPreset,
    displayFilters: DisplayFilterSettings,
    bookmarksCount: Int,
    zoomScale: Float,
    isExtractingAudio: Boolean,
    canEnterPip: Boolean,
    onDismiss: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenABLoop: () -> Unit,
    onToggleBackgroundPlay: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onStepPrevFrame: () -> Unit,
    onStepNextFrame: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onOpenAudioFx: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onToggleEyeCare: (Boolean) -> Unit,
    onToggleMirror: () -> Unit,
    onRotateVideo90: () -> Unit,
    onResetZoom: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onOpenClipCutter: () -> Unit,
    onExtractAudio: () -> Unit,
    onOpenCast: () -> Unit,
    onEnterPip: () -> Unit,
    onEnableKidsLock: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Playback, 1=Audio & Display, 2=Tools

    Box(modifier = Modifier.fillMaxSize()) {
        // Subtle scrim on left to dismiss drawer smoothly without pausing video
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66050608))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Translucent Right-Side Glassmorphic Panel
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(336.dp)
                    .testTag("player_settings_drawer"),
                color = Color(0xEB0B0D14),
                border = BorderStroke(0.9.dp, VoxoraHairlineBorder),
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Player Tools",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "Live controls · playback continues",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.56f)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("player_drawer_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close drawer",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3-Tab Segmented Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF151824))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabs = listOf("Playback", "Audio & View", "Tools")
                        tabs.forEachIndexed { idx, label ->
                            val isSelected = selectedTab == idx
                            Surface(
                                onClick = { selectedTab = idx },
                                shape = RoundedCornerShape(11.dp),
                                color = if (isSelected) VoxoraAmber else Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("player_drawer_tab_$idx")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        ),
                                        color = if (isSelected) Color(0xFF140D00) else Color.White.copy(alpha = 0.72f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tab Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (selectedTab) {
                            0 -> {
                                DrawerActionRow(
                                    icon = Icons.Rounded.Speed,
                                    title = "Playback Speed",
                                    subtitle = "${playbackSpeed}x · Hold-to-speed 2x/3x",
                                    highlighted = kotlin.math.abs(playbackSpeed - 1.0f) > 0.05f,
                                    onClick = {
                                        onDismiss()
                                        onOpenSpeed()
                                    },
                                    testTag = "drawer_speed_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Repeat,
                                    title = "A–B Section Loop",
                                    subtitle = when {
                                        abLoop.isLooping -> "Looping active"
                                        abLoop.pointAMs != null -> "Point A marked"
                                        else -> "Repeat any scene or dialogue"
                                    },
                                    highlighted = abLoop.isLooping || abLoop.pointAMs != null,
                                    onClick = {
                                        onDismiss()
                                        onOpenABLoop()
                                    },
                                    testTag = "drawer_ab_loop_row"
                                )
                                DrawerToggleRow(
                                    icon = Icons.Rounded.Headphones,
                                    title = "Background Audio Play",
                                    subtitle = "Keep listening when app minimizes",
                                    checked = isBackgroundPlayActive,
                                    onCheckedChange = { onToggleBackgroundPlay() },
                                    testTag = "drawer_background_play_toggle"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Bedtime,
                                    title = "Sleep Timer",
                                    subtitle = if (sleepTimerState.isActive) "Active · ${sleepTimerState.badgeText()}" else "Off",
                                    highlighted = sleepTimerState.isActive,
                                    onClick = {
                                        onDismiss()
                                        onOpenSleepTimer()
                                    },
                                    testTag = "drawer_sleep_timer_row"
                                )

                                HorizontalDivider(
                                    color = VoxoraHairlineBorder,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                // Frame-by-Frame Precision Stepper
                                Text(
                                    text = "FRAME STEP",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = VoxoraAmberBright,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DrawerCompactButton(
                                        icon = Icons.Rounded.SkipPrevious,
                                        label = "-1 Frame",
                                        onClick = onStepPrevFrame,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("drawer_prev_frame_btn")
                                    )
                                    DrawerCompactButton(
                                        icon = Icons.Rounded.SkipNext,
                                        label = "+1 Frame",
                                        onClick = onStepNextFrame,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("drawer_next_frame_btn")
                                    )
                                }

                                DrawerToggleRow(
                                    icon = if (isFullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    title = "Immersive Fullscreen",
                                    subtitle = "Hide status & navigation bars",
                                    checked = isFullScreen,
                                    onCheckedChange = { onToggleFullScreen() },
                                    testTag = "drawer_fullscreen_toggle"
                                )
                            }

                            1 -> {
                                DrawerActionRow(
                                    icon = Icons.Rounded.Equalizer,
                                    title = "Audio FX & 200% Booster",
                                    subtitle = "Boost +$volumeBoostPercent% · ${eqPreset.label} EQ",
                                    highlighted = volumeBoostPercent > 0 || eqPreset != AudioEqPreset.NORMAL,
                                    onClick = {
                                        onDismiss()
                                        onOpenAudioFx()
                                    },
                                    testTag = "drawer_audio_fx_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Audiotrack,
                                    title = "Audio Tracks",
                                    subtitle = "Switch embedded multi-language audio",
                                    onClick = {
                                        onDismiss()
                                        onOpenAudioTracks()
                                    },
                                    testTag = "drawer_audio_tracks_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Tune,
                                    title = "Color & Display Filters",
                                    subtitle = "Brightness, Contrast, Saturation & Eye Care",
                                    highlighted = displayFilters.eyeCareEnabled,
                                    onClick = {
                                        onDismiss()
                                        onOpenDisplaySettings()
                                    },
                                    testTag = "drawer_display_filters_row"
                                )
                                DrawerToggleRow(
                                    icon = Icons.Rounded.Visibility,
                                    title = "Eye Care Night Filter",
                                    subtitle = "Warm blue-light screen tint",
                                    checked = displayFilters.eyeCareEnabled,
                                    onCheckedChange = onToggleEyeCare,
                                    testTag = "drawer_eye_care_toggle"
                                )
                                DrawerToggleRow(
                                    icon = Icons.Rounded.Flip,
                                    title = "Mirror / Flip Horizontal",
                                    subtitle = "Flip video horizontally in real time",
                                    checked = displayFilters.isMirrored,
                                    onCheckedChange = { onToggleMirror() },
                                    testTag = "drawer_mirror_toggle"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Rotate90DegreesCw,
                                    title = "Rotate Video 90°",
                                    subtitle = "Current rotation: ${displayFilters.manualRotationDegrees}°",
                                    highlighted = displayFilters.manualRotationDegrees != 0,
                                    onClick = onRotateVideo90,
                                    testTag = "drawer_rotate_90_row"
                                )
                                if (zoomScale > 1.02f) {
                                    DrawerActionRow(
                                        icon = Icons.Rounded.ZoomOutMap,
                                        title = "Reset Pinch Zoom",
                                        subtitle = "Currently zoomed to ${String.format(java.util.Locale.US, "%.1f", zoomScale)}x",
                                        highlighted = true,
                                        onClick = onResetZoom,
                                        testTag = "drawer_reset_zoom_row"
                                    )
                                }
                            }

                            else -> {
                                DrawerActionRow(
                                    icon = Icons.Rounded.Bookmark,
                                    title = "Scene Bookmarks",
                                    subtitle = if (bookmarksCount > 0) "$bookmarksCount saved moments" else "Save timestamps with notes",
                                    highlighted = bookmarksCount > 0,
                                    onClick = {
                                        onDismiss()
                                        onOpenBookmarks()
                                    },
                                    testTag = "drawer_bookmarks_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.PhotoCamera,
                                    title = "Instant Screenshot",
                                    subtitle = "Capture clean HD frame to Gallery",
                                    onClick = {
                                        onDismiss()
                                        onCaptureScreenshot()
                                    },
                                    testTag = "drawer_screenshot_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.ContentCut,
                                    title = "Video Clip Cutter",
                                    subtitle = "Lossless trim & export clip to Movies",
                                    onClick = {
                                        onDismiss()
                                        onOpenClipCutter()
                                    },
                                    testTag = "drawer_clip_cutter_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.MusicNote,
                                    title = if (isExtractingAudio) "Extracting Audio..." else "Extract Audio (M4A)",
                                    subtitle = "Save audio track to Music/VOXORA",
                                    highlighted = isExtractingAudio,
                                    onClick = {
                                        onDismiss()
                                        onExtractAudio()
                                    },
                                    testTag = "drawer_extract_audio_row"
                                )
                                DrawerActionRow(
                                    icon = Icons.Rounded.Cast,
                                    title = "Cast to TV / Mirror",
                                    subtitle = "Open wireless display casting",
                                    onClick = {
                                        onDismiss()
                                        onOpenCast()
                                    },
                                    testTag = "drawer_cast_row"
                                )
                                if (canEnterPip) {
                                    DrawerActionRow(
                                        icon = Icons.Rounded.PictureInPictureAlt,
                                        title = "Picture-in-Picture (PiP)",
                                        subtitle = "Float mini-player over other apps",
                                        onClick = {
                                            onDismiss()
                                            onEnterPip()
                                        },
                                        testTag = "drawer_pip_row"
                                    )
                                }
                                DrawerActionRow(
                                    icon = Icons.Rounded.ChildCare,
                                    title = "Kids Safe Lock",
                                    subtitle = "Lock screen & back button (3s hold to unlock)",
                                    onClick = {
                                        onDismiss()
                                        onEnableKidsLock()
                                    },
                                    testTag = "drawer_kids_lock_row"
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
private fun DrawerActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        color = if (highlighted) VoxoraAmber.copy(alpha = 0.14f) else Color(0xFF141723),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            0.8.dp,
            if (highlighted) VoxoraAmber.copy(alpha = 0.55f) else VoxoraHairlineBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) VoxoraAmberBright else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) VoxoraAmberBright else Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DrawerToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        color = if (checked) VoxoraAmber.copy(alpha = 0.14f) else Color(0xFF141723),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            0.8.dp,
            if (checked) VoxoraAmber.copy(alpha = 0.55f) else VoxoraHairlineBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) VoxoraAmberBright else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF140D00),
                    checkedTrackColor = VoxoraAmber,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                    uncheckedTrackColor = VoxoraElevatedCharcoal,
                    uncheckedBorderColor = VoxoraHairlineBorder
                )
            )
        }
    }
}

@Composable
private fun DrawerCompactButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF141723),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.8.dp, VoxoraHairlineBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VoxoraAmberBright,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White
            )
        }
    }
}
