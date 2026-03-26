package de.codevoid.andronavibar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton

/**
 * Runtime pane for NavTargetCollection buttons.
 *
 * Shows a 2-column grid of navigation target buttons. Tapping one launches the
 * collection's configured app with the target's URI as an ACTION_VIEW intent.
 */
class NavTargetsPaneContent(
    private val context: Context,
    private val buttonIndex: Int,
    private val appPackage: String,
    private val db: LauncherDatabase
) : PaneContent {

    private var rootView: FrameLayout? = null

    override fun load(onReady: () -> Unit) = onReady()

    override fun hide() { rootView?.visibility = View.GONE }

    override fun show(container: ViewGroup) {
        rootView?.let { it.visibility = View.VISIBLE; return }
        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootView = frame
        container.addView(frame)
        frame.post { buildGrid(frame) }
    }

    override fun refresh() {
        val frame = rootView ?: return
        frame.removeAllViews()
        frame.post { buildGrid(frame) }
    }

    override fun unload() {
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView = null
    }

    private fun buildGrid(frame: FrameLayout) {
        val res    = context.resources
        val margin = res.dpToPx(MARGIN_DP)
        val tileW  = frame.width / COLUMNS - margin * 2
        val tileH  = frame.height / VISIBLE_ROWS - margin * 2

        val items = db.getCollectionItems(buttonIndex)

        if (items.isEmpty()) {
            frame.addView(TextView(context).apply {
                text = context.getString(R.string.no_targets)
                textSize = 16f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            })
            return
        }

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        var row: LinearLayout? = null
        for (i in items.indices) {
            if (i % COLUMNS == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                }
                col.addView(row)
            }
            val item = items[i]
            row!!.addView(FocusableButton(context).apply {
                text = item.label.ifEmpty { item.uri }
                cornerRadius = res.dpToPx(FocusableButton.CORNER_RADIUS_DP)
                layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener { launchTarget(item.uri) }
            })
        }

        // Fill remainder of last row with invisible spacers
        val rem = items.size % COLUMNS
        if (rem != 0) repeat(COLUMNS - rem) {
            row?.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
                    setMargins(margin, margin, margin, margin)
                }
            })
        }

        scroll.addView(col)
        frame.addView(scroll)
    }

    private fun launchTarget(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            if (appPackage.isNotEmpty()) setPackage(appPackage)
        }
        try { context.startActivity(intent) } catch (_: Exception) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            catch (_: Exception) { /* no handler */ }
        }
    }

    companion object {
        private const val COLUMNS      = 2
        private const val VISIBLE_ROWS = 6
        private const val MARGIN_DP    = 4
    }
}
