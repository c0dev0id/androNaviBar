package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.ViewGroup

/**
 * PaneContent that hosts an AppWidgetHostView in the left pane.
 *
 * The widget fills the entire reservedArea (MATCH_PARENT). The host view
 * is created fresh on show() and removed on unload(); the widget's remote
 * views continue to be delivered as long as the AppWidgetHost is listening.
 */
class WidgetPaneContent(
    private val context: Context,
    private val appWidgetHost: AppWidgetHost,
    private val appWidgetId: Int,
    private val providerInfo: AppWidgetProviderInfo
) : PaneContent {

    private var hostView: AppWidgetHostView? = null

    override fun load(onReady: () -> Unit) {
        onReady()   // host view is cheap to create; widget updates arrive async
    }

    override fun show(container: ViewGroup) {
        val hv = appWidgetHost.createView(context, appWidgetId, providerInfo)
        hv.setAppWidget(appWidgetId, providerInfo)
        hv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hostView = hv
        container.addView(hv)
    }

    override fun unload() {
        val hv = hostView ?: return
        hostView = null
        (hv.parent as? ViewGroup)?.removeView(hv)
    }
}
