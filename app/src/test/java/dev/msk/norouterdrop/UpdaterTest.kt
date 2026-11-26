package dev.msk.norouterdrop

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class UpdaterTest {
    @Test fun sameSignerNeedsEqualNonEmptySets() {
        assertTrue(Updater.sameSigner(setOf("a"), setOf("a")))
        assertFalse(Updater.sameSigner(setOf("a"), setOf("b")))
        assertFalse(Updater.sameSigner(emptySet(), emptySet()))
    }
    @Test fun digestIsReadFromReleaseJson() {
        val json = """{"tag_name":"v0.3.1","html_url":"https://github.com/o/r/releases/tag/v0.3.1",
          "assets":[{"name":"app.apk","browser_download_url":"https://github.com/o/r/releases/download/v0.3.1/app.apk","digest":"sha256:abc123"}]}"""
        val r = UpdateCheck.parse("0.3.0", json) as UpdateResult.Newer
        assertEquals("abc123", r.sha256)
    }
    @Test fun nonGithubDownloadIsRefused() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = Updater.download(ctx, "https://evil.example/app.apk", null) { }
        assertTrue(r is Updater.Outcome.Failed)
    }
}
