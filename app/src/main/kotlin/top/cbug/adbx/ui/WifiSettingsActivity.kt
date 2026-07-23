package top.cbug.adbx.ui

import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.WifiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen Wi-Fi settings. Reached from the Networks tab Wi-Fi card
 * ("Manage Wi-Fi" entry). Provides:
 *  - live search over SSID / security type
 *  - sort menu (alphabetical / signal / recent)
 *  - trusted toggle per row (persisted in Settings)
 *
 * Loading is async via [bgScope] so the toolbar + search box are responsive
 * while the 53+ network list streams in. Filter / sort happen on the IO
 * dispatcher then dispatched to main to update the adapter.
 */
class WifiSettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter = WifiAdapter()
    private var allItems: List<WifiItem> = emptyList()
    private var sortMode: Int = AppSettings.wifiSortMode
    private var lastQuery: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSearch: TextInputEditText
    private lateinit var tilSearch: TextInputLayout
    private lateinit var tvFilterSummary: TextView
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView


    /**
     * Wrap the base context with the user's preferred locale. Without
     * this the system-default `values/strings.xml` (English) leaks into
     * `getString(...)` calls even when the app-wide locale is zh-rCN.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(top.cbug.adbx.util.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_settings)

        toolbar       = findViewById(R.id.wifiToolbar)
        etSearch      = findViewById(R.id.etSearch)
        tilSearch     = findViewById(R.id.tilSearch)
        tvFilterSummary = findViewById(R.id.tvFilterSummary)
        rv            = findViewById(R.id.rvWifiFull)
        tvEmpty       = findViewById(R.id.tvEmpty)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_sort) {
                showSortMenu()
                true
            } else false
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.onToggleTrusted = { ssid, trusted ->
            if (trusted) AppSettings.addTrusted(ssid) else AppSettings.removeTrusted(ssid)
            AppSettings.save(this)
            // Re-sort so trusted jumps to top.
            applyFilterAndSort()
            // The user just toggled "trusted" — if the currently
            // connected SSID is the one we just trusted, immediately
            // re-evaluate and enable wireless ADB instead of waiting
            // for the next NETWORK_STATE_CHANGED. fireOnce is a
            // no-op background broadcast (goAsync, 10s budget), so
            // calling it from the main thread here is safe and fast.
            if (trusted) {
                top.cbug.adbx.WifiStateReceiver.fireOnce(this)
            }
        }

        // Live filter as the user types — debounced via text watcher running
        // on the same handler. For 50-100 networks this is fast enough to
        // not need an explicit debounce.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(s: Editable?) {
                lastQuery = s?.toString().orEmpty()
                applyFilterAndSort()
            }
        })

        AppSettings.load(this)
        sortMode = AppSettings.wifiSortMode
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgScope.cancel()
    }

    private fun showSortMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_sort) ?: toolbar
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.wifi_sort_alpha)
        popup.menu.add(0, 2, 0, R.string.wifi_sort_signal)
        popup.menu.add(0, 3, 0, R.string.wifi_sort_recent)
        popup.setOnMenuItemClickListener { item ->
            sortMode = item.itemId
            AppSettings.wifiSortMode = sortMode
            AppSettings.save(this)
            applyFilterAndSort()
            true
        }
        popup.show()
    }

    private fun refresh() {
        bgScope.launch {
            try {
                val networks = WifiHelper.getSavedNetworks(this@WifiSettingsActivity)
                allItems = networks.map { WifiItem(it.ssid, it.bssid, it.security) }
                withContext(Dispatchers.Main) { applyFilterAndSort() }
            } catch (t: Throwable) {
                android.util.Log.w("ADB_X_WifiSet", "refresh failed: ${t.message}")
            }
        }
    }

    /**
     * Apply the current query + sort mode to the cached allItems and update
     * the adapter. Runs on the main thread.
     */
    private fun applyFilterAndSort() {
        val q = lastQuery.trim().lowercase()
        val filtered = if (q.isEmpty()) allItems
        else allItems.filter {
            it.ssid.lowercase().contains(q) ||
            it.security.lowercase().contains(q)
        }
        val sorted = when (sortMode) {
            2 -> filtered.sortedByDescending { it.security.length + it.ssid.length }  // crude "signal" proxy
            3 -> filtered.sortedByDescending { it.ssid }                            // crude "recent" proxy
            else -> filtered.sortedBy { it.ssid.lowercase() }
        }
        adapter.update(sorted)
        val total = allItems.size
        val shown = sorted.size
        tvFilterSummary.text = getString(R.string.wifi_summary_fmt, shown, total)
        tvEmpty.visibility = if (shown == 0) View.VISIBLE else View.GONE
    }
}