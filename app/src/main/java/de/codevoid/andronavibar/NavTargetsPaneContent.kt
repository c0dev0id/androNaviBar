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
 * Shows a scrollable list of navigation targets. Tapping one launches the
 * collection's configured app with the target's URI as an ACTION_VIEW intent.
 */
class NavTargetsPaneContent(
    private val context: Context,
    private val buttonIndex: Int,
    private val appPackage: String,
    private val db: LauncherDatabase
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
                text = context.getString(R.string.no_targets)
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
                    setOnClickListener { launchTarget(item.uri) }
                })
            }
        }
        return scroll
    }

    private fun launchTarget(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            if (appPackage.isNotEmpty()) setPackage(appPackage)
        }
        try { context.startActivity(intent) } catch (_: Exception) {
            // Fall back without package restriction if the specific app can't handle it
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            catch (_: Exception) { /* no handler */ }
        }
    }
}
