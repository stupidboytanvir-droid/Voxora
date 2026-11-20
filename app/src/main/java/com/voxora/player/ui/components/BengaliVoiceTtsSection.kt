package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.ai.tts.BengaliTtsModelFiles
import com.voxora.player.ai.tts.BengaliTtsModelSlot
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.BengaliTtsSlotAvailability
import com.voxora.player.ai.tts.BengaliVoiceGenerationUiState
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.whisper.TranscriptExporter
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils
import java.util.Locale

/**
 * VOXORA Phase 5 — Real Offline Bengali Voice Generation (Sherpa-ONNX VITS TTS) tab.
 *
 * Strictly honest: displays "Bengali TTS Model Required / Not Installed" if VITS model files are absent,
 * synthesizes real `.wav` audio files per translated dialogue line on Dispatchers.IO, and lets the user
 * play & verify every generated Bengali line with its assigned Character voice directly inside the studio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BengaliVoiceTtsSection(
    videoTitle: String,
    translatedSegmentCount: Int,
    modelState: BengaliTtsModelState,
    slotAvailability: BengaliTtsSlotAvailability,
    installedFiles: BengaliTtsModelFiles?,
    voiceState: BengaliVoiceGenerationUiState,
    characters: List<CharacterVoiceProfile>,
    onCheckModelStatus: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportLocalFile: (BengaliTtsModelSlot) -> Unit,
    onDeleteModels: () -> Unit,
    onStartVoiceGeneration: () -> Unit,
    onStopVoiceGeneration: () -> Unit,
    onTogglePlaySegment: (DubbedAudioSegment) -> Unit,
    onClearSynthesizedAudio: () -> Unit,
    onSwitchToTranslateTab: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    onOverrideSegmentCharacter: (segmentIndex: Int, characterId: String) -> Unit
) {
    val isModelReady = modelState is BengaliTtsModelState.Ready
    var overrideDialogSegmentIndex by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Offline Sherpa-ONNX VITS Bengali Model Status Card
        BengaliTtsModelStatusCard(
            modelState = modelState,
            slotAvailability = slotAvailability,
            installedFiles = installedFiles,
            isSynthesizing = voiceState.isSynthesizing,
            onCheckModelStatus = onCheckModelStatus,
            onDownloadModels = onDownloadModels,
            onCancelModelDownload = onCancelModelDownload,
            onImportLocalFile = onImportLocalFile,
            onDeleteModels = onDeleteModels
        )

        // 2. Bengali Voice Generation Controls & Progress Card
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (voiceState.isSynthesizing) VoxoraElectricPurple else Color.White.copy(alpha = 0.09f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = null,
                            tint = VoxoraElectricPurpleBright,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Offline Bengali Voice (TTS)",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                            Text(
                                text = if (videoTitle.isNotBlank()) videoTitle else "Current local video",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Surface(
                        color = VoxoraSubtleBlue.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "VITS · বাংলা",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = VoxoraSubtleBlue,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                if (translatedSegmentCount == 0) {
                    Surface(
                        color = Color(0xFF131521),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Translate English speech to Bengali first in the Translate tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            OutlinedButton(
                                onClick = onSwitchToTranslateTab,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Translate")
                            }
                        }
                    }
                }

                // Generate Bengali Voice / Stop buttons (48dp touch targets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartVoiceGeneration,
                        enabled = isModelReady && translatedSegmentCount > 0 && !voiceState.isSynthesizing,
                        colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("bengali_tts_generate_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (voiceState.segments.isNotEmpty() && !voiceState.isSynthesizing) {
                                "Regenerate Voice"
                            } else {
                                "Generate Bengali Voice"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onStopVoiceGeneration,
                        enabled = voiceState.isSynthesizing,
                        border = BorderStroke(
                            1.dp,
                            if (voiceState.isSynthesizing) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.14f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (voiceState.isSynthesizing) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("bengali_tts_stop_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                if (!isModelReady) {
                    Text(
                        text = "Bengali TTS Model Required / Not Installed — download or import the VITS Bengali model pack above (or use One-Click AI Setup).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }

                // Live synthesis progress bar (X / Y dialogue lines synthesized)
                if (voiceState.isSynthesizing || voiceState.totalCount > 0 || voiceState.statusMessage != "Idle") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = voiceState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = "${voiceState.synthesizedCount} / ${voiceState.totalCount.coerceAtLeast(translatedSegmentCount)}",
                                style = TabularTimeStyle,
                                color = VoxoraElectricPurpleBright
                            )
                        }
                        LinearProgressIndicator(
                            progress = { voiceState.progressFraction.coerceIn(0f, 1f) },
                            color = VoxoraElectricPurpleBright,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                if (!voiceState.errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = voiceState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 3. Synthesized Bengali Voice Dialogue List with Per-Line Audition Playback
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Synthesized Bengali Audio (${voiceState.segments.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    if (voiceState.segments.isNotEmpty() && !voiceState.isSynthesizing) {
                        OutlinedButton(
                            onClick = onClearSynthesizedAudio,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }

                if (voiceState.segments.isEmpty()) {
                    Text(
                        text = if (voiceState.isSynthesizing) {
                            "Generating natural Bengali character audio (.wav) offline via Sherpa-ONNX VITS..."
                        } else {
                            "No synthesized Bengali voice lines yet. Tap Generate Bengali Voice above to render each character's Bengali dialogue offline and preview every line here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f)
                    )
                } else {
                    voiceState.segments.forEachIndexed { listIdx, segment ->
                        if (listIdx > 0) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        }
                        val isPlaying = voiceState.currentlyPlayingSegmentIndex == segment.segmentIndex
                        val assignedCharacter = characters.firstOrNull { it.characterId == segment.characterId }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("dubbed_segment_row_${segment.segmentIndex}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Surface(
                                        color = VoxoraElectricPurple.copy(alpha = 0.22f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSeekToTimestamp(segment.startMs) }
                                    ) {
                                        Text(
                                            text = TranscriptExporter.formatReadableTimestamp(segment.startMs),
                                            style = TabularTimeStyle,
                                            color = VoxoraElectricPurpleBright,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }

                                    if (characters.isNotEmpty()) {
                                        DialogueCharacterBadge(
                                            character = assignedCharacter,
                                            characters = characters,
                                            onClick = { overrideDialogSegmentIndex = segment.segmentIndex }
                                        )
                                    }

                                    Surface(
                                        color = Color.White.copy(alpha = 0.06f),
                                        shape = RoundedCornerShape(7.dp)
                                    ) {
                                        val durSec = String.format(Locale.US, "%.1fs", segment.durationMs / 1000f)
                                        val spd = String.format(Locale.US, "%.2fx", segment.appliedSpeed)
                                        Text(
                                            text = "$durSec · $spd",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = segment.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 48dp Play/Stop button to audition the synthesized Bengali WAV line
                            Surface(
                                color = if (isPlaying) VoxoraElectricPurple else VoxoraSubtleBlue.copy(alpha = 0.18f),
                                shape = CircleShape,
                                border = BorderStroke(
                                    1.dp,
                                    if (isPlaying) VoxoraElectricPurpleBright else VoxoraSubtleBlue.copy(alpha = 0.55f)
                                )
                            ) {
                                IconButton(
                                    onClick = { onTogglePlaySegment(segment) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("dubbed_segment_play_${segment.segmentIndex}")
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Stop Bengali voice preview" else "Play generated Bengali voice",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val targetIdx = overrideDialogSegmentIndex
    val targetSegment = targetIdx?.let { idx -> voiceState.segments.firstOrNull { it.segmentIndex == idx } }
    if (targetIdx != null && targetSegment != null) {
        CharacterOverrideDialog(
            segmentStartMs = targetSegment.startMs,
            dialoguePreview = targetSegment.text,
            currentCharacterId = targetSegment.characterId,
            characters = characters,
            onSelectCharacter = { newCharId ->
                onOverrideSegmentCharacter(targetSegment.segmentIndex, newCharId)
            },
            onSeekToSegment = { onSeekToTimestamp(targetSegment.startMs) },
            onDismiss = { overrideDialogSegmentIndex = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BengaliTtsModelStatusCard(
    modelState: BengaliTtsModelState,
    slotAvailability: BengaliTtsSlotAvailability,
    installedFiles: BengaliTtsModelFiles?,
    isSynthesizing: Boolean,
    onCheckModelStatus: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportLocalFile: (BengaliTtsModelSlot) -> Unit,
    onDeleteModels: () -> Unit
) {
    val badgeColor = when (modelState) {
        is BengaliTtsModelState.Ready -> Color(0xFF4ADE80)
        is BengaliTtsModelState.Downloading -> VoxoraSubtleBlue
        is BengaliTtsModelState.NotInstalled -> Color(0xFFFBBF24)
        is BengaliTtsModelState.Error -> MaterialTheme.colorScheme.error
    }

    val badgeLabel = when (modelState) {
        is BengaliTtsModelState.Ready -> "READY · OFFLINE"
        is BengaliTtsModelState.Downloading -> "DOWNLOADING ${modelState.progress}%"
        is BengaliTtsModelState.NotInstalled -> "MODEL REQUIRED"
        is BengaliTtsModelState.Error -> "ERROR"
    }

    Surface(
        color = Color(0xFF151826),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (modelState is BengaliTtsModelState.Ready) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Offline Bengali Voice Model",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "Sherpa-ONNX VITS Bengali (~109 MB · 100% Free)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeColor,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("bengali_tts_model_status_badge")
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TtsSlotRow(
                    label = "1. VITS Bengali Neural Model (model.onnx)",
                    installed = slotAvailability.hasModelOnnx,
                    sizeBytes = slotAvailability.modelSizeBytes
                )
                TtsSlotRow(
                    label = "2. Bengali Character Table (tokens.txt)",
                    installed = slotAvailability.hasTokensTxt,
                    sizeBytes = slotAvailability.tokensSizeBytes
                )
            }

            when (modelState) {
                is BengaliTtsModelState.NotInstalled -> {
                    Text(
                        text = "Bengali TTS Model Required / Not Installed. Download the open-source VITS Bengali pack once (~109 MB) or import local files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                is BengaliTtsModelState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = VoxoraSubtleBlue
                            )
                            Text(
                                text = "${modelState.stageLabel} (${modelState.progress}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (modelState.progress / 100f).coerceIn(0f, 1f) },
                            color = VoxoraSubtleBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                is BengaliTtsModelState.Ready -> {
                    val sizeText = installedFiles?.let { FormatUtils.formatFileSize(it.totalSizeBytes) } ?: "~109 MB"
                    Text(
                        text = "VITS Bengali model pack verified ($sizeText) in private app storage. Voice synthesis & Character Previews run 100% offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.84f)
                    )
                }
                is BengaliTtsModelState.Error -> {
                    Text(
                        text = modelState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (modelState) {
                    is BengaliTtsModelState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancelModelDownload,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bengali_tts_cancel_download_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Cancel Download")
                        }
                    }
                    is BengaliTtsModelState.Ready -> {
                        OutlinedButton(
                            onClick = onCheckModelStatus,
                            enabled = !isSynthesizing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Verify")
                        }
                        OutlinedButton(
                            onClick = onDeleteModels,
                            enabled = !isSynthesizing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bengali_tts_delete_models_button")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Model")
                        }
                    }
                    is BengaliTtsModelState.NotInstalled,
                    is BengaliTtsModelState.Error -> {
                        Button(
                            onClick = onDownloadModels,
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bengali_tts_download_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Bengali Voice (~109 MB)")
                        }
                        OutlinedButton(
                            onClick = { onImportLocalFile(BengaliTtsModelSlot.MODEL_ONNX) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bengali_tts_import_onnx_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Import model.onnx")
                        }
                        OutlinedButton(
                            onClick = { onImportLocalFile(BengaliTtsModelSlot.TOKENS_TXT) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("bengali_tts_import_tokens_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Import tokens.txt")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsSlotRow(
    label: String,
    installed: Boolean,
    sizeBytes: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Text(
            text = if (installed) "Installed (${FormatUtils.formatFileSize(sizeBytes)})" else "Missing",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (installed) Color(0xFF4ADE80) else Color(0xFFFBBF24)
        )
    }
}
