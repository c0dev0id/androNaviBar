package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup

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
            if (wDp > 0 && hDp > 0) {
                val opts = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                }
                AppWidgetManager.getInstance(context)
                    .updateAppWidgetOptions(appWidgetId, opts)
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
}
