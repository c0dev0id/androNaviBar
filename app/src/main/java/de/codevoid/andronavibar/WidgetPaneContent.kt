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

    override fun hide() { hostView.visibility = View.GONE }

    override fun show(container: ViewGroup) {
        if (hostView.parent != null) { hostView.visibility = View.VISIBLE; return }

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
