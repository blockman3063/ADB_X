package top.cbug.adbx.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import top.cbug.adbx.store.Settings

/**
 * Apply the user's locale choice on top of the system locale. Used by
 * MainActivity.attachBaseContext to wrap the activity context before
 * any view is inflated, so every getString() call sees the right strings.
 *
 * Settings.locale values:
 *   "system" — follow the device's per-app language preference (which
 *              itself may follow the system locale on Android 12-,
 *              or the user's per-app setting on Android 13+).
 *   "en"     — force English; uses default values/ which is English.
 *   "zh"     — force Simplified Chinese; uses values-zh-rCN/.
 */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val tag = Settings.locale
        if (tag == "system") return base

        val locale = Locale(tag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
