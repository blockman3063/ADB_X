package top.cbug.adbx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WifiHelper

/**
 * Full-screen pairing manager. Three responsibilities:
 *   1. Surface the active pairing session (port + code + copy-able
 *      `adb pair host:port code`) by polling AdbHelper.getPairingPort()
 *      on a background coroutine. Updates live as the underlying state
 *      changes.
 *   2. Let the user set a custom 6-8 digit pairing code via
 *      AdbHelper.setPairingCode() — gets stored in
 *      /data/local/tmp/adb_x_pairing_code for the LSPosed hook to read
 *      when adbd's pairing dialog comes up.
 *   3. Quick-action button to open Developer Options so the user can
 *      flip on "Pair device with pairing code" without leaving the app.
 */
class PairingActivity : AppCompatActivity() {

    private val tag = "ADB_X_Pairing"
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPairingStatusSub: TextView
    private lateinit var tvPairingHint: TextView
    private lateinit var btnCopyPairCommand: MaterialButton
    private lateinit var btnOpenDevOptions: MaterialButton
    private lateinit var btnTriggerPair: MaterialButton
    private lateinit var tilNewCode: TextInputLayout
    private lateinit var etNewCode: TextInputEditText
    private lateinit var btnSaveCode: MaterialButton

    /**
     * Wrap the base context with the user's preferred locale. Without
     * this the system-default `values/strings.xml` (English) leaks into
     * `getString(...)` calls even when the app-wide locale is zh-rCN.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(top.cbug.adbx.util.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        toolbar             = findViewById(R.id.pairingToolbar)
        tvPairingStatusSub  = findViewById(R.id.tvPairingStatusSub)
        tvPairingHint       = findViewById(R.id.tvPairingHint)
        btnCopyPairCommand  = findViewById(R.id.btnCopyPairCommand)
        btnOpenDevOptions    = findViewById(R.id.btnOpenDevOptions)
        btnTriggerPair       = findViewById(R.id.btnTriggerPair)
        tilNewCode          = findViewById(R.id.tilNewCode)
        etNewCode           = findViewById(R.id.etNewCode)
        btnSaveCode         = findViewById(R.id.btnSaveCode)

        toolbar.setNavigationOnClickListener { finish() }

        btnTriggerPair.setOnClickListener {
            // Direct IAdbManager.enablePairingByPairingCode via shell.
            // This spawns the ephemeral pairing port and posts the
            // WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION intent — same path
            // as the user tapping "Pair device with pairing code" in
            // Developer Options.
            val ok = AdbHelper.triggerPairing(this)
            if (ok) {
                Toast.makeText(this, getString(R.string.msg_pair_requested), Toast.LENGTH_SHORT).show()
                // Refresh status so the port + code appear as soon as
                // the system has them (typically <300 ms).
                bgScope.launch { kotlinx.coroutines.delay(500); refreshStatus() }
            } else {
                Toast.makeText(this, "Trigger failed — check logcat", Toast.LENGTH_LONG).show()
            }
        }

        btnOpenDevOptions.setOnClickListener {
            // Stock Android route. We try a few candidate Settings intents
            // in order of specificity:
            //   1. ACTION_WIRELESS_DEBUGGING_SETTINGS — direct wireless debug
            //      pane, introduced in API 33 (Android 13+).
            //   2. ACTION_APPLICATION_DEVELOPMENT_SETTINGS — Developer Options
            //      (Android 4.2+, universally available).
            // Some ROMs also accept ACTION_WIRELESS_SETTINGS but that's the
            // broader Network page, not what we want here.
            val candidates = listOf(
                "android.settings.WIRELESS_DEBUGGING_SETTINGS",
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            )
            for (action in candidates) {
                try {
                    val i = Intent(action)
                    if (i.resolveActivity(packageManager) != null) {
                        startActivity(i)
                        return@setOnClickListener
                    }
                } catch (_: Throwable) { }
            }
            Toast.makeText(this, "Cannot open Developer Options", Toast.LENGTH_LONG).show()
        }

        btnCopyPairCommand.setOnClickListener {
            val cmd = currentCommand()
            if (cmd.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb pair command", cmd))
                Toast.makeText(this, getString(R.string.msg_copied, cmd), Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveCode.setOnClickListener {
            val raw = etNewCode.text?.toString()?.trim().orEmpty()
            if (raw.length !in 6..8 || !raw.all { it.isDigit() }) {
                tilNewCode.error = getString(R.string.err_pairing_length)
                return@setOnClickListener
            }
            tilNewCode.error = null
            val ok = AdbHelper.setPairingCode(raw)
            if (ok) {
                Toast.makeText(this, getString(R.string.msg_pairing_saved, raw), Toast.LENGTH_SHORT).show()
                etNewCode.setText("")
                bgScope.launch { refreshStatus() }
            } else {
                Toast.makeText(this, "Failed to save (need root)", Toast.LENGTH_LONG).show()
            }
        }

        bgScope.launch { refreshStatusLoop() }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        bgScope.launch { refreshStatus() }
    }

    /** Current adb pair command built from cached status; empty if unknown. */
    private var lastStatus: AdbHelper.AdbStatus? = null

    private suspend fun refreshStatus() {
        val status = AdbHelper.getFullStatus(this@PairingActivity)
        lastStatus = status
        withContext(Dispatchers.Main) { applyStatusToViews(status) }
    }

    /**
     * Poll the status every 3s while the activity is visible. The
     * pairing port is ephemeral — once the dev-options dialog closes,
     * the port disappears — so a timer-driven refresh keeps the UI
     * accurate without the user having to back out and re-enter.
     */
    private suspend fun refreshStatusLoop() {
        while (true) {
            try {
                refreshStatus()
            } catch (t: Throwable) {
                Log.w(tag, "refresh failed: ${t.message}")
            }
            kotlinx.coroutines.delay(3000)
        }
    }

    private fun applyStatusToViews(status: AdbHelper.AdbStatus) {
        val code = status.pairingCode
        val port = status.pairingPort
        val pairingActive = port.isNotBlank()

        if (pairingActive) {
            tvPairingStatusSub.text = if (code.isNotBlank()) {
                getString(R.string.pairing_prefix_cmd_fmt, hostForDisplay(), port, code)
            } else {
                "${hostForDisplay()}:$port\n${getString(R.string.pairing_summary_no_code)}"
            }
            tvPairingHint.text = getString(R.string.pairing_status_active_fmt, port)
        } else {
            tvPairingStatusSub.text = "—"
            tvPairingHint.text = getString(R.string.pairing_status_idle) +
                "\n" + getString(R.string.pairing_hint_open_dev) +
                "\n\n" + getString(R.string.pairing_manual_hint)
        }
        btnCopyPairCommand.isEnabled = pairingActive || code.isNotBlank()
    }

    private fun currentCommand(): String {
        val s = lastStatus ?: return ""
        if (s.pairingPort.isBlank() || s.pairingCode.isBlank()) return ""
        return getString(R.string.pairing_prefix_cmd_fmt, hostForDisplay(), s.pairingPort, s.pairingCode)
    }

    private fun hostForDisplay(): String {
        // Try IPv4 from WifiHelper; fall back to a placeholder so the
        // command still has shape even if the IP probe failed.
        val ip = WifiHelper.getExternalIpAddress().ifBlank {
            try { WifiHelper.getLocalIpAddress(this).substringBefore("/") }
            catch (_: Throwable) { "<phone-ip>" }
        }
        return ip
    }
}
