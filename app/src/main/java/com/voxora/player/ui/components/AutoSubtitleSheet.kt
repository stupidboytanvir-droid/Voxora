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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.subtitles.AutoSubtitleLanguageCatalog
import com.voxora.player.ai.subtitles.AutoSubtitleUiState
import com.voxora.player.model.SubtitleTextColor
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.util.FormatUtils

/**
 * Unified Subtitle & Free Live Auto AI Subtitles modal sheet for VOXORA v2.3.0.
 *
 * Includes:
 * - Free 100% On-Device Auto AI Subtitles toggle + One-Click Setup progress
 * - Searchable 59+ Target Subtitle Language picker (ML Kit + Original Auto-Detect)
 * - Subtitle Position slider (0% Top to 100% Bottom) + direct screen drag hint
 * - Font Size, Text Color (White/Yellow/Cyan), Background Opacity, and Sync Delay controls
 * - Embedded & External .SRT/.VTT track selector
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutoSubtitleSettingsSheet(
    autoSubtitleState: AutoSubtitleUiState,
    tracks: List<SubtitleTrackInfo>,
    fontSizeSp: Float,
    verticalPositionFraction: Float,
    textColor: SubtitleTextColor,
    backgroundOpacity: Float,
    syncOffsetMs: Long,
    onToggleAutoSubtitles: (Boolean) -> Unit,
    onSelectAutoTargetLanguage: (String) -> Unit,
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
    var showLanguagePicker by remember { mutableStateOf(false) }
    var languageSearchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VoxoraCharcoal,
        contentColor = Color.White
    ) {
        if (showLanguagePicker) {
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
                        Text("Subtitle Language", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${AutoSubtitleLanguageCatalog.allLanguages.size} languages · 100% Free On-Device",
                            style = MaterialTheme.typography.bodySmall,
                            color = VoxoraCyan
                        )
                    }
                    TextButton(onClick = { showLanguagePicker = false }) {
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
                        val isSelected = lang.code.equals(autoSubtitleState.targetLanguageCode, ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onSelectAutoTargetLanguage(lang.code)
                                    showLanguagePicker = false
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
                                    Text(
                                        text = lang.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${lang.nativeName} · ${lang.code.uppercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.65f)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = VoxoraCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return@ModalBottomSheet
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
                    Text("Load .SRT/.VTT")
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
                                    .background(
                                        Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraCyan))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Auto AI Subtitles",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "100% Free On-Device · Any Movie Language",
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

                    // Target Language Selector Pill
                    Surface(
                        onClick = { showLanguagePicker = true },
                        color = Color(0xFF10131C),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auto_subtitles_language_picker_button")
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
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = VoxoraCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "Target Subtitle Language",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.65f)
                                    )
                                    Text(
                                        text = autoSubtitleState.targetLanguageLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            Text(
                                text = "Change (59+)",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoxoraElectricPurpleBright
                            )
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

            // Subtitle Position Slider (0% Top to 100% Bottom)
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
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraCyan,
                        activeTrackColor = VoxoraElectricPurple
                    ),
                    modifier = Modifier.testTag("subtitle_position_slider")
                )
                Text(
                    text = "Tip: You can also drag subtitles directly up or down on the video screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            // Text Color Chips (White / Yellow / Cyan)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text Color", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubtitleTextColor.entries.forEach { colorOption ->
                        val selected = textColor == colorOption
                        FilterChip(
                            selected = selected,
                            onClick = { onTextColorChange(colorOption) },
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
                            modifier = Modifier.testTag("subtitle_color_${colorOption.name}")
                        )
                    }
                }
            }

            // Font Size
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Font Size", style = MaterialTheme.typography.titleSmall)
                    Text("${fontSizeSp.toInt()} sp", style = TabularTimeStyle, color = VoxoraCyan)
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

            // Background Box Opacity
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Background Opacity", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${(backgroundOpacity * 100f).toInt()}%",
                        style = TabularTimeStyle,
                        color = VoxoraCyan
                    )
                }
                Slider(
                    value = backgroundOpacity,
                    onValueChange = onBackgroundOpacityChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraElectricPurpleBright,
                        activeTrackColor = VoxoraElectricPurple
                    )
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
