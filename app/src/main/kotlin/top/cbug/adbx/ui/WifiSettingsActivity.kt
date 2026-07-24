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
import top.cbug.adbx.util.SavedWifi
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.util.WifiSection
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
    // Snapshot of the most-recent merged network list (current +
    // saved + available). Wi-Fi items have mutable signal-dBm and
    // connection flags that the wifi scan refresh writes; we keep
    // a copy here so the search box can re-filter without re-running
    // a dumpsys probe on every keystroke.
    private var allItems: List<SavedWifi> = emptyList()
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
            // The user just toggled "trusted" — immediately re-evaluate
            // the auto-toggle path so we don't have to wait for the next
            // NETWORK_STATE_CHANGED (which may not fire for many minutes
            // if the user keeps the device still).
            applyFilterAndSort()
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
                // Pull three independent views of the wifi world and
                // merge them into a single ordered list. The saved
                // profile list comes from the config store (the app
                // uid cannot read it directly; we route through cmd
                // wifi / dumpsys / sysfs). The visible scan comes from
                // dumpsys wifi so we can show in-range networks that
                // the user has not yet configured. The current interface
                // comes from the same dumpsys but is parsed separately
                // because it carries live RSSI rather than a scan-time
                // snapshot.
                val saved = WifiHelper.getSavedNetworks(this@WifiSettingsActivity)
                val visible = WifiHelper.scanVisibleNetworks()
                val connected = WifiHelper.getConnectedNetwork()
                val merged = WifiHelper.mergeForDisplay(saved, visible, connected)
                allItems = merged
                withContext(Dispatchers.Main) { applyFilterAndSort() }
            } catch (t: Throwable) {
                android.util.Log.w("ADB_X_WifiSet", "refresh failed: ${t.message}")
            }
        }
    }

    /**
     * Apply the current query + sort mode, splitting by section, and
     * emit rows the adapter can render as a heterogeneous list. Order
     * is:
     *   - Connected (if any) — signal desc within
     *   - Saved networks — trusted first, then alphabetical
     *   - Other networks (visible but not saved) — signal desc
     * Each section starts with a [Row.Section] header.
     */
    private fun applyFilterAndSort() {
        val q = lastQuery.trim().lowercase()
        val matches = { w: SavedWifi ->
            q.isEmpty() ||
                w.ssid.lowercase().contains(q) ||
                w.security.lowercase().contains(q)
        }
        val current = allItems.firstOrNull { it.isConnected }
        val saved = allItems.filter { it.isSaved && it != current && matches(it) }
            .sortedWith(
                compareByDescending<SavedWifi> { AppSettings.isTrusted(it.ssid) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ssid }
            )
        val available = allItems.filter { !it.isSaved && matches(it) }
            .sortedByDescending { if (it.signalDbm <= -127) -999 else it.signalDbm }
        // Sort dropdown: when user picks "Signal", flip the saved
        // section to signal-desc as well so the toggle does something.
        val savedFinal = if (sortMode == 2)
            saved.sortedByDescending { if (it.signalDbm <= -127) -999 else it.signalDbm }
        else saved

        val rows = mutableListOf<WifiAdapter.Row>()
        if (current != null) {
            rows.add(WifiAdapter.Row.Section(WifiSection.CURRENT))
            rows.add(WifiAdapter.Row.Network(current))
        }
        if (savedFinal.isNotEmpty()) {
            rows.add(WifiAdapter.Row.Section(WifiSection.SAVED))
            for (w in savedFinal) rows.add(WifiAdapter.Row.Network(w))
        }
        if (available.isNotEmpty()) {
            rows.add(WifiAdapter.Row.Section(WifiSection.AVAILABLE))
            for (w in available) rows.add(WifiAdapter.Row.Network(w))
        }
        adapter.update(rows)

        val total = allItems.size
        val shown = rows.count { it is WifiAdapter.Row.Network }
        tvFilterSummary.text = getString(R.string.wifi_summary_fmt, shown, total)
        tvEmpty.visibility = if (shown == 0) View.VISIBLE else View.GONE
    }
}