package top.cbug.adbx.util

import android.content.Context
import android.util.Log
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.os.Build

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
     * Parse the "Wi-Fi:" lines of `dumpsys wifi`. Each entry looks
     * like:
     *   Wi-Fi: 90:e2:ba:c5:09:42, SSID = "Foo", BSSID = 90:e2:ba:c5:09:42,
     *          RSSI = -42, freq = 5220, level = 4, cap = ...
     *
     * Returns at most [limit] entries (newest first by virtue of
     * dumpsys ordering, which is best-effort sorted by last-scan).
     */
    fun parseVisibleNetworks(dumpsysOutput: String, limit: Int = 100): List<VisibleNetwork> {
        val result = mutableListOf<VisibleNetwork>()
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
            // 2.4 GHz: channels 1-14, freq 2412-2484; 5 GHz: >=5000.
            val is2g = freq != null && freq in 2400..2500
            result.add(VisibleNetwork(ssid = ssidClean, bssid = bssid,
                rssi = rssi, freq = freq ?: 0, is2g = is2g,
                lastSeenMs = System.currentTimeMillis()))
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Return currently-visible (in-range) networks with signal. Uses
     * `dumpsys wifi` via root and parses out the `Wi-Fi:` entries. Falls
     * back to an empty list if dumpsys is gated. ~280 ms typical.
     */
    fun scanVisibleNetworks(): List<VisibleNetwork> {
        val r = ShellUtils.executeSu("dumpsys wifi 2>&1", 3000)
        if (!r.isSuccess() || r.output.isBlank()) return emptyList()
        return parseVisibleNetworks(r.output)
    }

    /**
     * Return the network the OS reports as currently linked, with
     * RSSI pulled from the latest dumpsys. Returns null if not
     * connected to Wi-Fi.
     *
     * Approach: dumpsys reports one big line `mWifiInfo SSID: "...",
     * BSSID = ..., RSSI = ..., supplicant state: COMPLETED, ...`. We
     * grab SSID + BSSID + RSSI from there; the freqs are on a
     * separate line but we don't need them for the link-state card.
     */
    fun getConnectedNetwork(): VisibleNetwork? {
        val r = ShellUtils.executeSu("dumpsys wifi 2>&1", 3000)
        if (!r.isSuccess() || r.output.isBlank()) return null
        for (line in r.output.lines()) {
            val t = line.trim()
            if (!t.startsWith("mWifiInfo SSID")) continue
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
        // On modern Android the saved-network XML store at
        // /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml is
        // labelled system_file in SELinux. The app uid (u0_a38) can
        // not read it even via `su -c cat` — the su binary inherits
        // the calling process's context, and the su app_policy on
        // this ROM is restricted to a small set of safe commands.
        // Probing it adds 2 s × N SU_PATHS per path before failing,
        // so put cmd wifi first (which talks to system_server and is
        // not gated by the wifi config store SELinux label) and only
        // fall back to direct XML if cmd wifi returned nothing.
        //
        // 1. cmd wifi via su (fastest, ~30 ms for a 50-network list)
        val cmdNetworks = try { getSavedNetworksCmd() } catch (_: Exception) { emptyList() }
        if (cmdNetworks.isNotEmpty()) {
            Log.d(TAG, "Loaded " + cmdNetworks.size + " networks via cmd wifi")
            return cmdNetworks
        }
        Log.d(TAG, "cmd wifi path returned empty, falling through to XML...")

        // 2. dumpsys wifi parsing via root (used to be #3 but pulled
        // up here because XML is gated by SELinux on modern ROMs).
        if (ShellUtils.hasRoot()) {
            val rootDump = try { getSavedNetworksRootDumpsys() } catch (_: Exception) { emptyList() }
            if (rootDump.isNotEmpty()) {
                Log.d(TAG, "Loaded " + rootDump.size + " networks via dumpsys")
                return rootDump
            }
            Log.d(TAG, "dumpsys path returned empty")
        }

        // 3. Direct XML parsing (most reliable when it works, but
        // gated by SELinux on modern Android). Only attempt as a
        // last resort to avoid burning 6 s on a doomed probe.
        if (ShellUtils.hasRoot()) {
            val rootXml = try { getSavedNetworksRootXml() } catch (_: Exception) { emptyList() }
            if (rootXml.isNotEmpty()) {
                Log.d(TAG, "Loaded " + rootXml.size + " networks via XML")
                return rootXml
            }
            Log.d(TAG, "XML path returned empty")
        }

        // 4. Fallback: use WifiManager API (requires location permission)
        if (context != null) {
            val apiNetworks = try { getSavedNetworksApi(context) } catch (_: Exception) { emptyList() }
            if (apiNetworks.isNotEmpty()) {
                Log.d(TAG, "Loaded " + apiNetworks.size + " networks via WifiManager API")
                return apiNetworks
            }
            Log.d(TAG, "API path returned empty")
        }

        // 5. LSPosed-written file (system_server can read all WiFi configs
        // that the app domain cannot). Read /data/local/tmp/adb_x_wifi_list.
        val hookFile = try { getSavedNetworksFromHookFile() } catch (_: Exception) { emptyList() }
        if (hookFile.isNotEmpty()) {
            Log.d(TAG, "Loaded " + hookFile.size + " networks via hook file")
            return hookFile
        }
        Log.d(TAG, "hook file path returned empty")

        Log.w(TAG, buildString {
            appendLine("Cannot read saved networks - all methods failed")
            if (!ShellUtils.hasRoot()) appendLine("(no root)")
            appendLine("Check: su -c 'cmd wifi list-networks' 2>&1")
            appendLine("Check: su -c 'cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml' 2>&1")
        })
        return emptyList()
    }

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
        // Try via su first (capture stderr too by not using 2>/dev/null).
        // 2 s is plenty: cmd wifi on a 50-network list finishes in
        // ~300 ms; anything longer is a hang and we want to bail.
        if (ShellUtils.hasRoot()) {
            val suResult = ShellUtils.executeSu("cmd wifi list-networks 2>&1", 2000)
            if (suResult.isSuccess() && suResult.output.isNotBlank()) {
                val parsed = parseCmdWifiOutput(suResult.output)
                if (parsed.isNotEmpty()) return parsed
            }
            if (suResult.output.isNotBlank()) {
                Log.w(TAG, "cmd wifi via su returned: " + suResult.output.take(200))
            }
            // 尝试用 shell 用户运行
            val suShellResult = ShellUtils.executeSu("sh -c 'cmd wifi list-networks 2>&1'", 2000)
            if (suShellResult.isSuccess() && suShellResult.output.isNotBlank()) {
                val parsed = parseCmdWifiOutput(suShellResult.output)
                if (parsed.isNotEmpty()) return parsed
            }
            if (suShellResult.output.isNotBlank()) {
                Log.w(TAG, "cmd wifi via su/shell returned: " + suShellResult.output.take(200))
            }
        }
        // Fallback: run as app process
        val result = ShellUtils.execute("cmd wifi list-networks 2>&1", 2000)
        if (result.isSuccess() && result.output.isNotBlank()) {
            return parseCmdWifiOutput(result.output)
        }
        if (result.output.isNotBlank()) {
            Log.w(TAG, "cmd wifi (app) returned: " + result.output.take(200))
        }
        return emptyList()
    }

    private fun getSavedNetworksRootXml(): List<SavedWifi> {
        if (!ShellUtils.hasRoot()) return emptyList()
        val configPaths = listOf(
            // Ordered most-likely-first so we find a hit on the
            // first try on modern Android (Apex wifi config store)
            // and bail out fast on older devices (legacy /data/misc/wifi).
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc_ce/0/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc_ce/0/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/wpa_supplicant.conf",
            "/data/vendor/wifi/WifiConfigStore.xml",
            "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml"
        )

        val result = mutableMapOf<String, String>()
        for (path in configPaths) {
            // 2 s is plenty for cat'ing a < 100 KB XML. Anything
            // longer means the file doesn't exist or the shell call
            // is wedged — either way we want to bail, not wait 5 s.
            val r = ShellUtils.executeSu("cat '" + path + "' 2>&1", 2000)
            if (!r.isSuccess() || r.output.isBlank()) {
                if (r.output.isNotBlank() && !r.output.contains("No such file") && !r.output.contains("Permission denied")) {
                    Log.d(TAG, "Cannot read " + path + ": " + r.output.take(100))
                }
                // Once we found anything, stop probing alternate paths —
                // the user only needs one source of truth. Otherwise fall
                // through to the next path.
                if (result.isNotEmpty()) break
                continue
            }
            val xml = r.output

            var found = extractSsidFromWifiConfigStoreXml(xml, result)
            if (found > 0) {
                Log.d(TAG, "XML " + path + ": found " + found + " SSIDs")
            }

            if (path.contains("NetworkList") && found == 0) {
                // Try WifiConfigStore format with NetworkList element
                found = extractSsidFromNetworkListXml(xml, result)
                if (found > 0) {
                    Log.d(TAG, "NetworkList XML " + path + ": found " + found + " SSIDs")
                }
            }

            if (path.endsWith("wpa_supplicant.conf")) {
                for (match in Regex("""ssid="([^"]+)"""").findAll(xml)) {
                    val s = cleanSsid(match.groupValues[1])
                    if (s.isNotBlank() && s !in result) result[s] = "WPA"
                }
                if (result.isNotEmpty()) break
                continue
            }

            // Also look for any SSID="..." pattern
            for (match in Regex("""SSID\s*=\s*"([^"]+)"""").findAll(xml)) {
                val s = cleanSsid(match.groupValues[1])
                if (s.isNotBlank() && s !in result) result[s] = "Unknown"
            }

            // Bail out the moment we have something. On a 50-network
            // device this turns 35 s worst-case into ~2 s.
            if (result.isNotEmpty()) break
        }

        Log.d(TAG, "XML total: found " + result.size + " SSIDs")
        return result.map { SavedWifi(it.key, null, it.value) }
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

    /** Plain-text fallback used when getConfiguredNetworks returns 0 (Android 11+
     *  privacy policy hides the full list from third-party apps). The LSPosed
     *  system_server hook writes the list here on WiFi events. */
    private fun getSavedNetworksFromHookFile(): List<SavedWifi> {
        return try {
            val file = java.io.File("/data/local/tmp/adb_x_wifi_list")
            if (!file.exists() || !file.canRead()) return emptyList()
            val lines = file.readLines()
            lines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 3) return@mapNotNull null
                val ssid = parts[0].trim()
                if (ssid.isBlank()) return@mapNotNull null
                SavedWifi(ssid, parts[1].trim().ifBlank { null }, parts[2].trim())
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun parseSecurity(config: WifiConfiguration): String {
        return when {
            config.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.NONE) -> "Open"
            else -> "Secured"
        }
    }

    private fun getSavedNetworksRootDumpsys(): List<SavedWifi> {
        if (!ShellUtils.hasRoot()) return emptyList()
        val result = ShellUtils.executeSu("dumpsys wifi 2>&1 | grep -i -E 'SSID[=:]|ssid=' | head -100", 3000)
        if (!result.isSuccess() || result.output.isBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        val networks = mutableListOf<SavedWifi>()
        for (line in result.output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            for (pattern in arrayOf(
                Regex("""SSID[=:]\s*"?([^"\s,;]+)"""),
                Regex("""ssid\s*=\s*"?([^"\s,;]+)"""))) {
                val m = pattern.find(trimmed)
                if (m != null) {
                    val s = cleanSsid(m.groupValues[1])
                    if (s.isNotBlank() && s !in seen && s != "null" && s != "0x" && s != "<unknown ssid>") {
                        seen.add(s); networks.add(SavedWifi(s, null, "Unknown"))
                    }
                }
            }
        }
        return networks.toList()
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
