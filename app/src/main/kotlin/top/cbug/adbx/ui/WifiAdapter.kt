package top.cbug.adbx.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import top.cbug.adbx.R
import top.cbug.adbx.store.Settings as AppSettings

data class WifiItem(val ssid: String, val bssid: String?, val security: String)

/**
 * RecyclerView adapter for saved WiFi networks.
 *
 * Trusted networks are pinned to the top of the list, sorted
 * alphabetically within each group. The trusted-set lookup reads
 * live from AppSettings on every bind, so toggling the switch
 * immediately re-orders without needing to re-fetch the network list.
 */
class WifiAdapter(
    private val items: MutableList<WifiItem> = mutableListOf()
) : RecyclerView.Adapter<WifiAdapter.VH>() {

    /** Called when the user flips the trusted switch. */
    var onToggleTrusted: ((ssid: String, trusted: Boolean) -> Unit)? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSsid: TextView       = view.findViewById(R.id.tvWifiSsid)
        val tvSecurity: TextView   = view.findViewById(R.id.tvWifiSecurity)
        val swTrusted: MaterialSwitch = view.findViewById(R.id.swTrusted)
    }

    /**
     * TODO: document update
     * @param List<WifiItem>
     */
    fun update(newItems: List<WifiItem>) {
        items.clear()
        items.addAll(newItems)
        sortAndNotify()
    }

    /** Re-sort after the trusted set changed but the network list didn't. */
    fun refresh(trusted: Set<String>) {
        sortAndNotify()
    }

    private fun sortAndNotify() {
        items.sortWith(
            compareByDescending<WifiItem> { AppSettings.isTrusted(it.ssid) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ssid }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvSsid.text = item.ssid.ifBlank { "(unknown)" }
        holder.tvSecurity.text = item.security
        holder.swTrusted.setOnCheckedChangeListener(null)
        holder.swTrusted.isChecked = AppSettings.isTrusted(item.ssid)
        holder.swTrusted.setOnCheckedChangeListener { _, isChecked ->
            onToggleTrusted?.invoke(item.ssid, isChecked)
            // Re-sort so trusted jumps to the top.
            sortAndNotify()
        }
    }

    override fun getItemCount(): Int = items.size
}
