package top.cbug.adbx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

    /** Heterogeneous row types. */
    sealed class Row {
        data class Section(val kind: WifiSection) : Row()
        data class Network(val entry: SavedWifi) : Row()
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_NETWORK = 1
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Network -> TYPE_NETWORK
    }

    fun update(newItems: List<Row>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Re-sort after the trusted set changed but the data didn't. */
    fun refresh() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionVH(inflater.inflate(R.layout.item_wifi_section, parent, false))
        } else {
            NetworkVH(inflater.inflate(R.layout.item_wifi, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is Row.Section -> (holder as SectionVH).bind(row.kind)
            is Row.Network -> (holder as NetworkVH).bind(row.entry)
        }
    }

    override fun getItemCount(): Int = items.size

    // ---- Section view holder: just shows the section title -----------

    inner class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvSectionTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        fun bind(kind: WifiSection) {
            val resId = when (kind) {
                WifiSection.CURRENT -> R.string.wifi_section_current
                WifiSection.SAVED -> R.string.wifi_section_saved
                WifiSection.AVAILABLE -> R.string.wifi_section_available
            }
            tvSectionTitle.text = tvSectionTitle.context.getString(resId)
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
            tvSignal.text = if (entry.signalDbm <= -127)
                tvSignal.context.getString(R.string.wifi_signal_unknown)
            else
                "${entry.signalDbm} dBm"

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
