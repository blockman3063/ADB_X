package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.AdbKeyHelper

/**
 * Wired (USB) auto-toggle.
 *
 * WHAT CHANGED AND WHY
 * --------------------
 * The old code listed /sys/bus/usb/devices and trusted a device's
 * serial / VID:PID. That cannot identify the PC: when a phone is
 * plugged into a computer the phone is the USB *device*, so the
 * sysfs tree only ever describes gadgets the phone enumerates as a
 * *host* (OTG drives), never the PC. A USB device cannot read its
 * host's descriptors — full stop.
 *
 * We now trust ADB host keys instead. Android already keeps the set
 * of PCs the user approved in /data/misc/adb/adb_keys. We reuse that
 * as the source of truth and let the user mark which of those key
 * fingerprints they consider "trusted". Because the keys are the
 * live file, tapping "Revoke USB debugging authorizations" in system
 * Settings removes them and our trust lapses with it.
 *
 * HONEST LIMITATION: an ADB key identifies a key, not a physical
 * machine (see AdbKeyHelper). We also cannot know *which* PC is on
 * the other end while USB ADB is off — there is no handshake yet.
 * So "auto-enable" means: on a USB connect event, if at least one
 * trusted key is still authorized, turn USB ADB on and let the
 * normal ADB auth flow decide whether that specific PC is allowed.
 * The UI states this plainly.
 */
class UsbStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val connected = when (action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            Intent.ACTION_POWER_CONNECTED,
            ACTION_USB_CONNECTED,
            ACTION_INTERNAL_FIRE -> true
            ACTION_USB_STATE -> intent.getBooleanExtra("connected", false)
            else -> false
        }
        val disconnected = when (action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED,
            Intent.ACTION_POWER_DISCONNECTED,
            ACTION_USB_STATE,
            ACTION_USB_DISCONNECTED -> !connected
            else -> false
        }
        if (!connected && !disconnected) return

        val pending = goAsync()
        try {
            AppSettings.load(context)

            // Authorized hosts that are still trusted. This reads
            // adb_keys via root; empty on no-root means "no trusted
            // host", never "trust everyone".
            val trustedPresent = AppSettings.wiredAutoEnable &&
                AdbKeyHelper.hasTrustedAuthorizedHost(AppSettings.trustedUsbSet())

            Log.i(TAG, "USB event=$action connected=$connected " +
                "wiredEnable=${AppSettings.wiredAutoEnable} " +
                "trustedAuthorizedHost=$trustedPresent")

            if (connected && trustedPresent) {
                AdbHelper.enableUsbAdb()
            }

            // Auto-disable on disconnect when no trusted device remains.
            // With key-based trust we cannot see the PC, so a detach is
            // treated as "no host" - the user opted into this via the
            // auto-disable switch, which the UI labels as such.
            if (disconnected && !connected && AppSettings.wiredAutoDisable) {
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
        /** Hidden in public SDK; literal value of UsbManager.ACTION_USB_STATE. */
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val ACTION_INTERNAL_FIRE = "top.cbug.adbx.action.USB_EVAL"
        const val ACTION_USB_CONNECTED = "top.cbug.adbx.action.USB_CONNECTED"
        const val ACTION_USB_DISCONNECTED = "top.cbug.adbx.action.USB_DISCONNECTED"

        /** Re-evaluate USB state now (e.g. after the user toggles trust). */
        fun fireOnce(context: Context) {
            context.sendBroadcast(
                Intent(context, UsbStateReceiver::class.java)
                    .setAction(ACTION_INTERNAL_FIRE)
            )
        }

        /** Forward a real connect/disconnect event from the running service. */
        fun fireConnection(context: Context, connected: Boolean) {
            val action = if (connected) ACTION_USB_CONNECTED else ACTION_USB_DISCONNECTED
            context.sendBroadcast(
                Intent(context, UsbStateReceiver::class.java).setAction(action)
            )
        }
    }
}
