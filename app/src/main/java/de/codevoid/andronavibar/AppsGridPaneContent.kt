package de.codevoid.andronavibar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AppsGridPaneContent(
    private val context: Context,
    private val apps:    List<AppEntry>
) : PaneContent {

    private var rootView:   FrameLayout? = null
    private var scrollView: ScrollView?  = null
    private var cells:      List<View>   = emptyList()
    private var focusIndex: Int          = 0
    private var columns:    Int          = 4

    /** Called once after the grid is built and visible. */
    var onContentReady: (() -> Unit)? = null

    // ── PaneContent ───────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        rootView = root
        container.addView(root)
        root.post { buildGrid(root) }
    }

    override fun unload() {
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView   = null
        scrollView = null
        cells      = emptyList()
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    fun handleKey(keyCode: Int): Boolean {
        if (apps.isEmpty()) return false
        return when (keyCode) {
            19 -> { moveFocus(focusIndex - columns); true }                          // UP
            20 -> { moveFocus(focusIndex + columns); true }                          // DOWN
            21 -> { if (focusIndex % columns > 0) moveFocus(focusIndex - 1); true } // LEFT
            22 -> {                                                                   // RIGHT
                if (focusIndex % columns < columns - 1 && focusIndex < apps.lastIndex) {
                    moveFocus(focusIndex + 1)
                    true
                } else false                                                          // edge → parent
            }
            66 -> { launchFocused(); true }
            else -> false
        }
    }

    // ── Grid construction ─────────────────────────────────────────────────────

    private fun buildGrid(root: FrameLayout) {
        columns = 4
        val tileW = root.width / columns

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        scrollView = scroll

        val col = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val builtCells = mutableListOf<View>()
        var rowLayout: LinearLayout? = null

        for (i in apps.indices) {
            if (i % columns == 0) {
                rowLayout = LinearLayout(context).apply {
                    orientation  = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                }
                col.addView(rowLayout)
            }
            val cell = buildCell(apps[i], i == focusIndex, tileW)
            val idx  = i
            cell.setOnClickListener { launchApp(apps[idx]) }
            rowLayout!!.addView(cell)
            builtCells.add(cell)
        }

        // Pad the last row with blank spacers so the row fills the width.
        val rem = apps.size % columns
        if (rem != 0) {
            repeat(columns - rem) {
                rowLayout?.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(tileW, tileW)
                })
            }
        }

        cells = builtCells
        scroll.addView(col)
        root.addView(scroll)
        scrollToFocused()
        onContentReady?.invoke()
        onContentReady = null
    }

    private fun buildCell(app: AppEntry, focused: Boolean, tileW: Int): View {
        val iconSize = (tileW * 0.55f).toInt()
        return LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(tileW, tileW)
            gravity      = Gravity.CENTER
            val p = dpToPx(6)
            setPadding(p, p, p, p)
            if (focused) foreground = makeFocusRing()

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    try { context.packageManager.getApplicationIcon(app.packageName) }
                    catch (_: Exception) { null }
                )
            })

            addView(TextView(context).apply {
                text         = app.label
                textSize     = 10f
                gravity      = Gravity.CENTER
                maxLines     = 2
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setTextColor(context.getColor(R.color.text_primary))
            })
        }
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    fun setInitialFocus() {
        cells.getOrNull(focusIndex)?.foreground = makeFocusRing()
    }

    fun clearFocus() {
        cells.getOrNull(focusIndex)?.foreground = null
    }

    private fun moveFocus(newIndex: Int) {
        val clamped = newIndex.coerceIn(0, apps.lastIndex)
        if (clamped == focusIndex) return
        cells.getOrNull(focusIndex)?.foreground = null
        focusIndex = clamped
        cells.getOrNull(focusIndex)?.foreground = makeFocusRing()
        scrollToFocused()
    }

    private fun scrollToFocused() {
        val sv   = scrollView ?: return
        val cell = cells.getOrNull(focusIndex) ?: return
        sv.post {
            val row = cell.parent as? View ?: return@post
            sv.smoothScrollTo(0, row.top)
        }
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private fun launchFocused() {
        launchApp(apps.getOrNull(focusIndex) ?: return)
    }

    private fun launchApp(app: AppEntry) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        context.startActivity(intent)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeFocusRing(): GradientDrawable = GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(FocusableButton.CORNER_RADIUS_DP).toFloat()
        setStroke(dpToPx(FocusableButton.STROKE_WIDTH_DP), context.getColor(R.color.colorPrimary))
        setColor(Color.TRANSPARENT)
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
