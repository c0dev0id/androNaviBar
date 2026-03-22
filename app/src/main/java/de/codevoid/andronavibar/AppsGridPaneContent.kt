package de.codevoid.andronavibar

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import de.codevoid.andronavibar.ui.LauncherButton

class AppsGridPaneContent(
    private val context: Context,
    private val apps:    List<AppEntry>
) : PaneContent {

    private var rootView:   FrameLayout?         = null
    private var scrollView: ScrollView?          = null
    private var cells:      List<LauncherButton> = emptyList()
    private var focusIndex: Int                  = 0
    private val columns:    Int                  = 2

    /** Called once after the grid is built and visible. */
    var onContentReady: (() -> Unit)? = null

    // ── PaneContent ─────────────────────────────────────────────────────────────

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

    // ── Key handling ────────────────────────────────────────────────────────────

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

    // ── Grid construction ───────────────────────────────────────────────────────

    private fun buildGrid(root: FrameLayout) {
        val marginPx = dpToPx(MARGIN_DP)
        val tileW = root.width / columns - marginPx * 2
        val tileH = root.height / VISIBLE_ROWS - marginPx * 2

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        scrollView = scroll

        val col = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val builtCells = mutableListOf<LauncherButton>()
        var rowLayout: LinearLayout? = null

        for (i in apps.indices) {
            if (i % columns == 0) {
                rowLayout = LinearLayout(context).apply {
                    orientation  = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                }
                col.addView(rowLayout)
            }
            val cell = buildCell(apps[i], i, rowLayout!!, tileW, tileH, marginPx)
            rowLayout.addView(cell)
            builtCells.add(cell)
        }

        // Pad the last row with blank spacers so the row fills the width.
        val rem = apps.size % columns
        if (rem != 0) {
            repeat(columns - rem) {
                rowLayout?.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
                        setMargins(marginPx, marginPx, marginPx, marginPx)
                    }
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

    private fun buildCell(
        app: AppEntry, index: Int, parent: ViewGroup,
        tileW: Int, tileH: Int, marginPx: Int
    ): LauncherButton {
        val cell = LayoutInflater.from(context).inflate(
            R.layout.launcher_button_item, parent, false
        ) as LauncherButton

        cell.layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        cell.buttonIcon = try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (_: Exception) { null }
        cell.text = app.label
        cell.isFocusedButton = (index == focusIndex)
        cell.onFocusRequested = { moveFocus(index) }
        cell.setOnClickListener { launchApp(app) }
        return cell
    }

    // ── Focus ───────────────────────────────────────────────────────────────────

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

    // ── Launch ──────────────────────────────────────────────────────────────────

    private fun launchFocused() {
        launchApp(apps.getOrNull(focusIndex) ?: return)
    }

    private fun launchApp(app: AppEntry) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        context.startActivity(intent)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val VISIBLE_ROWS = 6
        private const val MARGIN_DP = 4
    }
}
