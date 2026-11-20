package com.voxora.player.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.whisper.TranscriptExportFormat
import com.voxora.player.dubbing.whisper.TranscriptExporter
import com.voxora.player.dubbing.whisper.WhisperModelCatalog
import com.voxora.player.dubbing.whisper.WhisperModelStatus
import com.voxora.player.dubbing.whisper.WhisperTranscriptionState
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils

/**
 * VOXORA Phase 2 — Real Offline English Speech Recognition UI (whisper.cpp native ARM64 JNI).
 *
 * Strict honesty:
 * - Never displays "Ready" unless [WhisperModelStatus.LoadedReady] is genuinely verified via JNI.
 * - Never generates fabricated text or simulated loading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhisperTranscriptionSection(
    videoTitle: String,
    hasActiveVideo: Boolean,
    modelStatus: WhisperModelStatus,
    transcriptionState: WhisperTranscriptionState,
    onDownloadBaseEn: () -> Unit,
    onImportLocalModel: () -> Unit,
    onCancelModelOperation: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onStartTranscription: () -> Unit,
    onStopTranscription: () -> Unit,
    onExportTranscript: (TranscriptExportFormat) -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    characters: List<CharacterVoiceProfile> = emptyList(),
    segmentCharacterMap: Map<Int, String> = emptyMap(),
    onOverrideSegmentCharacter: (segmentIndex: Int, characterId: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var copiedNotice by remember { mutableStateOf(false) }
    var overrideDialogSegmentIndex by remember { mutableStateOf<Int?>(null) }

    val isModelLoaded = modelStatus is WhisperModelStatus.LoadedReady
    val loadedModelName = when (modelStatus) {
        is WhisperModelStatus.LoadedReady -> modelStatus.model.fileName
        is WhisperModelStatus.InstalledUnloaded -> modelStatus.model.fileName
        is WhisperModelStatus.Loading -> modelStatus.model.fileName
        else -> WhisperModelCatalog.BASE_EN.fileName
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Offline Whisper Model Status & Manager Card
        WhisperModelStatusCard(
            modelStatus = modelStatus,
            isTranscribing = transcriptionState.isRunning,
            onDownloadBaseEn = onDownloadBaseEn,
            onImportLocalModel = onImportLocalModel,
            onCancelModelOperation = onCancelModelOperation,
            onLoadModel = onLoadModel,
            onUnloadModel = onUnloadModel,
            onDeleteModel = onDeleteModel
        )

        // 2. Start / Stop Transcription & Processing Progress Card
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (transcriptionState.isRunning) VoxoraElectricPurple else Color.White.copy(alpha = 0.09f)
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
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = VoxoraElectricPurpleBright,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Offline English Speech Recognition",
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
                            text = "16kHz PCM · EN",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = VoxoraSubtleBlue,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // Start / Stop Buttons (48dp touch targets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartTranscription,
                        enabled = isModelLoaded && hasActiveVideo && !transcriptionState.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("whisper_start_transcription_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Transcription")
                    }

                    OutlinedButton(
                        onClick = onStopTranscription,
                        enabled = transcriptionState.isRunning,
                        border = BorderStroke(
                            1.dp,
                            if (transcriptionState.isRunning) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.14f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (transcriptionState.isRunning) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("whisper_stop_transcription_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                if (!isModelLoaded) {
                    Text(
                        text = "Install and load the real Whisper base.en model above to enable 100% offline English transcription.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                }

                // Processing Progress Bar & Live Status
                if (transcriptionState.isRunning || transcriptionState.progressFraction > 0f || transcriptionState.phaseLabel != "Idle") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = transcriptionState.phaseLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = "${(transcriptionState.progressFraction * 100).toInt()}%",
                                style = TabularTimeStyle,
                                color = VoxoraElectricPurpleBright
                            )
                        }
                        LinearProgressIndicator(
                            progress = { transcriptionState.progressFraction.coerceIn(0f, 1f) },
                            color = VoxoraElectricPurpleBright,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        if (transcriptionState.totalDurationMs > 0L) {
                            Text(
                                text = "Audio processed: ${FormatUtils.formatDuration(transcriptionState.processedDurationMs)} / ${FormatUtils.formatDuration(transcriptionState.totalDurationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }

                if (!transcriptionState.errorMessage.isNullOrBlank()) {
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
                                text = transcriptionState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 3. Export Transcription Controls
        if (transcriptionState.segments.isNotEmpty()) {
            Surface(
                color = Color(0xFF151926),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SaveAlt,
                            contentDescription = null,
                            tint = VoxoraSubtleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Export Transcription",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onExportTranscript(TranscriptExportFormat.SRT) },
                            border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_export_srt_button")
                        ) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Export .SRT")
                        }

                        OutlinedButton(
                            onClick = { onExportTranscript(TranscriptExportFormat.TXT) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_export_txt_button")
                        ) {
                            Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Export .TXT")
                        }

                        OutlinedButton(
                            onClick = { onExportTranscript(TranscriptExportFormat.JSON) },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_export_json_button")
                        ) {
                            Text("Export .JSON")
                        }

                        OutlinedButton(
                            onClick = {
                                val text = TranscriptExporter.toTimestampedText(
                                    videoTitle = transcriptionState.activeVideoTitle.ifBlank { videoTitle },
                                    modelName = loadedModelName,
                                    segments = transcriptionState.segments
                                )
                                shareTranscript(context, text, videoTitle)
                            },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_share_button")
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Share")
                        }
                    }

                    if (!transcriptionState.exportFeedback.isNullOrBlank()) {
                        Text(
                            text = transcriptionState.exportFeedback,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }
        }

        // 4. Original English Text Summary Card
        if (transcriptionState.fullTranscriptText.isNotBlank()) {
            Surface(
                color = Color(0xFF181924),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Original English Text",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("VOXORA Transcript", transcriptionState.fullTranscriptText)
                                )
                                copiedNotice = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (copiedNotice) "Copied" else "Copy")
                        }
                    }
                    Text(
                        text = transcriptionState.fullTranscriptText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.testTag("whisper_full_english_text")
                    )
                }
            }
        }

        // 5. Timestamped Dialogue Segments
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Timestamped Dialogue (${transcriptionState.segments.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    if (transcriptionState.segments.isNotEmpty()) {
                        Text(
                            text = "Tap time to seek · Tap line to set speaker",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoxoraSubtleBlue
                        )
                    }
                }

                if (transcriptionState.segments.isEmpty()) {
                    Text(
                        text = if (transcriptionState.isRunning) {
                            "Listening and decoding English dialogue segments locally with whisper.cpp..."
                        } else {
                            "No transcription generated yet. Start offline recognition to see timestamped English dialogue here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                } else {
                    transcriptionState.segments.forEachIndexed { index, segment ->
                        if (index > 0) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        }
                        val resolvedCharId = segmentCharacterMap[index]
                            ?: characters.firstOrNull {
                                it.characterId == segment.speakerId || it.speakerId == segment.speakerId
                            }?.characterId
                        val assignedCharacter = characters.firstOrNull { it.characterId == resolvedCharId }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (characters.isNotEmpty()) {
                                        overrideDialogSegmentIndex = index
                                    } else {
                                        onSeekToTimestamp(segment.startTimeMs)
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .testTag("whisper_segment_${segment.id}"),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = VoxoraElectricPurple.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSeekToTimestamp(segment.startTimeMs) }
                                ) {
                                    Text(
                                        text = "${TranscriptExporter.formatReadableTimestamp(segment.startTimeMs)} → ${TranscriptExporter.formatReadableTimestamp(segment.endTimeMs)}",
                                        style = TabularTimeStyle,
                                        color = VoxoraElectricPurpleBright,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                                if (characters.isNotEmpty()) {
                                    DialogueCharacterBadge(
                                        character = assignedCharacter,
                                        characters = characters,
                                        onClick = { overrideDialogSegmentIndex = index },
                                        modifier = Modifier.testTag("whisper_segment_character_badge_$index")
                                    )
                                }
                            }
                            Text(
                                text = segment.originalText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                    }
                }
            }
        }
    }

    val targetIdx = overrideDialogSegmentIndex
    val targetSegment = targetIdx?.let { transcriptionState.segments.getOrNull(it) }
    if (targetIdx != null && targetSegment != null) {
        val currentCharId = segmentCharacterMap[targetIdx]
            ?: characters.firstOrNull {
                it.characterId == targetSegment.speakerId || it.speakerId == targetSegment.speakerId
            }?.characterId
        CharacterOverrideDialog(
            segmentStartMs = targetSegment.startTimeMs,
            dialoguePreview = targetSegment.originalText,
            currentCharacterId = currentCharId,
            characters = characters,
            onSelectCharacter = { newCharId ->
                onOverrideSegmentCharacter(targetIdx, newCharId)
            },
            onSeekToSegment = { onSeekToTimestamp(targetSegment.startTimeMs) },
            onDismiss = { overrideDialogSegmentIndex = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhisperModelStatusCard(
    modelStatus: WhisperModelStatus,
    isTranscribing: Boolean,
    onDownloadBaseEn: () -> Unit,
    onImportLocalModel: () -> Unit,
    onCancelModelOperation: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: () -> Unit
) {
    val badgeColor = when (modelStatus) {
        is WhisperModelStatus.LoadedReady -> Color(0xFF4ADE80)
        is WhisperModelStatus.Downloading,
        is WhisperModelStatus.Importing,
        is WhisperModelStatus.Verifying,
        is WhisperModelStatus.Loading -> VoxoraSubtleBlue
        is WhisperModelStatus.InstalledUnloaded -> Color(0xFFFBBF24)
        is WhisperModelStatus.NotInstalled -> Color(0xFFFBBF24)
        is WhisperModelStatus.Error -> MaterialTheme.colorScheme.error
    }

    val badgeLabel = when (modelStatus) {
        is WhisperModelStatus.LoadedReady -> "READY · LOADED"
        is WhisperModelStatus.Downloading -> "DOWNLOADING"
        is WhisperModelStatus.Importing -> "IMPORTING"
        is WhisperModelStatus.Verifying -> "VERIFYING"
        is WhisperModelStatus.Loading -> "LOADING NATIVE"
        is WhisperModelStatus.InstalledUnloaded -> "INSTALLED · UNLOADED"
        is WhisperModelStatus.NotInstalled -> "MODEL NOT INSTALLED"
        is WhisperModelStatus.Error -> "ERROR"
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
                        imageVector = if (modelStatus is WhisperModelStatus.LoadedReady) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.Memory
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Model Status · whisper.cpp",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = WhisperModelCatalog.BASE_EN.displayName,
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
                            .testTag("whisper_model_status_badge")
                    )
                }
            }

            when (modelStatus) {
                is WhisperModelStatus.NotInstalled -> {
                    Text(
                        text = "Download the open-source Whisper base.en model (~141 MB) once or import a local ggml-base.en.bin file. Stored in private app storage; works 100% offline afterward.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                is WhisperModelStatus.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Downloading ggml-base.en.bin (${FormatUtils.formatFileSize(modelStatus.downloadedBytes)} / ${FormatUtils.formatFileSize(modelStatus.totalBytes)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Text(
                                text = "${(modelStatus.progressFraction * 100).toInt()}%",
                                style = TabularTimeStyle,
                                color = VoxoraSubtleBlue
                            )
                        }
                        LinearProgressIndicator(
                            progress = { modelStatus.progressFraction.coerceIn(0f, 1f) },
                            color = VoxoraSubtleBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                is WhisperModelStatus.Importing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Copying local model to app storage (${FormatUtils.formatFileSize(modelStatus.copiedBytes)})...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        LinearProgressIndicator(
                            progress = { modelStatus.progressFraction.coerceIn(0f, 1f) },
                            color = VoxoraSubtleBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                is WhisperModelStatus.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoxoraSubtleBlue)
                        Text(
                            text = modelStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                is WhisperModelStatus.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoxoraElectricPurpleBright)
                        Text(
                            text = "Initializing native whisper.cpp engine with ${modelStatus.model.fileName}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                is WhisperModelStatus.InstalledUnloaded -> {
                    val m = modelStatus.model
                    Text(
                        text = "${m.fileName} · ${FormatUtils.formatFileSize(m.sizeBytes)} · ${m.headerFormat} verified\nSHA-256: ${m.sha256.take(16)}…${if (m.matchesOfficialChecksum) " (Official base.en)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
                is WhisperModelStatus.LoadedReady -> {
                    val m = modelStatus.model
                    Text(
                        text = "${m.fileName} (${FormatUtils.formatFileSize(m.sizeBytes)}) loaded in native memory · Offline Ready\nSHA-256: ${m.sha256.take(16)}… · ${modelStatus.nativeSystemInfo.take(90)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.84f)
                    )
                }
                is WhisperModelStatus.Error -> {
                    Text(
                        text = modelStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Action Buttons
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (modelStatus) {
                    is WhisperModelStatus.Downloading,
                    is WhisperModelStatus.Importing -> {
                        OutlinedButton(
                            onClick = onCancelModelOperation,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Cancel")
                        }
                    }
                    is WhisperModelStatus.Verifying,
                    is WhisperModelStatus.Loading -> Unit
                    is WhisperModelStatus.LoadedReady -> {
                        OutlinedButton(
                            onClick = onUnloadModel,
                            enabled = !isTranscribing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Unload Model")
                        }
                        OutlinedButton(
                            onClick = onImportLocalModel,
                            enabled = !isTranscribing,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Switch Local Model")
                        }
                        OutlinedButton(
                            onClick = onDeleteModel,
                            enabled = !isTranscribing,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    is WhisperModelStatus.InstalledUnloaded -> {
                        Button(
                            onClick = onLoadModel,
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_load_model_button")
                        ) {
                            Text("Load Model")
                        }
                        OutlinedButton(
                            onClick = onImportLocalModel,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Import Local .bin")
                        }
                        OutlinedButton(
                            onClick = onDeleteModel,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    is WhisperModelStatus.NotInstalled,
                    is WhisperModelStatus.Error -> {
                        Button(
                            onClick = onDownloadBaseEn,
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_download_model_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download base.en (~141 MB)")
                        }
                        OutlinedButton(
                            onClick = onImportLocalModel,
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("whisper_import_model_button")
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Select Local .bin")
                        }
                        if (modelStatus is WhisperModelStatus.Error && modelStatus.installedModel != null) {
                            OutlinedButton(
                                onClick = onLoadModel,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Retry Load")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shareTranscript(context: Context, content: String, videoTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "VOXORA English Transcript — ${videoTitle.ifBlank { "Video" }}")
        putExtra(Intent.EXTRA_TEXT, content)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share English Transcription"))
    }
}
