package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews

/**
 * AppWidgetHost that creates crash-resistant host views.
 *
 * After a host process restart, cached RemoteViews may reference content://
 * URIs whose temporary grants have expired.  WidgetPaneContent.show() handles
 * recovery by calling updateAppWidgetOptions() which triggers providers to
 * re-send fresh RemoteViews.  This host prevents crashes from two remaining
 * failure paths:
 *
 * 1. **Main layout inflation** — RemoteViews.apply() inside updateAppWidget()
 *    throws ActionException (wrapping SecurityException).
 *
 * 2. **Collection item inflation** — RemoteCollectionItemsAdapter.getView()
 *    throws during the measure/draw pass.
 */
class SafeAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = SafeAppWidgetHostView(context)
}

internal class SafeAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    /**
     * True while [onLayout] is executing. During layout, the framework's
     * AppWidgetHostView.onLayout() catches any exception from child views
     * and calls handleViewError(), which does removeAllViews() + addView(errorView).
     * We suppress both to preserve the widget's inflated view tree.
     */
    private var inLayout = false
    private var suppressNextAddView = false

    /** Fires once if a layout error was suppressed, so the host can trigger a refresh. */
    var onLayoutError: (() -> Unit)? = null

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        inLayout = true
        try {
            super.onLayout(changed, left, top, right, bottom)
        } finally {
            inLayout = false
        }
    }

    // handleViewError() calls removeAllViews() then addView(errorView).
    // Suppress both during layout to keep the widget's real view tree alive.

    override fun removeAllViews() {
        if (inLayout) {
            Log.i(TAG, "Suppressed error view replacement during layout")
            suppressNextAddView = true
            onLayoutError?.invoke()
            onLayoutError = null
            return
        }
        super.removeAllViews()
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (suppressNextAddView) {
            suppressNextAddView = false
            return
        }
        super.addView(child, index, params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } catch (e: Exception) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
            Log.w(TAG, "Widget measure failed: ${e.message}")
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        try {
            super.dispatchDraw(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "Widget draw failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SafeWidgetHost"
    }
}
