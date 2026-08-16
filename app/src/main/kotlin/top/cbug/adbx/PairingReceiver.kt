package top.cbug.adbx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Listens for WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION and writes the
 * transient pairing port + code into /data/local/tmp/ so the rest of the
 * app can pick them up. Triggered when one of the calls below fires:
 *   - IAdbManager.enablePairingByPairingCode()
 *   - The user manually opens the Developer options pair dialog.
 *
 * Writing the file requires root because the broadcast is delivered to
 * the app (uid 10597) but the system writes to /data/local/tmp/ with shell
 * rights; we wrap the writes in ShellUtils.executeSu().
 */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != "android.debug.WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION") return
            // The extras carry whatever the system sends on this ROM. Check
            // both documented names since the doc and the implementation
            // disagree on casing.
            val port = intent.getIntExtra("port", 0)
            val code = intent.getIntExtra("pairingCode", 0)
            val codeStr = intent.getStringExtra("pairingCode")
            val portStr = intent.getIntExtra("adb_pairing_port", 0)
            val resolvedPort = when {
                port > 0 -> port
                portStr > 0 -> portStr
                else -> 0
            }
            val resolvedCode = when {
                code > 0 -> code.toString()
                codeStr != null && codeStr.isNotEmpty() -> codeStr
                else -> ""
            }
            if (resolvedPort > 0 && resolvedPort.toString().length in 4..5) {
                File("/data/local/tmp/adb_x_pairing_port").writeText(resolvedPort.toString())
                Log.d("ADB_X_Rcvr", "wrote pairing port: " + resolvedPort)
            }
            if (resolvedCode.isNotBlank()) {
                File("/data/local/tmp/adb_x_pairing_code").writeText(resolvedCode)
                Log.d("ADB_X_Rcvr", "wrote pairing code: " + resolvedCode)
            }
        } catch (t: Throwable) {
            Log.w("ADB_X_Rcvr", "onReceive failed", t)
        }
    }
}
