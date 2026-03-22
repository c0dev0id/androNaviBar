package de.codevoid.andronavibar

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Global configuration pane shown by the Configure button.
 *
 * Displays a scrollable list of all button slots. Each slot shows:
 * type selector, label editor, icon selector, and move up/down controls.
 * Changes are written to SharedPreferences immediately (no save/cancel).
 */
class GlobalConfigPaneContent(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val callbacks: Callbacks
) : PaneContent {

    interface Callbacks {
        fun onReloadButton(index: Int)
        fun onReloadAll()
        fun onAddButton()
        fun onRemoveLastButton()
        fun onPickImage(buttonIndex: Int)
    }

    private var rootView: ScrollView? = null
    private var listContainer: LinearLayout? = null

    // ── PaneContent ─────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val root = buildLayout()
        rootView = root
        container.addView(root)
    }

    override fun unload() {
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView = null
        listContainer = null
    }

    /** Rebuild the list after a structural change (reorder, add, remove, image pick). */
    fun rebuild() {
        val container = listContainer ?: return
        container.removeAllViews()
        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) container.addView(buildButtonEntry(i))
        container.addView(buildFooter())
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(context.getColor(R.color.surface_dark))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
            val p = dpToPx(16)
            setPadding(p, p, p, p)
        }
        listContainer = container

        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) container.addView(buildButtonEntry(i))
        container.addView(buildFooter())

        scroll.addView(container)
        return scroll
    }

    private fun buildButtonEntry(index: Int): LinearLayout {
        val type = prefs.getString("btn_${index}_type", null)
        val label = prefs.getString("btn_${index}_label", "") ?: ""
        val iconType = prefs.getString("btn_${index}_icon_type", null)
        val iconData = prefs.getString("btn_${index}_icon_data", null)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dpToPx(8)
            }
            val p = dpToPx(12)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(context.getColor(R.color.surface_card))
            }
        }

        // ── Header: slot number + move buttons ──────────────────────────
        card.addView(buildHeader(index))

        // ── Type selector ───────────────────────────────────────────────
        card.addView(buildTypeSelector(index, type))

        // ── Label field (only for non-empty buttons) ────────────────────
        if (type != null) {
            card.addView(buildLabelField(index, label))
        }

        // ── Icon selector (non-empty, non-app buttons) ─────────────────
        if (type != null && type != "app") {
            card.addView(buildIconSelector(index, iconType, iconData))
        }

        return card
    }

    // ── Header row ──────────────────────────────────────────────────────────

    private fun buildHeader(index: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Button ${index + 1}"
            textSize = 16f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        row.addView(makeSmallButton("\u2191") { // ↑
            if (index > 0) {
                swapButtonPrefs(index, index - 1)
                callbacks.onReloadAll()
                rebuild()
            }
        })

        row.addView(makeSmallButton("\u2193") { // ↓
            val count = prefs.getInt("button_count", 6)
            if (index < count - 1) {
                swapButtonPrefs(index, index + 1)
                callbacks.onReloadAll()
                rebuild()
            }
        })

        return row
    }

    // ── Type selector ───────────────────────────────────────────────────────

    private fun buildTypeSelector(index: Int, currentType: String?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpToPx(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        val types = listOf(
            null     to "Empty",
            "app"    to "App",
            "url"    to "URL",
            "widget" to "Widget",
            "apps"   to "Apps",
            "music"  to "Music"
        )

        for ((key, name) in types) {
            row.addView(makeSmallButton(name, active = key == currentType) {
                changeButtonType(index, key)
                callbacks.onReloadButton(index)
                rebuild()
            })
        }

        return row
    }

    // ── Label field ─────────────────────────────────────────────────────────

    private fun buildLabelField(index: Int, currentLabel: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpToPx(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Label: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val edit = EditText(context).apply {
            setText(currentLabel)
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            hint = "Button label"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setBackgroundColor(Color.TRANSPARENT)
        }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("btn_${index}_label", s?.toString() ?: "").apply()
                callbacks.onReloadButton(index)
            }
        })

        row.addView(edit)
        return row
    }

    // ── Icon selector ───────────────────────────────────────────────────────

    private fun buildIconSelector(
        index: Int,
        currentIconType: String?,
        currentIconData: String?
    ): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpToPx(8) }
        }

        // Option buttons row
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Icon: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val options = listOf("none" to "None", "emoji" to "Emoji", "custom" to "Image")
        for ((key, name) in options) {
            val active = when (key) {
                "none"   -> currentIconType == null
                "emoji"  -> currentIconType == "emoji"
                "custom" -> currentIconType == "custom"
                else     -> false
            }
            row.addView(makeSmallButton(name, active) {
                applyIconOption(index, key)
                callbacks.onReloadButton(index)
                rebuild()
            })
        }

        wrapper.addView(row)

        // Detail row for the active icon type
        when (currentIconType) {
            "emoji" -> wrapper.addView(buildEmojiInput(index, currentIconData))
            "custom" -> wrapper.addView(buildImagePicker(index))
        }

        return wrapper
    }

    private fun buildEmojiInput(index: Int, currentEmoji: String?): EditText {
        return EditText(context).apply {
            setText(currentEmoji ?: "")
            hint = "Paste emoji\u2026"
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpToPx(4)
            }
            setBackgroundColor(Color.TRANSPARENT)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("btn_${index}_icon_data", s?.toString() ?: "").apply()
                    callbacks.onReloadButton(index)
                }
            })
        }
    }

    private fun buildImagePicker(index: Int): MaterialButton {
        val hasFile = File(context.filesDir, "btn_${index}_icon.png").exists()
        return makeActionButton(if (hasFile) "Change image\u2026" else "Pick image\u2026") {
            callbacks.onPickImage(index)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dpToPx(4)
            }
        }
    }

    // ── Footer (add / remove) ───────────────────────────────────────────────

    private fun buildFooter(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dpToPx(16) }
            gravity = Gravity.CENTER
        }

        row.addView(makeActionButton("+ Add Button") {
            callbacks.onAddButton()
            rebuild()
        })

        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(16), 0)
        })

        row.addView(makeActionButton("\u2212 Remove Last") { // −
            callbacks.onRemoveLastButton()
            rebuild()
        })

        return row
    }

    // ── Type change ─────────────────────────────────────────────────────────

    private fun changeButtonType(index: Int, typeKey: String?) {
        val edit = prefs.edit()
            .remove("btn_${index}_type")
            .remove("btn_${index}_value")
            .remove("btn_${index}_label")
            .remove("btn_${index}_widget_id")
            .remove("btn_${index}_apps")
            .remove("btn_${index}_icon_type")
            .remove("btn_${index}_icon_data")
            .remove("btn_${index}_open_browser")

        if (typeKey != null) {
            edit.putString("btn_${index}_type", typeKey)
            val defaultLabel = when (typeKey) {
                "apps"  -> "Apps"
                "music" -> "Music"
                else    -> ""
            }
            edit.putString("btn_${index}_label", defaultLabel)
        }

        edit.apply()
        File(context.filesDir, "btn_${index}_icon.png").delete()
    }

    private fun applyIconOption(index: Int, option: String) {
        val edit = prefs.edit()
        when (option) {
            "none" -> edit
                .remove("btn_${index}_icon_type")
                .remove("btn_${index}_icon_data")
            "emoji" -> edit
                .putString("btn_${index}_icon_type", "emoji")
                .putString("btn_${index}_icon_data", "")
            "custom" -> {
                edit.putString("btn_${index}_icon_type", "custom")
                    .remove("btn_${index}_icon_data")
                edit.apply()
                callbacks.onPickImage(index)
                return
            }
        }
        edit.apply()
        if (option == "none") File(context.filesDir, "btn_${index}_icon.png").delete()
    }

    // ── Button reorder ──────────────────────────────────────────────────────

    private fun swapButtonPrefs(a: Int, b: Int) {
        val keys = listOf(
            "_type", "_value", "_label", "_icon_type", "_icon_data",
            "_widget_id", "_open_browser", "_apps"
        )
        val aVals = keys.associateWith { k -> prefs.getString("btn_$a$k", null) }
        val bVals = keys.associateWith { k -> prefs.getString("btn_$b$k", null) }

        val edit = prefs.edit()
        for (k in keys) {
            val toA = bVals[k]
            val toB = aVals[k]
            if (toA != null) edit.putString("btn_$a$k", toA) else edit.remove("btn_$a$k")
            if (toB != null) edit.putString("btn_$b$k", toB) else edit.remove("btn_$b$k")
        }
        edit.apply()

        // Swap icon files
        val fa = File(context.filesDir, "btn_${a}_icon.png")
        val fb = File(context.filesDir, "btn_${b}_icon.png")
        val tmp = File(context.filesDir, "btn_swap_tmp.png")
        if (fa.exists()) fa.copyTo(tmp, overwrite = true) else tmp.delete()
        if (fb.exists()) fb.copyTo(fa, overwrite = true) else fa.delete()
        if (tmp.exists()) tmp.copyTo(fb, overwrite = true) else fb.delete()
        tmp.delete()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeSmallButton(
        label: String,
        active: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = label
            textSize = 12f
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            val hp = dpToPx(8)
            val vp = dpToPx(4)
            setPadding(hp, vp, hp, vp)
            cornerRadius = dpToPx(8)
            backgroundTintList = ColorStateList.valueOf(
                context.getColor(if (active) R.color.colorPrimary else R.color.button_inactive)
            )
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeActionButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = label
            textSize = 14f
            cornerRadius = dpToPx(12)
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { onClick() }
        }
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
