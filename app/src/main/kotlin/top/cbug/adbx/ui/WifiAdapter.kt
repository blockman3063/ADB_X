package top.cbug.adbx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.util.SavedWifi
import top.cbug.adbx.util.WifiSection

/**
 * RecyclerView adapter for the full-screen wifi manager. Mirrors the
 * three-section layout of Android's own Wi-Fi settings panel:
 *
 *   ┌─ Connected ───────────────────────────┐
 *   │  ★ MyHomeWiFi  [saved]  WPA2-Psk    │
 *   │       -42 dBm                          │
 *   ├─ Saved networks ──────────────────────┤
 *   │  MyHomeWiFi     [saved]  WPA2-Psk   │
 *   │       -67 dBm                          │
 *   │  OfficeGuest    [saved]  Open         │
 *   │  BurgerKing                        │
 *   ├─ Other networks ─────────────────────┤
 *   │  CafeFree        Open                  │
 *   │       -88 dBm                          │
 *   │                                        │
 *   └────────────────────────────────────────┘
 *
 * Each section is rendered via a single TYPE_SECTION header row that
 * the recycler treats as a sibling of the network rows. The trusted
 * toggle is only visible for saved networks (open networks that
 * aren't a profile yet are visible-not-actionable until the user
 * long-presses to add — out of scope for the v1 wifi screen).
 */
class WifiAdapter(
    private val items: MutableList<Row> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Called when the user flips the trusted switch. */
    var onToggleTrusted: ((ssid: String, trusted: Boolean) -> Unit)? = null

    /** Called when the user taps a section header to fold / unfold
     *  its body. Passed the [WifiSection] identity plus the desired
     *  next state. The activity owns the boolean and re-binds. */
    var onToggleSection: ((kind: WifiSection) -> Unit)? = null

    /** Heterogeneous row types. */
    sealed class Row {
        data class Section(val kind: WifiSection, val isExpanded: Boolean) : Row()
        data class Network(val entry: SavedWifi) : Row()
        /** Non-clickable informational row inside a section —
         *  currently used when the saved list is empty on ROMs
         *  that block app-context cmd-wifi / root probes (OnePlus
         *  Android 16+) so the user can see the section exists. */
        data class Placeholder(val kind: WifiSection) : Row()
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_NETWORK = 1
        private const val TYPE_PLACEHOLDER = 2

        /** DiffUtil callback for the heterogeneous list. We treat
         *  rows as equivalent by (kind, ssid, isConnected) — same SSID
         *  with different RSSI reuses the same row so the toggle
         *  switch doesn't blink off-and-on across refreshes that
         *  briefly return a stale RSSI sentinel. */
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                when {
                    oldItem is Row.Section && newItem is Row.Section ->
                        oldItem.kind == newItem.kind
                    oldItem is Row.Network && newItem is Row.Network ->
                        oldItem.entry.ssid == newItem.entry.ssid &&
                            oldItem.entry.isConnected == newItem.entry.isConnected &&
                            oldItem.entry.isSaved == newItem.entry.isSaved
                    oldItem is Row.Placeholder && newItem is Row.Placeholder -> oldItem.kind == newItem.kind
                    else -> false
                }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }

    /** Compute the diff between an old and new row list and
     *  return the DiffUtil result. Pulled out so tests / callers
     *  can drive it without an adapter instance. */
    private class RowDiff(
        val diff: DiffUtil.DiffResult
    ) {
        fun dispatchUpdatesTo(adapter: WifiAdapter) = diff.dispatchUpdatesTo(adapter)
        companion object {
            fun calculate(old: List<Row>, new: List<Row>): RowDiff {
                val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = old.size
                    override fun getNewListSize(): Int = new.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                        DIFF.areItemsTheSame(old[oldPos], new[newPos])
                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                        DIFF.areContentsTheSame(old[oldPos], new[newPos])
                })
                return RowDiff(result)
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Network -> TYPE_NETWORK
        is Row.Placeholder -> TYPE_PLACEHOLDER
    }

    fun update(newItems: List<Row>) {
        val diff = RowDiff.calculate(items, newItems)
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    /** Re-sort after the trusted set changed but the data didn't. */
    fun refresh() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionVH(inflater.inflate(R.layout.item_wifi_section, parent, false))
            TYPE_PLACEHOLDER -> PlaceholderVH(inflater.inflate(R.layout.item_wifi_placeholder, parent, false))
            else -> NetworkVH(inflater.inflate(R.layout.item_wifi, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is Row.Section -> (holder as SectionVH).bind(row.kind, row.isExpanded)
            is Row.Network -> (holder as NetworkVH).bind(row.entry)
            is Row.Placeholder -> (holder as PlaceholderVH).bind(row.kind)
        }
    }

    override fun getItemCount(): Int = items.size

    // ---- Section view holder: title + chevron + click-to-toggle ----

    inner class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSectionTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        private val ivChevron: ImageView = view.findViewById(R.id.ivSectionChevron)

        fun bind(kind: WifiSection, isExpanded: Boolean) {
            val resId = when (kind) {
                WifiSection.CURRENT -> R.string.wifi_section_current
                WifiSection.SAVED -> R.string.wifi_section_saved
                WifiSection.AVAILABLE -> R.string.wifi_section_available
            }
            tvSectionTitle.text = tvSectionTitle.context.getString(resId)
            // Chevron points down for expanded sections, right for
            // collapsed ones. We rotate the same vector drawable
            // instead of swapping resources so the colour stays in
            // sync with the theme.
            ivChevron.rotation = if (isExpanded) 90f else 0f
            ivChevron.contentDescription = tvSectionTitle.context.getString(
                if (isExpanded) R.string.wifi_section_collapse
                else R.string.wifi_section_expand
            )
            // Re-bind the click handler every time the header is
            // re-rendered (cheap; same lambda target, fresh closure
            // over `kind`). The closure captures only [kind] — no
            // position lookup is needed because [onToggleSection]
            // operates on the section identity, not the row index.
            itemView.setOnClickListener {
                onToggleSection?.invoke(kind)
            }
        }
    }

    // ---- Placeholder view holder: non-clickable explainer row ----

    inner class PlaceholderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.tvPlaceholderText)
        fun bind(kind: WifiSection) {
            val resId: Int = when (kind) {
                WifiSection.SAVED -> R.string.wifi_saved_empty_hint
                WifiSection.CURRENT, WifiSection.AVAILABLE -> 0
            }
            tvText.text = if (resId == 0) "" else tvText.context.getString(resId)
        }
    }

    // ---- Network view holder: SSID / security / signal / trusted ----

    inner class NetworkVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivCheck: ImageView = view.findViewById(R.id.ivWifiConnectedCheck)
        private val tvSsid: TextView = view.findViewById(R.id.tvWifiSsid)
        private val chipSaved: TextView = view.findViewById(R.id.chipSaved)
        private val tvSecurity: TextView = view.findViewById(R.id.tvWifiSecurity)
        private val tvSignal: TextView = view.findViewById(R.id.tvWifiSignal)
        private val swTrusted: MaterialSwitch = view.findViewById(R.id.swTrusted)

        fun bind(entry: SavedWifi) {
            tvSsid.text = entry.ssid.ifBlank { "(unknown)" }
            tvSecurity.text = if (entry.security.isBlank()) "Unknown" else entry.security
            // Three signal states:
            //   - RSSI > -127 and we're scanning live: show the dBm
            //     number ("-44 dBm")
            //   - RSSI == -127 and the row is a saved profile that
            //     didn't show up in the latest scan cache: render
            //     "不在范围" — different semantics from "no signal"
            //   - RSSI == -127 and the row is in available-not-saved:
            //     just show the no-signal sentinel
            tvSignal.text = when {
                entry.signalDbm > -127 -> "${entry.signalDbm} dBm"
                entry.isSaved -> tvSignal.context.getString(R.string.wifi_out_of_range)
                else -> tvSignal.context.getString(R.string.wifi_signal_unknown)
            }

            // Connected badge
            ivCheck.visibility = if (entry.isConnected) View.VISIBLE else View.GONE

            // Saved chip
            chipSaved.visibility = if (entry.isSaved) View.VISIBLE else View.GONE

            // Trusted toggle: only meaningful for saved profiles.
            // On open networks we haven't joined yet there's nothing
            // to "trust" (auto-toggle fires on SSID, but the network
            // isn't in Settings.trustedSet). We still surface the row
            // so the user can see it exists and ask us to add support
            // in a later release.
            swTrusted.setOnCheckedChangeListener(null)
            swTrusted.isChecked = AppSettings.isTrusted(entry.ssid)
            swTrusted.isEnabled = entry.isSaved
            swTrusted.setOnCheckedChangeListener { _, isChecked ->
                onToggleTrusted?.invoke(entry.ssid, isChecked)
            }
        }
    }
}
