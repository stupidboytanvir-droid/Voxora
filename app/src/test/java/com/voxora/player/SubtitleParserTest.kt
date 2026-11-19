package com.voxora.player

import com.voxora.player.data.local.subtitles.SubtitleParser
import com.voxora.player.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `parse SRT cues and strip HTML tags`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,500
            <i>Hello</i> VOXORA!

            2
            00:00:05,250 --> 00:00:08,000
            Second line
            with break
        """.trimIndent()

        val cues = SubtitleParser.parse(srt, "movie.srt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4500L, cues[0].endTimeMs)
        assertEquals("Hello VOXORA!", cues[0].text)
        assertEquals("Second line\nwith break", cues[1].text)
    }

    @Test
    fun `parse VTT cues and ignore header metadata`() {
        val vtt = """
            WEBVTT

            NOTE This is a comment

            00:02.000 --> 00:05.500 align:middle
            Welcome to <b>VOXORA</b>
        """.trimIndent()

        val cues = SubtitleParser.parse(vtt, "sample.vtt")
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].startTimeMs)
        assertEquals(5500L, cues[0].endTimeMs)
        assertEquals("Welcome to VOXORA", cues[0].text)
    }

    @Test
    fun `subtitle sync offset shifts active cue timing`() {
        val srt = """
            1
            00:00:10,000 --> 00:00:12,000
            Synced cue
        """.trimIndent()
        val cues = SubtitleParser.parse(srt, "test.srt")

        // Without offset: visible at 11s
        assertEquals("Synced cue", SubtitleParser.findActiveCueText(cues, 11_000L, 0L))
        // Delayed by +2000ms: not visible at 11s, visible at 13s
        assertNull(SubtitleParser.findActiveCueText(cues, 11_000L, 2_000L))
        assertEquals("Synced cue", SubtitleParser.findActiveCueText(cues, 13_000L, 2_000L))
    }

    @Test
    fun `format duration and file size helpers`() {
        assertEquals("02:05", FormatUtils.formatDuration(125_000L))
        assertEquals("1:01:05", FormatUtils.formatDuration(3_665_000L))
        assertEquals("1080p", FormatUtils.formatResolution(1920, 1080))
        assertEquals("4K", FormatUtils.formatResolution(3840, 2160))
        assertEquals("+0.5s", FormatUtils.formatSyncOffset(500L))
    }
}
