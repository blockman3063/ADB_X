package top.cbug.adbx.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BootLogger {

    private const val TAG = "ADB_X_BootLogger"
    private const val PREFS = "adb_x_boot_log"
    private const val KEY_BOOT_LOG = "boot_log"
    private const val MAX_BOOT_LOG_LINES = 40

    @Volatile private var initialized = false
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialized = true
    }

    fun append(message: String) {
        if (!initialized) return
        try {
            val ts = System.currentTimeMillis()
            val line = "$ts|$message"
            val existing = prefs?.getString(KEY_BOOT_LOG, "") ?: ""
            val updated = if (existing.isBlank()) line else existing + "\n" + line
            val trimmed = trimLines(updated)
            prefs?.edit()?.putString(KEY_BOOT_LOG, trimmed)?.apply()
            Log.d(TAG, "boot log appended: $message")
        } catch (t: Throwable) {
            Log.w(TAG, "boot log append failed: ${t.message}")
        }
    }

    fun readLines(): List<String> {
        if (!initialized) return emptyList()
        val raw = prefs?.getString(KEY_BOOT_LOG, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return raw.split('\n').filter { it.isNotBlank() }.takeLast(MAX_BOOT_LOG_LINES).map { line ->
            val idx = line.indexOf('|')
            if (idx > 0) {
                val ts = line.substring(0, idx).toLongOrNull() ?: 0L
                val msg = line.substring(idx + 1)
                if (ts > 0L) sdf.format(Date(ts)) + " " + msg else msg
            } else line
        }
    }

    fun clear() {
        if (!initialized) return
        prefs?.edit()?.putString(KEY_BOOT_LOG, "")?.apply()
    }

    private fun trimLines(input: String): String {
        val lines = input.split('\n')
        return if (lines.size > MAX_BOOT_LOG_LINES) lines.takeLast(MAX_BOOT_LOG_LINES).joinToString("\n") else input
    }
}
