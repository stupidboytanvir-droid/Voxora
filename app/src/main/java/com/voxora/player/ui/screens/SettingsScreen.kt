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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxora.player.BuildConfig
import com.voxora.player.R
import com.voxora.player.ai.subtitles.AutoSubtitleLanguageCatalog
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.ThemeMode
import com.voxora.player.model.UserPreferences
import com.voxora.player.ui.components.VoxoraBrandMark
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraAmber
import com.voxora.player.ui.theme.VoxoraAmberBright
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraElevatedCharcoal
import com.voxora.player.ui.theme.VoxoraError
import com.voxora.player.ui.theme.VoxoraHairlineBorder
import com.voxora.player.ui.theme.VoxoraObsidian
import com.voxora.player.ui.theme.VoxoraSuccess
import com.voxora.player.ui.viewmodel.SettingsViewModel
import com.voxora.player.util.FormatUtils
import kotlinx.coroutines.launch

private enum class SettingsSheetType {
    NONE,
    PLAYBACK_GESTURES,
    AI_SUBTITLES,
    AUDIO_VISUAL,
    LIBRARY_DATA,
    UPDATES,
    ABOUT
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onOpenVault: () -> Unit = {},
    onOpenCleaner: () -> Unit = {},
    onPlayStreamUrl: (url: String, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoonMessage = stringResource(R.string.ai_dubbing_coming_soon_message)
    val isWhisperInstalled by viewModel.isWhisperModelInstalled.collectAsStateWithLifecycle()

    var activeSheet by remember { mutableStateOf(SettingsSheetType.NONE) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showStreamUrlDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshOfflineModelStatus()
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            snackbarHostState.showSnackbar(statusBanner)
            viewModel.dismissBanner()
        }
    }

    if (showStreamUrlDialog) {
        StreamUrlDialog(
            onPlayUrl = onPlayStreamUrl,
            onDismiss = { showStreamUrlDialog = false }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = VoxoraCardCharcoal,
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

    val primaryLangName = remember(preferences.autoSubtitleTargetLanguageCode) {
        AutoSubtitleLanguageCatalog.findByCode(preferences.autoSubtitleTargetLanguageCode).displayName
    }
    val updateSummary = when (updateState) {
        is UpdateUiState.UpdateAvailable -> "v${updateState.release.versionName} available"
        is UpdateUiState.Downloading -> "Downloading ${(updateState.progressFraction * 100).toInt()}%"
        is UpdateUiState.ReadyToInstall -> "Ready to install"
        is UpdateUiState.UpToDate -> "Up to date"
        UpdateUiState.Checking -> "Checking..."
        else -> "v${BuildConfig.VERSION_NAME}"
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 118.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 2.dp)) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "VOXORA Cinema Spatial · v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. Experience & Playback
            item {
                SettingsGroupHeader("EXPERIENCE")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsGroupedCard {
                    SettingsMenuRow(
                        icon = Icons.Rounded.PlayCircleOutline,
                        title = "Playback & Gestures",
                        subtitle = "Auto-Orientation · ${preferences.defaultPlaybackSpeed}x · Hold ${preferences.holdSpeedMultiplier}x",
                        onClick = { activeSheet = SettingsSheetType.PLAYBACK_GESTURES },
                        testTag = "settings_row_playback"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.Subtitles,
                        title = "AI Subtitles",
                        subtitle = "$primaryLangName · ${preferences.subtitleFontSizeSp.toInt()}sp" +
                            if (preferences.dualSubtitlesEnabled) " · Dual ON" else "",
                        onClick = {
                            viewModel.refreshOfflineModelStatus()
                            activeSheet = SettingsSheetType.AI_SUBTITLES
                        },
                        testTag = "settings_row_subtitles"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.Equalizer,
                        title = "Audio & Visual",
                        subtitle = "${preferences.themeMode.label} · Boost +${preferences.volumeBoostPercent}% · ${preferences.audioEqPreset.label}",
                        onClick = { activeSheet = SettingsSheetType.AUDIO_VISUAL },
                        testTag = "settings_row_audio_visual"
                    )
                }
            }

            // 2. Tools & Privacy (Stream URL, Private Vault, Storage Cleaner, Library Data)
            item {
                SettingsGroupHeader("TOOLS & PRIVACY")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsGroupedCard {
                    SettingsMenuRow(
                        icon = Icons.Rounded.Security,
                        title = "Private Vault",
                        subtitle = if (preferences.vaultPinSha256.isNotBlank()) "PIN & Biometric Lock active" else "Lock private videos behind PIN or biometrics",
                        onClick = onOpenVault,
                        testTag = "settings_open_vault_button"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.Link,
                        title = "Stream Network URL",
                        subtitle = "Play direct MP4, MKV, HLS (.m3u8) or DASH links",
                        onClick = { showStreamUrlDialog = true },
                        testTag = "settings_stream_url_button"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.CleaningServices,
                        title = "Storage Cleaner",
                        subtitle = "Find duplicate & oversized video files",
                        onClick = onOpenCleaner,
                        testTag = "settings_open_cleaner_button"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.VideoLibrary,
                        title = "Library & History",
                        subtitle = "Scan filters, hidden folders, rescan & history",
                        onClick = { activeSheet = SettingsSheetType.LIBRARY_DATA },
                        testTag = "settings_row_library_data"
                    )
                }
            }

            // 3. Upcoming & About
            item {
                SettingsGroupHeader("UPCOMING & ABOUT")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsGroupedCard {
                    SettingsMenuRow(
                        icon = Icons.Rounded.GraphicEq,
                        title = "AI Audio Dubbing",
                        subtitle = "On-device neural voice dubbing roadmap",
                        trailingBadge = "Coming Soon",
                        trailingIcon = Icons.Rounded.Lock,
                        onClick = {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(comingSoonMessage)
                            }
                        },
                        testTag = "settings_ai_dubbing_coming_soon_card"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = "App Updates",
                        subtitle = "GitHub Releases in-app updater · $updateSummary",
                        onClick = { activeSheet = SettingsSheetType.UPDATES },
                        testTag = "settings_row_updates"
                    )
                    SettingsGroupDivider()
                    SettingsMenuRow(
                        icon = Icons.Rounded.Info,
                        title = "About VOXORA",
                        subtitle = "Version ${BuildConfig.VERSION_NAME} · Media3 + whisper.cpp + ML Kit",
                        onClick = { activeSheet = SettingsSheetType.ABOUT },
                        testTag = "settings_row_about"
                    )
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

    // Focused Category Bottom Sheets
    if (activeSheet != SettingsSheetType.NONE) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { activeSheet = SettingsSheetType.NONE },
            sheetState = sheetState,
            containerColor = VoxoraCardCharcoal,
            contentColor = Color.White
        ) {
            when (activeSheet) {
                SettingsSheetType.PLAYBACK_GESTURES -> PlaybackGesturesSheetContent(
                    preferences = preferences,
                    viewModel = viewModel
                )
                SettingsSheetType.AI_SUBTITLES -> AiSubtitlesSheetContent(
                    preferences = preferences,
                    isWhisperInstalled = isWhisperInstalled,
                    viewModel = viewModel
                )
                SettingsSheetType.AUDIO_VISUAL -> AudioVisualSheetContent(
                    preferences = preferences,
                    viewModel = viewModel
                )
                SettingsSheetType.LIBRARY_DATA -> LibraryDataSheetContent(
                    preferences = preferences,
                    viewModel = viewModel,
                    onRequestClearHistory = {
                        activeSheet = SettingsSheetType.NONE
                        showClearDialog = true
                    }
                )
                SettingsSheetType.UPDATES -> UpdatesSheetContent(
                    preferences = preferences,
                    updateState = updateState,
                    hasNotificationPermission = hasNotificationPermission,
                    onToggleAutoCheck = viewModel::setAutoCheckUpdates,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onCheckForUpdates = onCheckForUpdates,
                    onOpenUpdateDialog = {
                        activeSheet = SettingsSheetType.NONE
                        onOpenUpdateDialog()
                    }
                )
                SettingsSheetType.ABOUT -> AboutVoxoraSheetContent()
                SettingsSheetType.NONE -> Unit
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        ),
        color = VoxoraAmberBright,
        modifier = Modifier.padding(start = 6.dp)
    )
}

@Composable
private fun SettingsGroupedCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = VoxoraCardCharcoal),
        border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        color = VoxoraHairlineBorder.copy(alpha = 0.7f),
        thickness = 0.7.dp,
        modifier = Modifier.padding(start = 60.dp, end = 16.dp)
    )
}

@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String,
    trailingBadge: String? = null,
    trailingIcon: ImageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = VoxoraAmber.copy(alpha = 0.14f),
            shape = RoundedCornerShape(11.dp),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = VoxoraAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
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
        if (trailingBadge != null) {
            Surface(
                color = VoxoraElevatedCharcoal,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
            ) {
                Text(
                    text = trailingBadge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = VoxoraAmberBright,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackGesturesSheetContent(
    preferences: UserPreferences,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Playback & Gestures",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )

        Surface(
            color = VoxoraElevatedCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Smart Auto-Orientation & Hardware Decoding",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = VoxoraAmberBright
                )
                Text(
                    text = "Landscape videos automatically rotate to Sensor Landscape; vertical videos play in Portrait. Hardware MediaCodec acceleration is active by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }

        SettingsSwitchRow(
            title = "Resume Playback",
            subtitle = "Continue automatically from last saved position",
            checked = preferences.resumePlaybackEnabled,
            onCheckedChange = viewModel::setResumePlaybackEnabled,
            testTag = "settings_resume_switch"
        )
        SettingsSwitchRow(
            title = "Auto-Play Next Video",
            subtitle = "Continue seamlessly to the next video in library",
            checked = preferences.autoPlayNext,
            onCheckedChange = viewModel::setAutoPlayNext,
            testTag = "settings_autoplay_switch"
        )

        HorizontalDivider(color = VoxoraHairlineBorder)

        Text("Default Playback Speed", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                AmberFilterChip(
                    selected = kotlin.math.abs(preferences.defaultPlaybackSpeed - speed) < 0.01f,
                    label = "${speed}x",
                    onClick = { viewModel.setDefaultPlaybackSpeed(speed) }
                )
            }
        }

        Text("Press & Hold Fast-Forward Speed", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Hold anywhere on the video to boost speed; slide up/down to toggle 2.0x / 3.0x",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2.0f, 3.0f).forEach { holdSpeed ->
                AmberFilterChip(
                    selected = kotlin.math.abs(preferences.holdSpeedMultiplier - holdSpeed) < 0.05f,
                    label = "${holdSpeed}x Hold",
                    onClick = { viewModel.setHoldSpeedMultiplier(holdSpeed) },
                    modifier = Modifier.testTag("settings_hold_speed_${holdSpeed.toInt()}x")
                )
            }
        }

        Text("Default Aspect Ratio", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AspectRatioMode.entries.forEach { mode ->
                AmberFilterChip(
                    selected = preferences.defaultAspectRatio == mode,
                    label = mode.label,
                    onClick = { viewModel.setDefaultAspectRatio(mode) }
                )
            }
        }

        HorizontalDivider(color = VoxoraHairlineBorder)

        SettingsSwitchRow(
            title = "Brightness Swipe (Left Side)",
            subtitle = "Swipe up/down on left side to adjust screen brightness",
            checked = preferences.brightnessGestureEnabled,
            onCheckedChange = viewModel::setBrightnessGestureEnabled,
            testTag = "settings_brightness_gesture_switch"
        )
        SettingsSwitchRow(
            title = "Volume Swipe (Right Side)",
            subtitle = "Swipe up/down on right side to adjust media volume",
            checked = preferences.volumeGestureEnabled,
            onCheckedChange = viewModel::setVolumeGestureEnabled,
            testTag = "settings_volume_gesture_switch"
        )

        Text("Double-Tap Seek Duration", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { seconds ->
                AmberFilterChip(
                    selected = preferences.doubleTapSeekSeconds == seconds,
                    label = "${seconds}s",
                    onClick = { viewModel.setDoubleTapSeekSeconds(seconds) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSubtitlesSheetContent(
    preferences: UserPreferences,
    isWhisperInstalled: Boolean,
    viewModel: SettingsViewModel
) {
    var pickingLanguageFor by remember { mutableStateOf<String?>(null) } // "primary" or "secondary"
    var langQuery by remember { mutableStateOf("") }

    if (pickingLanguageFor != null) {
        val filtered = remember(langQuery) { AutoSubtitleLanguageCatalog.filter(langQuery) }
        val activeCode = if (pickingLanguageFor == "primary") {
            preferences.autoSubtitleTargetLanguageCode
        } else {
            preferences.secondarySubtitleLanguageCode
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pickingLanguageFor == "primary") "Default Subtitle Language" else "Secondary Dual Language",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                TextButton(onClick = {
                    pickingLanguageFor = null
                    langQuery = ""
                }) {
                    Text("Done", color = VoxoraAmberBright)
                }
            }
            OutlinedTextField(
                value = langQuery,
                onValueChange = { langQuery = it },
                placeholder = { Text("Search 59+ languages...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (langQuery.isNotEmpty()) {
                        IconButton(onClick = { langQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VoxoraAmber,
                    unfocusedBorderColor = VoxoraHairlineBorder
                ),
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.code }) { option ->
                    val selected = option.code == activeCode
                    Surface(
                        onClick = {
                            if (pickingLanguageFor == "primary") {
                                viewModel.setAutoSubtitleTargetLanguageCode(option.code)
                            } else {
                                viewModel.setSecondarySubtitleLanguageCode(option.code)
                            }
                            pickingLanguageFor = null
                            langQuery = ""
                        },
                        color = if (selected) VoxoraAmber.copy(alpha = 0.16f) else VoxoraElevatedCharcoal,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            0.8.dp,
                            if (selected) VoxoraAmber else VoxoraHairlineBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = option.nativeName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = VoxoraAmber)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val primaryLang = remember(preferences.autoSubtitleTargetLanguageCode) {
        AutoSubtitleLanguageCatalog.findByCode(preferences.autoSubtitleTargetLanguageCode)
    }
    val secondaryLang = remember(preferences.secondarySubtitleLanguageCode) {
        AutoSubtitleLanguageCatalog.findByCode(preferences.secondarySubtitleLanguageCode)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "AI Subtitles",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )

        // Offline Model Status Pill
        Surface(
            color = VoxoraElevatedCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, if (isWhisperInstalled) VoxoraSuccess.copy(alpha = 0.5f) else VoxoraHairlineBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Whisper Multilingual Engine (ggml-base-q5_1)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    Text(
                        text = if (isWhisperInstalled) {
                            "Installed offline (~57 MB) · High-accuracy VAD + anti-hallucination active"
                        } else {
                            "Auto-downloads automatically on first AI Subtitle activation (~57 MB)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Surface(
                    color = if (isWhisperInstalled) VoxoraSuccess.copy(alpha = 0.16f) else VoxoraAmber.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isWhisperInstalled) "Ready" else "On Demand",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isWhisperInstalled) VoxoraSuccess else VoxoraAmberBright,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Language Selection Rows
        Surface(
            onClick = { pickingLanguageFor = "primary" },
            color = VoxoraElevatedCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Default Language (59+)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${primaryLang.displayName} (${primaryLang.nativeName})",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoxoraAmberBright
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
            }
        }

        SettingsSwitchRow(
            title = "Dual Subtitles Mode",
            subtitle = "Display two synchronized subtitle languages simultaneously",
            checked = preferences.dualSubtitlesEnabled,
            onCheckedChange = viewModel::setDualSubtitlesEnabled,
            testTag = "settings_dual_subtitles_switch"
        )

        if (preferences.dualSubtitlesEnabled) {
            Surface(
                onClick = { pickingLanguageFor = "secondary" },
                color = VoxoraElevatedCharcoal,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Secondary Language", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${secondaryLang.displayName} (${secondaryLang.nativeName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = VoxoraAmberBright
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                }
            }
        }

        // Live Subtitle Preview Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(VoxoraObsidian)
                .padding(vertical = 16.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Black.copy(alpha = preferences.subtitleBackgroundOpacity),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "VOXORA Speech-Synced Subtitle Preview",
                    color = Color(preferences.subtitleTextColor.colorHex),
                    fontSize = preferences.subtitleFontSizeSp.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        Text("Text Color", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitleTextColor.entries.forEach { textColor ->
                AmberFilterChip(
                    selected = preferences.subtitleTextColor == textColor,
                    label = textColor.label,
                    onClick = { viewModel.setSubtitleTextColor(textColor) },
                    modifier = Modifier.testTag("settings_subtitle_color_${textColor.name}")
                )
            }
        }

        AmberSliderRow(
            title = "Default Font Size",
            valueLabel = "${preferences.subtitleFontSizeSp.toInt()} sp",
            value = preferences.subtitleFontSizeSp.coerceIn(11f, 26f),
            valueRange = 11f..26f,
            onValueChange = viewModel::setSubtitleFontSizeSp
        )

        AmberSliderRow(
            title = "Vertical Position (Top → Bottom)",
            valueLabel = "${(preferences.subtitleVerticalPositionFraction * 100).toInt()}%",
            value = preferences.subtitleVerticalPositionFraction.coerceIn(0.05f, 0.95f),
            valueRange = 0.05f..0.95f,
            onValueChange = viewModel::setSubtitleVerticalPositionFraction
        )

        AmberSliderRow(
            title = "Background Opacity",
            valueLabel = "${(preferences.subtitleBackgroundOpacity * 100).toInt()}%",
            value = preferences.subtitleBackgroundOpacity,
            valueRange = 0f..1f,
            onValueChange = viewModel::setSubtitleBackgroundOpacity
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
                    color = VoxoraAmberBright
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioVisualSheetContent(
    preferences: UserPreferences,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Audio & Visual",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )

        Text("Appearance Theme", style = MaterialTheme.typography.titleSmall)
        ThemeMode.entries.forEach { mode ->
            val selected = mode == preferences.themeMode
            Surface(
                onClick = { viewModel.setThemeMode(mode) },
                color = if (selected) VoxoraAmber.copy(alpha = 0.16f) else VoxoraElevatedCharcoal,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.8.dp, if (selected) VoxoraAmber else VoxoraHairlineBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_option_${mode.name}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.titleSmall, color = Color.White)
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
                    }
                    if (selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = VoxoraAmber)
                    }
                }
            }
        }

        HorizontalDivider(color = VoxoraHairlineBorder)

        AmberSliderRow(
            title = "Default Volume Booster",
            valueLabel = if (preferences.volumeBoostPercent > 0) "+${preferences.volumeBoostPercent}% (${100 + preferences.volumeBoostPercent}%)" else "100% (Normal)",
            value = preferences.volumeBoostPercent.toFloat(),
            valueRange = 0f..100f,
            onValueChange = { viewModel.setVolumeBoostPercent(it.toInt()) }
        )

        Text("5-Band Equalizer Preset", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioEqPreset.entries.forEach { preset ->
                AmberFilterChip(
                    selected = preferences.audioEqPreset == preset,
                    label = preset.label,
                    onClick = { viewModel.setAudioEqPreset(preset) }
                )
            }
        }

        HorizontalDivider(color = VoxoraHairlineBorder)

        SettingsSwitchRow(
            title = "Eye Care Warm Filter",
            subtitle = "Reduce blue light automatically during night viewing",
            checked = preferences.eyeCareEnabled,
            onCheckedChange = viewModel::setEyeCareEnabled,
            testTag = "settings_eye_care_switch"
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryDataSheetContent(
    preferences: UserPreferences,
    viewModel: SettingsViewModel,
    onRequestClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Library & History",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )

        Text("Minimum Video Duration Filter", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "All", 5 to "5s+", 30 to "30s+", 60 to "1m+").forEach { (sec, label) ->
                AmberFilterChip(
                    selected = preferences.minVideoDurationSec == sec,
                    label = label,
                    onClick = { viewModel.setMinVideoDurationSec(sec) }
                )
            }
        }

        SettingsSwitchRow(
            title = "Show Hidden Folders",
            subtitle = "Include dot-prefixed directories when scanning storage",
            checked = preferences.showHiddenFolders,
            onCheckedChange = viewModel::setShowHiddenFolders,
            testTag = "settings_hidden_folders_switch"
        )

        HorizontalDivider(color = VoxoraHairlineBorder)

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
                onClick = onRequestClearHistory,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoxoraError),
                border = BorderStroke(1.dp, VoxoraError.copy(alpha = 0.55f)),
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

@Composable
private fun UpdatesSheetContent(
    preferences: UserPreferences,
    updateState: UpdateUiState,
    hasNotificationPermission: Boolean,
    onToggleAutoCheck: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenUpdateDialog: () -> Unit
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "App Updates",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = when (updateState) {
                is UpdateUiState.UpdateAvailable,
                is UpdateUiState.ReadyToInstall -> VoxoraSuccess
                is UpdateUiState.Error -> VoxoraError
                else -> Color.White.copy(alpha = 0.75f)
            }
        )

        SettingsSwitchRow(
            title = "Auto-Check & Notify Updates",
            subtitle = "Check GitHub Releases on launch and notify when an update arrives",
            checked = preferences.autoCheckUpdates,
            onCheckedChange = onToggleAutoCheck,
            testTag = "settings_auto_update_switch"
        )

        if (!hasNotificationPermission) {
            OutlinedButton(
                onClick = onRequestNotificationPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_enable_update_notifications_button")
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Enable Update Notifications")
            }
        }

        val isUpdateActionable = updateState is UpdateUiState.UpdateAvailable ||
            updateState is UpdateUiState.Downloading ||
            updateState is UpdateUiState.ReadyToInstall

        if (isUpdateActionable) {
            Button(
                onClick = onOpenUpdateDialog,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraAmber, contentColor = Color(0xFF140D00)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_open_update_button")
            ) {
                Text(
                    text = when (updateState) {
                        is UpdateUiState.ReadyToInstall -> "Install v${updateState.release.versionName}"
                        is UpdateUiState.Downloading -> "View Download"
                        is UpdateUiState.UpdateAvailable -> "Download v${updateState.release.versionName}"
                        else -> "Update"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Button(
                onClick = onCheckForUpdates,
                enabled = updateState !is UpdateUiState.Checking,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraAmber, contentColor = Color(0xFF140D00)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_check_updates_button")
            ) {
                if (updateState is UpdateUiState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF140D00))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check for Updates", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AboutVoxoraSheetContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoxoraBrandMark(size = 46.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "VOXORA Media Player",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Cinema Spatial · Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoxoraAmberBright
                )
            }
        }
        Text(
            text = "Built on AndroidX Media3 ExoPlayer, Jetpack Compose, Room, whisper.cpp, and Google ML Kit for flagship local cinema playback and 100% free on-device Auto AI Subtitles.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f)
        )
        Surface(
            color = VoxoraElevatedCharcoal,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, VoxoraHairlineBorder)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = VoxoraAmber)
                Text(
                    text = "v${BuildConfig.VERSION_NAME} — VOXORA Cinema Spatial UI, Floating Glass Dock, MX-Style Slide-Out Player Drawer, Smart Episode Title Formatting, and Speech-Locked VAD Subtitles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }
        }
        Text(
            text = "Package: ${BuildConfig.APPLICATION_ID} · Build ${BuildConfig.VERSION_CODE}",
            style = TabularTimeStyle,
            color = Color.White.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun AmberFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = VoxoraAmber,
            selectedLabelColor = Color(0xFF140D00),
            containerColor = VoxoraElevatedCharcoal,
            labelColor = Color.White.copy(alpha = 0.84f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = VoxoraHairlineBorder,
            selectedBorderColor = VoxoraAmber
        ),
        modifier = modifier
    )
}

@Composable
private fun AmberSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = TabularTimeStyle, color = VoxoraAmberBright)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = VoxoraAmber,
                activeTrackColor = VoxoraAmber,
                inactiveTrackColor = VoxoraHairlineBorder
            )
        )
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
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
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
