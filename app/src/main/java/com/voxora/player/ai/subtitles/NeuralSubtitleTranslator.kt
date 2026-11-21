package com.voxora.player.ai.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lightweight, free Online Neural Subtitle Translator backed by Google Translate's neural web API
 * (`translate.googleapis.com/translate_a/single?client=gtx`) with a crisp 2.5-second timeout,
 * single-request chunk batching (`\n|||\n`), and in-memory caching so re-seeking never re-translates.
 *
 * Returns `null` cleanly whenever offline, rate-limited, or timed out so [AutoSubtitleManager]
 * falls back seamlessly to on-device Google ML Kit Translation.
 */
class NeuralSubtitleTranslator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2_500L, TimeUnit.MILLISECONDS)
        .readTimeout(2_500L, TimeUnit.MILLISECONDS)
        .writeTimeout(2_500L, TimeUnit.MILLISECONDS)
        .callTimeout(2_500L, TimeUnit.MILLISECONDS)
        .build()
) {
    private val cache = ConcurrentHashMap<String, String>()

    fun getCached(text: String, targetLangCode: String): String? {
        return cache[cacheKey(text, targetLangCode)]
    }

    fun clearCache() {
        cache.clear()
    }

    /**
     * Translates an entire chunk's lines in **ONE single OkHttp request** using `\n|||\n`
     * delimiters (2.5s timeout), checking the in-memory cache first.
     *
     * Returns an aligned list of translated strings (or `null` at any index where batch
     * translation failed or split count mismatched so caller falls back cleanly).
     */
    suspend fun translateBatch(
        lines: List<String>,
        targetLangCode: String
    ): List<String?> {
        if (lines.isEmpty()) return emptyList()
        val lang = targetLangCode.trim().lowercase(Locale.ROOT)
        if (lang.isBlank() || lang == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) {
            return List(lines.size) { null }
        }

        val results = MutableList<String?>(lines.size) { null }
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()

        for (i in lines.indices) {
            val cleaned = lines[i].trim()
            if (cleaned.isBlank()) continue
            val cached = cache[cacheKey(cleaned, lang)]
            if (cached != null) {
                results[i] = cached
            } else {
                uncachedIndices.add(i)
                uncachedTexts.add(cleaned)
            }
        }

        if (uncachedTexts.isEmpty()) return results

        if (uncachedTexts.size == 1) {
            results[uncachedIndices[0]] = translate(uncachedTexts[0], lang)
            return results
        }

        val joined = uncachedTexts.joinToString(BATCH_DELIMITER)
        val rawTranslated = executeTranslateRequest(joined, lang, preserveNewlines = true)
        val split = rawTranslated?.let { splitBatchedTranslation(it, uncachedTexts.size) }

        if (split != null) {
            if (cache.size + split.size > MAX_CACHE_ENTRIES) {
                cache.clear()
            }
            for (k in uncachedIndices.indices) {
                val translatedLine = split[k]
                results[uncachedIndices[k]] = translatedLine
                cache[cacheKey(uncachedTexts[k], lang)] = translatedLine
            }
        }

        return results
    }

    suspend fun translate(text: String, targetLangCode: String): String? {
        val cleaned = text.trim()
        val lang = targetLangCode.trim().lowercase(Locale.ROOT)
        if (cleaned.isBlank() || lang.isBlank() || lang == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) {
            return null
        }

        val key = cacheKey(cleaned, lang)
        cache[key]?.let { return it }

        val translated = executeTranslateRequest(cleaned, lang, preserveNewlines = false)
        if (!translated.isNullOrBlank()) {
            if (cache.size > MAX_CACHE_ENTRIES) {
                cache.clear()
            }
            cache[key] = translated
        }
        return translated
    }

    private suspend fun executeTranslateRequest(
        queryText: String,
        targetLangCode: String,
        preserveNewlines: Boolean
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(queryText, Charsets.UTF_8.name())
            val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetLangCode&dt=t&q=$encodedQuery"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                if (preserveNewlines) {
                    parseGoogleTranslateResponsePreserveNewlines(body)
                } else {
                    parseGoogleTranslateResponse(body)
                }
            }
        }.getOrNull()
    }

    companion object {
        const val BATCH_DELIMITER = "\n|||\n"
        private const val MAX_CACHE_ENTRIES = 1_500
        private val BATCH_SPLIT_REGEX = Regex("\\s*\\|\\|\\|\\s*")

        private fun cacheKey(text: String, targetLang: String): String = "$targetLang|$text"

        /**
         * Parses Google Translate `translate_a/single` nested JSON array response:
         * `[[["translated part 1","source 1",null,null,10],["translated part 2","source 2",...]],null,"en",...]`
         */
        fun parseGoogleTranslateResponse(jsonBody: String): String? {
            return parseGoogleTranslateResponsePreserveNewlines(jsonBody)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        fun parseGoogleTranslateResponsePreserveNewlines(jsonBody: String): String? {
            if (jsonBody.isBlank()) return null
            return runCatching {
                val root = Json.parseToJsonElement(jsonBody).jsonArray
                val sentencesArray = root.firstOrNull() as? JsonArray ?: return@runCatching null
                val sb = StringBuilder()
                for (entry in sentencesArray) {
                    val tuple = entry as? JsonArray ?: continue
                    val translatedPrim = tuple.firstOrNull() as? JsonPrimitive ?: continue
                    if (translatedPrim.isString) {
                        sb.append(translatedPrim.content)
                    }
                }
                sb.toString().trim().takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * Splits a single batched neural translation response on `|||` back into exact per-segment
         * lines. Returns `null` if the split count does not match [expectedCount] so the caller
         * can cleanly fall back to individual / ML Kit translation.
         */
        fun splitBatchedTranslation(translatedBatch: String, expectedCount: Int): List<String>? {
            if (expectedCount <= 0 || translatedBatch.isBlank()) return null
            val parts = translatedBatch
                .split(BATCH_SPLIT_REGEX)
                .map { it.replace(Regex("\\s+"), " ").trim() }
            if (parts.size != expectedCount || parts.any { it.isBlank() }) {
                return null
            }
            return parts
        }
    }
}
