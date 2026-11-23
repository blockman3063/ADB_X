package top.cbug.adbx.xposed

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Hooks the wireless-debugging pairing dialog in the Settings main process
 * so the one-time 6-digit pairing code shown to the user can be surfaced in
 * our own UI (paired with the port read from `dumpsys adb`).
 *
 * Why these specific classes/methods:
 *   On this OnePlus (Android 15, OPlus ROM) the pairing dialog is
 *   `com.android.settings.development.AdbWirelessDialog`. Its controller
 *   receives the plain-text code via `setPairingCode(String)` — dexdump of
 *   Settings.apk confirmed the signature. The old code hooked non-existent
 *   classes and only single-String methods, so it never hooked anything.
 *
 * Transport: the injected Settings process cannot write /data/local/tmp
 * (SELinux) nor exec su, but it CAN write Settings.Global via reflection —
 * the same channel our WiFi-list dump uses successfully on this ROM. We
 * publish the code plus a timestamp; the app reads it with a TTL.
 *
 * The pairing SESSION itself is NEVER started by us. The user taps
 * "Pair device with pairing code" in Developer options; we only observe
 * the code once the system dialog displays it.
 */
object SettingsHooks {

    fun hook(lpparam: LoadPackageParam) {
        XposedInit.log("SettingsHooks: loading into ${lpparam.processName}")
        hookPairingCodeSetter(lpparam)
        hookPairingCode(lpparam)
    }

    /**
     * Primary hook: AdbWirelessDialogController.setPairingCode(String).
     * Called with the exact code rendered in the dialog.
     */
    private fun hookPairingCodeSetter(lpparam: LoadPackageParam) {
        val className = "com.android.settings.development.AdbWirelessDialogController"
        try {
            XposedHelpers.findAndHookMethod(
                className, lpparam.classLoader, "setPairingCode", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val code = param.args[0] as? String ?: return
                        if (!code.matches(Regex("\\d{6,8}"))) return
                        XposedInit.log("SettingsHooks: pairing code via setPairingCode = $code")
                        publishCode(contextOf(param.thisObject), code)
                    }
                })
            XposedInit.log("SettingsHooks: hooked AdbWirelessDialogController.setPairingCode")
        } catch (t: Throwable) {
            XposedInit.log("SettingsHooks: setPairingCode hook failed: ${t.message}")
        }
    }

    /** Fallback for other AOSP/ROM shapes where a single String carries the code. */
    private fun hookPairingCode(lpparam: LoadPackageParam) {
        val candidates = listOf(
            "com.android.settings.development.AdbWirelessDebuggingFragment",
            "com.android.settings.development.AdbWirelessDebuggingPreferenceController",
            "com.android.settings.development.AdbWirelessDialog"
        )
        for (className in candidates) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                for (method in clazz.declaredMethods) {
                    if (method.parameterCount == 1 && method.parameterTypes[0] == String::class.java) {
                        XposedHelpers.findAndHookMethod(clazz, method.name, String::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val arg = param.args[0] as? String ?: return
                                    if (arg.matches(Regex("\\d{6,8}"))) {
                                        XposedInit.log("SettingsHooks[$className.${method.name}] captured code: $arg")
                                        publishCode(contextOf(param.thisObject), arg)
                                    }
                                }
                            })
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    private fun contextOf(obj: Any?): Context? {
        return try {
            XposedHelpers.getObjectField(obj, "mContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /** Publish code + timestamp into Settings.Global for the app to consume. */
    private fun publishCode(context: Context?, code: String) {
        if (context == null) {
            XposedInit.log("SettingsHooks: no Context, cannot publish code")
            return
        }
        try {
            val settings = android.provider.Settings.Global::class.java
            val putString = settings.getMethod(
                "putString",
                android.content.ContentResolver::class.java,
                String::class.java,
                String::class.java
            )
            val resolver = context.contentResolver
            putString.invoke(null, resolver, "adb_x_hook_code", code)
            putString.invoke(null, resolver, "adb_x_hook_code_ts", System.currentTimeMillis().toString())
            XposedInit.log("SettingsHooks: published pairing code to Settings.Global")
        } catch (t: Throwable) {
            XposedInit.log("SettingsHooks publish failed: ${t.message}")
        }
    }
}
