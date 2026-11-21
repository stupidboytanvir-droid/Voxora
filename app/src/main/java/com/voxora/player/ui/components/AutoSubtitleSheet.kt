package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.subtitles.AutoSubtitleLanguageCatalog
import com.voxora.player.ai.subtitles.AutoSubtitleUiState
import com.voxora.player.ai.subtitles.DialogueSearchResult
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.util.FormatUtils

private enum class SubtitleSheetMode {
    SETTINGS,
    PICK_PRIMARY_LANG,
    PICK_SECONDARY_LANG,
    SEARCH_DIALOGUE
}

/**
 * Unified Pro Subtitle & Free Live Auto AI Subtitles modal sheet for VOXORA v2.4.0.
 *
 * Includes:
 * - Free 100% On-Device Auto AI Subtitles (VAD speech-synced) + One-Click Setup
 * - Dual Subtitles toggle (Primary + Secondary language with independent color & size)
 * - One-tap "Save .SRT" export to Documents/VOXORA/Subtitles/ with auto-reload
 * - Dialogue Search (Jump to Scene) across all generated subtitles
 * - Default 15sp font size (range 11sp–26sp), position slider, color chips, opacity, sync delay
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoSubtitleSettingsSheet(
    autoSubtitleState: AutoSubtitleUiState,
    tracks: List<SubtitleTrackInfo>,
    fontSizeSp: Float,
    verticalPositionFraction: Float,
    textColor: SubtitleTextColor,
    secondaryTextColor: SubtitleTextColor,
    secondaryFontSizeSp: Float,
    backgroundOpacity: Float,
    syncOffsetMs: Long,
    isExportingSrt: Boolean,
    onToggleAutoSubtitles: (Boolean) -> Unit,
    onSelectAutoTargetLanguage: (String) -> Unit,
    onToggleDualSubtitles: (Boolean) -> Unit,
    onSelectSecondaryLanguage: (String) -> Unit,
    onSecondaryTextColorChange: (SubtitleTextColor) -> Unit,
    onSecondaryFontSizeChange: (Float) -> Unit,
    onExportSrt: () -> Unit,
    onSearchDialogue: (String) -> List<DialogueSearchResult>,
    onJumpToTimestamp: (Long) -> Unit,
    onSelectTrack: (SubtitleTrackInfo) -> Unit,
    onDisableSubtitles: () -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onVerticalPositionChange: (Float) -> Unit,
    onTextColorChange: (SubtitleTextColor) -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
    onAdjustSync: (Long) -> Unit,
    onResetSync: () -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(SubtitleSheetMode.SETTINGS) }
    var languageSearchQuery by remember { mutableStateOf("") }
    var dialogueQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        when (mode) {
            SubtitleSheetMode.PICK_PRIMARY_LANG,
            SubtitleSheetMode.PICK_SECONDARY_LANG -> {
                val isPrimary = mode == SubtitleSheetMode.PICK_PRIMARY_LANG
                val activeCode = if (isPrimary) {
                    autoSubtitleState.targetLanguageCode
                } else {
                    autoSubtitleState.secondaryLanguageCode
                }
                val filtered = remember(languageSearchQuery) {
                    AutoSubtitleLanguageCatalog.filter(languageSearchQuery)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isPrimary) "Primary Subtitle Language" else "Secondary (Dual) Language",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "${AutoSubtitleLanguageCatalog.allLanguages.size} languages · 100% Free On-Device",
                                style = MaterialTheme.typography.bodySmall,
                                color = VoxoraCyan
                            )
                        }
                        TextButton(onClick = {
                            languageSearchQuery = ""
                            mode = SubtitleSheetMode.SETTINGS
                        }) {
                            Text("Done", color = VoxoraElectricPurpleBright)
                        }
                    }

                    OutlinedTextField(
                        value = languageSearchQuery,
                        onValueChange = { languageSearchQuery = it },
                        placeholder = { Text("Search Bengali, Hindi, Spanish, Japanese...", color = Color.White.copy(alpha = 0.5f)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = VoxoraCyan) },
                        trailingIcon = {
                            if (languageSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { languageSearchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VoxoraCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auto_subtitle_language_search")
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.code }) { lang ->
                            val isSelected = lang.code.equals(activeCode, ignoreCase = true)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (isPrimary) {
                                            onSelectAutoTargetLanguage(lang.code)
                                        } else {
                                            onSelectSecondaryLanguage(lang.code)
                                        }
                                        languageSearchQuery = ""
                                        mode = SubtitleSheetMode.SETTINGS
                                    }
                                    .testTag("auto_subtitle_lang_${lang.code}"),
                                color = if (isSelected) VoxoraElectricPurple.copy(alpha = 0.22f) else VoxoraCardCharcoal,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) VoxoraCyan else Color.White.copy(alpha = 0.08f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(lang.displayName, style = MaterialTheme.typography.titleSmall, color = Color.White)
                                        Text(
                                            text = "${lang.nativeName} · ${lang.code.uppercase()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.65f)
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = VoxoraCyan)
                                    }
                                }
                            }
                        }
                    }
                }
                return@ModalBottomSheet
            }

            SubtitleSheetMode.SEARCH_DIALOGUE -> {
                val results = remember(dialogueQuery, autoSubtitleState.generatedCueCount) {
                    onSearchDialogue(dialogueQuery)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dialogue Search", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Tap any line to jump directly to that scene",
                                style = MaterialTheme.typography.bodySmall,
                                color = VoxoraCyan
                            )
                        }
                        TextButton(onClick = { mode = SubtitleSheetMode.SETTINGS }) {
                            Text("Back", color = VoxoraElectricPurpleBright)
                        }
                    }

                    OutlinedTextField(
                        value = dialogueQuery,
                        onValueChange = { dialogueQuery = it },
                        placeholder = { Text("Search spoken words or translated lines...", color = Color.White.copy(alpha = 0.5f)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = VoxoraCyan) },
                        trailingIcon = {
                            if (dialogueQuery.isNotEmpty()) {
                                IconButton(onClick = { dialogueQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VoxoraCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("subtitle_dialogue_search_input")
                    )

                    if (results.isEmpty()) {
                        Text(
                            text = if (autoSubtitleState.generatedCueCount == 0) {
                                "Enable Auto AI Subtitles and play the video to populate searchable dialogue."
                            } else {
                                "No matching dialogue lines found."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(results, key = { "${it.startTimeMs}_${it.primaryText.hashCode()}" }) { item ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            onJumpToTimestamp(item.startTimeMs)
                                            onDismiss()
                                        },
                                    color = VoxoraCardCharcoal,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = FormatUtils.formatDuration(item.startTimeMs),
                                            style = TabularTimeStyle,
                                            color = VoxoraCyan
                                        )
                                        Text(
                                            text = item.primaryText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                        if (!item.secondaryText.isNullOrBlank()) {
                                            Text(
                                                text = item.secondaryText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(secondaryTextColor.colorHex)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return@ModalBottomSheet
            }

            SubtitleSheetMode.SETTINGS -> Unit
        }

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
                Text("Subtitles & Auto AI", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = onPickExternalSubtitle,
                    border = BorderStroke(1.dp, VoxoraElectricPurple),
                    modifier = Modifier.testTag("pick_external_subtitle_button")
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Load .SRT")
                }
            }

            // Free Live Auto AI Subtitles Hero Card
            Surface(
                color = VoxoraCardCharcoal,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            VoxoraElectricPurple.copy(alpha = if (autoSubtitleState.isEnabled) 0.9f else 0.45f),
                            VoxoraCyan.copy(alpha = if (autoSubtitleState.isEnabled) 0.9f else 0.45f)
                        )
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraCyan))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("Auto AI Subtitles", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(
                                    text = "100% Free On-Device · VAD Speech-Synced",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VoxoraCyan
                                )
                            }
                        }
                        Switch(
                            checked = autoSubtitleState.isEnabled,
                            onCheckedChange = onToggleAutoSubtitles,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VoxoraElectricPurple
                            ),
                            modifier = Modifier.testTag("auto_subtitles_switch")
                        )
                    }

                    // Primary Language Picker
                    LanguageSelectorTile(
                        title = "Primary Subtitle Language",
                        value = autoSubtitleState.targetLanguageLabel,
                        onClick = { mode = SubtitleSheetMode.PICK_PRIMARY_LANG },
                        testTag = "auto_subtitles_language_picker_button"
                    )

                    // Dual Subtitles Toggle + Secondary Language
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dual Subtitles", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Text(
                                text = "Show two languages simultaneously (e.g. English + Bengali)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                        Switch(
                            checked = autoSubtitleState.dualSubtitlesEnabled,
                            onCheckedChange = onToggleDualSubtitles,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = VoxoraCyan
                            ),
                            modifier = Modifier.testTag("dual_subtitles_switch")
                        )
                    }

                    if (autoSubtitleState.dualSubtitlesEnabled) {
                        LanguageSelectorTile(
                            title = "Secondary (Bottom) Language",
                            value = autoSubtitleState.secondaryLanguageLabel,
                            onClick = { mode = SubtitleSheetMode.PICK_SECONDARY_LANG },
                            testTag = "auto_subtitles_secondary_language_button"
                        )
                    }

                    // Action Row: Save .SRT + Dialogue Search
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportSrt,
                            enabled = !isExportingSrt && autoSubtitleState.generatedCueCount > 0,
                            border = BorderStroke(1.dp, VoxoraCyan.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_srt_button")
                        ) {
                            if (isExportingSrt) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoxoraCyan)
                            } else {
                                Icon(Icons.Rounded.SaveAlt, contentDescription = null, tint = VoxoraCyan, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save .SRT")
                        }
                        OutlinedButton(
                            onClick = { mode = SubtitleSheetMode.SEARCH_DIALOGUE },
                            border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("search_dialogue_button")
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = VoxoraElectricPurpleBright, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Search Scene")
                        }
                    }

                    if (autoSubtitleState.isSetupRunning) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = autoSubtitleState.setupStepLabel.ifBlank { "Setting up on-device AI..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            LinearProgressIndicator(
                                progress = { (autoSubtitleState.setupProgressPercent / 100f).coerceIn(0f, 1f) },
                                color = VoxoraCyan,
                                trackColor = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        }
                    } else {
                        Text(
                            text = autoSubtitleState.errorMessage ?: autoSubtitleState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (autoSubtitleState.errorMessage != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            }
                        )
                    }
                }
            }

            // Subtitle Tracks (Off / Embedded / External)
            val anyTrackSelected = !autoSubtitleState.isEnabled && tracks.any { it.isSelected }
            SubtitleTrackRowItem(
                label = "Subtitles Off",
                subtitle = "Disable all subtitle tracks",
                selected = !autoSubtitleState.isEnabled && !anyTrackSelected,
                icon = Icons.Rounded.SubtitlesOff,
                onClick = onDisableSubtitles
            )

            tracks.forEach { track ->
                SubtitleTrackRowItem(
                    label = track.label,
                    subtitle = if (track.isExternal) "External file · Supports millisecond sync" else track.language,
                    selected = !autoSubtitleState.isEnabled && track.isSelected,
                    icon = Icons.Rounded.Subtitles,
                    onClick = { onSelectTrack(track) }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            // Subtitle Position Slider (5% Top to 95% Bottom)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Subtitle Position", style = MaterialTheme.typography.titleSmall)
                    val pct = (verticalPositionFraction * 100f).toInt()
                    Text(
                        text = "$pct% (${when {
                            pct <= 25 -> "Top"
                            pct >= 75 -> "Bottom"
                            else -> "Middle"
                        }})",
                        style = TabularTimeStyle,
                        color = VoxoraCyan
                    )
                }
                Slider(
                    value = verticalPositionFraction,
                    onValueChange = onVerticalPositionChange,
                    valueRange = 0.05f..0.95f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple),
                    modifier = Modifier.testTag("subtitle_position_slider")
                )
                Text(
                    text = "Tip: Drag subtitles directly up or down on the video screen anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            // Primary Text Color Chips
            SubtitleColorChipRow(
                title = "Primary Text Color",
                selected = textColor,
                onSelect = onTextColorChange,
                tagPrefix = "subtitle_color_"
            )

            // Primary Font Size (11sp - 26sp, default 15sp)
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Primary Font Size", style = MaterialTheme.typography.titleSmall)
                    Text("${fontSizeSp.toInt()} sp", style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = fontSizeSp.coerceIn(11f, 26f),
                    onValueChange = onFontSizeChange,
                    valueRange = 11f..26f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraElectricPurpleBright, activeTrackColor = VoxoraElectricPurple)
                )
            }

            // Secondary (Dual) Subtitle Independent Styling
            if (autoSubtitleState.dualSubtitlesEnabled) {
                SubtitleColorChipRow(
                    title = "Secondary (Dual) Color",
                    selected = secondaryTextColor,
                    onSelect = onSecondaryTextColorChange,
                    tagPrefix = "secondary_subtitle_color_"
                )
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Secondary Font Size", style = MaterialTheme.typography.titleSmall)
                        Text("${secondaryFontSizeSp.toInt()} sp", style = TabularTimeStyle, color = VoxoraCyan)
                    }
                    Slider(
                        value = secondaryFontSizeSp.coerceIn(11f, 26f),
                        onValueChange = onSecondaryFontSizeChange,
                        valueRange = 11f..26f,
                        colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                    )
                }
            }

            // Background Box Opacity
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Background Opacity", style = MaterialTheme.typography.titleSmall)
                    Text("${(backgroundOpacity * 100f).toInt()}%", style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = backgroundOpacity,
                    onValueChange = onBackgroundOpacityChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraElectricPurpleBright, activeTrackColor = VoxoraElectricPurple)
                )
            }

            // Sync Delay Adjustment
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { onAdjustSync(-500L) }, modifier = Modifier.weight(1f)) { Text("-0.5s") }
                    OutlinedButton(onClick = { onAdjustSync(-100L) }, modifier = Modifier.weight(1f)) { Text("-0.1s") }
                    OutlinedButton(onClick = onResetSync, modifier = Modifier.weight(1f)) { Text("0.0s") }
                    OutlinedButton(onClick = { onAdjustSync(100L) }, modifier = Modifier.weight(1f)) { Text("+0.1s") }
                    OutlinedButton(onClick = { onAdjustSync(500L) }, modifier = Modifier.weight(1f)) { Text("+0.5s") }
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectorTile(
    title: String,
    value: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF10131C),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Translate, contentDescription = null, tint = VoxoraCyan, modifier = Modifier.size(18.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
                    Text(value, style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
            }
            Text("Change (59+)", style = MaterialTheme.typography.labelMedium, color = VoxoraElectricPurpleBright)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleColorChipRow(
    title: String,
    selected: SubtitleTextColor,
    onSelect: (SubtitleTextColor) -> Unit,
    tagPrefix: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubtitleTextColor.entries.forEach { colorOption ->
                val isSelected = selected == colorOption
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(colorOption) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(colorOption.colorHex))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                        )
                    },
                    label = { Text(colorOption.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VoxoraElectricPurple,
                        selectedLabelColor = Color.White,
                        labelColor = Color.White.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier.testTag("$tagPrefix${colorOption.name}")
                )
            }
        }
    }
}

@Composable
private fun SubtitleTrackRowItem(
    label: String,
    subtitle: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = VoxoraCyan)
            }
        }
    }
}
