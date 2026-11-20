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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.voxora.player.ai.diarization.DiarizationModelFiles
import com.voxora.player.ai.diarization.DiarizationModelSlot
import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.diarization.DiarizationSlotAvailability
import com.voxora.player.ai.diarization.SpeakerDiarizationUiState
import com.voxora.player.ai.dubbing.AutoDubPipelineState
import com.voxora.player.ai.dubbing.DubbedExportState
import com.voxora.player.ai.dubbing.DubbingExportFormat
import com.voxora.player.ai.dubbing.LiveDubbingPlaybackState
import com.voxora.player.ai.translation.BengaliTranslationUiState
import com.voxora.player.ai.translation.TranslationExportFormat
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.ai.tts.BengaliTtsModelFiles
import com.voxora.player.ai.tts.BengaliTtsModelSlot
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.BengaliTtsSlotAvailability
import com.voxora.player.ai.tts.BengaliVoiceGenerationUiState
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.ai.tts.OneClickAiSetupState
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.ComponentReadiness
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.dubbing.DubbingLanguageRegistry
import com.voxora.player.dubbing.PlayerDubbingUiState
import com.voxora.player.ui.theme.TabularTimeStyle
import com.voxora.player.ui.theme.VoxoraCharcoal
import com.voxora.player.ui.theme.VoxoraElectricPurple
import com.voxora.player.dubbing.whisper.TranscriptExportFormat
import com.voxora.player.dubbing.whisper.WhisperModelStatus
import com.voxora.player.dubbing.whisper.WhisperTranscriptionState
import com.voxora.player.ui.theme.VoxoraElectricPurpleBright
import com.voxora.player.ui.theme.VoxoraSubtleBlue
import com.voxora.player.util.FormatUtils

private enum class DubbingStudioTab(val label: String) {
    CONTROLS("Controls"),
    TRANSCRIBE("Transcribe"),
    SPEAKERS("Speakers"),
    TRANSLATE("Translate"),
    VOICE_TTS("Voice / TTS"),
    CHARACTERS("Characters"),
    PIPELINE("Pipeline")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDubbingStudioSheet(
    videoTitle: String,
    state: PlayerDubbingUiState,
    whisperModelStatus: WhisperModelStatus,
    whisperTranscriptionState: WhisperTranscriptionState,
    translationModelState: TranslationModelState,
    translationUiState: BengaliTranslationUiState,
    diarizationModelState: DiarizationModelState,
    diarizationSlotAvailability: DiarizationSlotAvailability,
    diarizationInstalledFiles: DiarizationModelFiles?,
    diarizationUiState: SpeakerDiarizationUiState,
    bengaliTtsModelState: BengaliTtsModelState,
    bengaliTtsSlotAvailability: BengaliTtsSlotAvailability,
    bengaliTtsInstalledFiles: BengaliTtsModelFiles?,
    bengaliVoiceUiState: BengaliVoiceGenerationUiState,
    oneClickAiSetupState: OneClickAiSetupState,
    liveDubbingState: LiveDubbingPlaybackState,
    autoDubPipelineState: AutoDubPipelineState,
    dubbedExportState: DubbedExportState,
    onStartOneClickAiSetup: () -> Unit,
    onCancelOneClickAiSetup: () -> Unit,
    onStartAutoDubVideo: () -> Unit,
    onCancelAutoDubVideo: () -> Unit,
    onExportDubbedMedia: (DubbingExportFormat) -> Unit,
    onCancelDubbedExport: () -> Unit,
    onOpenExportedMedia: (uriString: String, mimeType: String) -> Unit,
    onShareExportedMedia: (uriString: String, mimeType: String, title: String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSelectSourceLanguage: (DubbingLanguage) -> Unit,
    onSelectTargetLanguage: (DubbingLanguage) -> Unit,
    onSelectOutputMode: (DubbingAudioOutputMode) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onDubbedVolumeChange: (Float) -> Unit,
    onAdjustSyncOffset: (Long) -> Unit,
    onSetSyncOffsetMs: (Long) -> Unit,
    onResetSyncOffset: () -> Unit,
    onPreserveBackgroundChange: (Boolean) -> Unit,
    onBgmDuckingStrengthChange: (Float) -> Unit,
    onVocalAttenuationChange: (Boolean) -> Unit,
    onFastMovieModeChange: (Boolean) -> Unit,
    onUseSherpaVitsExperimentalChange: (Boolean) -> Unit,
    onAddCharacter: (CharacterVoiceProfile) -> Unit,
    onUpdateCharacter: (CharacterVoiceProfile) -> Unit,
    onRemoveCharacter: (String) -> Unit,
    onResetDefaultCharacters: () -> Unit,
    onPreviewCharacterVoice: (CharacterVoiceProfile) -> Unit,
    onStopVoicePreview: () -> Unit,
    onDownloadWhisperModel: () -> Unit,
    onImportWhisperModel: () -> Unit,
    onCancelWhisperModelOp: () -> Unit,
    onLoadWhisperModel: () -> Unit,
    onUnloadWhisperModel: () -> Unit,
    onDeleteWhisperModel: () -> Unit,
    onStartTranscription: () -> Unit,
    onStopTranscription: () -> Unit,
    onExportTranscription: (TranscriptExportFormat) -> Unit,
    onCheckTranslationModelStatus: () -> Unit,
    onDownloadTranslationModel: (Boolean) -> Unit,
    onDeleteTranslationModel: () -> Unit,
    onStartTranslation: () -> Unit,
    onStopTranslation: () -> Unit,
    onClearCachedTranslation: () -> Unit,
    onExportTranslation: (TranslationExportFormat) -> Unit,
    onCheckDiarizationModelStatus: () -> Unit,
    onDownloadDiarizationModels: () -> Unit,
    onCancelDiarizationDownload: () -> Unit,
    onImportLocalDiarizationModel: (DiarizationModelSlot) -> Unit,
    onDeleteDiarizationModels: () -> Unit,
    onSelectDiarizationClusters: (Int) -> Unit,
    onDiarizationThresholdChange: (Float) -> Unit,
    onStartDiarization: () -> Unit,
    onStopDiarization: () -> Unit,
    onReassignSpeakerToCharacter: (speakerIndex: Int, characterId: String) -> Unit,
    onOverrideSegmentCharacter: (segmentIndex: Int, characterId: String) -> Unit,
    onCheckBengaliTtsModelStatus: () -> Unit,
    onDownloadBengaliTtsModels: () -> Unit,
    onCancelBengaliTtsDownload: () -> Unit,
    onImportLocalBengaliTtsFile: (BengaliTtsModelSlot) -> Unit,
    onDeleteBengaliTtsModels: () -> Unit,
    onStartBengaliVoiceGeneration: () -> Unit,
    onStopBengaliVoiceGeneration: () -> Unit,
    onTogglePlayDubbedSegment: (DubbedAudioSegment) -> Unit,
    onClearSynthesizedBengaliAudio: () -> Unit,
    onSeekToTimestamp: (Long) -> Unit,
    onInspectAudioTrack: () -> Unit,
    onRunStage1Extraction: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(DubbingStudioTab.CONTROLS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
            // Studio Header with Master Enable Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            Brush.linearGradient(listOf(VoxoraElectricPurple, VoxoraSubtleBlue))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "AI Dubbing Studio",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Surface(
                            color = VoxoraElectricPurple.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, VoxoraElectricPurple)
                        ) {
                            Text(
                                text = "v2.2.0",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = VoxoraElectricPurpleBright,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "${state.config.sourceLanguage.displayName} → ${state.config.targetLanguage.displayName} (${state.config.targetLanguage.nativeName}) · Fast Live 30s Chunk Dubbing & MP4 Export",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoxoraSubtleBlue
                    )
                }
                Switch(
                    checked = state.config.isDubbingEnabled,
                    onCheckedChange = onSetEnabled,
                    modifier = Modifier.testTag("dubbing_enable_switch")
                )
            }

            // Unified One-Click AI Setup Card (or compact green All Ready badge once 4/4 models installed)
            OneClickAiSetupCard(
                state = oneClickAiSetupState,
                onStartOneClickSetup = onStartOneClickAiSetup,
                onCancelOneClickSetup = onCancelOneClickAiSetup
            )

            // Honest Pipeline Readiness Summary
            Surface(
                color = Color(0xFF171A26),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VoxoraSubtleBlue.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = VoxoraSubtleBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${state.readyComponentCount}/${state.totalComponentCount} stages ready on-device. Full offline pipeline: Whisper ASR, Sherpa-ONNX Diarization & VITS Bengali TTS, ML Kit Translation, Smart BGM Ducking, Live Sync & MP4 Export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // Segmented Studio Navigation Tabs (horizontally scrollable for crisp labels on all widths)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF14151E))
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DubbingStudioTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Surface(
                        onClick = { selectedTab = tab },
                        color = if (selected) VoxoraElectricPurple else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("dubbing_tab_${tab.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = when (tab) {
                                DubbingStudioTab.CONTROLS -> Icons.Rounded.Tune
                                DubbingStudioTab.TRANSCRIBE -> Icons.Rounded.GraphicEq
                                DubbingStudioTab.SPEAKERS -> Icons.Rounded.RecordVoiceOver
                                DubbingStudioTab.TRANSLATE -> Icons.Rounded.Translate
                                DubbingStudioTab.VOICE_TTS -> Icons.AutoMirrored.Rounded.VolumeUp
                                DubbingStudioTab.CHARACTERS -> Icons.Rounded.People
                                DubbingStudioTab.PIPELINE -> Icons.Rounded.Layers
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                DubbingStudioTab.CONTROLS -> {
                    val ttsRequiredReady = !state.config.useSherpaVitsExperimental || oneClickAiSetupState.isBengaliTtsReady
                    val requiredModelsReady = if (state.config.fastMovieModeEnabled) {
                        oneClickAiSetupState.isWhisperReady &&
                            oneClickAiSetupState.isTranslationReady &&
                            ttsRequiredReady
                    } else {
                        oneClickAiSetupState.isWhisperReady &&
                            oneClickAiSetupState.isDiarizationReady &&
                            oneClickAiSetupState.isTranslationReady &&
                            ttsRequiredReady
                    }
                    DubbingControlsTab(
                        state = state,
                        allModelsInstalled = requiredModelsReady,
                        liveDubbingState = liveDubbingState,
                        autoDubPipelineState = autoDubPipelineState,
                        dubbedExportState = dubbedExportState,
                        onStartAutoDubVideo = onStartAutoDubVideo,
                        onCancelAutoDubVideo = onCancelAutoDubVideo,
                        onExportDubbedMedia = onExportDubbedMedia,
                        onCancelDubbedExport = onCancelDubbedExport,
                        onOpenExportedMedia = onOpenExportedMedia,
                        onShareExportedMedia = onShareExportedMedia,
                        onSelectSourceLanguage = onSelectSourceLanguage,
                        onSelectTargetLanguage = onSelectTargetLanguage,
                        onSelectOutputMode = onSelectOutputMode,
                        onOriginalVolumeChange = onOriginalVolumeChange,
                        onDubbedVolumeChange = onDubbedVolumeChange,
                        onAdjustSyncOffset = onAdjustSyncOffset,
                        onSetSyncOffsetMs = onSetSyncOffsetMs,
                        onResetSyncOffset = onResetSyncOffset,
                        onPreserveBackgroundChange = onPreserveBackgroundChange,
                        onBgmDuckingStrengthChange = onBgmDuckingStrengthChange,
                        onVocalAttenuationChange = onVocalAttenuationChange,
                        onFastMovieModeChange = onFastMovieModeChange,
                        onUseSherpaVitsExperimentalChange = onUseSherpaVitsExperimentalChange
                    )
                }
                DubbingStudioTab.TRANSCRIBE -> {
                    WhisperTranscriptionSection(
                        videoTitle = videoTitle,
                        hasActiveVideo = state.config.mediaUriString.isNotBlank(),
                        modelStatus = whisperModelStatus,
                        transcriptionState = whisperTranscriptionState,
                        onDownloadBaseEn = onDownloadWhisperModel,
                        onImportLocalModel = onImportWhisperModel,
                        onCancelModelOperation = onCancelWhisperModelOp,
                        onLoadModel = onLoadWhisperModel,
                        onUnloadModel = onUnloadWhisperModel,
                        onDeleteModel = onDeleteWhisperModel,
                        onStartTranscription = onStartTranscription,
                        onStopTranscription = onStopTranscription,
                        onExportTranscript = onExportTranscription,
                        onSeekToTimestamp = onSeekToTimestamp,
                        characters = state.config.characterVoices,
                        segmentCharacterMap = diarizationUiState.segmentCharacterMap,
                        onOverrideSegmentCharacter = onOverrideSegmentCharacter
                    )
                }
                DubbingStudioTab.SPEAKERS -> {
                    SpeakerDiarizationSection(
                        videoTitle = videoTitle,
                        hasActiveVideo = state.config.mediaUriString.isNotBlank(),
                        modelState = diarizationModelState,
                        slotAvailability = diarizationSlotAvailability,
                        installedFiles = diarizationInstalledFiles,
                        diarizationState = diarizationUiState,
                        characters = state.config.characterVoices,
                        onCheckModelStatus = onCheckDiarizationModelStatus,
                        onDownloadModels = onDownloadDiarizationModels,
                        onCancelModelDownload = onCancelDiarizationDownload,
                        onImportLocalModel = onImportLocalDiarizationModel,
                        onDeleteModels = onDeleteDiarizationModels,
                        onSelectNumClusters = onSelectDiarizationClusters,
                        onThresholdChange = onDiarizationThresholdChange,
                        onStartDiarization = onStartDiarization,
                        onStopDiarization = onStopDiarization,
                        onReassignSpeaker = onReassignSpeakerToCharacter
                    )
                }
                DubbingStudioTab.TRANSLATE -> {
                    BengaliTranslationSection(
                        videoTitle = videoTitle,
                        englishSegmentCount = whisperTranscriptionState.segments.size,
                        modelState = translationModelState,
                        translationState = translationUiState,
                        onCheckModelStatus = onCheckTranslationModelStatus,
                        onDownloadModel = onDownloadTranslationModel,
                        onDeleteModel = onDeleteTranslationModel,
                        onStartTranslation = onStartTranslation,
                        onStopTranslation = onStopTranslation,
                        onClearCachedTranslation = onClearCachedTranslation,
                        onExportTranslation = onExportTranslation,
                        onSwitchToTranscribeTab = { selectedTab = DubbingStudioTab.TRANSCRIBE },
                        onSeekToTimestamp = onSeekToTimestamp,
                        characters = state.config.characterVoices,
                        segmentCharacterMap = diarizationUiState.segmentCharacterMap,
                        onOverrideSegmentCharacter = onOverrideSegmentCharacter
                    )
                }
                DubbingStudioTab.VOICE_TTS -> {
                    BengaliVoiceTtsSection(
                        videoTitle = videoTitle,
                        translatedSegmentCount = translationUiState.segments.size,
                        useSherpaVitsExperimental = state.config.useSherpaVitsExperimental,
                        onUseSherpaVitsExperimentalChange = onUseSherpaVitsExperimentalChange,
                        modelState = bengaliTtsModelState,
                        slotAvailability = bengaliTtsSlotAvailability,
                        installedFiles = bengaliTtsInstalledFiles,
                        voiceState = bengaliVoiceUiState,
                        characters = state.config.characterVoices,
                        onCheckModelStatus = onCheckBengaliTtsModelStatus,
                        onDownloadModels = onDownloadBengaliTtsModels,
                        onCancelModelDownload = onCancelBengaliTtsDownload,
                        onImportLocalFile = onImportLocalBengaliTtsFile,
                        onDeleteModels = onDeleteBengaliTtsModels,
                        onStartVoiceGeneration = onStartBengaliVoiceGeneration,
                        onStopVoiceGeneration = onStopBengaliVoiceGeneration,
                        onTogglePlaySegment = onTogglePlayDubbedSegment,
                        onClearSynthesizedAudio = onClearSynthesizedBengaliAudio,
                        onSwitchToTranslateTab = { selectedTab = DubbingStudioTab.TRANSLATE },
                        onSeekToTimestamp = onSeekToTimestamp,
                        onOverrideSegmentCharacter = onOverrideSegmentCharacter
                    )
                }
                DubbingStudioTab.CHARACTERS -> {
                    CharacterManagementSection(
                        characters = state.config.characterVoices,
                        movieAssignments = state.movieSpeakerAssignments,
                        ttsPreviewState = state.ttsPreviewState,
                        hasActiveMovie = state.config.mediaUriString.isNotBlank(),
                        onAddCharacter = onAddCharacter,
                        onUpdateCharacter = onUpdateCharacter,
                        onDeleteCharacter = onRemoveCharacter,
                        onResetDefaultCharacters = onResetDefaultCharacters,
                        onPreviewCharacterVoice = onPreviewCharacterVoice,
                        onStopVoicePreview = onStopVoicePreview
                    )
                }
                DubbingStudioTab.PIPELINE -> {
                    DubbingPipelineTab(
                        state = state,
                        onInspectAudioTrack = onInspectAudioTrack,
                        onRunStage1Extraction = onRunStage1Extraction
                    )
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dubbing_studio_done_button")
            ) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DubbingControlsTab(
    state: PlayerDubbingUiState,
    allModelsInstalled: Boolean,
    liveDubbingState: LiveDubbingPlaybackState,
    autoDubPipelineState: AutoDubPipelineState,
    dubbedExportState: DubbedExportState,
    onStartAutoDubVideo: () -> Unit,
    onCancelAutoDubVideo: () -> Unit,
    onExportDubbedMedia: (DubbingExportFormat) -> Unit,
    onCancelDubbedExport: () -> Unit,
    onOpenExportedMedia: (uriString: String, mimeType: String) -> Unit,
    onShareExportedMedia: (uriString: String, mimeType: String, title: String) -> Unit,
    onSelectSourceLanguage: (DubbingLanguage) -> Unit,
    onSelectTargetLanguage: (DubbingLanguage) -> Unit,
    onSelectOutputMode: (DubbingAudioOutputMode) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onDubbedVolumeChange: (Float) -> Unit,
    onAdjustSyncOffset: (Long) -> Unit,
    onSetSyncOffsetMs: (Long) -> Unit,
    onResetSyncOffset: () -> Unit,
    onPreserveBackgroundChange: (Boolean) -> Unit,
    onBgmDuckingStrengthChange: (Float) -> Unit,
    onVocalAttenuationChange: (Boolean) -> Unit,
    onFastMovieModeChange: (Boolean) -> Unit,
    onUseSherpaVitsExperimentalChange: (Boolean) -> Unit
) {
    val config = state.config
    var showAllLanguages by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 0. Prominent Live Rolling Chunk Auto-Dub Video Hero Card
        AutoDubHeroCard(
            hasActiveVideo = config.mediaUriString.isNotBlank(),
            allModelsInstalled = allModelsInstalled,
            autoDubState = autoDubPipelineState,
            liveDubbingState = liveDubbingState,
            fastMovieModeEnabled = config.fastMovieModeEnabled,
            onStartAutoDub = onStartAutoDubVideo,
            onCancelAutoDub = onCancelAutoDubVideo
        )

        // 0.5. Fast Movie Mode Toggle Card (Default ON — 30s rolling chunks, skips heavy Diarization)
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (config.fastMovieModeEnabled) {
                    Color(0xFF4ADE80).copy(alpha = 0.45f)
                } else {
                    VoxoraSubtleBlue.copy(alpha = 0.45f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = if (config.fastMovieModeEnabled) Color(0xFF4ADE80) else VoxoraSubtleBlue,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Fast Movie Mode",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Surface(
                            color = if (config.fastMovieModeEnabled) {
                                Color(0xFF4ADE80).copy(alpha = 0.16f)
                            } else {
                                VoxoraSubtleBlue.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (config.fastMovieModeEnabled) "5x FASTER · <20MB RAM" else "MULTI-SPEAKER",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (config.fastMovieModeEnabled) Color(0xFF4ADE80) else VoxoraSubtleBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = if (config.fastMovieModeEnabled) {
                            "30s live rolling chunks · Skips heavy Speaker Diarization to start Bengali playback in seconds and prevent OOM. Turn OFF for Multi-Speaker Diarization."
                        } else {
                            "Multi-Speaker Diarization active · Detects and assigns speakers per 30s chunk using Sherpa-ONNX (uses more CPU/RAM)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
                Switch(
                    checked = config.fastMovieModeEnabled,
                    onCheckedChange = onFastMovieModeChange,
                    modifier = Modifier.testTag("fast_movie_mode_switch")
                )
            }
        }

        // 0.75. Bengali TTS Engine Selector (Default: Android Built-in Offline TTS; Optional: Sherpa-ONNX VITS Experimental)
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                1.dp,
                if (config.useSherpaVitsExperimental) {
                    Color(0xFFFBBF24).copy(alpha = 0.55f)
                } else {
                    Color(0xFF4ADE80).copy(alpha = 0.40f)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = if (config.useSherpaVitsExperimental) Color(0xFFFBBF24) else Color(0xFF4ADE80),
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Use Sherpa-ONNX VITS (Experimental)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                    Surface(
                        color = if (config.useSherpaVitsExperimental) {
                            Color(0xFFFBBF24).copy(alpha = 0.16f)
                        } else {
                            Color(0xFF4ADE80).copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (config.useSherpaVitsExperimental) {
                                "EXPERIMENTAL · NATIVE ONNX"
                            } else {
                                "DEFAULT · EDGE NEURAL TTS + OFFLINE FALLBACK"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (config.useSherpaVitsExperimental) Color(0xFFFBBF24) else Color(0xFF4ADE80),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = if (config.useSherpaVitsExperimental) {
                            "Uses Sherpa-ONNX VITS neural model (~109 MB). May use high native RAM on some devices."
                        } else {
                            "Free human-like Bengali Neural voices (Nabanita & Pradeep) with auto Male/Female detection, expressive prosody, and automatic offline Android TTS fallback."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
                Switch(
                    checked = config.useSherpaVitsExperimental,
                    onCheckedChange = onUseSherpaVitsExperimentalChange,
                    modifier = Modifier.testTag("use_sherpa_vits_experimental_switch")
                )
            }
        }
        // 1. Language Pair Card (English -> Bengali Phase 1 + Future Expansion)
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
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
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = VoxoraSubtleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Language Configuration", style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(
                        onClick = { showAllLanguages = !showAllLanguages },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (showAllLanguages) "Hide Roadmap" else "All Languages",
                            color = VoxoraSubtleBlue,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Active Pair Pill Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LanguagePairChip(
                        role = "Original",
                        language = config.sourceLanguage,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = VoxoraElectricPurpleBright,
                        modifier = Modifier.size(20.dp)
                    )
                    LanguagePairChip(
                        role = "Target",
                        language = config.targetLanguage,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (showAllLanguages) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Text(
                        text = "Source Language (Phase 1: English)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DubbingLanguageRegistry.sourceLanguages.forEach { lang ->
                            FilterChip(
                                selected = config.sourceLanguage.code == lang.code,
                                enabled = lang.isPhase1Active,
                                onClick = { onSelectSourceLanguage(lang) },
                                label = {
                                    Text(
                                        if (lang.isPhase1Active) lang.displayName
                                        else "${lang.displayName} (Soon)"
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VoxoraElectricPurple,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Text(
                        text = "Target Dubbing Language (Phase 1: Bengali / বাংলা)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DubbingLanguageRegistry.targetLanguages.forEach { lang ->
                            FilterChip(
                                selected = config.targetLanguage.code == lang.code,
                                enabled = lang.isPhase1Active,
                                onClick = { onSelectTargetLanguage(lang) },
                                label = {
                                    Text(
                                        if (lang.isPhase1Active) "${lang.displayName} (${lang.nativeName})"
                                        else "${lang.displayName} (Planned)"
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VoxoraElectricPurple,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Audio Track Output Mode (Original Audio vs Bengali Dubbed Audio)
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = null,
                        tint = VoxoraElectricPurpleBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Audio Output Mode", style = MaterialTheme.typography.titleSmall)
                }

                DubbingAudioOutputMode.entries.forEach { mode ->
                    val selected = config.audioOutputMode == mode
                    Surface(
                        onClick = { onSelectOutputMode(mode) },
                        color = if (selected) VoxoraElectricPurple.copy(alpha = 0.2f) else Color(0xFF13141D),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) VoxoraElectricPurple else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dubbing_output_mode_${mode.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = VoxoraSubtleBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (config.audioOutputMode != DubbingAudioOutputMode.ORIGINAL_AUDIO && !state.hasSynthesizedBengaliTrack) {
                    Text(
                        text = "No offline Bengali voice track is synthesized for this video yet. Tap \"Auto-Dub Entire Video\" above or synthesize voices in the Voice / TTS tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24)
                    )
                } else if (liveDubbingState.isActive) {
                    Text(
                        text = if (liveDubbingState.isOriginalDucked) {
                            "Live synchronized Bengali dubbing active · Smart BGM Ducking engaged"
                        } else {
                            "Live synchronized Bengali dubbing active · Original BGM & SFX at full richness"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4ADE80)
                    )
                }
            }
        }

        // 3. Volume Mixer & Background Preservation
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = VoxoraSubtleBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Live Audio Mixer", style = MaterialTheme.typography.titleSmall)
                }

                // Original Audio Volume (Live ExoPlayer volume control)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Original Audio Volume",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "${(config.originalAudioVolume * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = VoxoraSubtleBlue
                        )
                    }
                    Slider(
                        value = config.originalAudioVolume,
                        onValueChange = onOriginalVolumeChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraElectricPurpleBright,
                            activeTrackColor = VoxoraElectricPurple
                        ),
                        modifier = Modifier.testTag("dubbing_original_volume_slider")
                    )
                }

                // Bengali Dubbed Audio Volume
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bengali Dubbed Audio Volume",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "${(config.dubbedAudioVolume * 100).toInt()}%",
                            style = TabularTimeStyle,
                            color = VoxoraElectricPurpleBright
                        )
                    }
                    Slider(
                        value = config.dubbedAudioVolume,
                        onValueChange = onDubbedVolumeChange,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = VoxoraSubtleBlue,
                            activeTrackColor = VoxoraSubtleBlue
                        ),
                        modifier = Modifier.testTag("dubbing_dubbed_volume_slider")
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = VoxoraSubtleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Smart BGM & SFX Ducking",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Smoothly ducks original audio during Bengali dialogue (150ms ramp) and restores BGM/SFX to 100% between lines",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                    Switch(
                        checked = config.preserveOriginalBackgroundAudio,
                        onCheckedChange = onPreserveBackgroundChange,
                        modifier = Modifier.testTag("dubbing_preserve_bg_switch")
                    )
                }

                if (config.preserveOriginalBackgroundAudio) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "BGM Ducking Strength",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            val duckPct = (config.bgmDuckingStrength * 100f).toInt().coerceIn(0, 95)
                            Text(
                                text = "${duckPct}% (BGM at ${100 - duckPct}%)",
                                style = TabularTimeStyle,
                                color = VoxoraSubtleBlue
                            )
                        }
                        Slider(
                            value = config.bgmDuckingStrength,
                            onValueChange = onBgmDuckingStrengthChange,
                            valueRange = 0f..0.95f,
                            colors = SliderDefaults.colors(
                                thumbColor = VoxoraSubtleBlue,
                                activeTrackColor = VoxoraSubtleBlue
                            ),
                            modifier = Modifier.testTag("dubbing_bgm_ducking_slider")
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Stereo Vocal Reduction (Mid-Side)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Attenuates center-panned English vocals while keeping stereo BGM/SFX intact during offline export",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = config.vocalAttenuationEnabled,
                        onCheckedChange = onVocalAttenuationChange,
                        modifier = Modifier.testTag("dubbing_vocal_attenuation_switch")
                    )
                }
            }
        }

        // 4. Dubbing Synchronization Offset
        Surface(
            color = Color(0xFF181924),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
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
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = VoxoraElectricPurpleBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Dubbing Synchronization", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        text = FormatUtils.formatSyncOffset(config.syncOffsetMs),
                        style = TabularTimeStyle,
                        color = VoxoraElectricPurpleBright
                    )
                }
                Slider(
                    value = config.syncOffsetMs.toFloat(),
                    onValueChange = { onSetSyncOffsetMs((it / 50f).toLong() * 50L) },
                    valueRange = -5000f..5000f,
                    colors = SliderDefaults.colors(
                        thumbColor = VoxoraElectricPurpleBright,
                        activeTrackColor = VoxoraElectricPurple
                    ),
                    modifier = Modifier.testTag("dubbing_sync_offset_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAdjustSyncOffset(-500L) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("-0.5s") }
                    OutlinedButton(
                        onClick = { onAdjustSyncOffset(-100L) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("-0.1s") }
                    OutlinedButton(
                        onClick = onResetSyncOffset,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("0.0s") }
                    OutlinedButton(
                        onClick = { onAdjustSyncOffset(100L) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("+0.1s") }
                    OutlinedButton(
                        onClick = { onAdjustSyncOffset(500L) },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("+0.5s") }
                }
            }
        }

        // 5. Offline Dubbed Video (MP4) & Audio (M4A / WAV) Export Section
        DubbedExportSection(
            hasSynthesizedDubTrack = state.hasSynthesizedBengaliTrack,
            exportState = dubbedExportState,
            onExportFormat = onExportDubbedMedia,
            onCancelExport = onCancelDubbedExport,
            onOpenExportedMedia = onOpenExportedMedia,
            onShareExportedMedia = onShareExportedMedia
        )
    }
}

@Composable
private fun LanguagePairChip(
    role: String,
    language: DubbingLanguage,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF12131C),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, VoxoraElectricPurple.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = role.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = VoxoraSubtleBlue
            )
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DubbingPipelineTab(
    state: PlayerDubbingUiState,
    onInspectAudioTrack: () -> Unit,
    onRunStage1Extraction: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Real On-Device Stage 1 Audio Track Inspector (MediaExtractor)
        Surface(
            color = Color(0xFF151E24),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.45f))
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
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Active Video Audio Track",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Surface(
                        color = Color(0xFF4ADE80).copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "ON-DEVICE READY",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF4ADE80),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                val info = state.extractedAudioInfo
                if (state.isInspectingAudio) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VoxoraSubtleBlue)
                        Text(
                            text = "Inspecting local video audio stream via Android MediaExtractor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                } else if (info != null) {
                    Text(
                        text = buildString {
                            append("Codec: ${info.codecDisplayName} (${info.mimeType})")
                            append("\nSample Rate: ${info.sampleRateHz} Hz · Channels: ${info.channelCount}")
                            if (info.bitrateBps > 0) append(" · ${info.bitrateBps / 1000} kbps")
                            if (info.extractedFilePath != null) {
                                append("\nExtracted stream: ${FormatUtils.formatFileSize(info.extractedBytes)} cached locally")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                } else {
                    Text(
                        text = state.extractionMessage ?: "Tap Analyze to inspect the current video's audio stream on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }

                if (!state.extractionMessage.isNullOrBlank() && info != null) {
                    Text(
                        text = state.extractionMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = VoxoraSubtleBlue
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onInspectAudioTrack,
                        enabled = !state.isInspectingAudio && !state.isExtractingAudio,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Inspect Track")
                    }
                    Button(
                        onClick = onRunStage1Extraction,
                        enabled = !state.isExtractingAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = VoxoraElectricPurple),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dubbing_extract_audio_button")
                    ) {
                        if (state.isExtractingAudio) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Extracting...")
                        } else {
                            Text("Extract Audio")
                        }
                    }
                }
            }
        }

        // Complete 7-Stage Modular Pipeline Readiness List
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Memory,
                contentDescription = null,
                tint = VoxoraSubtleBlue,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Zero-Cost Offline Dubbing Architecture (7 Stages)",
                style = MaterialTheme.typography.titleSmall
            )
        }

        state.pipelineStages.forEach { stageStatus ->
            val isReady = stageStatus.readiness == ComponentReadiness.READY
            val badgeColor = when (stageStatus.readiness) {
                ComponentReadiness.READY -> Color(0xFF4ADE80)
                ComponentReadiness.RUNNING -> VoxoraSubtleBlue
                ComponentReadiness.REQUIRES_AI_ENGINE -> Color(0xFFFBBF24)
                ComponentReadiness.ERROR -> MaterialTheme.colorScheme.error
            }
            Surface(
                color = Color(0xFF181924),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isReady) Color(0xFF4ADE80).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${stageStatus.stage.order}",
                                    style = TabularTimeStyle,
                                    color = badgeColor
                                )
                            }
                            Text(
                                text = stageStatus.stage.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stageStatus.readiness.badgeLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = stageStatus.statusDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "Engine target: ${stageStatus.stage.openSourceTarget}",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoxoraSubtleBlue
                    )
                }
            }
        }
    }
}

