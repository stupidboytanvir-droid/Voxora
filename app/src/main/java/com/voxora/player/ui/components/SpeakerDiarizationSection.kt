package com.voxora.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.voxora.player.ai.diarization.DetectedSpeakerSummary
import com.voxora.player.ai.diarization.DiarizationModelFiles
import com.voxora.player.ai.diarization.DiarizationModelSlot
import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.diarization.DiarizationSlotAvailability
import com.voxora.player.ai.diarization.SpeakerDiarizationUiState
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils
import java.util.Locale

/**
 * VOXORA Phase 4 — Real Offline Speaker Diarization & Automatic Character Mapping UI
 * powered by Sherpa-ONNX (`OfflineSpeakerDiarization`) with zero paid APIs and zero dummy speakers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerDiarizationSection(
    videoTitle: String,
    hasActiveVideo: Boolean,
    modelState: DiarizationModelState,
    slotAvailability: DiarizationSlotAvailability,
    installedFiles: DiarizationModelFiles?,
    diarizationState: SpeakerDiarizationUiState,
    characters: List<CharacterVoiceProfile>,
    onCheckModelStatus: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportLocalModel: (DiarizationModelSlot) -> Unit,
    onDeleteModels: () -> Unit,
    onSelectNumClusters: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onStartDiarization: () -> Unit,
    onStopDiarization: () -> Unit,
    onReassignSpeaker: (speakerIndex: Int, characterId: String) -> Unit
) {
    val isModelReady = modelState is DiarizationModelState.Ready

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Offline Sherpa-ONNX Diarization Models Card
        DiarizationModelStatusCard(
            modelState = modelState,
            slotAvailability = slotAvailability,
            installedFiles = installedFiles,
            isDiarizing = diarizationState.isRunning,
            onCheckModelStatus = onCheckModelStatus,
            onDownloadModels = onDownloadModels,
            onCancelModelDownload = onCancelModelDownload,
            onImportLocalModel = onImportLocalModel,
            onDeleteModels = onDeleteModels
        )

        // 2. Speaker Detection Controls & Progress Card
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (diarizationState.isRunning) VoxoraElectricPurple else Color.White.copy(alpha = 0.09f)
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
                                text = "Offline Speaker Diarization",
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
                            text = "SHERPA-ONNX",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = VoxoraSubtleBlue,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // Speaker count selector (Auto = -1 or fixed 2..6)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Expected Speakers",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val options = listOf(-1 to "Auto", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6")
                        options.forEach { (value, label) ->
                            FilterChip(
                                selected = diarizationState.numClustersConfig == value,
                                enabled = !diarizationState.isRunning,
                                onClick = { onSelectNumClusters(value) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VoxoraElectricPurple,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("diarization_clusters_$value")
                            )
                        }
                    }
                }

                // Clustering threshold slider (used when Auto speaker count is active)
                if (diarizationState.numClustersConfig <= 0) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Clustering Threshold",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f", diarizationState.thresholdConfig),
                                style = TabularTimeStyle,
                                color = VoxoraSubtleBlue
                            )
                        }
                        Slider(
                            value = diarizationState.thresholdConfig,
                            onValueChange = onThresholdChange,
                            enabled = !diarizationState.isRunning,
                            valueRange = 0.25f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = VoxoraElectricPurpleBright,
                                activeTrackColor = VoxoraElectricPurple
                            ),
                            modifier = Modifier.testTag("diarization_threshold_slider")
                        )
                    }
                }

                // Detect Speakers / Stop Action Row (48dp touch targets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartDiarization,
                        enabled = isModelReady && hasActiveVideo && !diarizationState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("diarization_start_button")
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (diarizationState.detectedSegments.isNotEmpty() && !diarizationState.isRunning) {
                                "Re-Detect Speakers"
                            } else {
                                "Detect Speakers"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onStopDiarization,
                        enabled = diarizationState.isRunning,
                        border = BorderStroke(
                            1.dp,
                            if (diarizationState.isRunning) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.14f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (diarizationState.isRunning) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("diarization_stop_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                if (!isModelReady) {
                    Text(
                        text = "Diarization Model Required / Not Installed — download or import both offline ONNX models above to detect speakers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }

                // Real-time Progress Indicator
                if (diarizationState.isRunning || diarizationState.progressFraction > 0f || diarizationState.statusMessage != "Idle") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = diarizationState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = "${(diarizationState.progressFraction * 100).toInt()}%",
                                style = TabularTimeStyle,
                                color = VoxoraElectricPurpleBright
                            )
                        }
                        LinearProgressIndicator(
                            progress = { diarizationState.progressFraction.coerceIn(0f, 1f) },
                            color = VoxoraElectricPurpleBright,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                if (!diarizationState.errorMessage.isNullOrBlank()) {
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
                                text = diarizationState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 3. Speaker-to-Character Mapping Panel
        SpeakerToCharacterMappingPanel(
            summaries = diarizationState.detectedSpeakerSummaries,
            characters = characters,
            isRunning = diarizationState.isRunning,
            onReassignSpeaker = onReassignSpeaker
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiarizationModelStatusCard(
    modelState: DiarizationModelState,
    slotAvailability: DiarizationSlotAvailability,
    installedFiles: DiarizationModelFiles?,
    isDiarizing: Boolean,
    onCheckModelStatus: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportLocalModel: (DiarizationModelSlot) -> Unit,
    onDeleteModels: () -> Unit
) {
    val badgeColor = when (modelState) {
        is DiarizationModelState.Ready -> Color(0xFF4ADE80)
        is DiarizationModelState.Downloading -> VoxoraSubtleBlue
        is DiarizationModelState.NotInstalled -> Color(0xFFFBBF24)
        is DiarizationModelState.Error -> MaterialTheme.colorScheme.error
    }

    val badgeLabel = when (modelState) {
        is DiarizationModelState.Ready -> "READY · OFFLINE"
        is DiarizationModelState.Downloading -> "DOWNLOADING ${modelState.progress}%"
        is DiarizationModelState.NotInstalled -> "MODEL REQUIRED"
        is DiarizationModelState.Error -> "ERROR"
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
                        imageVector = if (modelState is DiarizationModelState.Ready) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.People
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Offline Diarization Models",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "Pyannote 3.0 + 3D-Speaker ERes2Net (~31 MB)",
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
                            .testTag("diarization_model_status_badge")
                    )
                }
            }

            // Two required ONNX slot indicators
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModelSlotStatusRow(
                    label = "1. Segmentation (pyannote-3.0 .onnx)",
                    installed = slotAvailability.hasSegmentationModel,
                    sizeBytes = slotAvailability.segmentationSizeBytes
                )
                ModelSlotStatusRow(
                    label = "2. Speaker Embedding (ERes2Net / TitaNet .onnx)",
                    installed = slotAvailability.hasEmbeddingModel,
                    sizeBytes = slotAvailability.embeddingSizeBytes
                )
            }

            when (modelState) {
                is DiarizationModelState.NotInstalled -> {
                    Text(
                        text = "Diarization Model Required / Not Installed. Download both open-source ONNX models once (~31 MB total) or import local .onnx files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                is DiarizationModelState.Downloading -> {
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
                is DiarizationModelState.Ready -> {
                    val totalSizeText = installedFiles?.let { FormatUtils.formatFileSize(it.totalSizeBytes) } ?: "~31 MB"
                    Text(
                        text = "Both Sherpa-ONNX models verified ($totalSizeText) in private app storage. Speaker detection runs 100% offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.84f)
                    )
                }
                is DiarizationModelState.Error -> {
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
                    is DiarizationModelState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancelModelDownload,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("diarization_cancel_download_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Cancel Download")
                        }
                    }
                    is DiarizationModelState.Ready -> {
                        OutlinedButton(
                            onClick = onCheckModelStatus,
                            enabled = !isDiarizing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Verify")
                        }
                        OutlinedButton(
                            onClick = { onImportLocalModel(DiarizationModelSlot.EMBEDDING) },
                            enabled = !isDiarizing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Replace Embedding .onnx")
                        }
                        OutlinedButton(
                            onClick = onDeleteModels,
                            enabled = !isDiarizing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("diarization_delete_models_button")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Models")
                        }
                    }
                    is DiarizationModelState.NotInstalled,
                    is DiarizationModelState.Error -> {
                        Button(
                            onClick = onDownloadModels,
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("diarization_download_models_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Models (~31 MB)")
                        }
                        OutlinedButton(
                            onClick = { onImportLocalModel(DiarizationModelSlot.SEGMENTATION) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("diarization_import_seg_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Import Segmentation")
                        }
                        OutlinedButton(
                            onClick = { onImportLocalModel(DiarizationModelSlot.EMBEDDING) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("diarization_import_emb_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Import Embedding")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSlotStatusRow(
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

@Composable
private fun SpeakerToCharacterMappingPanel(
    summaries: List<DetectedSpeakerSummary>,
    characters: List<CharacterVoiceProfile>,
    isRunning: Boolean,
    onReassignSpeaker: (speakerIndex: Int, characterId: String) -> Unit
) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.People,
                        contentDescription = null,
                        tint = VoxoraSubtleBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Speaker → Character Mapping (${summaries.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                }
            }

            if (summaries.isEmpty()) {
                Text(
                    text = if (isRunning) {
                        "Analyzing vocal clusters and matching against VOXORA Character profiles..."
                    } else {
                        "No speakers detected for this video yet. Tap Detect Speakers above — VOXORA will automatically map each voice to your Character profiles (and auto-create Character 3, 4... if needed)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.68f)
                )
            } else {
                summaries.forEachIndexed { idx, summary ->
                    if (idx > 0) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                    }
                    SpeakerMappingRow(
                        summary = summary,
                        characters = characters,
                        onReassignSpeaker = { newCharId ->
                            onReassignSpeaker(summary.speakerIndex, newCharId)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerMappingRow(
    summary: DetectedSpeakerSummary,
    characters: List<CharacterVoiceProfile>,
    onReassignSpeaker: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val assignedCharacter = characters.firstOrNull { it.characterId == summary.assignedCharacterId }
    val accent = if (assignedCharacter != null) {
        characterAccentColor(assignedCharacter.characterId, characters)
    } else {
        speakerIndexAccentColor(summary.speakerIndex)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("speaker_mapping_row_${summary.speakerIndex}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Column {
                Text(
                    text = "Speaker ${summary.speakerIndex + 1}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )
                Text(
                    text = "${summary.segmentCount} turns · ${FormatUtils.formatDuration(summary.totalSpeakingMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }
        }

        Box {
            OutlinedButton(
                onClick = { menuExpanded = true },
                border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("speaker_reassign_dropdown_${summary.speakerIndex}")
            ) {
                Text(
                    text = assignedCharacter?.label ?: "Select Character",
                    color = accent,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = "Reassign speaker character",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = VoxoraCharcoal
            ) {
                characters.forEach { profile ->
                    val itemAccent = characterAccentColor(profile.characterId, characters)
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(itemAccent)
                                )
                                Column {
                                    Text(
                                        text = profile.label,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = profile.gender.label,
                                        color = Color.White.copy(alpha = 0.62f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        },
                        trailingIcon = if (profile.characterId == assignedCharacter?.characterId) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = itemAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        onClick = {
                            menuExpanded = false
                            onReassignSpeaker(profile.characterId)
                        }
                    )
                }
            }
        }
    }
}
