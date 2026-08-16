package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WiredUsbHelper

/**
 * Triggered by the system when a USB device is attached or detached.
 * We list currently-attached devices via sysfs, look up the user's
 * trusted set, and let AdbHelper enable or disable USB ADB depending
 * on whether a trusted device is present.
 *
 * Independence from wireless: this receiver never reads or modifies
 * the wireless-adb enable state. Toggling wired ADB has zero effect
 * on `adb_wifi_enabled` and vice versa. The two toggles share storage
 * but the actions live in separate code paths (this receiver vs.
 * WifiStateReceiver).
 *
 * The receiver runs off the main thread via goAsync() so a slow
 * sysfs read does not delay the broadcast dispatcher.
 */
class UsbStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            action != UsbManager.ACTION_USB_DEVICE_DETACHED &&
            action != ACTION_INTERNAL_FIRE) return

        val pending = goAsync()
        try {
            AppSettings.load(context)
            val devices = WiredUsbHelper.listDevices()
            val trustedAttached = devices.firstOrNull { AppSettings.isTrustedUsb(it.id) }
            val anyAttached = devices.isNotEmpty()
            Log.i(TAG, "USB state changed: action=" + action +
                " devices=" + devices.size +
                " trustedAttached=" + (trustedAttached != null))

            // Auto-enable on trusted attach.
            if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
                trustedAttached != null &&
                AppSettings.wiredAutoEnable) {
                AdbHelper.enableUsbAdb()
            }

            // Auto-disable on detach when no trusted device remains.
            if (action == UsbManager.ACTION_USB_DEVICE_DETACHED &&
                !anyAttached &&
                AppSettings.wiredAutoDisable) {
                AdbHelper.disableUsbAdb()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed", t)
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "ADB_X_UsbState"
        const val ACTION_INTERNAL_FIRE = "top.cbug.adbx.action.USB_EVAL"

        /**
         * Re-evaluate USB state now (used after the user toggles
         * trust on a device from the wired tab). Runs the same
         * goAsync path so we don't take the main thread for sysfs.
         */
        fun fireOnce(context: Context) {
            val intent = Intent(context, UsbStateReceiver::class.java)
                .setAction(ACTION_INTERNAL_FIRE)
            context.sendBroadcast(intent)
        }
    }
}
