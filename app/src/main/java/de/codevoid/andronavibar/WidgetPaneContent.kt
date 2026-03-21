package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.Log
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
 * After the view is attached, updateAppWidgetOptions communicates the actual
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
        val mgr = AppWidgetManager.getInstance(context)

        // Trigger the provider to re-send fresh RemoteViews BEFORE creating
        // the host view.  After a process restart the system's cached views
        // reference content:// URIs whose temporary grants expired with the
        // old process.  Poking updateAppWidgetOptions() causes the provider's
        // onAppWidgetOptionsChanged() to fire, which re-sends RemoteViews
        // with freshly granted URIs.  Using the already-stored options avoids
        // needing layout dimensions we don't have yet.
        val existingOpts = mgr.getAppWidgetOptions(appWidgetId)
        Log.d(TAG, "Triggering provider refresh for widget $appWidgetId opts=$existingOpts")
        mgr.updateAppWidgetOptions(appWidgetId, existingOpts)

        val hv = appWidgetHost.createView(context, appWidgetId, providerInfo)
        hv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hostView = hv
        container.addView(hv)

        // After the view is measured, send the actual pane dimensions and
        // poke collection adapters to reload.
        hv.post {
            val density = context.resources.displayMetrics.density
            val wDp = (hv.width  / density).toInt()
            val hDp = (hv.height / density).toInt()
            if (wDp > 0 && hDp > 0) {
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                }
                mgr.updateAppWidgetOptions(appWidgetId, opts)
            }

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

    companion object {
        private const val TAG = "WidgetPane"
    }
}
