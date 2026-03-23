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
 * SafeAppWidgetHostView catches exceptions during updateAppWidget(),
 * onMeasure(), and dispatchDraw() so a misbehaving widget cannot
 * crash the launcher process.
 */
class SafeAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = SafeAppWidgetHostView(context)
}

internal class SafeAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    /** True after onMeasure catches a SecurityException (stale URI permissions). */
    var hasSecurityError = false
        private set

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
            // The SecurityException may arrive directly or wrapped in a
            // RuntimeException (observed on API 34).  Check both.
            if (e is SecurityException || e.cause is SecurityException
                || e.message?.contains("SecurityException") == true) {
                hasSecurityError = true
            }
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
