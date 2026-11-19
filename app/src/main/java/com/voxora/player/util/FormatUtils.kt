package com.voxora.player.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object FormatUtils {
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatResolution(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val shortEdge = minOf(width, height)
        return when {
            shortEdge >= 2160 -> "4K"
            shortEdge >= 1440 -> "2K"
            shortEdge >= 1080 -> "1080p"
            shortEdge >= 720 -> "720p"
            shortEdge >= 480 -> "480p"
            else -> "${shortEdge}p"
        }
    }

    fun formatSyncOffset(offsetMs: Long): String {
        val seconds = offsetMs / 1000.0
        val sign = if (offsetMs > 0) "+" else ""
        return String.format(Locale.US, "%s%.1fs", sign, seconds)
    }

    fun formatRelativePlayedAt(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = (nowMs - timestampMs).coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }
}
