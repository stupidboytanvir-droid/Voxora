package com.voxora.player.util

import java.util.Locale

data class FormattedMediaTitle(
    val cleanTitle: String,
    val episodeBadge: String?,
    val rawTitle: String
)

/**
 * Smart Movie/Series Title Formatter for VOXORA Cinema Spatial (v2.5.0).
 *
 * - Automatically strips website prefixes/domains (`MovieLinkBD.com - `, `www.*.com`, etc.),
 *   file extensions (`.mkv`, `.mp4`, ...), release brackets (`[...]`, `(...)` junk),
 *   resolution/codec tags (`1080p`, `WEB-DL`, `x265`, `HEVC`, `Dual Audio`, ...),
 *   and replaces `_` / `.` separators with crisp spacing.
 * - Extracts Season/Episode markers (`S01E06`, `S1E6`, `S01.E06`, `EP06`, `Episode 6`, `1x06`)
 *   into a clean Amber pill badge (`S01 · E06` or `EP 06`) so episodes are immediately scannable.
 */
object TitleFormatter {

    private val videoExtensionRegex = Regex(
        """\.(mkv|mp4|avi|mov|wmv|flv|webm|m4v|ts|m2ts|3gp|mpg|mpeg)$""",
        RegexOption.IGNORE_CASE
    )

    private val urlOrDomainPrefixRegex = Regex(
        """(?i)(?:https?://)?(?:www\.)?[a-z0-9\-]+\.(?:com|net|org|in|bd|to|cc|tv|me|xyz|info|site|club|live|fun|pw|pro|vip|ws|io)(?:\s*[-–—:|_]+\s*|\b)"""
    )

    private val knownReleaseSitePrefixRegex = Regex(
        """(?i)^(?:movielinkbd|mlwbd|vegamovies|bollyflix|hdhub4u|filmyzilla|filmywap|katmoviehd|mkvcinemas|9xmovies|pahe|psa|yts|yify|rarbg|1337x|pagalworld|mp4moviez|moviesmod|uhdmovies|downloadhub|worldfree4u)\b[\s\-–—:|._]*"""
    )

    // Season + Episode: S01E06, S1E6, S01.E06, S01 E06, S01-E06, Season 1 Episode 6, 1x06
    private val seasonEpisodeRegex = Regex(
        """(?i)(?:\bS(\d{1,2})\s*[.\-_ ]*\s*E(?:P)?\s*(\d{1,3})\b|\bSeason\s*(\d{1,2})\s*[.\-_, ]*\s*(?:Episode|Ep)\.?\s*(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b)"""
    )

    // Episode-only: EP06, Ep 6, Episode 6, E06 (when bounded cleanly)
    private val episodeOnlyRegex = Regex(
        """(?i)\b(?:Episode|Ep\.?)\s*[-_.]?\s*(\d{1,3})\b"""
    )

    // Bracketed metadata like [MovieLinkBD], [1080p], [Dual Audio], (WEB-DL)
    private val squareBracketRegex = Regex("""\[[^]]*]""")

    private val junkParenRegex = Regex(
        """(?i)\([^)]*(?:1080p|720p|480p|2160p|4k|uhd|hdr|web-?dl|webrip|bluray|brrip|hdrip|dvdrip|x264|x265|hevc|10bit|aac|ddp|ac3|atmos|dual|hindi|english|bengali|bangla|esub|msub|sub|rip|com|net|org)[^)]*\)"""
    )

    // Trailing release/codec junk tokens once encountered
    private val releaseCutoffRegex = Regex(
        """(?i)\b(?:2160p|1080p|720p|480p|360p|4k|uhd|fhd|imax|hdrip|web-?dl|web-?rip|bluray|blu-ray|bdrip|brrip|dvdrip|hdts|camrip|hdcam|x264|x265|h\.?264|h\.?265|hevc|av1|vp9|10bit|8bit|hdr10|dv|dolby|atmos|ddp\d[\d.]*|dd\d[\d.]*|aac\d?[\d.]*|ac3|eac3|dts|truehd|flac|dual[\s.\-_]?audio|multi[\s.\-_]?audio|esubs?|msubs?|hcsub|hin-?eng|eng-?hin|hindi[\s.\-_]?dubbed|uncut|unrated|repack|proper|nf|amzn|dsnp|hmax|atvp|jc|zee5|sonyliv|hotstar)\b.*$"""
    )

    private val leadingTrailingPunctuationRegex = Regex("""^[\s\-–—:|._]+|[\s\-–—:|._]+$""")
    private val multiSpaceRegex = Regex("""\s{2,}""")

    fun format(rawTitle: String): FormattedMediaTitle {
        val original = rawTitle.trim()
        if (original.isEmpty()) {
            return FormattedMediaTitle(cleanTitle = "Untitled Video", episodeBadge = null, rawTitle = rawTitle)
        }

        // 1. Strip extension
        var working = original.replace(videoExtensionRegex, "")

        // 2. Strip square-bracket tags & domain/site prefixes
        working = working.replace(squareBracketRegex, " ")
        working = working.replace(urlOrDomainPrefixRegex, " ")
        working = working.replace(knownReleaseSitePrefixRegex, " ")
        working = working.replace(junkParenRegex, " ")

        // Normalize underscores to spaces before episode detection so `_S01E06_` / `_Episode_12_` match word boundaries
        working = working.replace('_', ' ')

        // 3. Extract Season/Episode badge before stripping dots
        var episodeBadge: String? = null
        val seMatch = seasonEpisodeRegex.find(working)
        if (seMatch != null) {
            val seasonNum = (seMatch.groupValues[1].ifEmpty {
                seMatch.groupValues[3].ifEmpty { seMatch.groupValues[5] }
            }).toIntOrNull()
            val epNum = (seMatch.groupValues[2].ifEmpty {
                seMatch.groupValues[4].ifEmpty { seMatch.groupValues[6] }
            }).toIntOrNull()
            if (seasonNum != null && epNum != null) {
                episodeBadge = String.format(Locale.US, "S%02d · E%02d", seasonNum, epNum)
                val beforeEp = working.substring(0, seMatch.range.first)
                val afterEp = working.substring(seMatch.range.last + 1)
                working = if (beforeEp.replace(leadingTrailingPunctuationRegex, "").length >= 2) {
                    "$beforeEp ${afterEp.replace(releaseCutoffRegex, "")}"
                } else {
                    afterEp
                }
            }
        } else {
            val epMatch = episodeOnlyRegex.find(working)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    episodeBadge = String.format(Locale.US, "EP %02d", epNum)
                    val beforeEp = working.substring(0, epMatch.range.first)
                    val afterEp = working.substring(epMatch.range.last + 1)
                    working = if (beforeEp.replace(leadingTrailingPunctuationRegex, "").length >= 2) {
                        "$beforeEp ${afterEp.replace(releaseCutoffRegex, "")}"
                    } else {
                        afterEp
                    }
                }
            }
        }

        // 4. Replace remaining dots with spaces, then strip release/codec trailing metadata
        working = working.replace('.', ' ')
        working = working.replace(releaseCutoffRegex, "")
        working = working.replace(knownReleaseSitePrefixRegex, "")
        working = working.replace(multiSpaceRegex, " ")
        working = working.replace(leadingTrailingPunctuationRegex, "").trim()

        // Strip dangling empty parentheses or trailing lone hyphens
        working = working.replace(Regex("""\(\s*\)"""), "").replace(leadingTrailingPunctuationRegex, "").trim()

        val finalTitle = if (working.length >= 2) {
            working
        } else {
            original
                .replace(videoExtensionRegex, "")
                .replace('_', ' ')
                .replace('.', ' ')
                .replace(multiSpaceRegex, " ")
                .trim()
                .ifBlank { "Untitled Video" }
        }

        return FormattedMediaTitle(
            cleanTitle = finalTitle,
            episodeBadge = episodeBadge,
            rawTitle = rawTitle
        )
    }
}
