package top.cbug.adbx.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import top.cbug.adbx.util.XposedStatus

class XposedInit : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "top.cbug.adbx"
        const val TAG = "ADB_X"

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Always flip the in-process activation flag when the framework
        // injects into us. This gives the app two independent observability
        // paths: (1) SharedPreferences written from any classloader, and
        // (2) a world-readable marker file visible from the app uid.
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                val atClass = lpparam.classLoader.loadClass("android.app.ActivityThread")
                val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
                val appCtx = atClass.getMethod("getApplication").invoke(currentAT)
                    as? android.content.Context
                if (appCtx != null) XposedStatus.init(appCtx)
            } catch (_: Throwable) { }
            XposedStatus.markActive(appContextFromParam(lpparam))
        }

        val procName = lpparam.processName
        when {
            procName == "system_server" || procName.endsWith(":system_server") -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' → system_server hook")
                AdbSystemHooks.hook(lpparam)
            }
            lpparam.packageName == "com.android.settings" -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' → settings-side system hook")
                SettingsHooks.hook(lpparam)
                AdbSystemHooks.hookSettings(lpparam)
            }
            lpparam.packageName == "android" && procName != "system_server" -> {
                XposedBridge.log("[$TAG] handleLoadPackage: android proc='$procName' (system_server handled separately)")
            }
            else -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' — no hook")
            }
        }
    }

    private fun appContextFromParam(lpparam: LoadPackageParam): android.content.Context? {
        return try {
            val atClass = lpparam.classLoader.loadClass("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
            val appCtx = atClass.getMethod("getApplication").invoke(currentAT)
            appCtx as? android.content.Context
        } catch (_: Throwable) {
            null
        }
    }
}
