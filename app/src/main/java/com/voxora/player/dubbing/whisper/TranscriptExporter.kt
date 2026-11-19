package com.voxora.player.dubbing.whisper

import com.voxora.player.dubbing.RecognizedSpeechSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

enum class TranscriptExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    SRT("SRT Subtitles (.srt)", "srt", "application/x-subrip"),
    TXT("Timestamped Text (.txt)", "txt", "text/plain"),
    JSON("Structured JSON (.json)", "json", "application/json")
}

@Serializable
private data class ExportedTranscriptDocument(
    val engine: String,
    val model: String,
    val videoTitle: String,
    val language: String,
    val fullText: String,
    val segments: List<ExportedSegmentDto>
)

@Serializable
private data class ExportedSegmentDto(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Formats genuine Whisper recognition output into SRT, timestamped plain text, or JSON.
 */
object TranscriptExporter {

    private val prettyJson = Json { prettyPrint = true }

    fun format(
        format: TranscriptExportFormat,
        videoTitle: String,
        modelName: String,
        segments: List<RecognizedSpeechSegment>
    ): String = when (format) {
        TranscriptExportFormat.SRT -> toSrt(segments)
        TranscriptExportFormat.TXT -> toTimestampedText(videoTitle, modelName, segments)
        TranscriptExportFormat.JSON -> toJson(videoTitle, modelName, segments)
    }

    fun toSrt(segments: List<RecognizedSpeechSegment>): String {
        if (segments.isEmpty()) return ""
        return buildString {
            segments.forEachIndexed { index, seg ->
                appendLine(index + 1)
                append(formatSrtTimestamp(seg.startTimeMs))
                append(" --> ")
                appendLine(formatSrtTimestamp(seg.endTimeMs.coerceAtLeast(seg.startTimeMs + 100L)))
                appendLine(seg.originalText.trim())
                appendLine()
            }
        }.trimEnd() + "\n"
    }

    fun toTimestampedText(
        videoTitle: String,
        modelName: String,
        segments: List<RecognizedSpeechSegment>
    ): String {
        if (segments.isEmpty()) return ""
        return buildString {
            appendLine("VOXORA Offline English Transcription")
            if (videoTitle.isNotBlank()) appendLine("Video: $videoTitle")
            if (modelName.isNotBlank()) appendLine("Model: $modelName (whisper.cpp)")
            appendLine("------------------------------------------------------------")
            segments.forEach { seg ->
                append("[")
                append(formatReadableTimestamp(seg.startTimeMs))
                append(" -> ")
                append(formatReadableTimestamp(seg.endTimeMs))
                append("] ")
                appendLine(seg.originalText.trim())
            }
        }
    }

    fun toJson(
        videoTitle: String,
        modelName: String,
        segments: List<RecognizedSpeechSegment>
    ): String {
        val doc = ExportedTranscriptDocument(
            engine = "whisper.cpp",
            model = modelName,
            videoTitle = videoTitle,
            language = "en",
            fullText = segments.joinToString(" ") { it.originalText.trim() }.trim(),
            segments = segments.mapIndexed { idx, seg ->
                ExportedSegmentDto(
                    index = idx + 1,
                    startMs = seg.startTimeMs,
                    endMs = seg.endTimeMs,
                    text = seg.originalText.trim()
                )
            }
        )
        return prettyJson.encodeToString(ExportedTranscriptDocument.serializer(), doc)
    }

    fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun formatReadableTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val tenths = (safe % 1_000L) / 10L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d.%02d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, tenths)
        }
    }
}
