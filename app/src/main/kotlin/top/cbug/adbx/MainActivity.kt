package top.cbug.adbx

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.provider.Settings
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.ui.NetworkFragment
import top.cbug.adbx.ui.SettingsFragment
import top.cbug.adbx.ui.StatusFragment
import top.cbug.adbx.ui.WifiAdapter
import top.cbug.adbx.ui.WifiItem
import top.cbug.adbx.ui.WifiSettingsActivity
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.LocaleHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.util.XposedStatus

/**
 * Single Activity host for the 4-tab bottom-nav UI. Activity is the
 * shared "controller" — fragments call back into it for things that
 * span tabs (status refresh, wifi scan, pairing dialog, etc.).
 *
 * Status indicators and other rendered widgets live on the active
 * Fragment's view tree, not on the Activity — but their backing data
 * (cached IP, port, latest status snapshot) lives here.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ADB_X_Main"
        private const val REQUEST_LOCATION = 1001
        private const val STATE_TAB = "selected_tab"
        // Polling interval for the backup watcher. The ContentObserver is
        // the primary path; this catches anything the observer misses on
        // certain OEM ROMs (e.g. OnePlus doesn't always notify observers
        // when the SystemUI adb-pairing dialog spawns a new transient port).
        private const val PAIRING_POLL_INTERVAL_MS = 3000L
    }

    val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val mainHandler = Handler(Looper.getMainLooper())
    private var refreshInProgress = false
    private var pairingPollJob: Job? = null
    private var wifiObserver: android.database.ContentObserver? = null
    private var adbObserver: android.database.ContentObserver? = null
    // Cached values for click-to-copy + cross-fragment reads.
    private var cachedLocalIp: String = ""
    private var cachedPort: String = ""

    // Latest status snapshot, used to re-render when the user switches tabs.
    data class StatusSnapshot(
        var adbEnabled: Boolean = false,
        var error: String? = null,
        var port: String = "",
        var pairingPort: String = "",
        var pairingCode: String = "",
        var ssid: String = "",
        var localIp: String = "",
        var externalIp: String = "",
        var hasRoot: Boolean = false,
        var xposed: XposedStatus.Info = XposedStatus.Info(
            state = XposedStatus.State.UNKNOWN, emptyList(), ""
        )
    )
    private var status = StatusSnapshot()

    private lateinit var bottomNav: BottomNavigationView

    // ---------------- Lifecycle ----------------

    override fun attachBaseContext(newBase: Context) {
        // Settings was loaded by App.attachBaseContext before us; apply
        // user-selected locale on top of system default.
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fire the Wi-Fi evaluate receiver once — covers the cold-start
        // case where the app launches on a trusted SSID before any
        // NETWORK_STATE_CHANGED has fired. The receiver reads the
        // current SSID, looks up Settings.trustedSsids, and toggles
        // wireless ADB if appropriate. Subsequent SSID changes are
        // delivered by the system automatically.
        top.cbug.adbx.WifiStateReceiver.fireOnce(this)
        try {
            setContentView(R.layout.activity_main)
            AppSettings.load(this)
            val navHost = findViewById<View>(R.id.nav_host)
            bottomNav = findViewById(R.id.bottom_nav)

            // Push only the status-bar inset into the fragment container so
            // the top of the content clears the status bar. The
            // BottomNavigationView applies its own bottom inset for the
            // navigation bar, so we don't add bottom padding here.
            ViewCompat.setOnApplyWindowInsetsListener(navHost) { v, insets ->
                val sysBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(top = sysBars.top)
                insets
            }
            bottomNav.setOnItemSelectedListener { item ->
                switchTo(when (item.itemId) {
                    R.id.tab_status   -> StatusFragment()
                    R.id.tab_networks -> NetworkFragment()
                    R.id.tab_networks -> NetworkFragment()
                    R.id.tab_settings -> SettingsFragment()
                    else               -> StatusFragment()
                })
                true
            }
            // Restore selected tab across config changes (e.g. language toggle).
            if (savedInstanceState == null) {
                bottomNav.selectedItemId = R.id.tab_status
            } else {
                // Re-select by reading saved state — fragment manager handles restore.
                bottomNav.selectedItemId = savedInstanceState.getInt(STATE_TAB, R.id.tab_status)
            }

            mainHandler.post {
                requestNeededPermissions()
                doMinimalRefresh()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate crashed", t)
            try {
                val tv = TextView(this)
                tv.text = getString(R.string.msg_init_failed, t.message ?: "")
                tv.setPadding(32, 32, 32, 32)
                setContentView(tv)
            } catch (_: Throwable) { }
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, bottomNav.selectedItemId)
    }

    override fun onResume() {
        super.onResume()
        // 1. Watch `adb_wifi_enabled` so the Status tab updates the moment
        //    the user toggles wireless ADB in Developer options. ContentObservers
        //    run on the main thread, so they're safe to immediately trigger a
        //    refresh without an extra hop. Use the literal constant — the
        //    Settings.Global.ADB_WIFI_ENABLED constant landed in API 33 only.
        val ADB_WIFI_ENABLED_URI = android.provider.Settings.Global.getUriFor(
            "adb_wifi_enabled"
        )
        wifiObserver = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                Log.d(TAG, "settings: adb_wifi_enabled changed, refreshing…")
                doMinimalRefresh()
            }
        }.also { observer ->
            contentResolver.registerContentObserver(ADB_WIFI_ENABLED_URI, false, observer)
        }

        // 2. Watch parcel-related transient props. `service.adb.tcp.port`
        //    and `service.adb.tls.port` are written by adbd on port-allocation
        //    events, but they go through system properties — not Settings.Global.
        //    ContentObserver can't see them. We do a short periodic poll from a
        //    background coroutine: cheap (su getprop returns in <50 ms), and
        //    keeps the pairing-port card live without requiring the hook.
        pairingPollJob?.cancel()
        pairingPollJob = bgScope.launch {
            var lastPort = ""
            var lastEnabled: Boolean? = null
            while (isActive) {
                try {
                    val cur = AdbHelper.getPairingPort()
                    val enabled = Settings.Global.getInt(
                        contentResolver, "adb_wifi_enabled", 0
                    ) == 1
                    if (cur != lastPort || enabled != lastEnabled) {
                        lastPort = cur
                        lastEnabled = enabled
                        Log.d(TAG, "polling: pairingPort='$cur' enabled=$enabled → doMinimalRefresh")
                        // Side-effect: once the polling loop has resolved an
                        // ephemeral pairing port, persist it into
                        // /data/local/tmp/adb_x_pairing_port so any future
                        // hook path can pick the same value up. Use su
                        // because the app uid cannot write
                        // /data/local/tmp/ directly.
                        if (cur.isNotEmpty() && (cur.toIntOrNull() ?: 0) in 1024..65535) {
                            runCatching {
                                top.cbug.adbx.util.ShellUtils.executeSu(
                                    "sh -c 'echo $cur > /data/local/tmp/adb_x_pairing_port && chmod 666 /data/local/tmp/adb_x_pairing_port'",
                                    1000
                                )
                            }
                        }
                        mainHandler.post { doMinimalRefresh() }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "polling tick failed: ${t.message}")
                }
                kotlinx.coroutines.delay(PAIRING_POLL_INTERVAL_MS)
            }
        }
        // (WifiStateReceiver is already fired in onCreate so the
        // cold-start case is covered; nothing extra needed on resume.)
    }

    override fun onPause() {
        super.onPause()
        wifiObserver?.let { contentResolver.unregisterContentObserver(it) }
        wifiObserver = null
        pairingPollJob?.cancel()
        pairingPollJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bgScope.cancel()
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.nav_host, fragment)
        }
        // The fragment being shown may want the latest status snapshot as
        // soon as it's attached; the FragmentTransaction's commit() is
        // synchronous enough that we can push here without an extra hop.
        mainHandler.post { pushStatusToActiveFragment() }
    }

    // ---------------- Status refresh (used by fragments) ----------------

    /**
     * TODO: document refreshStatusAndPairing
     */
    fun refreshStatusAndPairing() {
        renderXposedStatus()
    }

    /**
     * TODO: document doFullRefresh
     */
    fun doFullRefresh() {
        if (refreshInProgress) return
        refreshInProgress = true
        bgScope.launch {
            try {
                val st = AdbHelper.getFullStatus(this@MainActivity)
                val ssid = try { WifiHelper.getCurrentSsid(this@MainActivity) } catch (_: Exception) { "" }
                val ip = try { WifiHelper.getLocalIpAddress(this@MainActivity) } catch (_: Exception) { "" }
                val extIp = try { WifiHelper.getExternalIpAddress() } catch (_: Exception) { "" }
                val xposed = XposedStatus.probe(this@MainActivity)

                status = StatusSnapshot(
                    adbEnabled = st.enabled,
                    port = st.port,
                    pairingPort = st.pairingPort,
                    pairingCode = st.pairingCode,
                    ssid = ssid,
                    localIp = ip,
                    externalIp = extIp,
                    hasRoot = st.hasRoot,
                    xposed = xposed,
                )
                cachedLocalIp = ip
                cachedPort = st.port

                withContext(Dispatchers.Main) {
                    pushStatusToActiveFragment()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "doFullRefresh failed", t)
                withContext(Dispatchers.Main) {
                    status.error = t.message ?: "error"
                    pushStatusToActiveFragment()
                }
            } finally {
                refreshInProgress = false
            }
        }
    }

    private fun pushStatusToActiveFragment() {
        val frag = supportFragmentManager.findFragmentById(R.id.nav_host) ?: return
        when (frag) {
            is StatusFragment -> frag.renderStatus(buildStatusUiModel())
        }
    }

    private fun buildStatusUiModel(): StatusFragment.UiModel {
        return StatusFragment.UiModel(
            xposedTitle = getString(when (status.xposed.state) {
                XposedStatus.State.ACTIVE   -> R.string.xposed_active_title
                XposedStatus.State.INACTIVE -> R.string.xposed_inactive_title
                XposedStatus.State.UNKNOWN  -> R.string.xposed_inactive_title
            }),
            xposedSubtitle = getString(when (status.xposed.state) {
                XposedStatus.State.ACTIVE   -> R.string.xposed_active_subtitle
                XposedStatus.State.INACTIVE ->
                    if (status.xposed.frameworkPackages.isEmpty())
                        R.string.xposed_inactive_subtitle_no_frame
                    else
                        R.string.xposed_inactive_subtitle_with_frame
                XposedStatus.State.UNKNOWN  -> R.string.xposed_inactive_subtitle_no_frame
            }).let {
                // INACTIVE subtitle uses %1$s for the framework-package list —
                // inject the actual list. ACTIVE subtitle has no placeholders
                // so we pass it through unchanged.
                val args = status.xposed.frameworkPackages.joinToString(", ")
                if (status.xposed.state == XposedStatus.State.ACTIVE) it
                else getString(R.string.xposed_inactive_subtitle_with_frame, args)
            },
            xposedChipText = getString(when (status.xposed.state) {
                XposedStatus.State.ACTIVE   -> R.string.xposed_state_active
                XposedStatus.State.INACTIVE -> R.string.xposed_state_inactive
                XposedStatus.State.UNKNOWN  -> R.string.xposed_state_unknown
            }),
            xposedIsActive = status.xposed.state == XposedStatus.State.ACTIVE,
            adbState = status.adbEnabled,
            error = status.error,
            port = status.port,
            pairingPort = status.pairingPort,
            pairingCode = status.pairingCode,
            ssid = status.ssid,
            localIp = status.localIp,
            externalIp = status.externalIp,
            hasRoot = status.hasRoot,
        )
    }

    private fun renderXposedStatus() {
        // Probe in background; let StatusFragment pick it up via pushStatusToActiveFragment.
        bgScope.launch {
            val info = XposedStatus.probe(this@MainActivity)
            status = status.copy(xposed = info)
            withContext(Dispatchers.Main) { pushStatusToActiveFragment() }
        }
    }

    // ---------------- WiFi refresh ----------------

    /**
     * App-side poll of dumpsys as a backup path for the LSPosed hook.
     * When the candidate classes don't match this ROM, this is what
     * surfaces the (transient) pairing port to AdbHelper.
     */
    fun pollPairingPort() {
        bgScope.launch {
            try {
                val r = ShellUtils.executeSu(
                    "dumpsys activity provider com.android.adb 2>&1 | head -200",
                    2000
                )
                if (r.isSuccess() && r.output.isNotBlank()) {
                    android.util.Log.d("ADB_X_Main", "adb provider dump\n" + r.output.take(400))
                }
            } catch (_: Throwable) { }
        }
    }

    /**
     * TODO: document refreshWifiList
     */
    fun refreshWifiList() {
        if (refreshInProgress) {
            toast(getString(R.string.msg_still_loading))
            return
        }
        refreshInProgress = true
        bgScope.launch {
            try {
                ShellUtils.probeRootFast()
                val networks = WifiHelper.getSavedNetworks(this@MainActivity)
                val items = networks.map { WifiItem(it.ssid, it.bssid, it.security) }
                // List now lives in WifiSettingsActivity; nothing to
                // push back to NetworkFragment on the tab itself.
                withContext(Dispatchers.Main) { }
            } catch (t: Throwable) {
                Log.w(TAG, "refreshWifiList failed", t)
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.msg_wifi_refresh_fail, t.message ?: ""))
                }
            } finally {
                refreshInProgress = false
            }
        }
    }

    // ---------------- Port apply ----------------

    /**
     * TODO: document applyFixedPort
     * @param Int
     */
    fun applyFixedPort(port: Int) {
        AppSettings.fixedPort = port
        AppSettings.save(this)
        toast(getString(R.string.msg_setting_port, port))
        bgScope.launch {
            val ok = AdbHelper.setFixedPort(port)
            withContext(Dispatchers.Main) {
                toast(getString(if (ok) R.string.msg_fixed_port_ok else R.string.msg_fixed_port_fail, port))
            }
            doFullRefresh()
        }
    }

    // ---------------- Pairing dialog ----------------

    /**
     * TODO: document showSetPairingDialog
     */
    fun showSetPairingDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_pairing_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getString(R.string.dialog_pairing_default))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_pairing_title))
            .setMessage(getString(R.string.dialog_pairing_msg))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int ->
                val code = input.text.toString().trim()
                if (code.length !in 6..8 || !code.all { it.isDigit() }) {
                    toast(getString(R.string.err_pairing_length))
                    return@setPositiveButton
                }
                AdbHelper.setPairingCode(code)
                toast(getString(R.string.msg_pairing_saved, code))
                doFullRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * TODO: document openWifiSettingsActivity
     */
    fun openWifiSettingsActivity() {
        startActivity(Intent(this, WifiSettingsActivity::class.java))
    }

    /**
     * TODO: document openPairingActivity
     */
    fun openPairingActivity() {
        startActivity(Intent(this, PairingActivity::class.java))
    }

    /**
     * TODO: document showXposedHelpDialog
     */
    fun showXposedHelpDialog() {
        val info = XposedStatus.probe(this)
        val detected = if (info.frameworkPackages.isEmpty()) "  (none)"
            else info.frameworkPackages.joinToString("\n") { "  • $it" }
        val msg = buildString {
            appendLine("Hint: ${info.frameworkHint}")
            appendLine()
            appendLine("Framework packages detected:")
            appendLine(detected)
            appendLine()
            appendLine("Module state: ${info.state}")
            appendLine()
            appendLine(getString(R.string.about_source_code) + ": https://github.com/blockman3063/ADB_X")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.xposed_title))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(getString(R.string.dialog_xposed_refresh)) { _, _ ->
                renderXposedStatus()
            }
            .show()
    }

    // ---------------- Permissions ----------------

    /**
     * TODO: document requestNeededPermissions
     */
    fun requestNeededPermissions() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                toast(getString(R.string.msg_perm_granted))
            } else {
                toast(getString(R.string.msg_perm_denied))
            }
        }
    }

    // ---------------- Minimal refresh on first launch ----------------

    private fun doMinimalRefresh() {
        bgScope.launch {
            try {
                val ssid = try { WifiHelper.getCurrentSsid(this@MainActivity) } catch (_: Exception) { "" }
                val portNonRoot = AdbHelper.getCurrentPortNonRoot()
                val adbEnabled = portNonRoot.isNotEmpty()
                val pairingPort = try { AdbHelper.getPairingPort() } catch (_: Exception) { "" }
                val pairingCode = try { AdbHelper.readPairingCode() } catch (_: Exception) { "" }
                val localIp = try { WifiHelper.getLocalIpAddress(this@MainActivity) } catch (_: Exception) { "" }
                val hasRoot = ShellUtils.hasRoot()

                // Probe Xposed injection status. This is what lights up
                // the green "Active" chip on the Status tab when the hook
                // is actually loaded into this process.
                val xposed = try { XposedStatus.probe(this@MainActivity) } catch (_: Exception) {
                    XposedStatus.Info(XposedStatus.State.UNKNOWN, emptyList(), "probe failed")
                }

                status = status.copy(
                    adbEnabled = adbEnabled,
                    port = portNonRoot,
                    pairingPort = pairingPort,
                    pairingCode = pairingCode,
                    ssid = ssid,
                    localIp = localIp,
                    hasRoot = hasRoot,
                    xposed = xposed,
                )
                cachedLocalIp = localIp
                cachedPort = portNonRoot
                withContext(Dispatchers.Main) { pushStatusToActiveFragment() }
            } catch (t: Throwable) {
                Log.w(TAG, "doMinimalRefresh failed", t)
                status = status.copy(error = t.message ?: "error")
                withContext(Dispatchers.Main) { pushStatusToActiveFragment() }
            }
        }
    }

    /** Read the most recent receiver action from SharedPreferences so the
     *  Status tab can show "last triggered 5 min ago". The receiver
     *  writes these keys via WifiStateReceiver.recordLastTrigger() every
     *  time it actually toggles ADB. Returns "" / 0L when the receiver
     *  has never acted in this install. */
    fun getTrustedWifiLastAction(): String {
        return try {
            getSharedPreferences(top.cbug.adbx.WifiStateReceiver.PREFS, android.content.Context.MODE_PRIVATE)
                .getString(top.cbug.adbx.WifiStateReceiver.KEY_SSID, "") ?: ""
        } catch (_: Throwable) { "" }
    }
    /**
     * TODO: document getTrustedWifiLastActionMs
     */
    fun getTrustedWifiLastActionMs(): Long {
        return try {
            getSharedPreferences(top.cbug.adbx.WifiStateReceiver.PREFS, android.content.Context.MODE_PRIVATE)
                .getLong(top.cbug.adbx.WifiStateReceiver.KEY_MS, 0L)
        } catch (_: Throwable) { 0L }
    }

    // ---------------- Misc helpers ----------------

    /**
     * TODO: document toast
     * @param String
     */
    fun toast(msg: String) {
        if (isFinishing || isDestroyed) return
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /**
     * TODO: document copyToClipboard
     * @param String
     * @param String
     */
    fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun currentCachedIp(): String = cachedLocalIp
    fun currentCachedPort(): String = cachedPort

    /**
     * TODO: document toggleTrusted
     * @param String
     * @param Boolean
     */
    fun toggleTrusted(ssid: String, trusted: Boolean) {
        if (trusted) AppSettings.addTrusted(ssid) else AppSettings.removeTrusted(ssid)
        AppSettings.save(this)
    }
}
