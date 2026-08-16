package top.cbug.adbx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.R
import top.cbug.adbx.UsbStateReceiver
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.UsbDevice
import top.cbug.adbx.util.WiredUsbHelper

/**
 * Wired (USB) tab — independent auto-toggle surface. We do not
 * touch wireless ADB state here; that lives in NetworkFragment /
 * WifiStateReceiver and shares zero code paths with this tab.
 *
 * The user-facing flow is:
 *  1. Plug in a USB device (root reads /sys/bus/usb/devices
 *     entries with idVendor, idProduct, serial fields).
 *  2. Tap "Trust this device" — the serial goes into the
 *     Settings.trustedUsbSerials set, the toggles below
 *     take effect immediately.
 *  3. From then on, attaching that device and unmounting it
 *     lets the wired auto-toggle enable USB ADB on attach
 *     and (optionally) disable it on detach.
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

    private var lastDevices: List<UsbDevice> = emptyList()

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
            val devices = WiredUsbHelper.listDevices()
            // Toggle trust across all currently-detected devices —
            // matches what users expect in the typical one-cable
            // workflow. Caller can see the device list right below
            // and untust individually via [UsbStateReceiver.fireOnce].
            val anyTrustedBefore = devices.any { AppSettings.isTrustedUsb(it.id) }
            for (d in devices) {
                if (anyTrustedBefore) AppSettings.removeTrustedUsb(d.id)
                else AppSettings.addTrustedUsb(d.id)
            }
            AppSettings.save(requireContext())
            renderDevices(devices)
            renderStatus()
            UsbStateReceiver.fireOnce(requireContext())
        }
        btnToggleUsbAdb.setOnClickListener {
            val enabledNow = AdbHelper.isUsbAdbEnabled()
            val ok = if (enabledNow) AdbHelper.disableUsbAdb() else AdbHelper.enableUsbAdb()
            if (!ok) {
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.wired_toggle_failed,
                    android.widget.Toast.LENGTH_LONG,
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
        renderDevices(WiredUsbHelper.listDevices())
        renderStatus()
        renderAdbState()
    }

    private fun renderSwitches() {
        swWiredAutoEnable.isChecked = AppSettings.wiredAutoEnable
        swWiredAutoDisable.isChecked = AppSettings.wiredAutoDisable
    }

    private fun renderDevices(devices: List<UsbDevice>) {
        lastDevices = devices
        llDeviceList.removeAllViews()
        if (devices.isEmpty()) {
            tvNoDevices.visibility = View.VISIBLE
            btnTrustFirst.isEnabled = false
            btnTrustFirst.text = getString(R.string.wired_action_trust)
            return
        }
        tvNoDevices.visibility = View.GONE
        btnTrustFirst.isEnabled = true
        val anyTrusted = devices.any { AppSettings.isTrustedUsb(it.id) }
        btnTrustFirst.text = if (anyTrusted) {
            getString(R.string.wired_action_untrust)
        } else {
            getString(R.string.wired_action_trust)
        }
        val inflater = LayoutInflater.from(requireContext())
        for (d in devices) {
            val row = inflater.inflate(R.layout.item_usb_wired, llDeviceList, false) as ViewGroup
            val id = row.findViewById<TextView>(R.id.tvUsbId)
            val label = row.findViewById<TextView>(R.id.tvUsbLabel)
            val trustedChip = row.findViewById<TextView>(R.id.tvTrusted)
            id.text = d.id
            label.text = getString(R.string.wired_device_label, d.vendor, d.product)
            trustedChip.visibility = if (AppSettings.isTrustedUsb(d.id)) View.VISIBLE else View.GONE
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
            lastDevices.isEmpty() ->
                tvWiredStatus.text = getString(R.string.wired_status_disabled)
            lastDevices.any { AppSettings.isTrustedUsb(it.id) } ->
                tvWiredStatus.text = getString(
                    R.string.wired_status_device_trusted,
                    lastDevices.first { AppSettings.isTrustedUsb(it.id) }.id
                )
            else ->
                tvWiredStatus.text = getString(
                    R.string.wired_status_devices_untrusted,
                    lastDevices.size
                )
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
