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
 * Shows a scrollable list of bookmarks. Tapping one opens the URL either in
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

    private var rootView: ScrollView? = null

    override fun load(onReady: () -> Unit) = onReady()

    override fun hide() { rootView?.visibility = View.GONE }

    override fun show(container: ViewGroup) {
        rootView?.let { it.visibility = View.VISIBLE; return }
        val scroll = buildList()
        rootView = scroll
        container.addView(scroll)
    }

    override fun refresh() {
        val rv = rootView ?: return
        val parent = rv.parent as? ViewGroup ?: return
        parent.removeView(rv)
        rootView = null
        val scroll = buildList()
        rootView = scroll
        parent.addView(scroll)
    }

    override fun unload() {
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView = null
    }

    private fun buildList(): ScrollView {
        val res = context.resources
        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = res.dpToPx(12)
            setPadding(p, p, p, p)
        }
        scroll.addView(list)

        val items = db.getCollectionItems(buttonIndex)
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(R.string.no_bookmarks)
                textSize = 16f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            })
        } else {
            val gap = res.dpToPx(6)
            items.forEach { item ->
                list.addView(FocusableButton(context).apply {
                    text = item.label.ifEmpty { item.uri }
                    cornerRadius = res.dpToPx(10)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        bottomMargin = gap
                    }
                    setOnClickListener {
                        val url = normalizeUrl(item.uri)
                        if (item.openBrowser) onUrlBrowserActivated(url)
                        else onUrlActivated(url)
                    }
                })
            }
        }
        return scroll
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
}
