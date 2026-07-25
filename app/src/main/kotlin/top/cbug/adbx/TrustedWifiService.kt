package top.cbug.adbx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import de.robv.android.xposed.XposedBridge
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.WifiHelper

/**
 * Persistent auto-toggle daemon — owns a foreground notification so
 * the system keeps our process alive even when the user is not in
 * the app. This is what lets WiFi auto-toggle keep firing after the
 * app UI is closed: without it the LSPosed settings-side hook only
 * runs while the Settings process is up (which is short-lived after
 * the user navigates away).
 *
 * Responsibilities:
 *   1. Register a ConnectivityManager.NetworkCallback that fires
 *      whenever the active WiFi network comes up, goes down, or
 *      changes BSSID.
 *   2. Re-evaluate the trusted-SSID list against the current SSID.
 *   3. Call AdbHelper.enableWirelessAdb() / disableWirelessAdb().
 *   4. Mirror last-action state to SharedPreferences so the Status
 *      tab can render it on next open.
 *
 * The service is started by [WifiStateReceiver.fireOnce] every time
 * the receiver is invoked — including on BOOT_COMPLETED via
 * [BootReceiver] — and is intentionally self-restarting via
 * START_STICKY so it survives low-memory kills. The LSPosed
 * settings-side hook does the same work in parallel as a backup.
 */
class TrustedWifiService : Service() {

    companion object {
        private const val TAG = "ADB_X_Service"
        private const val CHANNEL_ID = "trusted_wifi_daemon"
        private const val NOTIF_ID = 1

        /**
         * Start the service if not already running. Safe to call from
         * receivers (boot / wifi state change / app foreground).
         */
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, TrustedWifiService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "start: ${t.message}")
            }
        }
    }

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "service created")
        registerWifiCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY makes the system restart us after a low-memory
        // kill with a null intent — we re-register the callback in
        // onCreate so that path is fine.
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            callback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trusted_wifi_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.trusted_wifi_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.trusted_wifi_notif_title))
            .setContentText(getString(R.string.trusted_wifi_notif_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerWifiCallback() {
        cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                WifiStateReceiver.fireOnce(this@TrustedWifiService)
            }
            override fun onLost(network: Network) {
                WifiStateReceiver.fireOnce(this@TrustedWifiService)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    WifiStateReceiver.fireOnce(this@TrustedWifiService)
            }
        }
        callback = cb
        try {
            cm!!.registerNetworkCallback(request, cb)
            Log.i(TAG, "NetworkCallback registered")
            // Eager evaluation on startup so the system picks up our
            // trust decision within 1 s of the service coming up.
            WifiStateReceiver.fireOnce(this)
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback: ${t.message}")
        }
    }
}
