package dev.msk.norouterdrop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Result of asking GitHub Releases for the newest version. Nothing is downloaded or installed here. */
sealed class UpdateResult {
    object NotConfigured : UpdateResult()
    object NoRelease : UpdateResult()
    data class UpToDate(val current: String) : UpdateResult()
    data class Newer(val current: String, val latest: String, val pageUrl: String, val apkUrl: String?) : UpdateResult()
    data class Failed(val why: String) : UpdateResult()
}

object UpdateCheck {
    /** "owner/repo" from strings.xml. A value with a "<" is still a placeholder. */
    fun repoConfigured(repo: String) = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo.trim())

    /** True when [latest] is a higher dotted number than [current]. A leading "v" and any "-suffix" are ignored. */
    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current); val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** Reads the GitHub "latest release" JSON. Only https://github.com links are accepted, so a bad reply cannot send the user elsewhere. */
    fun parse(current: String, json: String): UpdateResult = try {
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        val page = o.getString("html_url")
        var apk: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
        }
        fun ok(u: String?) = u != null && u.startsWith("https://github.com/")
        if (!ok(page)) UpdateResult.Failed("unexpected reply")
        else if (isNewer(current, tag)) UpdateResult.Newer(current, tag, page, apk?.takeIf { ok(it) })
        else UpdateResult.UpToDate(current)
    } catch (e: Exception) { UpdateResult.Failed("could not read the reply") }

    /** Blocking; call from a background thread. One HTTPS GET to api.github.com, no account, no personal data. */
    fun check(repo: String, current: String): UpdateResult {
        if (!repoConfigured(repo)) return UpdateResult.NotConfigured
        return try {
            val c = URL("https://api.github.com/repos/${repo.trim()}/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "${Brand.NAME}-update-check")
            when (c.responseCode) {
                404 -> UpdateResult.NoRelease
                200 -> parse(current, c.inputStream.bufferedReader().use { it.readText() })
                403, 429 -> UpdateResult.Failed("GitHub is limiting requests, try later")
                else -> UpdateResult.Failed("GitHub answered ${c.responseCode}")
            }
        } catch (e: java.io.IOException) { UpdateResult.Failed("no internet connection") }
        catch (e: Exception) { UpdateResult.Failed(e.javaClass.simpleName) }
    }
}
