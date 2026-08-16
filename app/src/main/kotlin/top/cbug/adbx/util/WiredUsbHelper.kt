package top.cbug.adbx.util

/**
 * A USB device as the kernel exposes it to us. The [id] field is
 * what we persist as the trust key — first preference is the
 * device serial, falling back to vendorId:productId for cables
 * that don't have a unique serial number.
 */
data class UsbDevice(
    val id: String,
    val vendor: String,
    val product: String,
)

/**
 * Detect currently-connected USB devices by reading /sys/bus/usb/devices/.
 * We can't use UsbManager.getDeviceList() from the app uid (no USB permission),
 * so the entire detection runs through `su -c cat …` over sysfs. Each call
 * is cheap (~40 ms in practice).
 */
object WiredUsbHelper {

    fun listDevices(): List<UsbDevice> {
        val r = ShellUtils.executeSu(
            "ls -1 /sys/bus/usb/devices/ 2>/dev/null | head -50",
            1500,
        )
        if (!r.isSuccess() || r.output.isBlank()) return emptyList()
        val devices = mutableListOf<UsbDevice>()
        for (busDir in r.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val full = "/sys/bus/usb/devices/$busDir"
            val probe = ShellUtils.executeSu(
                "cat '$full/idVendor' '$full/idProduct' '$full/serial' 2>/dev/null",
                1000,
            )
            if (!probe.isSuccess()) continue
            val lines = probe.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) continue
            val vendor = lines[0]
            val product = lines[1]
            val serial = if (lines.size >= 3) lines[2] else ""
            val id = when {
                serial.isNotEmpty() && serial != "<invalid>" -> "serial:$serial"
                else -> "vidpid:$vendor:$product"
            }
            devices.add(UsbDevice(id = id, vendor = vendor, product = product))
        }
        return devices
    }
}
