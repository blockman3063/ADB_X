package top.cbug.adbx

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
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
import kotlinx.coroutines.withContext
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.ui.PortFragment
import top.cbug.adbx.ui.SettingsFragment
import top.cbug.adbx.ui.StatusFragment
import top.cbug.adbx.ui.WifiAdapter
import top.cbug.adbx.ui.WiFiFragment
import top.cbug.adbx.ui.WifiItem
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
    }

    val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val mainHandler = Handler(Looper.getMainLooper())
    private val wifiAdapter = WifiAdapter()
    private var refreshInProgress = false

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
                    R.id.tab_port     -> PortFragment()
                    R.id.tab_wifi     -> WiFiFragment()
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

    override fun onDestroy() {
        super.onDestroy()
        bgScope.cancel()
    }

    private fun switchTo(fragment: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.nav_host, fragment)
        }
    }

    // ---------------- Status refresh (used by fragments) ----------------

    fun refreshStatusAndPairing() {
        renderXposedStatus()
    }

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
                if (it.startsWith("__")) it
                else {
                    val args = status.xposed.frameworkPackages.joinToString(", ")
                    getString(R.string.xposed_inactive_subtitle_with_frame, args)
                }
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
                withContext(Dispatchers.Main) {
                    wifiAdapter.update(items)
                    pushWifiToActiveFragment(items.size)
                }
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

    private fun pushWifiToActiveFragment(count: Int) {
        val frag = supportFragmentManager.findFragmentById(R.id.nav_host) ?: return
        if (frag is WiFiFragment) frag.onNetworksLoaded(count)
    }

    // ---------------- Port apply ----------------

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

                status = status.copy(
                    adbEnabled = adbEnabled,
                    port = portNonRoot,
                    pairingPort = pairingPort,
                    pairingCode = pairingCode,
                    ssid = ssid,
                    localIp = localIp,
                    hasRoot = hasRoot,
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

    // ---------------- Misc helpers ----------------

    fun toast(msg: String) {
        if (isFinishing || isDestroyed) return
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun currentCachedIp(): String = cachedLocalIp
    fun currentCachedPort(): String = cachedPort

    fun toggleTrusted(ssid: String, trusted: Boolean) {
        if (trusted) AppSettings.addTrusted(ssid) else AppSettings.removeTrusted(ssid)
        AppSettings.save(this)
    }
}
