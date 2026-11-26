package dev.msk.norouterdrop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A short list of the files this phone sent or received (name, direction, time, result). Stored only on the phone, last 100 entries. */
data class HistoryEntry(val ts: Long, val incoming: Boolean, val name: String, val ok: Boolean, val peer: String) {
    fun line(): String {
        val t = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ts))
        return (if (incoming) "Received  " else "Sent  ") + name + (if (ok) "" else "  (failed)") + "\n" + (if (incoming) "from " else "to ") + peer + " · " + t
    }
}

object HistoryStore {
    private const val FILE = "norouterdrop_history"
    private const val KEY = "entries"
    const val MAX = 100

    fun prefOn(ctx: Context): Boolean = ctx.getSharedPreferences("norouterdrop_ui", Context.MODE_PRIVATE).getBoolean("history_on", true)

    @Synchronized fun add(ctx: Context, e: HistoryEntry) {
        if (!prefOn(ctx)) return
        val list = all(ctx).toMutableList()
        list.add(0, e)
        save(ctx, list.take(MAX))
    }

    @Synchronized fun all(ctx: Context): List<HistoryEntry> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { val o = a.getJSONObject(it); HistoryEntry(o.getLong("ts"), o.getBoolean("in"), o.getString("name"), o.getBoolean("ok"), o.optString("peer")) }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun clear(ctx: Context) { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply() }

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("ts", e.ts).put("in", e.incoming).put("name", e.name).put("ok", e.ok).put("peer", e.peer))
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
