package de.codevoid.andronavibar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard pane — shown when the fixed Dashboard button at the top of the
 * column is activated. Displays the current time, date, and (eventually)
 * weather. A gear icon in the top-right corner opens the global config pane.
 */
class DashboardPaneContent(
    private val context: Context
) : PaneContent {

    /** Invoked when the user activates the gear icon. */
    var onConfigRequested: (() -> Unit)? = null

    private var rootView: FrameLayout? = null
    private var gearButton: FocusableButton? = null
    private var clockView: TextView? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val res = context.resources

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootView = root

        // ── Centre column: clock + date ───────────────────────────────────────

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        val clock = TextView(context).apply {
            textSize = 80f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
        }
        clockView = clock
        center.addView(clock)

        center.addView(TextView(context).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            textSize = 26f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = res.dpToPx(8)
            }
        })

        root.addView(center)

        // ── Gear icon — top-right corner ──────────────────────────────────────

        val gear = FocusableButton(context).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(context.getColor(R.color.text_secondary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.button_inactive)
            )
            val size = res.dpToPx(56)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = res.dpToPx(12)
                marginEnd  = res.dpToPx(12)
            }
            setOnClickListener { onConfigRequested?.invoke() }
        }
        gearButton = gear
        root.addView(gear)

        clockHandler.post(clockRunnable)
        container.addView(root)
    }

    override fun unload() {
        clockHandler.removeCallbacks(clockRunnable)
        val root = rootView ?: return
        rootView = null
        gearButton = null
        clockView = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** CONFIRM activates the gear; all other keys fall through to MainActivity. */
    fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        66 -> { onConfigRequested?.invoke(); true }
        else -> false
    }

    fun setInitialFocus() { gearButton?.isFocusedButton = true }
    fun clearFocus()       { gearButton?.isFocusedButton = false }
}
