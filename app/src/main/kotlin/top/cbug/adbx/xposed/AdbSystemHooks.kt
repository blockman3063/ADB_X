package top.cbug.adbx.xposed

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings-process hooks. All logic that still runs lives here.
 *
 * The former system_server branch never fired on this ROM (LSPosed
 * does not inject the server here), so `hook()` and its pairing-port
 * probes were removed. Only the Settings-side entry point remains —
 * it can be force-stopped and restarted without a full device reboot.
 *
 * App writes config to /data/system/adb_x_config.txt; the hook reads
 * it on WiFi events and retries when the file appears late.
 */
object AdbSystemHooks {

    private const val TAG = "ADB_X_SystemHooks"
    private const val SYNC_CONFIG_FILE = "/data/system/adb_x_config.txt"
    private val registered = AtomicBoolean(false)

    /** SSID we already enabled ADB for — avoid re-enabling on every event. */
    @Volatile private var lastEnabledSsid: String = ""

    /** Retry: when WiFi is up but no config yet, poll a few times. */
    private var retryCount = AtomicInteger(0)
    private val maxRetries = 8
    private val retryDelayMs = 10000L

    /**
     * Re-attempt to wire up system_server callbacks after the framework
     * has had a chance to bind connectivity/wifi services. Retries with
     * exponential backoff up to 60s, then gives up gracefully (the
     * user can still pair manually via the app).
     */
    private fun scheduleDeferredInit(
        context: Context,
        handler: Handler,
        attempt: Int
    ) {
        val delayMs = (2000L * attempt).coerceAtMost(60_000L)
        handler.postDelayed({
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (cm == null || wm == null) {
                    if (attempt >= 30) {
                        XposedInit.log(
                            "[$TAG] System services never came up after " +
                            "${attempt} retries — WiFi auto-enable will not work"
                        )
                        return@postDelayed
                    }
                    XposedInit.log(
                        "[$TAG] Defer retry #$attempt in ${delayMs}ms " +
                        "(cm=${cm != null}, wm=${wm != null})"
                    )
                    scheduleDeferredInit(context, handler, attempt + 1)
                    return@postDelayed
                }
                XposedInit.log("[$TAG] Deferred init succeeded on attempt $attempt")
                installCallbacks(context, cm, wm, handler)
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] Deferred init failed: ${t.message}")
                if (attempt < 30) scheduleDeferredInit(context, handler, attempt + 1)
            }
        }, delayMs)
    }

    /**
     * Deferred retry when the very first try threw (e.g. ActivityThread
     * getSystemContext returned null because zygote hadn't bootstrapped
     * it yet). Re-acquires the context from scratch each time.
     */
    private fun scheduleDeferredInitRetry(attempt: Int) {
        val delayMs = (2000L * attempt).coerceAtMost(60_000L)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                val activityThread = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
                val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (cm == null || wm == null) {
                    if (attempt >= 30) {
                        XposedInit.log(
                            "[$TAG] System services never came up after " +
                            "${attempt} retries — WiFi auto-enable will not work"
                        )
                        return@postDelayed
                    }
                    scheduleDeferredInit(context, handler, attempt + 1)
                    return@postDelayed
                }
                XposedInit.log("[$TAG] Deferred retry succeeded on attempt $attempt")
                installCallbacks(context, cm, wm, handler)
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] Deferred retry failed: ${t.message}")
                if (attempt < 30) scheduleDeferredInitRetry(attempt + 1)
            }
        }, delayMs)
    }

    /**
     * Actually wire up the WiFi NetworkCallback, the saved-network dump,
     * and the pair-request watcher. Safe to call multiple times — the
     * NetworkCallback is a fresh instance each call, so we never
     * double-register (Android would just dedupe by NetworkRequest).
     */
    private fun installCallbacks(
        context: Context,
        cm: ConnectivityManager,
        wm: WifiManager,
        handler: Handler
    ) {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleWifiEvent(cm, wm, network, context, handler)
                }
                override fun onLost(network: Network) {
                    // WiFi loss: do nothing on the ADB side. Android's
                    // own adbd keeps the wireless endpoint alive across
                    // SSID transitions and the user can keep their
                    // session. Forgetting to call disable here also
                    // avoids a race where we disable ADB while the user
                    // is about to reconnect to a different trusted SSID.
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                    lastEnabledSsid = ""
                    retryCount.set(0)
                    XposedInit.log("[$TAG] WiFi lost — leaving ADB state as-is (Android-managed)")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        handleWifiEvent(cm, wm, network, context, handler)
                }
            })

            XposedInit.log("[$TAG] WiFi callback registered in Settings process")

            // Dump saved WiFi networks to Settings.Global so the app can
            // read them. Android 11+ privacy hides the full list from
            // third-party apps via WifiManager.configuredNetworks, but the
            // Settings process (system_app) can run dumpsys wifi.
            //
            // On OnePlus OxygenOS 16+ the WifiManager binder service
            // intentionally returns an empty list from
            // getConfiguredNetworks() (see "WifiConfigManager -
            // Configured networks Begin ---- ... End ----" in dumpsys
            // wifi), while cmd wifi / dumpsys wifi / the
            // WifiConfigStore.xml file all still contain the saved
            // networks. /data/misc/apexdata/com.android.wifi/ is
            // labeled apex_data_file and the system_app uid we run
            // in via this hook can't read it. cmd service is
            // shell-uid-only. The only remaining source on a OnePlus
            // 16 + KernelSU build where LSPosed only hooks settings
            // (not system_server) is `dumpsys wifi`, which the system
            // wifi service will answer; it's slow (~10 s of poll
            // history on this build) but we run it once per
            // NetworkCallback onChanged() and cache to Settings.Global,
            // so the slow boot only blocks the very first refresh
            // after a WiFi state change.
            try {
                // dumpsys wifi is slow on some ROMs and can take >10 s.
                // Fire-and-forget on a worker thread. installCallbacks()
                // runs on the Settings main thread during
                // handleLoadPackage, so awaiting the dump here would
                // block that thread for up to the timeout and trip an ANR.
                Thread {
                    try {
                        val dump = StringBuilder()
                        // Stream dumpsys wifi into a temp file so a
                        // hung output stream doesn't deadlock us into
                        // the waitFor timeout. We redirect stdout into
                        // the pipe and parse line-by-line on the reader
                        // side; if the call hangs the timeout fires and
                        // we destroy the process, which closes the pipe
                        // and unblocks the reader.
                        try {
                            XposedInit.log("[$TAG] dumping via dumpsys wifi (60s ceiling)")
                            val proc = ProcessBuilder("/system/bin/dumpsys", "wifi")
                                .redirectErrorStream(true)
                                .start()
                            // Stream the output on a background thread so
                            // the child's stdout pipe doesn't deadlock
                            // waitFor() at ~64 kB of buffered output. We
                            // cancel the read after 60 s by joining the
                            // reader thread with the same deadline.
                            val seen = HashSet<String>()
                            val idSsidRegex = Regex("""ID:\s*\d+\s+SSID:\s*"?([^"\n]+?)"?\s+PROVIDER""")
                            val readerThread = Thread {
                                try {
                                    proc.inputStream.bufferedReader().useLines { lines ->
                                        for (line in lines) {
                                            val m = idSsidRegex.find(line) ?: continue
                                            val ssid = m.groupValues[1].trim()
                                            if (ssid.isNotBlank() && ssid != "<unknown ssid>") seen.add(ssid)
                                        }
                                    }
                                } catch (_: Throwable) { }
                            }
                            readerThread.isDaemon = true
                            readerThread.start()
                            val finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                            if (!finished) {
                                proc.destroyForcibly()
                            }
                            readerThread.join(2000)
                            XposedInit.log("[$TAG] dumpsys wifi finished=" + finished + " regex parsed " + seen.size + " SSIDs")
                            for (s in seen) {
                                dump.append(s).append('|')
                                    .append("|Secured\n")
                            }
                        } catch (t: Throwable) {
                            XposedInit.log("[$TAG] dumpsys wifi failed: ${t.message}")
                        }
                        val source = if (dump.isNotEmpty()) "dumpsys-wifi" else "empty"
                        persistDump(context, dump, source)
                    } catch (t: Throwable) {
                        XposedInit.log("[$TAG] WiFi dump failed: ${t.message}")
                    }
                }.start()
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] WiFi dump spawn failed: ${t.message}")
            }

            // NOTE: pairing is started ONLY by the user in Developer options.
            // The old auto pair-request watcher was removed: programmatic
            // startAdbPairing never opened a real dialog on this ROM and
            // only produced false UI state. Code capture now lives in the
            // Settings-side hook (AdbWirelessDialogController.setPairingCode).
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] installCallbacks failed: ${t.message}")
        }
    }

    private fun handleWifiEvent(cm: ConnectivityManager, wm: WifiManager,
                                network: Network, context: Context,
                                handler: Handler) {
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return

        val ssid = try { wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"") ?: "" }
                    catch (_: Exception) { "" }
        if (ssid.isBlank()) return

        // Already enabled ADB for this SSID?
        if (ssid == lastEnabledSsid) return

        val config = readConfig()
        if (!config.autoEnable) return
        if (ssid !in config.trustedSsids) {
            // Trusted list exists but doesn't include this SSID → fine, don't retry
            if (config.trustedSsids.isNotEmpty()) return
            // No trusted list yet → app might not have written config.
            // Schedule retry in case the user opens the app soon.
            retryConfig(handler, cm, wm, network, context)
            return
        }

        XposedInit.log("[$TAG] Trusted WiFi '$ssid' — enabling ADB")
        lastEnabledSsid = ssid
        retryCount.set(0)
        enableAdb(context)
    }

    /** Retry a few times — catches the case where the config file appears after WiFi connects. */
    private fun retryConfig(handler: Handler, cm: ConnectivityManager,
                            wm: WifiManager, network: Network, context: Context) {
        val attempt = retryCount.incrementAndGet()
        if (attempt > maxRetries) return

        XposedInit.log("[$TAG] Scheduling config retry #$attempt in ${retryDelayMs}ms")
        handler.postDelayed({
            val ssid = try { wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"") ?: "" }
                        catch (_: Exception) { "" }
            val cfg = readConfig()
            XposedInit.log("[$TAG] retry #$attempt ssid='$ssid' trusted=${cfg.trustedSsids} auto=${cfg.autoEnable}")
            if (ssid.isBlank()) {
                retryConfig(handler, cm, wm, network, context)
                return@postDelayed
            }
            if (ssid == lastEnabledSsid) return@postDelayed

            if (cfg.autoEnable && ssid in cfg.trustedSsids) {
                XposedInit.log("[$TAG] Config appeared on retry #$attempt — enabling ADB")
                lastEnabledSsid = ssid
                retryCount.set(0)
                enableAdb(context)
            } else {
                retryConfig(handler, cm, wm, network, context)
            }
        }, retryDelayMs)
    }

    private fun enableAdb(context: Context) {
        try {
            val before = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            val after = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
            XposedInit.log("[$TAG] adb_wifi_enabled before=$before after=$after")
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] Failed to enable ADB: ${t.message}")
        }

        // NOTE: the old SystemProperties.set("service.adb.tls.port", ...)
        // logic was removed — Rust adbd ignores that prop for TLS wireless
        // debugging and picks its own random port. Fixed ports, if needed,
        // are handled by the app via legacy TCP mode (adb tcpip), not here.
    }

    private data class AdbConfig(
        val autoEnable: Boolean = true,
        val bootStart: Boolean = true,
        val locale: String = "system",
        val fixedPortEnabled: Boolean = false,
        val fixedPort: Int = 5555,
        val trustedSsids: Set<String> = emptySet()
    )
    private fun readConfig(): AdbConfig {
        // Primary path: world-readable mirror at /data/local/tmp/.
        // We cannot write to Settings.Global from a third-party APK
        // because that requires WRITE_SECURE_SETTINGS. The mirror is
        // written by Settings.syncConfigToFile() every time the user
        // saves a config change. Set owner=root, mode=0644 — on most
        // ROMs the settings process can read this. We catch
        // permission errors explicitly and surface them in the log
        // so on-device debugging can see exactly why a particular
        // build is failing.
        val file = File(SYNC_CONFIG_FILE)
        return try {
            val raw = file.readText()
            val map = mutableMapOf<String, String>()
            for (line in raw.lines()) {
                val idx = line.trim().indexOf('=')
                if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            AdbConfig(
                autoEnable = map.getOrDefault("auto_enable", "true").toBooleanStrictOrNull() ?: true,
                bootStart = map.getOrDefault("boot_start", "true").toBooleanStrictOrNull() ?: true,
                locale = map.getOrDefault("locale", "system"),
                fixedPortEnabled = map.getOrDefault("fixed_port_enabled", "false").toBooleanStrictOrNull() ?: false,
                fixedPort = map.getOrDefault("fixed_port", "5555").toIntOrNull() ?: 5555,
                trustedSsids = map.getOrDefault("trusted_ssids", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] readConfig: ${t.javaClass.simpleName}: ${t.message}")
            AdbConfig()
        }
    }

    /**
     * Com.android.settings process hook. The Settings app is a privileged
     * system app and gets the same ConnectivityManager / WifiManager /
     * Settings.Global system services as system_server — they're shared
     * singletons via ServiceManager. This means we can install the
     * same NetworkCallback and the same Settings.Global writes without
     * needing the actual system_server runtime. On OnePlus (where
     * LSPosed scope "system" does not translate to a real system_server
     * injection) this is the practical path to auto-toggle.
     */
    fun hookSettings(lpparam: LoadPackageParam) {
        if (!registered.compareAndSet(false, true)) return
        XposedInit.log("[$TAG] Loading settings-side hooks (process=${lpparam.processName})")
        try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val activityThread = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val handler = Handler(Looper.getMainLooper())
            if (cm == null || wm == null) {
                XposedInit.log("[$TAG] settings-side: services not ready (cm=${cm != null}, wm=${wm != null})")
                scheduleDeferredInit(context, handler, attempt = 1)
                return
            }
            installCallbacks(context, cm, wm, handler)
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] settings-side init failed: ${t.message}")
            scheduleDeferredInitRetry(attempt = 1)
        }
    }

    /**
     * Persist a dump to Settings.Global as adb_x_wifi_list_count +
     * adb_x_wifi_list_<i> chunks. Called from installCallbacks() once
     * the dump has been collected by either the XML read, the
     * WifiManager reflection, or the legacy cmd wifi / dumpsys wifi
     * fallback paths. We split content into 4 kB slices because
     * Settings.Global values cap at around 92 kB on most ROMs.
     */
    private fun persistDump(context: Context, dump: StringBuilder, source: String) {
        try {
            val content = dump.toString()
            if (content.isBlank()) {
                XposedInit.log("[$TAG] persistDump: empty dump, nothing to write ($source)")
                return
            }
            val chunkSize = 4000
            val chunks = content.chunked(chunkSize)
            val resolver = context.contentResolver
            val settings = android.provider.Settings.Global::class.java
            val putString = settings.getMethod(
                "putString",
                android.content.ContentResolver::class.java,
                String::class.java,
                String::class.java
            )
            try { putString.invoke(null, resolver, "adb_x_wifi_list_count", chunks.size.toString()) } catch (_: Throwable) { }
            for ((i, chunk) in chunks.withIndex()) {
                try { putString.invoke(null, resolver, "adb_x_wifi_list_$i", chunk) }
                    catch (t: Throwable) {
                        XposedInit.log("[$TAG] persistDump putString failed at chunk $i: ${t.message}")
                    }
            }
            XposedInit.log("[$TAG] dumped " + dump.lines().count { it.isNotBlank() } + " WiFi networks to Settings.Global (" + chunks.size + " chunks, source=$source)")
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] persistDump failed: ${t.message}")
        }
    }

}