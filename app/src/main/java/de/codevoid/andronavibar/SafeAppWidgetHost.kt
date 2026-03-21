package de.codevoid.andronavibar

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView

/**
 * AppWidgetHost that creates crash-resistant host views.
 *
 * Two categories of FileProvider URI failures after a host process restart:
 *
 * 1. **Main layout inflation** — RemoteViews.apply() inside updateAppWidget()
 *    throws ActionException (wrapping SecurityException). The framework catches
 *    this internally and replaces content with "Couldn't add widget." Our outer
 *    try-catch never sees it. We detect the error view post-update and replace
 *    it with a ProgressBar while the provider sends fresh views.
 *
 * 2. **Collection item inflation** — RemoteCollectionItemsAdapter.getView()
 *    throws during the measure pass. Our onMeasure() catch prevents a crash.
 */
class SafeAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = SafeAppWidgetHostView(context)
}

private class SafeAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    /**
     * True once a non-null RemoteViews has been applied without hitting the
     * framework's internal error path. Used to avoid false-positive detection
     * of widgets whose real content happens to be a single TextView.
     */
    private var hasSuccessfulContent = false

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }

        if (remoteViews == null) return

        // After applying non-null RemoteViews, check whether the framework's
        // internal error handler replaced our content with its error TextView.
        // A real widget layout always has deeper nesting than a lone TextView.
        if (!hasSuccessfulContent && childCount == 1 && getChildAt(0) is TextView) {
            Log.i(TAG, "Detected error view after updateAppWidget — showing loading indicator")
            removeAllViews()
            addView(ProgressBar(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
        } else {
            hasSuccessfulContent = true
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
