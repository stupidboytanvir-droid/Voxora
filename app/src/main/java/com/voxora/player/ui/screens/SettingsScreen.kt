package com.voxora.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxora.player.BuildConfig
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.ThemeMode
import com.voxora.player.model.UserPreferences
import com.voxora.player.ui.components.VoxoraBrandMark
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.viewmodel.SettingsViewModel
import com.voxora.player.util.FormatUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    statusBanner: String?,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    val batteryEvaluation by viewModel.batterySavingEvaluation.collectAsStateWithLifecycle()
    val batteryStatus by viewModel.batteryStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            snackbarHostState.showSnackbar(statusBanner)
            viewModel.dismissBanner()
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Playback History?") },
            text = { Text("All saved resume positions and recent video records will be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Customize playback, gestures, subtitles, and media discovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Theme Settings
            item {
                SettingsSectionCard(
                    title = "Theme",
                    icon = Icons.Rounded.Palette
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = mode == preferences.themeMode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setThemeMode(mode) }
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                                .testTag("theme_option_${mode.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else voxoraColors.cardBorder
                                )
                            ) {
                                Text(
                                    text = if (selected) "Active" else "Select",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Playback Settings
            item {
                SettingsSectionCard(
                    title = "Playback & Default Speed",
                    icon = Icons.Rounded.PlayCircleOutline
                ) {
                    SettingsSwitchRow(
                        title = "Resume Playback",
                        subtitle = "Automatically continue from last saved position",
                        checked = preferences.resumePlaybackEnabled,
                        onCheckedChange = viewModel::setResumePlaybackEnabled,
                        testTag = "settings_resume_switch"
                    )
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = "Auto-Play Next Video",
                        subtitle = "Continue seamlessly to the next video in library",
                        checked = preferences.autoPlayNext,
                        onCheckedChange = viewModel::setAutoPlayNext,
                        testTag = "settings_autoplay_switch"
                    )
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Default Playback Speed", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            FilterChip(
                                selected = kotlin.math.abs(preferences.defaultPlaybackSpeed - speed) < 0.01f,
                                onClick = { viewModel.setDefaultPlaybackSpeed(speed) },
                                label = { Text("${speed}x") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Default Aspect Ratio", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AspectRatioMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.defaultAspectRatio == mode,
                                onClick = { viewModel.setDefaultAspectRatio(mode) },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Battery-Saving Mode Settings
            item {
                SettingsSectionCard(
                    title = "Player Battery Saver",
                    icon = Icons.Rounded.BatterySaver
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Device Battery: ${batteryStatus.batteryPercent}%${if (batteryStatus.isCharging) " (Charging)" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = if (batteryEvaluation.isActive) {
                                voxoraColors.success.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (batteryEvaluation.isActive) "Active · ${batteryEvaluation.reasonLabel}" else batteryEvaluation.reasonLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (batteryEvaluation.isActive) voxoraColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BatterySaverMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.batterySaverMode == mode,
                                onClick = { viewModel.setBatterySaverMode(mode) },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("battery_mode_${mode.name}")
                            )
                        }
                    }

                    if (preferences.batterySaverMode == BatterySaverMode.AUTO) {
                        Text("Low Battery Trigger Threshold", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(10, 15, 20, 30).forEach { threshold ->
                                FilterChip(
                                    selected = preferences.batterySaverThresholdPercent == threshold,
                                    onClick = { viewModel.setBatterySaverThresholdPercent(threshold) },
                                    label = { Text("$threshold%") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))

                    SettingsSwitchRow(
                        title = "Cap Display Refresh Rate (60Hz)",
                        subtitle = "Reduces high-refresh-rate display power draw during video playback",
                        checked = preferences.batterySaverCapRefreshRate,
                        onCheckedChange = viewModel::setBatterySaverCapRefreshRate,
                        testTag = "settings_battery_cap_refresh_switch"
                    )
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = "Disable Non-Essential Animations",
                        subtitle = "Disables player overlay fades and transitions to save GPU/CPU cycles",
                        checked = preferences.batterySaverDisableAnimations,
                        onCheckedChange = viewModel::setBatterySaverDisableAnimations,
                        testTag = "settings_battery_disable_anims_switch"
                    )
                }
            }

            // Gesture Settings
            item {
                SettingsSectionCard(
                    title = "Player Gestures",
                    icon = Icons.Rounded.TouchApp
                ) {
                    SettingsSwitchRow(
                        title = "Brightness Swipe (Left Side)",
                        subtitle = "Swipe up/down on left side to adjust screen brightness",
                        checked = preferences.brightnessGestureEnabled,
                        onCheckedChange = viewModel::setBrightnessGestureEnabled,
                        testTag = "settings_brightness_gesture_switch"
                    )
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = "Volume Swipe (Right Side)",
                        subtitle = "Swipe up/down on right side to adjust media volume",
                        checked = preferences.volumeGestureEnabled,
                        onCheckedChange = viewModel::setVolumeGestureEnabled,
                        testTag = "settings_volume_gesture_switch"
                    )
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Double-Tap Seek Duration", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(5, 10, 15, 30).forEach { seconds ->
                            FilterChip(
                                selected = preferences.doubleTapSeekSeconds == seconds,
                                onClick = { viewModel.setDoubleTapSeekSeconds(seconds) },
                                label = { Text("${seconds}s") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Subtitle Settings
            item {
                SettingsSectionCard(
                    title = "Subtitles",
                    icon = Icons.Rounded.Subtitles
                ) {
                    // Live Preview Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A0A0F))
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = preferences.subtitleBackgroundOpacity),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "VOXORA Subtitle Preview",
                                color = Color.White,
                                fontSize = preferences.subtitleFontSizeSp.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${preferences.subtitleFontSizeSp.toInt()} sp",
                            style = TabularTimeStyle,
                            color = voxoraColors.blueHighlight
                        )
                    }
                    Slider(
                        value = preferences.subtitleFontSizeSp,
                        onValueChange = viewModel::setSubtitleFontSizeSp,
                        valueRange = 12f..34f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Vertical Position", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${(preferences.subtitleBottomPaddingFraction * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = voxoraColors.blueHighlight
                        )
                    }
                    Slider(
                        value = preferences.subtitleBottomPaddingFraction,
                        onValueChange = viewModel::setSubtitleBottomPaddingFraction,
                        valueRange = 0.02f..0.35f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Background Opacity", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${(preferences.subtitleBackgroundOpacity * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = voxoraColors.blueHighlight
                        )
                    }
                    Slider(
                        value = preferences.subtitleBackgroundOpacity,
                        onValueChange = viewModel::setSubtitleBackgroundOpacity,
                        valueRange = 0f..1f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Default Sync Offset", style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.setSubtitleDefaultSyncOffsetMs(
                                        (preferences.subtitleDefaultSyncOffsetMs - 100L).coerceAtLeast(-5000L)
                                    )
                                }
                            ) { Text("-0.1s") }
                            Text(
                                text = FormatUtils.formatSyncOffset(preferences.subtitleDefaultSyncOffsetMs),
                                style = TabularTimeStyle,
                                color = voxoraColors.blueHighlight
                            )
                            OutlinedButton(
                                onClick = {
                                    viewModel.setSubtitleDefaultSyncOffsetMs(
                                        (preferences.subtitleDefaultSyncOffsetMs + 100L).coerceAtMost(5000L)
                                    )
                                }
                            ) { Text("+0.1s") }
                        }
                    }
                }
            }

            // Video Scanning & History
            item {
                SettingsSectionCard(
                    title = "Library Scanning & Data",
                    icon = Icons.Rounded.VideoLibrary
                ) {
                    Text("Minimum Video Duration Filter", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "All", 5 to "5s+", 30 to "30s+", 60 to "1m+").forEach { (sec, label) ->
                            FilterChip(
                                selected = preferences.minVideoDurationSec == sec,
                                onClick = { viewModel.setMinVideoDurationSec(sec) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = "Show Hidden Folders",
                        subtitle = "Include dot-prefixed hidden directories when scanning",
                        checked = preferences.showHiddenFolders,
                        onCheckedChange = viewModel::setShowHiddenFolders,
                        testTag = "settings_hidden_folders_switch"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = viewModel::rescanLibrary,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_rescan_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rescan Library")
                        }
                        OutlinedButton(
                            onClick = { showClearDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_clear_history_button")
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear History")
                        }
                    }
                }
            }

            // About VOXORA
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, voxoraColors.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VoxoraBrandMark(size = 44.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "VOXORA Media Player",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Play Beyond Language · Version ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = voxoraColors.blueHighlight
                                )
                            }
                        }
                        Text(
                            text = "Built on AndroidX Media3 ExoPlayer, Jetpack Compose, Room, and Scoped Storage for private, responsive local playback.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "AI Dubbing Architecture Ready — Multilingual voice dubbing, speaker detection, and background stem preservation arrive in a future release.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Text(
                            text = "Package: ${BuildConfig.APPLICATION_ID} · v${BuildConfig.VERSION_NAME}",
                            style = TabularTimeStyle,
                            color = voxoraColors.textMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val voxoraColors = LocalVoxoraColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, voxoraColors.cardBorder.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
