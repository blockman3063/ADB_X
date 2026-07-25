package top.cbug.adbx.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import top.cbug.adbx.util.XposedStatus

class XposedInit : IXposedHookLoadPackage {
    companion object {
        const val MODULE_PACKAGE = "top.cbug.adbx"
        const val TAG = "ADB_X"

        /**
         * TODO: document log
         * @param String
         */
        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        /**
         * TODO: document log
         * @param Throwable
         */
        fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // Flip the in-process activation flag whenever the framework injects into us.
        // This is what the UI reads to render the "Xposed active" badge. Pass the
        // process context (when injecting into our own app) so markActive() can
        // write to SharedPreferences — works across classloader boundaries.
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                // Get the application context via the loaded ActivityThread class
                // (available as soon as Zygote spawns the app process).
                val atClass = lpparam.classLoader.loadClass("android.app.ActivityThread")
                val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
                val appCtx = atClass.getMethod("getApplication").invoke(currentAT)
                    as? android.content.Context
                if (appCtx != null) XposedStatus.init(appCtx)
            } catch (_: Throwable) { }
            XposedStatus.markActive()
        }
        // System_server is a Java process whose runtime package name varies by
        // ROM — AOSP uses "android", OnePlus uses "android", but the process
        // name always contains "system_server". Hook the system_server
        // process by name rather than by package — that's the runtime we
        // need ConnectivityManager / WifiManager / Settings.Global in.
        val procName = lpparam.processName
        when {
            procName == "system_server" || procName.endsWith(":system_server") -> {
                AdbSystemHooks.hook(lpparam)
            }
            lpparam.packageName == "android" && procName != "system_server" -> {
                // "android" package but not the system_server process — skip;
                // AdbSystemHooks.hook() will be invoked by the system_server
                // branch above. Some OnePlus builds invoke handleLoadPackage
                // for the "android" package in BOTH zygote-derived processes
                // (system_server is one of them), so the process-name check
                // is necessary to avoid running the hook twice.
                XposedBridge.log("[$TAG] handleLoadPackage: android proc='$procName' — skipping AdbSystemHooks")
            }
            lpparam.packageName == "com.android.settings" -> SettingsHooks.hook(lpparam)
        }
    }
}
