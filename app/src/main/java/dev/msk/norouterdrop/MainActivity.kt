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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/** One thing the user picked to send: a file or media item (uri) or a piece of text. */
data class Selected(val uri: Uri?, val text: String?, val name: String)

class MainActivity : AppCompatActivity(), TransferService.UiListener {

    private var service: TransferService? = null
    private var bound = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var paneReceive: View
    private lateinit var paneSend: View
    private lateinit var paneSettings: View
    private lateinit var statusText: TextView
    private lateinit var deviceName: TextView
    private lateinit var deviceNumber: TextView
    private lateinit var badge: DeviceBadgeView
    private lateinit var pairHint: TextView
    private lateinit var waitButton: MaterialButton
    private lateinit var deviceList: LinearLayout
    private lateinit var deviceEmpty: TextView
    private lateinit var deviceListHint: TextView
    private lateinit var selectionBar: View
    private lateinit var selectionText: TextView
    private lateinit var settingsList: LinearLayout

    private var discovered: List<PeerInfo> = emptyList()
    private var trusted: List<TrustedPeer> = emptyList()
    private val selection = ArrayList<Selected>()
    private val prefs by lazy { getSharedPreferences("norouterdrop_ui", Context.MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TransferService.LocalBinder).service()
            service?.uiListener = this@MainActivity
            service?.appVisible = true
            bound = true
            showIdentity()
            refreshTrusted()
            onStatus("${Brand.NAME} is on")
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false; service = null }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) startServiceNow()
            else statusText.text = "Grant the Nearby devices permission, or the radios stay off"
        }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addUris(uris) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addUris(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(when (prefs.getString("theme", "system")) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "dark" -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        paneReceive = findViewById(R.id.paneReceive); paneSend = findViewById(R.id.paneSend); paneSettings = findViewById(R.id.paneSettings)
        statusText = findViewById(R.id.statusText); deviceName = findViewById(R.id.deviceName); deviceNumber = findViewById(R.id.deviceNumber)
        badge = findViewById(R.id.badge); pairHint = findViewById(R.id.pairHint); waitButton = findViewById(R.id.waitButton)
        deviceList = findViewById(R.id.deviceList); deviceEmpty = findViewById(R.id.deviceEmpty); deviceListHint = findViewById(R.id.deviceListHint)
        selectionBar = findViewById(R.id.selectionBar); selectionText = findViewById(R.id.selectionText); settingsList = findViewById(R.id.settingsList)

        badge.animationsOn = prefs.getBoolean("animations", true)
        showIdentity()
        findViewById<View>(R.id.historyButton).setOnClickListener { showHistory() }
        findViewById<View>(R.id.infoButton).setOnClickListener { showAbout() }
        waitButton.setOnClickListener { startWaitMode() }

        findViewById<View>(R.id.selFile).setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.selMedia).setOnClickListener { pickMedia.launch(arrayOf("image/*", "video/*")) }
        findViewById<View>(R.id.selText).setOnClickListener { askText() }
        findViewById<View>(R.id.selPaste).setOnClickListener { pasteText() }
        findViewById<View>(R.id.selectionClear).setOnClickListener { selection.clear(); updateSelection() }
        findViewById<View>(R.id.refreshButton).setOnClickListener { service?.refreshDiscovery(); toast("Searching again..."); }

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener {
            show(when (it.itemId) { R.id.nav_send -> 1; R.id.nav_settings -> 2; else -> 0 }); true
        }
        buildSettings()
        updateSelection()
        renderDevices()

        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startServiceNow() else permLauncher.launch(missing.toTypedArray())
    }

    override fun onStart() { super.onStart(); service?.appVisible = true }
    override fun onStop() { service?.appVisible = false; super.onStop() }

    override fun onDestroy() {
        service?.uiListener = null
        service?.appVisible = false
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    private fun show(tab: Int) {
        paneReceive.visibility = if (tab == 0) View.VISIBLE else View.GONE
        paneSend.visibility = if (tab == 1) View.VISIBLE else View.GONE
        paneSettings.visibility = if (tab == 2) View.VISIBLE else View.GONE
        if (tab == 1) { service?.refreshDiscovery() }
    }

    private fun showIdentity() {
        deviceName.text = service?.deviceName() ?: TrustedStore.localName(this)
        deviceNumber.text = "#" + (service?.deviceNumber() ?: shortId(TrustedStore.localId(this)).take(4))
    }

    // ---------------------------------------------------------- start

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES) else list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun startServiceNow() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            // Android's rule, not ours: peer discovery does nothing while Location Mode is off.
            AlertDialog.Builder(this)
                .setTitle("Turn Location on")
                .setMessage("Android only lets apps find nearby phones over Wi-Fi Direct while Location is on. ${Brand.NAME} never reads your location.")
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Later", null)
                .show()
        }
        val intent = Intent(this, TransferService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // ---------------------------------------------------------- pairing (wait side)

    private var waitingUntil = 0L
    private fun startWaitMode() {
        val s = service ?: return toast("${Brand.NAME} is still starting")
        s.noteTap("'Pair a new phone' (wait mode)")
        s.startWaiting()
        waitingUntil = System.currentTimeMillis() + 180_000
        waitButton.isEnabled = false
        waitButton.text = "Waiting for the other phone..."
        pairHint.text = "On the other phone: Send tab, tap this phone's name, then confirm the 6-digit code on both. (3 minutes)"
        ui.postDelayed({
            if (System.currentTimeMillis() >= waitingUntil) { waitButton.isEnabled = true; waitButton.text = "Pair a new phone"; pairHint.text = "" }
        }, 181_000)
    }

    // ---------------------------------------------------------- selection

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    private fun addUris(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) return
        for (u in uris) selection.add(Selected(u, null, queryName(u) ?: "file"))
        updateSelection()
    }

    private fun askText() {
        val et = EditText(this).apply { hint = "Type or paste text"; minLines = 3; gravity = Gravity.TOP or Gravity.START; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this).setTitle("Text to send").setView(et)
            .setPositiveButton("Add") { _, _ -> addText(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pasteText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) toast("Clipboard has no text") else addText(t)
    }

    private fun addText(t: String) {
        if (t.isBlank()) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        selection.add(Selected(null, t, "Text-$stamp-${selection.size + 1}.txt"))
        updateSelection()
    }

    private fun updateSelection() {
        selectionBar.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
        selectionText.text = when (selection.size) { 0 -> ""; 1 -> selection[0].name; else -> "${selection.size} items selected" }
        deviceListHint.text = if (selection.isEmpty()) "Choose what to send, then tap a paired phone." else "Tap a paired phone to send."
    }

    // ---------------------------------------------------------- devices list

    private fun isTrustedAddress(address: String) = trusted.any { it.address.equals(address, ignoreCase = true) }

    private fun deviceRow(title: String, sub: String, action: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 28, 32, 28); background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            isClickable = true; isFocusable = true; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 18 }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_phone); imageTintList = android.content.res.ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(84, 84).apply { marginEnd = 32 }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface)) })
        col.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)) })
        row.addView(col)
        row.addView(TextView(this).apply { text = action; textSize = 14f; setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary)) })
        return row
    }

    private fun themeColor(attr: Int): Int { val tv = android.util.TypedValue(); theme.resolveAttribute(attr, tv, true); return tv.data }

    private fun renderDevices() {
        deviceList.removeAllViews()
        var n = 0
        for (t in trusted) {
            deviceList.addView(deviceRow(t.name, "Paired", "Send") { sendTo(t) }); n++
        }
        for (p in discovered.filter { !isTrustedAddress(it.address) }) {
            deviceList.addView(deviceRow(p.name, if (p.available) "Nearby, not paired" else if (p.connected) "Connected by Wi-Fi Direct, not paired" else "Nearby, busy", "Pair") { askPair(p) }); n++
        }
        deviceEmpty.visibility = if (n == 0) View.VISIBLE else View.GONE
    }

    private fun askPair(p: PeerInfo) {
        val s = service ?: return toast("${Brand.NAME} is still starting")
        AlertDialog.Builder(this).setTitle("Pair with ${p.name}?")
            .setMessage("On ${p.name}, open ${Brand.NAME}, go to the Receive tab and tap \"Pair a new phone\" first. Then tap Pair here. Both phones will show a 6-digit code; confirm only if they match.")
            .setPositiveButton("Pair") { _, _ -> s.startPairing(p) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sendTo(peer: TrustedPeer) {
        val s = service ?: return toast("${Brand.NAME} is still starting")
        if (selection.isEmpty()) return toast("Choose what to send first (File, Media, Text or Paste)")
        val items = selection.toList()
        selection.clear(); updateSelection()
        toast("Sending ${items.size} item(s) to ${peer.name}")
        Thread {
            for (it in items) {
                val cached = File(cacheDir, "send_${System.currentTimeMillis()}_${it.name}")
                val ok = try {
                    if (it.text != null) { cached.writeText(it.text); true }
                    else contentResolver.openInputStream(it.uri!!)?.use { ins -> cached.outputStream().use { o -> ins.copyTo(o) } } != null
                } catch (e: Exception) { false }
                runOnUiThread { if (ok) s.sendToPeer(peer.peerId, cached, it.name) else toast("Could not read ${it.name}") }
            }
        }.start()
    }

    // ---------------------------------------------------------- settings screen

    private fun section(title: String) {
        settingsList.addView(TextView(this).apply {
            text = title; textSize = 14f; setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
            setPadding(8, 36, 8, 12)
        })
    }

    private fun settingRow(label: String, control: View, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 8, 10)
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        row.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(control)
        settingsList.addView(row)
    }

    private fun valueButton(text: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        this.text = text; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun toggle(key: String, def: Boolean, onChange: (Boolean) -> Unit = {}) = MaterialSwitch(this).apply {
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); onChange(on) }
    }

    private fun buildSettings() {
        settingsList.removeAllViews()
        section("General")
        val themeBtn = valueButton(themeLabel()) {}
        themeBtn.setOnClickListener {
            val opts = arrayOf("System", "Light", "Dark")
            AlertDialog.Builder(this).setTitle("Theme").setItems(opts) { _, w ->
                prefs.edit().putString("theme", arrayOf("system", "light", "dark")[w]).apply()
                AppCompatDelegate.setDefaultNightMode(when (w) { 1 -> AppCompatDelegate.MODE_NIGHT_NO; 2 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
            }.show()
        }
        settingRow("Theme", themeBtn)
        settingRow("Animations", toggle("animations", true) { badge.animationsOn = it })

        section("Receive")
        settingRow("Save to folder", valueButton("Downloads/${Brand.NAME}") { toast("Files are saved to Downloads/${Brand.NAME}. Changing the folder is not supported yet.") })
        settingRow("Accept from paired phones", valueButton("Always") { toast("Paired phones are accepted automatically. Phones that are not paired are always declined. This cannot be turned off.") })
        settingRow("Save to history", toggle("history_on", true))
        settingRow("History", valueButton("Open") { showHistory() })

        section("Network")
        settingRow("Connection", valueButton("Wi-Fi Direct") { toast("${Brand.NAME} uses Wi-Fi Direct only. No router or internet is needed.") })
        settingRow("Discovery", valueButton("Restart") { service?.refreshDiscovery(); toast("Searching again...") })
        settingRow("Device name", valueButton(service?.deviceName() ?: TrustedStore.localName(this)) { askDeviceName() })

        section("Other")
        settingRow("Log (copy for support)", valueButton("Open") { showLog() })
        settingRow("Check for updates", valueButton("Check") { checkUpdates() })
        settingRow("Contact / Support", valueButton("Open") { showContact() })
        settingRow("About ${Brand.NAME}", valueButton("Open") { showAbout() })
    }

    private fun themeLabel() = when (prefs.getString("theme", "system")) { "light" -> "Light"; "dark" -> "Dark"; else -> "System" }

    private fun askDeviceName() {
        val et = EditText(this).apply { setText(service?.deviceName() ?: TrustedStore.localName(this@MainActivity)); setSelectAllOnFocus(true); setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this).setTitle("Device name").setMessage("Shown to paired phones.").setView(et)
            .setPositiveButton("Save") { _, _ -> service?.setDeviceName(et.text.toString()); showIdentity(); buildSettings() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showHistory() {
        val items = HistoryStore.all(this)
        val b = AlertDialog.Builder(this).setTitle("History").setNegativeButton("Close", null)
        if (items.isEmpty()) b.setMessage("Nothing yet.") else {
            b.setItems(items.map { it.line() }.toTypedArray(), null)
            b.setNeutralButton("Clear") { _, _ -> HistoryStore.clear(this) }
        }
        b.show()
    }

    private fun showAbout() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        AlertDialog.Builder(this).setTitle("${Brand.NAME} $v")
            .setMessage("Sends files between your own Android phones over Wi-Fi Direct. No router, no internet, no account.\n\nEarly version, not independently audited. Do not rely on it where a failure would be serious.\n\nMIT licence, copyright MSK. The screen layout is inspired by LocalSend (localsend.org, Apache-2.0). No LocalSend code is included.")
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------- TransferService.UiListener

    override fun onStatus(text: String) { statusText.text = text }
    override fun onLog(text: String) { /* kept in AppLog; shown only in Settings > Log */ }

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
                b.setMessage("New version ${r.latest} is available (you have ${r.current}).\n\n" + (if (r.apkUrl != null) "Download and install fetches the file from GitHub, checks it is signed with the same key, then Android asks you to confirm the install. Your pairing is kept. Nothing happens without your taps." else "This release has no APK file attached. Use Open page."))
                if (r.apkUrl != null) b.setPositiveButton("Download and install") { _, _ -> downloadUpdate(r) }
                b.setNeutralButton("Open page") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) }
            }
        }
        b.show()
    }

    private fun downloadUpdate(r: UpdateResult.Newer) {
        val dlg = AlertDialog.Builder(this).setTitle("Downloading ${r.latest}").setMessage("0%").setCancelable(false).create()
        dlg.show()
        Thread {
            val res = Updater.download(this, r.apkUrl!!, r.sha256) { p -> runOnUiThread { dlg.setMessage("$p%") } }
            runOnUiThread {
                dlg.dismiss()
                if (isFinishing) return@runOnUiThread
                when (res) {
                    is Updater.Outcome.Failed -> AlertDialog.Builder(this).setTitle("Update not installed").setMessage("Could not update: ${res.why}.").setPositiveButton("OK", null).show()
                    is Updater.Outcome.Ready -> {
                        if (!Updater.install(this, res.file)) AlertDialog.Builder(this).setTitle("One setting needed").setMessage("Allow ${Brand.NAME} to install apps on the screen that just opened, press Back, then tap Check for updates again. The file is downloaded already.").setPositiveButton("OK", null).show()
                    }
                }
            }
        }.start()
    }

    private fun showContact() {
        val email = getString(R.string.contact_email).trim()
        val repo = getString(R.string.update_repo).trim()
        val issues = if (Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) "https://github.com/$repo/issues" else null
        val sb = StringBuilder("Problem or question?\n")
        if (email.contains("@") && !email.contains("<")) sb.append("Write to: ").append(email).append("\n")
        if (issues != null) sb.append("Or open an issue: ").append(issues).append("\n")
        sb.append("\nPlease include:\n- the name and model number of both phones (Settings > About phone)\n- the log (Settings > Log > Copy all)\n- what you did and what happened")
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

    override fun onDiscovered(peers: List<PeerInfo>) { discovered = peers; renderDevices() }

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
        renderDevices()
    }

    /** For the screenshot test only: shows the given state without a running service. */
    internal fun previewState(t: List<TrustedPeer>, peers: List<PeerInfo>, sel: List<Selected>, status: String, tab: Int) {
        trusted = t; discovered = peers; selection.clear(); selection.addAll(sel)
        statusText.text = status; renderDevices(); updateSelection()
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = when (tab) { 1 -> R.id.nav_send; 2 -> R.id.nav_settings; else -> R.id.nav_receive }
        show(tab)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
