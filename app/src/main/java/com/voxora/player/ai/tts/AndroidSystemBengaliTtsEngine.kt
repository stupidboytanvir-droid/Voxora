package com.voxora.player.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.VoiceGender
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Result of checking Android's built-in offline [TextToSpeech] engine for Bengali support.
 */
data class SystemBengaliTtsAvailability(
    val isAvailable: Boolean,
    val matchedLocaleTag: String? = null,
    val enginePackageName: String? = null,
    val message: String
)

/**
 * Default zero-crash, 100% free & offline Bengali TTS engine powered by Android's built-in
 * [TextToSpeech] (`Locale("bn", "BD")` with automatic fallback to `Locale("bn", "IN")` and `Locale("bn")`).
 *
 * Uses [TextToSpeech.synthesizeToFile] to render real WAV files for each translated line:
 * - Zero Sherpa-ONNX native C++ SIGSEGV risk and near-zero RAM footprint.
 * - Awaits [UtteranceProgressListener.onDone] / [UtteranceProgressListener.onError] via
 *   [suspendCancellableCoroutine] with a strict 10-second timeout per line.
 * - Applies Male/Female pitch (`setPitch`) and speaking rate (`setSpeechRate`) per character profile.
 */
class AndroidSystemBengaliTtsEngine(context: Context) {

    private val appContext = context.applicationContext
    private val ttsMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val pendingUtterances = ConcurrentHashMap<String, CancellableContinuation<Result<Unit>>>()

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var activeLocale: Locale? = null

    @Volatile
    private var lastAvailability: SystemBengaliTtsAvailability? = null

    fun requestCancel() {
        cancelRequested.set(true)
        runCatching { textToSpeech?.stop() }
        failAllPending(CancellationException("Bengali voice generation stopped by user."))
    }

    /**
     * Checks whether Android's built-in offline Text-to-Speech engine has Bengali (`bn-BD` / `bn-IN`) ready.
     */
    suspend fun checkAvailability(): SystemBengaliTtsAvailability = withContext(Dispatchers.IO) {
        ttsMutex.withLock {
            runCatching {
                ensureInitializedLocked()
                lastAvailability ?: SystemBengaliTtsAvailability(
                    isAvailable = true,
                    matchedLocaleTag = activeLocale?.toLanguageTag(),
                    enginePackageName = runCatching { textToSpeech?.defaultEngine }.getOrNull(),
                    message = "Android built-in offline Bengali TTS ready (${activeLocale?.toLanguageTag() ?: "bn-BD"})."
                )
            }.getOrElse { err ->
                SystemBengaliTtsAvailability(
                    isAvailable = false,
                    message = err.message ?: "Android built-in Bengali TTS is unavailable on this device."
                )
            }
        }
    }

    /**
     * Synthesizes all [translatedSegments] into per-line WAV files inside
     * `cacheDir/voxora_dub/<videoHash>/seg_<index>.wav` using Android's offline [TextToSpeech].
     */
    suspend fun synthesizeSegments(
        videoUri: String,
        translatedSegments: List<TranslatedSegment>,
        characters: List<CharacterVoiceProfile>,
        segmentCharacterOverrides: Map<Int, String> = emptyMap(),
        onSegmentSynthesized: (completedCount: Int, totalCount: Int, segment: DubbedAudioSegment) -> Unit = { _, _, _ -> },
        onStatusUpdate: (String) -> Unit = {}
    ): Result<List<DubbedAudioSegment>> = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No local video is currently loaded."))
        }
        if (translatedSegments.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No Bengali translated segments available. Translate the video first.")
            )
        }

        cancelRequested.set(false)
        val charactersById = characters.associateBy { it.characterId }
        val fallbackCharacter = characters.firstOrNull()
        val videoOutputDir = getVideoDubCacheDir(videoUri).apply { mkdirs() }

        try {
            coroutineContext.ensureActive()
            onStatusUpdate("Initializing Android built-in offline Bengali TTS (bn-BD / bn-IN)...")
            ttsMutex.withLock {
                ensureInitializedLocked()
            }

            val results = mutableListOf<DubbedAudioSegment>()
            val total = translatedSegments.size

            translatedSegments.forEachIndexed { idx, segment ->
                coroutineContext.ensureActive()
                if (cancelRequested.get()) {
                    throw CancellationException("Bengali voice generation stopped by user.")
                }

                val resolvedCharId = segmentCharacterOverrides[segment.index]
                    ?: segment.characterId
                    ?: fallbackCharacter?.characterId
                    ?: "DEFAULT_CHARACTER"
                val profile = charactersById[resolvedCharId] ?: fallbackCharacter

                val bengaliText = OfflineBengaliTtsEngine.sanitizeBengaliText(segment.translatedText)
                if (bengaliText.isBlank()) {
                    return@forEachIndexed
                }

                val targetWav = File(videoOutputDir, "seg_${segment.index}.wav")
                val targetWindowMs = (segment.endMs - segment.startMs).coerceAtLeast(0L)

                onStatusUpdate("Synthesizing Bengali voice ${idx + 1}/$total (${profile?.label ?: "Default Voice"})...")

                val (durationMs, appliedSpeed, appliedPitch) = synthesizeSingleLineToWav(
                    text = bengaliText,
                    profile = profile,
                    targetWindowMs = targetWindowMs,
                    outputWavFile = targetWav
                )

                val dubbed = DubbedAudioSegment(
                    segmentIndex = segment.index,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    characterId = resolvedCharId,
                    audioFilePath = targetWav.absolutePath,
                    durationMs = durationMs,
                    text = segment.translatedText,
                    appliedSpeed = appliedSpeed,
                    appliedPitch = appliedPitch
                )
                results.add(dubbed)
                onSegmentSynthesized(results.size, total, dubbed)
            }

            Result.success(results)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Renders a single Bengali preview WAV file using Android's offline [TextToSpeech].
     */
    suspend fun synthesizeCharacterPreview(
        profile: CharacterVoiceProfile,
        sampleText: String = OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        try {
            coroutineContext.ensureActive()
            val previewDir = File(appContext.cacheDir, "voxora_dub/previews").apply { mkdirs() }
            val safeId = profile.characterId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val outputFile = File(previewDir, "preview_sys_${safeId}.wav")

            synthesizeSingleLineToWav(
                text = OfflineBengaliTtsEngine.sanitizeBengaliText(sampleText)
                    .ifBlank { OfflineBengaliTtsEngine.DEFAULT_BENGALI_PREVIEW_PHRASE },
                profile = profile,
                targetWindowMs = 0L,
                outputWavFile = outputFile
            )
            Result.success(outputFile)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Releases Android [TextToSpeech] resources cleanly.
     */
    fun release() {
        cancelRequested.set(true)
        failAllPending(CancellationException("Android Bengali TTS engine released."))
        val instance = textToSpeech
        textToSpeech = null
        activeLocale = null
        lastAvailability = null
        if (instance != null) {
            runCatching { instance.stop() }
            runCatching { instance.shutdown() }
        }
    }

    private suspend fun ensureInitializedLocked(): Pair<TextToSpeech, Locale> {
        val existing = textToSpeech
        val existingLocale = activeLocale
        if (existing != null && existingLocale != null) {
            return existing to existingLocale
        }

        // Clean up any partial previous instance
        runCatching { existing?.stop() }
        runCatching { existing?.shutdown() }
        textToSpeech = null
        activeLocale = null

        val initDeferred = CompletableDeferred<Int>()
        val createdTts = withContext(Dispatchers.Main) {
            var ref: TextToSpeech? = null
            ref = TextToSpeech(appContext) { status ->
                if (!initDeferred.isCompleted) {
                    initDeferred.complete(status)
                }
            }
            ref
        }

        val initStatus = try {
            withTimeout(ENGINE_INIT_TIMEOUT_MS) { initDeferred.await() }
        } catch (te: TimeoutCancellationException) {
            runCatching { createdTts.shutdown() }
            throw IllegalStateException("Android Text-to-Speech engine timed out during initialization.", te)
        }

        if (initStatus != TextToSpeech.SUCCESS) {
            runCatching { createdTts.shutdown() }
            val msg = "Android Text-to-Speech engine failed to initialize (status=$initStatus). Check Android TTS settings."
            lastAvailability = SystemBengaliTtsAvailability(isAvailable = false, message = msg)
            throw IllegalStateException(msg)
        }

        val candidateLocales = listOf(
            Locale("bn", "BD"),
            Locale("bn", "IN"),
            Locale("bn")
        )

        var matchedLocale: Locale? = null
        var sawMissingData = false
        for (locale in candidateLocales) {
            val availability = runCatching { createdTts.isLanguageAvailable(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            when {
                availability >= TextToSpeech.LANG_AVAILABLE -> {
                    val setRes = runCatching { createdTts.setLanguage(locale) }
                        .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    if (setRes >= TextToSpeech.LANG_AVAILABLE) {
                        matchedLocale = locale
                        break
                    }
                }
                availability == TextToSpeech.LANG_MISSING_DATA -> {
                    sawMissingData = true
                }
            }
        }

        if (matchedLocale == null) {
            runCatching { createdTts.shutdown() }
            val reason = if (sawMissingData) {
                "Android offline Bengali (bn-BD / bn-IN) TTS voice data is not installed yet. Install Bengali voice data in Android Settings → Text-to-Speech, or enable Sherpa-ONNX VITS (Experimental)."
            } else {
                "Android Text-to-Speech on this device does not support Bengali (bn-BD / bn-IN). Install Google Speech Services Bengali offline voice, or enable Sherpa-ONNX VITS (Experimental)."
            }
            lastAvailability = SystemBengaliTtsAvailability(isAvailable = false, message = reason)
            throw IllegalStateException(reason)
        }

        createdTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                val cont = pendingUtterances.remove(utteranceId)
                if (cont != null && cont.isActive) {
                    cont.resume(Result.success(Unit))
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == null) return
                val cont = pendingUtterances.remove(utteranceId)
                if (cont != null && cont.isActive) {
                    cont.resume(
                        Result.failure(
                            IllegalStateException("Android Text-to-Speech failed to synthesize Bengali audio line.")
                        )
                    )
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null) return
                val cont = pendingUtterances.remove(utteranceId)
                if (cont != null && cont.isActive) {
                    cont.resume(
                        Result.failure(
                            IllegalStateException(
                                "Android Text-to-Speech failed to synthesize Bengali audio (errorCode=$errorCode)."
                            )
                        )
                    )
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == null) return
                val cont = pendingUtterances.remove(utteranceId)
                if (cont != null && cont.isActive) {
                    cont.resume(
                        Result.failure(CancellationException("Android Bengali TTS utterance stopped."))
                    )
                }
            }
        })

        textToSpeech = createdTts
        activeLocale = matchedLocale
        lastAvailability = SystemBengaliTtsAvailability(
            isAvailable = true,
            matchedLocaleTag = matchedLocale.toLanguageTag(),
            enginePackageName = runCatching { createdTts.defaultEngine }.getOrNull(),
            message = "Android built-in offline Bengali TTS ready (${matchedLocale.toLanguageTag()})."
        )
        return createdTts to matchedLocale
    }

    private suspend fun synthesizeSingleLineToWav(
        text: String,
        profile: CharacterVoiceProfile?,
        targetWindowMs: Long,
        outputWavFile: File
    ): Triple<Long, Float, Float> = ttsMutex.withLock {
        coroutineContext.ensureActive()
        if (cancelRequested.get()) {
            throw CancellationException("Bengali voice generation stopped by user.")
        }

        val (tts, locale) = ensureInitializedLocked()
        val effectivePitch = computeSystemPitch(profile)
        var effectiveSpeed = computeSystemSpeechRate(profile)

        outputWavFile.parentFile?.mkdirs()

        var durationMs = invokeSynthesizeToFile(
            tts = tts,
            locale = locale,
            text = text,
            pitch = effectivePitch,
            speechRate = effectiveSpeed,
            outputWavFile = outputWavFile
        )

        // Optional adaptive timing pass: tighten speech rate if Bengali line overflows dialogue window
        val fasterSpeed = WavAudioProcessor.computeTimingAdjustedSpeed(
            currentSpeed = effectiveSpeed,
            generatedDurationMs = durationMs,
            targetWindowMs = targetWindowMs,
            maxAllowedSpeed = 1.45f
        )
        if (fasterSpeed != null && !cancelRequested.get()) {
            effectiveSpeed = fasterSpeed
            val retryDuration = runCatching {
                invokeSynthesizeToFile(
                    tts = tts,
                    locale = locale,
                    text = text,
                    pitch = effectivePitch,
                    speechRate = effectiveSpeed,
                    outputWavFile = outputWavFile
                )
            }.getOrNull()
            if (retryDuration != null && retryDuration > 0L) {
                durationMs = retryDuration
            }
        }

        Triple(durationMs, effectiveSpeed, effectivePitch)
    }

    private suspend fun invokeSynthesizeToFile(
        tts: TextToSpeech,
        locale: Locale,
        text: String,
        pitch: Float,
        speechRate: Float,
        outputWavFile: File
    ): Long {
        val tempWav = File(outputWavFile.parentFile, "${outputWavFile.name}.tmp.wav")
        if (tempWav.exists()) runCatching { tempWav.delete() }

        val utteranceId = "voxora_bn_${UUID.randomUUID().toString().replace("-", "")}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        try {
            val synthesisResult = withTimeout(LINE_SYNTHESIS_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<Unit>> { continuation ->
                    pendingUtterances[utteranceId] = continuation
                    continuation.invokeOnCancellation {
                        pendingUtterances.remove(utteranceId)
                        runCatching { tts.stop() }
                    }

                    val enqueueStatus = runCatching {
                        tts.setLanguage(locale)
                        tts.setPitch(pitch)
                        tts.setSpeechRate(speechRate)
                        tts.synthesizeToFile(text, params, tempWav, utteranceId)
                    }.getOrElse { err ->
                        pendingUtterances.remove(utteranceId)
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(err))
                        }
                        return@suspendCancellableCoroutine
                    }

                    if (enqueueStatus != TextToSpeech.SUCCESS) {
                        pendingUtterances.remove(utteranceId)
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.failure(
                                    IllegalStateException(
                                        "Android TextToSpeech.synthesizeToFile rejected line (status=$enqueueStatus)."
                                    )
                                )
                            )
                        }
                    }
                }
            }
            synthesisResult.getOrThrow()
        } catch (te: TimeoutCancellationException) {
            pendingUtterances.remove(utteranceId)
            runCatching { tts.stop() }
            runCatching { if (tempWav.exists()) tempWav.delete() }
            throw IllegalStateException("Android Bengali TTS timed out after 10s synthesizing dialogue line.", te)
        } catch (ce: CancellationException) {
            pendingUtterances.remove(utteranceId)
            runCatching { if (tempWav.exists()) tempWav.delete() }
            throw ce
        }

        if (!tempWav.exists() || tempWav.length() <= 44L) {
            runCatching { if (tempWav.exists()) tempWav.delete() }
            throw IllegalStateException("Android Bengali TTS produced an empty WAV file for line: \"$text\"")
        }

        val wavHeader = DubbedTrackAssembler.inspectWavHeader(tempWav)
            ?: run {
                runCatching { tempWav.delete() }
                throw IllegalStateException("Android Bengali TTS wrote an unreadable WAV header.")
            }

        val durationMs = ((wavHeader.totalFrames.toDouble() / wavHeader.sampleRate.toDouble()) * 1_000.0)
            .toLong()
            .coerceAtLeast(100L)

        if (outputWavFile.exists()) runCatching { outputWavFile.delete() }
        if (!tempWav.renameTo(outputWavFile)) {
            tempWav.copyTo(outputWavFile, overwrite = true)
            runCatching { tempWav.delete() }
        }
        return durationMs
    }

    private fun failAllPending(cause: Throwable) {
        val entries = pendingUtterances.entries.toList()
        entries.forEach { (id, cont) ->
            pendingUtterances.remove(id)
            if (cont.isActive) {
                cont.resume(Result.failure(cause))
            }
        }
    }

    private fun getVideoDubCacheDir(videoUri: String): File {
        val hash = OfflineBengaliTtsEngine.sha256Short(videoUri)
        return File(appContext.cacheDir, "voxora_dub/$hash")
    }

    companion object {
        const val LINE_SYNTHESIS_TIMEOUT_MS = 10_000L
        const val ENGINE_INIT_TIMEOUT_MS = 10_000L

        /**
         * Distinct Male/Female pitch scaling for Android's built-in Bengali TextToSpeech engine
         * while honoring each character profile's custom pitch slider.
         */
        fun computeSystemPitch(profile: CharacterVoiceProfile?): Float {
            if (profile == null) return 1.0f
            val genderBase = when (profile.gender) {
                VoiceGender.MALE -> 0.85f
                VoiceGender.FEMALE -> 1.12f
            }
            return (profile.pitchFactor * genderBase).coerceIn(0.55f, 1.55f)
        }

        fun computeSystemSpeechRate(profile: CharacterVoiceProfile?): Float {
            return (profile?.speakingRate ?: 1.0f).coerceIn(
                WavAudioProcessor.MIN_SPEED_FACTOR,
                WavAudioProcessor.MAX_SPEED_FACTOR
            )
        }
    }
}
