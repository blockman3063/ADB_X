package top.cbug.adbx.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.cbug.adbx.store.Settings as AppSettings

object AdbHelper {

    // Base64 of the two shell scripts that live in the kernelSU
    // module. Held as constants so we never have to reason about
    // $-characters inside Kotlin triple-quoted strings.
    private const val SERVICE_SH_B64 = "IyEvc3lzdGVtL2Jpbi9zaApNT0RJRD1hZGJ4CmlmIFsgISAtZCAiL2RhdGEvYWRiL21vZHVsZXMvJE1PRElEIiBdOyB0aGVuCiAgZXhpdCAwCmZpCmk9MAp3aGlsZSBbICIkKGdldHByb3Agc3lzLmJvb3RfY29tcGxldGVkKSIgIT0gIjEiIF0gJiYgWyAkaSAtbHQgNjAgXTsgZG8KICBzbGVlcCAxCiAgaT0kKChpKzEpKQpkb25lCmV4ZWMgL2RhdGEvYWRiL21vZHVsZXMvJE1PRElEL2Jpbi9hZGJ4LWJvb3QtZXZhbAo="
    private const val BOOT_EVAL_B64 = "IyEvc3lzdGVtL2Jpbi9zaApUUlVTVF9GSUxFPS9kYXRhL2RhdGEvdG9wLmNidWcuYWRieC9zaGFyZWRfcHJlZnMvYWRiX3hfc2V0dGluZ3MueG1sClNFVFRJTkdTPS9kYXRhL2FkYi9tb2R1bGVzL2FkYngvc2V0dGluZ3MueG1sCm1rZGlyIC1wIC9kYXRhL2FkYi9tb2R1bGVzL2FkYngKY3AgIiRUUlVTVF9GSUxFIiAiJFNFVFRJTkdTIiAyPi9kZXYvbnVsbApjaG1vZCA2NDQgIiRTRVRUSU5HUyIgMj4vZGV2L251bGwKaWYgWyAhIC1mICIkU0VUVElOR1MiIF07IHRoZW4KICBleGl0IDAKZmkKQk9PVF9TVEFSVD0kKGdyZXAgLW9FICduYW1lPSJib290X3N0YXJ0IiB2YWx1ZT0iW14iXSsiJyAiJFNFVFRJTkdTIiB8IGdyZXAgLW9FICd0cnVlfGZhbHNlJyB8IGhlYWQgLTEpCkFVVE9fRU5BQkxFPSQoZ3JlcCAtb0UgJ25hbWU9ImF1dG9fZW5hYmxlIiB2YWx1ZT0iW14iXSsiJyAiJFNFVFRJTkdTIiB8IGdyZXAgLW9FICd0cnVlfGZhbHNlJyB8IGhlYWQgLTEpCmlmIFsgIiRCT09UX1NUQVJUIiAhPSAidHJ1ZSIgXSB8fCBbICIkQVVUT19FTkFCTEUiICE9ICJ0cnVlIiBdOyB0aGVuCiAgZXhpdCAwCmZpClNTSUQ9JChkdW1wc3lzIHdpZmkgMj4mMSB8IGdyZXAgLW9FICdTU0lEPSJbXiJdKyInIHwgaGVhZCAtMSB8IHNlZCAncy9TU0lEPSJcKC4qXCkiL1wxLycpCmlmIFsgLXogIiRTU0lEIiBdIHx8IFsgIiRTU0lEIiA9ICI8dW5rbm93biBzc2lkPiIgXTsgdGhlbgogIGV4aXQgMApmaQpUUlVTVEVEPSQoZ3JlcCAtYyAiJFNTSUQiICIkU0VUVElOR1MiIHx8IGVjaG8gMCkKaWYgWyAiJFRSVVNURUQiIC1ndCAiMCIgXTsgdGhlbgogIHNldHRpbmdzIHB1dCBnbG9iYWwgYWRiX3dpZmlfZW5hYmxlZCAxCmZpCmV4aXQgMAo="

    private const val TAG = "ADB_X_AdbHelper"

    /** Marker-file TTL. Ephemeral pairing ports expire after ~120 s in
     * Android; we leave a 5× safety window so the stale marker never
     * keeps the "配对进行中" card showing after the dialog has closed. */
    private const val PORT_MARKER_TTL_MS = 5 * 60 * 1000L

    /** One-time system pairing code (written by the Settings hook) expires
     *  with the dialog, ~120 s. Keep a small safety margin. */
    private const val SESSION_CODE_TTL_MS = 3 * 60 * 1000L

    /** Use settings global as primary check (works on Rust adbd too) */
    fun isAdbWifiEnabled(context: Context): Boolean {
        return getCurrentState(context)
    }

    /** Get current ADB port - prefers non-root method first */
    fun getCurrentPort(): String {
        val nr = getCurrentPortNonRoot()
        if (nr.isNotEmpty()) return nr
        if (!ShellUtils.hasRoot()) return ""
        for (prop in arrayOf(
            "service.adb.tls.port",
            "service.adb.tcp.port"
        )) {
            val r = ShellUtils.executeSu("getprop " + prop, 500)
            if (r.isSuccess()) {
                val out = r.output.trim()
                if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                    return out.filter { it.isDigit() }.take(5)
                }
            }
        }
        return ""
    }

    /** Non-root: read service props (all processes can read) */
    fun getCurrentPortNonRoot(): String {
        // Settings.Global.ADB_WIFI_ENABLED is the authoritative on/off switch
        // for wireless ADB on Android 13+. If it's off, the port shown by
        // getprop is stale and we should refuse to call the device "active".
        // Hardcode the string instead of the API 33 constant.
        // Try getInt first, but fall back to getString — Android stores the
        // value as either depending on how it was written, and adb shell
        // settings uses getString. Without this fallback the two can
        // disagree.
        val wEnabled = try {
            android.provider.Settings.Global.getInt(
                top.cbug.adbx.App.appContext.contentResolver,
                "adb_wifi_enabled", 0
            )
        } catch (_: Throwable) { -1 }
        val effective = if (wEnabled != 0) wEnabled else {
            try {
                when (android.provider.Settings.Global.getString(
                    top.cbug.adbx.App.appContext.contentResolver,
                    "adb_wifi_enabled"
                )) {
                    "1" -> 1
                    "0" -> 0
                    else -> wEnabled
                }
            } catch (_: Throwable) { wEnabled }
        }
        if (effective == 0) {
            Log.d(TAG, "getCurrentPortNonRoot: adb_wifi_enabled=0, skipping setprop port")
            return ""
        }
        for (prop in arrayOf(
            "service.adb.tls.port",
            "service.adb.tcp.port"
        )) {
            val out = ShellUtils.execute("getprop " + prop, 500).output.trim()
            if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }) {
                return out.filter { it.isDigit() }.take(5)
            }
        }
        return ""
    }

    /** Best-effort wireless ADB state detection.
     *  Primary: check getprop (fast, non-root), then settings global, then dumpsys */
    fun getCurrentState(context: Context): Boolean {
        // The Settings.Global.ADB_WIFI_ENABLED flag is the authoritative
        // on/off switch for wireless ADB on Android 13+. If it's off, we
        // answer "off" immediately and don't trust any stale getprop port.
        // Mirror the same getString-fallback as getCurrentPortNonRoot so
        // adb shell settings and Settings.Global agree.
        val wEnabled = try {
            android.provider.Settings.Global.getInt(
                top.cbug.adbx.App.appContext.contentResolver,
                "adb_wifi_enabled", 0
            )
        } catch (_: Throwable) { -1 }
        val effective = if (wEnabled != 0) wEnabled else {
            try {
                when (android.provider.Settings.Global.getString(
                    top.cbug.adbx.App.appContext.contentResolver,
                    "adb_wifi_enabled"
                )) {
                    "1" -> 1
                    "0" -> 0
                    else -> wEnabled
                }
            } catch (_: Throwable) { wEnabled }
        }
        if (effective == 0) {
            Log.d(TAG, "getCurrentState: adb_wifi_enabled=0, refusing stale port fallback")
            return false
        }
        // 1. Fastest: check service props first
        val port = getCurrentPortNonRoot()
        if (port.isNotEmpty()) {
            Log.d(TAG, "Active ADB port via non-root getprop: " + port)
            return true
        }

        // 2. Check settings global via root (authoritative on API 36)
        if (ShellUtils.hasRoot()) {
            val r = ShellUtils.executeSu("settings get global adb_wifi_enabled 2>/dev/null", 1000)
            if (r.isSuccess()) {
                val v = r.output.trim()
                if (v == "1") {
                    Log.d(TAG, "adb_wifi_enabled=1 via root")
                    return true
                }
                if (v == "0") {
                    Log.d(TAG, "adb_wifi_enabled=0 via root")
                    return false
                }
            }
            // Also check getprop via root
            for (prop in arrayOf("service.adb.tls.port", "service.adb.tcp.port")) {
                val rp = ShellUtils.executeSu("getprop " + prop, 500)
                if (rp.isSuccess()) {
                    val out = rp.output.trim()
                    if (out.isNotEmpty() && out != "0") {
                        Log.d(TAG, "Active port via su getprop: " + out)
                        return true
                    }
                }
            }
        }

        // 3. Try non-root via content resolver
        try {
            val v = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
            if (v == 1) {
                Log.d(TAG, "adb_wifi_enabled=1 via ContentResolver")
                return true
            }
        } catch (_: Exception) { }

        // 4. Last resort: dumpsys adb (slow)
        if (ShellUtils.hasRoot()) {
            val d = ShellUtils.executeSu("dumpsys adb 2>/dev/null", 2000)
            if (d.isSuccess()) {
                val output = d.output.lowercase()
                if (output.contains("adb wifi enabled") || output.contains("wireless debugging enabled")) {
                    Log.d(TAG, "dumpsys adb reports wireless enabled")
                    return true
                }
            }
        }
        return false
    }

    fun isTcpModePreferred(): Boolean {
        return try {
            AppSettings.load(top.cbug.adbx.App.appContext)
            AppSettings.useTcpMode
        } catch (_: Throwable) { false }
    }

    /**
     * Enable wireless ADB. Returns the ACTUAL outcome: this was a
     * silent no-op with a hardcoded `return true` before, which made
     * the UI show success on devices where su was unavailable.
     * Verification reads back Settings.Global — the authoritative
     * switch on Android 13+ — not just the su exit code.
     */
    fun enableWirelessAdb(): Boolean {
        Log.d(TAG, "enableWirelessAdb")
        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "enableWirelessAdb: no root, cannot write settings")
            return false
        }
        // On modern Android (14+, Rust adbd) the authoritative way is
        // settings put global. NEVER restart adbd — that kills the
        // wireless connection we're running over.
        val r = ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1500)
        if (!r.isSuccess()) {
            Log.w(TAG, "enableWirelessAdb: settings put failed: ${r.output.take(120)}")
            return false
        }
        if (isTcpModePreferred()) {
            ShellUtils.executeSu("setprop service.adb.tls.port 0", 500)
            ShellUtils.executeSu("setprop service.adb.tcp.port 5555", 500)
        } else {
            ShellUtils.executeSu("setprop service.adb.tls.port 5555", 500)
            ShellUtils.executeSu("setprop service.adb.tcp.port 5555", 500)
        }
        // Read back the authoritative switch.
        val on = try {
            android.provider.Settings.Global.getInt(
                top.cbug.adbx.App.appContext.contentResolver, "adb_wifi_enabled", 0
            ) == 1
        } catch (_: Throwable) { false }
        Log.d(TAG, "enableWirelessAdb: verified on=$on")
        return on
    }

    fun disableWirelessAdb(): Boolean {
        Log.d(TAG, "disableWirelessAdb")
        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "disableWirelessAdb: no root")
            return false
        }
        val r = ShellUtils.executeSu("settings put global adb_wifi_enabled 0", 1500)
        if (!r.isSuccess()) {
            Log.w(TAG, "disableWirelessAdb: settings put failed: ${r.output.take(120)}")
            return false
        }
        ShellUtils.executeSu("setprop service.adb.tls.port 0", 500)
        ShellUtils.executeSu("setprop service.adb.tcp.port 0", 500)
        val off = try {
            android.provider.Settings.Global.getInt(
                top.cbug.adbx.App.appContext.contentResolver, "adb_wifi_enabled", 1
            ) == 0
        } catch (_: Throwable) { false }
        Log.d(TAG, "disableWirelessAdb: verified off=$off")
        return off
    }

    fun setFixedPort(port: Int): Boolean {
        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "setFixedPort: no root")
            return false
        }
        ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1000)
        ShellUtils.executeSu("setprop service.adb.tcp.port " + port, 500)
        val r = ShellUtils.executeSu("setprop service.adb.tls.port " + port, 2000)
        // A shell rc=0 only means `setprop` ran, not that the value stuck.
        // Read both props back so the UI never claims success on a no-op.
        val tcp = ShellUtils.executeSu("getprop service.adb.tcp.port", 500).output.trim()
        val tls = ShellUtils.executeSu("getprop service.adb.tls.port", 500).output.trim()
        val want = port.toString()
        val ok = r.isSuccess() && tcp == want && tls == want
        Log.d(TAG, "setFixedPort($port): tcp=$tcp tls=$tls verified=$ok")
        return ok
    }

    fun setPairingCode(code: String): Boolean {
        if (code.length !in 6..8 || !code.all { it.isDigit() }) return false
        if (!writePairingCodeFile(code)) return false
        // Verify read-back instead of claiming success blindly.
        val stored = readPairingCode()
        Log.d(TAG, "setPairingCode: stored=$stored (requested=$code)")
        return stored == code
    }

    private fun writePairingCodeFile(code: String): Boolean {
        if (!ShellUtils.hasRoot()) {
            Log.w(TAG, "writePairingCodeFile: no root")
            return false
        }
        // Remove old file first to avoid readPairingCode() picking up a stale value
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 500)
        val r = ShellUtils.executeSu(
            "echo '" + code + "' > /data/local/tmp/adb_x_pairing_code && chmod 644 /data/local/tmp/adb_x_pairing_code",
            2000)
        return r.isSuccess()
    }

    /**
     * Read the one-time pairing code captured by the Settings hook from the
     * live system pairing dialog. Honors a TTL so a code from a closed
     * session never lingers. Returns "" when there is no fresh code.
     */
    fun readSessionPairingCode(): String {
        // Primary channel: Settings.Global. The hook runs inside
        // com.android.settings, which cannot write /data/local/tmp (SELinux)
        // nor exec su, but CAN write Settings.Global via reflection — the
        // same channel our WiFi-list dump uses successfully on this ROM.
        try {
            val ctx = top.cbug.adbx.App.appContext
            val cr = ctx.contentResolver
            val code = Settings.Global.getString(cr, "adb_x_hook_code") ?: ""
            val ts = Settings.Global.getString(cr, "adb_x_hook_code_ts")?.toLongOrNull() ?: 0L
            val age = System.currentTimeMillis() - ts
            if (code.matches(Regex("\\d{6,8}")) && age in 0..SESSION_CODE_TTL_MS) {
                Log.d(TAG, "session pairing code via Settings.Global: $code (age ${age}ms)")
                return code
            }
        } catch (_: Throwable) { }
        // Legacy/fallback channel: marker file written by an older hook.
        val readText = {
            val f = File("/data/local/tmp/adb_x_hook_code")
            if (!f.exists()) "" else {
                val age = System.currentTimeMillis() - f.lastModified()
                if (age > SESSION_CODE_TTL_MS || age < 0) "" else {
                    val t = f.readText().trim()
                    if (t.matches(Regex("\\d{6,8}"))) t else ""
                }
            }
        }
        try {
            val direct = readText()
            if (direct.isNotEmpty()) return direct
        } catch (_: Throwable) { }
        return ""
    }

    fun readPairingCode(): String {
        // Session code (fresh, from the hook) takes priority over the
        // long-lived custom code and over any dumpsys guess.
        val session = readSessionPairingCode()
        if (session.isNotEmpty()) return session
        // 1. Direct file read
        try {
            val file = File("/data/local/tmp/adb_x_pairing_code")
            if (file.exists()) {
                val text = file.readText().trim()
                if (text.matches(Regex("\\d{6,8}"))) return text
            }
        } catch (_: Exception) { }
        // 2. Via su
        val r = ShellUtils.executeSu("cat /data/local/tmp/adb_x_pairing_code 2>/dev/null", 1500)
        if (r.isSuccess()) {
            val out = r.output.trim()
            if (out.matches(Regex("\\d{6,8}"))) return out
        }
        // 3. Parse dumpsys adb for pairing code
        val d = ShellUtils.executeSu("dumpsys adb 2>/dev/null", 2000)
        if (d.isSuccess()) {
            for (line in d.output.lines()) {
                val trimmed = line.trim()
                val m1 = Regex("[Pp]airing\\s*[Cc]ode[:\\s]*(\\d{6,8})").find(trimmed)
                if (m1 != null) return m1.groupValues[1]
                val m2 = Regex("code[=:]\\s*(\\d{6,8})").find(trimmed)
                if (m2 != null) return m2.groupValues[1]
                // Rust adbd output patterns
                val m3 = Regex("[Pp]airing\\s*code\\s*is\\s*(\\d{6,8})").find(trimmed)
                if (m3 != null) return m3.groupValues[1]
                val m4 = Regex("code:\\s*(\\d{6,8})").find(trimmed)
                if (m4 != null) return m4.groupValues[1]
            }
        }
        return ""
    }

    /**
     * Lightweight marker-only probe. Reads the hook-written
     * /data/local/tmp/adb_x_pairing_port and returns the port when the
     * file is fresh. Touches no su and spawns no process, so it is cheap
     * enough for a UI polling loop — unlike [getPairingPort], whose
     * fallback chain shells out to dumpsys.
     *
     * The marker is ignored if older than [PORT_MARKER_TTL_MS] — Android
     * expires the ephemeral pairing port after ~120 s of inactivity, and
     * a stale value would otherwise keep the "配对进行中" card on screen
     * forever.
     */
    fun peekPairingMarker(): String {
        return try {
            val f = File("/data/local/tmp/adb_x_pairing_port")
            if (!f.exists() || !f.canRead()) return ""
            val ageMs = System.currentTimeMillis() - f.lastModified()
            if (ageMs > PORT_MARKER_TTL_MS) {
                Log.d(TAG, "pairing marker aged ${ageMs}ms, ignoring")
                return ""
            }
            val port = f.readText().trim()
            if (port.isNotEmpty() && port != "0" && port.all { it.isDigit() }
                && port.toInt() in 1024..65535) {
                Log.d(TAG, "pairing port via /data/local/tmp marker: $port (age ${ageMs}ms)")
                port
            } else ""
        } catch (_: Throwable) { "" }
    }

    /**
     * Read the temporary ADB pairing port. Only sources that reflect a
     * LIVE pairing session are accepted:
     *   1. TTL-guarded marker file written by the LSPosed hook at the
     *      moment it captures the pairing port + code.
     *   2. `dumpsys adb` → adb_wifi.tls_port — authoritative on Android 14/15
     *      Rust adbd; 0 means there is no active pairing session.
     *
     * Deliberately NOT used: `dumpsys wifi` (keeps a stale pairing-looking
     * field with port 3000 long after the dialog closes) and
     * `service.adb.tls.port` / `service.adb.tcp.port` (main listen ports,
     * e.g. 5555, that persist after the dialog closes). Both produced a
     * permanent false "配对进行中" status on real devices.
     *
     * Returns the empty string when nothing usable is found.
     */
    fun getPairingPort(): String {
        // 1. Hook-written marker file, via the cheap probe above.
        val marker = peekPairingMarker()
        if (marker.isNotEmpty()) return marker

        // Every remaining strategy shells out through su. Without root
        // there is nothing to gain: executeSu() walks all four SU_PATHS
        // entries and burns the full timeout on each, so the probes below
        // could cost ~12 s per call — and this runs on every refresh tick.
        if (!ShellUtils.hasRoot()) {
            Log.d(TAG, "getPairingPort: no root, skipping dumpsys fallbacks")
            return ""
        }

        // 2. dumpsys wifi parsing was REMOVED. On a real device (OPlus
        //    PJZ110) `dumpsys wifi` keeps an old pairing-looking field with
        //    port 3000 indefinitely, while `dumpsys adb` reports
        //    adb_wifi.tls_port=0 — no live session. Parsing it made the
        //    status cards claim "在端口 3000 上激活" forever. Wi-Fi dumps
        //    are NEVER a valid source for pairing-session state.

        // 3. dumpsys adb — authoritative on Android 14/15 Rust adbd.
        //    `dumpsys adb` dumps an `adb_wifi={ ... tls_port=N }` block.
        //    During an active pairing session tls_port is the ephemeral
        //    pairing port; outside one it is 0. This is a real system
        //    data source and needs NO system_server hook — unlike the
        //    old pairing-port capture hook that never loaded on this ROM.
        //    We also confirm adb_wifi.enabled=true so a stale/non-wifi
        //    value can't reanimate the pairing card.
        try {
            val r = ShellUtils.executeSu("dumpsys adb 2>&1", 3000)
            if (r.isSuccess() && r.output.isNotBlank()) {
                val out = r.output
                // Isolate the adb_wifi block if present.
                val wifiBlock = Regex("""adb_wifi\s*=\s*\{([\s\S]*?)\}""")
                    .find(out)?.groupValues?.get(1) ?: out
                val enabled = Regex("""enabled\s*=\s*(\w+)""")
                    .find(wifiBlock)?.groupValues?.get(1)?.equals("true", true) ?: true
                if (enabled) {
                    val port = Regex("""tls_port\s*=\s*(\d+)""")
                        .find(wifiBlock)?.groupValues?.get(1)
                    if (port != null && port.toIntOrNull() in 1024..65535) {
                        Log.d(TAG, "pairing port via dumpsys adb adb_wifi.tls_port: $port")
                        return port
                    }
                }
            }
        } catch (_: Throwable) { }

        // 4. setprop fallback REMOVED. service.adb.tls.port / tcp.port are
        //    the *main* listen ports and stay set long after a pairing
        //    dialog closes (tcp is 5555 by default). Treating them as a
        //    live pairing port created yet another false "配对进行中" card.
        //    The authoritative adb_wifi.tls_port above is sufficient.
        return ""
    }

    /**
     * Clear the hook-written pairing-port marker. Called when the user
     * cancels a pairing dialog or when the port becomes stale.
     */
    fun clearPairingPort() {
        try {
            ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_port", 1000)
        } catch (_: Throwable) { }
    }

    fun clearPairingCode() {
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 1000)
    }

    data class AdbStatus(
        val enabled: Boolean,
        val port: String,
        val pairingPort: String,
        val pairingCode: String,
        val hasRoot: Boolean,
        val mode: String = ""
    )

    /**
     * (suspend function)
     */
    suspend fun getFullStatus(context: Context): AdbStatus = withContext(Dispatchers.IO) {
        val hasRoot = ShellUtils.hasRoot()
        val enabled = getCurrentState(context)
        val port = getCurrentPort()
        val pairingPort = getPairingPort()
        val pairingCode = readPairingCode()
        val mode = detectAdbMode(port)
        AdbStatus(enabled, port, pairingPort, pairingCode, hasRoot, mode)
    }

    private fun detectAdbMode(port: String): String {
        if (port.isBlank()) return ""
        return try {
            val tls = ShellUtils.executeSu("getprop service.adb.tls.port", 400).output.trim()
            val tcp = ShellUtils.executeSu("getprop service.adb.tcp.port", 400).output.trim()
            when {
                port == tls && port == tcp -> ""
                port == tls -> "tls"
                port == tcp -> "tcp"
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Trigger the system pairing dialog by reflection-calling
     * AdbDebuggingManager.startAdbPairing() via the system_server classloader.
     *
     * We do this through `app_process` (Dalvik shell) running a tiny
     * Java/Kotlin-equivalent helper inside the system_server uid, so
     * the reflection has access to package-private methods. The exact
     * mechanism we use: write a helper script under
     * /data/local/tmp/adb_x_trigger_pair.sh and run it via `su 0 sh ...`.
     * The script invokes app_process with `-Djava.class.path=...` and
     * calls the manager.
     *
     * Why app_process? A pure Kotlin/Java reflection call from this
     * process cannot reach `com.android.server.adb.AdbDebuggingManager`
     * — that class lives in the system_server classloader, not the
     * app classloader.
     *
     * Returns true if the script ran without shell-level error. We do
     * NOT have a way to know whether the pairing dialog actually
     * opened (LSPosed is the only reliable way to peek at the
     * AdbDebuggingManager state from outside system_server, and on
     * this ROM the hook is not injected into system_server).
     */
    /** Outcome of a pairing-guide attempt. The pairing SESSION is always
     *  started by the user in system Developer options — this app never
     *  triggers it. We only guide the user to the right screen. */
    sealed class PairingStart {
        object OpenedSettings : PairingStart() // guided to Developer Options
        object Failed : PairingStart()
    }

    /**
     * Guide the user to the system wireless-pairing screen. We deliberately
     * do NOT start the pairing session ourselves: on this ROM every
     * programmatic path (`service call adb`, the request-marker watcher)
     * failed to open a real dialog and only produced false UI state. Android
     * only mints the one-time code when the user taps "Pair device with
     * pairing code" in Developer options, so that tap must be the user's.
     *
     * Our Settings hook watches the resulting dialog and records the code;
     * the app reads the port from `dumpsys adb`.
     */
    fun startPairing(context: Context?): PairingStart {
        val ctx = context ?: top.cbug.adbx.App.appContext
        // Prefer the dedicated wireless-debugging screen (string literal:
        // the constant is hidden on some SDK levels). Fall back to plain
        // Developer options if the ROM doesn't handle that action.
        val actions = arrayOf(
            "android.settings.WIRELESS_DEBUGGING_SETTINGS",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        )
        for (action in actions) {
            try {
                val intent = Intent(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ctx.startActivity(intent)
                Log.d(TAG, "startPairing: opened $action (user must tap Pair)")
                return PairingStart.OpenedSettings
            } catch (t: Throwable) {
                Log.w(TAG, "startPairing: $action failed: ${t.message}")
            }
        }
        return PairingStart.Failed
    }

    /** Backwards-compatible boolean: pairing is now always user-started. */
    fun triggerPairing(context: Context? = null): Boolean =
        startPairing(context) == PairingStart.OpenedSettings


    /** True only when the boot module and both scripts are actually on disk. */
    fun isKernelSuModuleInstalled(): Boolean {
        return try {
            val verify = ShellUtils.executeSu(
                "test -d /data/adb/modules/adbx && test -x /data/adb/modules/adbx/service.sh && test -x /data/adb/modules/adbx/bin/adbx-boot-eval && echo OK", 3000)
            verify.isSuccess() && verify.output.contains("OK")
        } catch (_: Throwable) { false }
    }

    /**
     * Install adbx as a KernelSU module so that the boot-time
     * `service.sh` runs every reboot, BEFORE the lockscreen goes
     * up and BEFORE OnePlus AppStartupManager decides what is
     * "recent". This bypasses both Android 14+ background-receiver
     * restrictions and OplusAppStartupManager's refusal to launch
     * background broadcasts.
     *
     * Layout:
     *
     *   /data/adb/modules/adbx/
     *     module.prop         <- metadata
     *     service.sh          <- runs in KernelSU uid at boot, calls
     *                           our app's CLI evaluation path
     *     bin/adbx-boot-eval  <- standalone shell script that reads
     *                           the current SSID and the trusted
     *                           list from /data/data/top.cbug.adbx
     *                           and toggles adb_wifi_enabled
     *
     * The service.sh uses ksud's `boot-completed` event so the
     * app process is not needed for the boot-time path — when the
     * device finishes booting, KernelSU invokes our script, the
     * script does the SSID lookup, and wireless ADB turns on
     * without any UI / notification / user interaction.
     *
     * Idempotent — running it again reuses the existing module dir
     * and overwrites only the script. Safe to call every time the
     * app opens, we gate it behind a one-shot SharedPreferences
     * key so we only run the install once per install (and again
     * if the user explicitly asks).
     *
     * Returns true if the module dir is in place after the call,
     * false on any failure (root missing, OPlus refusal, etc).
     */
    fun installAsKernelSuModule(): Boolean {
        return try {
            val moduleDir = "/data/adb/modules/adbx"
            // The two shell scripts are base64-encoded to avoid any
            // string-template interpolation conflicts with the $
            // characters inside the shell. The base64 strings live
            // as Kotlin string constants so they survive the
            // compile step without us having to load files from
            // assets/ at runtime.
            val cmds = listOf(
                "mkdir -p $moduleDir/bin",
                "echo '" + SERVICE_SH_B64 + "' | base64 -d > $moduleDir/service.sh",
                "chmod 755 $moduleDir/service.sh",
                "echo '" + BOOT_EVAL_B64 + "' | base64 -d > $moduleDir/bin/adbx-boot-eval",
                "chmod 755 $moduleDir/bin/adbx-boot-eval",
                "echo 'aWQ9YWRieApuYW1lPWFkYngKdmVyc2lvbj12MS4wLjAKdmVyc2lvbkNvZGU9MQphdXRob3I9YmxvY2ttYW4zMDYzCmRlc2NyaXB0aW9uPUJ5cGFzcyBPcGx1c0FwcFN0YXJ0dXBNYW5hZ2VyIHNvIHRoZSB0cnVzdGVkLVdpRmkgYXV0by10b2dnbGUgcnVucyBhdCBib290Cg==' | base64 -d > $moduleDir/module.prop",
                "chmod 644 $moduleDir/module.prop",
                "chown -R root:root $moduleDir",
                "ksud module install $moduleDir/module.prop 2>&1 || true",
            )
            for (c in cmds) {
                val r = ShellUtils.executeSu(c, 5000)
                if (!r.isSuccess()) {
                    Log.w(TAG, "installAsKernelSuModule: step failed: " + c.take(80) + " -> " + r.output.take(150))
                }
            }
            val verify = ShellUtils.executeSu(
                "test -d $moduleDir && test -x $moduleDir/service.sh && test -x $moduleDir/bin/adbx-boot-eval && echo OK", 3000)
            verify.isSuccess() && verify.output.contains("OK")
        } catch (t: Throwable) {
            Log.w(TAG, "installAsKernelSuModule failed", t)
            false
        }
    }

    // ---------------- USB / wired ADB ----------------
    //
    // Wired ADB lives on a different setting from wireless: they don't
    // interact on this device (wireless is over wlan0, USB is over the
    // gadget/composite driver). We expose distinct toggles so a user
    // can keep "auto-arm on trusted USB" on while leaving wireless off,
    // and so a single toggle can't disable both at once.

    /**
     * Enable ADB over USB by setting persist.sys.usb.config to include
     * "mtp,adb" (the modern Android default) and rebooting the USB
     * gadget driver. A reboot of the device is NOT required — the
     * persist properties take effect on the next USB function switch
     * which `service call usb` triggers, but on most OnePlus/opus ROMs
     * the kernel gadget is hot-pluggable via this command sequence.
     */
    fun enableUsbAdb(): Boolean {
        Log.d(TAG, "enableUsbAdb")
        // Mirror the existing USB function. We append ",adb" to whatever
        // is already in persist.sys.usb.config so we don't strip MTP / PTP
        // modes the user has set in Developer options.
        val current = ShellUtils.executeSu("getprop persist.sys.usb.config", 1000).output.trim()
        if (current.contains(",adb")) {
            Log.d(TAG, "enableUsbAdb: persist.sys.usb.config already includes adb")
        } else {
            val target = if (current.isBlank()) "mtp,adb" else "$current,adb"
            val r = ShellUtils.executeSu(
                "setprop persist.sys.usb.config '$target' && setprop sys.usb.config '$target'", 1500
            )
            if (!r.isSuccess()) {
                Log.w(TAG, "enableUsbAdb: setprop failed: " + r.output.take(200))
                return false
            }
        }
        // Re-bind the gadget so the new function takes effect. On Q+
        // this re-enumerates the USB device and the host picks up the
        // new function set without a full reboot.
        val r2 = ShellUtils.executeSu(
            "service call usb 8 i32 0 s16 'adb' i32 1 i32 0 i32 0", 2000
        )
        if (!r2.isSuccess()) {
            Log.w(TAG, "enableUsbAdb: service call usb failed: " + r2.output.take(200))
            return false
        }
        // Persist our local marker so the Status tab can show a
        // accurate state — the persist.sys.usb.config is the source
        // of truth, but reading it requires root every refresh.
        top.cbug.adbx.store.Settings.usbAdbEnabled = true
        return true
    }

    /** Strip the ,adb suffix from persist.sys.usb.config and re-bind
     *  the USB gadget. MTP / PTP / charging are preserved. */
    fun disableUsbAdb(): Boolean {
        Log.d(TAG, "disableUsbAdb")
        val current = ShellUtils.executeSu("getprop persist.sys.usb.config", 1000).output.trim()
        if (current.contains(",adb")) {
            val target = current.replace(",adb", "").replace("adb,", "").replace(",,", ",").trim(',')
            ShellUtils.executeSu(
                "setprop persist.sys.usb.config '$target' && setprop sys.usb.config '$target'", 1500
            )
        }
        ShellUtils.executeSu(
            "service call usb 8 i32 0 s16 'mtp' i32 1 i32 0 i32 0", 2000
        )
        top.cbug.adbx.store.Settings.usbAdbEnabled = false
        return true
    }

    /**
     * Whether ADB-over-USB is currently enabled. Uses getprop first
     * (root path), then falls back to the Settings marker the app
     * tracks locally. Either signal is enough — the user wants to
     * know "is ADB on for this USB cable right now".
     */
    fun isUsbAdbEnabled(): Boolean {
        val r = ShellUtils.executeSu("getprop persist.sys.usb.config", 1000)
        if (r.isSuccess() && r.output.contains(",adb")) return true
        return top.cbug.adbx.store.Settings.usbAdbEnabled
    }


}
