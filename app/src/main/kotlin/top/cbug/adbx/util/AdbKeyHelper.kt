package top.cbug.adbx.util

import android.util.Base64
import android.util.Log
import java.security.MessageDigest

/**
 * An ADB host key the device has already authorized, read from
 * /data/misc/adb/adb_keys. [fingerprint] is the same MD5 fingerprint
 * AOSP shows in the "Allow USB debugging?" dialog, so what the user
 * sees here can be matched against the system prompt.
 *
 * NOTE ON WHAT THIS IDENTIFIES: an ADB key, NOT a physical computer.
 * The key lives on the PC in ~/.android/adbkey; reinstalling the OS,
 * switching users, deleting the key or regenerating it changes the
 * identity, while copying ~/.android to another PC clones it. We say
 * this explicitly in the UI instead of pretending it is a hardware ID.
 */
data class AdbHostKey(
    val fingerprint: String,
    val comment: String,
)

/**
 * Reads the device's ADB authorized-host keys and computes trust
 * decisions for the wired (USB) tab.
 *
 * Why not USB VID/PID/serial (the old approach): when the phone is
 * connected to a PC it is the USB *device*, so /sys/bus/usb/devices
 * lists only what the phone enumerates as a *host* (OTG gadgets),
 * never the PC itself. There is no way for a USB device to read its
 * host's descriptors. ADB host keys are the only host identity
 * Android itself keeps — so we use those, and are honest that they
 * identify a key rather than a machine.
 */
object AdbKeyHelper {
    private const val TAG = "ADB_X_AdbKey"
    private const val ADB_KEYS_PATH = "/data/misc/adb/adb_keys"

    /**
     * List authorized host keys. Needs root (adb_keys is 0600 root).
     * Returns empty on no-root / no-file / parse failure — callers
     * treat that as "no authorized host", never as "trust everyone".
     */
    fun listAuthorizedHosts(): List<AdbHostKey> {
        val r = ShellUtils.executeSu("cat '$ADB_KEYS_PATH' 2>/dev/null", 2000)
        if (!r.isSuccess() || r.output.isBlank()) return emptyList()
        val out = mutableListOf<AdbHostKey>()
        for (line in r.output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val key = parts[0]
            if (key.length < 40) continue // not a base64 RSA public key
            val fp = fingerprint(key)
            if (fp.isEmpty()) continue
            val comment = if (parts.size > 1) parts[1].trim() else ""
            out.add(AdbHostKey(fingerprint = fp, comment = comment))
        }
        return out
    }

    /**
     * AOSP UsbDebuggingManager.getFingerprints(): MD5 over the decoded
     * public key, rendered as colon-separated uppercase hex. Matching
     * the system dialog lets users verify trust on their own screen.
     */
    fun fingerprint(base64PublicKey: String): String {
        return try {
            val bytes = Base64.decode(base64PublicKey, Base64.DEFAULT)
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            val hex = "0123456789ABCDEF"
            buildString {
                for (i in digest.indices) {
                    val v = digest[i].toInt() and 0xff
                    append(hex[v ushr 4])
                    append(hex[v and 0x0f])
                    if (i < digest.size - 1) append(':')
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fingerprint failed: ${t.message}")
            ""
        }
    }

    /**
     * True when at least one of the user's trusted fingerprints is
     * still present in the device's authorized-host list. Tying trust
     * to the live adb_keys file means "Revoke USB debugging
     * authorizations" in system Settings automatically invalidates
     * our trust too — a real lifecycle signal, unlike a static serial.
     */
    fun hasTrustedAuthorizedHost(trustedFingerprints: Set<String>): Boolean {
        if (trustedFingerprints.isEmpty()) return false
        return listAuthorizedHosts().any { it.fingerprint in trustedFingerprints }
    }
}
