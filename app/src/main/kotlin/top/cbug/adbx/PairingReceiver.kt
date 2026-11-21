package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.cbug.adbx.util.ShellUtils

/**
 * Listens for WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION and writes the
 * transient pairing port + code into /data/local/tmp/ so the rest of the
 * app can pick them up. Triggered when the user opens the system pairing
 * dialog from Developer options.
 *
 * /data/local/tmp is NOT writable by a normal third-party app — the old
 * code called File.writeText() directly (contradicting its own comment)
 * and silently failed on every real device. All writes now go through su
 * and are logged with the real outcome.
 */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != ACTION_DISCOVER) return
            // Extras vary across ROMs; check the documented and OEM names.
            val port = intent.getIntExtra("port", 0)
            val portAlt = intent.getIntExtra("adb_pairing_port", 0)
            val codeInt = intent.getIntExtra("pairingCode", 0)
            val codeStr = intent.getStringExtra("pairingCode")

            val resolvedPort = when {
                port in 1024..65535 -> port
                portAlt in 1024..65535 -> portAlt
                else -> 0
            }
            val resolvedCode = when {
                codeStr != null && codeStr.matches(Regex("\\d{6,8}")) -> codeStr
                codeInt in 100000..99999999 -> codeInt.toString()
                else -> ""
            }

            if (resolvedPort > 0) writeMarker("adb_x_pairing_port", resolvedPort.toString())
            if (resolvedCode.isNotBlank()) writeMarker("adb_x_pairing_code", resolvedCode)
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive failed", t)
        }
    }

    private fun writeMarker(name: String, value: String) {
        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "writeMarker $name skipped: no root")
            return
        }
        val r = ShellUtils.executeSu(
            "echo '$value' > /data/local/tmp/$name && chmod 644 /data/local/tmp/$name", 2000)
        Log.d(TAG, "writeMarker $name=$value success=${r.isSuccess()}")
    }

    companion object {
        private const val TAG = "ADB_X_Rcvr"
        const val ACTION_DISCOVER = "android.debug.WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION"
    }
}
