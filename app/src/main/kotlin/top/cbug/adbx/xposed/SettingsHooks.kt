package top.cbug.adbx.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

object SettingsHooks {

    fun hook(lpparam: LoadPackageParam) {
        XposedInit.log("SettingsHooks: loading into com.android.settings")
        hookPairingDialog(lpparam)
    }

    private fun hookPairingDialog(lpparam: LoadPackageParam) {
        val candidates = listOf(
            "com.android.settings.development.AdbWirelessDebuggingPreferenceController",
            "com.android.settings.development.AdbWirelessDebuggingFragment",
            "com.android.settings.development.AdbPairingDialogFragment"
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
                                    XposedInit.log("SettingsHooks[${method.name}] captured: $arg")
                                    if (arg.matches(Regex("\\d{6,8}"))) {
                                        writePairingCode(arg)
                                    }
                                }
                            })
                        XposedInit.log("SettingsHooks: hooked ${method.name} in $className")
                    }
                }
            } catch (_: Throwable) {
                /* class not found on this OEM / version */
            }
        }
    }

    private fun writePairingCode(code: String) {
        try {
            val dir = java.io.File("/data/local/tmp")
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, "adb_x_pairing_code").writeText(code)
            XposedInit.log("SettingsHooks: pairing code saved: $code")
        } catch (t: Throwable) {
            XposedInit.log(t)
        }
    }
}
