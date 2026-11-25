package dev.msk.norouterdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckTest {
    @Test fun versionOrder() {
        assertTrue(UpdateCheck.isNewer("0.1.0", "v0.2.0"))
        assertTrue(UpdateCheck.isNewer("0.9", "0.10"))
        assertTrue(UpdateCheck.isNewer("1.0", "1.0.1"))
        assertFalse(UpdateCheck.isNewer("0.2.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("0.3.0", "0.2.9"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "v1.0.0-beta"))
    }

    @Test fun placeholderRepoIsNotConfigured() {
        assertFalse(UpdateCheck.repoConfigured("<OWNER>/<REPO>"))
        assertFalse(UpdateCheck.repoConfigured(""))
        assertTrue(UpdateCheck.repoConfigured("someone/some-repo"))
        assertEquals(UpdateResult.NotConfigured, UpdateCheck.check("<OWNER>/<REPO>", "0.1.0"))
    }

    private val json = """{"tag_name":"v0.2.0","html_url":"https://github.com/o/r/releases/tag/v0.2.0",
        "assets":[{"name":"notes.txt","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/notes.txt"},
        {"name":"app.apk","browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/app.apk"}]}"""

    @Test fun newerReleaseWithApk() {
        val r = UpdateCheck.parse("0.1.0", json) as UpdateResult.Newer
        assertEquals("v0.2.0", r.latest)
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/app.apk", r.apkUrl)
    }

    @Test fun sameVersionIsUpToDate() {
        assertTrue(UpdateCheck.parse("0.2.0", json) is UpdateResult.UpToDate)
    }

    @Test fun nonGithubLinksAreRefused() {
        val bad = """{"tag_name":"v9","html_url":"https://evil.example/x","assets":[]}"""
        assertTrue(UpdateCheck.parse("0.1.0", bad) is UpdateResult.Failed)
        val badApk = """{"tag_name":"v9","html_url":"https://github.com/o/r/releases/tag/v9","assets":[{"name":"a.apk","browser_download_url":"https://evil.example/a.apk"}]}"""
        val r = UpdateCheck.parse("0.1.0", badApk) as UpdateResult.Newer
        assertEquals(null, r.apkUrl)
    }

    @Test fun garbageReplyFailsCleanly() {
        assertTrue(UpdateCheck.parse("0.1.0", "<html>") is UpdateResult.Failed)
    }
}
