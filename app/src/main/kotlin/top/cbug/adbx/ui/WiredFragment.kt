package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.R
import top.cbug.adbx.UsbStateReceiver
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.AdbHostKey
import top.cbug.adbx.util.AdbKeyHelper

/**
 * Wired (USB) tab — independent auto-toggle surface.
 *
 * The trust list is the set of ADB host keys this device has already
 * authorized (from /data/misc/adb/adb_keys), shown by their AOSP-style
 * fingerprint. Tapping a row marks that key trusted. Nothing here
 * pretends to identify a physical computer — see [AdbKeyHelper] and
 * the wired_help string, which say so in plain language.
 *
 * Auto-toggle is a best-effort convenience: on a USB connect event,
 * if any trusted key is still authorized, USB ADB is enabled and the
 * normal ADB RSA auth decides whether the connected PC is allowed.
 */
class WiredFragment : Fragment() {

    private lateinit var swWiredAutoEnable: MaterialSwitch
    private lateinit var swWiredAutoDisable: MaterialSwitch
    private lateinit var tvWiredStatus: TextView
    private lateinit var tvUsbAdbState: TextView
    private lateinit var btnToggleUsbAdb: MaterialButton
    private lateinit var llDeviceList: LinearLayout
    private lateinit var tvNoDevices: TextView
    private lateinit var btnTrustFirst: MaterialButton

    private var lastHosts: List<AdbHostKey> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_wired, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swWiredAutoEnable = view.findViewById(R.id.swWiredAutoEnable)
        swWiredAutoDisable = view.findViewById(R.id.swWiredAutoDisable)
        tvWiredStatus = view.findViewById(R.id.tvWiredStatus)
        tvUsbAdbState = view.findViewById(R.id.tvUsbAdbState)
        btnToggleUsbAdb = view.findViewById(R.id.btnToggleUsbAdb)
        llDeviceList = view.findViewById(R.id.llDeviceList)
        tvNoDevices = view.findViewById(R.id.tvNoDevices)
        btnTrustFirst = view.findViewById(R.id.btnWiredApplyNow)

        AppSettings.load(requireContext())
        renderSwitches()
        renderAdbState()

        swWiredAutoEnable.setOnCheckedChangeListener { _, checked ->
            AppSettings.wiredAutoEnable = checked
            AppSettings.save(requireContext())
            renderStatus()
        }
        swWiredAutoDisable.setOnCheckedChangeListener { _, checked ->
            AppSettings.wiredAutoDisable = checked
            AppSettings.save(requireContext())
            renderStatus()
        }
        btnTrustFirst.setOnClickListener {
            val hosts = AdbKeyHelper.listAuthorizedHosts()
            val anyTrustedBefore = hosts.any { AppSettings.isTrustedUsb(it.fingerprint) }
            for (h in hosts) {
                if (anyTrustedBefore) AppSettings.removeTrustedUsb(h.fingerprint)
                else AppSettings.addTrustedUsb(h.fingerprint)
            }
            AppSettings.save(requireContext())
            renderHosts(AdbKeyHelper.listAuthorizedHosts())
            renderStatus()
            UsbStateReceiver.fireOnce(requireContext())
        }
        btnToggleUsbAdb.setOnClickListener {
            val enabledNow = AdbHelper.isUsbAdbEnabled()
            val ok = if (enabledNow) AdbHelper.disableUsbAdb() else AdbHelper.enableUsbAdb()
            if (!ok) {
                Toast.makeText(
                    requireContext(),
                    R.string.wired_toggle_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
            AppSettings.save(requireContext())
            renderAdbState()
        }
    }

    override fun onResume() {
        super.onResume()
        AppSettings.load(requireContext())
        renderSwitches()
        renderHosts(AdbKeyHelper.listAuthorizedHosts())
        renderStatus()
        renderAdbState()
    }

    private fun renderSwitches() {
        swWiredAutoEnable.isChecked = AppSettings.wiredAutoEnable
        swWiredAutoDisable.isChecked = AppSettings.wiredAutoDisable
    }

    private fun renderHosts(hosts: List<AdbHostKey>) {
        lastHosts = hosts
        llDeviceList.removeAllViews()
        if (hosts.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            btnTrustFirst.isEnabled = false
            btnTrustFirst.text = getString(R.string.wired_action_trust)
            renderStatus()
            return
        }
        tvNoDevices.visibility = View.GONE
        btnTrustFirst.isEnabled = true
        val anyTrusted = hosts.any { AppSettings.isTrustedUsb(it.fingerprint) }
        btnTrustFirst.text = if (anyTrusted) {
            getString(R.string.wired_action_untrust)
        } else {
            getString(R.string.wired_action_trust)
        }
        val inflater = LayoutInflater.from(requireContext())
        for (h in hosts) {
            val row = inflater.inflate(R.layout.item_usb_wired, llDeviceList, false) as ViewGroup
            val fpView = row.findViewById<TextView>(R.id.tvUsbId)
            val labelView = row.findViewById<TextView>(R.id.tvUsbLabel)
            val trustedChip = row.findViewById<TextView>(R.id.tvTrusted)
            fpView.text = h.fingerprint
            labelView.text = if (h.comment.isNotBlank()) h.comment
                             else getString(R.string.wired_key_no_comment)
            trustedChip.visibility = if (AppSettings.isTrustedUsb(h.fingerprint)) View.VISIBLE else View.GONE
            row.setOnClickListener {
                if (AppSettings.isTrustedUsb(h.fingerprint)) {
                    AppSettings.removeTrustedUsb(h.fingerprint)
                } else {
                    AppSettings.addTrustedUsb(h.fingerprint)
                }
                AppSettings.save(requireContext())
                renderHosts(AdbKeyHelper.listAuthorizedHosts())
                renderStatus()
                UsbStateReceiver.fireOnce(requireContext())
            }
            llDeviceList.addView(row)
        }
    }

    private fun renderStatus() {
        val armed = AppSettings.wiredAutoEnable || AppSettings.wiredAutoDisable
        if (!armed) {
            tvWiredStatus.text = getString(R.string.wired_status_disabled)
            return
        }
        when {
            lastHosts.isEmpty() ->
                tvWiredStatus.text = getString(R.string.wired_status_no_keys)
            lastHosts.any { AppSettings.isTrustedUsb(it.fingerprint) } ->
                tvWiredStatus.text = getString(R.string.wired_status_key_trusted)
            else ->
                tvWiredStatus.text = getString(R.string.wired_status_keys_untrusted, lastHosts.size)
        }
    }

    private fun renderAdbState() {
        val enabled = AdbHelper.isUsbAdbEnabled()
        tvUsbAdbState.text = if (enabled) {
            getString(R.string.wired_usb_state_on)
        } else {
            getString(R.string.wired_usb_state_off)
        }
        btnToggleUsbAdb.text = if (enabled) {
            getString(R.string.wired_action_toggle_off)
        } else {
            getString(R.string.wired_action_toggle_on)
        }
    }
}
