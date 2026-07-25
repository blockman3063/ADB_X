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
        // System_server hook on stock AOSP — runs in the system_server
        // runtime where ConnectivityManager / WifiManager / Settings.Global
        // are bound. Some OEMs (OnePlus in particular) only inject
        // LSPosed modules into com.android.settings and never into
        // system_server, so we also call into AdbSystemHooks when the
        // process is the Settings app — it carries the same
        // ConnectivityService / WifiManager singletons as system_server
        // because they live in the OS framework, not the app process.
        // We log every entry path so on-device debugging can confirm
        // which one ran.
        val procName = lpparam.processName
        when {
            procName == "system_server" || procName.endsWith(":system_server") -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' → system_server hook")
                AdbSystemHooks.hook(lpparam)
            }
            // com.android.settings is the practical fallback on OnePlus.
            // The Settings app keeps the framework singletons, so the
            // hook still gets the real NetworkCallback dispatcher and the
            // real Settings.Global resolver — we are not running in a
            // sandbox.
            lpparam.packageName == "com.android.settings" -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' → settings-side system hook")
                SettingsHooks.hook(lpparam)
                AdbSystemHooks.hookSettings(lpparam)
            }
            // Stripped LSPosed scope: sometimes the framework injects the
            // module into the bare "android" package (uid 1000 framework
            // process — usually zygote-derived and same runtime as
            // system_server). Try once, otherwise stay silent so we
            // don't double-register when system_server also gets a
            // handleLoadPackage call.
            lpparam.packageName == "android" && procName != "system_server" -> {
                XposedBridge.log("[$TAG] handleLoadPackage: android proc='$procName' (system_server handled separately)")
            }
            else -> {
                XposedBridge.log("[$TAG] handleLoadPackage: procName='$procName' package='${lpparam.packageName}' — no hook")
            }
        }
    }
}
