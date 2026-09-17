package top.cbug.adbx.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import top.cbug.adbx.R

/**
 * A single horizontal "color block" status row: a small colored dot, a label, and
 * a value. Designed for at-a-glance status: ADB on/off, WiFi connected/disconnected,
 * pairing mode active/inactive, etc.
 *
 * Colors map to four states:
 *   OK       - green dot, light green pill background
 *   WARN     - orange dot, light orange pill background
 *   OFF      - grey dot, light grey pill background
 *   UNKNOWN  - red dot, light red pill background
 */
class StatusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class State { OK, WARN, OFF, UNKNOWN }

    private val card: MaterialCardView
    private val dot: android.view.View
    private val tvLabel: android.widget.TextView
    private val tvValue: android.widget.TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_status_indicator, this, true)
        orientation = VERTICAL
        card = findViewById(R.id.si_card)
        dot = findViewById(R.id.si_dot)
        tvLabel = findViewById(R.id.si_label)
        tvValue = findViewById(R.id.si_value)
    }

    fun setLabel(text: CharSequence) {
        tvLabel.text = text
    }

    fun setValue(text: CharSequence) {
        tvValue.text = text
    }

    fun setState(state: State) {
        val (dotColor, bgColor) = when (state) {
            State.OK -> R.color.status_ok to R.color.status_active_bg
            State.WARN -> R.color.status_warn to R.color.status_inactive_bg
            State.OFF -> R.color.status_off to R.color.status_off_bg
            State.UNKNOWN -> R.color.status_unknown to R.color.status_unknown_bg
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, dotColor))
        }
        dot.background = bg
        card.setCardBackgroundColor(ContextCompat.getColor(context, bgColor))
    }

    /** Delegate click to the card for proper Material ripple. */
    fun setCardOnClickListener(l: OnClickListener?) {
        card.setOnClickListener(l)
    }
}