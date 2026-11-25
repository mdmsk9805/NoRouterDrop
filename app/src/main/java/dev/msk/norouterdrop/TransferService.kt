package dev.msk.norouterdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The always-on heart of NoRouterDrop. Owns the radio link, the trusted-phone list,
 * pairing, and file transfers. Runs as a foreground service so transfers keep
 * working with the screen off.
 *
 * The rule the user asked for, enforced here:
 * a file is accepted automatically ONLY from a phone that was paired in person
 * (verification code confirmed on both screens). Every other offer is declined.
 */
class TransferService : Service() {

    interface UiListener {
        fun onStatus(text: String)
        fun onLog(text: String)
        fun onTrustedChanged()
        fun onDiscovered(peers: List<PeerInfo>)
        fun onPairCode(code: String, peerName: String)
        /** percent 0..100; incoming true = receiving. finished true = last call for this file. */
        fun onProgress(fileName: String, percent: Int, incoming: Boolean, finished: Boolean) {}
    }

    inner class LocalBinder : Binder() { fun service(): TransferService = this@TransferService }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private lateinit var store: TrustedStore
    private lateinit var localId: String
    private lateinit var localName: String
    private lateinit var transport: WifiDirectLink

    private val sessions = CopyOnWriteArrayList<Session>()
    var uiListener: UiListener? = null

    // ---- pairing state ----
    private var pairing: PairingSession? = null
    private var pairingSessionRef: Session? = null
    private var pairingMaster: ByteArray? = null
    private var pairingPeerId: String? = null
    private var pairingPeerName: String? = null
    private var pairingPeerAddress: String? = null
    @Volatile private var localConfirmed = false
    @Volatile private var peerConfirmed = false

    // ---- transfers ----
    private class PendingSend(val peerId: String, val file: File, val displayName: String, val transferId: String) {
        @Volatile var inFlight = false
        @Volatile var tries = 0
    }
    /** Files waiting to go out, in order. A link that is already open is reused for the next file. */
    private val ql = Any()
    private val sendQueue = ArrayList<PendingSend>()
    private val streaming = java.util.concurrent.CopyOnWriteArraySet<Session>()
    @Volatile private var finding = false
    private var deferSince = 0L
    private var deferLogged = false
    private var deferPending = false
    @Volatile private var findingSince = 0L
    @Volatile private var lastActivity = System.currentTimeMillis()
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private class ReceiveState(val out: FileOutputStream, val partFile: File, val expected: Long,
                               val sha: String, val name: String, var nextChunk: Long = 0, var received: Long = 0, var lastPct: Int = -1)
    private val receiving = ConcurrentHashMap<Session, ReceiveState>()

    // ---------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        store = TrustedStore(this)
        localId = TrustedStore.localId(this)
        localName = TrustedStore.localName(this)
        startForeground(NOTIF_ID, buildNotification("${Brand.NAME} is ready"))
        transport = WifiDirectLink(this, transportListener)
        transport.start()
        handler.postDelayed(keepAlive, KEEPALIVE_MS)
    }

    /** Every 10 s: send a tiny PING on idle links (some phones drop a silent Wi-Fi Direct group), close links that were idle for 5 min, release the Wi-Fi/CPU locks when nothing is open. */
    private val keepAlive = object : Runnable {
        override fun run() {
            val busy = synchronized(ql) { sendQueue.any { it.inFlight } }
            val now = System.currentTimeMillis()
            if (finding && now - findingSince > 90_000) finding = false
            for (s in sessions) {
                if (!s.isOpen || !s.isEncrypted) continue
                if (busy || streaming.contains(s) || receiving.containsKey(s)) continue
                s.send(Envelope(Envelope.Kind.PING, localId, localName))
            }
            val queued = synchronized(ql) { sendQueue.isNotEmpty() }
            val open = sessions.any { it.isOpen }
            if (open && !busy && !queued && now - lastActivity > IDLE_CLOSE_MS) {
                log("Link idle for 5 minutes; closing it to save battery (it reopens by itself when you send)")
                for (s in sessions) s.close()
                transport.dropGroup()
            }
            if (!open) releaseLocks()
            handler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private fun touch() { lastActivity = System.currentTimeMillis() }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(
                    if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "${Brand.NAME}:link").apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "${Brand.NAME}:link").apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) { log("Could not hold the Wi-Fi lock: ${e.javaClass.simpleName}") }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAlive)
        releaseLocks()
        transport.stop()
        for (s in sessions) s.close()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ---------------------------------------------------------- public API for the UI

    fun myLabel(): String = "$localName (${shortId(localId)})"

    fun trustedPeers(): List<TrustedPeer> = store.all()

    fun removePeer(peerId: String) {
        store.remove(peerId)
        post { uiListener?.onTrustedChanged() }
        log("Removed a phone from the trusted team")
    }

    @Volatile private var waitingUntil = 0L

    /** "Wait" mode: this phone never sends an invite, only waits for the other phone to invite it. Avoids both phones inviting at once. */
    fun noteTap(what: String) { log("BUTTON: $what on this phone"); }

    fun startWaiting() {
        if (System.currentTimeMillis() < waitingUntil) return   // already waiting; ignore repeat taps
        waitingUntil = System.currentTimeMillis() + 180_000
        cleanupPairing()
        transport.resetRadio("wait mode started")
        log("WAIT MODE (3 min): this phone will not send invites. On the OTHER phone tap 'Start pairing' and pick this phone. Accept the Android invite popup here if it appears.")
        post { uiListener?.onStatus("Waiting for the other phone to invite this phone...") }
        handler.postDelayed({
            if (waitingUntil != 0L && System.currentTimeMillis() >= waitingUntil) { waitingUntil = 0L; log("Wait mode ended (3 min). Tap 'Wait' again if needed.") }
        }, 181_000)
    }

    fun startPairing(peer: PeerInfo) {
        if (System.currentTimeMillis() < waitingUntil) {
            log("This phone is in WAIT mode, so it does not invite. Tap 'Start pairing' on the other phone, or wait for the 3 minutes to end.")
            return
        }
        if (Build.VERSION.SDK_INT < 33) { log("Secure pairing needs Android 13 or newer on BOTH phones"); return }
        if (store.find(peer.address) != null) { log("${peer.name} is already trusted"); return }
        cleanupPairing()
        try {
            pairing = PairingSession()
            pairingPeerAddress = peer.address
            transport.connectTo(peer.address)
            log("Pairing with ${peer.name}...")
        } catch (e: Throwable) { log("Pairing could not start: ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    fun confirmPairing(match: Boolean) {
        val s = pairingSessionRef ?: return
        if (!match) { cancelPairing("codes did not match; nothing was trusted"); return }
        localConfirmed = true
        s.send(Envelope(Envelope.Kind.PAIR_OK, localId, localName))
        maybeFinalizePairing()
    }

    fun cancelPairing(reason: String = "cancelled") {
        log("Pairing $reason")
        cleanupPairing()
    }

    fun sendToPeer(peerId: String, file: File, displayName: String) {
        val job = PendingSend(peerId, file, displayName, newId())
        synchronized(ql) { sendQueue.add(job) }
        touch()
        log("SEND: queued $displayName")
        post { pump() }
    }

    private fun dropJob(job: PendingSend, why: String) {
        synchronized(ql) { sendQueue.remove(job) }
        try { job.file.delete() } catch (_: Exception) { }
        log("SEND FAILED: ${job.displayName} dropped ($why)")
        post { uiListener?.onStatus("Could not send ${job.displayName}") }
    }

    /** Main thread. Offers the next queued file on an open link, or opens a link first. One file in flight at a time. */
    private fun pump() {
        val job: PendingSend
        synchronized(ql) {
            if (sendQueue.any { it.inFlight }) return
            job = sendQueue.firstOrNull() ?: return
        }
        val session = sessions.firstOrNull { it.peerId == job.peerId && it.isEncrypted && it.isOpen }
        if (session != null) {
            deferSince = 0L; deferLogged = false
            job.inFlight = true
            touch()
            offer(session, job)
            handler.postDelayed({
                if (job.inFlight && !streaming.contains(session) && synchronized(ql) { sendQueue.contains(job) }) {
                    job.inFlight = false; job.tries++
                    log("No answer to the offer of ${job.displayName} after 30 s")
                    if (job.tries >= 3) dropJob(job, "no answer") else pump()
                }
            }, 30_000)
            return
        }
        if (finding) return
        val peer = store.find(job.peerId)
        if (peer == null) { dropJob(job, "phone is not trusted"); return }
        // Before inviting: never tear down a group the other phone just formed, wait for its socket; let an incoming invite finish;
        // and break the tie so only one phone invites at a time.
        val now = System.currentTimeMillis()
        if (deferSince == 0L) deferSince = now
        val act = SendPlanner.next(false, transport.groupUp, transport.role, transport.incomingNegotiation, LinkPolicy.iInviteFirst(localId, job.peerId), now - deferSince)
        if (act.step != SendStep.INVITE) {
            if (!deferLogged) { deferLogged = true; log("SEND: waiting before inviting (${act.why})") }
            if (act.step == SendStep.REOPEN_SOCKET) transport.reopenClientSocket()
            if (!deferPending) { deferPending = true; handler.postDelayed({ deferPending = false; pump() }, act.waitMs) }
            return
        }
        deferSince = 0L; deferLogged = false
        finding = true; findingSince = System.currentTimeMillis()
        log("SEND: no open link to ${peer.name}; looking for it nearby (file ${job.displayName})")
        findAndInvite(peer, 0)
    }

    private fun sendFailedRetry(why: String) {
        finding = false
        val job = synchronized(ql) { sendQueue.firstOrNull() } ?: return
        job.tries++
        if (job.tries >= 3) { dropJob(job, why); post { pump() }; return }
        log("$why. Cleaning up and trying again in 5 s (try ${job.tries + 1} of 3)")
        transport.cleanAndRediscover()
        handler.postDelayed({ pump() }, 5000)
    }

    /** Picks the phone currently seen nearby (the stored address is not reliable). */
    private fun findAndInvite(peer: TrustedPeer, tries: Int) {
        val seen = transport.lastPeers
        val pick = seen.firstOrNull { it.address.equals(peer.address, true) && peer.address != "02:00:00:00:00:00" }
            ?: seen.singleOrNull()
            ?: seen.firstOrNull { p -> p.name.contains(peer.name, true) || peer.name.contains(p.name, true) }
        if (pick == null) {
            if (tries >= 15) {
                sendFailedRetry("No phone found nearby after 15 s (${seen.size} seen" + (if (seen.size > 1) ", several and I cannot tell which is ${peer.name}" else "") + ")")
                return
            }
            if (tries == 0) log("Nearby now: " + (if (seen.isEmpty()) "nobody yet, searching..." else seen.joinToString { it.name }))
            if (tries == 6) transport.cleanAndRediscover()
            handler.postDelayed({ findAndInvite(peer, tries + 1) }, 1000)
            return
        }
        log("SEND: inviting ${pick.name} (${pick.address}) as the phone for ${peer.name}")
        transport.connectTo(pick.address, pick.name)
    }

    // ---------------------------------------------------------- transport events

    private val transportListener = object : WifiDirectLink.Listener {
        override fun onStatus(text: String) = post { uiListener?.onStatus(text) }
        override fun onPeers(peers: List<PeerInfo>) = post { uiListener?.onDiscovered(peers) }
        override fun onSocketReady(socket: Socket, weAreGroupOwner: Boolean) {
            waitingUntil = 0L
            touch()
            post { holdLocks() }
            val s = Session(socket, io, sessionListener)
            sessions.add(s)
            s.start()
            val p = pairing
            log("Step 7: socket ready (this phone is ${if (weAreGroupOwner) "owner/server" else "client"}), " + if (p != null && pairingSessionRef == null) "sending my pairing key" else "no pairing started here, sending a plain hello")
            if (p != null && pairingSessionRef == null) handler.postDelayed({
                if (pairing === p && pairingPeerId == null) log("TIMEOUT: connected but no pairing key from the other phone after 20 s. Did the other phone open ${Brand.NAME} (not the normal app) and is it on Android 13+?")
            }, 20_000)
            if (p != null && pairingSessionRef == null) {
                pairingSessionRef = s
                s.send(Envelope(Envelope.Kind.PAIR_HELLO, localId, localName,
                    text = Base64.encodeToString(p.publicKeyEncoded, Base64.NO_WRAP),
                    address = transport.myAddress ?: ""))
                log("Sent PAIR_HELLO (my key)")
            } else {
                s.send(Envelope.hello(localId, localName, transport.myAddress ?: ""))
                log("Sent HELLO")
            }
        }
        override fun onLinkClosed() { }
        override fun onConnectFailed() { post { sendFailedRetry("The invite did not work") } }
        override fun onLog(text: String) { log(text) }
    }

    private val sessionListener = object : Session.Listener {
        override fun onNote(text: String) { log(text) }
        override fun onEnvelope(session: Session, env: Envelope) {
            log("Step 8: received ${env.kind} from ${env.fromName}")
            try { handle(session, env) } catch (e: Throwable) {
                log("ERROR handling ${env.kind}: ${e.javaClass.simpleName} ${e.message ?: ""}")
                session.close()
            }
        }
        override fun onClosed(session: Session) {
            sessions.remove(session)
            transport.socketClosed(session.socket)
            // The group may still be up (only the channel dropped): a client reopens its channel to the owner.
            handler.postDelayed({ if (sessions.none { it.isOpen }) transport.reopenClientSocket() }, 1500)
            receiving.remove(session)?.let {
                try { it.out.close() } catch (_: Exception) { }
                it.partFile.delete()
                log("${it.name} incomplete; partial file removed")
            }
            streaming.remove(session)
            // A file that was on its way goes back to the queue and is sent again over a new link.
            val hadJob = synchronized(ql) { val j = sendQueue.firstOrNull { it.inFlight && it.peerId == session.peerId }; if (j != null) { j.inFlight = false; j.tries++ }; j }
            if (hadJob != null) {
                if (hadJob.tries >= 3) dropJob(hadJob, "link kept closing") else { log("Link closed while ${hadJob.displayName} was waiting; will send again"); finding = false; handler.postDelayed({ pump() }, 3000) }
            }
            log("Link to the other phone closed" + if (session == pairingSessionRef) " during pairing; pairing cancelled" else "")
            if (session == pairingSessionRef) cleanupPairing()
            post { uiListener?.onStatus("Link closed") }
        }
    }

    // ---------------------------------------------------------- envelope handling (io threads)

    private fun handle(session: Session, env: Envelope) {
        if (session.recvBox != null) {
            if (env.seq <= session.lastRecvSeq) return  // replayed frame: drop
            session.lastRecvSeq = env.seq
        }
        when (env.kind) {
            Envelope.Kind.HELLO -> onHello(session, env)
            Envelope.Kind.PAIR_HELLO -> onPairHello(session, env)
            Envelope.Kind.PAIR_OK -> { if (session == pairingSessionRef) { peerConfirmed = true; maybeFinalizePairing() } }
            Envelope.Kind.OFFER -> onOffer(session, env)
            Envelope.Kind.ACCEPT -> onAccept(session)
            Envelope.Kind.DECLINE -> {
                log("${session.peerName ?: "The other phone"} declined the file (${env.text})")
                val j = synchronized(ql) { sendQueue.firstOrNull { it.inFlight } }
                if (j != null) { dropJob(j, "declined: ${env.text}"); post { pump() } }
            }
            Envelope.Kind.DONE -> { touch() }
            Envelope.Kind.PING -> { }
        }
    }

    private fun onHello(session: Session, env: Envelope) {
        val trusted = store.find(env.fromId)
        if (trusted == null) {
            log("An unknown phone connected (${shortId(env.fromId)}). Pair it first if it is yours. Closing only that channel.")
            session.close()
            return
        }
        val master = KeystoreWrap.unwrap(trusted.keyB64)
        if (master == null) { log("Could not unlock the stored key for ${trusted.name}; pair again"); return }
        val (sk, rk) = LinkKeys.directional(master, localId, env.fromId)
        session.sendBox = CryptoBox(sk)
        session.recvBox = CryptoBox(rk)
        if (env.address.isNotBlank()) store.update(env.fromId, env.fromName, env.address) else store.update(env.fromId, env.fromName, null)
        for (old in LinkRegistry.olderToClose(sessions.toList(), session, { it.peerId == env.fromId }, { it.isOpen })) {
            log("A second channel from ${trusted.name} arrived; closing the older one and keeping the new one")
            old.close()
        }
        log("Linked with ${trusted.name} (trusted)")
        post { uiListener?.onStatus("${trusted.name} is here") }
        finding = false
        touch()
        post { pump() }
    }

    private fun onPairHello(session: Session, env: Envelope) {
        if (Build.VERSION.SDK_INT < 33) { cancelPairing("needs Android 13+ on both phones"); return }
        val existing = store.find(env.fromId)
        if (existing != null) {
            val master = KeystoreWrap.unwrap(existing.keyB64)
            if (master != null) {
                val (sk, rk) = LinkKeys.directional(master, localId, env.fromId)
                session.sendBox = CryptoBox(sk)
                session.recvBox = CryptoBox(rk)
            }
            session.send(Envelope.hello(localId, localName, transport.myAddress ?: ""))
            return
        }
        val p = pairing ?: PairingSession().also { pairing = it }
        if (pairingSessionRef == null) {
            pairingSessionRef = session
            session.send(Envelope(Envelope.Kind.PAIR_HELLO, localId, localName,
                text = Base64.encodeToString(p.publicKeyEncoded, Base64.NO_WRAP),
                address = transport.myAddress ?: ""))
        }
        val peerPub = try { Base64.decode(env.text, Base64.NO_WRAP) } catch (e: Exception) { null }
        if (peerPub == null) { cancelPairing("got a bad key"); return }
        val result = p.finish(peerPub)
        if (result == null) { cancelPairing("key exchange failed"); return }
        val (master, code) = result
        pairingMaster = master
        pairingPeerId = env.fromId
        pairingPeerName = env.fromName
        if (env.address.isNotBlank()) pairingPeerAddress = env.address
        val (sk, rk) = LinkKeys.directional(master, localId, env.fromId)
        session.sendBox = CryptoBox(sk)
        session.recvBox = CryptoBox(rk)
        post { uiListener?.onPairCode(code, env.fromName) }
        log("Check the code on both phones")
    }

    private fun maybeFinalizePairing() {
        if (!localConfirmed || !peerConfirmed) return
        val pid = pairingPeerId ?: return
        val master = pairingMaster ?: return
        val name = pairingPeerName ?: "Phone"
        store.add(TrustedPeer(pid, name, pairingPeerAddress ?: "", KeystoreWrap.wrap(master)))
        log("$name joined your trusted team")
        cleanupPairing()
        post {
            uiListener?.onTrustedChanged()
            uiListener?.onStatus("$name is trusted now")
        }
    }

    private fun onOffer(session: Session, env: Envelope) {
        val trusted = store.find(env.fromId)
        if (trusted == null || !session.isEncrypted) {
            session.send(Envelope(Envelope.Kind.DECLINE, localId, localName, text = "not trusted"))
            log("Declined a file from an unknown phone")
            return
        }
        val free = try { android.os.StatFs(filesDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
        if (env.fileSize > free - 50L * 1024 * 1024) {
            session.send(Envelope(Envelope.Kind.DECLINE, localId, localName, text = "storage full"))
            log("${env.fileName} declined: not enough free storage")
            return
        }
        // Trusted phone: accept automatically. No tap. That is the point.
        val target = targetFile(env.fileName)
        try {
            receiving[session] = ReceiveState(FileOutputStream(target), target, env.fileSize, env.sha256Hex, env.fileName)
        } catch (e: Exception) {
            session.send(Envelope(Envelope.Kind.DECLINE, localId, localName, text = "storage error"))
            return
        }
        session.rawHandler = { frame -> onFileFrame(session, frame) }
        session.send(Envelope(Envelope.Kind.ACCEPT, localId, localName, transferId = env.transferId))
        log("Receiving ${env.fileName} (${env.fileSize / 1024} KB) from ${trusted.name}, no tap needed")
        touch()
        post { uiListener?.onProgress(env.fileName, 0, true, false) }
        post { uiListener?.onStatus("Receiving ${env.fileName}...") }
    }

    private fun onAccept(session: Session) {
        val job = synchronized(ql) { sendQueue.firstOrNull { it.inFlight } } ?: return
        streamFile(session, job)
    }

    private fun onFileFrame(session: Session, frame: ByteArray) {
        val st = receiving[session] ?: return
        val box = session.recvBox ?: return
        val plain = box.decrypt(frame) ?: return
        if (plain.size < 8) { abortReceive(session, st); return }
        val counter = java.nio.ByteBuffer.wrap(plain, 0, 8).long
        if (counter != st.nextChunk) { log("Chunk order broken; aborting ${st.name}"); abortReceive(session, st); return }
        st.nextChunk++
        try { st.out.write(plain, 8, plain.size - 8) } catch (e: Exception) { session.close(); return }
        st.received += (plain.size - 8)
        touch()
        if (st.expected > 0) {
            val pct = (st.received * 100 / st.expected).toInt().coerceIn(0, 100)
            if (pct != st.lastPct) { st.lastPct = pct; post { uiListener?.onProgress(st.name, pct, true, false) } }
        }
        if (st.received >= st.expected && st.expected > 0) {
            st.out.close()
            session.rawHandler = null
            receiving.remove(session)
            if (st.received != st.expected || sha256Hex(st.partFile) != st.sha) {
                log("${st.name} failed its integrity check; deleted")
                st.partFile.delete()
                post { uiListener?.onProgress(st.name, 0, true, true) }
                return
            }
            val where = savePublic(st.partFile, st.name)
            log("Got ${st.name} ✓  saved in $where")
            post { uiListener?.onProgress(st.name, 100, true, true) }
            session.send(Envelope(Envelope.Kind.DONE, localId, localName))
            notifyFile(st.name)
            post { uiListener?.onStatus("Received ${st.name}") }
        }
    }

    private fun abortReceive(session: Session, st: ReceiveState) {
        try { st.out.close() } catch (_: Exception) { }
        st.partFile.delete()
        receiving.remove(session)
        session.rawHandler = null
        session.send(Envelope(Envelope.Kind.DECLINE, localId, localName, text = "transfer aborted"))
    }

    @Volatile private var lastSavedWhere = "Downloads/${Brand.NAME}"

    /** Moves a verified file into public Downloads/NoRouterDrop (MediaStore, Android 10+). Below Android 10 it stays in the app folder. */
    private fun savePublic(part: File, rawName: String): String {
        val safe = FileNames.safe(rawName)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/${Brand.NAME}")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("cannot create file")
                contentResolver.openOutputStream(uri)!!.use { o -> part.inputStream().use { it.copyTo(o) } }
                contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                part.delete()
                lastSavedWhere = "Downloads/${Brand.NAME}"
                return lastSavedWhere
            } catch (e: Exception) {
                log("Could not save to public Downloads (${e.javaClass.simpleName}); keeping it in the app folder")
            }
        }
        val final = finalName(rawName)
        part.renameTo(final)
        lastSavedWhere = "app folder ${final.parent}"
        return lastSavedWhere
    }

    private fun finalName(name: String): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "${Brand.NAME}")
        val safe = FileNames.safe(name)   // peer-supplied name: strip path parts / illegal chars
        dir.mkdirs()
        var f = File(dir, safe)
        var i = 1
        while (f.exists()) {
            val dot = safe.lastIndexOf('.')
            f = File(dir, if (dot > 0) "${safe.substring(0, dot)} ($i).${safe.substring(dot + 1)}" else "$safe ($i)")
            i++
        }
        return f
    }

    private fun offer(session: Session, job: PendingSend) {
        session.send(Envelope(Envelope.Kind.OFFER, localId, localName,
            transferId = job.transferId, fileName = job.displayName, fileSize = job.file.length(), sha256Hex = sha256Hex(job.file)))
        log("Offering ${job.displayName} to ${session.peerName ?: "phone"}...")
    }

    private fun streamFile(session: Session, job: PendingSend) {
        val box = session.sendBox ?: return
        streaming.add(session)
        io.execute {
            var ok = false
            try {
                val total = job.file.length().coerceAtLeast(1)
                job.file.inputStream().buffered().use { ins ->
                    val buf = ByteArray(60 * 1024)
                    var sent = 0L
                    var counter = 0L
                    var lastPct = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        val payload = java.nio.ByteBuffer.allocate(8 + n).putLong(counter++).put(buf, 0, n).array()
                        session.sendRaw(Wire.frame(box.encrypt(payload)))
                        sent += n
                        touch()
                        val pct = (sent * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; post { uiListener?.onProgress(job.displayName, pct, false, false) } }
                    }
                }
                if (!session.isOpen) throw java.io.IOException("link closed while sending")
                session.send(Envelope(Envelope.Kind.DONE, localId, localName, transferId = job.transferId))
                ok = true
                log("Sent ${job.displayName} ✓")
                post { uiListener?.onProgress(job.displayName, 100, false, true); uiListener?.onStatus("Sent ${job.displayName}") }
            } catch (e: Exception) {
                log("Send of ${job.displayName} failed midway (${e.javaClass.simpleName}); it will be sent again")
                post { uiListener?.onProgress(job.displayName, 0, false, true) }
                session.close()
            } finally {
                streaming.remove(session)
                if (ok) {
                    synchronized(ql) { sendQueue.remove(job) }
                    try { job.file.delete() } catch (_: Exception) { }   // it was a cache copy
                    post { pump() }
                }
                // on failure the session closes; onClosed puts the job back for another try
            }
        }
    }

    // ---------------------------------------------------------- helpers

    /** Strips any path components (traversal-proof) and lands on a .part name. */
    private fun targetFile(rawName: String): File {
        var safe = rawName.replace('\\', '/').substringAfterLast('/').trim()
        if (safe.isBlank() || safe == "." || safe == "..") safe = "received.bin"
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "${Brand.NAME}")
        dir.mkdirs()
        var f = File(dir, "$safe.part")
        var i = 1
        while (f.exists()) f = File(dir, "$safe.${i++}.part")
        return f
    }

    private fun cleanupPairing() {
        pairing = null
        pairingSessionRef = null
        pairingMaster = null
        pairingPeerId = null
        pairingPeerName = null
        pairingPeerAddress = null
        localConfirmed = false
        peerConfirmed = false
    }

    private fun log(text: String) { AppLog.add(text); post { uiListener?.onLog(text) } }
    private fun post(block: () -> Unit) {
        handler.post { block() }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "${Brand.NAME}", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${Brand.NAME}")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyFile(name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("File received")
            .setContentText("$name saved in $lastSavedWhere")
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }

    companion object {
        private const val CHANNEL = "norouterdrop"
        private const val NOTIF_ID = 1
        private const val KEEPALIVE_MS = 10_000L
        private const val IDLE_CLOSE_MS = 5 * 60_000L
    }
}
