package top.cbug.adbx.store

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlin.concurrent.thread

object Settings {
    private const val PREFS_NAME = "adb_x_settings"
    private const val SYNC_CONFIG_FILE = "/data/local/tmp/adb_x_config.txt"

    private const val KEY_FIXED_PORT_ENABLED = "fixed_port_enabled"
    private const val KEY_FIXED_PORT = "fixed_port"
    private const val KEY_TRUSTED_SSIDS = "trusted_ssids"
    private const val KEY_AUTO_ENABLE = "auto_enable"
    private const val KEY_AUTO_DISABLE = "auto_disable"
    private const val KEY_BOOT_START = "boot_start"
    private const val KEY_LOCALE = "locale"
    private const val KEY_WIFI_SORT = "wifi_sort"
    private const val KEY_WIRED_AUTO_ENABLE = "wired_auto_enable"
    private const val KEY_WIRED_AUTO_DISABLE = "wired_auto_disable"
    private const val KEY_TRUSTED_USB_SERIALS = "trusted_usb_serials"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var fixedPortEnabled = false
    @Volatile var fixedPort = 5555
    @Volatile var autoEnable = true
    @Volatile var autoDisable = false
    @Volatile var bootStart = true
    @Volatile var locale: String = "system"
    @Volatile var wifiSortMode: Int = 1   // "system" | "en" | "zh"
    // Wired-USB auto-toggle. Defaults mirror wireless: arm on
    // (autoEnable=true) but leave autoDisable off (you don't usually
    // want ADB turning off when you unplug).
    @Volatile var wiredAutoEnable = true
    @Volatile var wiredAutoDisable = false
    @Volatile var usbAdbEnabled = false

    private var trustedSsids: MutableSet<String> = mutableSetOf()
    private var trustedUsbSerials: MutableSet<String> = mutableSetOf()

    /**
     * TODO: document load
     * @param Context
     */
    fun load(context: Context) {
        val p = prefs(context)
        fixedPortEnabled = p.getBoolean(KEY_FIXED_PORT_ENABLED, false)
        fixedPort = p.getInt(KEY_FIXED_PORT, 5555)
        autoEnable = p.getBoolean(KEY_AUTO_ENABLE, true)
        autoDisable = p.getBoolean(KEY_AUTO_DISABLE, false)
        bootStart = p.getBoolean(KEY_BOOT_START, true)
        locale = p.getString(KEY_LOCALE, "system") ?: "system"
        wifiSortMode = p.getInt(KEY_WIFI_SORT, 1)
        trustedSsids = p.getStringSet(KEY_TRUSTED_SSIDS, emptySet())!!.toMutableSet()
        wiredAutoEnable = p.getBoolean(KEY_WIRED_AUTO_ENABLE, true)
        wiredAutoDisable = p.getBoolean(KEY_WIRED_AUTO_DISABLE, false)
        usbAdbEnabled = p.getBoolean("usb_adb_enabled", false)
        trustedUsbSerials = p.getStringSet(KEY_TRUSTED_USB_SERIALS, emptySet())!!.toMutableSet()
    }

    /**
     * TODO: document isTrusted
     * @param String
     */
    fun isTrusted(ssid: String): Boolean {
        val clean = sanitizeSsid(ssid)
        if (clean.isBlank()) return false
        return trustedSsids.contains(clean)
    }

    /**
     * TODO: document addTrusted
     * @param String
     */
    fun addTrusted(ssid: String) {
        val clean = sanitizeSsid(ssid)
        if (clean.isNotBlank()) trustedSsids.add(clean)
    }

    /**
     * TODO: document removeTrusted
     * @param String
     */
    fun removeTrusted(ssid: String) {
        trustedSsids.remove(sanitizeSsid(ssid))
    }

    fun trustedSet(): Set<String> = trustedSsids.toSet()

    fun trustedUsbSet(): Set<String> = trustedUsbSerials.toSet()

    /**
     * Mark the given USB device serial as trusted. Save to persist.
     * Trust assignment is independent of the wireless trust set —
     * a user is welcome to trust their laptop over USB but never the
     * coffee-shop Wi-Fi, and vice versa.
     */
    fun addTrustedUsb(serial: String) {
        val clean = serial.trim()
        if (clean.isNotEmpty() && clean != "unknown") trustedUsbSerials.add(clean)
    }

    fun removeTrustedUsb(serial: String) {
        trustedUsbSerials.remove(serial.trim())
    }

    fun isTrustedUsb(serial: String): Boolean {
        val s = serial.trim()
        return s.isNotEmpty() && s != "unknown" && trustedUsbSerials.contains(s)
    }

    /**
     * Persist settings to SharedPreferences and kick off the
     * world-readable config mirror write in a background thread
     * (the syncConfigToFile path uses su which would block main).
     */
    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_FIXED_PORT_ENABLED, fixedPortEnabled)
            .putInt(KEY_FIXED_PORT, fixedPort)
            .putBoolean(KEY_AUTO_ENABLE, autoEnable)
            .putBoolean(KEY_AUTO_DISABLE, autoDisable)
            .putBoolean(KEY_BOOT_START, bootStart)
            .putString(KEY_LOCALE, locale)
            .putInt(KEY_WIFI_SORT, wifiSortMode)
            .putStringSet(KEY_TRUSTED_SSIDS, trustedSsids)
            .putBoolean(KEY_WIRED_AUTO_ENABLE, wiredAutoEnable)
            .putBoolean(KEY_WIRED_AUTO_DISABLE, wiredAutoDisable)
            .putBoolean("usb_adb_enabled", usbAdbEnabled)
            .putStringSet(KEY_TRUSTED_USB_SERIALS, trustedUsbSerials)
            .apply()
        // Defer config sync to background thread to avoid su blocking main thread
        thread(name = "adb-x-sync") {
            syncConfigToFile()
        }
    }


    /** Sync settings to world-readable file for Xposed module fallback.
     *  Runs on a background thread - do NOT call from main thread. */
    private fun syncConfigToFile() {
        val content = buildString {
            appendLine("fixed_port_enabled=" + fixedPortEnabled)
            appendLine("fixed_port=" + fixedPort)
            appendLine("auto_enable=" + autoEnable)
            appendLine("auto_disable=" + autoDisable)
            appendLine("boot_start=" + bootStart)
            appendLine("locale=" + locale)
            appendLine("wifi_sort=" + wifiSortMode)
            appendLine("trusted_ssids=" + trustedSsids.joinToString(","))
            appendLine("wired_auto_enable=" + wiredAutoEnable)
            appendLine("wired_auto_disable=" + wiredAutoDisable)
            appendLine("usb_adb_enabled=" + usbAdbEnabled)
            appendLine("trusted_usb_serials=" + trustedUsbSerials.joinToString(","))
        }
        try {
            val file = File(SYNC_CONFIG_FILE)
            try {
                file.parentFile?.mkdirs()
                file.writeText(content)
                file.setReadable(true, false)
                return
            } catch (_: Exception) {
                // Need root to write to /data/local/tmp
            }
            // Fallback via su
            top.cbug.adbx.util.ShellUtils.executeSu(
                "echo '" + content.replace("'", "'\\''") + "' > " + SYNC_CONFIG_FILE + " && chmod 644 " + SYNC_CONFIG_FILE, 3000)
        } catch (_: Exception) { }
    }

    private fun sanitizeSsid(ssid: String): String {
        var s = ssid.trim()
        if (s.startsWith(""") && s.endsWith(""") && s.length >= 2) s = s.substring(1, s.length - 1)
        return s
    }
}
