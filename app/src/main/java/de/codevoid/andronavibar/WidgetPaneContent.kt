package de.codevoid.andronavibar

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView

/**
 * PaneContent that displays a pre-created AppWidgetHostView.
 *
 * The host view is created once in MainActivity.onCreate() and cached
 * for reuse — matching the standard launcher pattern.  show() adds it
 * to the container; unload() removes it without destroying the view,
 * so the widget host keeps delivering updates.
 *
 * After the view is attached, updateAppWidgetOptions communicates the
 * actual pane dimensions so the provider can pick the right layout.
 */
class WidgetPaneContent(
    private val context: Context,
    private val hostView: AppWidgetHostView,
    private val appWidgetId: Int
) : PaneContent {

    private var container: ViewGroup? = null

    /** Called once after the host view is attached and sized. */
    var onContentReady: (() -> Unit)? = null

    override fun load(onReady: () -> Unit) {
        onReady()
    }

    override fun show(container: ViewGroup) {
        this.container = container

        // Detach from any previous parent (e.g. a prior show/unload cycle)
        (hostView.parent as? ViewGroup)?.removeView(hostView)

        hostView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(hostView)

        // After the view is measured, send the actual pane dimensions and
        // poke collection adapters to reload.
        hostView.post {
            val mgr = AppWidgetManager.getInstance(context)
            val density = context.resources.displayMetrics.density
            val wDp = (hostView.width  / density).toInt()
            val hDp = (hostView.height / density).toInt()
            if (wDp > 0 && hDp > 0) {
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                }
                mgr.updateAppWidgetOptions(appWidgetId, opts)
            }

            for (id in findCollectionViewIds(hostView)) {
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, id)
            }

            onContentReady?.invoke()
            onContentReady = null
        }
    }

    override fun unload() {
        container = null
        (hostView.parent as? ViewGroup)?.removeView(hostView)
    }

    // ── Focus / key handling ─────────────────────────────────────────────────

    /**
     * True when the widget tree contains at least one focusable (or
     * click-promoted-to-focusable) child.  Used by enterPane() to decide
     * whether pressing LEFT should transfer focus into the widget.
     */
    fun hasFocusableContent(): Boolean {
        ensureFocusableChildren()
        return findFirstFocusable(hostView) != null
    }

    fun setInitialFocus() {
        ensureFocusableChildren()
        hostView.requestFocus()
    }

    fun clearFocus() {
        hostView.clearFocus()
    }

    fun handleKey(keyCode: Int): Boolean {
        val focused = hostView.findFocus() ?: return false
        return when (keyCode) {
            19 -> moveFocus(focused, View.FOCUS_UP)         // UP
            20 -> moveFocus(focused, View.FOCUS_DOWN)       // DOWN
            21 -> moveFocus(focused, View.FOCUS_LEFT)       // LEFT
            22 -> {                                          // RIGHT — exit at edge
                val next = focused.focusSearch(View.FOCUS_RIGHT)
                if (next != null && hostView.isAncestorOf(next)) {
                    next.requestFocus()
                    true
                } else false
            }
            66 -> { focused.performClick(); true }          // ACTIVATE
            else -> false
        }
    }

    private fun moveFocus(from: View, direction: Int): Boolean {
        val next = from.focusSearch(direction)
        if (next != null && hostView.isAncestorOf(next)) next.requestFocus()
        return true   // consume key even at edge — only RIGHT exits
    }

    /** Promote clickable-but-not-focusable widget children for d-pad navigation. */
    private fun ensureFocusableChildren() {
        makeFocusable(hostView)
    }

    private fun makeFocusable(view: View) {
        if (view.isClickable && !view.isFocusable) view.isFocusable = true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) makeFocusable(view.getChildAt(i))
        }
    }

    private fun findFirstFocusable(view: View): View? {
        if (view !== hostView && view.isFocusable) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Check if [child] is a descendant of this ViewGroup. */
    private fun ViewGroup.isAncestorOf(child: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    /** Recursively find view IDs of ListView/GridView instances in the widget layout. */
    private fun findCollectionViewIds(view: View): List<Int> {
        val ids = mutableListOf<Int>()
        if (view is AbsListView && view.id != View.NO_ID) ids.add(view.id)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) ids.addAll(findCollectionViewIds(view.getChildAt(i)))
        }
        return ids
    }
}
