package com.voxora.player.ai.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.voxora.player.ai.translation.TranslatedSegment
import com.voxora.player.dubbing.CharacterVoiceProfile
import com.voxora.player.dubbing.VoiceGender
import com.voxora.player.dubbing.VoiceGenderOverrideMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Real offline Bengali Voice Generation (TTS) engine backed by Sherpa-ONNX [OfflineTts]
 * using open-source VITS Bengali models (`model.onnx` + `tokens.txt`).
 *
 * Strict guarantees:
 * - Zero cloud calls, zero paid APIs, zero silent/beep fake audio.
 * - Fails explicitly with [BengaliTtsModelNotInstalledException] if model files are missing.
 * - Runs all native synthesis and WAV encoding on [Dispatchers.IO].
 * - Applies each VOXORA Character Profile's speaking rate & Male/Female pitch timbre distinction,
 *   and automatically tightens speaking speed if Bengali audio overflows the video dialogue window.
 * - Supports clean coroutine cancellation (`ensureActive()` + callback returning `0`) and deterministic [release].
 */
class OfflineBengaliTtsEngine(
    private val context: Context,
    private val modelManager: BengaliTtsModelManager
) {
    private val systemBengaliTtsEngine = AndroidSystemBengaliTtsEngine(context)
    private val edgeNeuralTtsEngine = EdgeNeuralTtsEngine(context, systemBengaliTtsEngine)
    private val nativeMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    val engineDiagnosticState: StateFlow<TtsEngineDiagnosticState> = edgeNeuralTtsEngine.diagnosticState

    @Volatile
    private var activeTts: OfflineTts? = null

    @Volatile
    private var activeModelSignature: String? = null

    fun requestCancel() {
        cancelRequested.set(true)
        edgeNeuralTtsEngine.requestCancel()
        systemBengaliTtsEngine.requestCancel()
    }

    /**
     * Verifies Android built-in offline Bengali TTS (`bn-BD` / `bn-IN`) availability.
     */
    suspend fun checkSystemBengaliTtsAvailability(): SystemBengaliTtsAvailability {
        return systemBengaliTtsEngine.checkAvailability()
    }

    /**
     * Synthesizes all [translatedSegments] into per-line WAV files inside
     * `cacheDir/voxora_dub/<videoHash>/seg_<index>.wav`.
     *
     * - Default (`useSherpaVitsExperimental = false`): uses free Microsoft Edge Neural Bengali TTS
     *   (`bn-BD-NabanitaNeural` / `bn-BD-PradeepNeural` / `bn-IN-TanishaaNeural` / `bn-IN-BashkarNeural`)
     *   with expressive SSML prosody, auto F0 gender matching, and strict lip-sync rate clamping,
     *   automatically falling back to offline Android [android.speech.tts.TextToSpeech] when offline.
     * - Experimental (`useSherpaVitsExperimental = true`): uses Sherpa-ONNX VITS `OfflineTts`.
     */
    suspend fun synthesizeSegments(
        videoUri: String,
        translatedSegments: List<TranslatedSegment>,
        characters: List<CharacterVoiceProfile>,
        segmentCharacterOverrides: Map<Int, String> = emptyMap(),
        acousticProfiles: Map<Int, SegmentAcousticProfile> = emptyMap(),
        voiceGenderOverride: VoiceGenderOverrideMode = VoiceGenderOverrideMode.AUTO,
        useSherpaVitsExperimental: Boolean = false,
        onSegmentSynthesized: (completedCount: Int, totalCount: Int, segment: DubbedAudioSegment) -> Unit = { _, _, _ -> },
        onStatusUpdate: (String) -> Unit = {}
    ): Result<List<DubbedAudioSegment>> {
        if (!useSherpaVitsExperimental) {
            return edgeNeuralTtsEngine.synthesizeSegments(
                videoUri = videoUri,
                translatedSegments = translatedSegments,
                characters = characters,
                segmentCharacterOverrides = segmentCharacterOverrides,
                acousticProfiles = acousticProfiles,
                voiceGenderOverride = voiceGenderOverride,
                onSegmentSynthesized = onSegmentSynthesized,
                onStatusUpdate = onStatusUpdate
            )
        }

        return withContext(Dispatchers.IO) {
            if (videoUri.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No local video is currently loaded."))
            }
            if (translatedSegments.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No Bengali translated segments available. Translate the video first.")
                )
            }

            val modelFiles = modelManager.getInstalledModelFiles()
                ?: return@withContext Result.failure(BengaliTtsModelNotInstalledException())

            cancelRequested.set(false)
            val charactersById = characters.associateBy { it.characterId }
            val fallbackCharacter = characters.firstOrNull()
            val videoOutputDir = getVideoDubCacheDir(videoUri).apply { mkdirs() }

            try {
                coroutineContext.ensureActive()
                onStatusUpdate("Loading experimental offline Sherpa-ONNX VITS Bengali voice...")
                ensureNativeTtsInitialized(modelFiles)

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

                    val bengaliText = sanitizeBengaliText(segment.translatedText)
                    if (bengaliText.isBlank()) {
                        return@forEachIndexed
                    }

                    val targetWav = File(videoOutputDir, "seg_${segment.index}.wav")
                    val targetWindowMs = (segment.endMs - segment.startMs).coerceAtLeast(0L)

                    onStatusUpdate("Synthesizing line ${idx + 1}/$total (${profile?.label ?: "Default Voice"})...")

                    val (durationMs, appliedSpeed, appliedPitch) = synthesizeSingleLineToWav(
                        modelFiles = modelFiles,
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
            } catch (t: UnsatisfiedLinkError) {
                Result.failure(
                    IllegalStateException(
                        "Native Sherpa-ONNX TTS library failed to load on this device ABI: ${t.message}",
                        t
                    )
                )
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    /**
     * Synthesizes a real offline Bengali voice sample phrase for Character Management voice auditioning.
     */
    suspend fun synthesizeCharacterPreview(
        profile: CharacterVoiceProfile,
        sampleText: String = DEFAULT_BENGALI_PREVIEW_PHRASE,
        useSherpaVitsExperimental: Boolean = false
    ): Result<File> {
        if (!useSherpaVitsExperimental) {
            return edgeNeuralTtsEngine.synthesizeCharacterPreview(
                profile = profile,
                sampleText = sampleText
            )
        }

        return withContext(Dispatchers.IO) {
            val modelFiles = modelManager.getInstalledModelFiles()
                ?: return@withContext Result.failure(BengaliTtsModelNotInstalledException())

            cancelRequested.set(false)
            try {
                coroutineContext.ensureActive()
                val previewDir = File(context.cacheDir, "voxora_dub/previews").apply { mkdirs() }
                val safeId = profile.characterId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val outputFile = File(previewDir, "preview_${safeId}.wav")

                synthesizeSingleLineToWav(
                    modelFiles = modelFiles,
                    text = sanitizeBengaliText(sampleText).ifBlank { DEFAULT_BENGALI_PREVIEW_PHRASE },
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
    }

    /**
     * Clears synthesized cached `.wav` files for a specific video.
     */
    suspend fun clearCacheForVideo(videoUri: String) = withContext(Dispatchers.IO) {
        if (videoUri.isBlank()) return@withContext
        val dir = getVideoDubCacheDir(videoUri)
        if (dir.exists()) {
            runCatching { dir.deleteRecursively() }
        }
    }

    /**
     * Releases both Android built-in TTS and any native Sherpa-ONNX [OfflineTts] memory cleanly.
     */
    fun release() {
        cancelRequested.set(true)
        edgeNeuralTtsEngine.release()
        systemBengaliTtsEngine.release()
        val instance = activeTts
        activeTts = null
        activeModelSignature = null
        if (instance != null) {
            runCatching { instance.release() }
        }
    }

    private suspend fun ensureNativeTtsInitialized(modelFiles: BengaliTtsModelFiles): OfflineTts =
        nativeMutex.withLock {
            ensureNativeTtsLocked(modelFiles)
        }

    private fun ensureNativeTtsLocked(modelFiles: BengaliTtsModelFiles): OfflineTts {
        val threads = recommendedThreadCount()
        val sig = "${modelFiles.modelOnnxPath}|${modelFiles.tokensTxtPath}|$threads"
        val existing = activeTts
        if (existing != null && activeModelSignature == sig) {
            return existing
        }

        runCatching { existing?.release() }
        activeTts = null
        activeModelSignature = null

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFiles.modelOnnxPath,
                    lexicon = "",
                    tokens = modelFiles.tokensTxtPath,
                    dataDir = ""
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f
        )

        return OfflineTts(assetManager = null, config = config).also {
            activeTts = it
            activeModelSignature = sig
        }
    }

    private suspend fun synthesizeSingleLineToWav(
        modelFiles: BengaliTtsModelFiles,
        text: String,
        profile: CharacterVoiceProfile?,
        targetWindowMs: Long,
        outputWavFile: File
    ): Triple<Long, Float, Float> = nativeMutex.withLock {
        coroutineContext.ensureActive()
        val tts = ensureNativeTtsLocked(modelFiles)
        val speakerCount = runCatching { tts.numSpeakers() }.getOrDefault(1).coerceAtLeast(1)
        val sid = resolveSpeakerId(profile, speakerCount)
        val effectivePitch = computeEffectivePitch(profile)
        var effectiveSpeed = (profile?.speakingRate ?: 1.0f)
            .coerceIn(WavAudioProcessor.MIN_SPEED_FACTOR, WavAudioProcessor.MAX_SPEED_FACTOR)

        // Pass 1: Synthesize at character's configured speaking rate (with pitch compensation)
        var sherpaSpeed = WavAudioProcessor.computeCompensatedSherpaSpeed(effectiveSpeed, effectivePitch)
        var generated = tts.generateWithCallback(
            text = text,
            sid = sid,
            speed = sherpaSpeed,
            callback = { _ ->
                // Sherpa-ONNX OfflineTts convention: return 1 to continue, 0 to stop
                if (cancelRequested.get()) 0 else 1
            }
        )

        if (cancelRequested.get()) {
            throw CancellationException("Bengali voice generation stopped by user.")
        }

        if (generated.samples.isEmpty() || generated.sampleRate <= 0) {
            throw IllegalStateException("Offline Bengali TTS produced empty audio for line: \"$text\"")
        }

        var processedSamples = WavAudioProcessor.resampleForPitch(generated.samples, effectivePitch)
        var durationMs = ((processedSamples.size.toDouble() / generated.sampleRate.toDouble()) * 1_000.0).toLong()

        // Pass 2 (optional): Auto-adjust speed if Bengali voice significantly exceeds video dialogue window
        val fasterSpeed = WavAudioProcessor.computeTimingAdjustedSpeed(
            currentSpeed = effectiveSpeed,
            generatedDurationMs = durationMs,
            targetWindowMs = targetWindowMs
        )
        if (fasterSpeed != null && !cancelRequested.get()) {
            effectiveSpeed = fasterSpeed
            sherpaSpeed = WavAudioProcessor.computeCompensatedSherpaSpeed(effectiveSpeed, effectivePitch)
            val retried = tts.generateWithCallback(
                text = text,
                sid = sid,
                speed = sherpaSpeed,
                callback = { _ -> if (cancelRequested.get()) 0 else 1 }
            )
            if (cancelRequested.get()) {
                throw CancellationException("Bengali voice generation stopped by user.")
            }
            if (retried.samples.isNotEmpty() && retried.sampleRate > 0) {
                generated = retried
                processedSamples = WavAudioProcessor.resampleForPitch(retried.samples, effectivePitch)
            }
        }

        durationMs = WavAudioProcessor.writeMono16BitWav(
            samples = processedSamples,
            sampleRate = generated.sampleRate,
            targetFile = outputWavFile
        )
        Triple(durationMs, effectiveSpeed, effectivePitch)
    }

    private fun resolveSpeakerId(profile: CharacterVoiceProfile?, numSpeakers: Int): Int {
        if (numSpeakers <= 1 || profile == null) return 0
        val hash = kotlin.math.abs(profile.characterId.hashCode())
        return hash % numSpeakers
    }

    private fun getVideoDubCacheDir(videoUri: String): File {
        val hash = sha256Short(videoUri)
        return File(context.cacheDir, "voxora_dub/$hash")
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 3
            cores >= 2 -> 2
            else -> 1
        }
    }

    companion object {
        const val DEFAULT_BENGALI_PREVIEW_PHRASE = "নমস্কার, আমি ভক্সোরা বাংলা ডাবিং।"

        /**
         * Maps VOXORA Character Profile gender + pitchFactor onto a clear acoustic pitch multiplier.
         * Since the open-source Coqui Bengali VITS base voice is female, Male characters apply a
         * natural lower-register bias (0.86x) so Male and Female characters remain clearly distinct
         * even at default 1.0x pitch, while still honoring custom pitch adjustments.
         */
        fun computeEffectivePitch(profile: CharacterVoiceProfile?): Float {
            if (profile == null) return 1.0f
            val genderBase = when (profile.gender) {
                VoiceGender.MALE -> 0.86f
                VoiceGender.FEMALE -> 1.02f
            }
            return (profile.pitchFactor * genderBase).coerceIn(
                WavAudioProcessor.MIN_PITCH_FACTOR,
                WavAudioProcessor.MAX_PITCH_FACTOR
            )
        }

        fun sanitizeBengaliText(raw: String): String {
            return raw.replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }

        fun sha256Short(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
