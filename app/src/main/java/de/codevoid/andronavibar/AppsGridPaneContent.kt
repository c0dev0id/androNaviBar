package de.codevoid.andronavibar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton

class AppsGridPaneContent(
    private val context: Context,
    private val apps:    List<AppEntry>
) : PaneContent {

    private var rootView:   FrameLayout?       = null
    private var scrollView: ScrollView?        = null
    private var cells:      List<SquareButton> = emptyList()
    private var focusIndex: Int               = 0
    private val columns:    Int               = 2

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
        val tileW = root.width / columns

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        scrollView = scroll

        val col = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val builtCells = mutableListOf<SquareButton>()
        var rowLayout: LinearLayout? = null

        for (i in apps.indices) {
            if (i % columns == 0) {
                rowLayout = LinearLayout(context).apply {
                    orientation  = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                }
                col.addView(rowLayout)
            }
            val cell = buildCell(apps[i], i, tileW)
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

    private fun buildCell(app: AppEntry, index: Int, tileW: Int): SquareButton {
        val iconSz = (tileW * 0.50f).toInt()
        val idx = index
        return SquareButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(tileW, tileW)
            insetBottom = 0
            insetTop = 0
            minimumHeight = 0
            minimumWidth = 0
            minHeight = 0
            minWidth = 0

            icon = try { context.packageManager.getApplicationIcon(app.packageName) }
                   catch (_: Exception) { null }
            iconGravity = MaterialButton.ICON_GRAVITY_TOP
            this.iconSize = iconSz
            iconTint = null
            iconPadding = dpToPx(4)

            text = app.label
            textSize = 12f
            setTextColor(context.getColor(R.color.text_primary))
            isAllCaps = false
            maxLines = 2
            gravity = Gravity.CENTER

            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = 0
            this.elevation = 0f
            stateListAnimator = null
            rippleColor = ColorStateList.valueOf(
                context.getColor(R.color.colorPrimary) and 0x33FFFFFF
            )

            isFocusedButton = (index == focusIndex)
            onFocusRequested = { moveFocus(idx) }
            setOnClickListener { launchApp(app) }
        }
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    fun setInitialFocus() {
        cells.getOrNull(focusIndex)?.isFocusedButton = true
    }

    fun clearFocus() {
        cells.getOrNull(focusIndex)?.isFocusedButton = false
    }

    private fun moveFocus(newIndex: Int) {
        val clamped = newIndex.coerceIn(0, apps.lastIndex)
        if (clamped == focusIndex) return
        cells.getOrNull(focusIndex)?.isFocusedButton = false
        focusIndex = clamped
        cells.getOrNull(focusIndex)?.isFocusedButton = true
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

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
