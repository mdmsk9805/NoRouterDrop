package dev.msk.norouterdrop

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TransferService.UiListener {

    private var service: TransferService? = null
    private var bound = false

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var trustedList: ListView
    private val ui = Handler(Looper.getMainLooper())

    private var discovered: List<PeerInfo> = emptyList()
    private var trusted: List<TrustedPeer> = emptyList()
    private var pendingSendPeerId: String? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TransferService.LocalBinder).service()
            service?.uiListener = this@MainActivity
            bound = true
            deviceText.text = "This phone: ${service?.myLabel()}"
            refreshTrusted()
            onStatus("${Brand.NAME} is on")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) startServiceNow()
            else statusText.text = "Grant the Nearby devices permission, or the radios stay off"
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val peerId = pendingSendPeerId
            pendingSendPeerId = null
            if (uri != null && peerId != null) onFilePicked(peerId, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceText = findViewById(R.id.deviceText)
        trustedList = findViewById(R.id.trustedList)

        findViewById<TextView>(R.id.menuButton).setOnClickListener { v -> showMenu(v) }
        val pairBtn = findViewById<Button>(R.id.pairButton)
        val waitBtn = findViewById<Button>(R.id.waitButton)
        pairBtn.setOnClickListener {
            if (!waitBtn.isEnabled) { toast("This phone is in WAIT mode. Tap Start pairing on the OTHER phone."); return@setOnClickListener }
            service?.noteTap("'1. Start pairing' tapped")
            showPairDialog()
        }
        waitBtn.setOnClickListener {
            service?.noteTap("'2. Wait for the other phone' tapped")
            service?.startWaiting()
            // Clear mode display: this phone only waits for 3 minutes; the other button is disabled meanwhile.
            waitBtn.isEnabled = false; waitBtn.text = "WAIT MODE ON (3 min). This phone only waits."
            pairBtn.isEnabled = false; pairBtn.text = "1. Start pairing (off while waiting)"
            ui.postDelayed({
                waitBtn.isEnabled = true; waitBtn.text = "2. Wait for the other phone (tap on the OTHER phone)"
                pairBtn.isEnabled = true; pairBtn.text = "1. Start pairing (tap on ONE phone only)"
            }, 180_000)
        }
        findViewById<Button>(R.id.sendButton).setOnClickListener { showSendDialog() }

        trustedList.setOnItemLongClickListener { _, _, position, _ ->
            val peer = trusted.getOrNull(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setTitle("Remove ${peer.name}?")
                .setMessage("It will need to pair again before it can send you files.")
                .setPositiveButton("Remove") { _, _ ->
                    service?.removePeer(peer.peerId)
                }
                .setNegativeButton("Keep", null)
                .show()
            true
        }

        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startServiceNow() else permLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        service?.uiListener = null
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    // ---------------------------------------------------------- start

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33)
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun startServiceNow() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            // Android's rule, not ours: peer discovery does nothing while Location Mode is off.
            AlertDialog.Builder(this)
                .setTitle("Turn Location on")
                .setMessage("Android only lets apps find nearby phones over Wi-Fi Direct while Location is on. ${Brand.NAME} never reads your location.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
        }
        val intent = Intent(this, TransferService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // ---------------------------------------------------------- dialogs

    private fun showPairDialog() {
        val s = service ?: return toast("${Brand.NAME} is still starting")
        val options = discovered.filter { !storeTrustedAddress(it.address) }   // includes busy/invited ones: the app clears old state, then invites
        if (options.isEmpty()) {
            toast("No new phone found yet. Open ${Brand.NAME} on the other phone and keep both screens on.")
            return
        }
        val names = options.map { "${it.name}  (${it.address})" + if (it.available) "" else "  [busy/old invite: will clear and try]" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pair with which phone?")
            .setItems(names) { _, which -> s.startPairing(options[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSendDialog() {
        if (service == null) return toast("${Brand.NAME} is still starting")
        if (trusted.isEmpty()) return toast("Pair a phone first")
        val names = trusted.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Send to which phone?")
            .setItems(names) { _, which ->
                pendingSendPeerId = trusted[which].peerId
                pickFile.launch("*/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onFilePicked(peerId: String, uri: Uri) {
        Thread {
            val name = queryName(uri) ?: "file"
            val cached = File(cacheDir, "send_${System.currentTimeMillis()}_$name")
            val ok = try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    cached.outputStream().use { ins.copyTo(it) }
                } != null
            } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) service?.sendToPeer(peerId, cached, name)
                else toast("Could not read that file")
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    private fun storeTrustedAddress(address: String): Boolean =
        trusted.any { it.address.equals(address, ignoreCase = true) }

    // ---------------------------------------------------------- TransferService.UiListener

    override fun onStatus(text: String) {
        statusText.text = text
    }

    override fun onLog(text: String) { /* kept in AppLog; shown only in the menu's log screen */ }

    // ---- three-dot menu: log, contact ----
    private fun showMenu(anchor: android.view.View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Log (copy for support)")
        pm.menu.add(0, 3, 1, "Check for updates")
        pm.menu.add(0, 2, 2, "Contact / Support")
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> showLog(); 2 -> showContact(); 3 -> checkUpdates() }
            true
        }
        pm.show()
    }

    private fun showLog() {
        val tv = TextView(this).apply { textSize = 11f; setPadding(32, 16, 32, 0); setTextIsSelectable(true); text = AppLog.all().takeLast(60).joinToString("\n").ifEmpty { "(empty)" } }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle("Log").setView(sv)
            .setPositiveButton("Copy all") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("${Brand.NAME} log", AppLog.text()))
                toast("Log copied (${AppLog.all().size} lines)")
            }.setNegativeButton("Close", null).show()
    }

    private fun checkUpdates() {
        val current = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
        toast("Checking...")
        Thread {
            val r = UpdateCheck.check(getString(R.string.update_repo), current)
            runOnUiThread { if (!isFinishing) showUpdateResult(r) }
        }.start()
    }

    private fun showUpdateResult(r: UpdateResult) {
        val b = AlertDialog.Builder(this).setTitle("Check for updates").setNegativeButton("Close", null)
        when (r) {
            is UpdateResult.NotConfigured -> b.setMessage("Update checking is not set up in this build yet.")
            is UpdateResult.NoRelease -> b.setMessage("No release has been published yet.")
            is UpdateResult.UpToDate -> b.setMessage("You have the newest version (${r.current}).")
            is UpdateResult.Failed -> b.setMessage("Could not check: ${r.why}.")
            is UpdateResult.Newer -> {
                val url = r.apkUrl ?: r.pageUrl
                b.setMessage("New version ${r.latest} is available (you have ${r.current}).\n\nTap Download to open it in your browser. Nothing is installed automatically.")
                    .setPositiveButton("Download") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
        b.show()
    }

    private fun showContact() {
        val email = getString(R.string.contact_email).trim()
        val repo = getString(R.string.update_repo).trim()
        val issues = if (Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) "https://github.com/$repo/issues" else null
        val sb = StringBuilder("Problem or question?\n")
        if (email.contains("@") && !email.contains("<")) sb.append("Write to: ").append(email).append("\n")
        if (issues != null) sb.append("Or open an issue: ").append(issues).append("\n")
        sb.append("\nPlease include:\n- the name and model number of both phones (Settings > About phone)\n- the log (menu > Log > Copy all)\n- what you did and what happened")
        val b = AlertDialog.Builder(this).setTitle("Contact / Support").setMessage(sb.toString()).setPositiveButton("OK", null)
        if (issues != null) b.setNeutralButton("Open issues page") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(issues))) } catch (_: Exception) { }
        }
        b.show()
    }

    // ---- file progress ring ----
    private var progressDialog: AlertDialog? = null
    private var ring: RingProgressView? = null
    private var ringTitle: TextView? = null
    private var ringName = ""

    override fun onProgress(fileName: String, percent: Int, incoming: Boolean, finished: Boolean) {
        if (isFinishing) return
        if (progressDialog == null || ringName != fileName) {
            progressDialog?.dismiss()
            val lay = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(48, 32, 48, 16)
            }
            val r = RingProgressView(this)
            val t = TextView(this).apply { gravity = android.view.Gravity.CENTER; textSize = 14f; setPadding(0, 24, 0, 0) }
            lay.addView(r); lay.addView(t)
            ring = r; ringTitle = t; ringName = fileName
            progressDialog = AlertDialog.Builder(this).setTitle(if (incoming) "Receiving" else "Sending").setView(lay).setCancelable(true).create()
            progressDialog?.show()
        }
        ring?.percent = percent
        ringTitle?.text = fileName
        if (finished) {
            ui.postDelayed({ progressDialog?.dismiss(); progressDialog = null; ringName = "" }, if (percent >= 100) 900 else 0)
        }
    }

    override fun onTrustedChanged() = refreshTrusted()

    override fun onDiscovered(peers: List<PeerInfo>) {
        discovered = peers
    }

    override fun onPairCode(code: String, peerName: String) {
        AlertDialog.Builder(this)
            .setTitle("Pairing with $peerName")
            .setMessage("Both phones should show this code:\n\n$code\n\nTap Match on BOTH phones only if the code is the same. If it differs, someone else is in the middle; tap Don't match.")
            .setPositiveButton("Codes match") { _, _ -> service?.confirmPairing(true) }
            .setNegativeButton("Don't match") { _, _ -> service?.confirmPairing(false) }
            .setCancelable(false)
            .show()
    }

    private fun refreshTrusted() {
        trusted = service?.trustedPeers() ?: emptyList()
        trustedList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            trusted.map { "${it.name}  (${shortId(it.peerId)})" }
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
