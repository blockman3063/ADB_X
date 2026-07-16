package top.cbug.adbx.util

import java.io.File
import java.util.concurrent.TimeUnit

object ShellUtils {

    private const val TAG = "ADB_X_ShellUtils"
    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false
    @Volatile private var lastRootCheckMs = 0L
    private const val ROOT_CACHE_TTL_MS = 60000L
    @Volatile private var workingSuPath: List<String>? = null
    private const val PROBE_TIMEOUT_MS = 100L
    private val SU_PATHS = listOf(
        listOf("/system/bin/su", "-c"),
        listOf("su", "-c"),
        listOf("/data/adb/ksu/bin/su", "-c"),
        listOf("/data/adb/magisk/su", "-c"),
    )

    /** Fast root probe: check SU binary existence only, no shell execution.
     *  Sets workingSuPath immediately if a su binary is found. */
    fun probeRootFast(): Boolean {
        val now = System.currentTimeMillis()
        if (rootChecked && (now - lastRootCheckMs) < ROOT_CACHE_TTL_MS) {
            return rootAvailable
        }
        // Some ROMs (Magisk with DenyList, hardened SELinux) hide su
        // binaries from third-party apps via File.exists(). Try executing
        // su directly instead — if it returns 0 with output we have root.
        for (suPath in SU_PATHS) {
            try {
                val proc = ProcessBuilder(suPath + "echo ADB_X_ROOT_OK")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(PROBE_TIMEOUT_MS * 30, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    continue
                }
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (proc.exitValue() == 0 && out.contains("ADB_X_ROOT_OK")) {
                    workingSuPath = suPath
                    rootChecked = true
                    rootAvailable = true
                    lastRootCheckMs = now
                    android.util.Log.d(TAG, "probeRootFast: " + suPath[0] + " works")
                    return true
                }
            } catch (t: Exception) {
                android.util.Log.d(TAG, "probeRootFast: " + suPath[0] + " error: " + t.message)
            }
        }
        // Fallback: which su
        return runCatching {
            val proc = ProcessBuilder("/system/bin/sh", "-c", "which su 2>/dev/null")
                .redirectErrorStream(true).start()
            val ok = proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (ok) {
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (out.isNotEmpty()) {
                    rootChecked = true; rootAvailable = true; lastRootCheckMs = now
                    val suBinary = out.lines().first().trim()
                    workingSuPath = if (suBinary.contains(" ")) {
                        suBinary.split("\\s+".toRegex())
                    } else {
                        listOf(suBinary, "-c")
                    }
                    true
                } else false
            } else { proc.destroyForcibly(); false }
        }.getOrDefault(false)
    }

    /**
     * TODO: document execute
     * @param String
     * @param 2000
     */
    fun execute(command: String, timeoutMs: Long = 2000): Result {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                android.util.Log.d(TAG, "execute timeout: " + command.take(60))
                return Result(-2, "timeout")
            }
            val out = proc.inputStream.bufferedReader().readText()
            android.util.Log.d(TAG, "execute cmd='" + command.take(60) + "' rc=" + proc.exitValue() + " outLen=" + out.length)
            Result(proc.exitValue(), out)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "execute error: " + command.take(60) + " msg=" + e.message)
            Result(-1, e.message ?: "")
        }
    }

    /** executeSu: tries cached path first, falls back to all paths.
     *  Once a working path is found, it is cached for subsequent calls. */
    fun executeSu(command: String, timeoutMs: Long = 2000): Result {
        return try {
            val cached = workingSuPath
            if (cached != null) {
                try {
                    val proc = ProcessBuilder(cached + command)
                        .redirectErrorStream(true)
                        .start()
                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (finished) {
                        val out = proc.inputStream.bufferedReader().readText()
                        return Result(proc.exitValue(), out)
                    }
                    proc.destroyForcibly()
                } catch (_: Exception) { }
            }
            for (suPath in SU_PATHS) {
                try {
                    val proc = ProcessBuilder(suPath + command)
                        .redirectErrorStream(true)
                        .start()
                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) { proc.destroyForcibly(); continue }
                    val out = proc.inputStream.bufferedReader().readText()
                    val result = Result(proc.exitValue(), out)
                    if (result.isSuccess() || out.isNotBlank()) {
                        workingSuPath = suPath
                    }
                    return result
                } catch (_: Exception) { }
            }
            return Result(-1, "su not found")
        } catch (e: Exception) {
            return Result(-1, e.message ?: "")
        }
    }

    /**
     * TODO: document probeRoot
     * @param false
     */
    fun probeRoot(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && rootChecked && (now - lastRootCheckMs) < ROOT_CACHE_TTL_MS) {
            return rootAvailable
        }
        if (probeRootFast()) return true
        val cached = workingSuPath
        if (cached != null) {
            val testCmd = cached + "id"
            try {
                val proc = ProcessBuilder(testCmd).redirectErrorStream(true).start()
                val finished = proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (finished) {
                    val out = proc.inputStream.bufferedReader().readText()
                    if (out.contains("uid=0") || out.contains("root")) {
                        rootChecked = true; rootAvailable = true; lastRootCheckMs = now
                        return true
                    }
                } else { proc.destroyForcibly() }
            } catch (_: Exception) { }
        }
        for (suPath in SU_PATHS) {
            val testCmd = suPath + "id"
            try {
                val proc = ProcessBuilder(testCmd).redirectErrorStream(true).start()
                val finished = proc.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) { proc.destroyForcibly(); continue }
                val out = proc.inputStream.bufferedReader().readText()
                if (out.contains("uid=0") || out.contains("root")) {
                    rootChecked = true; rootAvailable = true; lastRootCheckMs = now
                    workingSuPath = suPath
                    return true
                }
            } catch (_: Exception) { }
        }
        rootChecked = true; rootAvailable = false; lastRootCheckMs = now
        return false
    }

    /**
     * TODO: document hasRoot
     * @param false
     */
    fun hasRoot(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && rootChecked && (now - lastRootCheckMs) < ROOT_CACHE_TTL_MS) {
            return rootAvailable
        }
        return probeRoot(false)
    }

    data class Result(val exitCode: Int, val output: String) {
        fun isSuccess(): Boolean = exitCode == 0
        fun getTrimmed(): String = output.trim()
    }
}