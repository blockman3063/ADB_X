package top.cbug.adbx.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Detects whether an Xposed framework (LSPosed, EdXposed, legacy Xposed) has loaded
 * this module. Three-state result:
 *
 *   ACTIVE   - framework injected into our process. The XposedInit hook writes a
 *              timestamp to SharedPreferences (cross-classloader safe) and
 *              /proc/self/maps / /proc/self/status both fail to confirm on Android
 *              10+ SELinux, so the SP signal is authoritative.
 *   INACTIVE - framework installed but our hook never ran here (module not in scope, or
 *              scope not configured yet).
 *   UNKNOWN  - no framework evidence found at all.
 *
 * Detection priority:
 *   1. SharedPreferences flag (set by XposedInit in any classloader context).
 *   2. /proc/self/maps (defunct on Android 10+, but kept as a sanity check).
 *   3. /proc/self/status TracerPid (non-zero when ptrace'd — not the same as
 *      Xposed but can sometimes hint at injection state on older ROMs).
 *   4. LSPosed config dir + module row — confirms LSPosed Zygisk is installed even
 *      without the manager APK present (common on KernelSU-only setups).
 *   5. Known manager packages — legacy fallback.
 */
object XposedStatus {

    private const val TAG = "ADB_X_XposedStatus"
    private const val SP_NAME = "adb_x_xposed_state"
    private const val SP_KEY_LAST_INJECT = "last_inject_ms"

    enum class State { ACTIVE, INACTIVE, UNKNOWN }

    data class Info(
        val state: State,
        val frameworkPackages: List<String>,
        val frameworkHint: String
    )

    /**
     * Called by [top.cbug.adbx.xposed.XposedInit.handleLoadPackage] whenever the
     * framework injects into a process. Persists to SharedPreferences so the
     * app process — even when its classloader is different from the one
     * LSPosed used — can see the injection on the next probe. Also writes
     * a marker file as a secondary signal.
     *
     * SharedPreferences is the authoritative signal because it survives
     * across classloader boundaries and SELinux restrictions.
     */
    fun markActive(context: Context? = null) {
        try {
            val now = System.currentTimeMillis()
            // Write to SharedPreferences (cross-classloader safe). The context
            // may be null when called from XposedInit in a non-app process
            // (system_server etc.) — fall back to a marker file in that case.
            val ctx = context ?: appContext
            if (ctx != null) {
                ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(SP_KEY_LAST_INJECT, now)
                    .apply()
                Log.d(TAG, "marker written via SharedPreferences")
            } else {
                writeMarkerFile(now)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "marker write failed: ${t.message}")
            writeMarkerFile(System.currentTimeMillis())
        }
    }

    private fun writeMarkerFile(now: Long) {
        // Try /data/local/tmp first (shell data file context).
        try {
            val f = File(MARKER_PATH)
            f.writeText(now.toString())
            f.setReadable(true, false)
            Log.d(TAG, "marker written to /data/local/tmp")
            return
        } catch (t: Throwable) {
            Log.d(TAG, "/data/local/tmp write failed (${t.message}), trying app data dir")
        }
        // Fallback: write to the app's own data dir via su (system_server can
        // su to the app uid when LSPosed grants root). This is the only path
        // that works when SELinux blocks system_server from /data/local/tmp.
        try {
            val path = "/data/data/top.cbug.adbx/files/adb_x_injected"
            Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "echo " + now + " > " + path + " && chmod 666 " + path))
            Log.d(TAG, "marker written to app data dir")
        } catch (t2: Throwable) {
            Log.w(TAG, "marker file write failed: ${t2.message}")
        }
    }

    private val MARKER_PATH = "/data/local/tmp/adb_x_injected"
    private val APP_MARKER_PATH = "/data/data/top.cbug.adbx/files/adb_x_injected"

    /** Held statically so XposedInit (running before Application.onCreate) can
     *  reach the application context. Initialised lazily on first access. */
    private var appContext: Context? = null
    /**
     * TODO: document init
     * @param Context
     */
    fun init(app: Context) { appContext = app.applicationContext }

    /**
     * TODO: document reset
     * @param null
     */
    fun reset(context: Context? = null) {
        try {
            (context ?: appContext)?.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(SP_KEY_LAST_INJECT)?.apply()
        } catch (_: Throwable) { }
        try {
            File(MARKER_PATH).delete()
        } catch (_: Throwable) { }
    }

    /**
     * Returns true if XposedInit ran in our process within the last 30 minutes
     * (LSPosed fires handleLoadPackage every time the app is launched).
     */
    fun probe(context: Context): Info {
        // 1) Authoritative: SharedPreferences written by the hook in any
        //    classloader context. Survives SELinux / proc restrictions.
        val spHit = hasSpMarker(context)
        if (spHit) return Info(State.ACTIVE, emptyList(), "Hook loaded (SharedPreferences)").also {
            Log.d(TAG, "probe: ACTIVE via SharedPreferences")
        }

        // 2) Fallback: marker file written by the hook (system_server process).
        if (hasInjectionMarker()) {
            Log.d(TAG, "probe: ACTIVE via /data/local/tmp marker")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (file marker)")
        }

        // 3) Fallback: marker file in app's own data dir (survives SELinux
        //    blocks on /data/local/tmp).
        if (hasAppInjectionMarker()) {
            Log.d(TAG, "probe: ACTIVE via app data marker")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (app data marker)")
        }

        // 4) Fallback: /proc/self/maps + status (defunct on Android 10+).
        if (hasInjectedIntoSelf()) {
            Log.d(TAG, "probe: ACTIVE via /proc/self/maps")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (proc self maps)")
        }

        Log.d(TAG, "probe: no ACTIVE signal, falling through to framework check")

        // 4) Evidence the framework exists on this device
        val frameworks = mutableListOf<String>()
        val hint = StringBuilder()

        if (isLSPosedZygiskPresent()) {
            frameworks += "LSPosed (Zygisk)"
            hint.append("LSPosed Zygisk installed")
        }
        val mgrPkgs = detectManagerPackages()
        if (mgrPkgs.isNotEmpty()) {
            frameworks += mgrPkgs
            if (hint.isNotEmpty()) hint.append(" + ")
            hint.append(mgrPkgs.joinToString(", "))
        }

        return if (frameworks.isEmpty()) {
            Info(State.UNKNOWN, emptyList(), "No Xposed framework detected")
        } else {
            Info(
                State.INACTIVE,
                frameworks,
                "Framework installed ($hint) but hook not loaded into this process"
            )
        }
    }

    private fun hasSpMarker(context: Context): Boolean {
        return try {
            val last = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getLong(SP_KEY_LAST_INJECT, 0L)
            if (last == 0L) return false
            // Stale if older than 7 days (LSPosed fires markActive on every app launch,
            // so a recent timestamp is reliable evidence of hook activity).
            val ageMs = System.currentTimeMillis() - last
            ageMs in 0..(7 * 24 * 60 * 60 * 1000L)
        } catch (_: Throwable) { false }
    }

    /** Marker file written by markActive() inside the LSPosed-loaded classloader. */
    private fun hasInjectionMarker(): Boolean {
        return try {
            val f = File(MARKER_PATH)
            if (!f.exists() || !f.canRead()) return false
            val ageMs = System.currentTimeMillis() - f.lastModified()
            ageMs in 0..(24 * 60 * 60 * 1000)
        } catch (_: Throwable) { false }
    }

    /** App data dir marker (survives SELinux on /data/local/tmp). */
    private fun hasAppInjectionMarker(): Boolean {
        return try {
            val f = File(APP_MARKER_PATH)
            if (!f.exists() || !f.canRead()) return false
            val ageMs = System.currentTimeMillis() - f.lastModified()
            ageMs in 0..(24 * 60 * 60 * 1000)
        } catch (_: Throwable) { false }
    }

    /**
     * Read /proc/self/maps + /proc/self/status looking for XposedBridge or related
     * native libraries. This is the most reliable signal: if a framework injected us,
     * the bridge library is mapped into our address space.
     */
    private fun hasInjectedIntoSelf(): Boolean {
        // /proc/self/maps
        runCatching {
            File("/proc/self/maps").useLines { lines ->
                if (lines.any { line ->
                        val l = line.lowercase()
                        l.contains("xposed") || l.contains("lsposed") ||
                        l.contains("edxp") || l.contains("riru")
                    }) return true
            }
        }
        // /proc/self/status — TracerPid non-zero indicates ptrace (LSPosed self-inject).
        runCatching {
            val status = File("/proc/self/status")
            if (status.exists() && status.canRead()) {
                val tracerLine = status.readLines().firstOrNull { it.startsWith("TracerPid:") }
                val tracer = tracerLine?.substringAfter(":")?.trim()?.toIntOrNull()
                if (tracer != null && tracer > 0) return true
            }
        }
        return false
    }

    /**
     * On Zygisk-based setups the manager APK may not be installed but the LSPosed
     * Zygisk module is. Detect this by looking for:
     *   - /data/adb/lspd/config/modules_config.db (LSPosed state DB)
     *   - /data/adb/modules/zygisk_lsposed (Zygisk module install path)
     *   - /data/adb/modules/riru_lsposed (Riru-based, older setups)
     */
    private fun isLSPosedZygiskPresent(): Boolean {
        val probes = listOf(
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed",
            "/data/adb/zygisksu/lsposed"
        )
        return probes.any { File(it).exists() }
    }

    /** Known manager package names — fallback only. */
    private fun detectManagerPackages(): List<String> {
        val candidates = listOf(
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "org.lsposed.lspatch"
        )
        val su = ShellUtils.executeSu("pm list packages | cut -d: -f2", 2000)
        if (su.isSuccess() && su.output.isNotBlank()) {
            val installed = su.output.lineSequence().map { it.trim() }.toSet()
            return candidates.filter { it in installed }
        }
        return emptyList()
    }
}