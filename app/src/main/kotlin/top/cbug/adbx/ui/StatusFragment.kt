package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.widget.TextView
import java.io.File
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.util.XposedStatus
import top.cbug.adbx.store.Settings as AppSettings

/**
 * Status tab — Xposed card + 5 status indicators + ADB enable/disable
 * + pairing code card.
 */
class StatusFragment : Fragment() {

    /** Plain-data snapshot the Activity pushes when status updates. */
    data class UiModel(
        val xposedTitle: String,
        val xposedSubtitle: String,
        val xposedChipText: String,
        val xposedIsActive: Boolean,
        val adbState: Boolean,
        val error: String?,
        val port: String,
        val pairingPort: String,
        val pairingCode: String,
        val ssid: String,
        val localIp: String,
        val externalIp: String,
        val hasRoot: Boolean,
    )
    private lateinit var cardXposedStatus: MaterialCardView
    private lateinit var tvXposedTitle: TextView
    private lateinit var tvXposedSubtitle: TextView
    private lateinit var chipXposedState: Chip

    private lateinit var siAdb: StatusIndicatorView
    private lateinit var siPairing: StatusIndicatorView
    private lateinit var siPort: StatusIndicatorView
    private lateinit var siWifi: StatusIndicatorView
    private lateinit var siRoot: StatusIndicatorView
    private lateinit var toggleAdb: MaterialButtonToggleGroup
    private lateinit var btnEnableAdb: MaterialButton
    private lateinit var btnDisableAdb: MaterialButton

    private lateinit var cardPairingShortcut: MaterialCardView
    private lateinit var btnStartPairing: MaterialButton
    private lateinit var tvPairingHint: TextView
    private lateinit var cardTrustedWifi: MaterialCardView
    private lateinit var tvTrustedWifiSubtitle: TextView
    private lateinit var btnTrustCurrentSsid: MaterialButton
    private lateinit var cardPairingActive: MaterialCardView
    private lateinit var tvPairingConnectionString: TextView
    private lateinit var tvPairingBreakdown: TextView
    private lateinit var btnCopyPairCommand: MaterialButton
    private lateinit var tvPairingCountdown: TextView
    private lateinit var etPairingPort: TextInputEditText

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardXposedStatus  = view.findViewById(R.id.cardXposedStatus)
        tvXposedTitle      = view.findViewById(R.id.tvXposedTitle)
        tvXposedSubtitle   = view.findViewById(R.id.tvXposedSubtitle)
        chipXposedState    = view.findViewById(R.id.chipXposedState)

        siAdb      = view.findViewById(R.id.siAdb)
        siPairing  = view.findViewById(R.id.siPairing)
        siPort     = view.findViewById(R.id.siPort)
        siWifi     = view.findViewById(R.id.siWifi)
        siRoot     = view.findViewById(R.id.siRoot)
        toggleAdb  = view.findViewById(R.id.toggleAdb)
        btnEnableAdb = view.findViewById(R.id.btnEnableAdb)
        btnDisableAdb = view.findViewById(R.id.btnDisableAdb)

        cardPairingShortcut = view.findViewById(R.id.cardPairingShortcut)
        btnStartPairing = view.findViewById(R.id.btnStartPairing)
        tvPairingHint       = view.findViewById(R.id.tvPairingHint)

        cardTrustedWifi      = view.findViewById(R.id.cardTrustedWifi)
        tvTrustedWifiSubtitle = view.findViewById(R.id.tvTrustedWifiSubtitle)
        btnTrustCurrentSsid = view.findViewById(R.id.btnTrustCurrentSsid)

        cardPairingActive         = view.findViewById(R.id.cardPairingActive)
        tvPairingConnectionString = view.findViewById(R.id.tvPairingConnectionString)
        tvPairingBreakdown        = view.findViewById(R.id.tvPairingBreakdown)
        btnCopyPairCommand        = view.findViewById(R.id.btnCopyPairCommand)
        tvPairingCountdown        = view.findViewById(R.id.tvPairingCountdown)
        etPairingPort             = view.findViewById(R.id.etPairingPort)

        siAdb.setLabel(getString(R.string.si_label_adb))
        siPairing.setLabel(getString(R.string.si_label_pairing))
        siPort.setLabel(getString(R.string.si_label_port))
        siWifi.setLabel(getString(R.string.si_label_wifi))
        siRoot.setLabel(getString(R.string.si_label_root))
        for (si in listOf(siAdb, siPairing, siPort, siWifi, siRoot)) {
            si.setState(StatusIndicatorView.State.UNKNOWN)
            si.setValue(getString(R.string.si_value_loading))
        }
        tvPairingHint.text = getString(R.string.si_value_loading)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.refreshStatusAndPairing()
        (activity as? MainActivity)?.doFullRefresh()
    }

    /** Called by MainActivity when new status data is available. */
    fun renderStatus(m: UiModel) {
        if (!isAdded) return
        android.util.Log.d("ADB_X_StatusFr", "renderStatus: adb=" + m.adbState + " port='" + m.port + "'")
        // Xposed card
        tvXposedTitle.text = m.xposedTitle
        tvXposedSubtitle.text = m.xposedSubtitle
        chipXposedState.text = m.xposedChipText
        val chipIcon = if (m.xposedIsActive) R.drawable.ic_check_circle else R.drawable.ic_warning
        chipXposedState.setChipIconResource(chipIcon)
        chipXposedState.chipBackgroundColor = ContextCompat.getColorStateList(
            requireContext(),
            if (m.xposedIsActive) R.color.state_active_bg else R.color.state_inactive_bg
        )
        chipXposedState.setTextColor(ContextCompat.getColor(
            requireContext(),
            if (m.xposedIsActive) R.color.state_active_fg else R.color.state_inactive_fg
        ))
        cardXposedStatus.setCardBackgroundColor(ContextCompat.getColor(
            requireContext(),
            if (m.xposedIsActive) R.color.state_active_bg else R.color.state_inactive_bg
        ))

        // ADB indicator
        if (m.error != null) {
            siAdb.setState(StatusIndicatorView.State.UNKNOWN)
            siAdb.setValue(m.error)
        } else if (m.adbState) {
            siAdb.setState(StatusIndicatorView.State.OK)
            siAdb.setValue(getString(R.string.si_value_enabled))
        } else {
            siAdb.setState(StatusIndicatorView.State.OFF)
            siAdb.setValue(getString(R.string.si_value_disabled))
        }

        // Port
        if (m.port.isNotBlank()) {
            siPort.setState(StatusIndicatorView.State.OK)
            siPort.setValue(m.port)
        } else {
            siPort.setState(StatusIndicatorView.State.OFF)
            siPort.setValue("—")
        }

        // Pairing
        when {
            m.pairingCode.isNotBlank() -> {
                siPairing.setState(StatusIndicatorView.State.WARN)
                siPairing.setValue(getString(R.string.si_value_pairing_code, m.pairingCode))
            }
            m.pairingPort.isNotBlank() -> {
                siPairing.setState(StatusIndicatorView.State.WARN)
                siPairing.setValue(getString(R.string.si_value_pairing_active, m.pairingPort))
            }
            m.adbState -> {
                siPairing.setState(StatusIndicatorView.State.OFF)
                siPairing.setValue(getString(R.string.si_value_pairing_idle))
            }
            else -> {
                siPairing.setState(StatusIndicatorView.State.OFF)
                siPairing.setValue("—")
            }
        }

        // WiFi
        if (m.ssid.isNotBlank()) {
            siWifi.setState(StatusIndicatorView.State.OK)
            val text = buildString {
                append(m.ssid)
                if (m.localIp.isNotEmpty()) append("  |  ${m.localIp}")
                if (m.externalIp.isNotEmpty()) append("  |  ${m.externalIp}")
            }
            siWifi.setValue(text)
        } else {
            siWifi.setState(StatusIndicatorView.State.OFF)
            siWifi.setValue(when {
                m.localIp.isNotEmpty() -> m.localIp
                m.externalIp.isNotEmpty() -> "Ext: ${m.externalIp}"
                else -> getString(R.string.si_value_wifi_disconnected)
            })
        }

        // Root
        siRoot.setState(
            if (m.hasRoot) StatusIndicatorView.State.OK else StatusIndicatorView.State.UNKNOWN
        )
        siRoot.setValue(
            if (m.hasRoot) getString(R.string.si_value_available)
            else getString(R.string.si_value_unavailable)
        )

        // Pairing shortcut card (default state) — line below the indicators
        val code = m.pairingCode
        tvPairingHint.text = if (code.isNotBlank()) {
            getString(R.string.si_value_pairing_code, code)
        } else if (m.pairingPort.isNotBlank()) {
            getString(R.string.si_value_pairing_active, m.pairingPort)
        } else if (m.adbState) {
            getString(R.string.si_value_pairing_idle)
        } else {
            "—"
        }

        // Pairing ACTIVE card — shown when a pairing port is currently
        // open. Android gives the pairing port a short TTL (default 120s),
        // so we surface the full `adb pair host:port code` command the
        // Pairing ACTIVE card (shown only when a pairing port is open)
        val pairingActive = m.pairingPort.isNotBlank()
        cardPairingActive.visibility = if (pairingActive) View.VISIBLE else View.GONE
        // The shortcut card is always visible — it leads to PairingActivity
        // which exposes the set-code form + dev-options opener, independent
        // of whether a pairing session is currently running.
        cardPairingShortcut.visibility = View.VISIBLE

        // If the detected pairing marker has expired (TTL elapsed / file
        // deleted), make sure the user can tap 开启配对模式 again — both
        // the hint text and the button enabled state reset.
        if (!pairingActive) unstickPairingButton()

        if (pairingActive) {
            val host = if (m.localIp.isNotEmpty()) m.localIp
                       else if (m.externalIp.isNotEmpty()) m.externalIp
                       else "<phone-ip>"
            // Prefer the user-editable input box (auto-detected value goes
            // in as the starting point but the user can correct it).
            if (etPairingPort.text.isNullOrBlank()) {
                etPairingPort.setText(m.pairingPort)
            }
            val port = etPairingPort.text?.toString()?.trim().orEmpty().ifEmpty { m.pairingPort }
            val codeFinal = code.ifBlank { m.pairingCode }
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

        renderTrustedWifi(m.ssid)
    }

    /**
     * Render the Trusted-WiFi auto-toggle card.
     */
    private fun renderTrustedWifi(currentSsid: String) {
        val settings = top.cbug.adbx.store.Settings
        val armed = settings.autoEnable || settings.autoDisable
        val ssidDisplay = if (currentSsid.isBlank()) "—" else currentSsid
        if (!armed) {
            tvTrustedWifiSubtitle.text = getString(R.string.trusted_wifi_status_not_armed)
            btnTrustCurrentSsid.visibility = View.GONE
            return
        }
        // The trust button is the user's shortcut — show it whenever
        // there's an SSID to act on, regardless of whether auto-toggle
        // has been armed.
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
            currentSsid.isBlank() -> {
                val what = when {
                    settings.autoEnable && settings.autoDisable -> getString(R.string.sw_auto_enable) + " + " + getString(R.string.sw_auto_disable)
                    settings.autoEnable -> getString(R.string.sw_auto_enable)
                    else -> getString(R.string.sw_auto_disable)
                }
                getString(R.string.trusted_wifi_status_disabled) + " · " + what
            }
            settings.isTrusted(currentSsid) -> getString(R.string.trusted_wifi_status_armed, ssidDisplay, getString(R.string.trusted_wifi_trusted))
            else -> getString(R.string.trusted_wifi_status_armed, ssidDisplay, getString(R.string.trusted_wifi_untrusted))
        }
        val lastAction = (activity as? MainActivity)?.getTrustedWifiLastAction() ?: ""
        val lastActionMs = (activity as? MainActivity)?.getTrustedWifiLastActionMs() ?: 0L
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

    private fun setupListeners() {
        val act = activity as? MainActivity ?: return
        cardXposedStatus.setOnClickListener { act.showXposedHelpDialog() }

        toggleAdb.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            act.bgScope.launch {
                when (checkedId) {
                    R.id.btnEnableAdb -> {
                        act.toast(getString(R.string.msg_enabling_adb))
                        AdbHelper.enableWirelessAdb()
                        act.doFullRefresh()
                    }
                    R.id.btnDisableAdb -> {
                        act.toast(getString(R.string.msg_disabling_adb))
                        AdbHelper.disableWirelessAdb()
                        act.doFullRefresh()
                    }
                }
            }
        }

        // Pairing shortcut opens the dedicated PairingActivity
        cardPairingShortcut.setOnClickListener { act.openPairingActivity() }

        btnStartPairing.setOnClickListener { triggerInAppPairing() }

        // Trust-current-SSID toggle inside the trusted-wifi card.
        // Lets users enable auto-toggle without first opening the wifi
        // management screen and toggling the switch there.
        btnTrustCurrentSsid.setOnClickListener {
            val act = (activity as? MainActivity)
                ?: return@setOnClickListener
            // Read the SSID from MainActivity's cached state — the
            // Status tab is showing it already, so we don't have to
            // query the WifiManager again just for the trust-toggle.
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

    private fun lockPairingButton() {
        btnStartPairing.isEnabled = false
        btnStartPairing.text = getString(R.string.section_pairing_code)
    }

    /**
     * Path A: write the pair-request marker file for the system_server-
     * side LSPosed watcher. The watcher (added in AdbSystemHooks.hook) is
     * the right place for this — it inherits the AdbDebuggingManager
     * instance reference, reflective access to startAdbPairing(), and
     * root-level file write to /data/local/tmp/adb_x_pairing_port.
     *
     * Path B (last-resort): if the system_server LSPosed hook is not
     * loaded on this ROM (KernelSU-only installation does not register
     * top.cbug.adbx as a module, so lspd never injects it), we leave the
     * request marker set so the next device reboot + LSPosed reload
     * picks it up.
     */
    private fun triggerInAppPairing() {
        try {
            // Calls IAdbManager.enablePairingByPairingCode via shell
            // `service call adb 8` (AOSP transaction code 8). If the
            // shell path is gated (e.g. app uid via su, where
            // servicemanager denies by SELinux context) the helper
            // opens the Developer Options screen as a fallback so
            // the user can still enable pairing via the UI tap.
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
            android.util.Log.e("ADB_X_StatusFr", "triggerInAppPairing failed", t)
            android.widget.Toast.makeText(
                requireContext(),
                "Trigger failed: " + t.message,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Re-enable the 开启配对模式 button and reset the hint text. Called
     * either by the 30-second safety timer after a tap, or by
     * renderStatus() each tick once the marker file has expired
     * (or never appeared in the first place), so the user is never
     * stranded on a disabled control.
     */
    private fun unstickPairingButton() {
        if (!isAdded) return
        btnStartPairing.isEnabled = true
        btnStartPairing.text = getString(R.string.btn_start_pairing)
        tvPairingHint.text = getString(R.string.pairing_hint_idle)
    }
}

