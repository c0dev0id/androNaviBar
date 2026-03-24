package de.codevoid.andronavibar

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

        // ── Clock + date group — optically centred at ~40% from top ──────────
        //
        // A plain gravity=CENTER lands at 50% which feels low for a clock face.
        // Weighted spacers (top:2 / bottom:3) lift the group to the optical centre.

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }

        // Top spacer — weight 2
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 2f)
        })

        val clock = TextView(context).apply {
            textSize = 88f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            letterSpacing = -0.02f   // tighten the wide numerals slightly
        }
        clockView = clock
        column.addView(clock)

        // Orange accent rule beneath the time
        column.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(72), res.dpToPx(3)).apply {
                topMargin    = res.dpToPx(10)
                bottomMargin = res.dpToPx(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        column.addView(TextView(context).apply {
            text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
            textSize = 22f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            letterSpacing = 0.04f   // slightly open tracking for the date label
        })

        // Bottom spacer — weight 3
        column.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 3f)
        })

        root.addView(column)

        // ── Gear icon — top-right corner ──────────────────────────────────────

        val gear = FocusableButton(context).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(context.getColor(R.color.text_secondary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.TRANSPARENT
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
