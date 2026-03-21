package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.util.Log
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

private class SafeAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }
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
