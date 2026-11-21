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
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
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

data class VadTimedSegment(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

data class MergedSentenceBlock(
    val mergedText: String,
    val constituents: List<VadTimedSegment>
) {
    val startTimeMs: Long get() = constituents.first().startTimeMs
    val endTimeMs: Long get() = constituents.last().endTimeMs
}

data class CachedSubtitleSegment(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val rawOriginalText: String,
    val translatedText: String
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
    val translationRevision: Int = 0,
    val loadedFromSavedSrt: Boolean = false,
    val statusMessage: String = "Off · Tap Enable for 100% free AI subtitles",
    val errorMessage: String? = null
)

/**
 * Full 59+ language catalog powered by Online Neural Translation + Google ML Kit Offline Fallback,
 * plus "Original Spoken Language" (Whisper multilingual auto-detection without translation).
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
 * Flagship Auto AI Subtitle Engine for VOXORA v2.6.0:
 *
 * - **Progressive Fast-Start Chunking**: When enabled or after seeking to an uncached position,
 *   transcribes a **7-second fast-start chunk** first (~1.5–2s startup!), then seamlessly
 *   continues with **20-second rolling look-ahead chunks**.
 * - **2-Stage Grammatically Accurate Translation**:
 *   1. Smart Sentence Merging before translation (joins non-terminated fragments with <600ms gap
 *      up to 18 words so translators receive full grammatical context).
 *   2. Online Neural Translation Primary ([NeuralSubtitleTranslator] with 3s timeout & memory cache)
 *      with instant, zero-error fallback to On-Device Google ML Kit Translation when offline.
 * - **Strict Speech-Synced VAD & Zero Look-Ahead**: Anchored to exact MediaExtractor PTS,
 *   `noiseFloor * 1.45f`, >1200ms leading silence trim, and strict `currentPosMs in start..end`.
 */
class AutoSubtitleManager(
    context: Context,
    private val whisperSpeechEngine: WhisperCppSpeechRecognitionEngine,
    private val videoPcmAudioDecoder: VideoPcmAudioDecoder = VideoPcmAudioDecoder(context.applicationContext),
    private val neuralTranslator: NeuralSubtitleTranslator = NeuralSubtitleTranslator()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cuesLock = Any()
    private val translatorMutex = Mutex()

    private val _state = MutableStateFlow(AutoSubtitleUiState())
    val state: StateFlow<AutoSubtitleUiState> = _state.asStateFlow()

    private val seekGenerationId = AtomicLong(0L)

    private val rawBlocksByWindow = linkedMapOf<Long, List<MergedSentenceBlock>>()
    private val cachedSegmentsByWindow = linkedMapOf<Long, List<CachedSubtitleSegment>>()
    private val primaryCuesByWindow = linkedMapOf<Long, List<SubtitleCue>>()
    private val secondaryCuesByWindow = linkedMapOf<Long, List<SubtitleCue>>()
    private val coveredIntervals = mutableListOf<LongRange>()

    @Volatile
    private var sortedPrimaryCues: List<SubtitleCue> = emptyList()
    @Volatile
    private var sortedSecondaryCues: List<SubtitleCue> = emptyList()
    @Volatile
    private var cachedWindowsUsedEnglishBridge: Boolean = false

    @Volatile
    private var activeMediaUri: String = ""
    @Volatile
    private var activeMediaDurationMs: Long = 0L
    @Volatile
    private var activeProcessingWindow: LongRange? = null
    @Volatile
    private var lastPlaybackPositionMs: Long = 0L
    @Volatile
    private var lastDriftTriggerPosMs: Long = Long.MIN_VALUE
    @Volatile
    private var isPlaybackActive: Boolean = false
    @Volatile
    private var activeAudioTrackIndex: Int = 0
    @Volatile
    private var audioTrackLanguageHint: String? = null
    @Volatile
    private var windowCounter: Int = 0
    @Volatile
    private var lastEmittedRawNormalized: String? = null
    @Volatile
    private var lastEmittedRawEndMs: Long = Long.MIN_VALUE

    private var setupJob: Job? = null
    private var activeSubtitleJob: Job? = null
    private var retranslateJob: Job? = null

    private val activeTranslators = linkedMapOf<String, Translator>()

    private val savedSrtCacheDir: File
        get() = File(appContext.filesDir, "saved_ai_subtitles").apply { mkdirs() }

    /**
     * Cancels all in-flight jobs, invalidates stale results, and wipes all in-memory caches
     * and duplicate-suppressor state before switching to a new video or Auto-Next episode.
     */
    fun resetForNewMedia() {
        seekGenerationId.incrementAndGet()
        setupJob?.cancel()
        setupJob = null
        retranslateJob?.cancel()
        retranslateJob = null
        pauseProcessing()
        clearGeneratedCues()
        activeAudioTrackIndex = 0
        audioTrackLanguageHint = null
        lastPlaybackPositionMs = 0L
        lastDriftTriggerPosMs = Long.MIN_VALUE
        _state.value = _state.value.copy(
            isGeneratingChunk = false,
            activeChunkIndex = -1,
            completedChunksCount = 0,
            generatedCueCount = 0,
            translationRevision = _state.value.translationRevision + 1,
            errorMessage = null
        )
    }

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
            resetForNewMedia()
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
                    statusMessage = "Off · Tap Enable for instant AI subtitles"
                )
            }
            refreshReadinessAsync(targetOpt.code)
        } else {
            updateTargetLanguageInternal(targetOpt.code, allowInstantRetranslate = false)
            updateDualConfigInternal(dualEnabled, secondaryOpt.code, allowInstantRetranslate = false)
        }
    }

    fun updateDuration(durationMs: Long) {
        if (durationMs > 0L) {
            activeMediaDurationMs = durationMs
        }
    }

    /**
     * Dual-Audio Track Synchronization:
     * Updates the active audio stream ordinal + ISO language code for MediaExtractor & Whisper.
     * When the user switches tracks mid-video (e.g. Korean -> English in a Dual-Audio MKV),
     * invalidates old-track caches and immediately re-anchors at [currentPositionMs].
     */
    fun onAudioTrackChanged(
        audioTrackIndex: Int,
        isoLanguageCode: String?,
        currentPositionMs: Long = lastPlaybackPositionMs
    ) {
        val safeTrackIdx = audioTrackIndex.coerceAtLeast(0)
        val normalizedLang = isoLanguageCode
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != "und" && it != "auto" }

        val changed = (activeAudioTrackIndex != safeTrackIdx) || (audioTrackLanguageHint != normalizedLang)
        if (!changed) return

        activeAudioTrackIndex = safeTrackIdx
        audioTrackLanguageHint = normalizedLang

        val cur = _state.value
        val hasCached = synchronized(cuesLock) {
            coveredIntervals.isNotEmpty() || rawBlocksByWindow.isNotEmpty()
        }
        if (cur.isEnabled && !cur.isSetupRunning && !cur.loadedFromSavedSrt && activeMediaUri.isNotBlank()) {
            val anchorPos = currentPositionMs.coerceAtLeast(0L)
            lastPlaybackPositionMs = anchorPos
            retranslateJob?.cancel()
            retranslateJob = null
            pauseProcessing()
            clearGeneratedCues()
            _state.value = cur.copy(
                isGeneratingChunk = false,
                activeChunkIndex = -1,
                completedChunksCount = 0,
                generatedCueCount = 0,
                translationRevision = cur.translationRevision + 1,
                statusMessage = "Audio track switched · Syncing subtitles..."
            )
            if (isPlaybackActive) {
                reanchorAtPosition(anchorPos, debounceMs = 0L)
            }
        } else if (hasCached) {
            clearGeneratedCues()
        }
    }

    fun setAudioTrackLanguageHint(isoLanguageCode: String?) {
        onAudioTrackChanged(
            audioTrackIndex = activeAudioTrackIndex,
            isoLanguageCode = isoLanguageCode,
            currentPositionMs = lastPlaybackPositionMs
        )
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
        updateTargetLanguageInternal(targetCode, allowInstantRetranslate = true)
    }

    fun setDualSubtitlesConfig(enabled: Boolean, secondaryCode: String) {
        updateDualConfigInternal(enabled, secondaryCode, allowInstantRetranslate = true)
    }

    private fun updateDualConfigInternal(
        enabled: Boolean,
        secondaryCode: String,
        allowInstantRetranslate: Boolean
    ) {
        val secOpt = AutoSubtitleLanguageCatalog.findByCode(secondaryCode)
        val cur = _state.value
        val changed = cur.dualSubtitlesEnabled != enabled ||
            !cur.secondaryLanguageCode.equals(secOpt.code, ignoreCase = true)
        if (!changed) return

        val wasEnabled = cur.isEnabled
        val hasRawBlocks = synchronized(cuesLock) { rawBlocksByWindow.isNotEmpty() }

        _state.value = cur.copy(
            dualSubtitlesEnabled = enabled,
            secondaryLanguageCode = secOpt.code,
            secondaryLanguageLabel = secOpt.displayName,
            loadedFromSavedSrt = false,
            errorMessage = null
        )

        if (!enabled) {
            synchronized(cuesLock) {
                secondaryCuesByWindow.clear()
                sortedSecondaryCues = emptyList()
            }
            _state.value = _state.value.copy(translationRevision = _state.value.translationRevision + 1)
            return
        }

        if (wasEnabled && allowInstantRetranslate && activeMediaUri.isNotBlank()) {
            if (hasRawBlocks) {
                if (needsMlKitTranslation(secOpt.code)) {
                    scope.launch { ensureMlKitLanguagePackDownloaded(secOpt.code) }
                }
                retranslateCachedWindowsAsync(
                    primaryCode = _state.value.targetLanguageCode,
                    primaryLabel = _state.value.targetLanguageLabel,
                    dualEnabled = true,
                    secondaryCode = secOpt.code,
                    secondaryLabel = secOpt.displayName,
                    retranslatePrimary = false,
                    retranslateSecondary = true
                )
            } else {
                enableAndPrepare(lastPlaybackPositionMs, isPlaybackActive)
            }
        }
    }

    /**
     * Instant Target Language Switching (Zero Whisper Re-Run):
     * Reuses cached `rawOriginalText` blocks and batch-translates them in < 1 second without
     * re-running Whisper whenever cached blocks exist (unless switching from an English-bridge
     * cache to raw non-English Original mode).
     */
    private fun updateTargetLanguageInternal(targetCode: String, allowInstantRetranslate: Boolean) {
        val opt = AutoSubtitleLanguageCatalog.findByCode(targetCode)
        val cur = _state.value
        val changed = !cur.targetLanguageCode.equals(opt.code, ignoreCase = true)
        if (!changed) return

        val wasEnabled = cur.isEnabled
        val hasRawBlocks = synchronized(cuesLock) { rawBlocksByWindow.isNotEmpty() }
        val requiresWhisperRerun = opt.code == AutoSubtitleLanguageCatalog.CODE_ORIGINAL &&
            cachedWindowsUsedEnglishBridge &&
            audioTrackLanguageHint != "en"

        if (wasEnabled && allowInstantRetranslate && !cur.loadedFromSavedSrt && hasRawBlocks && !requiresWhisperRerun) {
            _state.value = cur.copy(
                targetLanguageCode = opt.code,
                targetLanguageLabel = opt.displayName,
                errorMessage = null,
                statusMessage = "Switching subtitles to ${opt.displayName}..."
            )
            refreshReadinessAsync(opt.code)
            if (needsMlKitTranslation(opt.code)) {
                scope.launch { ensureMlKitLanguagePackDownloaded(opt.code) }
            }
            retranslateCachedWindowsAsync(
                primaryCode = opt.code,
                primaryLabel = opt.displayName,
                dualEnabled = cur.dualSubtitlesEnabled,
                secondaryCode = cur.secondaryLanguageCode,
                secondaryLabel = cur.secondaryLanguageLabel,
                retranslatePrimary = true,
                retranslateSecondary = false
            )
            return
        }

        if (allowInstantRetranslate) {
            retranslateJob?.cancel()
            retranslateJob = null
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
        if (wasEnabled && allowInstantRetranslate && activeMediaUri.isNotBlank()) {
            enableAndPrepare(lastPlaybackPositionMs, isPlaybackActive)
        }
    }

    /**
     * Re-translates already-cached `rawOriginalText` windows in the background (prioritizing the
     * window nearest to live playback position first) using single-request batched neural
     * translation so switching languages updates the UI in < 1 second with ZERO Whisper re-run.
     */
    private fun retranslateCachedWindowsAsync(
        primaryCode: String,
        primaryLabel: String,
        dualEnabled: Boolean,
        secondaryCode: String,
        secondaryLabel: String,
        retranslatePrimary: Boolean,
        retranslateSecondary: Boolean
    ) {
        retranslateJob?.cancel()
        val expectedUri = activeMediaUri
        val anchorPos = lastPlaybackPositionMs
        retranslateJob = scope.launch {
            val snapshotEntries = synchronized(cuesLock) {
                rawBlocksByWindow.entries
                    .map { it.key to it.value }
                    .sortedBy { (winStart, _) -> abs(winStart - anchorPos) }
            }
            if (snapshotEntries.isEmpty()) return@launch

            val primaryFallback = if (retranslatePrimary && needsMlKitTranslation(primaryCode)) {
                getOrCreateTranslator(primaryCode)
            } else null
            val secondaryFallback = if (retranslateSecondary && dualEnabled && needsMlKitTranslation(secondaryCode)) {
                getOrCreateTranslator(secondaryCode)
            } else null

            for ((winStart, blocks) in snapshotEntries) {
                coroutineContext.ensureActive()
                if (activeMediaUri != expectedUri) return@launch
                if (blocks.isEmpty()) continue

                val newPrimaryCues = if (retranslatePrimary) {
                    val translatedList = translateBlocksBatched(
                        blocks = blocks,
                        targetCode = primaryCode,
                        mlKitFallback = primaryFallback,
                        sourceIsAlreadyEnglish = cachedWindowsUsedEnglishBridge || audioTrackLanguageHint == "en"
                    )
                    val builtCues = mutableListOf<SubtitleCue>()
                    val builtCached = mutableListOf<CachedSubtitleSegment>()
                    for (i in blocks.indices) {
                        val block = blocks[i]
                        val tr = translatedList[i]
                        builtCached.add(
                            CachedSubtitleSegment(
                                startTimeMs = block.startTimeMs,
                                endTimeMs = block.endTimeMs,
                                rawOriginalText = block.mergedText,
                                translatedText = tr
                            )
                        )
                        builtCues.addAll(mapTranslatedSentenceToCues(block, tr))
                    }
                    builtCached to enforceStrictNonOverlap(builtCues)
                } else null

                val newSecondaryCues = if (retranslateSecondary && dualEnabled) {
                    val translatedSec = if (secondaryCode.equals(primaryCode, ignoreCase = true) && newPrimaryCues != null) {
                        newPrimaryCues.first.map { it.translatedText }
                    } else {
                        translateBlocksBatched(
                            blocks = blocks,
                            targetCode = secondaryCode,
                            mlKitFallback = secondaryFallback,
                            sourceIsAlreadyEnglish = cachedWindowsUsedEnglishBridge || audioTrackLanguageHint == "en"
                        )
                    }
                    val builtSec = mutableListOf<SubtitleCue>()
                    for (i in blocks.indices) {
                        builtSec.addAll(mapTranslatedSentenceToCues(blocks[i], translatedSec[i]))
                    }
                    enforceStrictNonOverlap(builtSec)
                } else null

                synchronized(cuesLock) {
                    if (activeMediaUri != expectedUri) return@synchronized
                    if (newPrimaryCues != null) {
                        cachedSegmentsByWindow[winStart] = newPrimaryCues.first
                        primaryCuesByWindow[winStart] = newPrimaryCues.second
                        sortedPrimaryCues = suppressConsecutiveDuplicateCues(
                            enforceStrictNonOverlap(
                                primaryCuesByWindow.values.flatten().sortedBy { it.startTimeMs }
                            )
                        )
                    }
                    if (newSecondaryCues != null) {
                        secondaryCuesByWindow[winStart] = newSecondaryCues
                        sortedSecondaryCues = suppressConsecutiveDuplicateCues(
                            enforceStrictNonOverlap(
                                secondaryCuesByWindow.values.flatten().sortedBy { it.startTimeMs }
                            )
                        )
                    }
                }

                val modeLabel = if (dualEnabled) "$primaryLabel + $secondaryLabel" else primaryLabel
                _state.value = _state.value.copy(
                    generatedCueCount = sortedPrimaryCues.size,
                    translationRevision = _state.value.translationRevision + 1,
                    statusMessage = "Live Auto Subtitles ($modeLabel) · ${sortedPrimaryCues.size} lines synced"
                )
            }
        }
    }

    /**
     * One-Click Setup & Instant Fast-Start Activation:
     * Anchors strictly to live [currentPositionMs] (`exoPlayer.currentPosition.coerceAtLeast(0L)`),
     * ensures Whisper model is ready, prepares ML Kit offline fallback packs in background, and
     * launches the 6-second fast-start chunk followed by 18-second rolling look-ahead (max 60s ahead).
     */
    fun enableAndPrepare(currentPositionMs: Long, isPlayingNow: Boolean) {
        if (activeMediaUri.isBlank()) {
            _state.value = _state.value.copy(
                errorMessage = "Load a video before enabling Auto AI Subtitles."
            )
            return
        }
        val anchorPos = currentPositionMs.coerceAtLeast(0L)
        lastPlaybackPositionMs = anchorPos
        isPlaybackActive = isPlayingNow

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
                setupStepLabel = "Preparing VOXORA Smart Subtitles...",
                errorMessage = null,
                statusMessage = "Preparing Instant AI Subtitles ($targetLabel)..."
            )
            try {
                val whisperRes = whisperSpeechEngine.ensureBaseEnModelReady { pct ->
                    _state.value = _state.value.copy(
                        setupProgressPercent = pct.coerceIn(0, 95),
                        setupStepLabel = if (pct < 90) {
                            "Setting up Smart Subtitles ($pct%)..."
                        } else {
                            "Activating Offline AI Engine..."
                        }
                    )
                }
                whisperRes.getOrThrow()

                _state.value = _state.value.copy(isWhisperModelReady = true)

                // Warm up offline ML Kit packs in background so instant-start neural translation is NEVER blocked
                if (needsMlKitTranslation(targetCode)) {
                    scope.launch { ensureMlKitLanguagePackDownloaded(targetCode) }
                }
                if (dualEnabled && needsMlKitTranslation(secondaryCode) &&
                    !secondaryCode.equals(targetCode, ignoreCase = true)
                ) {
                    scope.launch { ensureMlKitLanguagePackDownloaded(secondaryCode) }
                }

                val modeLabel = if (dualEnabled) "$targetLabel + $secondaryLabel" else targetLabel
                _state.value = _state.value.copy(
                    isSetupRunning = false,
                    setupProgressPercent = 100,
                    setupStepLabel = "",
                    isTargetLanguagePackReady = true,
                    statusMessage = "Fast-Start Subtitles ($modeLabel) · Syncing speech...",
                    errorMessage = null
                )

                // Immediately anchor the fast 6-second first chunk right at live currentPosMs
                reanchorAtPosition(
                    anchorPositionMs = lastPlaybackPositionMs,
                    debounceMs = 0L,
                    forceFirstChunkWhilePaused = !isPlaybackActive
                )
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
        seekGenerationId.incrementAndGet()
        setupJob?.cancel()
        setupJob = null
        retranslateJob?.cancel()
        retranslateJob = null
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
     * Pauses rolling look-ahead immediately when the player pauses or moves to background so
     * CPU and battery are never wasted while paused. Preserves all cached cues for instant resume.
     */
    fun onPaused() {
        isPlaybackActive = false
        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) {
            pauseProcessing()
            return
        }
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return
        if (activeSubtitleJob?.isActive == true || cur.isGeneratingChunk) {
            pauseProcessing()
            _state.value = _state.value.copy(
                isGeneratingChunk = false,
                activeChunkIndex = -1,
                statusMessage = "Paused · Auto Subtitles standing by (0% CPU)"
            )
        }
    }

    /**
     * Seamlessly resumes rolling look-ahead anchored at [currentPositionMs] when playback resumes.
     */
    fun onResumed(currentPositionMs: Long) {
        val pos = currentPositionMs.coerceAtLeast(0L)
        lastPlaybackPositionMs = pos
        isPlaybackActive = true
        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

        val nextWin = synchronized(cuesLock) {
            planNextWindow(pos, coveredIntervals, activeMediaDurationMs)
        }
        if (nextWin != null && activeSubtitleJob?.isActive != true) {
            scheduleRollingGeneration(anchorPositionMs = pos, debounceMs = 0L)
        }
    }

    fun onPlaybackStateUpdated(positionMs: Long, isPlaying: Boolean) {
        val pos = positionMs.coerceAtLeast(0L)
        lastPlaybackPositionMs = pos
        val wasPlaying = isPlaybackActive
        isPlaybackActive = isPlaying

        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

        if (!isPlaying) {
            if (wasPlaying || activeSubtitleJob?.isActive == true) {
                onPaused()
            }
            return
        }

        val nextWin = synchronized(cuesLock) {
            planNextWindow(pos, coveredIntervals, activeMediaDurationMs)
        }
        if (nextWin != null && activeSubtitleJob?.isActive != true) {
            scheduleRollingGeneration(anchorPositionMs = pos, debounceMs = 0L)
        }
    }

    /**
     * Drift Watchdog called from the 80ms subtitle display loop:
     * If CC is ON and live playback position is > 3 seconds outside any cached chunk with no
     * active job targeting it (e.g., resume jump or fast-forward drift), auto-reanchors strictly
     * at [currentPositionMs].
     */
    fun checkDriftWatchdog(currentPositionMs: Long) {
        val pos = currentPositionMs.coerceAtLeast(0L)
        lastPlaybackPositionMs = pos
        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning || !isPlaybackActive) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

        val drifted = synchronized(cuesLock) {
            isPositionDriftedBeyondThreshold(
                positionMs = pos,
                covered = coveredIntervals,
                activeProcessingWindow = activeProcessingWindow,
                thresholdMs = DRIFT_WATCHDOG_THRESHOLD_MS
            )
        }
        if (!drifted) return

        // Avoid rapid re-triggers while an already-launched watchdog job is starting up
        if (activeSubtitleJob?.isActive == true &&
            abs(pos - lastDriftTriggerPosMs) <= DRIFT_WATCHDOG_THRESHOLD_MS
        ) {
            return
        }
        lastDriftTriggerPosMs = pos
        reanchorAtPosition(anchorPositionMs = pos, debounceMs = 0L)
    }

    /**
     * Handles ExoPlayer `Player.DISCONTINUITY_REASON_SEEK` (seekbar drag, ±10s double-tap,
     * bookmark jump, or resume jump):
     * - If [newPositionMs] is already covered by cached chunks, serves instantly without restarting.
     * - If NOT covered, increments [seekGenerationId], cancels the active coroutine immediately,
     *   debounces by 350ms, and launches a fresh job anchored strictly at [newPositionMs] with a
     *   6-second fast-start chunk followed by 18-second rolling look-ahead (max 60s ahead).
     */
    fun onSeekDiscontinuity(newPositionMs: Long) {
        val pos = newPositionMs.coerceAtLeast(0L)
        lastPlaybackPositionMs = pos
        val cur = _state.value
        if (!cur.isEnabled || cur.isSetupRunning) return
        if (cur.loadedFromSavedSrt && !cur.dualSubtitlesEnabled) return

        val alreadyCovered = synchronized(cuesLock) {
            isPositionCovered(pos, coveredIntervals)
        }
        if (alreadyCovered) {
            if (isPlaybackActive && activeSubtitleJob?.isActive != true) {
                scheduleRollingGeneration(anchorPositionMs = pos, debounceMs = 0L)
            }
            return
        }

        lastDriftTriggerPosMs = pos
        if (isPlaybackActive) {
            reanchorAtPosition(anchorPositionMs = pos, debounceMs = SEEK_DEBOUNCE_MS)
        } else {
            // Even if paused when seeking, abort stale job targeting old timestamp so CPU stays 0%
            seekGenerationId.incrementAndGet()
            pauseProcessing()
        }
    }

    fun onSeekTo(positionMs: Long) {
        onSeekDiscontinuity(positionMs)
    }

    fun pauseProcessing() {
        whisperSpeechEngine.requestAbortNativeInference()
        activeSubtitleJob?.cancel()
        activeSubtitleJob = null
        activeProcessingWindow = null
        if (_state.value.isGeneratingChunk) {
            _state.value = _state.value.copy(
                isGeneratingChunk = false,
                activeChunkIndex = -1
            )
        }
    }

    private fun reanchorAtPosition(
        anchorPositionMs: Long,
        debounceMs: Long,
        forceFirstChunkWhilePaused: Boolean = false
    ) {
        val uri = activeMediaUri
        if (uri.isBlank() || !_state.value.isEnabled) return
        if (!isPlaybackActive && !forceFirstChunkWhilePaused) return

        val myGen = seekGenerationId.incrementAndGet()
        whisperSpeechEngine.requestAbortNativeInference()
        activeSubtitleJob?.cancel()
        activeSubtitleJob = null
        activeProcessingWindow = null

        launchSubtitleWorker(
            uri = uri,
            anchorPositionMs = anchorPositionMs.coerceAtLeast(0L),
            generationId = myGen,
            debounceMs = debounceMs,
            forceFirstChunkWhilePaused = forceFirstChunkWhilePaused
        )
    }

    private fun scheduleRollingGeneration(
        anchorPositionMs: Long,
        debounceMs: Long = 0L,
        forceFirstChunkWhilePaused: Boolean = false
    ) {
        val uri = activeMediaUri
        if (uri.isBlank() || !_state.value.isEnabled) return
        if (!isPlaybackActive && !forceFirstChunkWhilePaused) return
        if (activeSubtitleJob?.isActive == true) return

        val myGen = seekGenerationId.get()
        launchSubtitleWorker(
            uri = uri,
            anchorPositionMs = anchorPositionMs.coerceAtLeast(0L),
            generationId = myGen,
            debounceMs = debounceMs,
            forceFirstChunkWhilePaused = forceFirstChunkWhilePaused
        )
    }

    private fun launchSubtitleWorker(
        uri: String,
        anchorPositionMs: Long,
        generationId: Long,
        debounceMs: Long,
        forceFirstChunkWhilePaused: Boolean
    ) {
        lastPlaybackPositionMs = anchorPositionMs
        activeSubtitleJob = scope.launch {
            try {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                if (generationId != seekGenerationId.get()) return@launch
                if (activeMediaUri != uri) return@launch

                var didSinglePausedFastStart = false
                var isFirstWindowAfterAnchor = true
                while (_state.value.isEnabled &&
                    generationId == seekGenerationId.get() &&
                    activeMediaUri == uri &&
                    (isPlaybackActive || (forceFirstChunkWhilePaused && !didSinglePausedFastStart))
                ) {
                    ensureActive()
                    val pos = if (isFirstWindowAfterAnchor) {
                        isFirstWindowAfterAnchor = false
                        anchorPositionMs
                    } else {
                        lastPlaybackPositionMs
                    }
                    val nextWindow = synchronized(cuesLock) {
                        planNextWindow(pos, coveredIntervals, activeMediaDurationMs)
                    } ?: break

                    val (windowStartMs, windowDurationMs) = nextWindow
                    processSingleWindow(
                        mediaUriString = uri,
                        windowStartMs = windowStartMs,
                        windowDurationMs = windowDurationMs,
                        expectedGen = generationId
                    )
                    if (!isPlaybackActive) {
                        didSinglePausedFastStart = true
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (generationId == seekGenerationId.get()) {
                    _state.value = _state.value.copy(
                        isGeneratingChunk = false,
                        activeChunkIndex = -1,
                        errorMessage = t.message ?: "Auto subtitle generation paused due to an error."
                    )
                }
            } finally {
                if (generationId == seekGenerationId.get()) {
                    activeProcessingWindow = null
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
    }

    private suspend fun processSingleWindow(
        mediaUriString: String,
        windowStartMs: Long,
        windowDurationMs: Long,
        expectedGen: Long
    ) {
        // Stale Result Guard #1
        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        val initialSnapshot = _state.value
        val seqIndex = windowCounter++
        val isFastStart = windowDurationMs <= FAST_START_CHUNK_DURATION_MS + 500L
        activeProcessingWindow = windowStartMs..(windowStartMs + windowDurationMs)

        _state.value = initialSnapshot.copy(
            isGeneratingChunk = true,
            activeChunkIndex = seqIndex,
            errorMessage = null,
            statusMessage = if (isFastStart) {
                "Instant start (${initialSnapshot.targetLanguageLabel} · ${windowDurationMs / 1000L}s)..."
            } else {
                "Syncing speech (${initialSnapshot.targetLanguageLabel} · +${windowDurationMs / 1000L}s ahead)..."
            }
        )

        val pcmChunk = videoPcmAudioDecoder.decodeWindowTo16kPcm(
            mediaUriString = mediaUriString,
            startMs = windowStartMs,
            windowDurationMs = windowDurationMs,
            chunkIndex = seqIndex,
            preferredAudioTrackIndex = activeAudioTrackIndex,
            preferredAudioLanguageCode = audioTrackLanguageHint
        ).getOrThrow()

        // Stale Result Guard #2
        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        val actualStartMs = pcmChunk.startTimeMs
        val actualEndMs = (actualStartMs + pcmChunk.durationMs.coerceAtLeast(windowDurationMs))

        if (pcmChunk.samples16kMono.isEmpty() || !hasChunkSpeechActivity(pcmChunk.samples16kMono)) {
            synchronized(cuesLock) {
                if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return
                rawBlocksByWindow[windowStartMs] = emptyList()
                cachedSegmentsByWindow[windowStartMs] = emptyList()
                primaryCuesByWindow[windowStartMs] = emptyList()
                if (_state.value.dualSubtitlesEnabled) {
                    secondaryCuesByWindow[windowStartMs] = emptyList()
                }
                recordCoveredInterval(windowStartMs..actualEndMs)
            }
            return
        }

        val preWhisperSnapshot = _state.value
        val needsEnglishBridge = preWhisperSnapshot.targetLanguageCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL ||
            (preWhisperSnapshot.dualSubtitlesEnabled &&
                preWhisperSnapshot.secondaryLanguageCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL)

        val whisperLangParam = buildWhisperLanguageParam(
            sourceLanguageHint = audioTrackLanguageHint,
            translateToEnglish = needsEnglishBridge
        )

        val rawSegments = whisperSpeechEngine.transcribePcmChunkSuspending(
            chunk = pcmChunk,
            language = whisperLangParam
        ).getOrThrow()

        // Stale Result Guard #3
        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        // Stage 1: Sanitize + Speech-Synced VAD trim + Raw Consecutive Duplicate Suppressor
        val chunkNoiseFloor = estimateChunkNoiseFloor(pcmChunk.samples16kMono)
        val vadSegments = mutableListOf<VadTimedSegment>()
        for (seg in rawSegments) {
            coroutineContext.ensureActive()
            if (expectedGen != seekGenerationId.get()) return
            val cleaned = SubtitleSanitizer.sanitize(seg.originalText) ?: continue
            val rawStart = seg.startTimeMs.coerceIn(actualStartMs, actualEndMs)
            val rawEnd = seg.endTimeMs.coerceIn(rawStart + 350L, actualEndMs + 500L)
            val wordCount = cleaned.split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
            val trimmedBounds = trimSegmentWithVad(
                pcm16k = pcmChunk.samples16kMono,
                chunkStartMs = actualStartMs,
                segmentStartMs = rawStart,
                segmentEndMs = rawEnd,
                noiseFloorRms = chunkNoiseFloor,
                wordCount = wordCount
            ) ?: continue

            val normalizedRaw = normalizeForDuplicateCheck(cleaned)
            val isConsecutiveDuplicate = normalizedRaw.isNotEmpty() &&
                normalizedRaw == lastEmittedRawNormalized &&
                trimmedBounds.first >= lastEmittedRawEndMs - 500L
            if (isConsecutiveDuplicate) {
                continue
            }
            lastEmittedRawNormalized = normalizedRaw
            lastEmittedRawEndMs = trimmedBounds.second

            vadSegments.add(
                VadTimedSegment(
                    startTimeMs = trimmedBounds.first,
                    endTimeMs = trimmedBounds.second,
                    text = cleaned
                )
            )
        }

        // Stage 2: Smart Sentence Merging before translation for natural grammatical accuracy
        val mergedSentences = mergeIncompleteSentences(vadSegments)

        // Read latest target language in case user switched language while Whisper was decoding
        val latestSnapshot = _state.value
        val targetCode = latestSnapshot.targetLanguageCode
        val targetLabel = latestSnapshot.targetLanguageLabel
        val dualEnabled = latestSnapshot.dualSubtitlesEnabled
        val secondaryCode = latestSnapshot.secondaryLanguageCode

        val primaryFallbackTranslator = if (needsMlKitTranslation(targetCode)) {
            getOrCreateTranslator(targetCode)
        } else null

        val secondaryFallbackTranslator = if (dualEnabled && needsMlKitTranslation(secondaryCode)) {
            getOrCreateTranslator(secondaryCode)
        } else null

        // Stage 3: Single-Request Batched Online Neural Translation Primary + Offline ML Kit Fallback
        val translatedPrimaryList = translateBlocksBatched(
            blocks = mergedSentences,
            targetCode = targetCode,
            mlKitFallback = primaryFallbackTranslator
        )

        // Stale Result Guard #4
        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        val translatedSecondaryList = if (dualEnabled) {
            if (secondaryCode.equals(targetCode, ignoreCase = true)) {
                translatedPrimaryList
            } else {
                translateBlocksBatched(
                    blocks = mergedSentences,
                    targetCode = secondaryCode,
                    mlKitFallback = secondaryFallbackTranslator
                )
            }
        } else {
            emptyList()
        }

        // Stale Result Guard #5 (before committing to cache or emitting to UI)
        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        val generatedPrimary = mutableListOf<SubtitleCue>()
        val generatedSecondary = mutableListOf<SubtitleCue>()
        val cachedSegments = mutableListOf<CachedSubtitleSegment>()

        for (i in mergedSentences.indices) {
            val block = mergedSentences[i]
            val primaryTranslated = translatedPrimaryList[i]
            cachedSegments.add(
                CachedSubtitleSegment(
                    startTimeMs = block.startTimeMs,
                    endTimeMs = block.endTimeMs,
                    rawOriginalText = block.mergedText,
                    translatedText = primaryTranslated
                )
            )
            generatedPrimary.addAll(mapTranslatedSentenceToCues(block, primaryTranslated))
            if (dualEnabled && i < translatedSecondaryList.size) {
                generatedSecondary.addAll(mapTranslatedSentenceToCues(block, translatedSecondaryList[i]))
            }
        }

        val nonOverlappingPrimary = enforceStrictNonOverlap(generatedPrimary)
        val nonOverlappingSecondary = enforceStrictNonOverlap(generatedSecondary)

        synchronized(cuesLock) {
            if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return
            if (needsEnglishBridge) {
                cachedWindowsUsedEnglishBridge = true
            }
            rawBlocksByWindow[windowStartMs] = mergedSentences
            cachedSegmentsByWindow[windowStartMs] = cachedSegments
            primaryCuesByWindow[windowStartMs] = nonOverlappingPrimary
            sortedPrimaryCues = suppressConsecutiveDuplicateCues(
                enforceStrictNonOverlap(
                    primaryCuesByWindow.values.flatten().sortedBy { it.startTimeMs }
                )
            )
            if (dualEnabled) {
                secondaryCuesByWindow[windowStartMs] = nonOverlappingSecondary
                sortedSecondaryCues = suppressConsecutiveDuplicateCues(
                    enforceStrictNonOverlap(
                        secondaryCuesByWindow.values.flatten().sortedBy { it.startTimeMs }
                    )
                )
            } else {
                secondaryCuesByWindow.clear()
                sortedSecondaryCues = emptyList()
            }
            recordCoveredInterval(windowStartMs..actualEndMs)
        }

        if (expectedGen != seekGenerationId.get() || activeMediaUri != mediaUriString) return

        // Immediately publish translated chunk to UI state flow
        val modeLabel = if (dualEnabled) "$targetLabel + ${latestSnapshot.secondaryLanguageLabel}" else targetLabel
        _state.value = _state.value.copy(
            completedChunksCount = primaryCuesByWindow.size,
            generatedCueCount = sortedPrimaryCues.size,
            translationRevision = _state.value.translationRevision + 1,
            statusMessage = "Live Auto Subtitles ($modeLabel) · ${sortedPrimaryCues.size} lines synced"
        )
    }

    private suspend fun translateBlocksBatched(
        blocks: List<MergedSentenceBlock>,
        targetCode: String,
        mlKitFallback: Translator?,
        sourceIsAlreadyEnglish: Boolean = true
    ): List<String> {
        if (blocks.isEmpty()) return emptyList()
        if (targetCode == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) {
            return blocks.map { it.mergedText }
        }
        if (sourceIsAlreadyEnglish &&
            targetCode.equals(AutoSubtitleLanguageCatalog.CODE_ENGLISH, ignoreCase = true)
        ) {
            return blocks.map { it.mergedText }
        }

        // 1. Single-request batched Online Neural Translation (2.5s timeout + memory cache)
        val rawLines = blocks.map { it.mergedText }
        val batchResults = neuralTranslator.translateBatch(rawLines, targetCode)

        // 2. Fallback per-line ONLY if batch split mismatched or timed out
        val resolved = ArrayList<String>(blocks.size)
        for (i in blocks.indices) {
            coroutineContext.ensureActive()
            val batchHit = batchResults.getOrNull(i)
            if (!batchHit.isNullOrBlank()) {
                resolved.add(batchHit)
            } else {
                resolved.add(
                    translateSentenceWithNeuralPrimary(
                        text = rawLines[i],
                        targetCode = targetCode,
                        mlKitFallback = mlKitFallback,
                        sourceIsAlreadyEnglish = sourceIsAlreadyEnglish
                    )
                )
            }
        }
        return resolved
    }

    private suspend fun translateSentenceWithNeuralPrimary(
        text: String,
        targetCode: String,
        mlKitFallback: Translator?,
        sourceIsAlreadyEnglish: Boolean = true
    ): String {
        if (targetCode == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) return text
        if (sourceIsAlreadyEnglish &&
            targetCode.equals(AutoSubtitleLanguageCatalog.CODE_ENGLISH, ignoreCase = true)
        ) {
            return text
        }
        // 1. Online Neural Translation Primary (natural grammar + memory cached)
        val neuralResult = neuralTranslator.translate(text, targetCode)
        if (!neuralResult.isNullOrBlank()) {
            return neuralResult
        }
        // 2. Seamless Offline ML Kit Fallback
        if (mlKitFallback != null) {
            return runCatching { mlKitFallback.translate(text).await().trim() }
                .getOrDefault(text)
                .ifBlank { text }
        }
        return text
    }

    private fun recordCoveredInterval(range: LongRange) {
        coveredIntervals.add(range)
        val merged = mergeCoveredIntervals(coveredIntervals)
        coveredIntervals.clear()
        coveredIntervals.addAll(merged)
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

            if (activeMediaUri.isNotBlank()) {
                val mirrorFile = cacheFileFor(activeMediaUri, langCode)
                mirrorFile.writeText(srtContent, Charsets.UTF_8)
            }

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
            rawBlocksByWindow.clear()
            cachedSegmentsByWindow.clear()
            primaryCuesByWindow.clear()
            secondaryCuesByWindow.clear()
            coveredIntervals.clear()
            sortedPrimaryCues = emptyList()
            sortedSecondaryCues = emptyList()
            cachedWindowsUsedEnglishBridge = false
        }
        windowCounter = 0
        lastEmittedRawNormalized = null
        lastEmittedRawEndMs = Long.MIN_VALUE
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
        seekGenerationId.incrementAndGet()
        setupJob?.cancel()
        retranslateJob?.cancel()
        pauseProcessing()
        clearGeneratedCues()
        neuralTranslator.clearCache()
        activeTranslators.values.forEach { runCatching { it.close() } }
        activeTranslators.clear()
    }

    companion object {
        const val FAST_START_CHUNK_DURATION_MS: Long = 6_000L
        const val ROLLING_CHUNK_DURATION_MS: Long = 18_000L
        const val MAX_LOOKAHEAD_MS: Long = 60_000L
        const val SEEK_DEBOUNCE_MS: Long = 350L
        const val DRIFT_WATCHDOG_THRESHOLD_MS: Long = 3_000L
        const val CHUNK_DURATION_MS: Long = ROLLING_CHUNK_DURATION_MS
        const val SAMPLE_RATE_HZ: Int = 16_000
        private const val MAX_MERGED_SENTENCE_WORDS: Int = 18
        private const val SENTENCE_MERGE_MAX_GAP_MS: Long = 600L
        private const val VAD_FRAME_MS: Int = 50
        private const val VAD_FRAME_SAMPLES: Int = (SAMPLE_RATE_HZ * VAD_FRAME_MS) / 1000 // 800 samples (50ms)
        private const val VAD_CONSECUTIVE_ONSET_FRAMES: Int = 2 // 2 * 50ms = 100ms speech onset
        const val PRE_WHISPER_SPEECH_RMS_THRESHOLD: Float = 0.014f
        private const val MIN_SPEECH_RMS_THRESHOLD: Float = 0.008f
        private const val LEADING_SILENCE_TRIM_MIN_GAP_MS: Long = 1_200L

        private val SENTENCE_END_CHARS = setOf('.', '?', '!', '।', '。', '？', '！')
        private val DUPLICATE_NORM_STRIP_REGEX = Regex("[\\p{P}\\p{S}\\s]+")

        /**
         * Pre-Whisper Speech Gate (v2.6.1):
         * Computes 50ms frame RMS across the 16kHz PCM chunk. Skips Whisper completely if the
         * chunk's active energy (90th-percentile frame RMS and sustained speech frames) falls below
         * [thresholdRms] (`0.014f`), eliminating hallucinations during silence or quiet BGM scenes.
         */
        fun hasChunkSpeechActivity(
            pcm16k: FloatArray,
            thresholdRms: Float = PRE_WHISPER_SPEECH_RMS_THRESHOLD
        ): Boolean {
            if (pcm16k.size < VAD_FRAME_SAMPLES) return false
            val frameCount = pcm16k.size / VAD_FRAME_SAMPLES
            val energies = FloatArray(frameCount)
            var peakRms = 0f
            var consecutiveAbove = 0
            var hasSustainedSpeech = false

            for (f in 0 until frameCount) {
                val base = f * VAD_FRAME_SAMPLES
                var sumSq = 0.0
                for (i in 0 until VAD_FRAME_SAMPLES) {
                    val s = pcm16k[base + i].toDouble()
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / VAD_FRAME_SAMPLES).toFloat()
                energies[f] = rms
                if (rms > peakRms) peakRms = rms
                if (rms >= thresholdRms) {
                    consecutiveAbove++
                    if (consecutiveAbove >= 3) { // >= 150ms sustained active audio
                        hasSustainedSpeech = true
                    }
                } else {
                    consecutiveAbove = 0
                }
            }

            if (peakRms < thresholdRms) return false
            energies.sort()
            val p90Index = ((frameCount - 1) * 0.90f).toInt().coerceIn(0, frameCount - 1)
            val p90Rms = energies[p90Index]
            return p90Rms >= thresholdRms || hasSustainedSpeech
        }

        /**
         * Normalizes subtitle text (Unicode letters/digits only, lowercased) so repeated
         * hallucinations such as `"আমি দুঃখিত।"`, `"আমি দুঃখিত"`, `"I'm sorry!"`, `"I'm sorry."`
         * match reliably regardless of trailing punctuation or whitespace.
         */
        fun normalizeForDuplicateCheck(text: String): String {
            return text
                .trim()
                .lowercase(Locale.ROOT)
                .replace(DUPLICATE_NORM_STRIP_REGEX, "")
        }

        /**
         * Consecutive Duplicate Suppressor (v2.6.1):
         * Drops any subtitle cue whose normalized text repeats the immediately preceding emitted
         * cue (2+ consecutive identical lines across adjacent segments or chunk boundaries),
         * keeping silent/BGM scenes clean while allowing genuine lines to reappear after
         * different spoken dialogue occurs in between.
         */
        fun suppressConsecutiveDuplicateCues(cues: List<SubtitleCue>): List<SubtitleCue> {
            if (cues.size <= 1) return cues
            val filtered = ArrayList<SubtitleCue>(cues.size)
            var lastNormalized: String? = null
            for (cue in cues) {
                val norm = normalizeForDuplicateCheck(cue.text)
                if (norm.isNotEmpty() && norm == lastNormalized) {
                    continue
                }
                filtered.add(cue)
                if (norm.isNotEmpty()) {
                    lastNormalized = norm
                }
            }
            return filtered
        }

        fun needsMlKitTranslation(targetCode: String): Boolean {
            return targetCode != AutoSubtitleLanguageCatalog.CODE_ORIGINAL &&
                !targetCode.equals(AutoSubtitleLanguageCatalog.CODE_ENGLISH, ignoreCase = true)
        }

        fun chunkIndexForPositionMs(positionMs: Long): Int {
            return (positionMs.coerceAtLeast(0L) / ROLLING_CHUNK_DURATION_MS).toInt()
        }

        fun isPositionCovered(positionMs: Long, covered: List<LongRange>): Boolean {
            val pos = positionMs.coerceAtLeast(0L)
            return covered.any { pos in it }
        }

        /**
         * Drift Watchdog predicate:
         * Returns `true` when [positionMs] is more than [thresholdMs] (default 3 seconds) outside
         * every cached interval AND no active processing window is already targeting [positionMs].
         */
        fun isPositionDriftedBeyondThreshold(
            positionMs: Long,
            covered: List<LongRange>,
            activeProcessingWindow: LongRange?,
            thresholdMs: Long = DRIFT_WATCHDOG_THRESHOLD_MS
        ): Boolean {
            val pos = positionMs.coerceAtLeast(0L)
            if (activeProcessingWindow != null) {
                val winDist = when {
                    pos < activeProcessingWindow.first -> activeProcessingWindow.first - pos
                    pos > activeProcessingWindow.last -> pos - activeProcessingWindow.last
                    else -> 0L
                }
                if (winDist <= thresholdMs) return false
            }
            if (covered.isEmpty()) return true
            var minDist = Long.MAX_VALUE
            for (range in covered) {
                val dist = when {
                    pos < range.first -> range.first - pos
                    pos > range.last -> pos - range.last
                    else -> 0L
                }
                if (dist == 0L) return false
                if (dist < minDist) minDist = dist
            }
            return minDist > thresholdMs
        }

        /**
         * Progressive Fast-Start Window Planner (v2.6.3):
         * 1. If [currentPositionMs] is NOT covered yet (initial enable, resume, or seek), returns
         *    a fast **6-second first chunk** `[currentPositionMs, 6_000L]` anchored strictly at `pos`.
         * 2. If [currentPositionMs] IS covered, plans the next **18-second rolling look-ahead chunk**
         *    immediately after the covering interval, strictly capped at max **60 seconds** ahead of
         *    [currentPositionMs] ([MAX_LOOKAHEAD_MS]).
         */
        fun planNextWindow(
            currentPositionMs: Long,
            covered: List<LongRange>,
            durationMs: Long
        ): Pair<Long, Long>? {
            val pos = currentPositionMs.coerceAtLeast(0L)
            if (durationMs > 0L && pos >= durationMs) return null

            val merged = mergeCoveredIntervals(covered)
            val coveringCurrent = merged.firstOrNull { pos in it }

            if (coveringCurrent == null) {
                // Fast-Start 6-second chunk right at current playback / seek position
                val nextStart = merged.firstOrNull { it.first > pos }?.first
                val maxEnd = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                val desiredEnd = minOf(pos + FAST_START_CHUNK_DURATION_MS, nextStart ?: maxEnd, maxEnd)
                val dur = (desiredEnd - pos).coerceAtLeast(0L)
                return if (dur >= 1_000L) pos to dur else null
            }

            // Current position is covered: roll 18s look-ahead strictly capped at max 60s ahead of live pos
            val lookAheadStart = coveringCurrent.last
            if (durationMs > 0L && lookAheadStart >= durationMs) return null
            val maxLookAheadEnd = pos + MAX_LOOKAHEAD_MS
            if (lookAheadStart >= maxLookAheadEnd) return null

            val nextCoveredStart = merged.firstOrNull { it.first > lookAheadStart }?.first
            val maxEnd = if (durationMs > 0L) durationMs else Long.MAX_VALUE
            val desiredEnd = minOf(
                lookAheadStart + ROLLING_CHUNK_DURATION_MS,
                maxLookAheadEnd,
                nextCoveredStart ?: maxEnd,
                maxEnd
            )
            val dur = (desiredEnd - lookAheadStart).coerceAtLeast(0L)
            return if (dur >= 1_000L) lookAheadStart to dur else null
        }

        fun mergeCoveredIntervals(intervals: List<LongRange>): List<LongRange> {
            if (intervals.size <= 1) return intervals.toList()
            val sorted = intervals.sortedBy { it.first }
            val result = mutableListOf(sorted.first())
            for (i in 1 until sorted.size) {
                val cur = sorted[i]
                val prev = result.last()
                if (cur.first <= prev.last + 250L) {
                    result[result.lastIndex] = prev.first..maxOf(prev.last, cur.last)
                } else {
                    result.add(cur)
                }
            }
            return result
        }

        /**
         * Stage 1 Smart Sentence Merging before translation:
         * Joins consecutive Whisper segments belonging to the same spoken sentence when:
         * - Previous text does NOT end with sentence-ending punctuation (`.`, `?`, `!`, `।`, `。`, `？`, `！`),
         * - Time gap to next segment is `< 600ms`, AND
         * - Combined word count does not exceed 18 words.
         */
        fun mergeIncompleteSentences(segments: List<VadTimedSegment>): List<MergedSentenceBlock> {
            if (segments.isEmpty()) return emptyList()
            val blocks = mutableListOf<MergedSentenceBlock>()
            var currentGroup = mutableListOf(segments.first())
            var currentText = segments.first().text.trim()
            var currentWords = countWords(currentText)

            for (i in 1 until segments.size) {
                val next = segments[i]
                val prev = currentGroup.last()
                val gapMs = next.startTimeMs - prev.endTimeMs
                val nextText = next.text.trim()
                val nextWords = countWords(nextText)
                val prevTerminated = endsWithSentencePunctuation(currentText)

                if (!prevTerminated &&
                    gapMs in -250L until SENTENCE_MERGE_MAX_GAP_MS &&
                    (currentWords + nextWords) <= MAX_MERGED_SENTENCE_WORDS
                ) {
                    currentGroup.add(next)
                    currentText = "$currentText $nextText"
                    currentWords += nextWords
                } else {
                    blocks.add(MergedSentenceBlock(currentText, currentGroup.toList()))
                    currentGroup = mutableListOf(next)
                    currentText = nextText
                    currentWords = nextWords
                }
            }
            blocks.add(MergedSentenceBlock(currentText, currentGroup.toList()))
            return blocks
        }

        /**
         * Maps a translated merged sentence cleanly back to constituent VAD-trimmed timestamps.
         * Keeps single-segment or short (<4.5s) sentences intact, and splits longer multi-segment
         * translations proportionally across constituent timings so subtitles remain concise and synced.
         */
        fun mapTranslatedSentenceToCues(
            block: MergedSentenceBlock,
            translatedText: String
        ): List<SubtitleCue> {
            val clean = translatedText.trim()
            if (clean.isBlank()) return emptyList()
            val parts = block.constituents
            if (parts.size == 1 || (block.endTimeMs - block.startTimeMs) <= 4_500L) {
                return listOf(
                    SubtitleCue(
                        startTimeMs = block.startTimeMs,
                        endTimeMs = block.endTimeMs,
                        text = clean
                    )
                )
            }

            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < parts.size) {
                return listOf(
                    SubtitleCue(
                        startTimeMs = block.startTimeMs,
                        endTimeMs = block.endTimeMs,
                        text = clean
                    )
                )
            }

            val totalWeight = parts.sumOf { (it.endTimeMs - it.startTimeMs).coerceAtLeast(300L) }.toDouble()
            val cues = mutableListOf<SubtitleCue>()
            var wordCursor = 0
            for (idx in parts.indices) {
                val seg = parts[idx]
                val remainingSegments = parts.size - idx
                val remainingWords = words.size - wordCursor
                val takeCount = if (idx == parts.lastIndex) {
                    remainingWords
                } else {
                    val weight = (seg.endTimeMs - seg.startTimeMs).coerceAtLeast(300L).toDouble() / totalWeight
                    (words.size * weight).roundToInt().coerceIn(1, (remainingWords - (remainingSegments - 1)).coerceAtLeast(1))
                }
                val slice = words.subList(wordCursor, (wordCursor + takeCount).coerceAtMost(words.size))
                wordCursor += slice.size
                if (slice.isNotEmpty()) {
                    cues.add(
                        SubtitleCue(
                            startTimeMs = seg.startTimeMs,
                            endTimeMs = seg.endTimeMs,
                            text = slice.joinToString(" ")
                        )
                    )
                }
            }
            return cues
        }

        private fun endsWithSentencePunctuation(text: String): Boolean {
            val trimmed = text.trimEnd('"', '\'', ')', ']', ' ')
            if (trimmed.isEmpty()) return false
            return trimmed.last() in SENTENCE_END_CHARS
        }

        private fun countWords(text: String): Int {
            return text.split(Regex("\\s+")).count { it.isNotBlank() }
        }

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
            val p35Index = (frameCount * 0.35f).toInt().coerceIn(0, frameCount - 1)
            return energies[p35Index].coerceIn(0.002f, 0.060f)
        }

        fun trimSegmentWithVad(
            pcm16k: FloatArray,
            chunkStartMs: Long,
            segmentStartMs: Long,
            segmentEndMs: Long,
            noiseFloorRms: Float = 0.006f,
            wordCount: Int = 0
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

            if (peakRms < MIN_SPEECH_RMS_THRESHOLD) return null

            val speechThreshold = maxOf(
                MIN_SPEECH_RMS_THRESHOLD,
                noiseFloorRms * 1.45f
            )
            val effectiveThreshold = minOf(
                maxOf(speechThreshold, peakRms * 0.14f),
                peakRms * 0.80f
            )

            var firstActive = -1
            val requiredRun = VAD_CONSECUTIVE_ONSET_FRAMES.coerceAtMost(frameCount)
            var runLen = 0
            for (f in 0 until frameCount) {
                if (frameRms[f] >= effectiveThreshold) {
                    runLen++
                    if (runLen >= requiredRun) {
                        firstActive = f - requiredRun + 1
                        break
                    }
                } else {
                    runLen = 0
                }
            }
            if (firstActive < 0) {
                firstActive = frameRms.indexOfFirst { it >= effectiveThreshold }
            }
            if (firstActive < 0) return null

            var lastActive = firstActive
            for (f in frameCount - 1 downTo firstActive) {
                if (frameRms[f] >= effectiveThreshold * 0.80f) {
                    lastActive = f
                    break
                }
            }

            val detectedLeadingSilenceMs = firstActive * VAD_FRAME_MS.toLong()
            var speechStartMs = if (detectedLeadingSilenceMs > LEADING_SILENCE_TRIM_MIN_GAP_MS) {
                (chunkStartMs + relStartMs + detectedLeadingSilenceMs).coerceAtLeast(segmentStartMs)
            } else {
                segmentStartMs
            }

            val speechEndMs = (chunkStartMs + relStartMs + ((lastActive + 1) * VAD_FRAME_MS.toLong()) + 150L)
                .coerceAtMost(segmentEndMs)
                .coerceAtLeast(speechStartMs + 300L)

            if (wordCount > 0) {
                val expectedMs = (wordCount * 450L).coerceIn(1_000L, 6_500L)
                if ((speechEndMs - speechStartMs) > expectedMs + LEADING_SILENCE_TRIM_MIN_GAP_MS) {
                    speechStartMs = maxOf(speechStartMs, speechEndMs - expectedMs - 400L)
                        .coerceAtMost(speechEndMs - 300L)
                }
            }

            return speechStartMs to speechEndMs
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

        fun buildWhisperLanguageParam(
            sourceLanguageHint: String?,
            translateToEnglish: Boolean
        ): String {
            val baseLang = sourceLanguageHint
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() && it != "und" }
                ?: "auto"
            return if (translateToEnglish && baseLang != "en") {
                "$baseLang:translate"
            } else {
                baseLang
            }
        }

        fun cleanWhisperText(raw: String): String {
            return SubtitleSanitizer.sanitize(raw).orEmpty()
        }

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
