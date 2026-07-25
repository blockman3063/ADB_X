package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.cbug.adbx.store.Settings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WifiHelper

/**
 * Boot receiver — runs the trusted-WiFi evaluate logic inline on
 * boot. We cannot rely on [WifiStateReceiver.fireOnce] from here
 * because that requires the app process to be alive, and Android 14+
 * will not spawn a background process for a broadcast that itself
 * was delivered to a background process.
 *
 * The receiver:
 *   1. honours [Settings.bootStart]
 *   2. reads the current SSID via [WifiHelper.getCurrentSsid]
 *   3. applies the same trusted-set logic as [WifiStateReceiver]
 *
 * The inline logic here is intentionally a copy — duplication is
 * the lesser evil here vs. trying to share code via a context that
 * doesn't exist yet. Both paths funnel into the same
 * [AdbHelper.enable/disable] calls.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ADB_X_BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            Settings.load(context)
        } catch (_: Exception) { }

        if (!Settings.bootStart) {
            Log.d(TAG, "Boot completed — bootStart disabled, skipping")
            return
        }

        if (!Settings.autoEnable) {
            Log.d(TAG, "Boot completed — auto-enable disabled, skipping")
            return
        }

        val ssid = WifiHelper.getCurrentSsid(context)
        Log.d(TAG, "Boot completed — current SSID='" + ssid + "'")
        if (ssid.isBlank()) {
            Log.d(TAG, "Boot completed — empty SSID, skipping")
            return
        }

        val trusted = Settings.isTrusted(ssid)
        if (trusted) {
            Log.i(TAG, "Boot completed — trusted SSID " + ssid + ", enabling wireless ADB")
            AdbHelper.enableWirelessAdb()
            WifiStateReceiver.recordLastTriggerFromBoot(context, ssid)
        } else {
            Log.d(TAG, "Boot completed — non-trusted SSID " + ssid + ", leaving wireless ADB unchanged (Android handles disconnect)")
        }
        // Always start the persistent daemon so subsequent WiFi events
        // (join / leave / BSSID change) are handled even when the user
        // is not in the app. The service is idempotent — calling start
        // on an already-running service is a no-op.
        TrustedWifiService.start(context)
    }
}