package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.model.ABLoopState
import com.voxora.player.model.AudioEqPreset
import com.voxora.player.model.DisplayFilterSettings
import com.voxora.player.model.SceneBookmark
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCardCharcoal
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraCyan
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraGlassDark
import com.voxora.player.ui.viewmodel.UpNextUiState
import com.voxora.player.util.FormatUtils
import kotlinx.coroutines.delay

/**
 * Audio FX Modal Sheet: 0% to +200% Volume Booster (LoudnessEnhancer) + 5-Band Equalizer Presets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioFxSheet(
    volumeBoostPercent: Int,
    selectedPreset: AudioEqPreset,
    onVolumeBoostChange: (Int) -> Unit,
    onSelectPreset: (AudioEqPreset) -> Unit,
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
                    Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = VoxoraCyan)
                    Text("Audio FX & Booster", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = {
                    onVolumeBoostChange(0)
                    onSelectPreset(AudioEqPreset.NORMAL)
                }) {
                    Text("Reset", color = VoxoraElectricPurpleBright)
                }
            }

            // 200% Volume Booster Card
            Surface(
                color = VoxoraCardCharcoal,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = VoxoraCyan)
                            Text("Volume Booster", style = MaterialTheme.typography.titleSmall)
                        }
                        Surface(
                            color = if (volumeBoostPercent > 100) Color(0x33F59E0B) else VoxoraElectricPurple.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "+${volumeBoostPercent}% (${100 + volumeBoostPercent}%)",
                                style = TabularTimeStyle,
                                color = if (volumeBoostPercent > 100) Color(0xFFFBBF24) else VoxoraCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Slider(
                        value = volumeBoostPercent.toFloat(),
                        onValueChange = { onVolumeBoostChange(it.toInt()) },
                        valueRange = 0f..200f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraCyan,
                            activeTrackColor = VoxoraElectricPurple
                        ),
                        modifier = Modifier.testTag("audio_fx_boost_slider")
                    )
                    Text(
                        text = "Boosts quiet movies & dialogue up to +200% using Android LoudnessEnhancer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }

            Text("Equalizer Presets", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioEqPreset.entries.forEach { preset ->
                    val selected = preset == selectedPreset
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VoxoraElectricPurple,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.testTag("audio_eq_preset_${preset.name}")
                    )
                }
            }
        }
    }
}

/**
 * Display Settings Sheet: Night Mode / Eye Care warm filter, Brightness, Contrast, Saturation,
 * Horizontal Mirror, and 90° Manual Rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsSheet(
    filters: DisplayFilterSettings,
    onEyeCareToggle: (Boolean) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onToggleMirror: () -> Unit,
    onRotate90: () -> Unit,
    onReset: () -> Unit,
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Visibility, contentDescription = null, tint = VoxoraCyan)
                    Text("Display & Eye Care", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onReset) {
                    Text("Reset", color = VoxoraElectricPurpleBright)
                }
            }

            // Eye Care Warm Blue-Light Filter Toggle
            Surface(
                color = VoxoraCardCharcoal,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (filters.eyeCareEnabled) Color(0x99F59E0B) else Color.White.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Night Mode / Eye Care", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Warm amber blue-light filter for comfortable night viewing",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = filters.eyeCareEnabled,
                        onCheckedChange = onEyeCareToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFD97706)
                        ),
                        modifier = Modifier.testTag("display_eye_care_switch")
                    )
                }
            }

            // Quick Mirror & 90° Rotate Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleMirror,
                    border = BorderStroke(
                        1.dp,
                        if (filters.isMirrored) VoxoraCyan else Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("display_mirror_button")
                ) {
                    Icon(Icons.Rounded.Flip, contentDescription = null, tint = if (filters.isMirrored) VoxoraCyan else Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (filters.isMirrored) "Mirrored ON" else "Mirror Flip")
                }
                OutlinedButton(
                    onClick = onRotate90,
                    border = BorderStroke(
                        1.dp,
                        if (filters.manualRotationDegrees != 0) VoxoraCyan else Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("display_rotate90_button")
                ) {
                    Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = null, tint = VoxoraCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Rotate ${filters.manualRotationDegrees}°")
                }
            }

            // Brightness, Contrast, Saturation Sliders
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Brightness", style = MaterialTheme.typography.titleSmall)
                    Text("${(filters.brightnessOffset * 100).toInt()}%", style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = filters.brightnessOffset,
                    onValueChange = onBrightnessChange,
                    valueRange = -0.5f..0.5f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Contrast", style = MaterialTheme.typography.titleSmall)
                    Text("${(filters.contrast * 100).toInt()}%", style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = filters.contrast,
                    onValueChange = onContrastChange,
                    valueRange = 0.5f..1.8f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Saturation", style = MaterialTheme.typography.titleSmall)
                    Text("${(filters.saturation * 100).toInt()}%", style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = filters.saturation,
                    onValueChange = onSaturationChange,
                    valueRange = 0f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                )
            }
        }
    }
}

/**
 * Scene Bookmarks Sheet: save named timestamp bookmarks & tap to jump straight to any scene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    currentPositionMs: Long,
    bookmarks: List<SceneBookmark>,
    onAddBookmark: (String) -> Unit,
    onJumpToBookmark: (Long) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var titleInput by remember { mutableStateOf("") }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, tint = VoxoraCyan)
                Text("Scene Bookmarks", style = MaterialTheme.typography.titleLarge)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    placeholder = { Text("Scene name at ${FormatUtils.formatDuration(currentPositionMs)}") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VoxoraCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bookmark_title_input")
                )
                Button(
                    onClick = {
                        val name = titleInput.trim().ifBlank {
                            "Scene ${FormatUtils.formatDuration(currentPositionMs)}"
                        }
                        onAddBookmark(name)
                        titleInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier.testTag("bookmark_add_button")
                ) {
                    Text("Save")
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks for this video yet. Save memorable scenes to see markers on the seekbar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onJumpToBookmark(bm.timestampMs)
                                    onDismiss()
                                },
                            color = VoxoraCardCharcoal,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bm.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = FormatUtils.formatDuration(bm.timestampMs),
                                        style = TabularTimeStyle,
                                        color = VoxoraCyan
                                    )
                                }
                                IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete bookmark",
                                        tint = Color.White.copy(alpha = 0.7f)
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

/**
 * A-B Loop Modal Sheet: Set Point A, Set Point B, and seamlessly loop between them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ABLoopSheet(
    currentPositionMs: Long,
    abLoop: ABLoopState,
    onSetA: () -> Unit,
    onSetB: () -> Unit,
    onClear: () -> Unit,
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Repeat, contentDescription = null, tint = VoxoraCyan)
                Text("A–B Loop (Repeat Segment)", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "Current Position: ${FormatUtils.formatDuration(currentPositionMs)}",
                style = TabularTimeStyle,
                color = VoxoraCyan
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSetA,
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ab_loop_set_a")
                ) {
                    Text(
                        text = abLoop.pointAMs?.let { "A: ${FormatUtils.formatDuration(it)}" } ?: "Set Point A"
                    )
                }
                Button(
                    onClick = onSetB,
                    colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ab_loop_set_b")
                ) {
                    Text(
                        text = abLoop.pointBMs?.let { "B: ${FormatUtils.formatDuration(it)}" } ?: "Set Point B"
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    onClear()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ab_loop_clear")
            ) {
                Text("Clear A–B Loop")
            }
        }
    }
}

/**
 * Lossless Clip Cutter Sheet using Android MediaExtractor + MediaMuxer (instant, zero re-encoding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipCutterSheet(
    currentPositionMs: Long,
    durationMs: Long,
    isCutting: Boolean,
    onCutAndSave: (startMs: Long, endMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(2_000L)
    var startMs by remember(durationMs) {
        mutableLongStateOf((currentPositionMs - 5_000L).coerceIn(0L, (safeDuration - 1_000L).coerceAtLeast(0L)))
    }
    var endMs by remember(durationMs) {
        mutableLongStateOf((startMs + 15_000L).coerceIn((startMs + 1_000L).coerceAtMost(safeDuration), safeDuration))
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.ContentCut, contentDescription = null, tint = VoxoraCyan)
                Text("Lossless Clip Cutter", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "Instant zero-reencoding trim saved to Movies/VOXORA/Clips/",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Clip Start", style = MaterialTheme.typography.titleSmall)
                    Text(FormatUtils.formatDuration(startMs), style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = startMs.toFloat(),
                    onValueChange = { next ->
                        startMs = next.toLong().coerceIn(0L, (endMs - 1_000L).coerceAtLeast(0L))
                    },
                    valueRange = 0f..safeDuration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Clip End", style = MaterialTheme.typography.titleSmall)
                    Text(FormatUtils.formatDuration(endMs), style = TabularTimeStyle, color = VoxoraCyan)
                }
                Slider(
                    value = endMs.toFloat(),
                    onValueChange = { next ->
                        endMs = next.toLong().coerceIn((startMs + 1_000L).coerceAtMost(safeDuration), safeDuration)
                    },
                    valueRange = 0f..safeDuration.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = VoxoraCyan, activeTrackColor = VoxoraElectricPurple)
                )
            }

            Text(
                text = "Selected Clip Length: ${FormatUtils.formatDuration((endMs - startMs).coerceAtLeast(0L))}",
                style = MaterialTheme.typography.bodySmall,
                color = VoxoraElectricPurpleBright
            )

            Button(
                onClick = { onCutAndSave(startMs, endMs) },
                enabled = !isCutting && endMs > startMs + 500L,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clip_cutter_save_button")
            ) {
                if (isCutting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saving Clip...")
                } else {
                    Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Lossless Clip")
                }
            }
        }
    }
}

/**
 * 5-second cancelable "Up Next" auto-play card shown at video end.
 */
@Composable
fun BoxScope.UpNextOverlayCard(
    upNextState: UpNextUiState,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    val nextVideo = upNextState.nextVideo ?: return
    if (!upNextState.isVisible) return

    Surface(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .safeDrawingPadding()
            .padding(20.dp)
            .width(290.dp)
            .testTag("player_up_next_card"),
        color = VoxoraGlassDark,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(listOf(VoxoraElectricPurple, VoxoraCyan))
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Up Next in ${upNextState.remainingSeconds}s",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = VoxoraCyan
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                }
            }
            Text(
                text = nextVideo.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { (upNextState.remainingSeconds / 5f).coerceIn(0f, 1f) },
                color = VoxoraCyan,
                trackColor = Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
            )
            Button(
                onClick = onPlayNow,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play Now")
            }
        }
    }
}

/**
 * Kids Lock full-screen touch shield: blocks ALL touches and gestures.
 * Unlocking requires holding the subtle corner lock badge continuously for 3 seconds.
 */
@Composable
fun BoxScope.KidsLockOverlay(
    onUnlock: () -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (!isHolding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val stepMs = 50L
        val totalMs = 3_000L
        var elapsed = 0L
        while (isHolding && elapsed < totalMs) {
            delay(stepMs)
            elapsed += stepMs
            holdProgress = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        }
        if (isHolding && elapsed >= totalMs) {
            isHolding = false
            holdProgress = 0f
            onUnlock()
        }
    }

    // Consume all touches across the entire player surface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
            }
            .testTag("player_kids_lock_shield")
    ) {
        Surface(
            color = VoxoraGlassDark.copy(alpha = if (isHolding) 0.92f else 0.55f),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                1.dp,
                if (isHolding) VoxoraCyan else Color.White.copy(alpha = 0.16f)
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(14.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isHolding = true
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        } finally {
                            isHolding = false
                        }
                    }
                }
                .testTag("kids_lock_hold_unlock_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                    CircularProgressIndicator(
                        progress = { holdProgress },
                        color = VoxoraCyan,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Icon(
                        imageVector = if (isHolding) Icons.Rounded.Lock else Icons.Rounded.ChildCare,
                        contentDescription = "Hold 3 seconds to unlock Kids Lock",
                        tint = if (isHolding) VoxoraCyan else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (isHolding) "Hold to unlock..." else "Kids Lock · Hold 3s",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}
