package dev.msk.norouterdrop

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One live channel to another phone over a Wi-Fi Direct socket.
 *
 * Frames before pairing completes (PAIR_HELLO / HELLO) go plaintext and carry
 * no secrets. After key agreement the two sides hold DIRECTIONAL keys:
 * sendBox encrypts, recvBox decrypts. A fresh random 96-bit GCM IV per frame
 * (collision probability negligible at these volumes), and every envelope
 * carries a monotonic sequence number so replayed frames inside a session are
 * dropped. File bodies ride as raw frames whose plaintext is
 * counter(8) || data, counters enforced in order by the receiver.
 */
class Session(
    val socket: Socket,
    private val io: ExecutorService,
    private val listener: Listener
) {
    interface Listener {
        fun onEnvelope(session: Session, env: Envelope)
        fun onClosed(session: Session)
        fun onNote(text: String) {}
    }

    @Volatile var sendBox: CryptoBox? = null
    @Volatile var recvBox: CryptoBox? = null
    @Volatile var peerId: String? = null
    @Volatile var peerName: String? = null
    @Volatile var rawHandler: ((ByteArray) -> Unit)? = null
    @Volatile var lastRecvSeq: Long = -1

    private val out = socket.getOutputStream()
    private val outLock = Any()
    private val open = AtomicBoolean(true)
    private val sendSeq = AtomicLong(0)

    fun start() = io.execute { readLoop() }

    fun send(env: Envelope) {
        val plain = env.copy(seq = sendSeq.getAndIncrement()).toBytes()
        val payload = sendBox?.encrypt(plain) ?: plain
        sendRaw(Wire.frame(payload))
    }

    val isEncrypted: Boolean get() = sendBox != null && recvBox != null

    /** Android forbids network writes on the main thread (that closed the link right after connect). Main-thread sends go through one ordered writer thread. */
    private val mainWriter = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun sendRaw(bytes: ByteArray) {
        if (!open.get()) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { mainWriter.execute { writeNow(bytes) } } catch (_: Exception) { }
            return
        }
        writeNow(bytes)
    }

    private fun writeNow(bytes: ByteArray) {
        if (!open.get()) return
        synchronized(outLock) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                listener.onNote("Send failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
                close()
            }
        }
    }

    val isOpen: Boolean get() = open.get()

    fun close() {
        if (!open.compareAndSet(true, false)) return
        try { socket.close() } catch (_: Exception) { }
        try { mainWriter.shutdown() } catch (_: Exception) { }
        listener.onClosed(this)
    }

    private fun readLoop() {
        try {
            val ins = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (open.get()) {
                val frame = Wire.readFrame(ins)
                if (frame == null) { listener.onNote("The other phone closed the connection (end of data)"); break }
                val rh = rawHandler
                if (rh != null) {
                    try { rh(frame) } catch (e: Exception) { close(); return }
                    continue
                }
                val b = recvBox
                val plain = if (b != null) b.decrypt(frame) else frame
                if (plain == null) { listener.onNote("Dropped a frame that could not be decrypted"); continue }  // wrong key / tampered / reflected: drop
                val env = Envelope.parse(plain)
                if (env == null) { listener.onNote("Dropped a frame that could not be read (bad format)"); continue }
                peerId = env.fromId
                peerName = env.fromName
                listener.onEnvelope(this, env)
            }
        } catch (e: Exception) { listener.onNote("Read error: ${e.javaClass.simpleName} ${e.message ?: ""}") } finally { close() }
    }
}
