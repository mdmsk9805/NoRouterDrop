package dev.msk.norouterdrop

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** One envelope on the wire. JSON, length-prefixed (4-byte big-endian) on the socket. */
data class Envelope(
    val kind: Kind,
    val fromId: String,
    val fromName: String,
    val transferId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val sha256Hex: String = "",
    val text: String = "",
    val address: String = "",
    val seq: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Kind { HELLO, PAIR_HELLO, PAIR_OK, OFFER, ACCEPT, DECLINE, DONE, PING }

    fun toBytes(): ByteArray = JSONObject().apply {
        put("kind", kind.name)
        put("fromId", fromId)
        put("fromName", fromName)
        put("transferId", transferId)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("sha256Hex", sha256Hex)
        put("text", text)
        put("address", address)
        put("seq", seq)
        put("timestamp", timestamp)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): Envelope? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Envelope(
                kind = Kind.valueOf(o.getString("kind")),
                fromId = o.getString("fromId"),
                fromName = o.optString("fromName", "?"),
                transferId = o.optString("transferId", ""),
                fileName = o.optString("fileName", ""),
                fileSize = o.optLong("fileSize", 0),
                sha256Hex = o.optString("sha256Hex", ""),
                text = o.optString("text", ""),
                address = o.optString("address", ""),
                seq = o.optLong("seq", 0),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) { null }

        fun hello(id: String, name: String, address: String = "") =
            Envelope(Kind.HELLO, fromId = id, fromName = name, address = address)
    }
}

object Wire {
    fun frame(payload: ByteArray): ByteArray {
        val out = java.nio.ByteBuffer.allocate(4 + payload.size)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    /** Reads one frame; null on clean close. Throws on malformed length. */
    fun readFrame(input: java.io.DataInputStream): ByteArray? {
        val len = try { input.readInt() } catch (e: java.io.EOFException) { return null }
        require(len in 1..2_000_000) { "bad frame length" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }
}

fun sha256Hex(file: java.io.File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun shortId(id: String): String = if (id.length >= 8) id.substring(0, 8) else id

fun newId(): String = UUID.randomUUID().toString()

/** Peer-supplied file names are untrusted: keep the base name only, strip illegal characters. */
object FileNames {
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    fun safe(raw: String): String {
        var n = raw.replace('\\', '/').substringAfterLast('/')
        n = n.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim(' ', '.')
        if (n.isEmpty()) n = "unnamed"
        if (n.substringBefore('.').uppercase() in reserved) n = "_$n"
        return n.take(150)
    }
}
