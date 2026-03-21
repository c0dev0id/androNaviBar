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
     * True after the framework's handleViewError() has been called.
     * Checked by WidgetPaneContent after createView() returns (sync path).
     */
    var updateFailed = false
        private set

    /**
     * Callback invoked when inflation fails asynchronously (API 31+).
     * On API 34, updateAppWidget() uses async inflation by default,
     * so the error fires on the main thread AFTER createView() returns.
     */
    var onUpdateFailed: (() -> Unit)? = null

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        updateFailed = false
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }
    }

    /**
     * Detects the error-handling path inside AppWidgetHostView.
     *
     * When RemoteViews inflation fails (sync or async), the framework's
     * internal handleViewError() calls removeAllViews() before adding
     * the error TextView.  The normal success path calls removeAllViews()
     * from prepareView().  The call stack distinguishes the two.
     *
     * No inUpdate guard needed: on API 34 async inflation means the error
     * arrives on the main thread after updateAppWidget() has already returned.
     */
    override fun removeAllViews() {
        val isErrorHandler = Exception().stackTrace.any {
            it.className == "android.appwidget.AppWidgetHostView" &&
                it.methodName == "handleViewError"
        }
        if (isErrorHandler) {
            updateFailed = true
            Log.w(TAG, "Widget inflation failed — cached RemoteViews have expired URI grants")
            onUpdateFailed?.invoke()
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
