package com.voxora.player.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.ai.diarization.DiarizationModelFiles
import com.voxora.player.ai.diarization.DiarizationModelManager
import com.voxora.player.ai.diarization.DiarizationModelSlot
import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.diarization.DiarizationSlotAvailability
import com.voxora.player.ai.diarization.OfflineDiarizationEngine
import com.voxora.player.ai.diarization.SpeakerCharacterMapper
import com.voxora.player.ai.diarization.SpeakerDiarizationUiState
import com.voxora.player.ai.dubbing.AutoDubPipelineOrchestrator
import com.voxora.player.ai.dubbing.AutoDubPipelineState
import com.voxora.player.ai.dubbing.AutoDubStage
import com.voxora.player.ai.dubbing.DubbedExportState
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.dubbing.DubbedVideoExporter
import com.voxora.player.ai.dubbing.DubbingExportFormat
import com.voxora.player.ai.dubbing.DubbingPlaybackController
import com.voxora.player.ai.dubbing.LiveDubbingPlaybackState
import com.voxora.player.ai.translation.BengaliTranslationUiState
import com.voxora.player.ai.translation.OfflineTranslationEngine
import com.voxora.player.ai.translation.OfflineTranslationManager
import com.voxora.player.ai.translation.TranslationExportFormat
import com.voxora.player.ai.translation.TranslationExporter
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.ai.tts.BengaliTtsModelFiles
import com.voxora.player.ai.tts.BengaliTtsModelManager
import com.voxora.player.ai.tts.BengaliTtsModelSlot
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.BengaliTtsSlotAvailability
import com.voxora.player.ai.tts.BengaliVoiceGenerationUiState
import com.voxora.player.ai.tts.DubAudioPreviewPlayer
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.ai.tts.OfflineBengaliTtsEngine
import com.voxora.player.ai.tts.OneClickAiSetupState
import com.voxora.player.data.local.power.BatteryMonitor
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.data.repository.TranslationCacheRepository
import com.voxora.player.dubbing.AiDubbingPipeline
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.DubbingAudioOutputMode
import com.voxora.player.dubbing.DubbingCapabilityStatus
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.dubbing.DubbingPreferencesRepository
import com.voxora.player.dubbing.DubbingSessionConfig
import com.voxora.player.dubbing.DubbingVoiceCatalog
import com.voxora.player.dubbing.ExtractedAudioTrackInfo
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.PlayerDubbingUiState
import com.voxora.player.dubbing.TtsVoicePreviewEngine
import com.voxora.player.dubbing.VideoAudioExtractor
import com.voxora.player.dubbing.whisper.TranscriptExportFormat
import com.voxora.player.dubbing.whisper.TranscriptExporter
import com.voxora.player.dubbing.whisper.WhisperCppSpeechRecognitionEngine
import com.voxora.player.dubbing.whisper.WhisperModelCatalog
import com.voxora.player.dubbing.whisper.WhisperModelStatus
import com.voxora.player.dubbing.whisper.WhisperTranscriptionState
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.AudioTrackInfo
import com.voxora.player.model.BatterySaverEvaluation
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.BatterySavingPolicy
import com.voxora.player.model.BatteryStatus
import com.voxora.player.model.SubtitleTrackInfo
import com.voxora.player.model.UserPreferences
import com.voxora.player.model.VideoItem
import com.voxora.player.player.PlayerPlaybackState
import com.voxora.player.player.VoxoraPlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerScreenUiState(
    val currentVideo: VideoItem? = null,
    val videoTitle: String = "",
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val isLocked: Boolean = false,
    val isFullScreen: Boolean = true,
    val resumedFromPositionMs: Long? = null,
    val activeSubtitleText: String? = null,
    val fileMissingError: String? = null,
    val subtitleLoadFeedback: String? = null
)

class PlayerViewModel(
    private val app: VoxoraApplication,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val batteryMonitor: BatteryMonitor,
    private val aiDubbingPipeline: AiDubbingPipeline,
    private val videoAudioExtractor: VideoAudioExtractor,
    private val dubbingPreferencesRepository: DubbingPreferencesRepository,
    private val whisperSpeechEngine: WhisperCppSpeechRecognitionEngine,
    private val offlineTranslationManager: OfflineTranslationManager,
    private val offlineTranslationEngine: OfflineTranslationEngine,
    private val translationCacheRepository: TranslationCacheRepository,
    private val diarizationModelManager: DiarizationModelManager,
    private val offlineDiarizationEngine: OfflineDiarizationEngine,
    private val bengaliTtsModelManager: BengaliTtsModelManager,
    private val offlineBengaliTtsEngine: OfflineBengaliTtsEngine,
    private val dubbedTrackAssembler: DubbedTrackAssembler,
    private val autoDubPipelineOrchestrator: AutoDubPipelineOrchestrator,
    private val dubbedVideoExporter: DubbedVideoExporter
) : ViewModel() {

    val playerEngine = VoxoraPlayerEngine(app)
    val playbackState: StateFlow<PlayerPlaybackState> = playerEngine.state

    private val dubbingPlaybackController = DubbingPlaybackController(
        context = app,
        mainExoPlayer = playerEngine.exoPlayer,
        trackAssembler = dubbedTrackAssembler
    )
    val liveDubbingState: StateFlow<LiveDubbingPlaybackState> = dubbingPlaybackController.state

    val preferences: StateFlow<UserPreferences> = settingsRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = UserPreferences()
        )

    val batteryStatus: StateFlow<BatteryStatus> = batteryMonitor.batteryStatusFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = batteryMonitor.currentBatteryStatus()
        )

    val batterySavingState: StateFlow<BatterySaverEvaluation> = combine(
        preferences,
        batteryStatus
    ) { prefs, status ->
        BatterySavingPolicy.evaluate(prefs, status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BatterySavingPolicy.evaluate(preferences.value, batteryStatus.value)
    )

    private val _uiState = MutableStateFlow(PlayerScreenUiState())
    val uiState: StateFlow<PlayerScreenUiState> = _uiState.asStateFlow()

    private val ttsPreviewEngine = TtsVoicePreviewEngine(app)

    private val _activeMediaUri = MutableStateFlow("")
    private val _extractedAudioInfo = MutableStateFlow<ExtractedAudioTrackInfo?>(null)
    private val _isInspectingAudio = MutableStateFlow(false)
    private val _isExtractingAudio = MutableStateFlow(false)
    private val _extractionMessage = MutableStateFlow<String?>(null)

    private val dubbingConfigFlow: StateFlow<DubbingSessionConfig> =
        dubbingPreferencesRepository.configFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DubbingSessionConfig()
        )

    private val activeMovieAssignmentsFlow: StateFlow<List<MovieSpeakerAssignment>> = combine(
        _activeMediaUri,
        dubbingPreferencesRepository.allMovieAssignmentsFlow
    ) { uri, allMap ->
        if (uri.isBlank()) emptyList() else allMap[uri].orEmpty()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    val whisperModelStatus: StateFlow<WhisperModelStatus> = whisperSpeechEngine.modelStatus
    val whisperTranscriptionState: StateFlow<WhisperTranscriptionState> = whisperSpeechEngine.transcriptionState
    val translationModelState: StateFlow<TranslationModelState> = offlineTranslationManager.modelState
    val diarizationModelState: StateFlow<DiarizationModelState> = diarizationModelManager.modelState
    val diarizationSlotAvailability: StateFlow<DiarizationSlotAvailability> = diarizationModelManager.slotAvailability
    val diarizationInstalledFiles: StateFlow<DiarizationModelFiles?> = diarizationModelManager.installedFiles
    val bengaliTtsModelState: StateFlow<BengaliTtsModelState> = bengaliTtsModelManager.modelState
    val bengaliTtsSlotAvailability: StateFlow<BengaliTtsSlotAvailability> = bengaliTtsModelManager.slotAvailability
    val bengaliTtsInstalledFiles: StateFlow<BengaliTtsModelFiles?> = bengaliTtsModelManager.installedFiles

    private val dubAudioPreviewPlayer = DubAudioPreviewPlayer()
    private val _activeNeuralPreviewCharacterId = MutableStateFlow<String?>(null)

    private val _translationUiState = MutableStateFlow(BengaliTranslationUiState())
    val translationUiState: StateFlow<BengaliTranslationUiState> = _translationUiState.asStateFlow()

    private val _diarizationUiState = MutableStateFlow(SpeakerDiarizationUiState())
    val diarizationUiState: StateFlow<SpeakerDiarizationUiState> = _diarizationUiState.asStateFlow()

    private val _bengaliVoiceUiState = MutableStateFlow(BengaliVoiceGenerationUiState())
    val bengaliVoiceUiState: StateFlow<BengaliVoiceGenerationUiState> = _bengaliVoiceUiState.asStateFlow()

    private val _autoDubPipelineState = MutableStateFlow(AutoDubPipelineState())
    val autoDubPipelineState: StateFlow<AutoDubPipelineState> = _autoDubPipelineState.asStateFlow()

    private val _dubbedExportState = MutableStateFlow(DubbedExportState())
    val dubbedExportState: StateFlow<DubbedExportState> = _dubbedExportState.asStateFlow()

    private val _oneClickProgressState = MutableStateFlow(OneClickProgressSnapshot())

    val oneClickAiSetupState: StateFlow<OneClickAiSetupState> = combine(
        whisperModelStatus,
        diarizationModelState,
        translationModelState,
        bengaliTtsModelState,
        _oneClickProgressState
    ) { whisperStatus, diarizationState, translationState, ttsState, progress ->
        val isWhisperInstalledOnDisk = whisperStatus is WhisperModelStatus.LoadedReady ||
            whisperStatus is WhisperModelStatus.InstalledUnloaded ||
            whisperStatus is WhisperModelStatus.Loading ||
            (whisperStatus is WhisperModelStatus.Error && whisperStatus.installedModel != null) ||
            whisperSpeechEngine.isModelInstalledOnDisk()

        OneClickAiSetupState(
            isWhisperReady = isWhisperInstalledOnDisk,
            isDiarizationReady = diarizationState is DiarizationModelState.Ready,
            isTranslationReady = translationState is TranslationModelState.Ready,
            isBengaliTtsReady = ttsState is BengaliTtsModelState.Ready,
            isRunning = progress.isRunning,
            currentStepIndex = progress.currentStepIndex,
            totalSteps = progress.totalSteps,
            currentStepLabel = progress.currentStepLabel,
            currentStepProgressPercent = progress.currentStepProgressPercent,
            errorMessage = progress.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = OneClickAiSetupState()
    )

    private var activeTranslationJob: Job? = null
    private var activeDiarizationModelJob: Job? = null
    private var activeDiarizationJob: Job? = null
    private var activeBengaliTtsModelJob: Job? = null
    private var activeBengaliVoiceGenJob: Job? = null
    private var activeCharacterNeuralPreviewJob: Job? = null
    private var activeOneClickSetupJob: Job? = null
    private var activeAutoDubJob: Job? = null
    private var activeChunkWorkJob: Job? = null
    private var activeExportJob: Job? = null
    private val manualSegmentCharacterOverrides = mutableMapOf<Int, String>()
    private val completedLiveChunkIndices = mutableSetOf<Int>()
    @Volatile
    private var activeProcessingChunkIndex: Int = -1
    @Volatile
    private var pendingSeekTargetChunk: Int? = null

    private val diarizationAndTtsSnapshotFlow = combine(
        diarizationModelState,
        _diarizationUiState,
        bengaliTtsModelState,
        _bengaliVoiceUiState,
        liveDubbingState
    ) { diarizationModel, diarizationUi, ttsModel, ttsUi, liveDub ->
        DiarizationAndTtsPipelineSnapshot(
            diarizationModelState = diarizationModel,
            isDiarizing = diarizationUi.isRunning,
            bengaliTtsModelState = ttsModel,
            isSynthesizingBengaliVoice = ttsUi.isSynthesizing,
            hasSynthesizedBengaliTrack = ttsUi.segments.isNotEmpty() || liveDub.isTrackReady,
            isLiveDubbingActive = liveDub.isActive
        )
    }

    private val whisperSnapshotFlow = combine(
        whisperModelStatus,
        whisperTranscriptionState,
        translationModelState,
        _translationUiState,
        diarizationAndTtsSnapshotFlow
    ) { modelStatus, transState, translationState, translationUi, diarizationTtsSnap ->
        val isWhisperInstalledOnDisk = modelStatus is WhisperModelStatus.LoadedReady ||
            modelStatus is WhisperModelStatus.InstalledUnloaded ||
            modelStatus is WhisperModelStatus.Loading ||
            (modelStatus is WhisperModelStatus.Error && modelStatus.installedModel != null) ||
            whisperSpeechEngine.isModelInstalledOnDisk()

        WhisperPipelineSnapshot(
            isModelLoaded = isWhisperInstalledOnDisk,
            isTranscribing = transState.isRunning,
            detail = when (modelStatus) {
                is WhisperModelStatus.LoadedReady -> "whisper.cpp loaded ${modelStatus.model.fileName} · Ready offline"
                is WhisperModelStatus.InstalledUnloaded -> "Model ${modelStatus.model.fileName} installed on disk · Ready offline (auto-loads on demand)"
                is WhisperModelStatus.Downloading -> "Downloading ${WhisperModelCatalog.DEFAULT.fileName} (${(modelStatus.progressFraction * 100).toInt()}%)..."
                is WhisperModelStatus.Loading -> "Loading ${modelStatus.model.fileName} into native whisper.cpp..."
                is WhisperModelStatus.Error -> modelStatus.message
                else -> "Native whisper.cpp compiled · Download ${WhisperModelCatalog.DEFAULT.fileName} (~31 MB) in One-Click Setup or Transcribe tab"
            },
            diarizationModelState = diarizationTtsSnap.diarizationModelState,
            isDiarizing = diarizationTtsSnap.isDiarizing,
            translationModelState = translationState,
            isTranslating = translationUi.isTranslating,
            bengaliTtsModelState = diarizationTtsSnap.bengaliTtsModelState,
            isSynthesizingBengaliVoice = diarizationTtsSnap.isSynthesizingBengaliVoice,
            hasSynthesizedBengaliTrack = diarizationTtsSnap.hasSynthesizedBengaliTrack,
            isLiveDubbingActive = diarizationTtsSnap.isLiveDubbingActive
        )
    }

    private val extractionSnapshotFlow = combine(
        _extractedAudioInfo,
        _isInspectingAudio,
        _isExtractingAudio,
        _extractionMessage,
        whisperSnapshotFlow
    ) { audioInfo, inspecting, extracting, msg, whisperSnap ->
        ExtractionUiSnapshot(audioInfo, inspecting, extracting, msg, whisperSnap)
    }

    private val effectiveTtsPreviewStateFlow = combine(
        ttsPreviewEngine.state,
        bengaliTtsModelState,
        _activeNeuralPreviewCharacterId
    ) { systemTtsState, neuralTtsState, activeNeuralCharId ->
        if (neuralTtsState is BengaliTtsModelState.Ready) {
            systemTtsState.copy(
                isChecking = false,
                isRealTtsEngineAvailable = true,
                isBengaliVoiceSupported = true,
                matchedLocaleTag = "bn-VITS-Offline",
                statusMessage = "Offline Sherpa-ONNX VITS Bengali voice Ready. Preview plays real offline Bengali speech with each character's pitch & speed.",
                activePreviewCharacterId = activeNeuralCharId
            )
        } else {
            systemTtsState
        }
    }

    val dubbingState: StateFlow<PlayerDubbingUiState> = combine(
        dubbingConfigFlow,
        _activeMediaUri,
        extractionSnapshotFlow,
        activeMovieAssignmentsFlow,
        effectiveTtsPreviewStateFlow
    ) { config, uri, extraction, movieAssignments, ttsState ->
        val effectiveConfig = config.copy(mediaUriString = uri)
        PlayerDubbingUiState(
            config = effectiveConfig,
            extractedAudioInfo = extraction.audioInfo,
            isInspectingAudio = extraction.isInspecting,
            isExtractingAudio = extraction.isExtracting,
            extractionMessage = extraction.message,
            hasSynthesizedBengaliTrack = extraction.whisper.hasSynthesizedBengaliTrack,
            pipelineStages = aiDubbingPipeline.buildStageStatuses(
                extractedAudioInfo = extraction.audioInfo,
                isExtractingAudio = extraction.isExtracting,
                isWhisperModelLoaded = extraction.whisper.isModelLoaded,
                isTranscribing = extraction.whisper.isTranscribing,
                whisperModelDetail = extraction.whisper.detail,
                diarizationModelState = extraction.whisper.diarizationModelState,
                isDiarizing = extraction.whisper.isDiarizing,
                translationModelState = extraction.whisper.translationModelState,
                isTranslating = extraction.whisper.isTranslating,
                bengaliTtsModelState = extraction.whisper.bengaliTtsModelState,
                isSynthesizingBengaliVoice = extraction.whisper.isSynthesizingBengaliVoice,
                isLiveDubbingActive = extraction.whisper.isLiveDubbingActive,
                preserveBackgroundAudio = effectiveConfig.preserveOriginalBackgroundAudio,
                bgmDuckingStrength = effectiveConfig.bgmDuckingStrength,
                vocalAttenuationEnabled = effectiveConfig.vocalAttenuationEnabled
            ),
            movieSpeakerAssignments = movieAssignments,
            ttsPreviewState = ttsState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PlayerDubbingUiState(
            pipelineStages = aiDubbingPipeline.buildStageStatuses(
                extractedAudioInfo = null,
                isExtractingAudio = false,
                diarizationModelState = diarizationModelManager.modelState.value,
                translationModelState = offlineTranslationManager.modelState.value,
                bengaliTtsModelState = bengaliTtsModelManager.modelState.value
            ),
            ttsPreviewState = ttsPreviewEngine.state.value
        )
    )

    val dubbingStatus: DubbingCapabilityStatus = aiDubbingPipeline.getCapabilityStatus()
    val plannedDubbingLanguages: List<DubbingLanguage> = aiDubbingPipeline.getSupportedTargetLanguages()

    private var activeUriString: String? = null
    private var lastSavedPositionMs: Long = -1L

    init {
        viewModelScope.launch {
            offlineTranslationManager.checkModelStatus()
            diarizationModelManager.checkModelStatus()
            bengaliTtsModelManager.checkModelStatus()
        }

        viewModelScope.launch {
            dubbingConfigFlow.collect { config ->
                dubbingPlaybackController.updateConfig(config)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                playerEngine.pollPosition()
                val cueText = playerEngine.getCurrentSubtitleText()
                if (cueText != _uiState.value.activeSubtitleText) {
                    _uiState.value = _uiState.value.copy(activeSubtitleText = cueText)
                }
                val delayMs = if (batterySavingState.value.isActive) 350L else 150L
                delay(delayMs)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                persistProgressIfChanged()
            }
        }
    }

    fun initializeMedia(
        uriString: String,
        fallbackTitle: String,
        explicitStartMs: Long = -1L
    ) {
        if (activeUriString == uriString && _uiState.value.currentVideo != null) {
            return
        }
        activeUriString = uriString
        _activeMediaUri.value = uriString
        lastSavedPositionMs = -1L
        _extractedAudioInfo.value = null
        _extractionMessage.value = null
        stopSpeakerDiarization()
        stopBengaliVoiceGeneration()
        stopDubbedAudioPlayback()
        cancelAutoDubVideo()
        cancelDubbedExport()
        dubbingPlaybackController.clearTrack()
        manualSegmentCharacterOverrides.clear()
        _autoDubPipelineState.value = AutoDubPipelineState()
        _dubbedExportState.value = DubbedExportState()
        _diarizationUiState.value = SpeakerDiarizationUiState(
            activeVideoUri = uriString,
            numClustersConfig = _diarizationUiState.value.numClustersConfig,
            thresholdConfig = _diarizationUiState.value.thresholdConfig
        )
        _bengaliVoiceUiState.value = BengaliVoiceGenerationUiState(
            activeVideoUri = uriString
        )
        loadCachedTranslationForVideo(uriString, fallbackTitle)

        viewModelScope.launch {
            val prefs = settingsRepository.preferencesFlow.first()
            val accessible = mediaRepository.isVideoAccessible(uriString)
            if (!accessible) {
                _uiState.value = PlayerScreenUiState(
                    videoTitle = fallbackTitle,
                    aspectRatioMode = prefs.defaultAspectRatio,
                    fileMissingError = "This video file could not be opened. It may have been moved, deleted, or storage permission was revoked."
                )
                return@launch
            }

            val resolvedVideo = mediaRepository.findVideoByUri(uriString)
                ?: mediaRepository.addSafVideo(Uri.parse(uriString))

            val history = historyRepository.getHistoryForUri(uriString)
            val resumeMs = when {
                explicitStartMs >= 0L -> explicitStartMs
                prefs.resumePlaybackEnabled && history != null && history.isContinueWatching ->
                    history.lastPositionMs
                else -> 0L
            }
            val speed = history?.preferredSpeed ?: prefs.defaultPlaybackSpeed
            val syncOffset = history?.subtitleSyncOffsetMs ?: prefs.subtitleDefaultSyncOffsetMs

            _uiState.value = PlayerScreenUiState(
                currentVideo = resolvedVideo,
                videoTitle = resolvedVideo.title.ifBlank { fallbackTitle },
                aspectRatioMode = prefs.defaultAspectRatio,
                isLocked = false,
                isFullScreen = true,
                resumedFromPositionMs = resumeMs.takeIf { it > 2_000L }
            )

            playerEngine.setSubtitleSyncOffset(syncOffset)
            playerEngine.prepareMedia(
                uriString = uriString,
                startPositionMs = resumeMs,
                playbackSpeed = speed,
                autoPlay = true
            )

            if (!history?.externalSubtitleUri.isNullOrBlank()) {
                val subUri = Uri.parse(history!!.externalSubtitleUri!!)
                mediaRepository.readSubtitleContent(subUri).onSuccess { (name, content) ->
                    playerEngine.loadExternalSubtitle(
                        uriString = history.externalSubtitleUri,
                        name = history.externalSubtitleName ?: name,
                        rawContent = content,
                        syncOffsetMs = syncOffset
                    )
                }
            }
        }
    }

    fun startFromBeginning() {
        playerEngine.seekTo(0L)
        notifyLiveDubSeekToPosition(0L)
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun dismissResumeBanner() {
        _uiState.value = _uiState.value.copy(resumedFromPositionMs = null)
    }

    fun togglePlayPause() = playerEngine.togglePlayPause()

    fun pausePlayback() {
        playerEngine.pause()
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    fun seekTo(positionMs: Long) {
        playerEngine.seekTo(positionMs)
        notifyLiveDubSeekToPosition(positionMs)
    }

    fun seekBySeconds(seconds: Int) {
        val targetMs = (playerEngine.state.value.currentPositionMs + seconds * 1_000L).coerceAtLeast(0L)
        playerEngine.seekBy(seconds * 1_000L)
        notifyLiveDubSeekToPosition(targetMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerEngine.setPlaybackSpeed(speed)
        val uri = activeUriString ?: return
        viewModelScope.launch {
            historyRepository.savePreferredSpeed(uri, speed)
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        _uiState.value = _uiState.value.copy(aspectRatioMode = mode)
    }

    fun toggleScreenLock() {
        _uiState.value = _uiState.value.copy(isLocked = !_uiState.value.isLocked)
    }

    fun toggleFullScreen() {
        _uiState.value = _uiState.value.copy(isFullScreen = !_uiState.value.isFullScreen)
    }

    fun selectAudioTrack(track: AudioTrackInfo) = playerEngine.selectAudioTrack(track)

    fun selectSubtitleTrack(track: SubtitleTrackInfo) = playerEngine.selectEmbeddedSubtitle(track)

    fun disableSubtitles() = playerEngine.disableSubtitles()

    fun loadExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            mediaRepository.readSubtitleContent(uri).fold(
                onSuccess = { (name, content) ->
                    val sync = playerEngine.state.value.subtitleSyncOffsetMs
                    val loaded = playerEngine.loadExternalSubtitle(
                        uriString = uri.toString(),
                        name = name,
                        rawContent = content,
                        syncOffsetMs = sync
                    )
                    if (loaded) {
                        persistProgressIfChanged(force = true)
                        activeUriString?.let { videoUri ->
                            historyRepository.saveExternalSubtitle(
                                uriString = videoUri,
                                subtitleUri = uri.toString(),
                                subtitleName = name,
                                syncOffsetMs = sync
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            subtitleLoadFeedback = "Loaded subtitle: $name"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            subtitleLoadFeedback = "No valid SRT or VTT cues found in $name"
                        )
                    }
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        subtitleLoadFeedback = "Unable to read the selected subtitle file."
                    )
                }
            )
        }
    }

    fun clearSubtitleFeedback() {
        _uiState.value = _uiState.value.copy(subtitleLoadFeedback = null)
    }

    fun adjustSubtitleSync(deltaMs: Long) {
        val next = (playerEngine.state.value.subtitleSyncOffsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
        setSubtitleSync(next)
    }

    fun setSubtitleSync(offsetMs: Long) {
        playerEngine.setSubtitleSyncOffset(offsetMs)
        val uri = activeUriString ?: return
        viewModelScope.launch {
            historyRepository.saveSubtitleSyncOffset(uri, offsetMs)
        }
    }

    fun setSubtitleFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleFontSizeSp(sizeSp) }
    }

    fun setSubtitleBottomPadding(fraction: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBottomPaddingFraction(fraction) }
    }

    fun playNextInLibraryIfEnabled(): Boolean {
        val prefs = preferences.value
        val current = _uiState.value.currentVideo ?: return false
        if (!prefs.autoPlayNext) return false
        val library = mediaRepository.sortVideos(
            mediaRepository.libraryState.value.videos,
            prefs.sortOption
        )
        val idx = library.indexOfFirst { it.uriString == current.uriString }
        if (idx in 0 until library.lastIndex) {
            val next = library[idx + 1]
            activeUriString = null
            initializeMedia(next.uriString, next.title, explicitStartMs = 0L)
            return true
        }
        return false
    }

    fun retryLoad() {
        val uri = activeUriString ?: return
        val title = _uiState.value.videoTitle
        activeUriString = null
        initializeMedia(uri, title)
    }

    fun persistProgressSync() {
        viewModelScope.launch { persistProgressIfChanged(force = true) }
    }

    private suspend fun persistProgressIfChanged(force: Boolean = false) {
        val video = _uiState.value.currentVideo ?: return
        val s = playerEngine.state.value
        if (s.errorMessage != null) return
        val pos = s.currentPositionMs
        if (!force && pos == lastSavedPositionMs) return
        if (pos <= 0L && !force) return
        lastSavedPositionMs = pos
        historyRepository.recordOrUpdateProgress(
            video = video,
            positionMs = pos,
            durationMs = s.durationMs,
            speed = s.playbackSpeed
        )
    }

    fun setBatterySaverMode(mode: BatterySaverMode) {
        viewModelScope.launch { settingsRepository.setBatterySaverMode(mode) }
    }

    fun setBatterySaverCapRefreshRate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverCapRefreshRate(enabled) }
    }

    fun setBatterySaverDisableAnimations(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatterySaverDisableAnimations(enabled) }
    }

    // --- Phase 1 AI Dubbing Controls (English -> Bengali / বাংলা) ---

    fun setDubbingEnabled(enabled: Boolean) {
        viewModelScope.launch { dubbingPreferencesRepository.setDubbingEnabled(enabled) }
    }

    fun setDubbingSourceLanguage(language: DubbingLanguage) {
        if (!language.isPhase1Active) return
        viewModelScope.launch { dubbingPreferencesRepository.setSourceLanguage(language) }
    }

    fun setDubbingTargetLanguage(language: DubbingLanguage) {
        if (!language.isPhase1Active) return
        viewModelScope.launch { dubbingPreferencesRepository.setTargetLanguage(language) }
    }

    fun setDubbingOutputMode(mode: DubbingAudioOutputMode) {
        viewModelScope.launch { dubbingPreferencesRepository.setOutputMode(mode) }
    }

    fun setDubbingOriginalVolume(volume: Float) {
        viewModelScope.launch { dubbingPreferencesRepository.setOriginalAudioVolume(volume) }
    }

    fun setDubbingVoiceVolume(volume: Float) {
        viewModelScope.launch { dubbingPreferencesRepository.setDubbedAudioVolume(volume) }
    }

    fun adjustDubbingSyncOffset(deltaMs: Long) {
        val next = (dubbingConfigFlow.value.syncOffsetMs + deltaMs).coerceIn(-5_000L, 5_000L)
        viewModelScope.launch { dubbingPreferencesRepository.setSyncOffsetMs(next) }
    }

    fun setDubbingSyncOffsetMs(offsetMs: Long) {
        viewModelScope.launch { dubbingPreferencesRepository.setSyncOffsetMs(offsetMs.coerceIn(-5_000L, 5_000L)) }
    }

    fun resetDubbingSyncOffset() {
        viewModelScope.launch { dubbingPreferencesRepository.setSyncOffsetMs(0L) }
    }

    fun setPreserveBackgroundAudio(preserve: Boolean) {
        viewModelScope.launch { dubbingPreferencesRepository.setPreserveBackgroundAudio(preserve) }
    }

    fun setBgmDuckingStrength(strength: Float) {
        viewModelScope.launch { dubbingPreferencesRepository.setBgmDuckingStrength(strength) }
    }

    fun setVocalAttenuationEnabled(enabled: Boolean) {
        viewModelScope.launch { dubbingPreferencesRepository.setVocalAttenuationEnabled(enabled) }
    }

    fun setFastMovieModeEnabled(enabled: Boolean) {
        viewModelScope.launch { dubbingPreferencesRepository.setFastMovieModeEnabled(enabled) }
    }

    fun addDubbingCharacter(profile: CharacterVoiceProfile) {
        val current = dubbingConfigFlow.value.characterVoices
        val uniqueId = if (profile.characterId.isBlank() || current.any { it.characterId == profile.characterId }) {
            DubbingVoiceCatalog.nextUniqueCharacterId(current)
        } else {
            profile.characterId
        }
        val safeSpeakerId = profile.speakerId.trim().ifBlank {
            DubbingVoiceCatalog.nextAvailableSpeakerId(current)
        }
        val safeName = profile.label.trim().ifBlank {
            DubbingVoiceCatalog.nextDefaultCharacterName(current)
        }
        val added = profile.copy(
            characterId = uniqueId,
            speakerId = safeSpeakerId,
            label = safeName,
            createdAtMs = if (profile.createdAtMs > 0L) profile.createdAtMs else System.currentTimeMillis()
        )
        val updatedList = current + added
        val mediaUri = activeUriString.orEmpty()
        viewModelScope.launch {
            dubbingPreferencesRepository.saveCharacters(updatedList)
            if (mediaUri.isNotBlank()) {
                dubbingPreferencesRepository.syncMovieAssignmentsForCharacters(mediaUri, updatedList)
            }
        }
    }

    fun updateDubbingCharacter(updated: CharacterVoiceProfile) {
        val current = dubbingConfigFlow.value.characterVoices
        val next = current.map { if (it.characterId == updated.characterId) updated else it }
        val mediaUri = activeUriString.orEmpty()
        viewModelScope.launch {
            dubbingPreferencesRepository.saveCharacters(next)
            if (mediaUri.isNotBlank()) {
                dubbingPreferencesRepository.syncMovieAssignmentsForCharacters(mediaUri, next)
            }
        }
    }

    fun removeDubbingCharacter(characterId: String) {
        if (ttsPreviewEngine.state.value.activePreviewCharacterId == characterId) {
            ttsPreviewEngine.stopPreview()
        }
        val current = dubbingConfigFlow.value.characterVoices
        val next = current.filterNot { it.characterId == characterId }
        val mediaUri = activeUriString.orEmpty()
        viewModelScope.launch {
            dubbingPreferencesRepository.saveCharacters(next)
            if (mediaUri.isNotBlank()) {
                dubbingPreferencesRepository.syncMovieAssignmentsForCharacters(mediaUri, next)
            }
        }
    }

    fun resetDefaultDubbingCharacters() {
        ttsPreviewEngine.stopPreview()
        val defaults = DubbingVoiceCatalog.defaultCharacters()
        val mediaUri = activeUriString.orEmpty()
        viewModelScope.launch {
            dubbingPreferencesRepository.resetDefaultCharacters()
            if (mediaUri.isNotBlank()) {
                dubbingPreferencesRepository.syncMovieAssignmentsForCharacters(mediaUri, defaults)
            }
        }
    }

    fun previewCharacterVoice(profile: CharacterVoiceProfile) {
        if (bengaliTtsModelState.value is BengaliTtsModelState.Ready) {
            if (_activeNeuralPreviewCharacterId.value == profile.characterId) {
                stopVoicePreview()
                return
            }
            stopVoicePreview()
            stopDubbedAudioPlayback()
            _activeNeuralPreviewCharacterId.value = profile.characterId
            activeCharacterNeuralPreviewJob = viewModelScope.launch {
                val synthResult = offlineBengaliTtsEngine.synthesizeCharacterPreview(
                    profile = profile,
                    sampleText = OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE
                )
                synthResult.fold(
                    onSuccess = { wavFile ->
                        val started = dubAudioPreviewPlayer.playWavFile(
                            filePath = wavFile.absolutePath,
                            onCompleted = {
                                if (_activeNeuralPreviewCharacterId.value == profile.characterId) {
                                    _activeNeuralPreviewCharacterId.value = null
                                }
                            },
                            onError = {
                                if (_activeNeuralPreviewCharacterId.value == profile.characterId) {
                                    _activeNeuralPreviewCharacterId.value = null
                                }
                            }
                        )
                        if (!started && _activeNeuralPreviewCharacterId.value == profile.characterId) {
                            _activeNeuralPreviewCharacterId.value = null
                        }
                    },
                    onFailure = {
                        if (_activeNeuralPreviewCharacterId.value == profile.characterId) {
                            _activeNeuralPreviewCharacterId.value = null
                        }
                    }
                )
            }
        } else {
            ttsPreviewEngine.previewCharacter(profile)
        }
    }

    fun stopVoicePreview() {
        activeCharacterNeuralPreviewJob?.cancel()
        activeCharacterNeuralPreviewJob = null
        if (_activeNeuralPreviewCharacterId.value != null) {
            _activeNeuralPreviewCharacterId.value = null
            dubAudioPreviewPlayer.stop()
        }
        ttsPreviewEngine.stopPreview()
    }

    fun inspectCurrentVideoAudio() {
        val uri = activeUriString ?: return
        if (_isInspectingAudio.value) return
        _isInspectingAudio.value = true
        _extractionMessage.value = null
        viewModelScope.launch {
            videoAudioExtractor.inspectAudioTrack(uri).fold(
                onSuccess = { info ->
                    _extractedAudioInfo.value = info
                    _extractionMessage.value = null
                },
                onFailure = { err ->
                    _extractionMessage.value = err.message ?: "Unable to inspect audio stream for this media file."
                }
            )
            _isInspectingAudio.value = false
        }
    }

    fun runStage1AudioExtraction() {
        val uri = activeUriString ?: return
        if (_isExtractingAudio.value) return
        _isExtractingAudio.value = true
        _extractionMessage.value = null
        viewModelScope.launch {
            videoAudioExtractor.extractAudioStreamToCache(uri).fold(
                onSuccess = { info ->
                    _extractedAudioInfo.value = info
                    _extractionMessage.value =
                        "Stage 1 audio extraction succeeded on-device. Ready to feed into Phase 2 offline English ASR."
                },
                onFailure = { err ->
                    _extractionMessage.value = err.message ?: "Audio extraction failed for this video."
                }
            )
            _isExtractingAudio.value = false
        }
    }

    // --- Phase 2 Real Offline English Speech Recognition (whisper.cpp) ---

    fun downloadWhisperBaseEnModel() {
        whisperSpeechEngine.startDownloadBaseEnModel()
    }

    fun cancelWhisperModelOperation() {
        whisperSpeechEngine.cancelModelOperation()
    }

    fun importLocalWhisperModel(uri: Uri) {
        val displayName = resolveDocumentDisplayName(uri)
        whisperSpeechEngine.importLocalModelFile(uri, displayName)
    }

    fun loadInstalledWhisperModel() {
        whisperSpeechEngine.loadInstalledModel()
    }

    fun unloadWhisperModel() {
        whisperSpeechEngine.unloadModel()
    }

    fun deleteWhisperModel() {
        whisperSpeechEngine.deleteInstalledModel()
    }

    fun startVideoTranscription() {
        val uri = activeUriString.orEmpty()
        val title = _uiState.value.videoTitle
        val durationMs = playbackState.value.durationMs
        whisperSpeechEngine.startVideoTranscription(
            mediaUriString = uri,
            videoTitle = title,
            totalDurationMsHint = durationMs
        )
    }

    fun stopVideoTranscription() {
        whisperSpeechEngine.stopTranscription()
    }

    fun exportTranscriptionToUri(targetUri: Uri, format: TranscriptExportFormat) {
        val transState = whisperTranscriptionState.value
        if (transState.segments.isEmpty()) return
        val modelName = when (val m = whisperModelStatus.value) {
            is WhisperModelStatus.LoadedReady -> m.model.fileName
            is WhisperModelStatus.InstalledUnloaded -> m.model.fileName
            else -> WhisperModelCatalog.DEFAULT.fileName
        }
        val content = TranscriptExporter.format(
            format = format,
            videoTitle = transState.activeVideoTitle.ifBlank { _uiState.value.videoTitle },
            modelName = modelName,
            segments = transState.segments
        )
        viewModelScope.launch {
            runCatching {
                app.contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Unable to open destination file for writing.")
            }.fold(
                onSuccess = {
                    whisperSpeechEngine.setExportFeedback("Exported ${format.label} successfully.")
                },
                onFailure = { err ->
                    whisperSpeechEngine.setExportFeedback("Export failed: ${err.message ?: "Unknown error"}")
                }
            )
        }
    }

    // --- Phase 3 Real Offline English -> Bengali Translation (Google ML Kit) ---

    private fun loadCachedTranslationForVideo(uriString: String, fallbackTitle: String) {
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        _translationUiState.value = BengaliTranslationUiState(
            activeVideoUri = uriString,
            activeVideoTitle = fallbackTitle
        )
        if (uriString.isBlank()) return
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { translationCacheRepository.getCachedTranslation(uriString) }
                    .getOrDefault(emptyList())
            }
            if (activeUriString == uriString && cached.isNotEmpty()) {
                val restoredCharacterMap = cached
                    .mapNotNull { seg -> seg.characterId?.let { charId -> seg.index to charId } }
                    .toMap()
                if (restoredCharacterMap.isNotEmpty()) {
                    _diarizationUiState.value = _diarizationUiState.value.copy(
                        segmentCharacterMap = restoredCharacterMap
                    )
                }
                _translationUiState.value = BengaliTranslationUiState(
                    isTranslating = false,
                    completedSegments = cached.size,
                    totalSegments = cached.size,
                    activeVideoUri = uriString,
                    activeVideoTitle = _uiState.value.videoTitle.ifBlank { fallbackTitle },
                    segments = cached,
                    isLoadedFromCache = true,
                    statusMessage = "Loaded ${cached.size} cached Bengali segments offline"
                )
            }
        }
    }

    fun checkTranslationModelStatus() {
        viewModelScope.launch {
            offlineTranslationManager.checkModelStatus()
        }
    }

    fun downloadTranslationModel(requireWifi: Boolean = false) {
        viewModelScope.launch {
            offlineTranslationManager.downloadModel(requireWifi = requireWifi)
        }
    }

    fun deleteTranslationModel() {
        stopBengaliTranslation()
        viewModelScope.launch {
            offlineTranslationManager.deleteModel()
        }
    }

    fun startBengaliTranslation() {
        if (activeTranslationJob?.isActive == true) return
        val uri = activeUriString.orEmpty()
        val title = _uiState.value.videoTitle
        val sourceSegments = whisperTranscriptionState.value.segments

        if (sourceSegments.isEmpty()) {
            _translationUiState.value = _translationUiState.value.copy(
                errorMessage = "Generate an English transcript in the Transcribe tab first before translating to Bengali."
            )
            return
        }

        if (translationModelState.value !is TranslationModelState.Ready) {
            _translationUiState.value = _translationUiState.value.copy(
                errorMessage = "Model Required / Not Downloaded: Download the offline English → Bengali (~30 MB) ML Kit model first."
            )
            return
        }

        val total = sourceSegments.size
        _translationUiState.value = BengaliTranslationUiState(
            isTranslating = true,
            completedSegments = 0,
            totalSegments = total,
            activeVideoUri = uri,
            activeVideoTitle = title,
            segments = emptyList(),
            isLoadedFromCache = false,
            statusMessage = "Translating 0 / $total segments offline via ML Kit...",
            errorMessage = null,
            exportFeedback = null
        )

        activeTranslationJob = viewModelScope.launch {
            try {
                val result = offlineTranslationEngine.translateSegments(
                    segments = sourceSegments,
                    onProgress = { completed, segmentTotal ->
                        _translationUiState.value = _translationUiState.value.copy(
                            completedSegments = completed,
                            totalSegments = segmentTotal,
                            statusMessage = "Translating $completed / $segmentTotal segments offline..."
                        )
                    }
                )

                result.fold(
                    onSuccess = { translated ->
                        val assignedMap = _diarizationUiState.value.segmentCharacterMap
                        val enriched = SpeakerCharacterMapper.applyCharactersToTranslatedSegments(
                            segments = translated,
                            segmentIndexToCharacterId = assignedMap
                        )
                        if (uri.isNotBlank() && enriched.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    translationCacheRepository.saveTranslation(uri, enriched)
                                }
                            }
                        }
                        _translationUiState.value = _translationUiState.value.copy(
                            isTranslating = false,
                            completedSegments = enriched.size,
                            totalSegments = enriched.size,
                            segments = enriched,
                            isLoadedFromCache = false,
                            statusMessage = "Completed · ${enriched.size} segments translated offline & cached locally",
                            errorMessage = null
                        )
                    },
                    onFailure = { err ->
                        _translationUiState.value = _translationUiState.value.copy(
                            isTranslating = false,
                            statusMessage = "Translation failed",
                            errorMessage = err.message ?: "Offline English → Bengali translation failed."
                        )
                    }
                )
            } catch (ce: CancellationException) {
                _translationUiState.value = _translationUiState.value.copy(
                    isTranslating = false,
                    statusMessage = "Stopped by user",
                    errorMessage = null
                )
                throw ce
            }
        }
    }

    fun stopBengaliTranslation() {
        val job = activeTranslationJob
        activeTranslationJob = null
        job?.cancel()
        if (_translationUiState.value.isTranslating) {
            _translationUiState.value = _translationUiState.value.copy(
                isTranslating = false,
                statusMessage = "Stopped by user",
                errorMessage = null
            )
        }
    }

    fun clearCachedTranslation() {
        stopBengaliTranslation()
        val uri = activeUriString.orEmpty()
        viewModelScope.launch {
            if (uri.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { translationCacheRepository.clearTranslationForVideo(uri) }
                }
            }
            _translationUiState.value = BengaliTranslationUiState(
                activeVideoUri = uri,
                activeVideoTitle = _uiState.value.videoTitle,
                statusMessage = "Cached translation cleared"
            )
        }
    }

    fun exportTranslationToUri(targetUri: Uri, format: TranslationExportFormat) {
        val transUi = _translationUiState.value
        if (transUi.segments.isEmpty()) return
        val content = TranslationExporter.format(
            format = format,
            videoTitle = transUi.activeVideoTitle.ifBlank { _uiState.value.videoTitle },
            segments = transUi.segments
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("Unable to open destination file for writing.")
                }
            }
            result.fold(
                onSuccess = {
                    _translationUiState.value = _translationUiState.value.copy(
                        exportFeedback = "Exported ${format.label} successfully."
                    )
                },
                onFailure = { err ->
                    _translationUiState.value = _translationUiState.value.copy(
                        exportFeedback = "Export failed: ${err.message ?: "Unknown error"}"
                    )
                }
            )
        }
    }

    // --- Phase 4 Real Offline Speaker Diarization & Automatic Character Mapping (Sherpa-ONNX) ---

    fun checkDiarizationModelStatus() {
        viewModelScope.launch {
            diarizationModelManager.checkModelStatus()
        }
    }

    fun downloadDiarizationModels() {
        if (activeDiarizationModelJob?.isActive == true) return
        activeDiarizationModelJob = viewModelScope.launch {
            diarizationModelManager.downloadModels()
        }
    }

    fun cancelDiarizationModelDownload() {
        activeDiarizationModelJob?.cancel()
        activeDiarizationModelJob = null
        viewModelScope.launch {
            diarizationModelManager.checkModelStatus()
        }
    }

    fun importLocalDiarizationModel(slot: DiarizationModelSlot, uri: Uri) {
        if (activeDiarizationModelJob?.isActive == true) return
        val displayName = resolveDocumentDisplayName(uri)
        activeDiarizationModelJob = viewModelScope.launch {
            diarizationModelManager.importLocalModel(
                slot = slot,
                sourceUri = uri,
                displayName = displayName
            )
        }
    }

    fun deleteDiarizationModels() {
        stopSpeakerDiarization()
        activeDiarizationModelJob?.cancel()
        activeDiarizationModelJob = null
        viewModelScope.launch {
            offlineDiarizationEngine.release()
            diarizationModelManager.deleteModels()
        }
    }

    fun setDiarizationNumClusters(numClusters: Int) {
        val clamped = if (numClusters <= 0) -1 else numClusters.coerceIn(1, 10)
        _diarizationUiState.value = _diarizationUiState.value.copy(numClustersConfig = clamped)
    }

    fun setDiarizationThreshold(threshold: Float) {
        _diarizationUiState.value = _diarizationUiState.value.copy(
            thresholdConfig = threshold.coerceIn(0.25f, 0.85f)
        )
    }

    fun startSpeakerDiarization() {
        if (activeDiarizationJob?.isActive == true) return
        val uri = activeUriString.orEmpty()
        if (uri.isBlank()) {
            _diarizationUiState.value = _diarizationUiState.value.copy(
                errorMessage = "No local video is currently loaded."
            )
            return
        }
        if (diarizationModelState.value !is DiarizationModelState.Ready) {
            _diarizationUiState.value = _diarizationUiState.value.copy(
                errorMessage = "Diarization Model Required / Not Installed: Download or import the offline Sherpa-ONNX models above first."
            )
            return
        }

        val clusters = _diarizationUiState.value.numClustersConfig
        val threshold = _diarizationUiState.value.thresholdConfig

        _diarizationUiState.value = _diarizationUiState.value.copy(
            isRunning = true,
            progressFraction = 0.02f,
            statusMessage = "Starting offline Sherpa-ONNX speaker detection...",
            errorMessage = null,
            activeVideoUri = uri,
            autoCreatedCharacterCount = 0
        )

        activeDiarizationJob = viewModelScope.launch {
            try {
                val result = offlineDiarizationEngine.diarizeVideo(
                    mediaUriString = uri,
                    numClusters = clusters,
                    threshold = threshold,
                    onProgress = { fraction, label ->
                        _diarizationUiState.value = _diarizationUiState.value.copy(
                            progressFraction = fraction.coerceIn(0f, 1f),
                            statusMessage = label
                        )
                    }
                )

                result.fold(
                    onSuccess = { speakerSegments ->
                        val transcriptSegments = whisperTranscriptionState.value.segments
                        val translatedSegments = _translationUiState.value.segments
                        val dialogueIntervals: List<Pair<Long, Long>> = when {
                            transcriptSegments.isNotEmpty() ->
                                transcriptSegments.map { it.startTimeMs to it.endTimeMs }
                            translatedSegments.isNotEmpty() ->
                                translatedSegments.map { it.startMs to it.endMs }
                            else -> emptyList()
                        }

                        val currentConfig = dubbingConfigFlow.value
                        val currentAssignments = activeMovieAssignmentsFlow.value
                        val mapping = SpeakerCharacterMapper.mapSpeakersToCharacters(
                            speakerSegments = speakerSegments,
                            dialogueIntervals = dialogueIntervals,
                            savedCharacters = currentConfig.characterVoices,
                            existingMovieAssignments = currentAssignments,
                            existingManualOverrides = manualSegmentCharacterOverrides
                        )

                        // Persist any newly auto-created Character profiles & per-movie speaker locks
                        if (mapping.newlyCreatedCharacterIds.isNotEmpty()) {
                            dubbingPreferencesRepository.saveCharacters(mapping.updatedCharacters)
                        }
                        if (mapping.movieAssignments.isNotEmpty()) {
                            dubbingPreferencesRepository.saveMovieAssignments(uri, mapping.movieAssignments)
                        }

                        // Apply aligned characters to Whisper transcript & Bengali translated segments + Room DB
                        if (mapping.segmentIndexToCharacterId.isNotEmpty()) {
                            whisperSpeechEngine.applySpeakerAssignments(mapping.segmentIndexToCharacterId)
                            if (translatedSegments.isNotEmpty()) {
                                val updatedTranslated = SpeakerCharacterMapper.applyCharactersToTranslatedSegments(
                                    segments = translatedSegments,
                                    segmentIndexToCharacterId = mapping.segmentIndexToCharacterId
                                )
                                _translationUiState.value = _translationUiState.value.copy(segments = updatedTranslated)
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        translationCacheRepository.updateSegmentCharacterIds(
                                            videoUri = uri,
                                            assignments = mapping.segmentIndexToCharacterId
                                        )
                                    }
                                }
                            }
                        }

                        val distinctCount = speakerSegments.map { it.speakerIndex }.distinct().size
                        val createdSuffix = if (mapping.newlyCreatedCharacterIds.isNotEmpty()) {
                            " · Auto-created ${mapping.newlyCreatedCharacterIds.size} new character(s)"
                        } else {
                            ""
                        }

                        _diarizationUiState.value = _diarizationUiState.value.copy(
                            isRunning = false,
                            progressFraction = 1f,
                            statusMessage = if (distinctCount == 0) {
                                "Completed · No distinct speakers detected in audio"
                            } else {
                                "Detected $distinctCount speaker(s) across ${speakerSegments.size} turns$createdSuffix"
                            },
                            errorMessage = null,
                            detectedSegments = speakerSegments,
                            speakerToCharacterMap = mapping.speakerToCharacterMap,
                            segmentCharacterMap = mapping.segmentIndexToCharacterId,
                            autoCreatedCharacterCount = mapping.newlyCreatedCharacterIds.size
                        )
                    },
                    onFailure = { err ->
                        _diarizationUiState.value = _diarizationUiState.value.copy(
                            isRunning = false,
                            statusMessage = "Diarization failed",
                            errorMessage = err.message ?: "Offline speaker diarization failed."
                        )
                    }
                )
            } catch (ce: CancellationException) {
                _diarizationUiState.value = _diarizationUiState.value.copy(
                    isRunning = false,
                    statusMessage = "Stopped by user",
                    errorMessage = null
                )
                throw ce
            }
        }
    }

    fun stopSpeakerDiarization() {
        offlineDiarizationEngine.requestCancel()
        val job = activeDiarizationJob
        activeDiarizationJob = null
        job?.cancel()
        if (_diarizationUiState.value.isRunning) {
            _diarizationUiState.value = _diarizationUiState.value.copy(
                isRunning = false,
                statusMessage = "Stopped by user",
                errorMessage = null
            )
        }
    }

    /**
     * Reassigns an entire detected speaker cluster (`Speaker 1`, `Speaker 2`, ...) to any saved
     * VOXORA Character profile and updates DataStore movie locks, transcript labels, and Room cache.
     */
    fun reassignSpeakerToCharacter(speakerIndex: Int, characterId: String) {
        val uri = activeUriString.orEmpty()
        val currentState = _diarizationUiState.value
        val targetCharacter = dubbingConfigFlow.value.characterVoices
            .firstOrNull { it.characterId == characterId } ?: return

        val updatedSpeakerMap = currentState.speakerToCharacterMap.toMutableMap().apply {
            put(speakerIndex, characterId)
        }

        val transcriptSegments = whisperTranscriptionState.value.segments
        val translatedSegments = _translationUiState.value.segments
        val dialogueIntervals: List<Pair<Long, Long>> = when {
            transcriptSegments.isNotEmpty() -> transcriptSegments.map { it.startTimeMs to it.endTimeMs }
            translatedSegments.isNotEmpty() -> translatedSegments.map { it.startMs to it.endMs }
            else -> emptyList()
        }

        val updatedSegmentMap = currentState.segmentCharacterMap.toMutableMap()
        dialogueIntervals.forEachIndexed { index, (startMs, endMs) ->
            if (index !in manualSegmentCharacterOverrides) {
                val bestSpeaker = SpeakerCharacterMapper.findBestSpeakerIndexForInterval(
                    startMs = startMs,
                    endMs = endMs,
                    speakerSegments = currentState.detectedSegments
                )
                if (bestSpeaker == speakerIndex) {
                    updatedSegmentMap[index] = characterId
                }
            }
        }

        _diarizationUiState.value = currentState.copy(
            speakerToCharacterMap = updatedSpeakerMap,
            segmentCharacterMap = updatedSegmentMap
        )

        whisperSpeechEngine.applySpeakerAssignments(updatedSegmentMap)
        if (translatedSegments.isNotEmpty()) {
            val updatedTranslated = SpeakerCharacterMapper.applyCharactersToTranslatedSegments(
                segments = translatedSegments,
                segmentIndexToCharacterId = updatedSegmentMap
            )
            _translationUiState.value = _translationUiState.value.copy(segments = updatedTranslated)
        }

        viewModelScope.launch {
            if (uri.isNotBlank()) {
                val slotId = SpeakerCharacterMapper.speakerSlotId(speakerIndex)
                val existingAssignments = activeMovieAssignmentsFlow.value
                    .filterNot { it.diarizationSpeakerId.equals(slotId, ignoreCase = true) }
                val updatedAssignment = MovieSpeakerAssignment(
                    diarizationSpeakerId = slotId,
                    characterId = targetCharacter.characterId,
                    lockedVoiceId = targetCharacter.voiceId,
                    isAutoAssigned = false
                )
                dubbingPreferencesRepository.saveMovieAssignments(uri, existingAssignments + updatedAssignment)

                if (updatedSegmentMap.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            translationCacheRepository.updateSegmentCharacterIds(uri, updatedSegmentMap)
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually overrides the assigned VOXORA Character for a single dialogue line in both the
     * Transcript and Bengali Translation views, persisting immediately to Room DB.
     */
    fun overrideSegmentCharacter(segmentIndex: Int, characterId: String) {
        val uri = activeUriString.orEmpty()
        manualSegmentCharacterOverrides[segmentIndex] = characterId

        val updatedSegmentMap = _diarizationUiState.value.segmentCharacterMap.toMutableMap().apply {
            put(segmentIndex, characterId)
        }
        _diarizationUiState.value = _diarizationUiState.value.copy(
            segmentCharacterMap = updatedSegmentMap
        )

        whisperSpeechEngine.applySpeakerAssignments(mapOf(segmentIndex to characterId))

        val currentTranslated = _translationUiState.value.segments
        if (currentTranslated.isNotEmpty()) {
            val updatedTranslated = currentTranslated.map { seg ->
                if (seg.index == segmentIndex) seg.copy(characterId = characterId) else seg
            }
            _translationUiState.value = _translationUiState.value.copy(segments = updatedTranslated)
        }

        val currentDubbed = _bengaliVoiceUiState.value.segments
        if (currentDubbed.isNotEmpty()) {
            val updatedDubbed = currentDubbed.map { seg ->
                if (seg.segmentIndex == segmentIndex) seg.copy(characterId = characterId) else seg
            }
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(segments = updatedDubbed)
        }

        if (uri.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    translationCacheRepository.updateSegmentCharacterId(
                        videoUri = uri,
                        segmentIndex = segmentIndex,
                        characterId = characterId
                    )
                }
            }
        }
    }

    // --- Phase 5 Real Offline Bengali Voice Generation (Sherpa-ONNX VITS TTS) & One-Click AI Setup ---

    fun checkBengaliTtsModelStatus() {
        viewModelScope.launch {
            bengaliTtsModelManager.checkModelStatus()
        }
    }

    fun downloadBengaliTtsModels() {
        if (activeBengaliTtsModelJob?.isActive == true) return
        activeBengaliTtsModelJob = viewModelScope.launch {
            bengaliTtsModelManager.downloadModels()
        }
    }

    fun cancelBengaliTtsModelDownload() {
        activeBengaliTtsModelJob?.cancel()
        activeBengaliTtsModelJob = null
        viewModelScope.launch {
            bengaliTtsModelManager.checkModelStatus()
        }
    }

    fun importLocalBengaliTtsFile(slot: BengaliTtsModelSlot, uri: Uri) {
        if (activeBengaliTtsModelJob?.isActive == true) return
        val displayName = resolveDocumentDisplayName(uri)
        activeBengaliTtsModelJob = viewModelScope.launch {
            bengaliTtsModelManager.importLocalFile(
                slot = slot,
                sourceUri = uri,
                displayName = displayName
            )
        }
    }

    fun deleteBengaliTtsModels() {
        stopBengaliVoiceGeneration()
        stopVoicePreview()
        stopDubbedAudioPlayback()
        activeBengaliTtsModelJob?.cancel()
        activeBengaliTtsModelJob = null
        viewModelScope.launch {
            offlineBengaliTtsEngine.release()
            bengaliTtsModelManager.deleteModels()
        }
    }

    fun startBengaliVoiceGeneration() {
        if (activeBengaliVoiceGenJob?.isActive == true) return
        val uri = activeUriString.orEmpty()
        if (uri.isBlank()) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                errorMessage = "No local video is currently loaded."
            )
            return
        }
        if (bengaliTtsModelState.value !is BengaliTtsModelState.Ready) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                errorMessage = "Bengali TTS Model Required / Not Installed: Download or import the offline Sherpa-ONNX VITS Bengali model pack first."
            )
            return
        }
        val translatedSegments = _translationUiState.value.segments
        if (translatedSegments.isEmpty()) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                errorMessage = "No Bengali translated dialogue found. Run Transcribe and Translate to বাংলা first."
            )
            return
        }

        stopDubbedAudioPlayback()
        val characters = dubbingConfigFlow.value.characterVoices
        val overrides = _diarizationUiState.value.segmentCharacterMap

        _bengaliVoiceUiState.value = BengaliVoiceGenerationUiState(
            isSynthesizing = true,
            synthesizedCount = 0,
            totalCount = translatedSegments.size,
            statusMessage = "Initializing offline Sherpa-ONNX VITS Bengali voice...",
            errorMessage = null,
            activeVideoUri = uri,
            segments = emptyList(),
            currentlyPlayingSegmentIndex = null
        )

        activeBengaliVoiceGenJob = viewModelScope.launch {
            try {
                val accumulated = mutableListOf<DubbedAudioSegment>()
                val result = offlineBengaliTtsEngine.synthesizeSegments(
                    videoUri = uri,
                    translatedSegments = translatedSegments,
                    characters = characters,
                    segmentCharacterOverrides = overrides,
                    onSegmentSynthesized = { completed, total, dubbedSeg ->
                        accumulated.add(dubbedSeg)
                        _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                            synthesizedCount = completed,
                            totalCount = total,
                            statusMessage = "Synthesized $completed / $total Bengali dialogue lines...",
                            segments = accumulated.toList()
                        )
                    },
                    onStatusUpdate = { status ->
                        _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                            statusMessage = status
                        )
                    }
                )

                result.fold(
                    onSuccess = { allSegments ->
                        _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                            isSynthesizing = false,
                            synthesizedCount = allSegments.size,
                            totalCount = translatedSegments.size,
                            statusMessage = "Generated ${allSegments.size} Bengali voice line(s) offline · Live sync ready",
                            errorMessage = null,
                            segments = allSegments
                        )
                        dubbingPlaybackController.attachDubbedSegments(
                            videoUri = uri,
                            segments = allSegments,
                            videoDurationMsHint = playerEngine.state.value.durationMs
                        )
                    },
                    onFailure = { err ->
                        _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                            isSynthesizing = false,
                            statusMessage = "Bengali voice generation failed",
                            errorMessage = err.message ?: "Offline Bengali TTS synthesis failed."
                        )
                    }
                )
            } catch (ce: CancellationException) {
                _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                    isSynthesizing = false,
                    statusMessage = "Stopped by user",
                    errorMessage = null
                )
                throw ce
            }
        }
    }

    fun stopBengaliVoiceGeneration() {
        offlineBengaliTtsEngine.requestCancel()
        val job = activeBengaliVoiceGenJob
        activeBengaliVoiceGenJob = null
        job?.cancel()
        if (_bengaliVoiceUiState.value.isSynthesizing) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                isSynthesizing = false,
                statusMessage = "Stopped by user",
                errorMessage = null
            )
        }
    }

    fun togglePlayDubbedSegment(segment: DubbedAudioSegment) {
        if (_bengaliVoiceUiState.value.currentlyPlayingSegmentIndex == segment.segmentIndex) {
            stopDubbedAudioPlayback()
            return
        }
        stopVoicePreview()
        stopDubbedAudioPlayback()

        val started = dubAudioPreviewPlayer.playWavFile(
            filePath = segment.audioFilePath,
            onCompleted = {
                if (_bengaliVoiceUiState.value.currentlyPlayingSegmentIndex == segment.segmentIndex) {
                    _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                        currentlyPlayingSegmentIndex = null
                    )
                }
            },
            onError = { errMsg ->
                _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                    currentlyPlayingSegmentIndex = null,
                    errorMessage = errMsg
                )
            }
        )
        if (started) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                currentlyPlayingSegmentIndex = segment.segmentIndex,
                errorMessage = null
            )
        }
    }

    fun stopDubbedAudioPlayback() {
        dubAudioPreviewPlayer.stop()
        if (_bengaliVoiceUiState.value.currentlyPlayingSegmentIndex != null) {
            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(
                currentlyPlayingSegmentIndex = null
            )
        }
    }

    fun clearSynthesizedBengaliAudio() {
        stopBengaliVoiceGeneration()
        stopDubbedAudioPlayback()
        cancelDubbedExport()
        dubbingPlaybackController.clearTrack()
        val uri = activeUriString.orEmpty()
        _bengaliVoiceUiState.value = BengaliVoiceGenerationUiState(activeVideoUri = uri)
        if (uri.isNotBlank()) {
            viewModelScope.launch {
                offlineBengaliTtsEngine.clearCacheForVideo(uri)
            }
        }
    }

    // --- Phase 6 + v2.1.0 Fast Live 30-Second Rolling Chunk Auto-Dub & Offline Export ---

    private fun notifyLiveDubSeekToPosition(positionMs: Long) {
        if (activeAutoDubJob?.isActive != true) return
        val targetChunk = AutoDubPipelineOrchestrator.chunkIndexForPositionMs(positionMs)
        if (targetChunk in completedLiveChunkIndices || targetChunk == activeProcessingChunkIndex) {
            return
        }
        pendingSeekTargetChunk = targetChunk
        autoDubPipelineOrchestrator.requestCancelActiveStage()
        activeChunkWorkJob?.cancel()
    }

    /**
     * Launches Fast Live 30-Second Rolling Chunk Dubbing starting immediately at the player's
     * current position:
     * 1. Dubs the current 30s chunk (`Whisper tiny -> ML Kit বাংলা -> Sherpa VITS`, skipping heavy
     *    Diarization when Fast Movie Mode is ON).
     * 2. As soon as Chunk 1 completes (within seconds), immediately starts synchronized Bengali playback.
     * 3. Automatically pre-dubs the next 30s chunks in the background while playing, and immediately
     *    re-targets if the user seeks to an un-dubbed timestamp.
     */
    fun startAutoDubVideo() {
        if (activeAutoDubJob?.isActive == true) return
        val uri = activeUriString.orEmpty()
        if (uri.isBlank()) {
            _autoDubPipelineState.value = AutoDubPipelineState(
                isRunning = false,
                currentStage = AutoDubStage.ERROR,
                stageLabel = "Auto-Dub unavailable",
                errorMessage = "No local video is currently loaded."
            )
            return
        }

        stopVoicePreview()
        stopDubbedAudioPlayback()
        stopBengaliTranslation()
        stopSpeakerDiarization()
        stopBengaliVoiceGeneration()

        val title = _uiState.value.videoTitle
        val durationHint = playerEngine.state.value.durationMs
        val initialFastMode = dubbingConfigFlow.value.fastMovieModeEnabled

        _autoDubPipelineState.value = AutoDubPipelineState(
            isRunning = true,
            currentStage = AutoDubStage.TRANSCRIBING,
            stageIndex = 1,
            totalStages = if (initialFastMode) 3 else 4,
            stageLabel = "Checking offline AI models on disk...",
            stageProgressFraction = 0.02f,
            isLiveChunkStreaming = false,
            errorMessage = null
        )

        activeAutoDubJob = viewModelScope.launch {
            completedLiveChunkIndices.clear()
            pendingSeekTargetChunk = null
            activeProcessingChunkIndex = -1
            dubbingPlaybackController.clearTrack()

            val chunkTranslatedMap = linkedMapOf<Int, List<com.voxora.player.ai.translation.TranslatedSegment>>()
            val chunkDubbedMap = linkedMapOf<Int, List<DubbedAudioSegment>>()
            val chunkSpeakerMap = linkedMapOf<Int, List<com.voxora.player.ai.diarization.SpeakerSegment>>()
            val accumulatedSegCharMap = linkedMapOf<Int, String>()
            val accumulatedSpeakerCharMap = linkedMapOf<Int, String>()
            var totalAutoCreatedChars = 0
            var firstChunkActivated = false

            try {
                val missing = autoDubPipelineOrchestrator.findMissingModels(fastMovieMode = initialFastMode)
                if (missing.isNotEmpty()) {
                    _autoDubPipelineState.value = AutoDubPipelineState(
                        isRunning = false,
                        currentStage = AutoDubStage.ERROR,
                        stageLabel = "Missing offline AI model(s)",
                        errorMessage = "Missing offline AI model(s): ${missing.joinToString(", ")}. Tap \"Download All Missing Models\" in One-Click AI Setup above first."
                    )
                    return@launch
                }

                val detectedDurMs = maxOf(
                    durationHint,
                    autoDubPipelineOrchestrator.getVideoAudioDurationMs(uri)
                )
                val totalChunks = AutoDubPipelineOrchestrator.totalChunksForDurationMs(detectedDurMs)

                while (isActive) {
                    val currentPlayerPosMs = playerEngine.state.value.currentPositionMs.coerceAtLeast(0L)
                    val playerChunk = AutoDubPipelineOrchestrator.chunkIndexForPositionMs(currentPlayerPosMs)
                        .coerceIn(0, (totalChunks - 1).coerceAtLeast(0))

                    val requestedSeekChunk = pendingSeekTargetChunk
                        ?.also { pendingSeekTargetChunk = null }
                        ?.coerceIn(0, (totalChunks - 1).coerceAtLeast(0))
                        ?.takeIf { it !in completedLiveChunkIndices }

                    val nextChunk: Int? = requestedSeekChunk ?: run {
                        val lookAheadLimit = minOf(totalChunks - 1, playerChunk + LIVE_PREBUFFER_CHUNKS_AHEAD)
                        (playerChunk..lookAheadLimit).firstOrNull { it !in completedLiveChunkIndices }
                    }

                    if (nextChunk == null) {
                        if (completedLiveChunkIndices.size >= totalChunks) {
                            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(isSynthesizing = false)
                            _autoDubPipelineState.value = AutoDubPipelineState(
                                isRunning = false,
                                currentStage = AutoDubStage.COMPLETED,
                                stageIndex = if (dubbingConfigFlow.value.fastMovieModeEnabled) 3 else 4,
                                totalStages = if (dubbingConfigFlow.value.fastMovieModeEnabled) 3 else 4,
                                stageLabel = "All $totalChunks chunks dubbed · Live Bengali sync active!",
                                stageProgressFraction = 1f,
                                isLiveChunkStreaming = false,
                                completedChunkCount = completedLiveChunkIndices.size,
                                totalChunkCount = totalChunks,
                                bufferedAheadChunks = 0,
                                errorMessage = null
                            )
                            break
                        }

                        // Already buffered ahead of playback; idle lightly until player moves forward or user seeks
                        val aheadCount = completedLiveChunkIndices.count { it > playerChunk }
                        _autoDubPipelineState.value = AutoDubPipelineState(
                            isRunning = true,
                            currentStage = AutoDubStage.COMPLETED,
                            stageIndex = if (dubbingConfigFlow.value.fastMovieModeEnabled) 3 else 4,
                            totalStages = if (dubbingConfigFlow.value.fastMovieModeEnabled) 3 else 4,
                            stageLabel = "LIVE · ${completedLiveChunkIndices.size}/$totalChunks chunks ready ($aheadCount buffered ahead)",
                            stageProgressFraction = 1f,
                            isLiveChunkStreaming = true,
                            activeChunkIndex = playerChunk,
                            completedChunkCount = completedLiveChunkIndices.size,
                            totalChunkCount = totalChunks,
                            bufferedAheadChunks = aheadCount,
                            errorMessage = null
                        )
                        delay(400L)
                        continue
                    }

                    val liveFastMode = dubbingConfigFlow.value.fastMovieModeEnabled
                    val windowStartMs = AutoDubPipelineOrchestrator.chunkWindowStartMs(nextChunk)
                    val remainingMs = if (detectedDurMs > 0L) {
                        (detectedDurMs - windowStartMs).coerceIn(1_000L, AutoDubPipelineOrchestrator.ROLLING_CHUNK_DURATION_MS)
                    } else {
                        AutoDubPipelineOrchestrator.ROLLING_CHUNK_DURATION_MS
                    }

                    activeProcessingChunkIndex = nextChunk
                    val chunkDeferred = async(Dispatchers.IO) {
                        autoDubPipelineOrchestrator.dubSingleChunkWindow(
                            videoUri = uri,
                            chunkIndex = nextChunk,
                            windowStartMs = windowStartMs,
                            windowDurationMs = remainingMs,
                            fastMovieMode = liveFastMode,
                            savedCharacters = dubbingConfigFlow.value.characterVoices,
                            existingMovieAssignments = activeMovieAssignmentsFlow.value,
                            existingManualOverrides = manualSegmentCharacterOverrides.toMap(),
                            diarizationNumClusters = _diarizationUiState.value.numClustersConfig,
                            diarizationThreshold = _diarizationUiState.value.thresholdConfig,
                            onStageProgress = { stage, stageIdx, totalStages, prog, detail ->
                                val curChunk = AutoDubPipelineOrchestrator.chunkIndexForPositionMs(
                                    playerEngine.state.value.currentPositionMs
                                )
                                val aheadCount = completedLiveChunkIndices.count { it > curChunk }
                                _autoDubPipelineState.value = AutoDubPipelineState(
                                    isRunning = true,
                                    currentStage = stage,
                                    stageIndex = stageIdx,
                                    totalStages = totalStages,
                                    stageLabel = if (firstChunkActivated) {
                                        "LIVE · Pre-dubbing $detail"
                                    } else {
                                        detail
                                    },
                                    stageProgressFraction = prog,
                                    isLiveChunkStreaming = firstChunkActivated,
                                    activeChunkIndex = nextChunk,
                                    completedChunkCount = completedLiveChunkIndices.size,
                                    totalChunkCount = totalChunks,
                                    bufferedAheadChunks = aheadCount,
                                    errorMessage = null
                                )
                            }
                        )
                    }
                    activeChunkWorkJob = chunkDeferred

                    val chunkResult = try {
                        chunkDeferred.await()
                    } catch (ce: CancellationException) {
                        if (!isActive) throw ce
                        // Inner chunk cancelled cleanly because user seeked to a new timestamp
                        activeProcessingChunkIndex = -1
                        continue
                    } finally {
                        activeChunkWorkJob = null
                        activeProcessingChunkIndex = -1
                    }

                    var chunkFailed = false
                    chunkResult.fold(
                        onSuccess = { done ->
                            completedLiveChunkIndices.add(done.chunkIndex)
                            chunkTranslatedMap[done.chunkIndex] = done.translatedSegments
                            chunkDubbedMap[done.chunkIndex] = done.dubbedAudioSegments
                            chunkSpeakerMap[done.chunkIndex] = done.speakerSegments
                            accumulatedSegCharMap.putAll(done.segmentCharacterMap)
                            accumulatedSpeakerCharMap.putAll(done.speakerToCharacterMap)
                            totalAutoCreatedChars += done.autoCreatedCharacterCount

                            val sortedKeys = chunkDubbedMap.keys.sorted()
                            val allTranslated = sortedKeys.flatMap { chunkTranslatedMap[it].orEmpty() }
                            val allDubbed = sortedKeys.flatMap { chunkDubbedMap[it].orEmpty() }
                            val allSpeakers = sortedKeys.flatMap { chunkSpeakerMap[it].orEmpty() }

                            _translationUiState.value = BengaliTranslationUiState(
                                isTranslating = false,
                                completedSegments = allTranslated.size,
                                totalSegments = allTranslated.size,
                                statusMessage = "Live translated ${allTranslated.size} lines (${completedLiveChunkIndices.size}/$totalChunks chunks)",
                                activeVideoUri = uri,
                                activeVideoTitle = title,
                                segments = allTranslated
                            )
                            _diarizationUiState.value = _diarizationUiState.value.copy(
                                isRunning = false,
                                progressFraction = (completedLiveChunkIndices.size.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f),
                                statusMessage = if (liveFastMode) {
                                    "Fast Movie Mode ON (Multi-Speaker Diarization skipped)"
                                } else {
                                    "Detected ${allSpeakers.map { it.speakerIndex }.distinct().size} speaker(s) across ${completedLiveChunkIndices.size} chunk(s)"
                                },
                                errorMessage = null,
                                activeVideoUri = uri,
                                detectedSegments = allSpeakers,
                                speakerToCharacterMap = accumulatedSpeakerCharMap.toMap(),
                                segmentCharacterMap = accumulatedSegCharMap.toMap(),
                                autoCreatedCharacterCount = totalAutoCreatedChars
                            )
                            _bengaliVoiceUiState.value = BengaliVoiceGenerationUiState(
                                isSynthesizing = completedLiveChunkIndices.size < totalChunks,
                                synthesizedCount = allDubbed.size,
                                totalCount = allTranslated.size,
                                statusMessage = "Live Bengali streaming · ${allDubbed.size} lines (${completedLiveChunkIndices.size}/$totalChunks chunks ready)",
                                errorMessage = null,
                                activeVideoUri = uri,
                                segments = allDubbed
                            )

                            if (allTranslated.isNotEmpty()) {
                                launch(Dispatchers.IO) {
                                    runCatching {
                                        translationCacheRepository.saveTranslation(
                                            videoUri = uri,
                                            segments = allTranslated
                                        )
                                    }
                                }
                            }

                            // Attach 30s chunk without interrupting active playback of the current chunk
                            dubbingPlaybackController.attachOrUpdateRollingChunk(
                                videoUri = uri,
                                chunkTrack = done.assembledChunkTrack,
                                allAccumulatedSegments = allDubbed
                            )

                            // As soon as Chunk 1 finishes, immediately enable synchronized Bengali playback!
                            if (!firstChunkActivated) {
                                firstChunkActivated = true
                                dubbingPreferencesRepository.setDubbingEnabled(true)
                                dubbingPreferencesRepository.setOutputMode(DubbingAudioOutputMode.BENGALI_DUBBED_MIX)
                            }
                        },
                        onFailure = { err ->
                            chunkFailed = true
                            _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(isSynthesizing = false)
                            _autoDubPipelineState.value = AutoDubPipelineState(
                                isRunning = false,
                                currentStage = AutoDubStage.ERROR,
                                stageLabel = "Live Chunk Dubbing failed",
                                isLiveChunkStreaming = false,
                                completedChunkCount = completedLiveChunkIndices.size,
                                totalChunkCount = totalChunks,
                                errorMessage = err.message ?: "Live chunk dubbing failed on chunk ${nextChunk + 1}."
                            )
                        }
                    )
                    if (chunkFailed) break
                }
            } catch (ce: CancellationException) {
                _bengaliVoiceUiState.value = _bengaliVoiceUiState.value.copy(isSynthesizing = false)
                _autoDubPipelineState.value = AutoDubPipelineState(
                    isRunning = false,
                    currentStage = AutoDubStage.IDLE,
                    stageLabel = "Live Bengali dubbing stopped",
                    isLiveChunkStreaming = false,
                    completedChunkCount = completedLiveChunkIndices.size,
                    errorMessage = null
                )
                throw ce
            } finally {
                activeChunkWorkJob = null
                activeProcessingChunkIndex = -1
                pendingSeekTargetChunk = null
                withContext(NonCancellable) {
                    autoDubPipelineOrchestrator.releaseEnginesAfterLiveSession()
                }
            }
        }
    }

    fun cancelAutoDubVideo() {
        autoDubPipelineOrchestrator.requestCancelActiveStage()
        activeChunkWorkJob?.cancel()
        activeChunkWorkJob = null
        val job = activeAutoDubJob
        activeAutoDubJob = null
        job?.cancel()
        if (_autoDubPipelineState.value.isRunning) {
            _autoDubPipelineState.value = AutoDubPipelineState(
                isRunning = false,
                currentStage = AutoDubStage.IDLE,
                stageLabel = "Live Bengali dubbing stopped",
                isLiveChunkStreaming = false,
                completedChunkCount = completedLiveChunkIndices.size,
                errorMessage = null
            )
        }
    }

    fun startDubbedExport(format: DubbingExportFormat) {
        if (activeExportJob?.isActive == true) return
        val uri = activeUriString.orEmpty()
        val dubbedSegments = _bengaliVoiceUiState.value.segments
        if (uri.isBlank() || dubbedSegments.isEmpty()) {
            _dubbedExportState.value = DubbedExportState(
                isExporting = false,
                errorMessage = "Generate Bengali voices (or run Auto-Dub) first before exporting."
            )
            return
        }

        val title = _uiState.value.videoTitle
        val durationHint = playerEngine.state.value.durationMs
        val currentConfig = dubbingConfigFlow.value

        _dubbedExportState.value = DubbedExportState(
            isExporting = true,
            activeFormat = format,
            progressFraction = 0.02f,
            statusMessage = "Preparing offline ${format.label} export...",
            errorMessage = null
        )

        activeExportJob = viewModelScope.launch {
            try {
                val result = dubbedVideoExporter.exportDubbedMedia(
                    videoUriString = uri,
                    videoTitle = title,
                    videoDurationMsHint = durationHint,
                    dubbedSegments = dubbedSegments,
                    config = currentConfig,
                    format = format,
                    onProgress = { frac, msg ->
                        _dubbedExportState.value = _dubbedExportState.value.copy(
                            progressFraction = frac.coerceIn(0f, 1f),
                            statusMessage = msg
                        )
                    }
                )
                result.fold(
                    onSuccess = { exported ->
                        _dubbedExportState.value = DubbedExportState(
                            isExporting = false,
                            activeFormat = format,
                            progressFraction = 1f,
                            statusMessage = "Saved to ${exported.locationLabel}/${exported.displayName}",
                            exportedUriString = exported.uri.toString(),
                            exportedDisplayName = exported.displayName,
                            exportedLocationLabel = exported.locationLabel,
                            exportedMimeType = exported.mimeType,
                            errorMessage = null
                        )
                    },
                    onFailure = { err ->
                        _dubbedExportState.value = DubbedExportState(
                            isExporting = false,
                            activeFormat = format,
                            progressFraction = 0f,
                            statusMessage = "Export failed",
                            errorMessage = err.message ?: "Offline export failed."
                        )
                    }
                )
            } catch (ce: CancellationException) {
                _dubbedExportState.value = DubbedExportState(
                    isExporting = false,
                    statusMessage = "Export cancelled",
                    errorMessage = null
                )
                throw ce
            }
        }
    }

    fun cancelDubbedExport() {
        val job = activeExportJob
        activeExportJob = null
        job?.cancel()
        if (_dubbedExportState.value.isExporting) {
            _dubbedExportState.value = DubbedExportState(
                isExporting = false,
                statusMessage = "Export cancelled",
                errorMessage = null
            )
        }
    }

    fun clearExportStatus() {
        if (!_dubbedExportState.value.isExporting) {
            _dubbedExportState.value = DubbedExportState()
        }
    }

    /**
     * Unified One-Click AI Setup: sequentially downloads all missing offline models across
     * Whisper ASR, Sherpa-ONNX Diarization, ML Kit EN→বাংলা Translation, and Sherpa-ONNX VITS Bengali TTS
     * with live combined step & percentage progress.
     */
    fun startOneClickAiSetup() {
        if (activeOneClickSetupJob?.isActive == true) return

        val missingSteps = mutableListOf<String>()
        if (!whisperSpeechEngine.isModelInstalledOnDisk()) missingSteps.add("Whisper ASR")
        if (diarizationModelState.value !is DiarizationModelState.Ready) missingSteps.add("Speaker Diarization")
        if (translationModelState.value !is TranslationModelState.Ready) missingSteps.add("ML Kit EN→বাংলা")
        if (bengaliTtsModelState.value !is BengaliTtsModelState.Ready) missingSteps.add("Bengali TTS")

        if (missingSteps.isEmpty()) return

        activeOneClickSetupJob = viewModelScope.launch {
            val total = missingSteps.size
            var completedIdx = 0
            try {
                for (stepName in missingSteps) {
                    completedIdx += 1
                    val currentStep = completedIdx
                    _oneClickProgressState.value = OneClickProgressSnapshot(
                        isRunning = true,
                        currentStepIndex = currentStep,
                        totalSteps = total,
                        currentStepLabel = stepName,
                        currentStepProgressPercent = 0,
                        errorMessage = null
                    )

                    when (stepName) {
                        "Whisper ASR" -> {
                            val res = whisperSpeechEngine.ensureBaseEnModelReady { pct ->
                                _oneClickProgressState.value = _oneClickProgressState.value.copy(
                                    currentStepProgressPercent = pct
                                )
                            }
                            res.getOrThrow()
                        }
                        "Speaker Diarization" -> {
                            val progressCollector = launch {
                                diarizationModelState.collect { st ->
                                    if (st is DiarizationModelState.Downloading) {
                                        _oneClickProgressState.value = _oneClickProgressState.value.copy(
                                            currentStepProgressPercent = st.progress
                                        )
                                    }
                                }
                            }
                            try {
                                diarizationModelManager.downloadModels().getOrThrow()
                            } finally {
                                progressCollector.cancel()
                            }
                        }
                        "ML Kit EN→বাংলা" -> {
                            _oneClickProgressState.value = _oneClickProgressState.value.copy(
                                currentStepProgressPercent = 25
                            )
                            offlineTranslationManager.downloadModel(requireWifi = false).getOrThrow()
                            _oneClickProgressState.value = _oneClickProgressState.value.copy(
                                currentStepProgressPercent = 100
                            )
                        }
                        "Bengali TTS" -> {
                            val progressCollector = launch {
                                bengaliTtsModelState.collect { st ->
                                    if (st is BengaliTtsModelState.Downloading) {
                                        _oneClickProgressState.value = _oneClickProgressState.value.copy(
                                            currentStepProgressPercent = st.progress
                                        )
                                    }
                                }
                            }
                            try {
                                bengaliTtsModelManager.downloadModels().getOrThrow()
                            } finally {
                                progressCollector.cancel()
                            }
                        }
                    }
                }

                _oneClickProgressState.value = OneClickProgressSnapshot(isRunning = false)
            } catch (ce: CancellationException) {
                _oneClickProgressState.value = OneClickProgressSnapshot(isRunning = false)
                throw ce
            } catch (t: Throwable) {
                _oneClickProgressState.value = OneClickProgressSnapshot(
                    isRunning = false,
                    errorMessage = t.message ?: "One-Click AI Setup encountered a download error."
                )
            }
        }
    }

    fun cancelOneClickAiSetup() {
        activeOneClickSetupJob?.cancel()
        activeOneClickSetupJob = null
        whisperSpeechEngine.cancelModelOperation()
        _oneClickProgressState.value = OneClickProgressSnapshot(isRunning = false)
        viewModelScope.launch {
            diarizationModelManager.checkModelStatus()
            offlineTranslationManager.checkModelStatus()
            bengaliTtsModelManager.checkModelStatus()
        }
    }

    private fun resolveDocumentDisplayName(uri: Uri): String? {
        return runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    override fun onCleared() {
        activeChunkWorkJob?.cancel()
        activeAutoDubJob?.cancel()
        activeExportJob?.cancel()
        activeOneClickSetupJob?.cancel()
        activeTranslationJob?.cancel()
        activeDiarizationModelJob?.cancel()
        activeDiarizationJob?.cancel()
        activeBengaliTtsModelJob?.cancel()
        activeBengaliVoiceGenJob?.cancel()
        activeCharacterNeuralPreviewJob?.cancel()
        dubbingPlaybackController.release()
        dubAudioPreviewPlayer.release()
        offlineBengaliTtsEngine.release()
        offlineDiarizationEngine.release()
        offlineTranslationEngine.close()
        whisperSpeechEngine.stopTranscription()
        ttsPreviewEngine.release()
        playerEngine.release()
        super.onCleared()
    }

    companion object {
        private const val LIVE_PREBUFFER_CHUNKS_AHEAD = 2
    }

    private data class OneClickProgressSnapshot(
        val isRunning: Boolean = false,
        val currentStepIndex: Int = 0,
        val totalSteps: Int = 0,
        val currentStepLabel: String = "",
        val currentStepProgressPercent: Int = 0,
        val errorMessage: String? = null
    )

    private data class DiarizationAndTtsPipelineSnapshot(
        val diarizationModelState: DiarizationModelState = DiarizationModelState.NotInstalled,
        val isDiarizing: Boolean = false,
        val bengaliTtsModelState: BengaliTtsModelState = BengaliTtsModelState.NotInstalled,
        val isSynthesizingBengaliVoice: Boolean = false,
        val hasSynthesizedBengaliTrack: Boolean = false,
        val isLiveDubbingActive: Boolean = false
    )

    private data class WhisperPipelineSnapshot(
        val isModelLoaded: Boolean = false,
        val isTranscribing: Boolean = false,
        val detail: String? = null,
        val diarizationModelState: DiarizationModelState = DiarizationModelState.NotInstalled,
        val isDiarizing: Boolean = false,
        val translationModelState: TranslationModelState = TranslationModelState.NotDownloaded,
        val isTranslating: Boolean = false,
        val bengaliTtsModelState: BengaliTtsModelState = BengaliTtsModelState.NotInstalled,
        val isSynthesizingBengaliVoice: Boolean = false,
        val hasSynthesizedBengaliTrack: Boolean = false,
        val isLiveDubbingActive: Boolean = false
    )

    private data class ExtractionUiSnapshot(
        val audioInfo: ExtractedAudioTrackInfo?,
        val isInspecting: Boolean,
        val isExtracting: Boolean,
        val message: String?,
        val whisper: WhisperPipelineSnapshot = WhisperPipelineSnapshot()
    )

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(
                app = app,
                mediaRepository = app.mediaRepository,
                historyRepository = app.historyRepository,
                settingsRepository = app.settingsRepository,
                batteryMonitor = app.batteryMonitor,
                aiDubbingPipeline = app.aiDubbingPipeline,
                videoAudioExtractor = app.videoAudioExtractor,
                dubbingPreferencesRepository = app.dubbingPreferencesRepository,
                whisperSpeechEngine = app.whisperSpeechEngine,
                offlineTranslationManager = app.offlineTranslationManager,
                offlineTranslationEngine = app.offlineTranslationEngine,
                translationCacheRepository = app.translationCacheRepository,
                diarizationModelManager = app.diarizationModelManager,
                offlineDiarizationEngine = app.offlineDiarizationEngine,
                bengaliTtsModelManager = app.bengaliTtsModelManager,
                offlineBengaliTtsEngine = app.offlineBengaliTtsEngine,
                dubbedTrackAssembler = app.dubbedTrackAssembler,
                autoDubPipelineOrchestrator = app.autoDubPipelineOrchestrator,
                dubbedVideoExporter = app.dubbedVideoExporter
            ) as T
        }
    }
}
