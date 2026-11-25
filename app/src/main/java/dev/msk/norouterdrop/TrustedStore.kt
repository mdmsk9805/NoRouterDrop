package dev.msk.norouterdrop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TrustedPeer(
    val peerId: String,
    var name: String,
    var address: String,      // Wi-Fi Direct MAC, refreshed from discovery
    val keyB64: String,       // shared AES key from pairing; never leaves the two phones
    val addedAt: Long = System.currentTimeMillis()
)

/** Persistent list of phones the user explicitly paired and approved. */
class TrustedStore(context: Context) {
    private val prefs = context.getSharedPreferences("norouterdrop", Context.MODE_PRIVATE)
    private val peers = mutableListOf<TrustedPeer>()

    init { load() }

    @Synchronized fun all(): List<TrustedPeer> = peers.toList()

    @Synchronized fun find(idOrAddress: String): TrustedPeer? =
        peers.firstOrNull { it.peerId == idOrAddress || it.address.equals(idOrAddress, true) }

    @Synchronized fun isTrusted(idOrAddress: String): Boolean = find(idOrAddress) != null

    @Synchronized fun add(peer: TrustedPeer) {
        peers.removeAll { it.peerId == peer.peerId }
        peers.add(peer)
        save()
    }

    @Synchronized fun update(peerId: String, name: String?, address: String?) {
        val p = peers.firstOrNull { it.peerId == peerId } ?: return
        if (!name.isNullOrBlank()) p.name = name
        if (!address.isNullOrBlank()) p.address = address
        save()
    }

    @Synchronized fun remove(peerId: String) {
        peers.removeAll { it.peerId == peerId }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (p in peers) {
            arr.put(JSONObject().apply {
                put("peerId", p.peerId); put("name", p.name)
                put("address", p.address); put("keyB64", p.keyB64); put("addedAt", p.addedAt)
            })
        }
        prefs.edit().putString("peers", arr.toString()).apply()
    }

    private fun load() {
        peers.clear()
        val raw = prefs.getString("peers", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                peers.add(TrustedPeer(o.getString("peerId"), o.getString("name"), o.getString("address"), o.optString("keyB64", ""), o.optLong("addedAt")))
            }
        } catch (_: Exception) { }
    }

    companion object {
        fun localId(context: Context): String {
            val p = context.getSharedPreferences("norouterdrop", Context.MODE_PRIVATE)
            var id = p.getString("localId", null)
            if (id == null) {
                id = newId()
                p.edit().putString("localId", id).apply()
            }
            return id
        }

        fun localName(context: Context): String {
            val p = context.getSharedPreferences("norouterdrop", Context.MODE_PRIVATE)
            return p.getString("localName", null) ?: android.os.Build.MODEL ?: "Android"
        }

        fun setLocalName(context: Context, name: String) {
            context.getSharedPreferences("norouterdrop", Context.MODE_PRIVATE)
                .edit().putString("localName", name).apply()
        }
    }
}
