package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Log
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WifiHelper

/**
 * Triggered by the system whenever Wi-Fi state changes (join / leave /
 * state-change). Uses [goAsync] so we get up to 10 s of background
 * execution time on Android 14+ without needing a foreground service.
 *
 * The receiver does NOT keep any state between events — it just reads
 * the current SSID, looks up Settings.trustedSsids, and toggles
 * wireless ADB if appropriate. No polling, no NetworkCallback, no
 * notification, no sticky service.
 *
 * What this replaces:
 *   - TrustedWifiService + TrustedWifiWatcher singleton (deleted).
 *     That path needed FOREGROUND_SERVICE_DATA_SYNC permission and
 *     still would not fire reliably while the lockscreen was up —
 *     which is exactly when the user first joins a Wi-Fi after
 *     booting the phone.
 *   - WorkManager / JobScheduler paths. We don't need them. The OS
 *     already delivers NETWORK_STATE_CHANGED to every registered
 *     receiver; we are not adding a new scheduling primitive, we
 *     are reacting to an existing one.
 *
 * The receiver also writes a tiny SharedPreferences marker
 * "adb_x_last_trigger_ssid" / "adb_x_last_trigger_ms" so the Status
 * tab can show the most recent action without needing the watcher
 * singleton.
 *
 * One-time wake on app open: MainActivity.onResume calls
 * [WifiStateReceiver.fireOnce] so the cold-start case is handled
 * even before the system broadcasts again.
 */
class WifiStateReceiver : BroadcastReceiver() {



    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != WifiManager.NETWORK_STATE_CHANGED_ACTION &&
            action != ACTION_INTERNAL_FIRE) return

        val pending = goAsync()
        try {
            try {
                AppSettings.load(context)
            } catch (_: Throwable) { }

            if (!AppSettings.autoEnable && !AppSettings.autoDisable) {
                Log.d(TAG, "no auto-toggle rules armed, skip")
                pending.finish()
                return
            }

            val ssid = WifiHelper.getCurrentSsid(context)
            Log.d(TAG, "Wi-Fi state changed: ssid='" + ssid + "' action=" + action)
            if (ssid.isBlank()) {
                Log.d(TAG, "empty SSID, skip")
                pending.finish()
                return
            }

            val trusted = AppSettings.isTrusted(ssid)
            val acted = when {
                trusted && AppSettings.autoEnable -> {
                    Log.i(TAG, "trusted SSID " + ssid + ", enabling wireless ADB")
                    AdbHelper.enableWirelessAdb()
                    true
                }
                !trusted && AppSettings.autoDisable -> {
                    Log.i(TAG, "non-trusted SSID " + ssid + ", disabling wireless ADB")
                    AdbHelper.disableWirelessAdb()
                    true
                }
                else -> {
                    Log.d(TAG, "no action (trusted=" + trusted + " autoEnable=" + AppSettings.autoEnable + " autoDisable=" + AppSettings.autoDisable + ")")
                    false
                }
            }
            if (acted) {
                recordLastTrigger(context, ssid)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed", t)
        } finally {
            pending.finish()
        }
    }

    private fun recordLastTrigger(context: Context, ssid: String) {
        try {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SSID, ssid)
                .putLong(KEY_MS, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { }
    }

    companion object {
        private const val TAG = "ADB_X_WifiState"
        const val PREFS = "adb_x_trigger"
        const val KEY_SSID = "last_trigger_ssid"
        const val KEY_MS = "last_trigger_ms"

        /** Public entry — call from MainActivity.onResume to cover the
         *  cold-start case. */
        fun fireOnce(context: Context) {
            // Make sure the foreground daemon is running so its
            // NetworkCallback is the one watching WiFi state from
            // here on. Without this the receiver path only runs while
            // an app process is alive, which is too short for the
            // user experience of "open app once, then it just works."
            try {
                top.cbug.adbx.TrustedWifiService.start(context)
            } catch (_: Throwable) { }
            val intent = Intent(context, WifiStateReceiver::class.java)
                .setAction(ACTION_INTERNAL_FIRE)
            context.sendBroadcast(intent)
        }

        const val ACTION_INTERNAL_FIRE = "top.cbug.adbx.action.WIFI_EVAL"

        /** Public so [BootReceiver] can mark the same SharedPreferences
         *  key without needing to receive a second broadcast. The
         *  Status tab reads this so it can show "last triggered 5 min
         *  ago" regardless of which path fired. */
        @JvmStatic
        fun recordLastTriggerFromBoot(context: Context, ssid: String) {
            try {
                val prefs: SharedPreferences =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_SSID, ssid)
                    .putLong(KEY_MS, System.currentTimeMillis())
                    .apply()
            } catch (_: Throwable) { }
        }
    }
}