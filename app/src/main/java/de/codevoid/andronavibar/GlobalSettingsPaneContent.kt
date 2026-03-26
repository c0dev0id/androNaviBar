package de.codevoid.andronavibar

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton

/**
 * Global settings pane — shown in reservedArea when the ⚙ Settings button
 * in edit mode chrome is tapped, or when the Dashboard gear button is pressed.
 *
 * Currently: update check only. Future global settings land here.
 */
class GlobalSettingsPaneContent(
    private val activity: Activity
) : PaneContent {

    private var container: ViewGroup? = null
    private var updateBtn: FocusableButton? = null

    override fun load(onReady: () -> Unit) = onReady()

    override fun show(container: ViewGroup) {
        this.container = container
        build(container)
    }

    override fun unload() {
        container?.removeAllViews()
        container = null
        updateBtn = null
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

        form.addView(makeText(activity.getString(R.string.edit_settings), 22f, bold = true))
        form.addView(gap(20))

        val btn = FocusableButton(activity).apply {
            text = activity.getString(R.string.check_for_update)
            textSize = 18f
            cornerRadius = activity.resources.dpToPx(12)
            backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.button_inactive))
            setTextColor(activity.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnClickListener { triggerUpdateCheck() }
        }
        updateBtn = btn
        form.addView(btn)
    }

    private fun triggerUpdateCheck() {
        val btn = updateBtn ?: return
        UpdateChecker.check(
            activity,
            onStatus   = { text     -> btn.text             = text },
            onProgress = { progress -> btn.downloadProgress = progress }
        )
    }

    private fun makeText(text: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(activity.getColor(R.color.text_primary))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

    private fun gap(dp: Int) = android.view.View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, activity.resources.dpToPx(dp))
    }
}
