package com.voxora.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voxora.player.data.update.AppUpdateManager
import com.voxora.player.data.update.SemanticVersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = VoxoraApplication::class)
class AppUpdateLogicTest {

    @Test
    fun `semantic version comparator detects newer versions accurately`() {
        assertTrue(SemanticVersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(SemanticVersionComparator.isNewer("v1.1.0", "1.0.9"))
        assertTrue(SemanticVersionComparator.isNewer("2.0.0", "v1.9.9"))
        assertTrue(SemanticVersionComparator.isNewer("1.0.0", "1.0.0-beta.1"))

        assertFalse(SemanticVersionComparator.isNewer("v1.0.0", "1.0.0"))
        assertFalse(SemanticVersionComparator.isNewer("1.0", "1.0.0"))
        assertFalse(SemanticVersionComparator.isNewer("v0.9.9", "1.0.0"))
        assertFalse(SemanticVersionComparator.isNewer("1.0.0-rc1", "1.0.0"))
    }

    @Test
    fun `github release json parser extracts apk download asset and metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppUpdateManager(context)
        val sampleJson = """
            {
              "tag_name": "v9.9.0",
              "name": "VOXORA v9.9.0",
              "body": "Added smooth HDR tone mapping and improved subtitle sync.",
              "html_url": "https://github.com/stupidboytanvir-droid/Voxora/releases/tag/v9.9.0",
              "published_at": "2026-11-20T10:00:00Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/stupidboytanvir-droid/Voxora/releases/download/v9.9.0/checksums.txt",
                  "size": 128,
                  "content_type": "text/plain"
                },
                {
                  "name": "VOXORA-v9.9.0.apk",
                  "browser_download_url": "https://github.com/stupidboytanvir-droid/Voxora/releases/download/v9.9.0/VOXORA-v9.9.0.apk",
                  "size": 29481200,
                  "content_type": "application/vnd.android.package-archive"
                }
              ]
            }
        """.trimIndent()

        val release = manager.parseReleaseJson(sampleJson)
        assertNotNull(release)
        assertEquals("v9.9.0", release!!.tagName)
        assertEquals("9.9.0", release.versionName)
        assertEquals("VOXORA-v9.9.0.apk", release.apkFileName)
        assertEquals(29481200L, release.apkSizeBytes)
        assertEquals(
            "https://github.com/stupidboytanvir-droid/Voxora/releases/download/v9.9.0/VOXORA-v9.9.0.apk",
            release.apkDownloadUrl
        )
        assertTrue(manager.isUpdateAvailable(release))
    }

    @Test
    fun `draft release is ignored by parser`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AppUpdateManager(context)
        val draftJson = """
            {
              "tag_name": "v2.0.0",
              "draft": true,
              "assets": []
            }
        """.trimIndent()

        assertNull(manager.parseReleaseJson(draftJson))
    }
}
