package dev.msk.norouterdrop

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

data class PeerInfo(val address: String, val name: String, val available: Boolean)

/**
 * Discovers nearby phones over Wi-Fi Direct and opens a TCP socket between two
 * of them. No router, no internet, no account: the radio link is the network.
 *
 * Honest limits of stock Android Wi-Fi Direct:
 *  - A connection forms a "group": one phone is group owner, the other joins it.
 *  - Some phones show a system "accept connection" dialog on the invited side.
 *    That dialog is Android's, not this app's, and cannot be skipped without root.
 */
@SuppressLint("MissingPermission") // every entry point is guarded by PermCheck / try-SecurityException
class WifiDirectLink(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onPeers(peers: List<PeerInfo>)
        fun onSocketReady(socket: Socket, weAreGroupOwner: Boolean)
        fun onLinkClosed()
        /** The invite could not be completed after every automatic retry. */
        fun onConnectFailed() {}
        fun onLog(text: String) {}
    }

    enum class State { OFF, DISCOVERING, CONNECTING, CONNECTED }

    var state = State.OFF
        private set
    @Volatile var lastPeers: List<PeerInfo> = emptyList()
    @Volatile var iAmInvited = false
    var myAddress: String? = null
        private set

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private var receiverRegistered = false
    private var serverSocket: ServerSocket? = null
    private val openSockets = mutableListOf<Socket>()
    private var connectingTo: String? = null
    private var negotiatingAt = 0L
    @Volatile private var roleField = LinkRole.NONE
    @Volatile private var goAddr: String? = null
    private val clientBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** This phone's role in the current group (NONE when no group is up). */
    val role: LinkRole get() = if (state == State.CONNECTED) roleField else LinkRole.NONE
    /** The service tells us a channel closed, so a closed socket is not counted as the open link. */
    fun socketClosed(s: Socket) { synchronized(openSockets) { openSockets.remove(s) } }
    /** Client side: open the channel to the group owner again while the group is still up. */
    fun reopenClientSocket() {
        val a = goAddr
        if (state == State.CONNECTED && roleField == LinkRole.CLIENT && a != null && synchronized(openSockets) { openSockets.none { !it.isClosed } }) becomeClient(a)
    }
    private var discBusy = 0
    private var discNextOk = 0L
    private var discRetryPending = false

    /** A Wi-Fi Direct group is up (this phone may be waiting for the other phone to open the socket). */
    val groupUp: Boolean get() = state == State.CONNECTED
    /** The other phone's invite is being negotiated right now (or an invite is pending here). */
    val incomingNegotiation: Boolean get() = iAmInvited || (negotiatingAt != 0L && System.currentTimeMillis() - negotiatingAt < LinkPolicy.INCOMING_WAIT_MS)

    private val discoverTick = object : Runnable {
        override fun run() {
            if (state != State.OFF) {
                if (state != State.CONNECTING) tryDiscover()   // keep this phone visible to the other one
                handler.postDelayed(this, 15_000)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!on) listener.onStatus("Wi-Fi is off; turn Wi-Fi on for direct transfer")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val d = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    myAddress = d?.deviceAddress
                    iAmInvited = d?.status == WifiP2pDevice.INVITED
                    listener.onLog("This phone's Wi-Fi Direct status: " + when (d?.status) {
                        WifiP2pDevice.CONNECTED -> "CONNECTED"; WifiP2pDevice.INVITED -> "INVITED (an invite is pending here)"
                        WifiP2pDevice.FAILED -> "FAILED"; WifiP2pDevice.AVAILABLE -> "AVAILABLE"; WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"; else -> "unknown" })
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ni = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    listener.onLog("Step 3: connection change, connected=${ni?.isConnected} state=${ni?.detailedState}")
                    if (ni != null && ni.isConnected) { negotiatingAt = 0L; requestInfo() }
                    else if (state != State.CONNECTING && state != State.CONNECTED && ni?.detailedState == NetworkInfo.DetailedState.CONNECTING) {
                        // The other phone is inviting this one. Do not tear anything down and do not invite back.
                        if (negotiatingAt == 0L) listener.onLog("The other phone is connecting to this one; waiting for it")
                        negotiatingAt = System.currentTimeMillis()
                    }
                    else if (state == State.CONNECTING && ni?.detailedState == NetworkInfo.DetailedState.FAILED && connectingTo != null) handleConnectFailed(connectingTo!!)
                    else handleGroupGone()
                }
            }
        }
    }

    fun start() {
        if (manager == null) { listener.onStatus("This phone has no Wi-Fi Direct"); return }
        if (state != State.OFF) return
        channel = manager.initialize(context, Looper.getMainLooper(), null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                context.registerReceiver(receiver, filter)
            receiverRegistered = true
        }
        state = State.DISCOVERING
        listener.onStatus("Looking for nearby phones...")
        resetRadio("app start")
        handler.postDelayed(discoverTick, 2500)
    }

    fun stop() {
        handler.removeCallbacks(discoverTick)
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        try { manager?.removeGroup(channel, null) } catch (_: Exception) { }
        closeSockets()
        state = State.OFF
        connectingTo = null
        listener.onStatus("${Brand.NAME} is off")
    }

    /** Ask for a connection to a discovered peer. Android negotiates who owns the group. */
    fun connectTo(address: String, name: String? = null) {
        if (state == State.CONNECTING) { listener.onLog("Already connecting; not starting a second invite"); return }
        connectName = name
        if (state == State.CONNECTED) {
            // (The send queue already waited for the other phone to connect to this group before it got here.)
            // The group can outlive the socket (the link closed but Android still shows the group). Tear it down and invite again.
            listener.onLog("Old link was still marked connected but has no open channel; tearing it down before the new invite")
            closeSockets()
            state = State.DISCOVERING
        }
        attempt = 1
        // Clear any leftover half-open connection or group first, then connect after a short pause.
        if (iAmInvited) {
            listener.onLog("This phone has an invite pending from the other phone. Not sending a second invite (that makes both fail). Accept the Android popup, or look in the notification shade.")
            listener.onStatus("Invite pending here: accept it")
            return
        }
        state = State.CONNECTING
        // Light clean-up only (pending connect and old group). Do NOT stop discovery or clear peers: connecting to a phone that is
        // not in the current peer list gives "internal error" (code 0).
        val ch = channel
        listener.onLog("Cleaning the old connect/group (discovery stays on)")
        try {
            manager?.cancelConnect(ch, act("cancelConnect"))
            manager?.removeGroup(ch, act("removeGroup"))
        } catch (e: SecurityException) { listener.onLog("Cleaning needs the Nearby devices permission") }
        handler.postDelayed({ tryDiscover(); waitForPeer(address, name, 0) }, 1500)
    }

    /** Waits until the target phone is in the fresh peer list as AVAILABLE (up to 15 s), then connects with that fresh entry. */
    private fun waitForPeer(address: String, name: String?, tries: Int) {
        if (state != State.CONNECTING) return
        val p = lastPeers.firstOrNull { it.address.equals(address, true) && it.available }
            ?: lastPeers.firstOrNull { name != null && it.name == name && it.available }
        if (p != null) { listener.onLog("Target is in the fresh peer list (${p.name}, available); inviting now"); doConnect(p.address); return }
        if (tries >= 15) { retryOrGiveUp(address, "Target not available in the peer list after 15 s"); return }
        if (tries % 4 == 3) {
            // Discovery can get stuck after a group was removed: stop it, wait, start it again.
            try { manager?.stopPeerDiscovery(channel, null) } catch (_: Exception) { }
            handler.postDelayed({ if (state == State.CONNECTING) tryDiscover() }, 1500)
        }
        handler.postDelayed({ waitForPeer(address, name, tries + 1) }, 1000)
    }

    private var connectName: String? = null
    private val maxAttempts = 3

    /** After a failed invite (code 0, FAILED, timeout): full clean (stop discovery, drop group, wait), restart discovery, try again. Gives up after 3 attempts. */
    private fun retryOrGiveUp(address: String, why: String) {
        handler.removeCallbacks(connectTimeout)
        if (state != State.CONNECTING) return
        if (attempt >= maxAttempts) {
            state = State.DISCOVERING
            connectingTo = null
            listener.onLog("$why. Gave up after $maxAttempts attempts.")
            listener.onStatus("Could not connect to the other phone")
            cleanAndRediscover()
            listener.onConnectFailed()
            return
        }
        attempt++
        listener.onLog("$why. Full clean, then attempt $attempt of $maxAttempts")
        fullClean { if (state == State.CONNECTING) waitForPeer(address, connectName, 0) }
    }

    /** stop discovery, cancel connect, remove group, wait, restart discovery, then run [then]. */
    private fun fullClean(then: (() -> Unit)?) {
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, act("stopPeerDiscovery")) } catch (_: SecurityException) { }
        handler.postDelayed({
            try { manager?.cancelConnect(ch, act("cancelConnect")); manager?.removeGroup(ch, act("removeGroup")) } catch (_: SecurityException) { }
        }, 700)
        handler.postDelayed({
            try { manager?.clearLocalServices(ch, null); manager?.clearServiceRequests(ch, null) } catch (_: SecurityException) { }
        }, 1500)
        handler.postDelayed({ tryDiscover(); then?.invoke() }, 3500)
    }

    /** Public: used after a failed send so both phones see each other again. */
    fun cleanAndRediscover() {
        if (state == State.CONNECTING || state == State.OFF) return
        fullClean(null)
    }

    /** Public: drop the Wi-Fi Direct group (used when a link has been idle for a long time). */
    fun dropGroup() {
        closeSockets()
        try { manager?.removeGroup(channel, act("removeGroup")) } catch (_: Exception) { }
        if (state != State.OFF) state = State.DISCOVERING
        handler.postDelayed({ tryDiscover() }, 2000)
    }

    private var attempt = 1

    private fun act(name: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { listener.onLog("  $name: ok") }
        override fun onFailure(reason: Int) { listener.onLog("  $name: code $reason (${reasonText(reason)})") }
    }

    /** Clears every leftover Wi-Fi Direct state: pending connect, group, service requests, discovery. Then rediscovers after a pause. */
    fun resetRadio(why: String, rediscover: Boolean = true) {
        val ch = channel ?: return
        listener.onLog("Cleaning Wi-Fi Direct state ($why)")
        try {
            manager?.cancelConnect(ch, act("cancelConnect"))
            manager?.removeGroup(ch, act("removeGroup"))
            manager?.clearLocalServices(ch, act("clearLocalServices"))
            manager?.clearServiceRequests(ch, act("clearServiceRequests"))
            manager?.stopPeerDiscovery(ch, act("stopPeerDiscovery"))
        } catch (e: SecurityException) { listener.onLog("Cleaning needs the Nearby devices permission") }
        if (rediscover) handler.postDelayed({ listener.onLog("Rediscovering nearby phones"); tryDiscover() }, 1500)
    }

    private fun handleConnectFailed(address: String) {
        retryOrGiveUp(address, "Invite FAILED (Android state FAILED): the other phone declined, ignored it, or was not ready")
    }

    private fun doConnect(address: String) {
        connectingTo = address
        state = State.CONNECTING
        listener.onStatus("Connecting...")
        listener.onLog("Step 1: asking Android to connect to $address (invite request)")
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, 45_000)
        @Suppress("DEPRECATION") val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = android.net.wifi.WpsInfo.PBC
            groupOwnerIntent = 0   // initiator prefers to be the client; avoids the "both want to own the group" clash
        }
        listener.onLog("Attempt $attempt of $maxAttempts: connect request, push-button setup, initiator prefers client role")
        try {
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    listener.onStatus("Connecting... (the other phone may need one system tap)")
                    listener.onLog("Step 2: Android accepted the request and sent the invite. Now waiting for the other phone (it may show a system popup, check its notification shade too)")
                }
                override fun onFailure(reason: Int) {
                    retryOrGiveUp(address, "Invite refused (code $reason, ${reasonText(reason)})")
                }
            })
        } catch (e: SecurityException) {
            state = State.DISCOVERING
            listener.onStatus("Missing Nearby devices permission")
        }
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) {
            try { manager?.cancelConnect(channel, null) } catch (_: Exception) { }
            connectingTo?.let { retryOrGiveUp(it, "TIMEOUT after 45 s: no Wi-Fi Direct group formed") }
        }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
        WifiP2pManager.BUSY -> "Android is busy, try again in a few seconds"
        WifiP2pManager.ERROR -> "internal error"
        else -> "unknown"
    }

    private fun tryDiscover() {
        val now = System.currentTimeMillis()
        if (now < discNextOk) {
            // Android said "busy" a moment ago: do not hammer it. One retry when the wait is over.
            if (!discRetryPending && state != State.OFF) {
                discRetryPending = true
                handler.postDelayed({ discRetryPending = false; if (state != State.OFF && state != State.CONNECTING) tryDiscover() }, discNextOk - now + 200)
            }
            return
        }
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discBusy = 0 }
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        discBusy++
                        val wait = LinkPolicy.busyBackoffMs(discBusy)
                        discNextOk = System.currentTimeMillis() + wait
                        listener.onLog("Discovery: Android is busy (code 2); waiting ${wait / 1000} s before trying again")
                        tryDiscover()   // schedules the single retry
                    } else {
                        listener.onLog("Discovery failed, code $reason (${reasonText(reason)}); is Location turned on?")
                        listener.onStatus("Discovery failed ($reason); is Location turned on?")
                    }
                }
            })
        } catch (e: SecurityException) {
            listener.onStatus("Missing Nearby devices permission")
        }
    }

    private fun refreshPeers() {
        try {
            manager?.requestPeers(channel) { list ->
                val out = list.deviceList.map {
                    PeerInfo(it.deviceAddress, it.deviceName ?: "?", it.status == WifiP2pDevice.AVAILABLE)
                }
                lastPeers = out
                listener.onPeers(out)
                listener.onLog("Nearby phones: " + out.joinToString { it.name + "=" + (if (it.available) "available" else "busy/invited") })
            }
        } catch (_: SecurityException) { }
    }

    private fun requestInfo() {
        try {
            manager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                listener.onLog("Step 4: group formed=${info.groupFormed}, this phone is group ${if (info.isGroupOwner) "OWNER (server)" else "CLIENT"}, owner address=${info.groupOwnerAddress?.hostAddress}")
                if (!info.groupFormed) return@requestConnectionInfo
                handler.removeCallbacks(connectTimeout)
                if (info.isGroupOwner) becomeServer() else becomeClient(info.groupOwnerAddress?.hostAddress)
            }
        } catch (_: SecurityException) { }
    }

    private fun becomeServer() {
        roleField = LinkRole.OWNER
        if (serverSocket != null) { listener.onLog("(already listening; ignoring a repeated group-formed event)"); return }
        state = State.CONNECTED
        listener.onStatus("Connected; waiting for the other phone")
        io.execute {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                listener.onLog("Step 5: listening on port $PORT for the other phone")
                handler.postDelayed({ if (synchronized(openSockets) { openSockets.isEmpty() } && state == State.CONNECTED) listener.onLog("TIMEOUT: group formed but the other phone has not connected to the socket after 30 s") }, 30_000)
                while (true) {
                    val s = ss.accept()
                    listener.onLog("Step 6: the other phone connected to the socket")
                    synchronized(openSockets) { openSockets.add(s) }
                    handler.post { listener.onSocketReady(s, true) }
                }
            } catch (_: Exception) { /* closed */ }
        }
    }

    private fun becomeClient(goAddress: String?) {
        if (goAddress == null) return
        roleField = LinkRole.CLIENT
        goAddr = goAddress
        if (synchronized(openSockets) { openSockets.any { !it.isClosed } }) { listener.onLog("(channel already open; ignoring a repeated group-formed event)"); return }
        if (!clientBusy.compareAndSet(false, true)) { listener.onLog("(already opening the channel; ignoring a repeated group-formed event)"); return }
        state = State.CONNECTED
        listener.onStatus("Connected; opening channel")
        io.execute {
            try {
                listener.onLog("Step 5: opening socket to $goAddress:$PORT")
                var s: Socket? = null
                var last: Exception? = null
                for (attempt in 1..5) {
                    try { val t = Socket(); t.connect(java.net.InetSocketAddress(goAddress, PORT), 5000); s = t; break }
                    catch (e: Exception) { last = e; listener.onLog("Socket attempt $attempt/5 failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); Thread.sleep(2000) }
                }
                if (s == null) throw last ?: IllegalStateException("no socket")
                listener.onLog("Step 6: socket connected")
                synchronized(openSockets) { openSockets.add(s) }
                clientBusy.set(false)
                handler.post { listener.onSocketReady(s, false) }
            } catch (e: Exception) {
                clientBusy.set(false)
                handler.post { listener.onLog("FAILED at step 5/6: could not open the socket to the group owner: ${e.javaClass.simpleName} ${e.message ?: ""}"); listener.onStatus("Channel failed; try again") }
            }
        }
    }

    private fun handleGroupGone() {
        if (state == State.CONNECTING) { listener.onLog("(still connecting; ignoring a not-connected broadcast)"); return }
        listener.onLog("Group gone / not connected")
        roleField = LinkRole.NONE; goAddr = null; clientBusy.set(false)
        closeSockets()
        if (state != State.OFF) {
            state = State.DISCOVERING
            listener.onLinkClosed()
            listener.onStatus("Link closed; looking again")
            // Restart discovery after the group is gone so this phone is visible again and sees the other one.
            handler.postDelayed({
                if (state == State.DISCOVERING) {
                    try { manager?.stopPeerDiscovery(channel, null) } catch (_: Exception) { }
                    handler.postDelayed({ if (state == State.DISCOVERING) tryDiscover() }, 800)
                }
            }, 1500)
        }
    }

    private fun closeSockets() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        synchronized(openSockets) {
            for (s in openSockets) try { s.close() } catch (_: Exception) { }
            openSockets.clear()
        }
    }

    companion object { const val PORT = 46331 }
}
