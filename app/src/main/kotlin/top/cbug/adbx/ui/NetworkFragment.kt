package top.cbug.adbx.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Wireless tab — network-specific controls that used to live on the
 * Status tab. The tab hosts five cards in priority order:
 *
 *   1. Fixed-port + IP address (the "how do I connect?" answer)
 *   2. Manage Wi-Fi list (opens the dedicated WifiSettingsActivity
 *      with the full 3-section list)
 *   3. Trusted Wi-Fi — the auto-toggle card and the per-SSID chip
 *      group showing what is currently in the trust set
 *   4. Pairing shortcut — the entry point that opens PairingActivity
 *      (dev-options opener + set-code form)
 *   5. Pairing ACTIVE — only shown when a pairing port is open; the
 *      `adb pair host:port code` line + the breakdown + a copy button
 *
 * Pairing state is bound from the same UiModel the Status tab uses,
 * so the two views stay in sync without us re-reading shared prefs.
 */
class NetworkFragment : Fragment() {

    private lateinit var cgTrusted: ChipGroup
    private lateinit var btnRefreshWifi: MaterialButton
    private lateinit var cardWifiList: MaterialCardView
    private lateinit var swFixedPort: MaterialSwitch
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnApplyPort: MaterialButton
    private lateinit var tvAddress: TextView
    private lateinit var btnCopyAddress: MaterialButton

    // Trusted-Wi-Fi card
    private lateinit var cardTrustedWifi: MaterialCardView
    private lateinit var tvTrustedWifiSubtitle: TextView
    private lateinit var btnTrustCurrentSsid: MaterialButton

    // Pairing shortcut card
    private lateinit var cardPairingShortcut: MaterialCardView
    private lateinit var btnStartPairing: MaterialButton
    private lateinit var tvPairingHint: TextView

    // Pairing ACTIVE card
    private lateinit var cardPairingActive: MaterialCardView
    private lateinit var tvPairingConnectionString: TextView
    private lateinit var tvPairingBreakdown: TextView
    private lateinit var btnCopyPairCommand: MaterialButton
    private lateinit var tvPairingCountdown: TextView
    private lateinit var etPairingPort: TextInputEditText

    // Cached pairing snapshot so we can re-render after the user
    // edits the port input. The Status tab drives renderPairing() each
    // refresh; we keep the last values so the editable input box
    // doesn't reset on every tick.
    private var lastPairingPort: String = ""
    private var lastPairingCode: String = ""
    private var lastLocalIp: String = ""
    private var lastExternalIp: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_network, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cgTrusted      = view.findViewById(R.id.cgTrusted)
        btnRefreshWifi = view.findViewById(R.id.btnRefreshWifi)
        cardWifiList   = view.findViewById(R.id.cardWifiList)
        swFixedPort    = view.findViewById(R.id.swFixedPort)
        tilPort        = view.findViewById(R.id.tilPort)
        etPort         = view.findViewById(R.id.etPort)
        btnApplyPort   = view.findViewById(R.id.btnApplyPort)
        tvAddress      = view.findViewById(R.id.tvAddress)
        btnCopyAddress = view.findViewById(R.id.btnCopyAddress)

        cardTrustedWifi      = view.findViewById(R.id.cardTrustedWifi)
        tvTrustedWifiSubtitle = view.findViewById(R.id.tvTrustedWifiSubtitle)
        btnTrustCurrentSsid = view.findViewById(R.id.btnTrustCurrentSsid)

        cardPairingShortcut = view.findViewById(R.id.cardPairingShortcut)
        btnStartPairing = view.findViewById(R.id.btnStartPairing)
        tvPairingHint       = view.findViewById(R.id.tvPairingHint)

        cardPairingActive         = view.findViewById(R.id.cardPairingActive)
        tvPairingConnectionString = view.findViewById(R.id.tvPairingConnectionString)
        tvPairingBreakdown        = view.findViewById(R.id.tvPairingBreakdown)
        btnCopyPairCommand        = view.findViewById(R.id.btnCopyPairCommand)
        tvPairingCountdown        = view.findViewById(R.id.tvPairingCountdown)
        etPairingPort             = view.findViewById(R.id.etPairingPort)

        AppSettings.load(requireContext())
        renderTrustedChips()
        renderPortControls()
        renderAddress()
        renderTrustedWifiCard()
        renderPairingHint("")

        // The tab itself never loads the wifi list — opening the dedicated
        // activity triggers its own refresh(). WifiSettingsActivity owns
        // its loading state, so we don't share `refreshInProgress` with
        // MainActivity.doFullRefresh() any more.
        cardWifiList.setOnClickListener {
            (activity as? MainActivity)?.openWifiSettingsActivity()
        }
        btnRefreshWifi.setOnClickListener {
            // Keep the button as a no-op affordance that opens the list
            // — the dedicated activity has its own refresh control. Avoid
            // running MainActivity.refreshWifiList() because it shares
            // a single-flight flag with doFullRefresh() and would toast
            // "仍在加载" while a full status refresh is in flight.
            (activity as? MainActivity)?.openWifiSettingsActivity()
        }

        swFixedPort.setOnCheckedChangeListener { _, checked ->
            AppSettings.fixedPortEnabled = checked
            AppSettings.save(requireContext())
        }

        btnApplyPort.setOnClickListener {
            val portText = etPort.text?.toString()?.trim().orEmpty()
            val port = portText.toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                tilPort.error = getString(R.string.err_port_range)
                return@setOnClickListener
            }
            tilPort.error = null
            AppSettings.fixedPort = port
            AppSettings.save(requireContext())
            (activity as? MainActivity)?.applyFixedPort(port)
        }

        btnCopyAddress.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            val ip = act.currentCachedIp()
            val port = act.currentCachedPort()
            val text = when {
                ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
                ip.isNotEmpty() -> ip
                port.isNotEmpty() -> port
                else -> ""
            }
            if (text.isNotEmpty()) {
                act.copyToClipboard("ADB address", text)
                act.toast(getString(R.string.msg_copied, text))
            }
        }

        // Pairing shortcut opens the dedicated PairingActivity
        cardPairingShortcut.setOnClickListener { act ->
            (activity as? MainActivity)?.openPairingActivity()
        }

        btnStartPairing.setOnClickListener { triggerInAppPairing() }

        // Trust-current-SSID toggle. Lets users add the SSID they are
        // currently connected to without first opening the wifi
        // management screen and toggling the switch there.
        btnTrustCurrentSsid.setOnClickListener {
            val act = (activity as? MainActivity)
                ?: return@setOnClickListener
            val currentSsid = WifiHelper.cleanSsid(act.currentSsid)
            if (currentSsid.isBlank()) return@setOnClickListener
            AppSettings.load(requireContext())
            if (AppSettings.isTrusted(currentSsid)) {
                AppSettings.removeTrusted(currentSsid)
            } else {
                AppSettings.addTrusted(currentSsid)
            }
            AppSettings.save(requireContext())
            // Re-evaluate the trigger so the user sees the toggle
            // take effect immediately instead of waiting for the
            // next NETWORK_STATE_CHANGED.
            top.cbug.adbx.WifiStateReceiver.fireOnce(requireContext())
            act.doMinimalRefresh()
            renderTrustedWifiCard()
            renderTrustedChips()
        }
    }

    override fun onResume() {
        super.onResume()
        // Cheap: just re-render from in-memory Settings + cached IP/port
        // values that MainActivity already keeps. No shell, no wifi scan.
        AppSettings.load(requireContext())
        renderTrustedChips()
        renderPortControls()
        renderAddress()
        renderTrustedWifiCard()
    }

    private fun renderTrustedChips() {
        cgTrusted.removeAllViews()
        for (ssid in AppSettings.trustedSet().sorted()) {
            val chip = Chip(requireContext()).apply {
                text = ssid
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    AppSettings.removeTrusted(ssid)
                    AppSettings.save(requireContext())
                    renderTrustedChips()
                    renderTrustedWifiCard()
                }
            }
            cgTrusted.addView(chip)
        }
    }

    private fun renderPortControls() {
        swFixedPort.isChecked = AppSettings.fixedPortEnabled
        etPort.setText(AppSettings.fixedPort.toString())
    }

    private fun renderAddress() {
        val act = activity as? MainActivity
        val ip = act?.currentCachedIp().orEmpty()
        val port = act?.currentCachedPort().orEmpty()
        tvAddress.text = when {
            ip.isNotEmpty() && port.isNotEmpty() -> "$ip:$port"
            ip.isNotEmpty() -> ip
            port.isNotEmpty() -> port
            else -> "—"
        }
    }

    private fun renderTrustedWifiCard() {
        val act = activity as? MainActivity ?: return
        val settings = AppSettings
        val armed = settings.autoEnable
        val currentSsid = WifiHelper.cleanSsid(act.currentSsid)
        val ssidDisplay = if (currentSsid.isBlank()) "—" else currentSsid
        if (!armed) {
            tvTrustedWifiSubtitle.text = getString(R.string.trusted_wifi_status_not_armed)
            btnTrustCurrentSsid.visibility = View.GONE
            return
        }
        if (currentSsid.isBlank()) {
            btnTrustCurrentSsid.visibility = View.GONE
        } else {
            val trusted = settings.isTrusted(currentSsid)
            btnTrustCurrentSsid.text = getString(
                if (trusted) R.string.trust_current_ssid_remove
                else R.string.trust_current_ssid
            )
            btnTrustCurrentSsid.visibility = View.VISIBLE
        }
        val subtitle = when {
            settings.trustedSet().isEmpty() -> getString(R.string.trusted_wifi_status_no_ssids)
            currentSsid.isBlank() -> getString(R.string.trusted_wifi_status_disabled) + " · " + getString(R.string.sw_auto_enable)
            settings.isTrusted(currentSsid) -> getString(R.string.trusted_wifi_status_armed, ssidDisplay, getString(R.string.trusted_wifi_trusted))
            else -> getString(R.string.trusted_wifi_status_armed, ssidDisplay, getString(R.string.trusted_wifi_untrusted))
        }
        val lastAction = act.getTrustedWifiLastAction()
        val lastActionMs = act.getTrustedWifiLastActionMs()
        val ago = formatAgo(lastActionMs)
        val actionLine = if (lastActionMs == 0L) {
            getString(R.string.trusted_wifi_never_triggered)
        } else {
            getString(R.string.trusted_wifi_last_trigger, lastAction, ago)
        }
        tvTrustedWifiSubtitle.text = subtitle + "\n" + actionLine
    }

    private fun formatAgo(ms: Long): String {
        if (ms == 0L) return ""
        val deltaMin = ((System.currentTimeMillis() - ms) / 60_000L).toInt()
        return when {
            deltaMin < 1 -> getString(R.string.trusted_wifi_just_now)
            deltaMin < 60 -> getString(R.string.trusted_wifi_ago_minutes, deltaMin)
            else -> getString(R.string.trusted_wifi_ago_hours, deltaMin / 60)
        }
    }

    /**
     * Bind the pair of pairing cards from the latest UiModel. Called by
     * the host Activity so we don't read SharedPreferences or shell
     * here — the values are already in the same UiModel the Status
     * tab uses.
     */
    fun renderPairing(
        pairingPort: String,
        pairingCode: String,
        localIp: String,
        externalIp: String,
        adbEnabled: Boolean,
    ) {
        if (!isAdded) return
        lastPairingPort = pairingPort
        lastPairingCode = pairingCode
        lastLocalIp = localIp
        lastExternalIp = externalIp

        val hintText = when {
            pairingCode.isNotBlank() ->
                getString(R.string.si_value_pairing_code, pairingCode)
            pairingPort.isNotBlank() ->
                getString(R.string.si_value_pairing_active, pairingPort)
            adbEnabled -> getString(R.string.si_value_pairing_idle)
            else -> "—"
        }
        renderPairingHint(hintText)

        val pairingActive = pairingPort.isNotBlank()
        cardPairingActive.visibility = if (pairingActive) View.VISIBLE else View.GONE
        // The shortcut card is always visible — it leads to PairingActivity
        // which exposes the set-code form + dev-options opener, independent
        // of whether a pairing session is currently running.
        cardPairingShortcut.visibility = View.VISIBLE

        if (!pairingActive) {
            // Marker expired / no active port — reset the button so the
            // user can tap "开启配对模式" again. Matches the
            // status-tab behaviour from the previous layout.
            unstickPairingButton()
            return
        }

        val host = when {
            localIp.isNotEmpty() -> localIp
            externalIp.isNotEmpty() -> externalIp
            else -> "<phone-ip>"
        }
        // Auto-populate the editable port input the first time the
        // card is shown, then leave the user's edits alone.
        if (etPairingPort.text.isNullOrBlank()) {
            etPairingPort.setText(pairingPort)
        }
        val port = etPairingPort.text?.toString()?.trim().orEmpty().ifEmpty { pairingPort }
        val codeFinal = pairingCode.ifBlank { lastPairingCode }
        val cmd = "adb pair $host:$port $codeFinal"
        tvPairingConnectionString.text = cmd
        tvPairingBreakdown.text = "host: $host:$port  ·  code: $codeFinal"
        btnCopyPairCommand.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            act.copyToClipboard("adb pair command", cmd)
            act.toast(getString(R.string.msg_copied, cmd))
        }
        tvPairingCountdown.text = getString(R.string.pairing_expires_fmt, 120)
    }

    private fun renderPairingHint(hintText: String) {
        if (hintText.isEmpty()) {
            tvPairingHint.text = getString(R.string.pairing_hint_idle)
        } else {
            tvPairingHint.text = hintText
        }
    }

    /**
     * Trigger ADB pairing mode entirely from inside the app: write the
     * pair-request marker file. The system_server-side LSPosed watcher
     * (set up in AdbSystemHooks.hook()) picks it up within ~1 s and calls
     * AdbDebuggingManager.startAdbPairing(), then writes the resulting
     * port to /data/local/tmp/adb_x_pairing_port for our reader to pick
     * up. The user does not need to touch Developer options.
     */
    private fun triggerInAppPairing() {
        try {
            val ok = AdbHelper.triggerPairing(requireContext())
            if (ok) {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.msg_pair_requested,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                tvPairingHint.text = getString(R.string.msg_pair_requested)
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Trigger failed — try Developer Options",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            lockPairingButton()
            view?.postDelayed({ unstickPairingButton() }, 30_000L)
        } catch (t: Throwable) {
            Log.e("ADB_X_NetworkFr", "triggerInAppPairing failed", t)
            android.widget.Toast.makeText(
                requireContext(),
                "Trigger failed: " + t.message,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun lockPairingButton() {
        btnStartPairing.isEnabled = false
        btnStartPairing.text = getString(R.string.section_pairing_code)
    }

    /**
     * Re-enable the 开启配对模式 button and reset the hint text. Called
     * either by the 30-second safety timer after a tap, or by
     * renderPairing() each tick once the marker file has expired (or
     * never appeared in the first place), so the user is never
     * stranded on a disabled control.
     */
    private fun unstickPairingButton() {
        if (!isAdded) return
        btnStartPairing.isEnabled = true
        btnStartPairing.text = getString(R.string.btn_start_pairing)
        tvPairingHint.text = getString(R.string.pairing_hint_idle)
    }
}
