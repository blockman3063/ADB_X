package top.cbug.adbx.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** Use settings global as primary check (works on Rust adbd too) */
    fun isAdbWifiEnabled(context: Context): Boolean {
        return getCurrentState(context)
    }

    /** Get current ADB port - prefers non-root method first */
    fun getCurrentPort(context: Context?): String {
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
            if (context != null) {
                val v = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
                if (v == 1) {
                    Log.d(TAG, "adb_wifi_enabled=1 via ContentResolver")
                    return true
                }
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

    /**
     * TODO: document enableWirelessAdb
     */
    fun enableWirelessAdb(): Boolean {
        Log.d(TAG, "enableWirelessAdb")
        // On modern Android (14+, Rust adbd) the authoritative way is
        // settings put global.  NEVER restart adbd — that kills the
        // wireless connection we're running over.
        ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1500)
        // Also set service props as hints for the next adbd life-cycle.
        ShellUtils.executeSu("setprop service.adb.tls.port 5555", 500)
        ShellUtils.executeSu("setprop service.adb.tcp.port 5555", 500)
        return true
    }

    /**
     * TODO: document disableWirelessAdb
     */
    fun disableWirelessAdb(): Boolean {
        Log.d(TAG, "disableWirelessAdb")
        ShellUtils.executeSu("settings put global adb_wifi_enabled 0", 1500)
        ShellUtils.executeSu("setprop service.adb.tls.port 0", 500)
        ShellUtils.executeSu("setprop service.adb.tcp.port 0", 500)
        return true
    }

    /**
     * TODO: document setFixedPort
     * @param Int
     */
    fun setFixedPort(port: Int): Boolean {
        ShellUtils.executeSu("settings put global adb_wifi_enabled 1", 1000)
        ShellUtils.executeSu("setprop service.adb.tcp.port " + port, 500)
        val r = ShellUtils.executeSu(
            "setprop service.adb.tls.port " + port, 2000)
        Log.d(TAG, "setFixedPort(" + port + "): " + r.output.trim())
        return r.isSuccess()
    }

    /**
     * TODO: document setPairingCode
     * @param String
     */
    fun setPairingCode(code: String): Boolean {
        if (code.length !in 6..8 || !code.all { it.isDigit() }) return false
        writePairingCodeFile(code)
        Log.d(TAG, "Pairing code stored: " + code)
        return true
    }

    private fun writePairingCodeFile(code: String) {
        // Remove old file first to avoid readPairingCode() picking up a stale value
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 500)
        ShellUtils.executeSu(
            "echo '" + code + "' > /data/local/tmp/adb_x_pairing_code && chmod 644 /data/local/tmp/adb_x_pairing_code",
            2000)
    }

    /**
     * TODO: document readPairingCode
     */
    fun readPairingCode(): String {
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
     * Read the temporary ADB pairing port. Multiple strategies because no
     * single API is reliable across ROMs / Android versions:
     *   1. Marker file written by the LSPosed hook (system_server) at the
     *      point our hook captures the port + code from AdbDebuggingManager.
     *      Authoritative when present.
     *   2. `cmd statusbar` doesn't have this — fall back to grepping the
     *      marker file in /data/local/tmp OR in our app's data dir.
     *   3. dumpsys wifi parsing for a "pairing port" line — works on stock
     *      Android 13+.
     *   4. dumpsys adb parse for pairing fields.
     *   5. service.adb.tls.port as last-ditch (often wrong, but better than
     *      blank when the user really needs a port to try).
     *
     * Returns the empty string when nothing usable is found.
     */
    fun getPairingPort(): String {
        // 1. Hook-written marker file (system_server can write here, app can read).
        //    The marker is ignored if older than 5 min — Android expires
        //    the ephemeral pairing port after ~120 s of inactivity, and a
        //    stale value would otherwise keep the "配对进行中" card on
        //    screen forever.
        try {
            val f = File("/data/local/tmp/adb_x_pairing_port")
            if (f.exists() && f.canRead()) {
                val ageMs = System.currentTimeMillis() - f.lastModified()
                if (ageMs > PORT_MARKER_TTL_MS) {
                    Log.d(TAG, "pairing marker aged " + ageMs + "ms, ignoring")
                } else {
                    val port = f.readText().trim()
                    if (port.isNotEmpty() && port != "0" && port.all { it.isDigit() }
                        && port.toInt() in 1024..65535) {
                        Log.d(TAG, "pairing port via /data/local/tmp marker: " + port + " (age " + ageMs + "ms)")
                        return port
                    }
                }
            }
        } catch (_: Throwable) { }

        // 2. dumpsys wifi — match ONLY pairing-specific lines. We must
        //    avoid "adb tcp" / "tls port" because those are present in the
        //    main (non-ephemeral) ADB listen port output and would falsely
        //    reanimate the "配对进行中" card with the wrong port.
        try {
            val suResult = ShellUtils.executeSu(
                "dumpsys wifi 2>&1 | grep -iE 'pairing_port|adb-pairing|adb_pairing' | head -10", 3000)
            if (suResult.isSuccess() && suResult.output.isNotBlank()) {
                val rx = Regex("(?:adb[_-]pairing[_-]port|pairing[_-]port|adb[_-]pairing)[^0-9]*?(\\d{4,5})")
                val match = rx.find(suResult.output)
                if (match != null) {
                    val port = match.groupValues[1]
                    if (port.toIntOrNull() in 1024..65535) {
                        Log.d(TAG, "pairing port via dumpsys wifi: $port")
                        return port
                    }
                }
            }
        } catch (_: Throwable) { }

        // 3. dumpsys adb — for "adb_pair" or ephemeral port fields.
        try {
            val r = ShellUtils.executeSu("dumpsys adb 2>&1", 3000)
            if (r.isSuccess() && r.output.isNotBlank()) {
                val rx = Regex("(?:pairing|ephemeral|temp|adbd pair port)[^0-9]*?(\\d{4,5})")
                val match = rx.find(r.output)
                if (match != null) {
                    val port = match.groupValues[1]
                    if (port.toIntOrNull() in 1024..65535) {
                        Log.d(TAG, "pairing port via dumpsys adb: $port")
                        return port
                    }
                }
            }
        } catch (_: Throwable) { }

        // 4. setprop fallback — usually NOT the pairing port on Android 14+,
        //    and even worse, after the pairing dialog closes the system
        //    leaves the last ephemeral port in service.adb.tls.port for
        //    some time. Only accept the value as live when both
        //    service.adb.tls.port AND persist.adb.tls.port are non-zero
        //    and in the ephemeral range — any mismatch means we're looking
        //    at a stale value from a previous dialog and should ignore it.
        for (prop in arrayOf("service.adb.tls.port", "service.adb.tcp.port")) {
            val persistProp = "persist." + prop.removePrefix("service.")
            val persistOut = ShellUtils.executeSu("getprop " + persistProp, 500).output.trim()
            val persistVal = persistOut.toIntOrNull() ?: 0
            if (persistVal == 0) {
                Log.d(TAG, "skip setprop " + prop + ": " + persistProp + "=0 (no active listener)")
                continue
            }
            val out = ShellUtils.execute("getprop " + prop, 500).output.trim()
            if (out.isNotEmpty() && out != "0" && out.all { it.isDigit() }
                && out.toInt() in 1024..65535) {
                Log.d(TAG, "pairing port via setprop " + prop + ": " + out + " (persist ok)")
                return out
            }
        }
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

    /**
     * TODO: document clearPairingCode
     */
    fun clearPairingCode() {
        ShellUtils.executeSu("rm -f /data/local/tmp/adb_x_pairing_code", 1000)
    }

    data class AdbStatus(
        val enabled: Boolean,
        val port: String,
        val pairingPort: String,
        val pairingCode: String,
        val hasRoot: Boolean
    )

    /**
     * TODO: document getFullStatus
     * @param Context
     * (suspend function)
     */
    suspend fun getFullStatus(context: Context): AdbStatus = withContext(Dispatchers.IO) {
        val hasRoot = ShellUtils.hasRoot()
        val enabled = getCurrentState(context)
        val port = getCurrentPort(context)
        val pairingPort = getPairingPort()
        val pairingCode = readPairingCode()
        AdbStatus(enabled, port, pairingPort, pairingCode, hasRoot)
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
    fun triggerPairing(): Boolean {
        // AOSP IAdbManager AIDL: enablePairingByPairingCode is at
        // transaction code 8 (FIRST_CALL_TRANSACTION + 7). The 0-indexed
        // methods (allowDebugging, denyDebugging, ...) are followed by
        // enablePairingByPairingCode, which sends the intent
        // WIRELESS_DEBUG_ENABLE_DISCOVER_ACTION and spawns the
        // pairing port.
        Log.d(TAG, "triggerPairing: service call adb 8")
        val r = ShellUtils.executeSu("service call adb 8", 3000)
        return r.isSuccess()
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
