package com.voxora.player.ai.dubbing

import com.voxora.player.ai.diarization.DiarizationModelManager
import com.voxora.player.ai.diarization.DiarizationModelState
import com.voxora.player.ai.diarization.OfflineDiarizationEngine
import com.voxora.player.ai.diarization.SpeakerCharacterMapper
import com.voxora.player.ai.diarization.SpeakerSegment
import com.voxora.player.ai.translation.OfflineTranslationEngine
import com.voxora.player.ai.translation.OfflineTranslationManager
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.ai.translation.TranslationModelState
import com.voxora.player.ai.tts.BengaliTtsModelManager
import com.voxora.player.ai.tts.BengaliTtsModelState
import com.voxora.player.ai.tts.DubbedAudioSegment
import com.voxora.player.ai.tts.OfflineBengaliTtsEngine
import com.voxora.player.data.repository.TranslationCacheRepository
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.DubbingPreferencesRepository
import com.voxora.player.dubbing.MovieSpeakerAssignment
import com.voxora.player.dubbing.RecognizedSpeechSegment
import com.voxora.player.dubbing.whisper.WhisperCppSpeechRecognitionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Full output bundle produced by a completed One-Click Auto-Dub run so ViewModel can
 * hydrate all studio tabs (Transcribe, Speakers, Translate, Voice/TTS) and activate live playback.
 */
data class AutoDubCompletionResult(
    val transcriptSegments: List<RecognizedSpeechSegment>,
    val speakerSegments: List<SpeakerSegment>,
    val speakerToCharacterMap: Map<Int, String>,
    val segmentCharacterMap: Map<Int, String>,
    val autoCreatedCharacterCount: Int,
    val translatedSegments: List<TranslatedSegment>,
    val dubbedAudioSegments: List<DubbedAudioSegment>,
    val assembledTrack: AssembledDubTrack
)

/**
 * One-Click "Auto-Dub Entire Video" Offline Orchestrator (English -> বাংলা).
 *
 * Runs the complete 4-stage offline AI chain sequentially on [Dispatchers.IO]:
 * 1. Audio Extraction & English Speech Recognition (`whisper.cpp`) -> frees Whisper native RAM
 * 2. Speaker Diarization & Character Auto-Mapping (`Sherpa-ONNX`) -> frees Diarization native RAM
 * 3. Offline English-to-Bengali Translation (`ML Kit`) -> persists cache to Room DB
 * 4. Offline Bengali Voice Synthesis (`Sherpa-ONNX VITS`) -> frees VITS native RAM -> assembles timeline WAV
 *
 * Strictly honest: verifies all required offline models exist before starting and never produces
 * fake transcripts, speakers, translations, or silent placeholder audio.
 */
class AutoDubPipelineOrchestrator(
    private val whisperSpeechEngine: WhisperCppSpeechRecognitionEngine,
    private val diarizationModelManager: DiarizationModelManager,
    private val offlineDiarizationEngine: OfflineDiarizationEngine,
    private val offlineTranslationManager: OfflineTranslationManager,
    private val offlineTranslationEngine: OfflineTranslationEngine,
    private val translationCacheRepository: TranslationCacheRepository,
    private val bengaliTtsModelManager: BengaliTtsModelManager,
    private val offlineBengaliTtsEngine: OfflineBengaliTtsEngine,
    private val dubbingPreferencesRepository: DubbingPreferencesRepository,
    private val trackAssembler: DubbedTrackAssembler
) {
    /**
     * Checks which of the 4 offline AI models are currently missing on-device.
     */
    suspend fun findMissingModels(): List<String> = withContext(Dispatchers.IO) {
        val missing = mutableListOf<String>()
        if (!whisperSpeechEngine.isModelInstalled) {
            missing.add("Whisper ASR (base.en)")
        }
        if (diarizationModelManager.checkModelStatus() !is DiarizationModelState.Ready) {
            missing.add("Sherpa-ONNX Speaker Diarization")
        }
        if (offlineTranslationManager.checkModelStatus() !is TranslationModelState.Ready) {
            missing.add("ML Kit EN→বাংলা Translation")
        }
        if (bengaliTtsModelManager.checkModelStatus() !is BengaliTtsModelState.Ready) {
            missing.add("Sherpa-ONNX VITS Bengali TTS")
        }
        missing
    }

    /**
     * Requests cancellation of whichever native engine is currently executing.
     */
    fun requestCancelActiveStage() {
        whisperSpeechEngine.stopTranscription()
        offlineDiarizationEngine.requestCancel()
        offlineBengaliTtsEngine.requestCancel()
    }

    /**
     * Executes the complete offline 4-stage Auto-Dub pipeline for [videoUri].
     */
    suspend fun runAutoDub(
        videoUri: String,
        videoTitle: String,
        videoDurationMsHint: Long,
        savedCharacters: List<CharacterVoiceProfile>,
        existingMovieAssignments: List<MovieSpeakerAssignment>,
        existingManualOverrides: Map<Int, String>,
        diarizationNumClusters: Int = -1,
        diarizationThreshold: Float = 0.50f,
        onStateUpdate: (AutoDubPipelineState) -> Unit,
        onTranslatedSegmentsReady: (List<TranslatedSegment>) -> Unit = {},
        onDubbedSegmentSynthesized: (List<DubbedAudioSegment>) -> Unit = {}
    ): Result<AutoDubCompletionResult> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No local video is currently loaded."))
        }

        val missing = findMissingModels()
        if (missing.isNotEmpty()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Missing offline AI model(s): ${missing.joinToString(", ")}. Tap \"Download All Missing Models\" in One-Click AI Setup above first."
                )
            )
        }

        try {
            // =========================================================================
            // STAGE 1 / 4: Audio Extraction & English Speech Recognition (whisper.cpp)
            // =========================================================================
            coroutineContext.ensureActive()
            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = true,
                    currentStage = AutoDubStage.TRANSCRIBING,
                    stageIndex = 1,
                    totalStages = 4,
                    stageLabel = "Stage 1/4: Loading Whisper ASR & transcribing English...",
                    stageProgressFraction = 0.05f
                )
            )

            whisperSpeechEngine.ensureInstalledModelLoaded().getOrThrow()

            val transcriptSegments: List<RecognizedSpeechSegment> = coroutineScope {
                val progressJob = launch {
                    whisperSpeechEngine.transcriptionState.collect { st ->
                        if (st.isRunning) {
                            onStateUpdate(
                                AutoDubPipelineState(
                                    isRunning = true,
                                    currentStage = AutoDubStage.TRANSCRIBING,
                                    stageIndex = 1,
                                    totalStages = 4,
                                    stageLabel = "Stage 1/4: ${st.phaseLabel}",
                                    stageProgressFraction = st.progressFraction.coerceIn(0.05f, 0.98f)
                                )
                            )
                        }
                    }
                }
                try {
                    whisperSpeechEngine.transcribeVideoSuspending(
                        mediaUriString = videoUri,
                        videoTitle = videoTitle,
                        totalDurationMsHint = videoDurationMsHint
                    ).getOrThrow()
                } finally {
                    progressJob.cancel()
                    // Free Whisper native RAM (~150 MB) before launching Sherpa-ONNX to prevent OOM
                    whisperSpeechEngine.unloadNativeContextSuspending()
                }
            }

            if (transcriptSegments.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No English speech was detected in this video's audio track.")
                )
            }

            // =========================================================================
            // STAGE 2 / 4: Speaker Diarization & Character Auto-Mapping (Sherpa-ONNX)
            // =========================================================================
            coroutineContext.ensureActive()
            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = true,
                    currentStage = AutoDubStage.DIARIZING,
                    stageIndex = 2,
                    totalStages = 4,
                    stageLabel = "Stage 2/4: Detecting speakers & mapping characters...",
                    stageProgressFraction = 0.05f
                )
            )

            val speakerSegments: List<SpeakerSegment> = try {
                offlineDiarizationEngine.diarizeVideo(
                    mediaUriString = videoUri,
                    numClusters = diarizationNumClusters,
                    threshold = diarizationThreshold,
                    onProgress = { frac, label ->
                        onStateUpdate(
                            AutoDubPipelineState(
                                isRunning = true,
                                currentStage = AutoDubStage.DIARIZING,
                                stageIndex = 2,
                                totalStages = 4,
                                stageLabel = "Stage 2/4: $label",
                                stageProgressFraction = frac.coerceIn(0.05f, 0.98f)
                            )
                        )
                    }
                ).getOrThrow()
            } finally {
                // Free Sherpa-ONNX Diarization native memory before ML Kit & VITS TTS
                offlineDiarizationEngine.release()
            }

            val mapping = SpeakerCharacterMapper.mapSpeakersToCharacters(
                speakerSegments = speakerSegments,
                dialogueIntervals = transcriptSegments.map { it.startTimeMs to it.endTimeMs },
                savedCharacters = savedCharacters,
                existingMovieAssignments = existingMovieAssignments,
                existingManualOverrides = existingManualOverrides
            )

            if (mapping.newlyCreatedCharacterIds.isNotEmpty()) {
                dubbingPreferencesRepository.saveCharacters(mapping.updatedCharacters)
            }
            if (mapping.movieAssignments.isNotEmpty()) {
                dubbingPreferencesRepository.saveMovieAssignments(videoUri, mapping.movieAssignments)
            }
            if (mapping.segmentIndexToCharacterId.isNotEmpty()) {
                whisperSpeechEngine.applySpeakerAssignments(mapping.segmentIndexToCharacterId)
            }

            // =========================================================================
            // STAGE 3 / 4: Offline English-to-Bengali Translation (ML Kit)
            // =========================================================================
            coroutineContext.ensureActive()
            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = true,
                    currentStage = AutoDubStage.TRANSLATING,
                    stageIndex = 3,
                    totalStages = 4,
                    stageLabel = "Stage 3/4: Translating ${transcriptSegments.size} lines to বাংলা...",
                    stageProgressFraction = 0.05f
                )
            )

            val translatedRaw = offlineTranslationEngine.translateSegments(
                segments = transcriptSegments,
                onProgress = { completed, total ->
                    val frac = if (total > 0) completed.toFloat() / total.toFloat() else 0.5f
                    onStateUpdate(
                        AutoDubPipelineState(
                            isRunning = true,
                            currentStage = AutoDubStage.TRANSLATING,
                            stageIndex = 3,
                            totalStages = 4,
                            stageLabel = "Stage 3/4: Translating to Bengali ($completed/$total)...",
                            stageProgressFraction = frac.coerceIn(0.05f, 0.98f)
                        )
                    )
                }
            ).getOrThrow()

            val translatedWithCharacters = if (mapping.segmentIndexToCharacterId.isNotEmpty()) {
                SpeakerCharacterMapper.applyCharactersToTranslatedSegments(
                    segments = translatedRaw,
                    segmentIndexToCharacterId = mapping.segmentIndexToCharacterId
                )
            } else {
                translatedRaw
            }

            runCatching {
                translationCacheRepository.saveTranslation(
                    videoUri = videoUri,
                    segments = translatedWithCharacters
                )
            }
            onTranslatedSegmentsReady(translatedWithCharacters)

            // =========================================================================
            // STAGE 4 / 4: Offline Bengali Voice Synthesis (Sherpa-ONNX VITS) + Track Assembly
            // =========================================================================
            coroutineContext.ensureActive()
            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = true,
                    currentStage = AutoDubStage.SYNTHESIZING_BENGALI,
                    stageIndex = 4,
                    totalStages = 4,
                    stageLabel = "Stage 4/4: Synthesizing Bengali character voices (VITS)...",
                    stageProgressFraction = 0.05f
                )
            )

            val accumulatedDubbed = mutableListOf<DubbedAudioSegment>()
            val dubbedSegments: List<DubbedAudioSegment> = try {
                offlineBengaliTtsEngine.synthesizeSegments(
                    videoUri = videoUri,
                    translatedSegments = translatedWithCharacters,
                    characters = mapping.updatedCharacters,
                    segmentCharacterOverrides = mapping.segmentIndexToCharacterId,
                    onSegmentSynthesized = { completed, total, dubbed ->
                        accumulatedDubbed.add(dubbed)
                        onDubbedSegmentSynthesized(accumulatedDubbed.toList())
                        val frac = if (total > 0) (completed.toFloat() / total.toFloat()) * 0.85f else 0.5f
                        onStateUpdate(
                            AutoDubPipelineState(
                                isRunning = true,
                                currentStage = AutoDubStage.SYNTHESIZING_BENGALI,
                                stageIndex = 4,
                                totalStages = 4,
                                stageLabel = "Stage 4/4: Synthesizing Bengali voice ($completed/$total)...",
                                stageProgressFraction = frac.coerceIn(0.05f, 0.88f)
                            )
                        )
                    }
                ).getOrThrow()
            } finally {
                // Free VITS native model memory once WAV files are written to cache
                offlineBengaliTtsEngine.release()
            }

            // Assemble continuous timeline WAV track for instant synchronized playback
            coroutineContext.ensureActive()
            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = true,
                    currentStage = AutoDubStage.ASSEMBLING_LIVE_TRACK,
                    stageIndex = 4,
                    totalStages = 4,
                    stageLabel = "Stage 4/4: Assembling synchronized Bengali dub track...",
                    stageProgressFraction = 0.90f
                )
            )

            val assembledTrack = trackAssembler.assembleTimelineWav(
                videoUri = videoUri,
                segments = dubbedSegments,
                totalDurationMsHint = videoDurationMsHint,
                syncOffsetMs = 0L,
                targetSampleRate = DubbedTrackAssembler.DEFAULT_LIVE_SAMPLE_RATE,
                onProgress = { p ->
                    onStateUpdate(
                        AutoDubPipelineState(
                            isRunning = true,
                            currentStage = AutoDubStage.ASSEMBLING_LIVE_TRACK,
                            stageIndex = 4,
                            totalStages = 4,
                            stageLabel = "Stage 4/4: Assembling synchronized Bengali dub track...",
                            stageProgressFraction = (0.90f + p * 0.09f).coerceIn(0.90f, 0.99f)
                        )
                    )
                }
            ).getOrThrow()

            onStateUpdate(
                AutoDubPipelineState(
                    isRunning = false,
                    currentStage = AutoDubStage.COMPLETED,
                    stageIndex = 4,
                    totalStages = 4,
                    stageLabel = "Auto-Dub complete! Live Bengali dubbing is now active.",
                    stageProgressFraction = 1f,
                    errorMessage = null
                )
            )

            Result.success(
                AutoDubCompletionResult(
                    transcriptSegments = transcriptSegments,
                    speakerSegments = speakerSegments,
                    speakerToCharacterMap = mapping.speakerToCharacterMap,
                    segmentCharacterMap = mapping.segmentIndexToCharacterId,
                    autoCreatedCharacterCount = mapping.newlyCreatedCharacterIds.size,
                    translatedSegments = translatedWithCharacters,
                    dubbedAudioSegments = dubbedSegments,
                    assembledTrack = assembledTrack
                )
            )
        } catch (ce: CancellationException) {
            requestCancelActiveStage()
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
