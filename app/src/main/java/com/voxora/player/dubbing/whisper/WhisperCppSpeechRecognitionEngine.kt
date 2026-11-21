package com.voxora.player.dubbing.whisper

import android.content.Context
import android.net.Uri
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.dubbing.RecognizedSpeechSegment
import com.voxora.player.dubbing.SpeechRecognitionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class WhisperTranscriptionState(
    val isRunning: Boolean = false,
    val phaseLabel: String = "Idle",
    val progressFraction: Float = 0f,
    val activeVideoUri: String = "",
    val activeVideoTitle: String = "",
    val processedDurationMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val currentChunkIndex: Int = 0,
    val segments: List<RecognizedSpeechSegment> = emptyList(),
    val fullTranscriptText: String = "",
    val errorMessage: String? = null,
    val exportFeedback: String? = null
)

/**
 * Real offline English Speech Recognition engine backed by native `whisper.cpp` (C++/JNI).
 *
 * Strict integrity guarantees:
 * - Zero cloud calls, zero paid APIs, zero fabricated transcription.
 * - [isModelLoadedAndReady] ONLY returns true after the real native `libvoxora_whisper.so`
 *   loads, the model file passes GGML header/SHA-256 verification, and `nativeInitContext`
 *   returns a valid native `whisper_context*` handle.
 * - Processes local videos in 30-second 16 kHz mono PCM chunks on background coroutines so
 *   the UI thread and Media3 video playback remain smooth.
 */
/**
 * Process-wide singleton holder for the initialized native `whisper_context*` pointer.
 * Keeps `ggml-base-q5_1.bin` warm in memory across CC toggles, seeks, and player sessions so
 * enabling subtitles never pays a disk reload penalty.
 */
object WhisperNativeContextHolder {
    val mutex = Mutex()

    @Volatile
    var contextPtr: Long = 0L

    @Volatile
    var loadedModel: InstalledWhisperModel? = null

    @Volatile
    var nativeSystemInfo: String = "whisper.cpp v1.9.5 CPU"

    fun isLoadedForPath(absolutePath: String): Boolean {
        return contextPtr != 0L && loadedModel?.absolutePath == absolutePath
    }
}

class WhisperCppSpeechRecognitionEngine(
    context: Context,
    private val modelManager: WhisperModelManager = WhisperModelManager(context),
    private val videoDecoder: VideoPcmAudioDecoder = VideoPcmAudioDecoder(context)
) : SpeechRecognitionEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nativeContextMutex: Mutex
        get() = WhisperNativeContextHolder.mutex

    private var nativeContextPtr: Long
        get() = WhisperNativeContextHolder.contextPtr
        set(value) {
            WhisperNativeContextHolder.contextPtr = value
        }

    private var loadedModelInfo: InstalledWhisperModel?
        get() = WhisperNativeContextHolder.loadedModel
        set(value) {
            WhisperNativeContextHolder.loadedModel = value
        }

    private var activeModelJob: Job? = null
    private var activeTranscriptionJob: Job? = null

    private val _modelStatus = MutableStateFlow<WhisperModelStatus>(
        WhisperNativeContextHolder.loadedModel?.takeIf { WhisperNativeContextHolder.contextPtr != 0L }?.let {
            WhisperModelStatus.LoadedReady(it, WhisperNativeContextHolder.nativeSystemInfo)
        } ?: WhisperModelStatus.NotInstalled
    )
    val modelStatus: StateFlow<WhisperModelStatus> = _modelStatus.asStateFlow()

    private val _transcriptionState = MutableStateFlow(WhisperTranscriptionState())
    val transcriptionState: StateFlow<WhisperTranscriptionState> = _transcriptionState.asStateFlow()

    override val engineName: String = "whisper.cpp (On-Device ARM64 JNI)"

    override val isModelInstalled: Boolean
        get() = when (_modelStatus.value) {
            is WhisperModelStatus.InstalledUnloaded,
            is WhisperModelStatus.Loading,
            is WhisperModelStatus.LoadedReady -> true
            is WhisperModelStatus.Error -> (_modelStatus.value as WhisperModelStatus.Error).installedModel != null
            else -> false
        }

    /**
     * Checks whether a verified Whisper model file exists on disk in app storage.
     * Unlike [isModelLoadedAndReady], this NEVER flips to false when native RAM is unloaded
     * between dubbing stages.
     */
    fun isModelInstalledOnDisk(): Boolean = isModelInstalled || modelManager.hasInstalledModelOnDisk()

    val isModelLoadedAndReady: Boolean
        get() = nativeContextPtr != 0L && loadedModelInfo != null

    init {
        engineScope.launch {
            refreshInstalledModelStatus()
        }
    }

    suspend fun refreshInstalledModelStatus() {
        val current = _modelStatus.value
        if (current is WhisperModelStatus.Downloading ||
            current is WhisperModelStatus.Importing ||
            current is WhisperModelStatus.Loading ||
            current is WhisperModelStatus.LoadedReady
        ) {
            return
        }

        val installed = modelManager.findInstalledModel()
        _modelStatus.value = if (installed != null) {
            WhisperModelStatus.InstalledUnloaded(installed)
        } else {
            WhisperModelStatus.NotInstalled
        }
    }

    fun startDownloadBaseEnModel() {
        if (activeModelJob?.isActive == true) return
        activeModelJob = engineScope.launch {
            ensureBaseEnModelReady()
        }
    }

    /**
     * Downloads (if missing) and loads the default fast Whisper model (`ggml-tiny.en-q5_1.bin`, ~31 MB)
     * sequentially on [Dispatchers.IO]. Used both by the Transcribe tab and One-Click AI Setup.
     */
    suspend fun ensureBaseEnModelReady(
        onProgressPercent: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val spec = WhisperModelCatalog.DEFAULT
        if (isModelLoadedAndReady && loadedModelInfo?.fileName.equals(spec.fileName, ignoreCase = true)) {
            onProgressPercent(100)
            return@withContext Result.success(Unit)
        }

        val preferredFile = modelManager.getDefaultModelFile(spec)
        val alreadyInstalled = if (preferredFile.exists() && preferredFile.isFile) {
            modelManager.findInstalledModel(spec)
        } else {
            null
        }
        if (alreadyInstalled != null && alreadyInstalled.fileName.equals(spec.fileName, ignoreCase = true)) {
            onProgressPercent(85)
            loadInstalledModelInternal(alreadyInstalled)
            return@withContext if (isModelLoadedAndReady) {
                onProgressPercent(100)
                Result.success(Unit)
            } else {
                val errMsg = (_modelStatus.value as? WhisperModelStatus.Error)?.message
                    ?: "Failed to load installed Whisper model."
                Result.failure(IllegalStateException(errMsg))
            }
        }
        _modelStatus.value = WhisperModelStatus.Downloading(
            progressFraction = 0f,
            downloadedBytes = 0L,
            totalBytes = spec.expectedSizeBytes
        )
        onProgressPercent(0)

        val downloadResult = modelManager.downloadModel(
            spec = spec,
            onProgress = { downloaded, total ->
                val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                _modelStatus.value = WhisperModelStatus.Downloading(
                    progressFraction = frac,
                    downloadedBytes = downloaded,
                    totalBytes = total
                )
                onProgressPercent((frac * 90f).toInt().coerceIn(0, 90))
            },
            onVerifying = {
                _modelStatus.value = WhisperModelStatus.Verifying("Verifying GGML header & SHA-256 checksum...")
                onProgressPercent(92)
            }
        )

        downloadResult.fold(
            onSuccess = { installed ->
                onProgressPercent(95)
                loadInstalledModelInternal(installed)
                if (isModelLoadedAndReady) {
                    onProgressPercent(100)
                    Result.success(Unit)
                } else {
                    val errMsg = (_modelStatus.value as? WhisperModelStatus.Error)?.message
                        ?: "Whisper model downloaded, but native initialization failed."
                    Result.failure(IllegalStateException(errMsg))
                }
            },
            onFailure = { err ->
                if (err is CancellationException) {
                    refreshInstalledModelStatus()
                    throw err
                } else {
                    _modelStatus.value = WhisperModelStatus.Error(
                        message = err.message ?: "Failed to download Whisper ${spec.displayName} model."
                    )
                    Result.failure(err)
                }
            }
        )
    }

    fun cancelModelOperation() {
        activeModelJob?.cancel()
        activeModelJob = null
        engineScope.launch { refreshInstalledModelStatus() }
    }

    fun importLocalModelFile(uri: Uri, displayName: String?) {
        if (activeModelJob?.isActive == true) return
        activeModelJob = engineScope.launch {
            stopTranscription()
            unloadModelInternal()
            _modelStatus.value = WhisperModelStatus.Importing(progressFraction = 0f, copiedBytes = 0L)
            modelManager.importLocalModel(
                sourceUri = uri,
                suggestedFileName = displayName,
                onProgress = { copied ->
                    val estimated = WhisperModelCatalog.DEFAULT.expectedSizeBytes
                    val frac = (copied.toFloat() / estimated.toFloat()).coerceIn(0f, 0.99f)
                    _modelStatus.value = WhisperModelStatus.Importing(
                        progressFraction = frac,
                        copiedBytes = copied
                    )
                },
                onVerifying = {
                    _modelStatus.value = WhisperModelStatus.Verifying("Verifying local Whisper model file integrity...")
                }
            ).fold(
                onSuccess = { installed ->
                    loadInstalledModelInternal(installed)
                },
                onFailure = { err ->
                    _modelStatus.value = WhisperModelStatus.Error(
                        message = err.message ?: "Local model import failed."
                    )
                }
            )
        }
    }

    fun loadInstalledModel() {
        if (activeModelJob?.isActive == true) return
        activeModelJob = engineScope.launch {
            val installed = modelManager.findInstalledModel()
            if (installed == null) {
                _modelStatus.value = WhisperModelStatus.NotInstalled
                return@launch
            }
            loadInstalledModelInternal(installed)
        }
    }

    fun unloadModel() {
        engineScope.launch {
            stopTranscription()
            unloadNativeContextSuspending()
        }
    }

    /**
     * Loads the already-installed Whisper model into native memory if it is currently unloaded,
     * failing honestly if no model file exists on disk.
     */
    suspend fun ensureInstalledModelLoaded(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelLoadedAndReady) return@withContext Result.success(Unit)
        val installed = modelManager.findInstalledModel()
            ?: return@withContext Result.failure(
                IllegalStateException("Whisper offline model is not installed. Run One-Click AI Setup first.")
            )
        loadInstalledModelInternal(installed)
        if (isModelLoadedAndReady) {
            Result.success(Unit)
        } else {
            val err = (_modelStatus.value as? WhisperModelStatus.Error)?.message
                ?: "Failed to load Whisper native context."
            Result.failure(IllegalStateException(err))
        }
    }

    /**
     * Deterministically frees whisper.cpp native RAM between Auto-Dub pipeline stages
     * to prevent OOM on mid-range phones while keeping the on-disk model marked as installed.
     */
    suspend fun unloadNativeContextSuspending() = withContext(Dispatchers.IO) {
        val installed = loadedModelInfo ?: modelManager.findInstalledModel()
        unloadModelInternal()
        _modelStatus.value = if (installed != null) {
            WhisperModelStatus.InstalledUnloaded(installed)
        } else {
            WhisperModelStatus.NotInstalled
        }
    }

    /**
     * Runs real offline transcription and suspends until completion, supporting clean cancellation.
     */
    suspend fun transcribeVideoSuspending(
        mediaUriString: String,
        videoTitle: String,
        totalDurationMsHint: Long = 0L
    ): Result<List<RecognizedSpeechSegment>> {
        startVideoTranscription(mediaUriString, videoTitle, totalDurationMsHint)
        val job = activeTranscriptionJob
            ?: return Result.failure(
                IllegalStateException(
                    _transcriptionState.value.errorMessage ?: "Unable to start offline Whisper transcription."
                )
            )
        try {
            job.join()
        } catch (ce: CancellationException) {
            stopTranscription()
            throw ce
        }
        val finalState = _transcriptionState.value
        return if (!finalState.errorMessage.isNullOrBlank()) {
            Result.failure(IllegalStateException(finalState.errorMessage))
        } else {
            Result.success(finalState.segments)
        }
    }

    /**
     * Runs real offline Whisper inference on a single already-decoded [PcmAudioChunk]
     * without decoding the entire video into memory.
     *
     * @param language Whisper language parameter: `"auto"` for automatic multi-language
     *   transcription in original language, `"auto:translate"` to detect ANY language and
     *   translate directly to English inside whisper.cpp (enabling 59+ ML Kit target languages),
     *   or a specific ISO-639-1 code like `"en"`.
     */
    suspend fun transcribePcmChunkSuspending(
        chunk: PcmAudioChunk,
        language: String = "auto",
        onProgressPercent: (Int) -> Unit = {}
    ): Result<List<RecognizedSpeechSegment>> = withContext(Dispatchers.Default) {
        if (chunk.samples16kMono.isEmpty()) {
            return@withContext Result.success(emptyList())
        }
        val loadRes = ensureInstalledModelLoaded()
        if (loadRes.isFailure) {
            return@withContext Result.failure(
                loadRes.exceptionOrNull() ?: IllegalStateException("Whisper model failed to load.")
            )
        }

        WhisperNativeBridge.nativeResetAbort()
        val chunkSegments = mutableListOf<RecognizedSpeechSegment>()
        val counter = AtomicInteger(chunk.chunkIndex * 1000)

        try {
            coroutineContext.ensureActive()
            val rc = nativeContextMutex.withLock {
                val ptr = nativeContextPtr
                if (ptr == 0L) return@withLock -1
                val threads = WhisperNativeBridge.recommendedThreadCount()
                val callback = object : WhisperNativeCallback {
                    override fun onProgress(progressPercent: Int) {
                        onProgressPercent(progressPercent.coerceIn(0, 100))
                    }

                    override fun onNewSegment(startMs: Long, endMs: Long, text: String) {
                        val cleaned = text.trim()
                        if (cleaned.isEmpty()) return
                        val seg = RecognizedSpeechSegment(
                            id = "C${chunk.chunkIndex}_SEG_${counter.incrementAndGet().toString().padStart(4, '0')}",
                            startTimeMs = startMs.coerceAtLeast(chunk.startTimeMs),
                            endTimeMs = endMs.coerceAtLeast(startMs),
                            originalText = cleaned,
                            detectedLanguageCode = language.substringBefore(':'),
                            speakerId = "SPEAKER_01",
                            confidence = 1.0f
                        )
                        synchronized(chunkSegments) {
                            chunkSegments.add(seg)
                        }
                    }
                }
                WhisperNativeBridge.nativeTranscribeChunk(
                    contextPtr = ptr,
                    numThreads = threads,
                    audioData = chunk.samples16kMono,
                    chunkOffsetMs = chunk.startTimeMs,
                    language = language,
                    callback = callback
                )
            }
            if (rc == WhisperNativeBridge.RESULT_CANCELLED) {
                throw CancellationException("Chunk transcription cancelled.")
            }
            if (rc != 0) {
                return@withContext Result.failure(
                    IllegalStateException("whisper.cpp inference returned error code $rc on chunk ${chunk.chunkIndex + 1}.")
                )
            }
            onProgressPercent(100)
            Result.success(chunkSegments.toList())
        } catch (ce: CancellationException) {
            WhisperNativeBridge.nativeRequestAbort()
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Signals native whisper.cpp to abort any in-flight chunk transcription (e.g. on user seek).
     */
    fun requestAbortNativeInference() {
        WhisperNativeBridge.nativeRequestAbort()
    }

    fun deleteInstalledModel() {
        engineScope.launch {
            activeModelJob?.cancel()
            stopTranscription()
            unloadModelInternal()
            modelManager.deleteInstalledModels()
            _modelStatus.value = WhisperModelStatus.NotInstalled
        }
    }

    private suspend fun loadInstalledModelInternal(model: InstalledWhisperModel) {
        if (!WhisperNativeBridge.isNativeLibraryLoaded) {
            _modelStatus.value = WhisperModelStatus.Error(
                message = WhisperNativeBridge.loadError
                    ?: "Native whisper.cpp library is unavailable on this device ABI.",
                installedModel = model
            )
            return
        }

        // Fast-path: reuse already-loaded native context from WhisperNativeContextHolder (0ms)
        if (WhisperNativeContextHolder.isLoadedForPath(model.absolutePath)) {
            _modelStatus.value = WhisperModelStatus.LoadedReady(
                model = model,
                nativeSystemInfo = WhisperNativeContextHolder.nativeSystemInfo
            )
            return
        }

        _modelStatus.value = WhisperModelStatus.Loading(model)

        nativeContextMutex.withLock {
            if (WhisperNativeContextHolder.isLoadedForPath(model.absolutePath)) {
                _modelStatus.value = WhisperModelStatus.LoadedReady(
                    model = model,
                    nativeSystemInfo = WhisperNativeContextHolder.nativeSystemInfo
                )
                return@withLock
            }
            withContext(Dispatchers.Default) {
                if (nativeContextPtr != 0L) {
                    WhisperNativeBridge.nativeFreeContext(nativeContextPtr)
                    nativeContextPtr = 0L
                }

                // Double-check file exists before JNI init
                val modelFile = File(model.absolutePath)
                if (!modelFile.exists() || !modelFile.canRead()) {
                    _modelStatus.value = WhisperModelStatus.Error(
                        message = "Model file missing from app storage.",
                        installedModel = null
                    )
                    return@withContext
                }

                val ptr = WhisperNativeBridge.nativeInitContext(model.absolutePath)
                if (ptr == 0L) {
                    _modelStatus.value = WhisperModelStatus.Error(
                        message = "whisper.cpp could not load ${model.fileName}. Ensure it is a valid GGML Whisper model.",
                        installedModel = model
                    )
                } else {
                    nativeContextPtr = ptr
                    loadedModelInfo = model
                    val sysInfo = runCatching { WhisperNativeBridge.nativeGetSystemInfo() }
                        .getOrDefault("whisper.cpp v1.9.5 CPU")
                    WhisperNativeContextHolder.nativeSystemInfo = sysInfo
                    _modelStatus.value = WhisperModelStatus.LoadedReady(
                        model = model,
                        nativeSystemInfo = sysInfo
                    )
                }
            }
        }
    }

    private suspend fun unloadModelInternal() {
        nativeContextMutex.withLock {
            if (nativeContextPtr != 0L) {
                WhisperNativeBridge.nativeFreeContext(nativeContextPtr)
                nativeContextPtr = 0L
            }
            loadedModelInfo = null
        }
    }

    /**
     * Starts real offline English speech recognition on the active local video URI.
     * Decodes audio in 30-second 16 kHz mono Float32 chunks and streams timestamped English
     * segments as `whisper.cpp` decodes them.
     */
    fun startVideoTranscription(
        mediaUriString: String,
        videoTitle: String,
        totalDurationMsHint: Long = 0L
    ) {
        if (mediaUriString.isBlank()) {
            _transcriptionState.value = _transcriptionState.value.copy(
                errorMessage = "No local video is currently loaded."
            )
            return
        }
        if (!isModelLoadedAndReady) {
            _transcriptionState.value = _transcriptionState.value.copy(
                errorMessage = "Load the offline Whisper model first before starting transcription."
            )
            return
        }
        if (activeTranscriptionJob?.isActive == true) return

        WhisperNativeBridge.nativeResetAbort()
        val segmentCounter = AtomicInteger(0)

        _transcriptionState.value = WhisperTranscriptionState(
            isRunning = true,
            phaseLabel = "Extracting & decoding video audio to 16 kHz mono PCM...",
            progressFraction = 0.01f,
            activeVideoUri = mediaUriString,
            activeVideoTitle = videoTitle,
            totalDurationMs = totalDurationMsHint.coerceAtLeast(0L)
        )

        activeTranscriptionJob = engineScope.launch(Dispatchers.Default) {
            val accumulatedSegments = mutableListOf<RecognizedSpeechSegment>()
            var knownTotalMs = totalDurationMsHint.coerceAtLeast(0L)

            val decodeResult = videoDecoder.decodeVideoTo16kChunks(
                mediaUriString = mediaUriString,
                chunkDurationSeconds = VideoPcmAudioDecoder.DEFAULT_CHUNK_SECONDS,
                onDecodeProgress = { decodedMs, detectedTotalMs ->
                    if (detectedTotalMs > knownTotalMs) knownTotalMs = detectedTotalMs
                    _transcriptionState.value = _transcriptionState.value.copy(
                        totalDurationMs = knownTotalMs,
                        processedDurationMs = decodedMs.coerceAtMost(knownTotalMs.takeIf { it > 0L } ?: decodedMs)
                    )
                },
                onChunkDecoded = { chunk ->
                    coroutineContext.ensureActive()

                    val chunkNum = chunk.chunkIndex + 1
                    _transcriptionState.value = _transcriptionState.value.copy(
                        phaseLabel = "Recognizing English speech (Segment $chunkNum)...",
                        currentChunkIndex = chunkNum
                    )

                    val rc = runNativeChunkInference(
                        chunk = chunk,
                        knownTotalDurationMs = knownTotalMs,
                        segmentCounter = segmentCounter,
                        accumulatedSegments = accumulatedSegments
                    )

                    if (rc == WhisperNativeBridge.RESULT_CANCELLED) {
                        throw CancellationException("Transcription stopped by user.")
                    }
                    if (rc != 0) {
                        throw IllegalStateException("whisper.cpp inference returned error code $rc on segment $chunkNum.")
                    }

                    val completedMs = chunk.startTimeMs + chunk.durationMs
                    val overallProgress = if (knownTotalMs > 0L) {
                        (completedMs.toFloat() / knownTotalMs.toFloat()).coerceIn(0.02f, 0.99f)
                    } else {
                        0.5f
                    }
                    _transcriptionState.value = _transcriptionState.value.copy(
                        processedDurationMs = completedMs,
                        progressFraction = overallProgress
                    )
                }
            )

            decodeResult.fold(
                onSuccess = { finalDurationMs ->
                    val effectiveTotal = maxOf(knownTotalMs, finalDurationMs)
                    _transcriptionState.value = _transcriptionState.value.copy(
                        isRunning = false,
                        phaseLabel = if (accumulatedSegments.isEmpty()) {
                            "Completed · No English speech detected in audio"
                        } else {
                            "Completed · ${accumulatedSegments.size} English segments recognized offline"
                        },
                        progressFraction = 1f,
                        processedDurationMs = effectiveTotal,
                        totalDurationMs = effectiveTotal,
                        errorMessage = null
                    )
                },
                onFailure = { err ->
                    if (err is CancellationException) {
                        _transcriptionState.value = _transcriptionState.value.copy(
                            isRunning = false,
                            phaseLabel = "Stopped by user",
                            errorMessage = null
                        )
                    } else {
                        _transcriptionState.value = _transcriptionState.value.copy(
                            isRunning = false,
                            phaseLabel = "Error",
                            errorMessage = err.message ?: "Offline English speech recognition failed."
                        )
                    }
                }
            )
        }
    }

    fun stopTranscription() {
        WhisperNativeBridge.nativeRequestAbort()
        val job = activeTranscriptionJob
        activeTranscriptionJob = null
        if (job != null) {
            engineScope.launch {
                runCatching { job.cancelAndJoin() }
                WhisperNativeBridge.nativeResetAbort()
                _transcriptionState.value = _transcriptionState.value.copy(
                    isRunning = false,
                    phaseLabel = "Stopped"
                )
            }
        }
    }

    fun setExportFeedback(message: String?) {
        _transcriptionState.value = _transcriptionState.value.copy(exportFeedback = message)
    }

    fun applySpeakerAssignments(segmentIndexToCharacterId: Map<Int, String>) {
        if (segmentIndexToCharacterId.isEmpty()) return
        val current = _transcriptionState.value.segments
        if (current.isEmpty()) return
        val updated = current.mapIndexed { idx, segment ->
            val assigned = segmentIndexToCharacterId[idx]
            if (assigned != null) segment.copy(speakerId = assigned) else segment
        }
        _transcriptionState.value = _transcriptionState.value.copy(segments = updated)
    }

    /**
     * Implements Phase 1/2 [SpeechRecognitionEngine] contract for WAV or audio files directly.
     */
    override suspend fun transcribeAudioTrack(
        audioFilePath: String,
        sourceLanguage: DubbingLanguage
    ): Result<List<RecognizedSpeechSegment>> = withContext(Dispatchers.Default) {
        runCatching {
            if (!isModelLoadedAndReady) {
                throw IllegalStateException("Whisper base.en model is not loaded yet.")
            }
            val file = File(audioFilePath)
            val samples = PcmResampler.decodeWavTo16kMonoFloat(file)
            val totalMs = (samples.size.toLong() * 1_000L) / PcmResampler.WHISPER_SAMPLE_RATE
            val segments = mutableListOf<RecognizedSpeechSegment>()
            val counter = AtomicInteger(0)

            WhisperNativeBridge.nativeResetAbort()
            val chunk = PcmAudioChunk(
                chunkIndex = 0,
                startTimeMs = 0L,
                durationMs = totalMs,
                samples16kMono = samples
            )
            val rc = runNativeChunkInference(
                chunk = chunk,
                knownTotalDurationMs = totalMs,
                segmentCounter = counter,
                accumulatedSegments = segments
            )
            if (rc != 0) {
                throw IllegalStateException("whisper.cpp failed with error code $rc")
            }
            segments.toList()
        }
    }

    private suspend fun runNativeChunkInference(
        chunk: PcmAudioChunk,
        knownTotalDurationMs: Long,
        segmentCounter: AtomicInteger,
        accumulatedSegments: MutableList<RecognizedSpeechSegment>
    ): Int = nativeContextMutex.withLock {
        val ptr = nativeContextPtr
        if (ptr == 0L) return@withLock -1

        val threads = WhisperNativeBridge.recommendedThreadCount()
        val callback = object : WhisperNativeCallback {
            override fun onProgress(progressPercent: Int) {
                val chunkFraction = (progressPercent.coerceIn(0, 100)) / 100f
                val overall = if (knownTotalDurationMs > 0L) {
                    val estMs = chunk.startTimeMs + (chunk.durationMs * chunkFraction).toLong()
                    (estMs.toFloat() / knownTotalDurationMs.toFloat()).coerceIn(0.01f, 0.99f)
                } else {
                    chunkFraction.coerceIn(0.01f, 0.99f)
                }
                _transcriptionState.value = _transcriptionState.value.copy(
                    progressFraction = overall
                )
            }

            override fun onNewSegment(startMs: Long, endMs: Long, text: String) {
                val cleaned = text.trim()
                if (cleaned.isEmpty()) return
                val seg = RecognizedSpeechSegment(
                    id = "SEG_${segmentCounter.incrementAndGet().toString().padStart(4, '0')}",
                    startTimeMs = startMs.coerceAtLeast(0L),
                    endTimeMs = endMs.coerceAtLeast(startMs),
                    originalText = cleaned,
                    detectedLanguageCode = "en",
                    speakerId = "SPEAKER_01",
                    confidence = 1.0f
                )
                synchronized(accumulatedSegments) {
                    accumulatedSegments.add(seg)
                    val snapshot = accumulatedSegments.toList()
                    val combinedText = snapshot.joinToString(" ") { it.originalText }
                    _transcriptionState.value = _transcriptionState.value.copy(
                        segments = snapshot,
                        fullTranscriptText = combinedText
                    )
                }
            }
        }

        WhisperNativeBridge.nativeTranscribeChunk(
            contextPtr = ptr,
            numThreads = threads,
            audioData = chunk.samples16kMono,
            chunkOffsetMs = chunk.startTimeMs,
            language = "en",
            callback = callback
        )
    }

    fun release() {
        WhisperNativeBridge.nativeRequestAbort()
        activeTranscriptionJob?.cancel()
        activeModelJob?.cancel()
        if (nativeContextPtr != 0L) {
            runCatching { WhisperNativeBridge.nativeFreeContext(nativeContextPtr) }
            nativeContextPtr = 0L
        }
    }
}
