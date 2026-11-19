package com.voxora.player.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.voxora.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class AppUpdateManager(private val context: Context) {

    companion object {
        const val GITHUB_OWNER = "stupidboytanvir-droid"
        const val GITHUB_REPO = "Voxora"
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val RELEASES_PAGE_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    suspend fun fetchLatestRelease(): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = openConnection(LATEST_RELEASE_API_URL).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "VOXORA-Android/$currentVersionName")
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@runCatching null
                }
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub returned HTTP $code while checking updates.")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseReleaseJson(body)
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun parseReleaseJson(rawJson: String): AppReleaseInfo? {
        val dto = json.decodeFromString(GitHubReleaseDto.serializer(), rawJson)
        if (dto.draft || dto.tagName.isBlank()) return null

        val apkAsset = dto.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
        }

        val normalizedVersion = SemanticVersionComparator.normalizeVersion(dto.tagName)
        return AppReleaseInfo(
            tagName = dto.tagName,
            versionName = normalizedVersion,
            title = dto.name.ifBlank { "VOXORA v$normalizedVersion" },
            releaseNotes = dto.body.trim(),
            htmlUrl = dto.htmlUrl.ifBlank { RELEASES_PAGE_URL },
            apkDownloadUrl = apkAsset?.browserDownloadUrl?.takeIf { it.isNotBlank() },
            apkFileName = apkAsset?.name?.ifBlank { "VOXORA-v$normalizedVersion.apk" }
                ?: "VOXORA-v$normalizedVersion.apk",
            apkSizeBytes = apkAsset?.size ?: 0L,
            publishedAt = dto.publishedAt
        )
    }

    fun isUpdateAvailable(release: AppReleaseInfo): Boolean {
        return SemanticVersionComparator.isNewer(
            remoteVersion = release.versionName,
            currentVersion = currentVersionName
        )
    }

    suspend fun downloadApk(
        release: AppReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = release.apkDownloadUrl
            ?: return@withContext Result.failure(
                IllegalStateException("This release does not contain an APK file.")
            )

        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            cleanupOldApks(updatesDir)

            val safeName = release.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            val targetFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.part")
            if (partFile.exists()) partFile.delete()
            if (targetFile.exists()) targetFile.delete()

            val connection = openConnectionWithRedirects(downloadUrl)
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Download failed with HTTP $responseCode.")
                }

                val totalBytes = connection.contentLengthLong
                    .takeIf { it > 0L }
                    ?: release.apkSizeBytes

                var downloaded = 0L
                var lastReportTimeMs = 0L
                val buffer = ByteArray(16 * 1024)

                connection.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTimeMs >= 100L || downloaded == totalBytes) {
                                lastReportTimeMs = now
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded)
                targetFile
            } catch (t: Throwable) {
                partFile.delete()
                throw t
            } finally {
                connection.disconnect()
            }
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownAppSourcesIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createOpenReleasePageIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun cleanupOldApks(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                runCatching { file.delete() }
            }
        }
    }

    private fun openConnection(urlString: String): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "VOXORA-Android/$currentVersionName")
            }
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header.")
                }
                currentUrl = location
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading update APK.")
    }
}
