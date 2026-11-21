package com.voxora.player.ai.subtitles

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.voxora.player.dubbing.whisper.VideoPcmAudioDecoder
import com.voxora.player.dubbing.whisper.WhisperCppSpeechRecognitionEngine
import com.voxora.player.dubbing.whisper.WhisperModelCatalog
import com.voxora.player.model.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

data class SubtitleLanguageOption(
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    val searchKey: String
        get() = "$displayName $nativeName $code".lowercase(Locale.ROOT)
}

data class AutoSubtitleUiState(
    val isEnabled: Boolean = false,
    val isSetupRunning: Boolean = false,
    val setupProgressPercent: Int = 0,
    val setupStepLabel: String = "",
    val isWhisperModelReady: Boolean = false,
    val isTargetLanguagePackReady: Boolean = true,
    val targetLanguageCode: String = AutoSubtitleLanguageCatalog.CODE_ENGLISH,
    val targetLanguageLabel: String = "English",
    val isGeneratingChunk: Boolean = false,
    val activeChunkIndex: Int = -1,
    val completedChunksCount: Int = 0,
    val generatedCueCount: Int = 0,
    val statusMessage: String = "Off · Tap Enable for 100% free on-device subtitles",
    val errorMessage: String? = null
)

/**
 * Full 59+ language catalog powered by Google ML Kit On-Device Translation, plus
 * "Original Spoken Language" (Whisper multilingual auto-detection without translation).
 */
object AutoSubtitleLanguageCatalog {
    const val CODE_ORIGINAL = "original"
    const val CODE_ENGLISH = "en"

    private val priorityOrder = listOf(
        CODE_ORIGINAL,
        CODE_ENGLISH,
        "bn", "hi", "ur", "ar", "es", "fr", "de", "ja", "ko", "zh",
        "pt", "ru", "tr", "id", "ta", "te", "it", "vi", "th", "ms", "fa"
    )

    val allLanguages: List<SubtitleLanguageOption> by lazy {
        buildCatalog(runCatching { TranslateLanguage.getAllLanguages() }.getOrElse { fallbackLanguageCodes })
    }

    fun buildCatalog(mlKitCodes: Collection<String>): List<SubtitleLanguageOption> {
        val options = mutableListOf(
            SubtitleLanguageOption(
                code = CODE_ORIGINAL,
                displayName = "Original (Auto-Detect)",
                nativeName = "No Translation"
            )
        )
        val distinctCodes = mlKitCodes.distinct()
        for (code in distinctCodes) {
            val locale = Locale.forLanguageTag(code)
            val englishName = locale.getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                .ifBlank { code.uppercase(Locale.ROOT) }
            val nativeName = locale.getDisplayLanguage(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                .ifBlank { englishName }
            options.add(
                SubtitleLanguageOption(
                    code = code,
                    displayName = englishName,
                    nativeName = nativeName
                )
            )
        }
        return options.sortedWith(
            compareBy<SubtitleLanguageOption> { opt ->
                val idx = priorityOrder.indexOf(opt.code)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    fun findByCode(code: String): SubtitleLanguageOption {
        return allLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: SubtitleLanguageOption(CODE_ENGLISH, "English", "English")
    }

    fun filter(query: String): List<SubtitleLanguageOption> {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return allLanguages
        return allLanguages.filter { it.searchKey.contains(trimmed) }
    }

    private val fallbackLanguageCodes = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
        "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
        "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
        "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw",
        "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh"
    )
}

/**
 * Dedicated, lightweight, 100% free on-device Auto AI Subtitle engine for VOXORA v2.3.0.
 *
 * Pipeline:
 * 1. Multilingual speech recognition via `whisper.cpp` (`ggml-tiny-q5_1.bin`, ~31 MB) using
 *    rolling 25-second 16 kHz mono PCM windows ahead of playback.
 * 2. When target language is `"original"`, Whisper transcribes directly in the spoken language
 *    (`"auto"`). When target language is `"en"` or any of ML Kit's 59+ languages, Whisper
 *    translates any spoken language (Hindi, Korean, Japanese, Spanish, French, etc.) into English
 *    (`"auto:translate"`) and Google ML Kit On-Device Translation translates English into the
 *    selected target language completely offline.
 * 3. Frame-synced cue lookup matches exact `[startTimeMs, endTimeMs]` timestamps and hides
 *    immediately during silence.
 * 4. Battery-safe: immediately aborts native Whisper inference and cancels background coroutines
 *    when playback is paused or the app goes to the background.
 */
class AutoSubtitleManager(
    context: Context,
    private val whisperSpeechEngine: WhisperCppSpeechRecognitionEngine,
    private val videoPcmAudioDecoder: VideoPcmAudioDecoder = VideoPcmAudioDecoder(context.applicationContext)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cuesLock = Any()
    private val translatorMutex = Mutex()

    private val _state = MutableStateFlow(AutoSubtitleUiState())
    val state: StateFlow<AutoSubtitleUiState> = _state.asStateFlow()

    private val cuesByChunk = linkedMapOf<Int, List<SubtitleCue>>()
    @Volatile
    private var sortedCuesSnapshot: List<SubtitleCue> = emptyList()

    private val completedChunks = mutableSetOf<Int>()
    @Volatile
    private var activeMediaUri: String = ""
    @Volatile
    private var activeMediaDurationMs: Long = 0L
    @Volatile
    private var currentChunkBeingProcessed: Int = -1
    @Volatile
    private var lastPlaybackPositionMs: Long = 0L
    @Volatile
    private var isPlaybackActive: Boolean = false

    private var setupJob: Job? = null
    private var workerJob: Job? = null

    private var activeTranslator: Translator? = null
    private var activeTranslatorTargetCode: String? = null

    fun attachMedia(mediaUriString: String, durationMs: Long, preferredTargetLanguageCode: String) {
        val targetOpt = AutoSubtitleLanguageCatalog.findByCode(preferredTargetLanguageCode)
        val uriChanged = activeMediaUri != mediaUriString
        activeMediaUri = mediaUriString
        if (durationMs > 0L) {
            activeMediaDurationMs = durationMs
        }
        if (uriChanged) {
            pauseProcessing()
            clearGeneratedCues()
            _state.value = AutoSubtitleUiState(
                isEnabled = false,
                isWhisperModelReady = whisperSpeechEngine.isModelInstalledOnDisk(),
                targetLanguageCode = targetOpt.code,
                targetLanguageLabel = targetOpt.displayName,
                statusMessage = "Off · Tap Enable for free on-device AI subtitles"
            )
            refreshReadinessAsync(targetOpt.code)
        } else {
            updateTargetLanguageInternal(targetOpt.code, clearExisting = false)
        }
    }

    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) {
            activeMediaDurationMs = durationMs
        }
    }

    fun refreshReadinessAsync(targetCode: String = _state.value.targetLanguageCode) {
        scope.launch {
            val whisperReady = whisperSpeechEngine.isModelInstalledOnDisk()
            val mlKitReady = isTargetMlKitModelReady(targetCode)
            _state.value = _state.value.copy(
                isWhisperModelReady = whisperReady,
                isTargetLanguagePackReady = mlKitReady
            )
        }
    }

    fun setTargetLanguage(targetCode: String) {
        updateTargetLanguageInternal(targetCode, clearExisting = true)
    }

    private fun updateTargetLanguageInternal(targetCode: String, clearExisting: Boolean) {
        val opt = AutoSubtitleLanguageCatalog.findByCode(targetCode)
        val previous = _state.value.targetLanguageCode
        val changed = !previous.equals(opt.code, ignoreCase = true)
        if (!changed) return

        val wasEnabled = _state.value.isEnabled
        if (clearExisting) {
            pauseProcessing()
            clearGeneratedCues()
        }
        _state.value = _state.value.copy(
            targetLanguageCode = opt.code,
            targetLanguageLabel = opt.displayName,
            errorMessage = null,
            statusMessage = if (wasEnabled) {
                "Switching subtitles to ${opt.displayName}..."
            } else {
                "Target: ${opt.displayName} · Ready when enabled"
            }
        )
        refreshReadinessAsync(opt.code)
        if (wasEnabled && activeMediaUri.isNotBlank()) {
            enableAndPrepare(lastPlaybackPositionMs, isPlaybackActive)
        }
    }

    /**
     * One-Click Setup & Activation: downloads `ggml-tiny-q5_1.bin` (~31 MB) if missing,
     * downloads the selected ML Kit language pack if needed, and starts rolling 25s
     * subtitle generation synced to playback.
     */
    fun enableAndPrepare(currentPositionMs: Long, isPlayingNow: Boolean) {
        if (activeMediaUri.isBlank()) {
            _state.value = _state.value.copy(
                errorMessage = "Load a video before enabling Auto AI Subtitles."
            )
            return
        }
        lastPlaybackPositionMs = currentPositionMs.coerceAtLeast(0L)
        isPlaybackActive = isPlayingNow

        setupJob?.cancel()
        setupJob = scope.launch {
            val targetCode = _state.value.targetLanguageCode
            val targetLabel = _state.value.targetLanguageLabel
            _state.value = _state.value.copy(
                isEnabled = true,
                isSetupRunning = true,
                setupProgressPercent = 0,
                setupStepLabel = "Checking Whisper multilingual model...",
                errorMessage = null,
                statusMessage = "Preparing Auto AI Subtitles ($targetLabel)..."
            )
            try {
                // Step 1: Ensure Whisper multilingual tiny quantized model (~31 MB) is downloaded & loaded
                val whisperRes = whisperSpeechEngine.ensureBaseEnModelReady { pct ->
                    val scaled = if (needsMlKitTranslation(targetCode)) (pct * 0.70f).toInt() else pct
                    _state.value = _state.value.copy(
                        setupProgressPercent = scaled.coerceIn(0, 95),
                        setupStepLabel = if (pct < 90) {
                            "Downloading ${WhisperModelCatalog.DEFAULT.fileName} ($pct%)..."
                        } else {
                            "Loading Whisper multilingual model..."
                        }
                    )
                }
                whisperRes.getOrThrow()

                _state.value = _state.value.copy(isWhisperModelReady = true)

                // Step 2: Download ML Kit on-device translation pack when translating beyond English
                if (needsMlKitTranslation(targetCode)) {
                    _state.value = _state.value.copy(
                        setupProgressPercent = 75,
                        setupStepLabel = "Downloading $targetLabel offline language pack..."
                    )
                    ensureMlKitLanguagePackDownloaded(targetCode).getOrThrow()
                }

                _state.value = _state.value.copy(
                    isSetupRunning = false,
                    setupProgressPercent = 100,
                    setupStepLabel = "",
                    isTargetLanguagePackReady = true,
                    statusMessage = if (isPlaybackActive) {
                        "Live Auto Subtitles ($targetLabel) · Generating..."
                    } else {
                        "Auto Subtitles Ready ($targetLabel) · Play video to start"
                    },
                    errorMessage = null
                )

                if (isPlaybackActive) {
                    scheduleRollingGeneration(lastPlaybackPositionMs)
                }
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(isSetupRunning = false, setupStepLabel = "")
                throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isEnabled = false,
                    isSetupRunning = false,
                    setupStepLabel = "",
                    errorMessage = t.message ?: "Auto AI Subtitle setup failed.",
                    statusMessage = "Setup failed · Tap retry"
                )
            }
        }
    }

    fun disable() {
        setupJob?.cancel()
        setupJob = null
        pauseProcessing()
        _state.value = _state.value.copy(
            isEnabled = false,
            isSetupRunning = false,
            isGeneratingChunk = false,
            activeChunkIndex = -1,
            setupStepLabel = "",
            errorMessage = null,
            statusMessage = "Off"
        )
    }

    /**
     * Called during playback polling (~80ms when playing) or play/pause transitions.
     * Immediately halts background Whisper work when paused to save battery.
     */
    fun onPlaybackStateUpdated(positionMs: Long, isPlaying: Boolean) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        val wasPlaying = isPlaybackActive
        isPlaybackActive = isPlaying

        if (!_state.value.isEnabled || _state.value.isSetupRunning) return

        if (!isPlaying) {
            if (wasPlaying || workerJob?.isActive == true) {
                pauseProcessing()
                _state.value = _state.value.copy(
                    isGeneratingChunk = false,
                    statusMessage = "Paused · Auto Subtitles standing by (0% CPU)"
                )
            }
            return
        }

        val currentIdx = chunkIndexForPositionMs(lastPlaybackPositionMs)
        val needsWork = !completedChunks.contains(currentIdx) ||
            (!completedChunks.contains(currentIdx + 1) && isChunkWithinDuration(currentIdx + 1))

        if (needsWork && (workerJob == null || workerJob?.isActive != true)) {
            scheduleRollingGeneration(lastPlaybackPositionMs)
        }
    }

    /**
     * Called when the user seeks to a new timestamp. Cancels far-away chunk inference so
     * subtitles at the new seek target appear as quickly as possible.
     */
    fun onSeekTo(positionMs: Long) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        if (!_state.value.isEnabled || _state.value.isSetupRunning) return

        val targetIdx = chunkIndexForPositionMs(lastPlaybackPositionMs)
        val activeIdx = currentChunkBeingProcessed
        if (activeIdx >= 0 && abs(targetIdx - activeIdx) > 1) {
            pauseProcessing()
        }
        if (isPlaybackActive) {
            scheduleRollingGeneration(lastPlaybackPositionMs)
        }
    }

    /**
     * Immediately stops background chunk decoding & aborts native Whisper inference
     * whenever the player pauses or goes to the background (battery & thermal protection).
     */
    fun pauseProcessing() {
        whisperSpeechEngine.requestAbortNativeInference()
        workerJob?.cancel()
        workerJob = null
        currentChunkBeingProcessed = -1
        if (_state.value.isGeneratingChunk) {
            _state.value = _state.value.copy(
                isGeneratingChunk = false,
                activeChunkIndex = -1
            )
        }
    }

    private fun scheduleRollingGeneration(anchorPositionMs: Long) {
        val uri = activeMediaUri
        if (uri.isBlank() || !_state.value.isEnabled || !isPlaybackActive) return
        if (workerJob?.isActive == true) return

        workerJob = scope.launch {
            try {
                while (_state.value.isEnabled && isPlaybackActive) {
                    ensureActive()
                    val currentIdx = chunkIndexForPositionMs(lastPlaybackPositionMs.coerceAtLeast(anchorPositionMs))
                    val candidateIndices = listOf(currentIdx, currentIdx + 1)
                        .filter { it >= 0 && !completedChunks.contains(it) && isChunkWithinDuration(it) }

                    val nextChunkIdx = candidateIndices.firstOrNull() ?: break
                    processSingleChunk(uri, nextChunkIdx)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isGeneratingChunk = false,
                    activeChunkIndex = -1,
                    errorMessage = t.message ?: "Auto subtitle generation paused due to an error."
                )
            } finally {
                currentChunkBeingProcessed = -1
                if (_state.value.isGeneratingChunk) {
                    val label = _state.value.targetLanguageLabel
                    _state.value = _state.value.copy(
                        isGeneratingChunk = false,
                        activeChunkIndex = -1,
                        statusMessage = if (_state.value.isEnabled) {
                            "Live Auto Subtitles ($label) · ${_state.value.generatedCueCount} lines synced"
                        } else {
                            "Off"
                        }
                    )
                }
            }
        }
    }

    private suspend fun processSingleChunk(mediaUriString: String, chunkIndex: Int) {
        val startMs = chunkIndex * CHUNK_DURATION_MS
        val targetCode = _state.value.targetLanguageCode
        val targetLabel = _state.value.targetLanguageLabel

        currentChunkBeingProcessed = chunkIndex
        _state.value = _state.value.copy(
            isGeneratingChunk = true,
            activeChunkIndex = chunkIndex,
            errorMessage = null,
            statusMessage = "Listening & syncing ($targetLabel · Chunk ${chunkIndex + 1})..."
        )

        val pcmChunk = videoPcmAudioDecoder.decodeWindowTo16kPcm(
            mediaUriString = mediaUriString,
            startMs = startMs,
            windowDurationMs = CHUNK_DURATION_MS,
            chunkIndex = chunkIndex
        ).getOrThrow()

        if (pcmChunk.samples16kMono.isEmpty()) {
            completedChunks.add(chunkIndex)
            return
        }

        val whisperLangParam = if (targetCode == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) {
            "auto"
        } else {
            "auto:translate"
        }

        val segments = whisperSpeechEngine.transcribePcmChunkSuspending(
            chunk = pcmChunk,
            language = whisperLangParam
        ).getOrThrow()

        val generatedCues = mutableListOf<SubtitleCue>()
        val translator = if (needsMlKitTranslation(targetCode)) {
            getOrCreateTranslator(targetCode)
        } else {
            null
        }

        for (seg in segments) {
            coroutineContext.ensureActive()
            val cleaned = cleanWhisperText(seg.originalText)
            if (cleaned.isEmpty()) continue
            val finalText = if (translator != null) {
                runCatching { translator.translate(cleaned).await().trim() }
                    .getOrDefault(cleaned)
                    .ifBlank { cleaned }
            } else {
                cleaned
            }
            val cueStart = seg.startTimeMs.coerceAtLeast(startMs)
            val cueEnd = seg.endTimeMs.coerceAtLeast(cueStart + 450L)
            generatedCues.add(
                SubtitleCue(
                    startTimeMs = cueStart,
                    endTimeMs = cueEnd,
                    text = finalText
                )
            )
        }

        synchronized(cuesLock) {
            cuesByChunk[chunkIndex] = generatedCues
            sortedCuesSnapshot = cuesByChunk.values
                .flatten()
                .sortedBy { it.startTimeMs }
        }
        completedChunks.add(chunkIndex)

        _state.value = _state.value.copy(
            completedChunksCount = completedChunks.size,
            generatedCueCount = sortedCuesSnapshot.size,
            statusMessage = "Live Auto Subtitles ($targetLabel) · ${sortedCuesSnapshot.size} lines synced"
        )
    }

    /**
     * Frame-synced subtitle lookup. Returns the subtitle text strictly when [playbackPositionMs]
     * lies inside `[startTimeMs, endTimeMs]`, and `null` immediately during silence.
     */
    fun findActiveCueText(playbackPositionMs: Long, syncOffsetMs: Long = 0L): String? {
        if (!_state.value.isEnabled) return null
        return findExactCueForPosition(sortedCuesSnapshot, playbackPositionMs, syncOffsetMs)
    }

    private fun clearGeneratedCues() {
        synchronized(cuesLock) {
            cuesByChunk.clear()
            sortedCuesSnapshot = emptyList()
        }
        completedChunks.clear()
    }

    private fun isChunkWithinDuration(chunkIndex: Int): Boolean {
        val dur = activeMediaDurationMs
        if (dur <= 0L) return chunkIndex < 120
        return (chunkIndex * CHUNK_DURATION_MS) < dur
    }

    private suspend fun isTargetMlKitModelReady(targetCode: String): Boolean = withContext(Dispatchers.IO) {
        if (!needsMlKitTranslation(targetCode)) return@withContext true
        runCatching {
            val mgr = RemoteModelManager.getInstance()
            val targetModel = TranslateRemoteModel.Builder(targetCode).build()
            mgr.isModelDownloaded(targetModel).await()
        }.getOrDefault(false)
    }

    private suspend fun ensureMlKitLanguagePackDownloaded(targetCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mgr = RemoteModelManager.getInstance()
                val conditions = DownloadConditions.Builder().build()
                val englishModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
                val englishReady = runCatching { mgr.isModelDownloaded(englishModel).await() }.getOrDefault(true)
                if (!englishReady) {
                    mgr.download(englishModel, conditions).await()
                }
                val targetModel = TranslateRemoteModel.Builder(targetCode).build()
                val targetReady = runCatching { mgr.isModelDownloaded(targetModel).await() }.getOrDefault(false)
                if (!targetReady) {
                    mgr.download(targetModel, conditions).await()
                }
            }
        }

    private suspend fun getOrCreateTranslator(targetCode: String): Translator = translatorMutex.withLock {
        val existing = activeTranslator
        if (existing != null && activeTranslatorTargetCode == targetCode) {
            return@withLock existing
        }
        runCatching { existing?.close() }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetCode)
            .build()
        val created = Translation.getClient(options)
        activeTranslator = created
        activeTranslatorTargetCode = targetCode
        created
    }

    fun release() {
        setupJob?.cancel()
        pauseProcessing()
        clearGeneratedCues()
        runCatching { activeTranslator?.close() }
        activeTranslator = null
        activeTranslatorTargetCode = null
    }

    companion object {
        const val CHUNK_DURATION_MS: Long = 25_000L

        fun needsMlKitTranslation(targetCode: String): Boolean {
            return targetCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL &&
                !targetCode.equals(AutoSubtitleLanguageCatalog.CODE_ENGLISH, ignoreCase = true)
        }

        fun chunkIndexForPositionMs(positionMs: Long): Int {
            return (positionMs.coerceAtLeast(0L) / CHUNK_DURATION_MS).toInt()
        }

        /**
         * Strips Whisper non-speech tokens (e.g. `[MUSIC]`, `(applause)`, `[BLANK_AUDIO]`)
         * so silence/music stays clean and never flashes noisy artifacts.
         */
        fun cleanWhisperText(raw: String): String {
            val stripped = raw
                .replace(Regex("\\[(?:BLANK_AUDIO|MUSIC|APPLAUSE|LAUGHTER|SILENCE|INAUDIBLE|SOUND|NOISE)[^\\]]*]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\((?:music|applause|laughter|silence|sighs|groans)[^)]*\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (stripped.length <= 1 && !stripped.any { it.isLetterOrDigit() }) "" else stripped
        }

        /**
         * Exact binary-search / bounded lookup for frame-accurate subtitle display.
         * Returns `null` immediately outside `[startTimeMs, endTimeMs]`.
         */
        fun findExactCueForPosition(
            cues: List<SubtitleCue>,
            playbackPositionMs: Long,
            syncOffsetMs: Long = 0L
        ): String? {
            if (cues.isEmpty()) return null
            val adjusted = playbackPositionMs - syncOffsetMs
            if (adjusted < 0L) return null

            var low = 0
            var high = cues.lastIndex
            var candidateIdx = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cues[mid].startTimeMs <= adjusted) {
                    candidateIdx = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            if (candidateIdx < 0) return null

            // Check candidate and immediate predecessor for overlapping cue spans
            val activeLines = mutableListOf<String>()
            val startCheck = (candidateIdx - 1).coerceAtLeast(0)
            for (i in startCheck..candidateIdx) {
                val cue = cues[i]
                if (adjusted in cue.startTimeMs..cue.endTimeMs && cue.text.isNotBlank()) {
                    activeLines.add(cue.text)
                }
            }
            return activeLines.distinct().joinToString("\n").ifBlank { null }
        }
    }
}
