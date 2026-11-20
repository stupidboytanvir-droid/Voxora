package com.voxora.player.ai.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.voxora.player.dubbing.DubbingLanguage
import com.voxora.player.dubbing.DubbingLanguageRegistry
import com.voxora.player.dubbing.MultilingualTranslator
import com.voxora.player.dubbing.RecognizedSpeechSegment
import com.voxora.player.dubbing.TranslatedSegment as PipelineTranslatedSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Offline English -> Bengali (বাংলা) translation engine powered by Google ML Kit On-Device Translation.
 *
 * Strict integrity guarantees:
 * - Zero cloud API calls, zero API keys, zero fabricated/placeholder translations.
 * - Verifies that the offline ML Kit model is downloaded via [OfflineTranslationManager] before
 *   translating; returns [Result.failure] with [TranslationModelNotDownloadedException] otherwise.
 * - Executes on [Dispatchers.IO] with cooperative coroutine cancellation (`isActive` / `ensureActive`).
 * - Releases native ML Kit resources cleanly when [close] is invoked.
 */
class OfflineTranslationEngine(
    private val translationManager: OfflineTranslationManager,
    private val translatorFactory: (TranslatorOptions) -> Translator = { Translation.getClient(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MultilingualTranslator, Closeable {

    private val translatorOptions: TranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.BENGALI)
        .build()

    private val clientLock = Any()

    @Volatile
    private var translatorClient: Translator? = null

    override val engineName: String = "Google ML Kit On-Device Translation (EN → বাংলা)"

    override val isModelInstalled: Boolean
        get() = translationManager.isModelReady

    private fun getOrCreateTranslator(): Translator {
        return translatorClient ?: synchronized(clientLock) {
            translatorClient ?: translatorFactory(translatorOptions).also {
                translatorClient = it
            }
        }
    }

    /**
     * Translates Whisper [RecognizedSpeechSegment]s offline from English to Bengali (বাংলা).
     *
     * @param segments Timestamped English segments produced by `whisper.cpp`.
     * @param onProgress Callback invoked after each segment with `(completed, total)`.
     * @return [Result.success] with ordered [TranslatedSegment]s, or [Result.failure]
     *         (including [TranslationModelNotDownloadedException] if the offline model is missing).
     */
    suspend fun translateSegments(
        segments: List<RecognizedSpeechSegment>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<List<TranslatedSegment>> = withContext(ioDispatcher) {
        try {
            coroutineContext.ensureActive()

            val modelReady = translationManager.isModelDownloadedNow()
            if (!modelReady) {
                return@withContext Result.failure(TranslationModelNotDownloadedException())
            }

            if (segments.isEmpty()) {
                onProgress(0, 0)
                return@withContext Result.success(emptyList())
            }

            val translator = getOrCreateTranslator()
            val total = segments.size
            val results = ArrayList<TranslatedSegment>(total)

            for ((index, segment) in segments.withIndex()) {
                coroutineContext.ensureActive()

                val sourceText = segment.originalText.trim()
                val translatedText = if (sourceText.isEmpty()) {
                    ""
                } else {
                    translator.translate(sourceText).await().trim()
                }

                results.add(
                    TranslatedSegment(
                        index = index,
                        startMs = segment.startTimeMs,
                        endMs = segment.endTimeMs,
                        originalText = sourceText,
                        translatedText = translatedText,
                        characterId = null
                    )
                )

                onProgress(index + 1, total)
            }

            Result.success(results)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Bridges [OfflineTranslationEngine] into the VOXORA Phase 1 modular [MultilingualTranslator]
     * contract for English -> Bengali pipelines.
     */
    override suspend fun translateSegments(
        segments: List<RecognizedSpeechSegment>,
        sourceLanguage: DubbingLanguage,
        targetLanguage: DubbingLanguage
    ): Result<List<PipelineTranslatedSegment>> {
        if (!sourceLanguage.code.equals(DubbingLanguageRegistry.ENGLISH.code, ignoreCase = true) ||
            !targetLanguage.code.equals(DubbingLanguageRegistry.BENGALI.code, ignoreCase = true)
        ) {
            return Result.failure(
                UnsupportedOperationException(
                    "OfflineTranslationEngine currently supports English (en) → Bengali (bn) only."
                )
            )
        }
        return translateSegments(segments = segments, onProgress = { _, _ -> }).map { translated ->
            translated.mapIndexed { idx, item ->
                val segId = segments.getOrNull(idx)?.id
                    ?: "TR_${(idx + 1).toString().padStart(4, '0')}"
                item.toPipelineSegment(segmentId = segId)
            }
        }
    }

    /**
     * Closes the underlying ML Kit [Translator] instance to prevent native memory leaks.
     */
    override fun close() {
        synchronized(clientLock) {
            val existing = translatorClient
            translatorClient = null
            if (existing != null) {
                runCatching { existing.close() }
            }
        }
    }
}
