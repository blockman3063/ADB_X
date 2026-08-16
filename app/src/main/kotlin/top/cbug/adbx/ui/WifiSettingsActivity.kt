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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 *
 * Refresh strategy:
 *  - Manual: user taps the toolbar refresh action or the FAB.
 *  - Live: keeps refreshing while the activity is resumed; stops in
 *    onPause(). This keeps the 3-section view usable without re-entering
 *    from outside.
 *  - Stateful: per-tick refresh status is retained so the UI can render
 *    the last successful list, a loading state, or an error state. This
 *    avoids stale blank screens across orientation changes or quick tab
 *    switches.
 */
class WifiSettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val adapter = WifiAdapter()
    private var allItems: List<SavedWifi> = emptyList()
    // Saved-profile snapshot is sticky across refresh ticks — even when
    // a single refresh tick fails to read the saved list (cmd wifi
    // hiccup, API gated, etc.) we keep showing the last known saved
    // networks. Only a successful refresh with a non-empty saved list
    // overwrites this; a refresh that returns empty saved stays quiet.
    // Without this the "Saved networks" section flickers in/out across
    // the 30 s polling window.
    @Volatile private var stickySaved: List<SavedWifi> = emptyList()
    private var sortMode: Int = AppSettings.wifiSortMode
    private var lastQuery: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSearch: TextInputEditText
    private lateinit var tilSearch: TextInputLayout
    private lateinit var tvFilterSummary: TextView
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressLoading: View
    private lateinit var layoutError: View
    private lateinit var tvError: TextView
    private lateinit var btnRetry: View

    private var refreshPollJob: Job? = null
    private var scanResultsReceiver: BroadcastReceiver? = null
    @Volatile private var lastRefreshError: String? = null
    @Volatile private var lastRefreshSucceeded: Boolean = true

    /** Per-section fold state. The connected section never folds
     *  (a network you're on should always be visible) and the
     *  saved/available sections are user-toggled via chevron. We
     *  start everything expanded so the first refresh shows real
     *  data; the user collapses by tapping the section header. */
    private val sectionExpanded: MutableMap<WifiSection, Boolean> = mutableMapOf(
        WifiSection.CURRENT to true,
        WifiSection.SAVED to true,
        WifiSection.AVAILABLE to true
    )

    companion object {
        private const val MIN_REFRESH_INTERVAL_MS = 10_000L
        private const val DEFAULT_REFRESH_TICK_MS = 30_000L
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(top.cbug.adbx.util.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_settings)

        toolbar          = findViewById(R.id.wifiToolbar)
        etSearch         = findViewById(R.id.etSearch)
        tilSearch        = findViewById(R.id.tilSearch)
        tvFilterSummary  = findViewById(R.id.tvFilterSummary)
        rv               = findViewById(R.id.rvWifiFull)
        tvEmpty          = findViewById(R.id.tvEmpty)
        progressLoading  = findViewById(R.id.progressLoading)
        layoutError      = findViewById(R.id.layoutError)
        tvError          = findViewById(R.id.tvError)
        btnRetry         = findViewById(R.id.btnRetry)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> {
                    showSortMenu()
                    true
                }
                R.id.action_refresh -> {
                    refreshPollJob?.cancel()
                    startAutoRefresh()
                    true
                }
                else -> false
            }
        }

        findViewById<View?>(R.id.fabRefresh)?.setOnClickListener {
            refreshPollJob?.cancel()
            startAutoRefresh()
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.onToggleTrusted = { ssid, trusted ->
            if (trusted) AppSettings.addTrusted(ssid) else AppSettings.removeTrusted(ssid)
            AppSettings.save(this)
            applyFilterAndSort()
            if (trusted) {
                top.cbug.adbx.WifiStateReceiver.fireOnce(this)
            }
        }
        adapter.onToggleSection = { kind ->
            // The connected section folds the entire group is
            // meaningless while you're on it, but we still let the
            // user fold it (so the chevron behaves uniformly). The
            // header itself remains visible.
            sectionExpanded[kind] = !(sectionExpanded[kind] ?: true)
            applyFilterAndSort()
        }

        btnRetry.setOnClickListener {
            layoutError.visibility = View.GONE
            progressLoading.visibility = View.VISIBLE
            rv.visibility = View.GONE
            tvEmpty.visibility = View.GONE
            refreshPollJob?.cancel()
            startAutoRefresh()
        }

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
        // Feed the helper the activity context so scanCacheVisible()
        // can reach WifiManager.scanResults without us threading it
        // through every call site.
        WifiHelper.noteContext(this)
        // Register for SCAN_RESULTS_AVAILABLE so we re-refresh the
        // visible list once the framework finishes a scan triggered
        // by requestScan() below. Dumpsys returns nothing on this
        // ROM, so the scan cache is our only signal source.
        scanResultsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                // Re-pull after ~3 s — the framework reports this
                // intent before scanResults is fully populated.
                bgScope.launch {
                    delay(3_000L)
                    refresh()
                }
            }
        }
        registerReceiver(
            scanResultsReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        startAutoRefresh()
    }

    override fun onResume() {
        super.onResume()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        refreshPollJob?.cancel()
        refreshPollJob = null
    }

    override fun onDestroy() {
        refreshPollJob?.cancel()
        refreshPollJob = null
        scanResultsReceiver?.let { runCatching { unregisterReceiver(it) } }
        scanResultsReceiver = null
        bgScope.cancel()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Kick off a fresh framework scan on every entry. Without
        // this the cache ages out and the visible section empties.
        WifiHelper.requestScan()
    }

    private fun startAutoRefresh() {
        refreshPollJob?.cancel()
        refreshPollJob = bgScope.launch {
            refresh()
            delay(DEFAULT_REFRESH_TICK_MS.coerceAtLeast(MIN_REFRESH_INTERVAL_MS))
            while (isActive) {
                refresh()
                delay(DEFAULT_REFRESH_TICK_MS.coerceAtLeast(MIN_REFRESH_INTERVAL_MS))
            }
        }
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

    private fun showLoading() {
        // Don't re-show the spinner if we already have data on screen —
        // a 30 s auto-refresh tick shouldn't blank the list every cycle.
        // Only the first refresh on a freshly-opened page renders the
        // centered ProgressBar.
        if (adapter.itemCount > 0) return
        progressLoading.visibility = View.VISIBLE
        layoutError.visibility = View.GONE
        rv.visibility = View.GONE
        tvEmpty.visibility = View.GONE
    }

    private fun showError(message: String) {
        // Same rule: only show the full-screen error card if we don't
        // already have a list. If we do, keep the list and surface the
        // error in the summary line instead so the UI doesn't blink.
        if (adapter.itemCount > 0) {
            tvFilterSummary.text = message
            return
        }
        progressLoading.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
        tvError.text = message
        rv.visibility = View.GONE
        tvEmpty.visibility = View.GONE
    }

    private fun showList() {
        progressLoading.visibility = View.GONE
        layoutError.visibility = View.GONE
        val hasAny = adapter.itemCount > 0
        rv.visibility = if (hasAny) View.VISIBLE else View.GONE
        tvEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE
    }

    private fun refresh() {
        bgScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val savedDeferred = async {
                    WifiHelper.getSavedNetworks(this@WifiSettingsActivity)
                }
                val snapDeferred = async {
                    WifiHelper.snapshotWifi()
                }
                val saved = savedDeferred.await()
                val snap = snapDeferred.await()
                val connected = snap.connected
                    ?: WifiHelper.getConnectedNetworkFromApi(this@WifiSettingsActivity)
                if (saved.isNotEmpty()) stickySaved = saved
                val merged = WifiHelper.mergeForDisplay(stickySaved, snap.visible, connected)
                val t1 = android.os.SystemClock.elapsedRealtime()
                android.util.Log.d(
                    "ADB_X_WifiSet",
                    "refresh ok in ${t1 - t0}ms: saved=${saved.size} " +
                        "stickySaved=${stickySaved.size} visible=${snap.visible.size} " +
                        "connected=${connected?.ssid} merged=${merged.size}"
                )
                allItems = merged
                lastRefreshSucceeded = true
                lastRefreshError = null
                withContext(Dispatchers.Main) {
                    applyFilterAndSort()
                    showList()
                }
            } catch (t: Throwable) {
                val t1 = android.os.SystemClock.elapsedRealtime()
                android.util.Log.w(
                    "ADB_X_WifiSet",
                    "refresh failed after ${t1 - t0}ms: ${t.message}"
                )
                lastRefreshSucceeded = false
                val msg = t.message ?: getString(R.string.wifi_unknown_error)
                lastRefreshError = msg
                withContext(Dispatchers.Main) {
                    applyFilterAndSort()
                    showError(msg)
                }
            }
        }
    }

    /**
     * Apply the current query + sort mode, splitting by section, and
     * emit rows the adapter can render as a heterogeneous list. Order
     * is:
     *   - Connected (if any) — signal desc within
     *   - Other networks (visible but not saved) — signal desc
     *   - Saved networks — trusted first, then alphabetical
     * Saved is intentionally shown last because on this app a
     * typical device lists 50+ saved profiles (the OnePlus test
     * device dumps 53), which would otherwise push the live scan
     * results off the first screen.
     *
     * Each section starts with a [Row.Section] header. Folded
     * sections emit only the header; expanded sections emit the
     * header followed by every network in that group.
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
        val savedFinal = if (sortMode == 2)
            saved.sortedByDescending { if (it.signalDbm <= -127) -999 else it.signalDbm }
        else saved

        val adapterRows = mutableListOf<WifiAdapter.Row>()

        fun emit(
            section: WifiSection,
            rows: List<SavedWifi>,
            placeholder: WifiAdapter.Row.Placeholder? = null
        ) {
            // Always render the header — folding only hides the body.
            // The Placeholder row is treated as part of the body so
            // folding SAVED hides the empty-list hint as well.
            val expanded = sectionExpanded[section] ?: true
            adapterRows += WifiAdapter.Row.Section(section, expanded)
            if (expanded) {
                for (w in rows) adapterRows += WifiAdapter.Row.Network(w)
                placeholder?.let { adapterRows += it }
            }
        }

        // CURRENT always renders its header even with no current
        // network, so the user knows where the bar will appear once
        // the device connects. Passing a single-element list (or an
        // empty one) lets the same emitter handle the connected and
        // disconnected case.
        emit(WifiSection.CURRENT, listOfNotNull(current))
        emit(WifiSection.AVAILABLE, available)
        if (savedFinal.isEmpty()) {
            emit(
                WifiSection.SAVED,
                emptyList(),
                WifiAdapter.Row.Placeholder(WifiSection.SAVED)
            )
        } else {
            emit(WifiSection.SAVED, savedFinal)
        }

        val rows = adapterRows
        adapter.update(rows)

        val total = allItems.size
        val shown = rows.count { it is WifiAdapter.Row.Network }
        tvFilterSummary.text = getString(R.string.wifi_summary_fmt, shown, total)
    }
}
