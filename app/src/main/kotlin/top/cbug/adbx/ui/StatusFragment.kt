package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import top.cbug.adbx.MainActivity
import top.cbug.adbx.R
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.BootLogger

/**
 * Status tab — Xposed card + 5 status indicators + ADB enable/disable.
 *
 * The Trusted-Wi-Fi / Pairing controls used to live here too, but
 * they're network-specific actions and moved to the Wireless tab
 * (NetworkFragment). The status indicators stay on the Status tab
 * because they're the at-a-glance "is everything healthy?" view;
 * tapping through to do something is a Wireless-tab concern.
 */
class StatusFragment : Fragment() {

    /** Plain-data snapshot the Activity pushes when status updates. */
    data class StatusModel(
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
        val adbMode: String,
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

    private lateinit var cardBootLog: MaterialCardView
    private lateinit var tvBootLog: TextView
    private lateinit var btnClearBootLog: MaterialButton
    private lateinit var btnToggleBootLog: MaterialButton

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

        cardBootLog = view.findViewById(R.id.cardBootLog)
        tvBootLog = view.findViewById(R.id.tvBootLog)
        btnClearBootLog = view.findViewById(R.id.btnClearBootLog)
        btnToggleBootLog = view.findViewById(R.id.btnToggleBootLog)

        siAdb.setLabel(getString(R.string.si_label_adb))
        siPairing.setLabel(getString(R.string.si_label_pairing))
        siPort.setLabel(getString(R.string.si_label_port))
        siWifi.setLabel(getString(R.string.si_label_wifi))
        siRoot.setLabel(getString(R.string.si_label_root))
        for (si in listOf(siAdb, siPairing, siPort, siWifi, siRoot)) {
            si.setState(StatusIndicatorView.State.UNKNOWN)
            si.setValue(getString(R.string.si_value_loading))
        }

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.refreshStatusAndPairing()
        (activity as? MainActivity)?.doFullRefresh()
    }

    /** Called by MainActivity when new status data is available. */
    fun renderStatus(m: StatusFragment.StatusModel) {
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
            val modeText = when (m.adbMode) {
                "tls" -> getString(R.string.adb_mode_tls)
                "tcp" -> getString(R.string.adb_mode_tcp)
                else -> getString(R.string.adb_mode_unknown)
            }
            siAdb.setValue("${getString(R.string.si_value_enabled)} · $modeText")
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

        // Pairing indicator — at-a-glance state only, not actionable.
        // Tap-through lives in the Wireless tab.
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

        renderBootLog()
    }

    private fun renderBootLog() {
        val lines = BootLogger.readLines()
        if (lines.isEmpty()) {
            tvBootLog.text = getString(R.string.boot_log_empty)
            btnClearBootLog.visibility = View.GONE
        } else {
            tvBootLog.text = lines.joinToString("\n")
            btnClearBootLog.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        val act = activity as? MainActivity ?: return
        cardXposedStatus.setOnClickListener { act.showXposedHelpDialog() }

        btnClearBootLog.setOnClickListener {
            BootLogger.clear()
            renderBootLog()
        }
        btnToggleBootLog.setOnClickListener {
            cardBootLog.visibility = when (cardBootLog.visibility) {
                View.GONE -> View.VISIBLE
                else -> View.GONE
            }
        }

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
    }
}
