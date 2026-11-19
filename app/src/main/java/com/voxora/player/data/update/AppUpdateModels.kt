package com.voxora.player.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(
        val currentVersion: String,
        val checkedAtMs: Long = System.currentTimeMillis()
    ) : UpdateUiState

    data class UpdateAvailable(
        val release: AppReleaseInfo,
        val currentVersion: String
    ) : UpdateUiState

    data class Downloading(
        val release: AppReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : UpdateUiState

    data class ReadyToInstall(
        val release: AppReleaseInfo,
        val apkFilePath: String
    ) : UpdateUiState

    data class Error(
        val message: String,
        val release: AppReleaseInfo? = null
    ) : UpdateUiState
}

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("assets") val assets: List<GitHubAssetDto> = emptyList()
)

@Serializable
internal data class GitHubAssetDto(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("content_type") val contentType: String = ""
)

/**
 * Pure semantic version comparator supporting prefixes like "v1.2.3", two-segment
 * versions ("1.0"), and prerelease suffixes ("1.1.0-beta.1").
 */
object SemanticVersionComparator {

    fun normalizeVersion(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .trim()
    }

    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        return compare(remoteVersion, currentVersion) > 0
    }

    fun compare(v1: String, v2: String): Int {
        val clean1 = normalizeVersion(v1)
        val clean2 = normalizeVersion(v2)
        if (clean1 == clean2) return 0

        val parts1 = clean1.split("-", limit = 2)
        val parts2 = clean2.split("-", limit = 2)

        val core1 = parseNumericParts(parts1[0])
        val core2 = parseNumericParts(parts2[0])

        val maxLen = maxOf(core1.size, core2.size)
        for (i in 0 until maxLen) {
            val n1 = core1.getOrElse(i) { 0 }
            val n2 = core2.getOrElse(i) { 0 }
            if (n1 != n2) return n1.compareTo(n2)
        }

        // A stable release (no prerelease tag) is newer than a prerelease of the same core version
        val pre1 = parts1.getOrNull(1)
        val pre2 = parts2.getOrNull(1)
        return when {
            pre1 == null && pre2 == null -> 0
            pre1 == null -> 1
            pre2 == null -> -1
            else -> pre1.compareTo(pre2)
        }
    }

    private fun parseNumericParts(core: String): List<Int> {
        return core.split(".")
            .map { segment ->
                val digits = segment.takeWhile { it.isDigit() }
                digits.toIntOrNull() ?: 0
            }
    }
}
