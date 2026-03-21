package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView

/**
 * PaneContent that hosts an AppWidgetHostView in the left pane.
 *
 * The widget fills the entire reservedArea (MATCH_PARENT). The host view
 * is created fresh on show() and removed on unload(); the widget's remote
 * views continue to be delivered as long as the AppWidgetHost is listening.
 *
 * After the view is attached, updateAppWidgetSize communicates the actual
 * pane dimensions to the widget provider so it can pick the right layout
 * for the available space.
 */
class WidgetPaneContent(
    private val context: Context,
    private val appWidgetHost: AppWidgetHost,
    private val appWidgetId: Int,
    private val providerInfo: AppWidgetProviderInfo
) : PaneContent {

    private var hostView: AppWidgetHostView? = null

    /** Called once after the host view is attached and sized. */
    var onContentReady: (() -> Unit)? = null

    override fun load(onReady: () -> Unit) {
        onReady()   // host view is cheap to create; widget updates arrive async
    }

    override fun show(container: ViewGroup) {
        val hv = appWidgetHost.createView(context, appWidgetId, providerInfo)
        hv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hostView = hv
        container.addView(hv)

        // Tell the widget how much space it actually has. Posted so the
        // container has been measured and hv.width/height are non-zero.
        hv.post {
            val density = context.resources.displayMetrics.density
            val wDp = (hv.width  / density).toInt()
            val hDp = (hv.height / density).toInt()
            val mgr = AppWidgetManager.getInstance(context)
            if (wDp > 0 && hDp > 0) {
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                }
                // Triggers onAppWidgetOptionsChanged() in the provider, which
                // causes well-behaved providers to send fresh RemoteViews with
                // valid URI permissions (the cached views from before a restart
                // may reference content:// URIs whose grants have expired).
                mgr.updateAppWidgetOptions(appWidgetId, opts)
            }

            // For collection widgets (ListView/GridView), force the adapter to
            // reload items. Walk the view hierarchy to find collection view IDs.
            for (id in findCollectionViewIds(hv)) {
                mgr.notifyAppWidgetViewDataChanged(appWidgetId, id)
            }

            onContentReady?.invoke()
            onContentReady = null
        }
    }

    override fun unload() {
        val hv = hostView ?: return
        hostView = null
        (hv.parent as? ViewGroup)?.removeView(hv)
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
