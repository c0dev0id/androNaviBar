package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView

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
 *
 * If the cached RemoteViews fail to inflate (stale content:// URI grants
 * after a process restart), a recovery view is shown instead and
 * [onReconfigureNeeded] is invoked so the host Activity can re-launch
 * the widget's configure activity.
 */
class WidgetPaneContent(
    private val context: Context,
    private val appWidgetHost: AppWidgetHost,
    private val appWidgetId: Int,
    private val providerInfo: AppWidgetProviderInfo
) : PaneContent {

    /** The view added to the container — either the host view or a recovery view. */
    private var addedView: View? = null
    private var hostView: AppWidgetHostView? = null
    private var container: ViewGroup? = null

    /** Called once after the host view is attached and sized. */
    var onContentReady: (() -> Unit)? = null

    /** Called when cached RemoteViews are stale and the widget needs reconfiguration. */
    var onReconfigureNeeded: (() -> Unit)? = null

    override fun load(onReady: () -> Unit) {
        onReady()   // host view is cheap to create; widget updates arrive async
    }

    override fun show(container: ViewGroup) {
        this.container = container
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

        // createView() fetches cached RemoteViews via getAppWidgetViews(),
        // which does NOT re-grant content:// URI permissions.  After a
        // process restart the grants from the old process have expired,
        // so inflation fails with "Couldn't add widget."
        //
        // Calling startListening() again re-delivers the cached views
        // through the normal update channel, which DOES re-grant URI
        // permissions.  The view is now registered in the host's mViews
        // (createView put it there), so the re-delivered views are applied
        // to it — replacing the error view with the actual widget content.
        appWidgetHost.startListening()

        val safeView = hv as? SafeAppWidgetHostView

        // Fallback: if the re-delivery still fails, show a recovery view.
        safeView?.onUpdateFailed = {
            Log.w(TAG, "Widget $appWidgetId inflation failed — showing recovery")
            val c = this.container
            if (c != null) {
                val existing = addedView
                if (existing != null) {
                    (existing.parent as? ViewGroup)?.removeView(existing)
                }
                hostView = null
                addedView = null
                showRecoveryView(c)
            }
        }

        hv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hostView = hv
        addedView = hv
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
        (hostView as? SafeAppWidgetHostView)?.onUpdateFailed = null
        val view = addedView ?: return
        addedView = null
        hostView = null
        container = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun showRecoveryView(container: ViewGroup) {
        val tv = TextView(context).apply {
            text = context.getString(R.string.widget_expired)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener { onReconfigureNeeded?.invoke() }
        }
        addedView = tv
        container.addView(tv)
        onContentReady?.invoke()
        onContentReady = null
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
