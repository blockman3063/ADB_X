package top.cbug.adbx.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.ScanResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One row in the wifi management screen. Captures everything the
 * Android Wi-Fi settings panel shows: identity, link state, signal,
 * and trust settings state. Sections in the screen are derived from
 * a list of these — see [WifiSection].
 */
data class SavedWifi(
    val ssid: String,
    val bssid: String?,
    val security: String,
    /**
     * RSSI in dBm. `-127` is the "no signal" sentinel. We do not
     * derive a 0-4 bar count here — the UI does it from a band table.
     */
    val signalDbm: Int = -127,
    /** True iff the OS reports this network as the currently linked
     *  interface (WifiManager.connectionInfo.ssid matches). */
    val isConnected: Boolean = false,
    /** True iff we have a saved profile for this SSID (we showed up
     *  in /data/misc/.../WifiConfigStore.xml or cmd wifi list). */
    val isSaved: Boolean = true,
    /** Last time we saw this network in a scan, in millis since epoch.
     *  -1 if we never saw it actively scanned (only saved-config). */
    val lastSeenMs: Long = -1L,
    /** True iff this is a 2.4 GHz BSSID. False if 5/6 GHz. Used as a
     *  hint for the band filter chip; Android's Wi-Fi panel does
     *  the same (separate 2.4 / 5 / 6 groups). */
    val is2g: Boolean = true,
)

/** Section kind, used by [WifiAdapter] to render a divider header
 *  before each subsection (matches the system Wi-Fi settings:
 *  Current network / Saved networks / Available networks). */
enum class WifiSection { CURRENT, SAVED, AVAILABLE }

/** A network that the kernel/wpa_supplicant reports as visible right
 *  now, or that the OS has linked. Distinct from [SavedWifi] (which
 *  represents a configured profile in /data/misc/.../WifiConfigStore)
 *  because a visible network is not necessarily saved — the UI
 *  caller decides whether to render it as "available, not yet
 *  configured" or fold it into the saved list if a profile exists.
 */
data class VisibleNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val freq: Int,
    val is2g: Boolean,
    val lastSeenMs: Long
)

object WifiHelper {

    private const val TAG = "ADB_X_WifiHelper"

    // External IP cache — avoid hitting api.ipify.org on every refresh
    @Volatile private var cachedExternalIp: String = ""
    @Volatile private var externalIpFetchedMs: Long = 0L
    private const val EXTERNAL_IP_TTL_MS = 10 * 60 * 1000L  // 10 min

    // Stable "this path can't work on this ROM" sticky bits. Set on
    // the first observed failure of each root-only path so we don't
    // keep re-probing a known-dead source across every refresh tick.
    // cmd wifi is intentionally NOT sticky-failed — cmd service can
    // rate-limit and then come back; failing it permanently would
    // prevent recovery.
    @Volatile private var dumpsysWifiUnavailable: Boolean = false
    @Volatile private var xmlWifiUnavailable: Boolean = false

    /** Max wall budget for the entire [getSavedNetworks] cascade.
     *  Without this the 5-path fallback chain burns ~10 s on ROMs
     *  where every path fails — well past our refresh tick interval. */
    private const val SAVED_NETWORKS_BUDGET_MS = 4_000L

    /**
     * TODO: document getSavedNetworks
     * @param Context
     */


    // ---------------- Scan / signal / connection-state awareness ----------------
    //
    // The plain "list of saved networks" path doesn't tell the user
    // about networks that are in range but not saved (e.g. the office
    // Wi-Fi a colleague once typed in), nor does it surface signal
    // strength. The follow methods parse `dumpsys wifi` to extract the
    // currently-linked interface and the set of visible networks
    // with their RSSI + frequency, so the wifi management UI can
    // render the same three sections that Android's own Wi-Fi panel
    // does: Currently connected / Saved / Available.

    /** RSSI band → bar count (0..4). Used by the adapter to pick a
     *  signal icon. We use the standard Android thresholds: noise
     *  floor ~-100, very weak -89, weak -79, good -69, strong -59,
     *  very strong >=-49. */
    fun rssiToBars(rssi: Int): Int = when {
        rssi >= -50 -> 4
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        rssi >= -80 -> 1
        else -> 0
    }

    /**
     * Parse visible networks out of `dumpsys wifi` output. Two
     * formats are in use across AOSP and OEM ROMs:
     *
     *  - AOSP / Pixel: a single line per network,
     *      Wi-Fi: aa:bb:..., SSID = "Foo", BSSID = ..., RSSI = -42, freq = 5220, ...
     *
     *  - OnePlus OxygenOS / OPlus framework: scan results are buried
     *    in the per-event log under `CMD_ONESHOT_RSSI_POLL` lines that
     *    look like
     *      ... what=CMD_ONESHOT_RSSI_POLL ... "SSID" bssid rssi=-39 f=5805 ...
     *    AOSP dumpsys output also surfaces connected-network RSSI
     *    via `mWifiInfo SSID: "..." BSSID: ... RSSI: ...` — that one
     *    we already parse in [parseConnectedNetwork].
     *
     * Returns at most [limit] entries. We dedupe by BSSID so a
     * network polled multiple times keeps one row.
     */
    fun parseVisibleNetworks(dumpsysOutput: String, limit: Int = 100): List<VisibleNetwork> {
        val result = mutableListOf<VisibleNetwork>()
        val seenBssid = linkedSetOf<String>()
        // AOSP path — works on stock AOSP / Pixel / Samsung.
        for (line in dumpsysOutput.lines()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("Wi-Fi:")) continue
            val ssid = Regex("""SSID\s*=\s*"([^"]*)"""").find(trimmed)?.groupValues?.getOrNull(1)
            val bssid = Regex("""BSSID\s*=\s*([0-9a-fA-F:]{17})""").find(trimmed)?.groupValues?.getOrNull(1)
            val rssi = Regex("""RSSI\s*=\s*(-?\d+)""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val freq = Regex("""(?:freq|frequency)\s*=\s*(\d+)""").find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (ssid.isNullOrBlank() || bssid.isNullOrBlank() || rssi == null) continue
            val ssidClean = cleanSsid(ssid)
            if (ssidClean.isBlank()) continue
            if (bssid !in seenBssid) {
                seenBssid += bssid
                val is2g = freq != null && freq in 2400..2500
                result.add(VisibleNetwork(ssid = ssidClean, bssid = bssid,
                    rssi = rssi, freq = freq ?: 0, is2g = is2g,
                    lastSeenMs = System.currentTimeMillis()))
                if (result.size >= limit) break
            }
        }
        if (result.isNotEmpty()) return result
        // OnePlus / OPlus path — fall through if AOSP path returned
        // nothing. OnePlus exposes last-poll results as
        // `CMD_ONESHOT_RSSI_POLL ... "ssid" bssid rssi=-N f=Mhz` —
        // multiple entries per network so we keep only the most
        // recent (last) per BSSID.
        for (line in dumpsysOutput.lines()) {
            if (!line.contains("CMD_ONESHOT_RSSI_POLL")) continue
            // "USER_A39876_5G" bssid rssi=-39 f=5805 ...
            val q = Regex(""""([^"\s]+)"\s+([0-9a-fA-F:]{17})\s+rssi\s*=\s*(-?\d+)\s+f\s*=\s*(\d+)""")
            val m = q.find(line) ?: continue
            val (ssidRaw, bssid, rssi, freq) = m.destructured
            if (bssid in seenBssid) continue
            seenBssid += bssid
            val ssidClean = cleanSsid(ssidRaw)
            if (ssidClean.isBlank()) continue
            val is2g = freq.toIntOrNull()?.let { it in 2400..2500 } ?: true
            result.add(VisibleNetwork(ssid = ssidClean, bssid = bssid,
                rssi = rssi.toIntOrNull() ?: -127, freq = freq.toIntOrNull() ?: 0,
                is2g = is2g,
                lastSeenMs = System.currentTimeMillis()))
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Single consolidated dump of `dumpsys wifi` that yields the
     * information for both [scanVisibleNetworks] and
     * [getConnectedNetwork]. We used to run dumpsys twice (once per
     * call) and the second invocation dominated the visible-scan
     * path because dumpsys serialises the print and the second
     * caller waited ~280 ms for nothing. A single shared dump runs
     * in ~280 ms total and feeds both parsers.
     */
    data class WifiSnapshot(
        val visible: List<VisibleNetwork>,
        val connected: VisibleNetwork?,
    )

    fun snapshotWifi(): WifiSnapshot {
        // We previously ran `dumpsys wifi 2>&1` here and parsed the
        // 21k-line payload for connected-network RSSI / scan entries.
        // On OnePlus OxygenOS the call consistently takes 6-8 s
        // (measured against logcat: dumpsys start → next useful line
        // is ~8 s later). That's a lot of UI time on every refresh
        // tick. The public WifiManager scanResults cache gives us
        // the same data — connected-network RSSI is not in it, but
        // `enrichConnectedWithScanCache` patches that in from the
        // same cache by BSSID. So skip dumpsys entirely and read
        // straight from the cache. Refresh wall time drops from
        // ~8 s to ~50 ms on this ROM.
        val cached = scanCacheVisible()
        val apiConnected = latestContext?.let { getConnectedNetworkFromApi(it) }
        return WifiSnapshot(
            visible = cached,
            connected = apiConnected?.let { enrichConnectedWithScanCache(it) }
                ?: cached.firstOrNull()
        )
    }

    /**
     * Apply the most recently seen [Context] so [scanCacheVisible] can
     * reach [WifiManager] without each call site passing it in. Called
     * from [getSavedNetworks] which always has the activity context
     * (it's the only path that needs context anyway), so we piggy-back
     * on it to populate [latestContext] for the next snapshot.
     */
    fun noteContext(ctx: Context?) {
        if (ctx != null) latestContext = ctx.applicationContext
    }
    @Volatile private var latestContext: Context? = null

    /**
     * Public enrichment pass — given a [VisibleNetwork] that came from
     * any source (dumpsys, public API, anywhere), if its RSSI is the
     * `-127` "no signal" sentinel try to backfill RSSI/freq from the
     * scanResults cache. Match strategy:
     *   1. Exact BSSID hit (case-insensitive)
     *   2. Same SSID, strongest RSSI
     * If both miss we return the input unchanged so the caller still
     * sees the API value. This keeps "Currently connected" rows from
     * showing "No signal" when scan cache has fresh data.
     */
    fun enrichConnectedWithScanCache(connected: VisibleNetwork): VisibleNetwork {
        if (connected.rssi > -127) return connected
        val ctx = latestContext ?: return connected
        val wm = try {
            ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Throwable) { null } ?: return connected
        val cache = rawScanResults(wm)
        if (cache.isEmpty()) return connected
        val hit = cache.firstOrNull { it.BSSID.equals(connected.bssid, ignoreCase = true) }
            ?: cache.filter { it.SSID.equals(connected.ssid, ignoreCase = true) }
                .maxByOrNull { it.level }
            ?: return connected
        val freq = hit.frequency
        Log.d(TAG, "enrichConnectedWithScanCache: hit ${connected.ssid} " +
            "bssid=${connected.bssid} rssi=${hit.level} freq=$freq")
        return connected.copy(
            rssi = hit.level,
            freq = freq,
            is2g = freq in 2400..2500
        )
    }

    /**
     * Pull visible networks from the public [WifiManager.getScanResults]
     * API. Requires location permission on most ROMs but no root.
     * Returns a list dedup'd by SSID (we keep the strongest BSSID per
     * SSID so signal bar counts make sense). Returns empty when the
     * scan cache is empty or the API throws SecurityException.
     */
    fun scanCacheVisible(): List<VisibleNetwork> {
        val ctx = latestContext ?: run {
            Log.d(TAG, "scanCacheVisible: no context")
            return emptyList()
        }
        val wm = try {
            ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Throwable) { null } ?: run {
            Log.d(TAG, "scanCacheVisible: no WifiManager")
            return emptyList()
        }
        val results: List<ScanResult> = try {
            wm.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            Log.w(TAG, "scanResults requires location permission")
            return emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "scanResults threw: " + (t.message ?: "unknown"))
            return emptyList()
        }
        Log.d(TAG, "scanCacheVisible: raw count=" + results.size)
        if (results.isEmpty()) return emptyList()
        // Dedup by SSID, keep strongest RSSI per SSID.
        val bestBySsid = linkedMapOf<String, ScanResult>()
        for (r in results) {
            val ssid = r.SSID ?: continue
            if (ssid.isBlank() || ssid == "<unknown ssid>") continue
            val cur = bestBySsid[ssid]
            if (cur == null || r.level > cur.level) bestBySsid[ssid] = r
        }
        return bestBySsid.values.map { r ->
            val freq = r.frequency
            VisibleNetwork(
                ssid = r.SSID,
                bssid = r.BSSID ?: "00:00:00:00:00:00",
                rssi = r.level,
                freq = freq,
                is2g = freq in 2400..2500,
                lastSeenMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Ask the framework to start a fresh scan. Returns true if the
     * framework accepted the request. The scan results arrive
     * asynchronously via [SCAN_RESULTS_AVAILABLE_ACTION]; callers
     * that need to read them should re-invoke [scanCacheVisible]
     * after a ~3 s delay.
     */
    fun requestScan(): Boolean {
        val ctx = latestContext ?: return false
        val wm = try {
            ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (_: Throwable) { null } ?: return false
        return try {
            @Suppress("DEPRECATION")
            wm.startScan()
        } catch (_: Throwable) { false }
    }

    /**
     * Return currently-visible (in-range) networks with signal. Uses
     * [snapshotWifi] internally so we share one dumpsys pass with
     * [getConnectedNetwork] callers. ~280 ms typical.
     */
    fun scanVisibleNetworks(): List<VisibleNetwork> = snapshotWifi().visible

    /**
     * Return the network the OS reports as currently linked, with
     * RSSI pulled from the latest dumpsys. Returns null if not
     * connected to Wi-Fi. Reads from the same shared dumpsys pass
     * as [scanVisibleNetworks] when both are needed.
     */
    fun getConnectedNetwork(): VisibleNetwork? = snapshotWifi().connected

    /**
     * Lightweight fallback that pulls the connected network via the
     * public WifiManager API rather than dumpsys wifi. Used when
     * su is not available (user toggled root off, KSU app_policy
     * denies, OnePlus GMS mode, etc.) — returns the connected SSID
     * with placeholder signal/freq so the wifi list still shows the
     * "Currently connected" section.
     *
     * RSSI/freq come from the WifiManager.scanResults cache when we
     * find a matching BSSID — without that the public API returns
     * rssi=-127 / freq=0 and the row renders "No signal" even
     * though the connected network is clearly in range.
     */
    @Suppress("DEPRECATION")
    fun getConnectedNetworkFromApi(ctx: android.content.Context): VisibleNetwork? {
        Log.d(TAG, "getConnectedNetworkFromApi: enter")
        return try {
            val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            val ssid = cleanSsid(info.ssid)
            if (ssid.isBlank()) return null
            val bssid = info.bssid ?: "00:00:00:00:00:00"
            // Pull scanResults once and look for a BSSID hit. If the
            // connected BSSID is in the cache use its RSSI/freq; if
            // not, fall back to any same-SSID scan result (best
            // RSSI). If still nothing, keep the API's -127/0 values.
            val scanCache = rawScanResults(wm)
            val cacheHit = scanCache.firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
                ?: scanCache.filter { it.SSID.equals(ssid, ignoreCase = true) }
                    .maxByOrNull { it.level }
            if (cacheHit != null) {
                val freq = cacheHit.frequency
                Log.d(TAG, "getConnectedNetworkFromApi: enriched $ssid via scanResults " +
                    "rssi=${cacheHit.level} freq=$freq")
                return VisibleNetwork(
                    ssid = ssid,
                    bssid = bssid,
                    rssi = cacheHit.level,
                    freq = freq,
                    is2g = freq in 2400..2500,
                    lastSeenMs = System.currentTimeMillis()
                )
            }
            Log.d(TAG, "getConnectedNetworkFromApi: returning $ssid (no scan cache hit)")
            VisibleNetwork(
                ssid = ssid,
                bssid = bssid,
                rssi = -127,
                freq = 0,
                is2g = true,
                lastSeenMs = System.currentTimeMillis()
            )
        } catch (_: Throwable) { null }
    }

    /**
     * Internal helper — returns the raw [ScanResult] list with no
     * SSID filtering. Used by [getConnectedNetworkFromApi] to enrich
     * the connected network's RSSI from the scan cache.
     */
    private fun rawScanResults(wm: WifiManager): List<ScanResult> {
        return try {
            wm.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            Log.w(TAG, "rawScanResults: location permission denied")
            emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * Pure parser for the mWifiInfo line — exposed so [snapshotWifi]
     * can parse it without re-running dumpsys.
     */
    private fun parseConnectedNetwork(dumpsysOutput: String): VisibleNetwork? {
        for (line in dumpsysOutput.lines()) {
            val t = line.trim()
            if (!t.startsWith("mWifiInfo SSID")) {
                if (line.contains("mWifiInfo")) Log.d(TAG, "skip: '" + t.take(40) + "'")
                continue
            }
            Log.d(TAG, "parseConnected: hit line len=" + t.length)
            val ssid = Regex("""SSID:\s*\"([^\"]*)\"""").find(t)?.groupValues?.getOrNull(1)
            val bssid = Regex("""BSSID:\s*([0-9a-fA-F:]{17})""").find(t)?.groupValues?.getOrNull(1)
            val rssi = Regex("""RSSI:\s*(-?\d+)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val freq = Regex("""(?:freq|frequency)\s*=\s*(\d+)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (ssid.isNullOrBlank() || bssid.isNullOrBlank()) return null
            val ssidClean = cleanSsid(ssid)
            return VisibleNetwork(
                ssid = ssidClean,
                bssid = bssid,
                rssi = rssi ?: -127,
                freq = freq ?: 0,
                is2g = freq != null && freq in 2400..2500,
                lastSeenMs = System.currentTimeMillis()
            )
        }
        return null
    }

    /**
     * Merge saved networks + visible networks + currently-connected
     * network into a single ordered list. The adapter then renders
     * section headers as it walks the list.
     *
     * Ordering:
     *   1. currently connected (signal bar count desc)
     *   2. saved networks not currently visible (alphabetical, trusted first)
     *   3. available (in-range but not saved) (signal desc)
     *
     * Caller should also pass through the saved set so trusted rows
     * can be styled differently. The [isSaved] / [isConnected]
     * fields drive the badges.
     */
    fun mergeForDisplay(
        saved: List<SavedWifi>,
        visible: List<VisibleNetwork>,
        connected: VisibleNetwork?
    ): List<SavedWifi> {
        val visibleBySsid = visible.associateBy { it.ssid }
        val result = mutableListOf<SavedWifi>()
        // 1. Current
        if (connected != null) {
            val savedMatch = saved.firstOrNull { it.ssid == connected.ssid }
            result.add(SavedWifi(
                ssid = connected.ssid,
                bssid = connected.bssid,
                security = savedMatch?.security ?: "Unknown",
                signalDbm = connected.rssi,
                isConnected = true,
                isSaved = savedMatch?.isSaved ?: true,
                lastSeenMs = connected.lastSeenMs,
                is2g = connected.is2g
            ))
        }
        // 2. Saved (skip if currently connected — it's already #1)
        val connectedSsid = connected?.ssid
        for (s in saved) {
            if (s.ssid == connectedSsid) continue
            val v = visibleBySsid[s.ssid]
            result.add(s.copy(
                signalDbm = v?.rssi ?: -127,
                lastSeenMs = v?.lastSeenMs ?: -1L,
                is2g = v?.is2g ?: true
            ))
        }
        // 3. Visible-not-saved (skip if currently connected)
        for (v in visible) {
            if (v.ssid == connectedSsid) continue
            if (saved.any { it.ssid == v.ssid }) continue
            result.add(SavedWifi(
                ssid = v.ssid,
                bssid = v.bssid,
                security = "Unknown",
                signalDbm = v.rssi,
                isConnected = false,
                isSaved = false,
                lastSeenMs = v.lastSeenMs,
                is2g = v.is2g
            ))
        }
        return result
    }

    fun getSavedNetworks(context: Context): List<SavedWifi> {
        Log.d(TAG, "getSavedNetworks: rootAvailable=" + ShellUtils.hasRoot() + " contextNull=" + (context == null))
        val budgetStart = System.currentTimeMillis()
        fun remaining(): Long = SAVED_NETWORKS_BUDGET_MS - (System.currentTimeMillis() - budgetStart)
        // Probe order is most-likely-first:
        //   1. cmd wifi (app process) — fastest when system_server's
        //      cmd service responds. On OnePlus Android 16+ the cmd
        //      service rejects untrusted_app context outright (rc=255
        //      + empty output), so this path returns nothing here.
        //      Shell user can still call cmd wifi via adb.
        //   2-3. dumpsys wifi + XML — root paths that go through
        //      su. On OnePlus the su binary is absent (we observed
        //      "su not found" on every invocation), so these paths
        //      are gated by sticky-fail bits. Once a refresh tick
        //      burns 6 s finding out they're dead, subsequent ticks
        //      skip them entirely. Root ROMs still take these
        //      branches on the very first probe.
        //   4. WifiManager.configuredNetworks — requires location
        //      permission, returns 0 on Android 14+ behind the
        //      privacy policy.
        //   5. hook file at /data/local/tmp/adb_x_wifi_list —
        //      written by the LSPosed system_server hook when the
        //      module is loaded; fast (~5 ms) and doesn't gate on
        //      cmd availability.
        //
        // The wall budget keeps the total probe under 4 s even
        // when every path fails. On OnePlus, after the first
        // sticky-fail tick, subsequent refreshes finish in <50 ms.
        if (remaining() > 0) {
            val cmdNetworks = try { getSavedNetworksCmd() } catch (_: Exception) { emptyList() }
            if (cmdNetworks.isNotEmpty()) {
                Log.d(TAG, "Loaded " + cmdNetworks.size + " networks via cmd wifi")
                return cmdNetworks
            }
            Log.d(TAG, "cmd wifi path returned empty, falling through...")
        }

        if (!dumpsysWifiUnavailable && ShellUtils.hasRoot() && remaining() > 0) {
            val rootDump = try { getSavedNetworksRootDumpsys() } catch (_: Exception) { emptyList() }
            if (rootDump.isNotEmpty()) {
                Log.d(TAG, "Loaded " + rootDump.size + " networks via dumpsys")
                return rootDump
            }
            Log.d(TAG, "dumpsys path returned empty")
            // On ROMs where su is missing (OnePlus OxygenOS without
            // Magisk/KernelSU), every dumpsys probe burns 6 s of
            // timeout before failing. Mark the path sticky the very
            // first time so subsequent ticks skip it in <1 ms.
            dumpsysWifiUnavailable = true
        }

        if (!xmlWifiUnavailable && ShellUtils.hasRoot() && remaining() > 0) {
            val rootXml = try { getSavedNetworksRootXml() } catch (_: Exception) { emptyList() }
            if (rootXml.isNotEmpty()) {
                Log.d(TAG, "Loaded " + rootXml.size + " networks via XML")
                return rootXml
            }
            Log.d(TAG, "XML path returned empty")
            xmlWifiUnavailable = true
        }

        if (context != null && remaining() > 0) {
            val apiNetworks = try { getSavedNetworksApi(context) } catch (_: Exception) { emptyList() }
            if (apiNetworks.isNotEmpty()) {
                Log.d(TAG, "Loaded " + apiNetworks.size + " networks via WifiManager API")
                return apiNetworks
            }
            Log.d(TAG, "API path returned empty")
        }

        if (remaining() > 0) {
            val hookFile = try { getSavedNetworksFromHookFile() } catch (_: Exception) { emptyList() }
            if (hookFile.isNotEmpty()) {
                Log.d(TAG, "Loaded " + hookFile.size + " networks via hook file")
                return hookFile
            }
            Log.d(TAG, "hook file path returned empty")
        }

        Log.w(TAG, "Cannot read saved networks - all methods failed within budget")
        return emptyList()
    }

    /** Set true the first time we observe "su not found" / "Permission
     *  denied" in a cmd-wifi attempt. The Activity shouldn't keep
     *  re-probing a path we already know is dead on this ROM. */
    @Volatile private var lastCmdWifiWasSuFailure: Boolean = false

    private fun parseCmdWifiOutput(output: String, errorOutput: String? = null): List<SavedWifi> {
        Log.d(TAG, "parseCmdWifiOutput: stdout=" + output.take(200) + if (errorOutput != null) " stderr=" + errorOutput.take(200) else "")
        val seen = linkedSetOf<String>()
        val result = mutableListOf<SavedWifi>()

        for (line in output.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.isBlank()) continue

            val lower = trimmed.lowercase()
            if (lower.startsWith("network id") || lower.startsWith("---") ||
                lower.startsWith("ssid") || lower.startsWith("security")) continue

            val parts = trimmed.split("\\s{2,}".toRegex())
            if (parts.size < 2) continue

            val ssidRaw = if (parts.size >= 3) {
                parts.drop(1).dropLast(1).joinToString(" ").trim()
            } else {
                parts[1].trim()
            }
            val security = if (parts.size >= 3) parts.last().trim() else "Unknown"

            val ssid = cleanSsid(ssidRaw)
            if (ssid.isBlank() || ssid == "null" || ssid == "0x" || ssid == "<unknown ssid>") continue
            if (ssid.length < 1 || !ssid.any { it.isLetterOrDigit() }) continue

            if (ssid !in seen) {
                seen.add(ssid)
                result.add(SavedWifi(ssid, null, security))
            }
        }
        return result
    }

    private fun getSavedNetworksCmd(): List<SavedWifi> {
        // Run cmd wifi in the app process. We try the direct
        // binary invocation (without going through sh -c) first
        // because OnePlus's toybox sh can fail to locate /system/bin/cmd
        // in the untrusted_app sandboxed PATH (it returns rc=255
        // + empty output instead of cmd service's real rc=20). If
        // the cmd service denies the call we get the real failure
        // and fall through to the LSPosed Settings.Global path.
        for (variant in listOf(
            arrayOf("/system/bin/cmd", "wifi", "list-networks"),
            arrayOf("sh", "-c", "PATH=/system/bin:/system/xbin:/vendor/bin cmd wifi list-networks 2>&1"),
            arrayOf("sh", "-c", "cmd wifi list-networks 2>&1"),
        )) {
            val result = try {
                val proc = ProcessBuilder(*variant)
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    continue
                }
                val out = proc.inputStream.bufferedReader().readText()
                ShellUtils.Result(proc.exitValue(), out)
            } catch (_: Throwable) { continue }
            if (result.isSuccess() && result.output.isNotBlank()) {
                val parsed = parseCmdWifiOutput(result.output)
                if (parsed.isNotEmpty()) return parsed
            }
            if (result.output.isNotBlank()) {
                Log.w(TAG, "cmd wifi (app) returned: " + result.output.take(200))
            }
        }
        return emptyList()
    }

    private fun getSavedNetworksRootXml(): List<SavedWifi> {
        // Direct XML parsing of /data/misc/apexdata/com.android.wifi/
        // WifiConfigStore.xml is doubly slow on every refresh: each
        // of the 7 candidate paths is `executeSu("cat ...", 500)`
        // → 500 ms each, totaling 3.5 s on the first tick before
        // the sticky-fail flips. We could narrow that with the
        // su-not-found probe, but the underlying premise (that
        // shell-level su can read the saved XML store) is broken
        // on every ROM we've tested: OnePlus returns "su not
        // found", and on Magisk/KernelSU ROMs the untrusted_app
        // context can't read /data/misc/apexdata/* even with su
        // because of the system_file SELinux label. The cmd-wifi
        // app-process path is the only one that actually works on
        // modern Android, so this method is now a no-op stub.
        return emptyList()
    }

    /** Parse WifiConfigStore.xml format (used on API 30+)
     *  Returns number of SSIDs found and populates result map. */
    private fun extractSsidFromWifiConfigStoreXml(xml: String, result: MutableMap<String, String>): Int {
        var count = 0

        // Pattern 1: <string name="SSID">"MyWiFi"</string>  (quoted)
        for (match in Regex("""<string\s+name="SSID">(.*?)</string>""").findAll(xml)) {
            var raw = match.groupValues[1].trim()
            raw = raw.removeSurrounding("\"").removeSurrounding("'").trim()
            val s = cleanSsid(raw)
            if (s.isNotBlank() && s !in result) {
                result[s] = detectSecurity(xml, s)
                count++
            }
        }
        if (count > 0) return count

        // Pattern 2: <string name="SSID">&quot;MyWiFi&quot;</string>
        for (match in Regex("""<string\s+name="SSID">&quot;(.*?)&quot;</string>""").findAll(xml)) {
            val s = cleanSsid(match.groupValues[1])
            if (s.isNotBlank() && s !in result) {
                result[s] = detectSecurity(xml, s)
                count++
            }
        }

        // Pattern 3: SSID="FreeWiFi" (without quotes inside value)
        for (match in Regex("""<string\s+name="SSID">([^<&]+)</string>""").findAll(xml)) {
            val s = cleanSsid(match.groupValues[1])
            if (s.isNotBlank() && s !in result) {
                result[s] = detectSecurity(xml, s)
                count++
            }
        }

        return count
    }

    /** Try to detect security type for a given SSID from the WifiConfigStore XML */
    private fun detectSecurity(xml: String, ssid: String): String {
        // Find the WifiConfiguration block containing this SSID
        val escapedSsid = ssid.replace("\"", "&quot;")
        val ssidIndex = xml.indexOf(escapedSsid)
        if (ssidIndex < 0) return "Unknown"

        val blockStart = xml.lastIndexOf("<WifiConfiguration>", ssidIndex)
        val blockEnd = if (blockStart >= 0) xml.indexOf("</WifiConfiguration>", blockStart) else -1
        val block = if (blockStart >= 0 && blockEnd > blockStart)
            xml.substring(blockStart, blockEnd) else xml

        return when {
            block.contains("KeyMgmt=NONE") || block.contains("KeyMgmt\" value=\"NONE") ||
                block.contains("open") || block.contains("owe") -> "Open"
            block.contains("SAE") || block.contains("sae") -> "WPA3"
            block.contains("WPA2") || block.contains("PSK") || block.contains("psk") -> "WPA2"
            block.contains("WPA") || block.contains("wpa") -> "WPA"
            block.contains("WEP") || block.contains("wep") -> "WEP"
            block.contains("SuiteB") || block.contains("suiteb") -> "SuiteB"
            else -> "Unknown"
        }
    }

    /** Alternative: extract from NetworkList XML format */
    private fun extractSsidFromNetworkListXml(xml: String, result: MutableMap<String, String>): Int {
        var count = 0
        // Look for <Network SSID="xxx">
        for (match in Regex("""<Network\s+SSID\s*=\s*"([^"]+)""").findAll(xml)) {
            val s = cleanSsid(match.groupValues[1])
            if (s.isNotBlank() && s !in result) {
                result[s] = "Unknown"
                count++
            }
        }
        return count
    }

    /** Use WifiManager.getConfiguredNetworks() API (deprecated but works on API 30-35)
     *  Requires ACCESS_FINE_LOCATION or NEARBY_WIFI_DEVICES permission. */
    private fun getSavedNetworksApi(context: Context): List<SavedWifi> {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        val configs: List<WifiConfiguration> = try {
            @Suppress("DEPRECATION")
            wm.configuredNetworks.toList()
        } catch (e: SecurityException) {
            Log.w(TAG, "getConfiguredNetworks requires location permission: " + e.message)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getConfiguredNetworks failed: " + e.message)
            return emptyList()
        }
        Log.d(TAG, "getConfiguredNetworks: got " + configs.size + " networks")
        if (configs.isEmpty()) return emptyList()
        val result = configs.mapNotNull { config ->
            val ssid = cleanSsid(config.SSID)
            if (ssid.isBlank()) return@mapNotNull null
            SavedWifi(ssid, config.BSSID, parseSecurity(config))
        }
        Log.d(TAG, "getSavedNetworksApi returning " + result.size + " networks")
        return result
    }

    /**
     * Plain-text fallback used when the saved-networks sources above
     * return 0 (e.g. cmd wifi is permission-gated on Android 14, the
     * SELinux-gated dumpsys path returns nothing, and the system_app
     * WifiManager API hides most profiles behind the privacy policy).
     *
     * The LSPosed system_server hook has full WifiManager visibility,
     * so it dumps a flat ssid|bssid|security list to
     * /data/system/adb_x_wifi_list (system_data_file label — the
     * untrusted_app SELinux context that we run in can read this).
     * This is the slowest fallback path but it works on every ROM
     * we've tested, including OnePlus where the XML and dumpsys paths
     * are blocked by SELinux labels.
     */
    private fun getSavedNetworksFromHookFile(): List<SavedWifi> {
     // First try the LSPosed-module-written Settings.Global slots.
     // On OnePlus Android 16 SELinux blocks every direct file
     // write path from the settings process (shell_data_file,
     // system_data_file, and system_file under /data/adb/lspd
     // are all EACCES for system_app uid 1000), so the module
     // stores the dump through Settings.Global instead and the
     // adbx app reads it back here.
     try {
         val ctx = latestContext
         if (ctx == null) {
             Log.d(TAG, "hook file path: no context, falling back to on-disk")
             return readHookFileCandidates()
         }
         val count = android.provider.Settings.Global.getString(ctx.contentResolver, "adb_x_wifi_list_count")
         Log.d(TAG, "hook file path: Settings.Global adb_x_wifi_list_count='$count'")
         val countInt = count?.toIntOrNull() ?: 0
         if (countInt <= 0) {
             Log.d(TAG, "hook file path: count=0, falling back to on-disk")
             return readHookFileCandidates()
         }
         val sb = StringBuilder()
         for (i in 0 until countInt) {
             val chunk = android.provider.Settings.Global.getString(ctx.contentResolver, "adb_x_wifi_list_$i")
             if (!chunk.isNullOrEmpty()) sb.append(chunk)
         }
         val raw = sb.toString()
         Log.d(TAG, "hook file path: Settings.Global read ${raw.length} bytes")
         if (raw.isBlank()) return readHookFileCandidates()
         val parsed = raw.lines().mapNotNull { line ->
             val parts = line.split("|")
             if (parts.size < 3) return@mapNotNull null
             val ssid = parts[0].trim()
             if (ssid.isBlank()) return@mapNotNull null
             SavedWifi(ssid, parts[1].trim().ifBlank { null }, parts[2].trim())
         }
         if (parsed.isNotEmpty()) return parsed
     } catch (t: Throwable) {
         Log.w(TAG, "hook file path: Settings.Global read failed: ${t.javaClass.simpleName}: ${t.message}")
     }
     return readHookFileCandidates()
 }

 /**
  * Fall back to the on-disk LSPosed hook file (older OnePlus
  * builds or pre-Settings.Global hook implementations). The
  * adbx app can read these on most ROMs because the kernel
  * label allows untrusted_app to read shell_data_file paths
  * (file mode 0666) even when it can't write them.
  */
 private fun readHookFileCandidates(): List<SavedWifi> {
     val candidates = listOf(
         "/data/adb/lspd/config/adb_x_wifi_list",
         "/data/system/adb_x_wifi_list",
         "/data/local/tmp/adb_x_wifi_list",
     )
     for (path in candidates) {
         val nets = try { readHookFile(path) } catch (_: Throwable) { emptyList() }
         if (nets.isNotEmpty()) return nets
     }
     return emptyList()
 }

    private fun readHookFile(path: String): List<SavedWifi> {
        val file = java.io.File(path)
        if (!file.exists() || !file.canRead()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val ssid = parts[0].trim()
            if (ssid.isBlank()) return@mapNotNull null
            SavedWifi(ssid, parts[1].trim().ifBlank { null }, parts[2].trim())
        }
    }

    private fun parseSecurity(config: WifiConfiguration): String {
        return when {
            config.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.NONE) -> "Open"
            else -> "Secured"
        }
    }

    private fun getSavedNetworksRootDumpsys(): List<SavedWifi> {
        if (!ShellUtils.hasRoot()) return emptyList()
        // dumpsys wifi on a system_server with 200+ networks returns
        // ~6-8 kB of text and a full OnePlus dump is ~21 kB / 6-8 s
        // of wall time — far too slow to run on every refresh tick.
        // We gave up trying to parse it: this method is now a
        // no-op stub kept for API compatibility. The cmd-wifi app
        // path is the only one that returns the saved list on
        // current Android (where dumpsys is allowed but heavy).
        return emptyList()
    }

    /**
     * TODO: document getCurrentSsid
     * @param Context
     */
    fun getCurrentSsid(context: Context): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return ""
        return try {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            cleanSsid(info.ssid)
        } catch (_: Throwable) { "" }
    }

    /**
     * TODO: document cleanSsid
     * @param String?
     */
    fun cleanSsid(ssid: String?): String {
        if (ssid == null) return ""
        var s = ssid.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) s = s.substring(1, s.length - 1).trim()
        if (s.startsWith("'") && s.endsWith("'") && s.length >= 2) s = s.substring(1, s.length - 1).trim()
        if (s == "<unknown ssid>" || s == "0x" || s == "null" || s.isBlank()) return ""
        return s
    }

    /** Get WiFi interface IPv4 address via root, falling back to WifiManager. */
    fun getLocalIpAddress(context: Context): String {
        // Fast root path
        val r = ShellUtils.executeSu("ip -4 addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print \$2}'", 2000)
        if (r.isSuccess()) {
            val ip = r.output.trim().removeSuffix("/24").removeSuffix("/16").trim()
            if (ip.isNotEmpty() && !ip.startsWith("0.") && !ip.startsWith("127.")) return ip
        }
        // Fallback via ifconfig
        val r2 = ShellUtils.executeSu("ifconfig wlan0 2>/dev/null | grep 'inet addr' | awk -F: '{print \$2}' | awk '{print \$1}'", 2000)
        if (r2.isSuccess()) {
            val ip = r2.output.trim()
            if (ip.isNotEmpty() && !ip.startsWith("0.") && !ip.startsWith("127.")) return ip
        }
        // Non-root fallback via WifiManager
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return ""
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo ?: return ""
            val ipInt = info.ipAddress ?: return ""
            val ip = String.format("%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff)
            if (ip.isNotEmpty() && !ip.startsWith("0.") && !ip.startsWith("127.")) return ip
        } catch (_: Exception) { }
        return ""
    }

    /** Fetch public IP from api.ipify.org (IO-bound, call on background thread).
     *  Caches result for 10 minutes to avoid hammering the API on every UI refresh. */
    fun getExternalIpAddress(): String {
        val now = System.currentTimeMillis()
        if (cachedExternalIp.isNotEmpty() && (now - externalIpFetchedMs) < EXTERNAL_IP_TTL_MS) {
            return cachedExternalIp
        }
        return try {
            val url = java.net.URL("https://api.ipify.org")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val ip = conn.inputStream.bufferedReader().readText().trim()
            if (ip.isNotEmpty()) {
                cachedExternalIp = ip
                externalIpFetchedMs = now
            }
            ip
        } catch (_: Exception) {
            // Keep stale cached value on failure rather than overwriting with empty
            cachedExternalIp.ifEmpty { "" }
        }
    }
}
