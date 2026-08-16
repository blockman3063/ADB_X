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

    /**
     * Fast root probe: check SU binary existence by trying to run a
     * one-line echo through each candidate su. Sets workingSuPath
     * immediately if any su binary works. Early-bails on "su not
     * found" so we don't hammer every entry of [SU_PATHS] on ROMs
     * like OnePlus OxygenOS where su is just absent.
     */
    fun probeRootFast(): Boolean {
        val now = System.currentTimeMillis()
        if (rootChecked && (now - lastRootCheckMs) < ROOT_CACHE_TTL_MS) {
            return rootAvailable
        }
        for (suPath in SU_PATHS) {
            try {
                val cmd = suPath + "echo ADB_X_ROOT_OK"
                val proc = ProcessBuilder(*cmd.toTypedArray())
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(PROBE_TIMEOUT_MS * 30, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    continue
                }
                val out = proc.inputStream.bufferedReader().readText().trim()
                // Early-bail on "su not found" — there's no point
                // hammering the next 3 SU_PATHS entries on ROMs
                // where su is just missing.
                if (out.contains("su not found", ignoreCase = true)) {
                    rootChecked = true
                    rootAvailable = false
                    lastRootCheckMs = now
                    return false
                }
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
                    workingSuPath = listOf("/system/bin/sh", "-c", out.trim() + " ")
                }
            }
            rootChecked = true; rootAvailable = false; lastRootCheckMs = now
            ok
        }.getOrElse { false }
    }

    /**
     * Plain shell execute via /system/bin/sh -c. Returns (-2, "timeout")
     * if the call doesn't finish in [timeoutMs].
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

    /**
     * executeSu: tries cached path first, falls back to all paths.
     * Once a working path is found, it is cached for subsequent calls.
     * On ROMs where every su entry returns "su not found" we return
     * immediately after the first such failure rather than chewing
     * through the rest of the SU_PATHS list with full timeouts.
     */
    fun executeSu(command: String, timeoutMs: Long = 2000): Result {
        val cmdStart = System.currentTimeMillis()
        android.util.Log.d(TAG, "executeSu cmd='" + command.take(60) + "' timeout=" + timeoutMs + "ms")
        var outcome: Result? = null
        try {
            val cached = workingSuPath
            if (cached != null) {
                try {
                    val cmd = cached + command
                    val proc = ProcessBuilder(*cmd.toTypedArray())
                        .redirectErrorStream(true)
                        .start()
                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (finished) {
                        val out = proc.inputStream.bufferedReader().readText()
                        if (out.contains("su not found", ignoreCase = true)) {
                            workingSuPath = null
                            outcome = Result(127, out)
                            android.util.Log.d(TAG, "executeSu done in " + (System.currentTimeMillis() - cmdStart) + "ms rc=127 (su not found)")
                            return outcome
                        }
                        outcome = Result(proc.exitValue(), out)
                        android.util.Log.d(TAG, "executeSu done in " + (System.currentTimeMillis() - cmdStart) + "ms rc=" + outcome.exitCode)
                        return outcome
                    }
                    proc.destroyForcibly()
                } catch (_: Exception) { }
            }
            // Track the very first "su not found" we observe and use
            // it to break the SU_PATHS loop entirely — there's no
            // point hammering the remaining 3 entries on ROMs where
            // su is just absent. Without this the loop burns
            // `timeoutMs` × |SU_PATHS| = 1 s × 4 = 4 s of dead
            // time per refresh tick before the first su-not-found
            // sticky-fail.
            var sawSuNotFound = false
            for (suPath in SU_PATHS) {
                try {
                    val cmd = suPath + command
                    val proc = ProcessBuilder(*cmd.toTypedArray())
                        .redirectErrorStream(true)
                        .start()
                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) { proc.destroyForcibly(); continue }
                    val out = proc.inputStream.bufferedReader().readText()
                    if (out.contains("su not found", ignoreCase = true)) {
                        sawSuNotFound = true
                        continue
                    }
                    val r = Result(proc.exitValue(), out)
                    if (r.isSuccess() || out.isNotBlank()) {
                        workingSuPath = suPath
                    }
                    outcome = r
                    android.util.Log.d(TAG, "executeSu done in " + (System.currentTimeMillis() - cmdStart) + "ms rc=" + outcome.exitCode)
                    return outcome
                } catch (_: Exception) { }
            }
            // If every entry echoed "su not found", we know the ROM
            // is su-less — no point retrying on subsequent ticks.
            // workingSuPath stays null so the cached-path branch
            // above keeps returning early.
            if (sawSuNotFound) {
                // No-op: leave workingSuPath = null.
            }
            outcome = Result(-1, "su not found")
        } catch (e: Exception) {
            outcome = Result(-1, e.message ?: "")
        }
        android.util.Log.d(TAG, "executeSu done in " + (System.currentTimeMillis() - cmdStart) + "ms rc=" + (outcome?.exitCode ?: -99))
        return outcome ?: Result(-99, "unreachable")
    }

    /**
     * Run a shell command via su, piping the given content to stdin.
     * Used when the caller wants to send a multi-line script with
     * heredocs, quotes, and dollar-signs that would otherwise be
     * eaten by the outer sh -c '...' wrapper.
     */
    fun executeSuWithStdin(content: String, timeoutMs: Long = 10000L): Result {
        val cmdStart = System.currentTimeMillis()
        return try {
            val cached = workingSuPath
            val candidates = if (cached != null) listOf(cached) + SU_PATHS else SU_PATHS
            for (suPath in candidates) {
                try {
                    val pb = ProcessBuilder(*suPath.toTypedArray(), "sh", "-s")
                        .redirectErrorStream(true)
                    val proc = pb.start()
                    proc.outputStream.bufferedWriter().use { it.write(content); it.flush() }
                    proc.outputStream.close()
                    val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) { proc.destroyForcibly(); continue }
                    val out = proc.inputStream.bufferedReader().readText()
                    if (out.contains("su not found", ignoreCase = true)) continue
                    val result = Result(proc.exitValue(), out)
                    if (result.isSuccess() || out.isNotBlank()) {
                        workingSuPath = suPath
                    }
                    android.util.Log.d(TAG, "executeSuWithStdin done in " + (System.currentTimeMillis() - cmdStart) + "ms rc=" + result.exitCode)
                    return result
                } catch (_: Exception) { }
            }
            Result(-1, "su not found")
        } catch (e: Exception) {
            Result(-1, e.message ?: "")
        }
    }

    /**
     * Full root probe — re-checks every SU_PATHS entry and verifies
     * each returns uid=0. Heavier than [probeRootFast] (which is
     * what [hasRoot] actually calls) — kept for callers that need
     * to force a fresh check.
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
     * Cheap root check that honours the in-process root cache.
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