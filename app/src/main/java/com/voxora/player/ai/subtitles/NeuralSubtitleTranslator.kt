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
 * (`translate.googleapis.com/translate_a/single?client=gtx`) with a 3-second timeout and
 * in-memory translation cache so re-seeking never re-translates.
 *
 * Returns `null` cleanly whenever offline, rate-limited, or timed out so [AutoSubtitleManager]
 * falls back seamlessly to on-device Google ML Kit Translation.
 */
class NeuralSubtitleTranslator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()
) {
    private val cache = ConcurrentHashMap<String, String>()

    fun getCached(text: String, targetLangCode: String): String? {
        return cache[cacheKey(text, targetLangCode)]
    }

    fun clearCache() {
        cache.clear()
    }

    suspend fun translate(text: String, targetLangCode: String): String? {
        val cleaned = text.trim()
        val lang = targetLangCode.trim().lowercase(Locale.ROOT)
        if (cleaned.isBlank() || lang.isBlank() || lang == AutoSubtitleLanguageCatalog.CODE_ORIGINAL) {
            return null
        }

        val key = cacheKey(cleaned, lang)
        cache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val encodedQuery = URLEncoder.encode(cleaned, Charsets.UTF_8.name())
                val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=auto&tl=$lang&dt=t&q=$encodedQuery"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val translated = parseGoogleTranslateResponse(body)
                    if (!translated.isNullOrBlank()) {
                        if (cache.size > MAX_CACHE_ENTRIES) {
                            cache.clear()
                        }
                        cache[key] = translated
                    }
                    translated
                }
            }.getOrNull()
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 1_500

        private fun cacheKey(text: String, targetLang: String): String = "$targetLang|$text"

        /**
         * Parses Google Translate `translate_a/single` nested JSON array response:
         * `[[["translated part 1","source 1",null,null,10],["translated part 2","source 2",...]],null,"en",...]`
         * Pure Kotlin using `kotlinx.serialization.json` so it runs cleanly on both Android and JVM unit tests.
         */
        fun parseGoogleTranslateResponse(jsonBody: String): String? {
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
                sb.toString().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}
