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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxora.player.BuildConfig
import com.voxora.player.R
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.ThemeMode
import com.voxora.player.model.UserPreferences
import com.voxora.player.ui.components.VoxoraBrandMark
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.viewmodel.SettingsViewModel
import com.voxora.player.util.FormatUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    statusBanner: String?,
    viewModel: SettingsViewModel,
    updateState: UpdateUiState = UpdateUiState.Idle,
    hasNotificationPermission: Boolean = true,
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val voxoraColors = LocalVoxoraColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoonMessage = stringResource(R.string.ai_dubbing_coming_soon_message)
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

            // Locked AI Audio Dubbing — Coming Soon Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(comingSoonMessage)
                            }
                        }
                        .testTag("settings_ai_dubbing_coming_soon_card"),
                    shape = RoundedCornerShape(18.dp),
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
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = VoxoraElectricPurple.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = VoxoraElectricPurple,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "AI Audio Dubbing",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = "Voice dubbing is paused while we focus on ultra-fast Live Auto AI Subtitles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = VoxoraCyan.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "Coming Soon",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoxoraCyan,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
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
                    HorizontalDivider(color = voxoraColors.cardBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Press & Hold Fast-Forward Speed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Press and hold anywhere on the player to boost speed; slide up/down to switch between 2.0x and 3.0x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(2.0f, 3.0f).forEach { holdSpeed ->
                            FilterChip(
                                selected = kotlin.math.abs(preferences.holdSpeedMultiplier - holdSpeed) < 0.05f,
                                onClick = { viewModel.setHoldSpeedMultiplier(holdSpeed) },
                                label = { Text("${holdSpeed}x") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VoxoraCyan,
                                    selectedLabelColor = Color(0xFF061018)
                                ),
                                modifier = Modifier.testTag("settings_hold_speed_${holdSpeed.toInt()}x")
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
                            .background(Color(0xFF0B0D12))
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = preferences.subtitleBackgroundOpacity),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "VOXORA Live Auto AI Subtitle Preview",
                                color = Color(preferences.subtitleTextColor.colorHex),
                                fontSize = preferences.subtitleFontSizeSp.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text("Text Color", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SubtitleTextColor.entries.forEach { textColor ->
                            FilterChip(
                                selected = preferences.subtitleTextColor == textColor,
                                onClick = { viewModel.setSubtitleTextColor(textColor) },
                                label = { Text(textColor.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("settings_subtitle_color_${textColor.name}")
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
                        Text("Vertical Position (Top → Bottom)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${(preferences.subtitleVerticalPositionFraction * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = voxoraColors.blueHighlight
                        )
                    }
                    Slider(
                        value = preferences.subtitleVerticalPositionFraction.coerceIn(0.05f, 0.95f),
                        onValueChange = viewModel::setSubtitleVerticalPositionFraction,
                        valueRange = 0.05f..0.95f
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

            // App Updates (GitHub Releases In-App Updater)
            item {
                SettingsSectionCard(
                    title = "App Updates",
                    icon = Icons.Rounded.SystemUpdate
                ) {
                    val statusText = when (updateState) {
                        UpdateUiState.Idle -> "Installed: v${BuildConfig.VERSION_NAME} · Ready to check GitHub Releases"
                        UpdateUiState.Checking -> "Checking GitHub Releases for updates..."
                        is UpdateUiState.UpToDate -> "You are on the latest version (v${updateState.currentVersion})"
                        is UpdateUiState.UpdateAvailable -> "New version v${updateState.release.versionName} is available!"
                        is UpdateUiState.Downloading -> "Downloading v${updateState.release.versionName} (${(updateState.progressFraction * 100).toInt()}%)..."
                        is UpdateUiState.ReadyToInstall -> "v${updateState.release.versionName} downloaded and ready to install"
                        is UpdateUiState.Error -> updateState.message
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (updateState) {
                            is UpdateUiState.UpdateAvailable,
                            is UpdateUiState.ReadyToInstall -> voxoraColors.success
                            is UpdateUiState.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    SettingsSwitchRow(
                        title = "Auto-Check & Notify Updates",
                        subtitle = "Check GitHub Releases on launch and send a notification when a new update arrives",
                        checked = preferences.autoCheckUpdates,
                        onCheckedChange = viewModel::setAutoCheckUpdates,
                        testTag = "settings_auto_update_switch"
                    )

                    if (!hasNotificationPermission) {
                        OutlinedButton(
                            onClick = onRequestNotificationPermission,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_enable_update_notifications_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enable Update Notifications")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isUpdateActionable = updateState is UpdateUiState.UpdateAvailable ||
                            updateState is UpdateUiState.Downloading ||
                            updateState is UpdateUiState.ReadyToInstall

                        if (isUpdateActionable) {
                            Button(
                                onClick = onOpenUpdateDialog,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_open_update_button")
                            ) {
                                Text(
                                    text = when (updateState) {
                                        is UpdateUiState.ReadyToInstall -> "Install v${updateState.release.versionName}"
                                        is UpdateUiState.Downloading -> "View Download"
                                        is UpdateUiState.UpdateAvailable -> "Download v${updateState.release.versionName}"
                                        else -> "Update"
                                    }
                                )
                            }
                        } else {
                            Button(
                                onClick = onCheckForUpdates,
                                enabled = updateState !is UpdateUiState.Checking,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_check_updates_button")
                            ) {
                                if (updateState is UpdateUiState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Checking...")
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Check for Updates")
                                }
                            }
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
                            text = "Built on AndroidX Media3 ExoPlayer, Jetpack Compose, Room, whisper.cpp, and Google ML Kit for smooth hardware-accelerated playback and 100% free on-device Auto AI Subtitles.",
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
                                    text = "VOXORA Media Player v${BuildConfig.VERSION_NAME} — Free Live Auto AI Subtitles (59+ languages, drag-to-reposition), Press & Hold 2x/3x Speed, Picture-in-Picture, Sleep Timer & Obsidian UI. AI Audio Dubbing coming soon.",
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
