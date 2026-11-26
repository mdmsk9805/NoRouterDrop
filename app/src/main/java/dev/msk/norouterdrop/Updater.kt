package dev.msk.norouterdrop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manual in-app update: downloads the APK of a GitHub release into the app cache, checks it, then hands it to Android's own installer.
 * Android shows its normal install screen; nothing is installed without that tap. Runs only when the user taps the button.
 */
object Updater {
    sealed class Outcome {
        data class Ready(val file: File) : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    private const val MAX_BYTES = 80L * 1024 * 1024

    /** Only https links on github.com (release downloads redirect to GitHub's own asset hosts). Blocking: call from a background thread. */
    fun download(ctx: Context, url: String, expectedSha256: String?, onProgress: (Int) -> Unit): Outcome {
        if (!url.startsWith("https://github.com/")) return Outcome.Failed("unexpected download address")
        val dir = File(ctx.cacheDir, "updates"); dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        return try {
            var u = URL(url)
            var c: HttpURLConnection? = null
            for (hop in 0..5) {   // follow redirects by hand, https only
                val cc = u.openConnection() as HttpURLConnection
                cc.instanceFollowRedirects = false
                cc.connectTimeout = 15_000; cc.readTimeout = 20_000
                cc.setRequestProperty("User-Agent", "${Brand.NAME}-update")
                val code = cc.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = cc.getHeaderField("Location") ?: return Outcome.Failed("bad redirect")
                    cc.disconnect()
                    u = URL(u, loc)
                    if (u.protocol != "https") return Outcome.Failed("download was redirected to a non-secure address")
                    continue
                }
                if (code != 200) { cc.disconnect(); return Outcome.Failed("download answered $code") }
                c = cc; break
            }
            val conn = c ?: return Outcome.Failed("too many redirects")
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) return Outcome.Failed("file is too large")
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = -1
            conn.inputStream.use { ins -> out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    os.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done > MAX_BYTES) return Outcome.Failed("file is too large")
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            } }
            conn.disconnect()
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !hex.equals(expectedSha256, true)) { out.delete(); return Outcome.Failed("checksum does not match GitHub's record") }
            val why = checkApk(ctx, out)
            if (why != null) { out.delete(); return Outcome.Failed(why) }
            Outcome.Ready(out)
        } catch (e: java.io.IOException) { Outcome.Failed("download failed (no internet?)") }
        catch (e: Exception) { Outcome.Failed(e.javaClass.simpleName) }
    }

    /** Same app id and same signing key as the installed app. Otherwise Android would refuse the update anyway. Null = fine. */
    fun checkApk(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "the downloaded file is not a valid app"
        if (info.packageName != ctx.packageName) return "the downloaded app is a different app"
        val mine = try { pm.getPackageInfo(ctx.packageName, flags) } catch (e: Exception) { return null }
        return if (sameSigner(sigs(info), sigs(mine))) null
        else "the new version is signed with a different key, so Android will not install it over this one. Uninstall this app first (pairing will be lost) or install the new one by hand."
    }

    @Suppress("DEPRECATION")
    private fun sigs(i: android.content.pm.PackageInfo): Set<String> {
        val arr = if (Build.VERSION.SDK_INT >= 28) i.signingInfo?.apkContentsSigners else i.signatures
        return arr?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    fun sameSigner(a: Set<String>, b: Set<String>) = a.isNotEmpty() && a == b

    /** Needs the one-time "install unknown apps" switch for this app. Returns false after opening that Android screen. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
