package top.cbug.adbx.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Watches the connected Wi-Fi SSID and toggles wireless ADB
 * automatically when it matches one of [top.cbug.adbx.store.Settings.trustedSsids].
 *
 * This is the app-side implementation: it does NOT use any LSPosed
 * hook. The framework reports network availability to any app that
 * holds the NEARBY_WIFI_DEVICES permission, and the app gets the
 * current SSID through WifiManager — no root, no hook, no
 * accessibility event.
 *
 * Flow on onAvailable:
 *   1. Find the WiFi Network (TRANSPORT_WIFI + NET_CAPABILITY_NOT_VPN)
 *   2. Read current SSID via WifiManager
 *   3. Clean it (strip quotes, "<unknown ssid>")
 *   4. If Settings.autoEnable AND SSID is trusted → enable wireless ADB
 *   5. If Settings.autoDisable AND SSID is NOT trusted → disable wireless ADB
 *
 * The actual ADB-toggle call goes through [AdbHelper.enableWirelessAdb] /
 * [AdbHelper.disableWirelessAdb] which already handle root vs non-root
 * paths and the persist.adb.* port guard.
 *
 * We only register the callback when the app is in the foreground —
 * Android 14+ restricts background network callbacks, so there is no
 * point in keeping it alive when the user has the app closed. The
 * MainActivity recreates it on resume.
 */
class TrustedWifiWatcher(private val context: Context) {
    private val tag = "ADB_X_TrustWifi"
    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastSsid: String = ""
    private var lastTriggerSsid: String = ""
    private var lastTriggerMs: Long = 0L

    /** Toggle the most recent SSID we acted on. Empty = never acted. */
    fun lastAction(): String = lastTriggerSsid
    fun lastActionMs(): Long = lastTriggerMs

    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkAvailable(network)
            }
            override fun onLost(network: Network) {
                Log.d(tag, "onLost: network=" + network)
                // On network lost, if autoDisable is on we tear down ADB.
                handleNetworkLost()
            }
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities
            ) {
                // No-op — we re-read SSID on onAvailable only.
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
            Log.d(tag, "start: NetworkCallback registered")
            // Also pick up the SSID we're on right now (already-connected case).
            refreshCurrentSsid()
        } catch (t: Throwable) {
            Log.w(tag, "start: registerNetworkCallback failed", t)
        }
    }

    fun stop() {
        val cb = callback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) { /* might already be unregistered */ }
        callback = null
        Log.d(tag, "stop: NetworkCallback unregistered")
    }

    /** Public entry — re-read current SSID and act on it. Useful at boot /
     *  app resume when the network callback may have missed an event. */
    fun refreshCurrentSsid() {
        val ssid = readSsid()
        if (ssid.isBlank()) {
            Log.d(tag, "refreshCurrentSsid: no SSID yet")
            return
        }
        lastSsid = ssid
        evaluate(ssid, reason = "refresh")
    }

    private fun handleNetworkAvailable(network: Network) {
        val ssid = readSsid()
        if (ssid.isBlank()) {
            Log.d(tag, "onAvailable(" + network + "): empty SSID, skip")
            return
        }
        if (ssid == lastSsid) {
            Log.d(tag, "onAvailable(" + network + "): same SSID=" + ssid + ", skip")
            return
        }
        lastSsid = ssid
        evaluate(ssid, reason = "onAvailable(" + network + ")")
    }

    private fun handleNetworkLost() {
        val settings = top.cbug.adbx.store.Settings
        if (!settings.autoDisable) {
            Log.d(tag, "onLost: autoDisable=false, keeping ADB state")
            return
        }
        val adbHelper = top.cbug.adbx.util.AdbHelper
        Log.d(tag, "onLost: autoDisable=true, disabling wireless ADB")
        adbHelper.disableWirelessAdb()
    }

    private fun evaluate(ssid: String, reason: String) {
        val settings = top.cbug.adbx.store.Settings
        val trusted = settings.isTrusted(ssid)
        Log.d(tag, "evaluate: ssid=" + ssid + " trusted=" + trusted + " autoEnable=" + settings.autoEnable + " autoDisable=" + settings.autoDisable + " reason=" + reason)

        if (trusted && settings.autoEnable) {
            Log.i(tag, "trusted SSID " + ssid + ", enabling wireless ADB")
            top.cbug.adbx.util.AdbHelper.enableWirelessAdb()
            lastTriggerSsid = ssid
            lastTriggerMs = System.currentTimeMillis()
            return
        }
        if (!trusted && settings.autoDisable) {
            // Don't disable on roam to a non-trusted SSID until we also have
            // onLost semantics. The simplest guard: only act on the onAvailable
            // path when we previously had a different SSID — skip the
            // "transition unknown -> non-trusted" case to avoid false positives.
            Log.i(tag, "non-trusted SSID " + ssid + ", autoDisable=true (would disable)")
            // We DO disable here — settings.autoDisable is the user's explicit
            // "disconnect = kill ADB" choice.
            top.cbug.adbx.util.AdbHelper.disableWirelessAdb()
            lastTriggerSsid = ssid
            lastTriggerMs = System.currentTimeMillis()
            return
        }
        Log.d(tag, "evaluate: no action (trusted=" + trusted + " autoEnable=" + settings.autoEnable + " autoDisable=" + settings.autoDisable + ")")
    }

    /** Returns the clean SSID (no surrounding quotes, no <unknown ssid>). */
    private fun readSsid(): String {
        return try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo ?: return ""
            WifiHelper.cleanSsid(info.ssid)
        } catch (t: Throwable) {
            Log.w(tag, "readSsid failed", t)
            ""
        }
    }
}