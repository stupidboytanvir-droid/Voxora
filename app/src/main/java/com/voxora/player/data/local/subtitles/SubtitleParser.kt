package com.voxora.player.data.local.subtitles

import com.voxora.player.model.SubtitleCue

/**
 * Pure-Kotlin parser for SRT and WebVTT (.vtt) subtitle files with sync-offset lookup.
 */
object SubtitleParser {

    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val VTT_ALIGNMENT_REGEX = Regex("\\s+(align|position|line|size):\\S+")

    fun parse(rawContent: String, fileNameOrExt: String = ""): List<SubtitleCue> {
        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val isVtt = fileNameOrExt.endsWith(".vtt", ignoreCase = true) ||
            normalized.trimStart().startsWith("WEBVTT", ignoreCase = true)

        return if (isVtt) parseVtt(normalized) else parseSrt(normalized)
    }

    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex == -1) continue

            val times = parseTimeRange(lines[timeLineIndex]) ?: continue
            val textLines = lines.drop(timeLineIndex + 1)
            val text = cleanCueText(textLines.joinToString("\n"))
            if (text.isNotBlank()) {
                cues.add(SubtitleCue(times.first, times.second, text))
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val first = lines.first().trim()
            if (first.startsWith("WEBVTT", ignoreCase = true) ||
                first.startsWith("NOTE", ignoreCase = true) ||
                first.startsWith("STYLE", ignoreCase = true) ||
                first.startsWith("REGION", ignoreCase = true)
            ) {
                continue
            }

            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex == -1) continue

            val cleanedTimeLine = lines[timeLineIndex].replace(VTT_ALIGNMENT_REGEX, "")
            val times = parseTimeRange(cleanedTimeLine) ?: continue
            val textLines = lines.drop(timeLineIndex + 1)
            val text = cleanCueText(textLines.joinToString("\n"))
            if (text.isNotBlank()) {
                cues.add(SubtitleCue(times.first, times.second, text))
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private fun parseTimeRange(timeLine: String): Pair<Long, Long>? {
        val parts = timeLine.split("-->")
        if (parts.size < 2) return null
        val start = parseTimestampMs(parts[0].trim()) ?: return null
        val endRaw = parts[1].trim().split(Regex("\\s+")).firstOrNull() ?: return null
        val end = parseTimestampMs(endRaw) ?: return null
        return if (end >= start) start to end else null
    }

    fun parseTimestampMs(raw: String): Long? {
        val normalized = raw.trim().replace(',', '.')
        val mainAndMillis = normalized.split('.')
        val timeParts = mainAndMillis[0].split(':')
        val millis = if (mainAndMillis.size > 1) {
            mainAndMillis[1].take(3).padEnd(3, '0').toLongOrNull() ?: return null
        } else 0L

        return when (timeParts.size) {
            3 -> {
                val hours = timeParts[0].toLongOrNull() ?: return null
                val mins = timeParts[1].toLongOrNull() ?: return null
                val secs = timeParts[2].toLongOrNull() ?: return null
                ((hours * 3600L) + (mins * 60L) + secs) * 1000L + millis
            }
            2 -> {
                val mins = timeParts[0].toLongOrNull() ?: return null
                val secs = timeParts[1].toLongOrNull() ?: return null
                ((mins * 60L) + secs) * 1000L + millis
            }
            else -> null
        }
    }

    /**
     * Finds active cue text at [playbackPositionMs] adjusted by [syncOffsetMs].
     * Positive syncOffsetMs delays subtitles (shows them later), negative advances them.
     */
    fun findActiveCueText(
        cues: List<SubtitleCue>,
        playbackPositionMs: Long,
        syncOffsetMs: Long
    ): String? {
        if (cues.isEmpty()) return null
        val effectivePos = playbackPositionMs - syncOffsetMs
        val active = cues.filter { effectivePos in it.startTimeMs..it.endTimeMs }
        if (active.isEmpty()) return null
        return active.joinToString("\n") { it.text }
    }

    private fun cleanCueText(raw: String): String {
        return raw
            .replace(HTML_TAG_REGEX, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }
}
