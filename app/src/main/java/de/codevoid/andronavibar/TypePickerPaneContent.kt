package de.codevoid.andronavibar

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Type picker pane — shown in reservedArea when the user taps "+ Add" in edit mode.
 *
 * Presents the four available button types as tappable cards in a 2×2 grid.
 * On selection, fires onTypeSelected(typeKey); on cancel fires onCancelled().
 * The button row is not created until onTypeSelected fires.
 */
class TypePickerPaneContent(
    private val activity: Activity,
    private val onTypeSelected: (String) -> Unit,
    private val onCancelled: () -> Unit
) : PaneContent {

    private var container: ViewGroup? = null

    override fun load(onReady: () -> Unit) = onReady()

    override fun show(container: ViewGroup) {
        this.container = container
        build(container)
    }

    override fun unload() {
        container?.removeAllViews()
        container = null
    }

    private fun build(container: ViewGroup) {
        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = activity.resources.dpToPx(20)
            setPadding(p, p, p, p)
        }
        scroll.addView(form)
        container.addView(scroll)

        form.addView(makeText(activity.getString(R.string.type_picker_title), 22f, bold = true))
        form.addView(gap(20))

        val types = listOf(
            Triple("app",       activity.getString(R.string.tab_app),       activity.getString(R.string.type_app_desc)),
            Triple("url",       activity.getString(R.string.tab_url),       activity.getString(R.string.type_url_desc)),
            Triple("widget",    activity.getString(R.string.widget_tab),    activity.getString(R.string.type_widget_desc)),
            Triple("music",     activity.getString(R.string.tab_music),     activity.getString(R.string.type_music_desc)),
            Triple("bookmark",  activity.getString(R.string.type_bookmark), activity.getString(R.string.type_bookmark_desc)),
            Triple("navtarget", activity.getString(R.string.type_navtarget),activity.getString(R.string.type_navtarget_desc))
        )

        val cardGap = activity.resources.dpToPx(8)
        for (pair in types.chunked(2)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            for ((col, triple) in pair.withIndex()) {
                val (typeKey, typeName, typeDesc) = triple
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (col == 0) rightMargin = cardGap / 2
                    else leftMargin = cardGap / 2
                }
                row.addView(makeTypeCard(typeKey, typeName, typeDesc).also { it.layoutParams = lp })
            }
            form.addView(row)
            form.addView(gap(cardGap))
        }

        form.addView(gap(8))
        form.addView(makeButton(activity.getString(R.string.cancel)) { onCancelled() })
    }

    private fun makeTypeCard(typeKey: String, name: String, desc: String): LinearLayout {
        val p = activity.resources.dpToPx(16)
        val bg = GradientDrawable().apply {
            setColor(activity.getColor(R.color.surface_card))
            cornerRadius = activity.resources.dpToPx(12).toFloat()
        }
        val ta = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = ta.getDrawable(0)
        ta.recycle()

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            foreground = ripple
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true

            addView(TextView(activity).apply {
                text = name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(activity.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            })
            addView(gap(4))
            addView(TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(activity.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            })

            setOnClickListener { onTypeSelected(typeKey) }
        }
    }

    private fun makeText(text: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(activity.getColor(R.color.text_primary))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

    private fun makeButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(activity).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

    private fun gap(dp: Int) = android.view.View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, activity.resources.dpToPx(dp))
    }
}
