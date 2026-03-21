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
 * URIs whose temporary grants have expired.  SafeAppWidgetHostView detects
 * inflation failures so WidgetPaneContent can offer recovery.
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
     * True if the most recent updateAppWidget() call failed to inflate the
     * RemoteViews.  Checked by WidgetPaneContent after createView() returns.
     */
    var updateFailed = false
        private set

    private var inUpdate = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        inUpdate = true
        updateFailed = false
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }
        inUpdate = false
    }

    /**
     * Detects the error-handling path inside AppWidgetHostView.
     *
     * When RemoteViews inflation fails, the framework's internal
     * handleViewError() calls removeAllViews() before adding the error
     * TextView.  By checking the call stack we can distinguish this from
     * the normal view-replacement path in applyRemoteViews().
     */
    override fun removeAllViews() {
        if (inUpdate) {
            val isErrorHandler = Exception().stackTrace.any {
                it.className == "android.appwidget.AppWidgetHostView" &&
                    it.methodName == "handleViewError"
            }
            if (isErrorHandler) {
                updateFailed = true
                Log.w(TAG, "Widget inflation failed — cached RemoteViews have expired URI grants")
            }
        }
        super.removeAllViews()
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
