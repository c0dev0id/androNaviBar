package de.codevoid.andronavibar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton

/**
 * Runtime pane for BookmarkCollection buttons.
 *
 * Shows a 2-column grid of bookmark buttons. Tapping one opens the URL either in
 * the internal WebView (via onUrlActivated) or in the external browser
 * (via onUrlBrowserActivated), depending on the item's openBrowser flag.
 */
class BookmarksPaneContent(
    private val context: Context,
    private val buttonIndex: Int,
    private val db: LauncherDatabase,
    private val onUrlActivated: (String) -> Unit,
    private val onUrlBrowserActivated: (String) -> Unit
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
        val res     = context.resources
        val margin  = res.dpToPx(MARGIN_DP)
        val tileW   = frame.width / COLUMNS - margin * 2
        val tileH   = frame.height / VISIBLE_ROWS - margin * 2

        val items = db.getCollectionItems(buttonIndex)

        if (items.isEmpty()) {
            frame.addView(TextView(context).apply {
                text = context.getString(R.string.no_bookmarks)
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
                setOnClickListener {
                    val url = normalizeUrl(item.uri)
                    if (item.openBrowser) onUrlBrowserActivated(url) else onUrlActivated(url)
                }
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

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

    companion object {
        private const val COLUMNS     = 2
        private const val VISIBLE_ROWS = 6
        private const val MARGIN_DP   = 4
    }
}
