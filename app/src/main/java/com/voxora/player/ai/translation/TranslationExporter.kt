package com.voxora.player.ai.translation

import com.voxora.player.dubbing.whisper.TranscriptExporter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class TranslationExportFormat(
    val label: String,
    val fileSuffix: String,
    val extension: String,
    val mimeType: String
) {
    BENGALI_SRT("Bengali .SRT", "_bn", "srt", "application/x-subrip"),
    BILINGUAL_SRT("Bilingual .SRT", "_en_bn", "srt", "application/x-subrip"),
    TXT("Bilingual .TXT", "_en_bn", "txt", "text/plain"),
    JSON("Structured .JSON", "_en_bn", "json", "application/json")
}

@Serializable
private data class ExportedTranslationDocument(
    val engine: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val videoTitle: String,
    val fullOriginalText: String,
    val fullTranslatedText: String,
    val segments: List<ExportedTranslatedSegmentDto>
)

@Serializable
private data class ExportedTranslatedSegmentDto(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String,
    val characterId: String? = null
)

/**
 * Formats genuine offline ML Kit English -> Bengali translations into Bengali SRT,
 * Bilingual SRT (EN + BN), timestamped TXT, or structured JSON.
 */
object TranslationExporter {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun format(
        format: TranslationExportFormat,
        videoTitle: String,
        segments: List<TranslatedSegment>
    ): String = when (format) {
        TranslationExportFormat.BENGALI_SRT -> toBengaliSrt(segments)
        TranslationExportFormat.BILINGUAL_SRT -> toBilingualSrt(segments)
        TranslationExportFormat.TXT -> toTimestampedText(videoTitle, segments)
        TranslationExportFormat.JSON -> toJson(videoTitle, segments)
    }

    fun toBengaliSrt(segments: List<TranslatedSegment>): String {
        if (segments.isEmpty()) return ""
        return buildString {
            segments.forEachIndexed { idx, seg ->
                appendLine(idx + 1)
                append(TranscriptExporter.formatSrtTimestamp(seg.startMs))
                append(" --> ")
                appendLine(TranscriptExporter.formatSrtTimestamp(seg.endMs.coerceAtLeast(seg.startMs + 100L)))
                appendLine(seg.translatedText.trim())
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun toBilingualSrt(segments: List<TranslatedSegment>): String {
        if (segments.isEmpty()) return ""
        return buildString {
            segments.forEachIndexed { idx, seg ->
                appendLine(idx + 1)
                append(TranscriptExporter.formatSrtTimestamp(seg.startMs))
                append(" --> ")
                appendLine(TranscriptExporter.formatSrtTimestamp(seg.endMs.coerceAtLeast(seg.startMs + 100L)))
                val english = seg.originalText.trim()
                val bengali = seg.translatedText.trim()
                if (english.isNotEmpty()) appendLine(english)
                if (bengali.isNotEmpty()) appendLine(bengali)
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun toTimestampedText(
        videoTitle: String,
        segments: List<TranslatedSegment>
    ): String {
        if (segments.isEmpty()) return ""
        return buildString {
            appendLine("VOXORA Offline English → Bengali (বাংলা) Translation")
            if (videoTitle.isNotBlank()) appendLine("Video: $videoTitle")
            appendLine("Engine: Google ML Kit On-Device Translation (Offline)")
            appendLine("------------------------------------------------------------")
            segments.forEach { seg ->
                append("[")
                append(TranscriptExporter.formatReadableTimestamp(seg.startMs))
                append(" -> ")
                append(TranscriptExporter.formatReadableTimestamp(seg.endMs))
                appendLine("]")
                appendLine("EN: ${seg.originalText.trim()}")
                appendLine("বাংলা: ${seg.translatedText.trim()}")
                appendLine()
            }
        }
    }

    fun toJson(
        videoTitle: String,
        segments: List<TranslatedSegment>
    ): String {
        val doc = ExportedTranslationDocument(
            engine = "Google ML Kit On-Device Translation",
            sourceLanguage = "en",
            targetLanguage = "bn",
            videoTitle = videoTitle,
            fullOriginalText = segments.joinToString(" ") { it.originalText.trim() }.trim(),
            fullTranslatedText = segments.joinToString(" ") { it.translatedText.trim() }.trim(),
            segments = segments.map { seg ->
                ExportedTranslatedSegmentDto(
                    index = seg.index + 1,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    originalText = seg.originalText.trim(),
                    translatedText = seg.translatedText.trim(),
                    characterId = seg.characterId
                )
            }
        )
        return prettyJson.encodeToString(ExportedTranslationDocument.serializer(), doc)
    }
}
