package de.codevoid.andronavibar

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton

/**
 * Content pane for App Launcher buttons.
 *
 * Shows the app label, a large app icon, and a focusable "Start App" button.
 * The user presses LEFT to enter the pane, then CONFIRM to launch the app.
 */
class AppLauncherPaneContent(
    private val context: Context,
    private val packageName: String,
    private val label: String,
    private val onStartApp: () -> Unit
) : PaneContent {

    private var rootView: LinearLayout? = null
    private var startButton: FocusableButton? = null

    override fun load(onReady: () -> Unit) {
        onReady()
    }

    override fun hide() { rootView?.visibility = View.GONE }

    override fun show(container: ViewGroup) {
        rootView?.let { it.visibility = View.VISIBLE; return }

        val res = context.resources

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootView = root

        root.addView(TextView(context).apply {
            text = label
            textSize = 32f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                bottomMargin = res.dpToPx(16)
            }
        })

        val iconSize = res.dpToPx(128)
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = res.dpToPx(32)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        try {
            iconView.setImageDrawable(context.packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {}
        root.addView(iconView)

        val btn = FocusableButton(context).apply {
            text = context.getString(R.string.start_app)
            textSize = 22f
            cornerRadius = res.dpToPx(FocusableButton.CORNER_RADIUS_DP)
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(300), WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onStartApp() }
        }
        startButton = btn
        root.addView(btn)

        container.addView(root)
    }

    override fun unload() {
        val root = rootView ?: return
        rootView = null
        startButton = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** Returns true when the key was consumed (CONFIRM launches the app). */
    fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        66 -> { onStartApp(); true }
        else -> false
    }

    fun setInitialFocus() { startButton?.isFocusedButton = true }
    fun clearFocus()       { startButton?.isFocusedButton = false }
}
