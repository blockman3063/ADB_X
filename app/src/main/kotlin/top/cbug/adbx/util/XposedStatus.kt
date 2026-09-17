package top.cbug.adbx.util

import android.content.Context
import android.util.Log
import java.io.File

object XposedStatus {

    private const val TAG = "ADB_X_XposedStatus"

    enum class State { ACTIVE, INACTIVE, UNKNOWN }

    data class Info(
        val state: State,
        val frameworkPackages: List<String>,
        val frameworkHint: String
    )

    private var appContext: Context? = null
    fun init(app: Context) { appContext = app.applicationContext }

    fun markActive(context: Context? = null) {
        try {
            val now = System.currentTimeMillis()
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
        try {
            val f = File(MARKER_PATH)
            f.writeText(now.toString())
            f.setReadable(true, false)
            Log.d(TAG, "marker written to /data/local/tmp")
            return
        } catch (t: Throwable) {
            Log.d(TAG, "/data/local/tmp write failed (${t.message}), trying app data dir")
        }
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
    private const val SP_NAME = "adb_x_xposed_state"
    private const val SP_KEY_LAST_INJECT = "last_inject_ms"

    fun reset(context: Context? = null) {
        try {
            (context ?: appContext)?.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(SP_KEY_LAST_INJECT)?.apply()
        } catch (_: Throwable) { }
        try {
            File(MARKER_PATH).delete()
        } catch (_: Throwable) { }
    }

    fun probe(context: Context): Info {
        if (hasInjectionMarker()) {
            Log.d(TAG, "probe: ACTIVE via /data/local/tmp marker")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (file marker)")
        }

        if (hasAppInjectionMarker()) {
            Log.d(TAG, "probe: ACTIVE via app data marker")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (app data marker)")
        }

        if (hasInjectedIntoSelf()) {
            Log.d(TAG, "probe: ACTIVE via /proc/self maps/status")
            return Info(State.ACTIVE, emptyList(), "Hook loaded (proc self maps)")
        }

        Log.d(TAG, "probe: no ACTIVE signal, falling through to framework check")

        val frameworks = mutableListOf<String>()
        val hint = StringBuilder()

        if (isLSPosedZygiskPresent()) {
            frameworks += "LSPosed (Zygisk)"
            hint.append("LSPosed Zygisk installed")
        }
        val mgrPkgs = detectManagerPackages(context)
        if (mgrPkgs.isNotEmpty()) {
            frameworks += mgrPkgs
            if (hint.isNotEmpty()) hint.append(" + ")
            hint.append(mgrPkgs.joinToString(", "))
        }

        val scopeEnabled = isModuleEnabledForCurrentScope()
        return if (frameworks.isEmpty() && !scopeEnabled) {
            Info(State.UNKNOWN, emptyList(), "No Xposed framework detected")
        } else {
            Info(
                State.INACTIVE,
                frameworks,
                buildString {
                    append("Framework installed (")
                    append(if (hint.isNotEmpty()) hint.toString() else "none detected")
                    if (scopeEnabled) {
                        append(") but module scope not enabled for this package")
                    } else {
                        append(") but hook not loaded into this process")
                    }
                }
            )
        }
    }

    private fun hasInjectionMarker(): Boolean {
        return try {
            val f = File("/data/local/tmp/adb_x_injected")
            f.exists() && f.canRead() && f.lastModified() in 0..System.currentTimeMillis() &&
                    System.currentTimeMillis() - f.lastModified() <= 24 * 60 * 60 * 1000
        } catch (_: Throwable) { false }
    }

    private fun hasAppInjectionMarker(): Boolean {
        return try {
            val f = File("/data/data/top.cbug.adbx/files/adb_x_injected")
            f.exists() && f.canRead() && f.lastModified() in 0..System.currentTimeMillis() &&
                    System.currentTimeMillis() - f.lastModified() <= 24 * 60 * 60 * 1000
        } catch (_: Throwable) { false }
    }

    private fun hasInjectedIntoSelf(): Boolean {
        runCatching {
            File("/proc/self/maps").useLines { lines ->
                if (lines.any { line ->
                        val l = line.lowercase()
                        l.contains("xposed") || l.contains("lsposed") ||
                                l.contains("edxp") || l.contains("riru")
                    }) return true
            }
        }
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

    private fun isLSPosedZygiskPresent(): Boolean {
        val probes = listOf(
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed",
            "/data/adb/zygisksu/lsposed"
        )
        return probes.any { File(it).exists() }
    }

    /**
     * Which Xposed manager apps are actually installed. Queries
     * PackageManager rather than shelling out, so it works without root —
     * that was the original intent of this stub, which previously built a
     * candidate list and then discarded it. Reporting the real packages
     * lets the Status card distinguish "framework installed" from
     * "framework not detected".
     */
    @Suppress("DEPRECATION")
    private fun detectManagerPackages(context: Context): List<String> {
        val candidates = listOf(
            "org.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "org.lsposed.lspatch"
        )
        return try {
            val pm = context.packageManager
            candidates.filter { pkg ->
                try {
                    pm.getApplicationInfo(pkg, 0)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isModuleEnabledForCurrentScope(): Boolean {
        return try {
            val db = "/data/adb/lspd/config/modules_config.db"
            if (!File(db).exists()) return false
            val r = ShellUtils.executeSu(
                "sqlite3 '$db' \"SELECT scope_type FROM modules WHERE package_name='top.cbug.adbx' LIMIT 1;\"",
                2000
            )
            if (!r.isSuccess() || r.output.isBlank()) return false
            val scope = r.output.trim().toIntOrNull() ?: return false
            scope != 0
        } catch (_: Throwable) {
            false
        }
    }
}
