package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Log
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.BootLogger
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

        BootLogger.init(context)
        val pending = goAsync()
        try {
            try {
                AppSettings.load(context)
            } catch (_: Throwable) { }

            if (!AppSettings.autoEnable) {
                Log.d(TAG, "auto-enable not armed, skip")
                BootLogger.append("auto-enable not armed")
                return
            }

            val ssid = WifiHelper.getCurrentSsid(context)
            Log.d(TAG, "Wi-Fi state changed: ssid='$ssid' action=$action")
            BootLogger.append("wifi action=$action ssid=$ssid")
            if (ssid.isBlank()) {
                Log.d(TAG, "empty SSID, skip")
                BootLogger.append("empty ssid skip")
                return
            }

            val trusted = AppSettings.isTrusted(ssid)
            if (trusted) {
                // Skip the write when wireless ADB is already on: each
                // enableWirelessAdb() costs four su invocations, and this
                // receiver fires on every Wi-Fi state change.
                val alreadyOn = try {
                    android.provider.Settings.Global.getInt(
                        context.contentResolver, "adb_wifi_enabled", 0
                    ) == 1
                } catch (_: Throwable) { false }
                if (alreadyOn) {
                    Log.d(TAG, "trusted SSID $ssid but ADB already on, skipping write")
                    BootLogger.append("trusted ssid=$ssid already-on skip")
                } else {
                    Log.i(TAG, "trusted SSID $ssid, enabling wireless ADB")
                    BootLogger.append("trusted ssid=$ssid enable")
                    AdbHelper.enableWirelessAdb()
                    recordLastTrigger(context, ssid)
                }
            } else {
                Log.d(TAG, "non-trusted SSID $ssid, leaving wireless ADB unchanged (Android handles disconnect)")
                BootLogger.append("non-trusted ssid=$ssid skip")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluate failed", t)
            BootLogger.append("evaluate failed ${t.message ?: "unknown"}")
        } finally {
            try { pending.finish() } catch (_: Throwable) { }
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