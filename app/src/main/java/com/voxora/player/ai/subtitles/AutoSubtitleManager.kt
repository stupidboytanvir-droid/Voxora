package com.voxora.player.ai.subtitles

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.voxora.player.data.local.subtitles.SubtitleParser
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
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

data class SubtitleLanguageOption(
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    val searchKey: String
        get() = "$displayName $nativeName $code".lowercase(Locale.ROOT)
}

data class DualActiveSubtitle(
    val primaryText: String? = null,
    val secondaryText: String? = null
)

data class DialogueSearchResult(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val primaryText: String,
    val secondaryText: String? = null
)

data class AutoSubtitleUiState(
    val isEnabled: Boolean = false,
    val isSetupRunning: Boolean = false,
    val setupProgressPercent: Int = 0,
    val setupStepLabel: String = "",
    val isWhisperModelReady: Boolean = false,
    val isTargetLanguagePackReady: Boolean = true,
    val targetLanguageCode: String = AutoSubtitleLanguageCatalog.CODE_ENGLISH,
    val targetLanguageLabel: String = "English",
    val dualSubtitlesEnabled: Boolean = false,
    val secondaryLanguageCode: String = "bn",
    val secondaryLanguageLabel: String = "Bengali",
    val isGeneratingChunk: Boolean = false,
    val activeChunkIndex: Int = -1,
    val completedChunksCount: Int = 0,
    val generatedCueCount: Int = 0,
    val loadedFromSavedSrt: Boolean = false,
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
 * Dedicated, lightweight, 100% free on-device Auto AI Subtitle engine for VOXORA v2.4.0.
 *
 * Enhancements in v2.4.0:
 * - RMS Energy / VAD Trimming on each 16kHz PCM segment slice so subtitles NEVER appear early
 *   during 5-7s silent intros/gaps and vanish cleanly when speech ends.
 * - Strict zero-lookahead lookup (`currentPosMs in startTimeMs..endTimeMs`).
 * - Dual Subtitles: renders Primary + Secondary language simultaneously.
 * - Export to `.SRT` (`Documents/VOXORA/Subtitles/`) + automatic reload on next playback.
 * - Dialogue Search: instant keyword search across generated subtitles with timestamp jump.
 */
class AutoSubtitleManager(
    context: Context,
    private val whisperSpeechEngine: WhisperCppSpeechRecognitionEngine,
    private val videoPcmAudioDecoder: VideoPcmAudioDecoder = VideoPcmAudioDecoder(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cuesLock = Any()
    private val translatorMutex = Mutex()

    private val _state = MutableStateFlow(AutoSubtitleUiState())
    val state: StateFlow<AutoSubtitleUiState> = _state.asStateFlow()

    private val primaryCuesByChunk = linkedMapOf<Int, List<SubtitleCue>>()
    private val secondaryCuesByChunk = linkedMapOf<Int, List<SubtitleCue>>()
    @Volatile
    private var sortedPrimaryCues: List<SubtitleCue> = emptyList()
    @Volatile
    private var sortedSecondaryCues: List<SubtitleCue> = emptyList()

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

    private val activeTranslators = linkedMapOf<String, Translator>()

    private val savedSrtCacheDir: File
        get() = File(appContext.filesDir, "saved_ai_subtitles").apply { mkdirs() }

    fun attachMedia(
        mediaUriString: String,
        durationMs: Long,
        preferredTargetLanguageCode: String,
        dualEnabled: Boolean = false,
        secondaryLanguageCode: String = "bn"
    ) {
        val targetOpt = AutoSubtitleLanguageCatalog.findByCode(preferredTargetLanguageCode)
        val secondaryOpt = AutoSubtitleLanguageCatalog.findByCode(secondaryLanguageCode)
        val uriChanged = activeMediaUri != mediaUriString
        activeMediaUri = mediaUriString
        if (durationMs > 0L) {
            activeMediaDurationMs = durationMs
        }
        if (uriChanged) {
            pauseProcessing()
            clearGeneratedCues()
            val savedCues = loadCachedSavedSrt(mediaUriString, targetOpt.code)
            if (savedCues.isNotEmpty()) {
                synchronized(cuesLock) {
                    sortedPrimaryCues = savedCues
                }
                _state.value = AutoSubtitleUiState(
                    isEnabled = true,
                    isWhisperModelReady = whisperSpeechEngine.isModelInstalledOnDisk(),
                    targetLanguageCode = targetOpt.code,
                    targetLanguageLabel = targetOpt.displayName,
                    dualSubtitlesEnabled = dualEnabled,
                    secondaryLanguageCode = secondaryOpt.code,
                    secondaryLanguageLabel = secondaryOpt.displayName,
                    generatedCueCount = savedCues.size,
                    loadedFromSavedSrt = true,
                    statusMessage = "Auto-loaded saved .SRT (${savedCues.size} lines · 0% CPU)"
                )
            } else {
                _state.value = AutoSubtitleUiState(
                    isEnabled = false,
                    isWhisperModelReady = whisperSpeechEngine.isModelInstalledOnDisk(),
                    targetLanguageCode = targetOpt.code,
                    targetLanguageLabel = targetOpt.displayName,
                    dualSubtitlesEnabled = dualEnabled,
                    secondaryLanguageCode = secondaryOpt.code,
                    secondaryLanguageLabel = secondaryOpt.displayName,
                    statusMessage = "Off · Tap Enable for free on-device AI subtitles"
                )
            }
            refreshReadinessAsync(targetOpt.code)
        } else {
            updateTargetLanguageInternal(targetOpt.code, clearExisting = false)
            updateDualConfigInternal(dualEnabled, secondaryOpt.code, clearExisting = false)
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

    fun setDualSubtitlesConfig(enabled: Boolean, secondaryCode: String) {
        updateDualConfigInternal(enabled, secondaryCode, clearExisting = true)
    }

    private fun updateDualConfigInternal(enabled: Boolean, secondaryCode: String, clearExisting: Boolean) {
        val secOpt = AutoSubtitleLanguageCatalog.findByCode(secondaryCode)
        val cur = _state.value
        val changed = cur.dualSubtitlesEnabled != enabled ||
            !cur.secondaryLanguageCode.equals(secOpt.code, ignoreCase = true)
        if (!changed) return

        val wasEnabled = cur.isEnabled
        if (clearExisting && wasEnabled) {
            pauseProcessing()
            clearGeneratedCues()
        }
        _state.value = cur.copy(
            dualSubtitlesEnabled = enabled,
            secondaryLanguageCode = secOpt.code,
            secondaryLanguageLabel = secOpt.displayName,
            loadedFromSavedSrt = false,
            errorMessage = null
        )
        if (wasEnabled && clearExisting && activeMediaUri.isNotBlank()) {
            enableAndPrepare(lastPlaybackPositionMs, isPlaybackActive)
        }
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
            loadedFromSavedSrt = false,
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
     * downloads primary (and optional dual secondary) ML Kit language packs, and starts
     * rolling 25s VAD-trimmed subtitle generation synced to playback.
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

        // If already auto-loaded from saved SRT and not dual-mode, enable immediately at 0% CPU
        if (_state.value.loadedFromSavedSrt && sortedPrimaryCues.isNotEmpty() && !_state.value.dualSubtitlesEnabled) {
            _state.value = _state.value.copy(
                isEnabled = true,
                statusMessage = "Saved .SRT active (${sortedPrimaryCues.size} lines · 0% CPU)"
            )
            return
        }

        setupJob?.cancel()
        setupJob = scope.launch {
            val snapshot = _state.value
            val targetCode = snapshot.targetLanguageCode
            val targetLabel = snapshot.targetLanguageLabel
            val dualEnabled = snapshot.dualSubtitlesEnabled
            val secondaryCode = snapshot.secondaryLanguageCode
            val secondaryLabel = snapshot.secondaryLanguageLabel

            _state.value = _state.value.copy(
                isEnabled = true,
                isSetupRunning = true,
                setupProgressPercent = 0,
                setupStepLabel = "Checking Whisper multilingual model...",
                errorMessage = null,
                statusMessage = "Preparing Auto AI Subtitles ($targetLabel)..."
            )
            try {
                val whisperRes = whisperSpeechEngine.ensureBaseEnModelReady { pct ->
                    val scaled = if (needsMlKitTranslation(targetCode) || (dualEnabled && needsMlKitTranslation(secondaryCode))) {
                        (pct * 0.65f).toInt()
                    } else {
                        pct
                    }
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

                if (needsMlKitTranslation(targetCode)) {
                    _state.value = _state.value.copy(
                        setupProgressPercent = 72,
                        setupStepLabel = "Downloading $targetLabel offline language pack..."
                    )
                    ensureMlKitLanguagePackDownloaded(targetCode).getOrThrow()
                }

                if (dualEnabled && needsMlKitTranslation(secondaryCode) &&
                    !secondaryCode.equals(targetCode, ignoreCase = true)
                ) {
                    _state.value = _state.value.copy(
                        setupProgressPercent = 86,
                        setupStepLabel = "Downloading $secondaryLabel dual subtitle pack..."
                    )
                    ensureMlKitLanguagePackDownloaded(secondaryCode).getOrThrow()
                }

                val modeLabel = if (dualEnabled) "$targetLabel + $secondaryLabel" else targetLabel
                _state.value = _state.value.copy(
                    isSetupRunning = false,
                    setupProgressPercent = 100,
                    setupStepLabel = "",
                    isTargetLanguagePackReady = true,
                    statusMessage = if (isPlaybackActive) {
                        "Live Auto Subtitles ($modeLabel) · Syncing speech..."
                    } else {
                        "Auto Subtitles Ready ($modeLabel) · Play video to start"
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

    fun onPlaybackStateUpdated(positionMs: Long, isPlaying: Boolean) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        val wasPlaying = isPlaybackActive
        isPlaybackActive = isPlaying

        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

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

    fun onSeekTo(positionMs: Long) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

        val targetIdx = chunkIndexForPositionMs(lastPlaybackPositionMs)
        val activeIdx = currentChunkBeingProcessed
        if (activeIdx >= 0 && abs(targetIdx - activeIdx) > 1) {
            pauseProcessing()
        }
        if (isPlaybackActive) {
            scheduleRollingGeneration(lastPlaybackPositionMs)
        }
    }

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
                    val s = _state.value
                    val label = if (s.dualSubtitlesEnabled) {
                        "${s.targetLanguageLabel} + ${s.secondaryLanguageLabel}"
                    } else {
                        s.targetLanguageLabel
                    }
                    _state.value = s.copy(
                        isGeneratingChunk = false,
                        activeChunkIndex = -1,
                        statusMessage = if (s.isEnabled) {
                            "Live Auto Subtitles ($label) · ${s.generatedCueCount} lines synced"
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
        val snapshot = _state.value
        val targetCode = snapshot.targetLanguageCode
        val targetLabel = snapshot.targetLanguageLabel
        val dualEnabled = snapshot.dualSubtitlesEnabled
        val secondaryCode = snapshot.secondaryLanguageCode

        currentChunkBeingProcessed = chunkIndex
        _state.value = snapshot.copy(
            isGeneratingChunk = true,
            activeChunkIndex = chunkIndex,
            errorMessage = null,
            statusMessage = "Syncing speech ($targetLabel · Chunk ${chunkIndex + 1})..."
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

        val needsEnglishBridge = targetCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL ||
            (dualEnabled && secondaryCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL)

        val whisperLangParam = if (needsEnglishBridge) "auto:translate" else "auto"

        val segments = whisperSpeechEngine.transcribePcmChunkSuspending(
            chunk = pcmChunk,
            language = whisperLangParam
        ).getOrThrow()

        val primaryTranslator = if (needsMlKitTranslation(targetCode)) {
            getOrCreateTranslator(targetCode)
        } else null

        val secondaryTranslator = if (dualEnabled && needsMlKitTranslation(secondaryCode)) {
            getOrCreateTranslator(secondaryCode)
        } else null

        val generatedPrimary = mutableListOf<SubtitleCue>()
        val generatedSecondary = mutableListOf<SubtitleCue>()

        // Compute adaptive noise floor across the 25s chunk for robust VAD trimming
        val chunkNoiseFloor = estimateChunkNoiseFloor(pcmChunk.samples16kMono)

        for (seg in segments) {
            coroutineContext.ensureActive()
            val cleaned = cleanWhisperText(seg.originalText)
            if (cleaned.isEmpty()) continue

            val rawStart = seg.startTimeMs.coerceAtLeast(startMs)
            val rawEnd = seg.endTimeMs.coerceAtLeast(rawStart + 350L)

            // CRITICAL v2.4.0 FIX: Trim leading & trailing silence using 20ms RMS Voice Activity Detection
            val trimmedBounds = trimSegmentWithVad(
                pcm16k = pcmChunk.samples16kMono,
                chunkStartMs = startMs,
                segmentStartMs = rawStart,
                segmentEndMs = rawEnd,
                noiseFloorRms = chunkNoiseFloor
            ) ?: continue // Drop pure-silence hallucinations completely

            val cueStart = trimmedBounds.first
            val cueEnd = trimmedBounds.second

            val primaryText = if (primaryTranslator != null) {
                runCatching { primaryTranslator.translate(cleaned).await().trim() }
                    .getOrDefault(cleaned)
                    .ifBlank { cleaned }
            } else {
                cleaned
            }

            generatedPrimary.add(
                SubtitleCue(
                    startTimeMs = cueStart,
                    endTimeMs = cueEnd,
                    text = primaryText
                )
            )

            if (dualEnabled) {
                val secText = when {
                    secondaryCode.equals(targetCode, ignoreCase = true) -> primaryText
                    secondaryTranslator != null -> {
                        runCatching { secondaryTranslator.translate(cleaned).await().trim() }
                            .getOrDefault(cleaned)
                            .ifBlank { cleaned }
                    }
                    else -> cleaned
                }
                generatedSecondary.add(
                    SubtitleCue(
                        startTimeMs = cueStart,
                        endTimeMs = cueEnd,
                        text = secText
                    )
                )
            }
        }

        // Prevent consecutive cues from overlapping into the next speaker's lead-in
        val nonOverlappingPrimary = enforceStrictNonOverlap(generatedPrimary)
        val nonOverlappingSecondary = enforceStrictNonOverlap(generatedSecondary)

        synchronized(cuesLock) {
            primaryCuesByChunk[chunkIndex] = nonOverlappingPrimary
            sortedPrimaryCues = primaryCuesByChunk.values
                .flatten()
                .sortedBy { it.startTimeMs }
            if (dualEnabled) {
                secondaryCuesByChunk[chunkIndex] = nonOverlappingSecondary
                sortedSecondaryCues = secondaryCuesByChunk.values
                    .flatten()
                    .sortedBy { it.startTimeMs }
            } else {
                secondaryCuesByChunk.clear()
                sortedSecondaryCues = emptyList()
            }
        }
        completedChunks.add(chunkIndex)

        val modeLabel = if (dualEnabled) "$targetLabel + ${snapshot.secondaryLanguageLabel}" else targetLabel
        _state.value = _state.value.copy(
            completedChunksCount = completedChunks.size,
            generatedCueCount = sortedPrimaryCues.size,
            statusMessage = "Live Auto Subtitles ($modeLabel) · ${sortedPrimaryCues.size} lines synced"
        )
    }

    /**
     * Frame-synced primary + optional secondary subtitle lookup.
     * Zero look-ahead: strictly returns text ONLY when `currentPosMs in startTimeMs..endTimeMs`.
     */
    fun findActiveDualCue(playbackPositionMs: Long, syncOffsetMs: Long = 0L): DualActiveSubtitle {
        val cur = _state.value
        if (!cur.isEnabled) return DualActiveSubtitle()
        val primary = findExactCueForPosition(sortedPrimaryCues, playbackPositionMs, syncOffsetMs)
        val secondary = if (cur.dualSubtitlesEnabled) {
            findExactCueForPosition(sortedSecondaryCues, playbackPositionMs, syncOffsetMs)
        } else {
            null
        }
        return DualActiveSubtitle(primaryText = primary, secondaryText = secondary)
    }

    fun findActiveCueText(playbackPositionMs: Long, syncOffsetMs: Long = 0L): String? {
        return findActiveDualCue(playbackPositionMs, syncOffsetMs).primaryText
    }

    /**
     * Dialogue Search (Jump to Scene): searches generated primary & secondary subtitles
     * for any word or phrase and returns matching timestamped entries.
     */
    fun searchDialogue(query: String): List<DialogueSearchResult> {
        val primary = sortedPrimaryCues
        if (primary.isEmpty()) return emptyList()
        val secondary = sortedSecondaryCues
        val q = query.trim().lowercase(Locale.ROOT)
        val results = mutableListOf<DialogueSearchResult>()
        for (i in primary.indices) {
            val pCue = primary[i]
            val sCue = secondary.getOrNull(i)?.takeIf { abs(it.startTimeMs - pCue.startTimeMs) < 1_000L }
            val matches = q.isEmpty() ||
                pCue.text.lowercase(Locale.ROOT).contains(q) ||
                (sCue?.text?.lowercase(Locale.ROOT)?.contains(q) == true)
            if (matches) {
                results.add(
                    DialogueSearchResult(
                        startTimeMs = pCue.startTimeMs,
                        endTimeMs = pCue.endTimeMs,
                        primaryText = pCue.text,
                        secondaryText = sCue?.text
                    )
                )
            }
        }
        return results
    }

    /**
     * Exports generated AI subtitles as a standard `.srt` file to `Documents/VOXORA/Subtitles/`
     * AND saves a local auto-load mirror keyed by media URI + language so future playback
     * loads immediately without re-running Whisper.
     */
    suspend fun exportSubtitlesToSrt(videoTitle: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cues = sortedPrimaryCues
            if (cues.isEmpty()) {
                throw IllegalStateException("No AI subtitles generated yet. Play the video briefly first.")
            }
            val langCode = _state.value.targetLanguageCode
            val srtContent = formatAsSrt(cues)
            val safeBase = videoTitle
                .replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
                .trim()
                .take(48)
                .ifBlank { "VOXORA_Subtitle" }
            val fileName = "${safeBase}_${langCode}.srt"

            // 1. Save auto-load mirror inside app storage keyed by media URI + language
            if (activeMediaUri.isNotBlank()) {
                val mirrorFile = cacheFileFor(activeMediaUri, langCode)
                mirrorFile.writeText(srtContent, Charsets.UTF_8)
            }

            // 2. Save user-visible .SRT to Documents/VOXORA/Subtitles/
            val savedDisplayPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/VOXORA/Subtitles")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: throw IllegalStateException("Could not create .SRT file in Documents/VOXORA/Subtitles")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(srtContent.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open output stream for .SRT export")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Documents/VOXORA/Subtitles/$fileName"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "VOXORA/Subtitles"
                ).apply { mkdirs() }
                val outFile = File(dir, fileName)
                outFile.writeText(srtContent, Charsets.UTF_8)
                outFile.absolutePath
            }
            savedDisplayPath
        }
    }

    private fun loadCachedSavedSrt(mediaUri: String, langCode: String): List<SubtitleCue> {
        val file = cacheFileFor(mediaUri, langCode)
        if (!file.exists()) return emptyList()
        return runCatching {
            SubtitleParser.parse(file.readText(Charsets.UTF_8), file.name)
        }.getOrDefault(emptyList())
    }

    private fun cacheFileFor(mediaUri: String, langCode: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$mediaUri|$langCode".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(savedSrtCacheDir, "${digest}_${langCode}.srt")
    }

    private fun clearGeneratedCues() {
        synchronized(cuesLock) {
            primaryCuesByChunk.clear()
            secondaryCuesByChunk.clear()
            sortedPrimaryCues = emptyList()
            sortedSecondaryCues = emptyList()
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
        activeTranslators[targetCode]?.let { return@withLock it }
        if (activeTranslators.size >= 3) {
            val oldestKey = activeTranslators.keys.first()
            runCatching { activeTranslators.remove(oldestKey)?.close() }
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetCode)
            .build()
        val created = Translation.getClient(options)
        activeTranslators[targetCode] = created
        created
    }

    fun release() {
        setupJob?.cancel()
        pauseProcessing()
        clearGeneratedCues()
        activeTranslators.values.forEach { runCatching { it.close() } }
        activeTranslators.clear()
    }

    companion object {
        const val CHUNK_DURATION_MS: Long = 25_000L
        const val SAMPLE_RATE_HZ: Int = 16_000
        private const val VAD_FRAME_MS: Int = 20
        private const val VAD_FRAME_SAMPLES: Int = (SAMPLE_RATE_HZ * VAD_FRAME_MS) / 1000 // 320 samples
        private const val MIN_SPEECH_RMS_THRESHOLD: Float = 0.015f

        fun needsMlKitTranslation(targetCode: String): Boolean {
            return targetCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL &&
                !targetCode.equals(AutoSubtitleLanguageCatalog.CODE_ENGLISH, ignoreCase = true)
        }

        fun chunkIndexForPositionMs(positionMs: Long): Int {
            return (positionMs.coerceAtLeast(0L) / CHUNK_DURATION_MS).toInt()
        }

        /**
         * Estimates the ambient noise floor RMS across a 16kHz PCM chunk using the 20th-percentile
         * frame RMS so VAD adapts cleanly to quiet rooms, movie BGM, or noisy recordings.
         */
        fun estimateChunkNoiseFloor(pcm16k: FloatArray): Float {
            if (pcm16k.size < VAD_FRAME_SAMPLES) return 0.006f
            val frameCount = pcm16k.size / VAD_FRAME_SAMPLES
            val energies = FloatArray(frameCount)
            for (f in 0 until frameCount) {
                val base = f * VAD_FRAME_SAMPLES
                var sumSq = 0.0
                for (i in 0 until VAD_FRAME_SAMPLES) {
                    val s = pcm16k[base + i].toDouble()
                    sumSq += s * s
                }
                energies[f] = sqrt(sumSq / VAD_FRAME_SAMPLES).toFloat()
            }
            energies.sort()
            val p20Index = (frameCount * 0.20f).toInt().coerceIn(0, frameCount - 1)
            return energies[p20Index].coerceIn(0.002f, 0.045f)
        }

        /**
         * Lightweight RMS Energy / Voice Activity Detection (VAD) trim for a Whisper segment.
         *
         * Fixes premature subtitles during 5-7s silent gaps before the speaker actually speaks:
         * - Advances `startMs` forward to the exact millisecond where speech energy crosses the
         *   adaptive threshold (requires 2 consecutive active 20ms frames to ignore micro-clicks).
         * - Trims `endMs` back to where speech actually stops (+120ms natural decay tail).
         * - Returns `null` if the entire segment slice contains no real speech energy.
         */
        fun trimSegmentWithVad(
            pcm16k: FloatArray,
            chunkStartMs: Long,
            segmentStartMs: Long,
            segmentEndMs: Long,
            noiseFloorRms: Float = 0.006f
        ): Pair<Long, Long>? {
            if (pcm16k.isEmpty() || segmentEndMs <= segmentStartMs) return null

            val relStartMs = (segmentStartMs - chunkStartMs).coerceAtLeast(0L)
            val relEndMs = (segmentEndMs - chunkStartMs).coerceAtLeast(relStartMs)
            val startSample = ((relStartMs * SAMPLE_RATE_HZ) / 1000L).toInt().coerceIn(0, pcm16k.size)
            val endSample = ((relEndMs * SAMPLE_RATE_HZ) / 1000L).toInt().coerceIn(startSample, pcm16k.size)
            val sliceLen = endSample - startSample
            if (sliceLen < VAD_FRAME_SAMPLES) return segmentStartMs to segmentEndMs

            val frameCount = sliceLen / VAD_FRAME_SAMPLES
            val frameRms = FloatArray(frameCount)
            var peakRms = 0f
            for (f in 0 until frameCount) {
                val base = startSample + f * VAD_FRAME_SAMPLES
                var sumSq = 0.0
                for (i in 0 until VAD_FRAME_SAMPLES) {
                    val v = pcm16k[base + i].toDouble()
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / VAD_FRAME_SAMPLES).toFloat()
                frameRms[f] = rms
                if (rms > peakRms) peakRms = rms
            }

            val adaptiveThreshold = maxOf(
                MIN_SPEECH_RMS_THRESHOLD,
                noiseFloorRms * 2.2f,
                peakRms * 0.16f
            )

            // If peak energy never exceeds minimum speech floor, discard silent hallucination
            if (peakRms < MIN_SPEECH_RMS_THRESHOLD) return null

            // Find first sustained speech onset (2 consecutive active frames to skip clicks)
            var firstActive = -1
            for (f in 0 until frameCount) {
                val isActive = frameRms[f] >= adaptiveThreshold
                val nextActive = (f + 1 < frameCount) && (frameRms[f + 1] >= adaptiveThreshold * 0.85f)
                if (isActive && (nextActive || frameCount == 1)) {
                    firstActive = f
                    break
                }
            }
            if (firstActive < 0) {
                firstActive = frameRms.indexOfFirst { it >= adaptiveThreshold }
            }
            if (firstActive < 0) return null

            // Find last active speech frame
            var lastActive = firstActive
            for (f in frameCount - 1 downTo firstActive) {
                if (frameRms[f] >= adaptiveThreshold * 0.85f) {
                    lastActive = f
                    break
                }
            }

            // Tiny 40ms onset lead (never bleeds into multi-second silence) and 120ms speech tail
            val trimmedStartMs = (chunkStartMs + relStartMs + (firstActive * VAD_FRAME_MS.toLong()) - 40L)
                .coerceAtLeast(segmentStartMs)
            val trimmedEndMs = (chunkStartMs + relStartMs + ((lastActive + 1) * VAD_FRAME_MS.toLong()) + 120L)
                .coerceAtMost(segmentEndMs)
                .coerceAtLeast(trimmedStartMs + 280L)

            return trimmedStartMs to trimmedEndMs
        }

        fun enforceStrictNonOverlap(cues: List<SubtitleCue>): List<SubtitleCue> {
            if (cues.size <= 1) return cues
            val sorted = cues.sortedBy { it.startTimeMs }
            val result = mutableListOf<SubtitleCue>()
            for (i in sorted.indices) {
                val cur = sorted[i]
                val next = sorted.getOrNull(i + 1)
                val clampedEnd = if (next != null && cur.endTimeMs >= next.startTimeMs) {
                    (next.startTimeMs - 20L).coerceAtLeast(cur.startTimeMs + 150L)
                } else {
                    cur.endTimeMs
                }
                result.add(cur.copy(endTimeMs = clampedEnd))
            }
            return result
        }

        fun formatAsSrt(cues: List<SubtitleCue>): String {
            val sb = StringBuilder()
            cues.sortedBy { it.startTimeMs }.forEachIndexed { idx, cue ->
                sb.append(idx + 1).append('\n')
                sb.append(formatSrtTimestamp(cue.startTimeMs))
                    .append(" --> ")
                    .append(formatSrtTimestamp(cue.endTimeMs))
                    .append('\n')
                sb.append(cue.text.trim()).append("\n\n")
            }
            return sb.toString()
        }

        fun formatSrtTimestamp(ms: Long): String {
            val safe = ms.coerceAtLeast(0L)
            val hours = safe / 3_600_000L
            val minutes = (safe % 3_600_000L) / 60_000L
            val seconds = (safe % 60_000L) / 1_000L
            val millis = safe % 1_000L
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
        }

        fun cleanWhisperText(raw: String): String {
            val stripped = raw
                .replace(Regex("\\[(?:BLANK_AUDIO|MUSIC|APPLAUSE|LAUGHTER|SILENCE|INAUDIBLE|SOUND|NOISE)[^\\]]*]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\((?:music|applause|laughter|silence|sighs|groans)[^)]*\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (stripped.length <= 1 && !stripped.any { it.isLetterOrDigit() }) "" else stripped
        }

        /**
         * Strict zero-lookahead lookup for frame-accurate subtitle display.
         * Returns `null` immediately during any silent gap outside `[startTimeMs, endTimeMs]`.
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

            val cue = cues[candidateIdx]
            return if (adjusted in cue.startTimeMs..cue.endTimeMs && cue.text.isNotBlank()) {
                cue.text
            } else {
                null
            }
        }
    }
}
