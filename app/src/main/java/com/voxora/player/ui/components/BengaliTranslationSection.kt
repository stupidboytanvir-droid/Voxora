package com.voxora.player.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.voxora.player.ai.translation.BengaliTranslationUiState
import com.voxora.player.ai.translation.TranslationExportFormat
import com.voxora.player.ai.translation.TranslationExporter
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.whisper.TranscriptExporter
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue

/**
 * VOXORA Phase 3 — Real Offline English -> Bengali (বাংলা) Translation UI
 * backed by Google ML Kit On-Device Translation (~30 MB offline model, zero API keys).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BengaliTranslationSection(
    videoTitle: String,
    englishSegmentCount: Int,
    modelState: TranslationModelState,
    translationState: BengaliTranslationUiState,
    onCheckModelStatus: () -> Unit,
    onDownloadModel: (requireWifi: Boolean) -> Unit,
    onDeleteModel: () -> Unit,
    onStartTranslation: () -> Unit,
    onStopTranslation: () -> Unit,
    onClearCachedTranslation: () -> Unit,
    onExportTranslation: (TranslationExportFormat) -> Unit,
    onSwitchToTranscribeTab: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    characters: List<CharacterVoiceProfile> = emptyList(),
    segmentCharacterMap: Map<Int, String> = emptyMap(),
    onOverrideSegmentCharacter: (segmentIndex: Int, characterId: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var requireWifiForDownload by remember { mutableStateOf(false) }
    var copiedNotice by remember { mutableStateOf(false) }
    var overrideDialogSegmentIndex by remember { mutableStateOf<Int?>(null) }

    val isModelReady = modelState is TranslationModelState.Ready
    val hasEnglishTranscript = englishSegmentCount > 0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Offline ML Kit Translation Model Manager Card
        TranslationModelStatusCard(
            modelState = modelState,
            isTranslating = translationState.isTranslating,
            requireWifi = requireWifiForDownload,
            onToggleRequireWifi = { requireWifiForDownload = !requireWifiForDownload },
            onCheckModelStatus = onCheckModelStatus,
            onDownloadModel = { onDownloadModel(requireWifiForDownload) },
            onDeleteModel = onDeleteModel
        )

        // 2. Translate Controls & Real-Time Segment Progress Card
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (translationState.isTranslating) VoxoraElectricPurple else Color.White.copy(alpha = 0.09f)
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
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = VoxoraElectricPurpleBright,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Offline Bengali Translation",
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
                            text = "EN → বাংলা",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = VoxoraSubtleBlue,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // Translate / Stop Action Row (48dp touch targets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartTranslation,
                        enabled = isModelReady && hasEnglishTranscript && !translationState.isTranslating,
                        colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("translation_start_button")
                    ) {
                        Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (translationState.segments.isNotEmpty() && !translationState.isTranslating) {
                                "Re-Translate to Bengali"
                            } else {
                                "Translate to Bengali"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onStopTranslation,
                        enabled = translationState.isTranslating,
                        border = BorderStroke(
                            1.dp,
                            if (translationState.isTranslating) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.14f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (translationState.isTranslating) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("translation_stop_button")
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                // Honest Prerequisite Guidance
                if (!isModelReady) {
                    Text(
                        text = "Model Required / Not Downloaded — download the offline ML Kit English → Bengali (~30 MB) model above to enable translation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                } else if (!hasEnglishTranscript && translationState.segments.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Generate an English transcript with whisper.cpp first to translate dialogue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFBBF24),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        OutlinedButton(
                            onClick = onSwitchToTranscribeTab,
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_go_transcribe_button")
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Transcribe")
                        }
                    }
                }

                // Real-time Progress Bar (X / Y segments)
                if (translationState.isTranslating || translationState.totalSegments > 0 || translationState.statusMessage != "Idle") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = translationState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.86f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            if (translationState.totalSegments > 0) {
                                Text(
                                    text = "${translationState.completedSegments} / ${translationState.totalSegments}",
                                    style = TabularTimeStyle,
                                    color = VoxoraElectricPurpleBright,
                                    modifier = Modifier.testTag("translation_progress_counter")
                                )
                            }
                        }
                        if (translationState.isTranslating || translationState.totalSegments > 0) {
                            LinearProgressIndicator(
                                progress = { translationState.progressFraction },
                                color = VoxoraElectricPurpleBright,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }

                // Local Room Cache Indicator + Clear Cache Button
                if (translationState.segments.isNotEmpty() && !translationState.isTranslating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFF4ADE80).copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cached,
                                    contentDescription = null,
                                    tint = Color(0xFF4ADE80),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (translationState.isLoadedFromCache) {
                                        "Loaded from Room local cache"
                                    } else {
                                        "Saved in local cache for offline replay"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4ADE80)
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onClearCachedTranslation,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .testTag("translation_clear_cache_button")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear")
                        }
                    }
                }

                // Error Surface
                if (!translationState.errorMessage.isNullOrBlank()) {
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
                                text = translationState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 3. Export Bengali & Bilingual Subtitles / Text / JSON
        if (translationState.segments.isNotEmpty()) {
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
                            text = "Export Bengali Translation",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onExportTranslation(TranslationExportFormat.BENGALI_SRT) },
                            border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.75f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_export_bn_srt_button")
                        ) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Bengali .SRT")
                        }

                        OutlinedButton(
                            onClick = { onExportTranslation(TranslationExportFormat.BILINGUAL_SRT) },
                            border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.75f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_export_bilingual_srt_button")
                        ) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Bilingual .SRT")
                        }

                        OutlinedButton(
                            onClick = { onExportTranslation(TranslationExportFormat.TXT) },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_export_txt_button")
                        ) {
                            Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Export .TXT")
                        }

                        OutlinedButton(
                            onClick = { onExportTranslation(TranslationExportFormat.JSON) },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_export_json_button")
                        ) {
                            Text("Export .JSON")
                        }

                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "VOXORA Bengali Translation",
                                        translationState.fullBengaliText
                                    )
                                )
                                copiedNotice = true
                            },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(if (copiedNotice) "Copied" else "Copy বাংলা")
                        }

                        OutlinedButton(
                            onClick = {
                                val formatted = TranslationExporter.toTimestampedText(
                                    videoTitle = translationState.activeVideoTitle.ifBlank { videoTitle },
                                    segments = translationState.segments
                                )
                                shareTranslation(context, formatted, videoTitle)
                            },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_share_button")
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Share")
                        }
                    }

                    if (!translationState.exportFeedback.isNullOrBlank()) {
                        Text(
                            text = translationState.exportFeedback,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }
            }
        }

        // 4. Timestamped Bilingual Dialogue List (English Original + Bengali Translation)
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
                        text = "Bilingual Dialogue (${translationState.segments.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                    if (translationState.segments.isNotEmpty()) {
                        Text(
                            text = "Tap time to seek · Tap line to set speaker",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoxoraSubtleBlue
                        )
                    }
                }

                if (translationState.segments.isEmpty()) {
                    Text(
                        text = if (translationState.isTranslating) {
                            "Translating timestamped English dialogue to Bengali (বাংলা) offline on-device..."
                        } else {
                            "No Bengali translation generated yet. Once your English transcript and offline ML Kit model are ready, tap Translate to Bengali."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .testTag("translation_bilingual_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = translationState.segments,
                            key = { _, seg -> "${seg.index}_${seg.startMs}" }
                        ) { index, segment ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.06f),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            val resolvedCharId = segment.characterId ?: segmentCharacterMap[segment.index]
                            val assignedCharacter = characters.firstOrNull { it.characterId == resolvedCharId }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (characters.isNotEmpty()) {
                                            overrideDialogSegmentIndex = segment.index
                                        } else {
                                            onSeekToTimestamp(segment.startMs)
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                                    .testTag("translated_segment_${segment.index}"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                            .clickable { onSeekToTimestamp(segment.startMs) }
                                    ) {
                                        Text(
                                            text = "${TranscriptExporter.formatReadableTimestamp(segment.startMs)} → ${TranscriptExporter.formatReadableTimestamp(segment.endMs)}",
                                            style = TabularTimeStyle,
                                            color = VoxoraElectricPurpleBright,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (characters.isNotEmpty()) {
                                        DialogueCharacterBadge(
                                            character = assignedCharacter,
                                            characters = characters,
                                            onClick = { overrideDialogSegmentIndex = segment.index },
                                            modifier = Modifier.testTag("translated_segment_character_badge_${segment.index}")
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "EN",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Text(
                                        text = segment.originalText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.74f),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "বাংলা",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = VoxoraSubtleBlue,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Text(
                                        text = segment.translatedText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val targetSegIndex = overrideDialogSegmentIndex
    val targetSegment = targetSegIndex?.let { idx ->
        translationState.segments.firstOrNull { it.index == idx }
    }
    if (targetSegIndex != null && targetSegment != null) {
        val currentCharId = targetSegment.characterId ?: segmentCharacterMap[targetSegment.index]
        CharacterOverrideDialog(
            segmentStartMs = targetSegment.startMs,
            dialoguePreview = "${targetSegment.originalText} — ${targetSegment.translatedText}",
            currentCharacterId = currentCharId,
            characters = characters,
            onSelectCharacter = { newCharId ->
                onOverrideSegmentCharacter(targetSegment.index, newCharId)
            },
            onSeekToSegment = { onSeekToTimestamp(targetSegment.startMs) },
            onDismiss = { overrideDialogSegmentIndex = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationModelStatusCard(
    modelState: TranslationModelState,
    isTranslating: Boolean,
    requireWifi: Boolean,
    onToggleRequireWifi: () -> Unit,
    onCheckModelStatus: () -> Unit,
    onDownloadModel: () -> Unit,
    onDeleteModel: () -> Unit
) {
    val badgeColor = when (modelState) {
        is TranslationModelState.Ready -> Color(0xFF4ADE80)
        is TranslationModelState.Checking,
        is TranslationModelState.Downloading -> VoxoraSubtleBlue
        is TranslationModelState.NotDownloaded -> Color(0xFFFBBF24)
        is TranslationModelState.Error -> MaterialTheme.colorScheme.error
    }

    val badgeLabel = when (modelState) {
        is TranslationModelState.Ready -> "READY · OFFLINE"
        is TranslationModelState.Checking -> "CHECKING"
        is TranslationModelState.Downloading -> "DOWNLOADING"
        is TranslationModelState.NotDownloaded -> "MODEL REQUIRED"
        is TranslationModelState.Error -> "ERROR"
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
                        imageVector = if (modelState is TranslationModelState.Ready) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.Translate
                        },
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Offline Translation Model",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Text(
                            text = "Google ML Kit · English → বাংলা (~30 MB)",
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
                            .testTag("translation_model_status_badge")
                    )
                }
            }

            when (modelState) {
                is TranslationModelState.NotDownloaded -> {
                    Text(
                        text = "Model Required / Not Downloaded. Download the free ~30 MB ML Kit English → Bengali pack once — all translations run 100% offline with zero API keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                is TranslationModelState.Checking -> {
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
                            text = "Checking on-device ML Kit offline model storage...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                is TranslationModelState.Downloading -> {
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
                                text = "Downloading offline ML Kit English → Bengali model (~30 MB)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        }
                        LinearProgressIndicator(
                            color = VoxoraSubtleBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
                is TranslationModelState.Ready -> {
                    Text(
                        text = "English → Bengali (বাংলা) model is installed locally. Translation runs 100% offline with zero internet required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.84f)
                    )
                }
                is TranslationModelState.Error -> {
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
                    is TranslationModelState.Checking,
                    is TranslationModelState.Downloading -> Unit
                    is TranslationModelState.Ready -> {
                        OutlinedButton(
                            onClick = onCheckModelStatus,
                            enabled = !isTranslating,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Verify Status")
                        }
                        OutlinedButton(
                            onClick = onDeleteModel,
                            enabled = !isTranslating,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_delete_model_button")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Model")
                        }
                    }
                    is TranslationModelState.NotDownloaded,
                    is TranslationModelState.Error -> {
                        Button(
                            onClick = onDownloadModel,
                            colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("translation_download_model_button")
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download Offline Model (~30 MB)")
                        }
                        FilterChip(
                            selected = requireWifi,
                            onClick = onToggleRequireWifi,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = { Text("Wi-Fi Only") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VoxoraSubtleBlue.copy(alpha = 0.25f),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = VoxoraSubtleBlue
                            ),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        OutlinedButton(
                            onClick = onCheckModelStatus,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Check")
                        }
                    }
                }
            }
        }
    }
}

private fun shareTranslation(context: Context, content: String, videoTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "VOXORA Bengali Translation — ${videoTitle.ifBlank { "Video" }}")
        putExtra(Intent.EXTRA_TEXT, content)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share Bengali Translation"))
    }
}
