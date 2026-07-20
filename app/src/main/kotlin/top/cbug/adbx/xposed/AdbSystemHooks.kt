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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * System_server hooks — all ADB management logic lives here.
 *
 * App (UI only): writes config to /data/local/tmp/adb_x_config.txt
 * Hook: reads config on WiFi events + retry when file appears late.
 */
object AdbSystemHooks {

    private const val TAG = "ADB_X_SystemHooks"
    private const val CONFIG_PATH = "/data/local/tmp/adb_x_config.txt"
    private val registered = AtomicBoolean(false)

    /** SSID we already enabled ADB for — avoid re-enabling on every event. */
    @Volatile private var lastEnabledSsid: String = ""

    /** Retry: when WiFi is up but no config yet, poll a few times. */
    private var retryCount = AtomicInteger(0)
    private val maxRetries = 8
    private val retryDelayMs = 10000L

    /**
     * TODO: document hook
     * @param LoadPackageParam
     */
    fun hook(lpparam: LoadPackageParam) {
        if (!registered.compareAndSet(false, true)) return
        XposedInit.log("[$TAG] Loading system_server hooks")

        try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
            val activityThread = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val handler = Handler(Looper.getMainLooper())

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleWifiEvent(cm, wm, network, context, handler)
                }
                override fun onLost(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                    lastEnabledSsid = ""
                    retryCount.set(0)
                    val config = readConfig()
                    if (config.autoDisable) {
                        XposedInit.log("[$TAG] WiFi lost — disabling ADB")
                        disableAdb(context)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        handleWifiEvent(cm, wm, network, context, handler)
                }
            })

            XposedInit.log("[$TAG] WiFi callback registered — all ADB logic in system_server")

            // Dump saved WiFi networks to a world-readable file so the app
            // can read them. Android 11+ privacy hides the full list from
            // third-party apps via WifiManager.configuredNetworks, but
            // system_server has full visibility. We piggyback on the
            // existing WifiManager injection point.
            try {
                val wifiClass = XposedHelpers.findClass("android.net.wifi.WifiManager", null)
                val getNetworksMethod = wifiClass.getDeclaredMethod("getConfiguredNetworks")
                val wmInstance = context.getSystemService(Context.WIFI_SERVICE)
                val networks = getNetworksMethod.invoke(wmInstance) as? List<*> ?: emptyList<Any>()
                val sb = StringBuilder()
                for (net in networks) {
                    if (net == null) continue
                    val cls = net.javaClass
                    val ssid = try { (XposedHelpers.callMethod(net, "getSSID") ?: "").toString() } catch (_: Throwable) { "" }
                    val bssid = try { (XposedHelpers.callMethod(net, "getBSSID") ?: "").toString() } catch (_: Throwable) { "" }
                    sb.append(ssid.replace("\"", "")).append('|')
                      .append(bssid.replace("\"", "")).append('|')
                      .append("Secured").append('\n')
                }
                val tmp = java.io.File("/data/local/tmp/adb_x_wifi_list")
                tmp.writeText(sb.toString())
                java.io.File("/data/local/tmp/adb_x_wifi_list").setReadable(true, false)
                XposedInit.log("[$TAG] dumped " + networks.size + " WiFi networks")
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] WiFi dump failed: ${t.message}")
            }

            // 5. Pair-request watcher: app writes /data/local/tmp/adb_x_request_pair
            //    when the user taps "Start pairing"; we trigger
            //    AdbDebuggingManager.startAdbPairing() in system_server and
            //    capture the resulting port to /data/local/tmp/adb_x_pairing_port.
            try {
                startPairRequestWatcher(context, handler)
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] pair watcher start failed: ${t.message}")
            }
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] Init failed: ${t.message}")
            registered.set(false)
        }

        // Best-effort hook: try to catch the temporary ADB pairing port.
        // On Android 14+, the pairing port lives inside adbd's process
        // (no setprop), so we try hooking candidate dialog classes that
        // receive the port on construction. If none match this ROM we
        // silently skip — the user can still type the port manually in
        // the Status tab.
        hookPairingDialog()
    }

    /**
     * Poll /data/local/tmp/adb_x_request_pair every second. When the file
     * contains "1", trigger an AdbDebuggingManager startAdbPairing()
     * via reflection; the dialog-relevant port + code land in the same
     * pairing-port detector already running above. The file is deleted
     * after the request consumes so a single tap does not fire twice.
     */
    private fun startPairRequestWatcher(context: Context, handler: Handler) {
        val requestFile = File("/data/local/tmp/adb_x_request_pair")
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (requestFile.exists() && requestFile.length() > 0) {
                        val raw = requestFile.readText().trim()
                        XposedInit.log("[$TAG] pair-request detected: $raw")
                        try { requestFile.delete() } catch (_: Throwable) { }
                        if (raw == "1") triggerAdbPairing(context)
                    }
                } catch (t: Throwable) {
                    XposedInit.log("[$TAG] pair-request poll error: ${t.message}")
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(poll)
    }

    /**
     * Reflectively invoke AdbDebuggingManager.startAdbPairing(). The
     * method exists across AOSP and OnePlus reshuffles of class names; we
     * try a few candidates. Whatever the class, the pairing port + code
     * go through the existing hookPairingDialog detector.
     */
    private fun triggerAdbPairing(context: Context) {
        try {
            val managerCls = XposedHelpers.findClass(
                "com.android.server.adb.AdbDebuggingManager", null
            )
            val inst = XposedHelpers.callStaticMethod(managerCls, "getInstance")
                ?: XposedHelpers.newInstance(managerCls, context)
            val methodNames = arrayOf(
                "startAdbPairing",
                "startPairing",
                "startWirelessAdbPairing",
                "createAdbPairingService",
            )
            var invoked = false
            for (m in methodNames) {
                try {
                    XposedHelpers.callMethod(inst, m)
                    XposedInit.log("[$TAG] startAdbPairing invoked via $m")
                    invoked = true
                    break
                } catch (_: Throwable) { }
            }
            if (!invoked) {
                XposedInit.log("[$TAG] startAdbPairing: no matching method")
                Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c",
                    "am broadcast -a android.intent.action.ADB_PAIRING_PORT_CHANGED"
                ))
            }
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] triggerAdbPairing failed: ${t.message}")
        }
    }


    private fun hookPairingDialog() {
        // Candidate classes across Android versions. Each entry is
        // (className, fieldNameHoldingPort). On match, the value is
        // persisted to /data/local/tmp/adb_x_pairing_port for the app
        // to read via AdbHelper.getPairingPort().
        val candidates = listOf(
            "com.android.systemui.adb.AdbPairingDialog" to "mPort",
            "com.android.systemui.adb.AdbPairingDialog" to "mServicePort",
            "com.android.systemui.adb.AdbPairingDialog" to "port",
            "com.android.settingslib.wifi.AccessPoint" to "mPairingPort",
            "com.android.settings.wifi.WifiPairingDialog" to "mPort",
        )
        for ((className, fieldName) in candidates) {
            try {
                val cls = XposedHelpers.findClass(className, null)
                XposedHelpers.findField(cls, fieldName)
                XposedHelpers.findAndHookConstructor(cls, Any::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val port = XposedHelpers.getObjectField(param.thisObject, fieldName)?.toString()
                            if (!port.isNullOrBlank() && port != "0") {
                                writePairingPort(port)
                            }
                        } catch (_: Throwable) { }
                    }
                })
                XposedInit.log("[$TAG] Hooked $className.$fieldName")
            } catch (_: Throwable) {
                // Class or field not on this ROM — silently skip.
            }
        }
    }

    private fun writePairingPort(port: String) {
        try {
            val ctx = XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                ),
                "getSystemContext"
            ) as Context
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sh -c 'echo $port > /data/local/tmp/adb_x_pairing_port && chmod 666 /data/local/tmp/adb_x_pairing_port'"))
            XposedInit.log("[$TAG] pairing port written: $port")
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] writePairingPort failed: ${t.message}")
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
        enableAdb(context, config)
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
            if (ssid.isBlank()) return@postDelayed
            if (ssid == lastEnabledSsid) return@postDelayed

            val config = readConfig()
            if (config.autoEnable && ssid in config.trustedSsids) {
                XposedInit.log("[$TAG] Config appeared on retry #$attempt — enabling ADB")
                lastEnabledSsid = ssid
                retryCount.set(0)
                enableAdb(context, config)
            } else {
                retryConfig(handler, cm, wm, network, context)
            }
        }, retryDelayMs)
    }

    private fun enableAdb(context: Context, config: AdbConfig) {
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
            XposedInit.log("[$TAG] adb_wifi_enabled=1 OK")
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] Failed to enable ADB: ${t.message}")
        }

        if (config.fixedPortEnabled && config.fixedPort in 1024..65535) {
            try {
                val spClass = XposedHelpers.findClass("android.os.SystemProperties", null)
                val setMethod = spClass.getDeclaredMethod("set", String::class.java, String::class.java)
                setMethod.invoke(null, "service.adb.tls.port", config.fixedPort.toString())
                setMethod.invoke(null, "service.adb.tcp.port", config.fixedPort.toString())
                XposedInit.log("[$TAG] Fixed port set to ${config.fixedPort}")
            } catch (t: Throwable) {
                XposedInit.log("[$TAG] Fixed port failed (non-fatal): ${t.message}")
            }
        }
    }

    private fun disableAdb(context: Context) {
        try {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] Failed to disable ADB: ${t.message}")
        }
    }

    private data class AdbConfig(
        val autoEnable: Boolean = true,
        val autoDisable: Boolean = false,
        val bootStart: Boolean = true,
        val locale: String = "system",
        val fixedPortEnabled: Boolean = false,
        val fixedPort: Int = 5555,
        val trustedSsids: Set<String> = emptySet()
    )

    private fun readConfig(): AdbConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists() || !file.canRead()) return AdbConfig()
        return try {
            val map = mutableMapOf<String, String>()
            for (line in file.readLines()) {
                val idx = line.trim().indexOf('=')
                if (idx > 0) map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            AdbConfig(
                autoEnable = map.getOrDefault("auto_enable", "true").toBooleanStrictOrNull() ?: true,
                autoDisable = map.getOrDefault("auto_disable", "false").toBooleanStrictOrNull() ?: false,
                bootStart = map.getOrDefault("boot_start", "true").toBooleanStrictOrNull() ?: true,
                locale = map.getOrDefault("locale", "system"),
                fixedPortEnabled = map.getOrDefault("fixed_port_enabled", "false").toBooleanStrictOrNull() ?: false,
                fixedPort = map.getOrDefault("fixed_port", "5555").toIntOrNull() ?: 5555,
                trustedSsids = map.getOrDefault("trusted_ssids", "")
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            )
        } catch (t: Throwable) {
            XposedInit.log("[$TAG] readConfig: ${t.message}")
            AdbConfig()
        }
    }
}