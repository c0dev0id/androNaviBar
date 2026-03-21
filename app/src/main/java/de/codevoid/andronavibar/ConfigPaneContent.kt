package de.codevoid.andronavibar

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * PaneContent that presents a full button-configuration UI in the left pane.
 *
 * Layout:
 *   [ App ]  [ URL ]  [ Clear ]      ← type selector row
 *   ┌────────────────────────────┐
 *   │  scrollable app list       │   ← visible when App tab is active
 *   │  — or —                    │
 *   │  URL text field            │   ← visible when URL tab is active
 *   └────────────────────────────┘
 *
 * Remote navigation: DPAD_UP/DPAD_DOWN scrolls the app list; ENTER (key 66)
 * confirms the current selection and triggers [onSave].
 * Button B (key TBD): add to handleKey() when key code is confirmed.
 *
 * Touch: tap an app row to select immediately; type in the URL field and
 * confirm with ENTER or the remote's confirm button.
 *
 * Closing is always initiated by the caller (MainActivity), not by this class.
 * [onSave] fires when the user confirms a selection; MainActivity is
 * responsible for calling unload() afterwards.
 */
class ConfigPaneContent(
    private val context:       Context,
    private val initialConfig: ButtonConfig,
    private val onSave:        (ButtonConfig) -> Unit
) : PaneContent {

    private enum class Tab { APP, URL, CLEAR }

    // ── State ─────────────────────────────────────────────────────────────────

    private var activeTab: Tab = when (initialConfig) {
        is ButtonConfig.AppLauncher -> Tab.APP
        is ButtonConfig.UrlLauncher -> Tab.URL
        is ButtonConfig.Empty       -> Tab.APP
    }

    private var apps:             List<ResolveInfo> = emptyList()
    private var selectedAppIndex: Int               = 0

    // ── Live view references (valid between show() and unload()) ──────────────

    private var rootView:      View?                      = null
    private var detailArea:    ViewGroup?                 = null
    private var tabButtons:    Map<Tab, MaterialButton>   = emptyMap()
    private var appRows:       List<View>                 = emptyList()
    private var appScrollView: ScrollView?                = null
    private var urlEditText:   EditText?                  = null

    // ── PaneContent ───────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) {
        // Query installed apps — same synchronous call the old dialog used.
        // TODO: move to button.scope coroutine when performance warrants it.
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = context.packageManager
            .queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

        if (initialConfig is ButtonConfig.AppLauncher) {
            selectedAppIndex = apps
                .indexOfFirst { it.activityInfo.packageName == initialConfig.packageName }
                .coerceAtLeast(0)
        }

        onReady()
    }

    override fun show(container: ViewGroup) {
        val root = buildRoot()
        rootView = root
        container.addView(root)
        appScrollView?.post { scrollToSelected() }
    }

    override fun unload() {
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView      = null
        detailArea    = null
        tabButtons    = emptyMap()
        appRows       = emptyList()
        appScrollView = null
        urlEditText   = null
    }

    // ── Key handling (routed from MainActivity while this pane has input focus) ─

    /**
     * Returns true if the key was consumed.
     *
     * Key 19 / 20 (DPAD_UP / DPAD_DOWN): navigate app list.
     * Key 66 (ENTER / Round Button 1): confirm selection.
     * Key TBD (Button B): add `KEY_BUTTON_B -> { save(); true }` when confirmed.
     */
    fun handleKey(keyCode: Int): Boolean {
        return when {
            keyCode == 19 && activeTab == Tab.APP -> moveAppSelection(-1)
            keyCode == 20 && activeTab == Tab.APP -> moveAppSelection(+1)
            keyCode == 66                         -> { save(); true }
            else                                  -> false
        }
    }

    /** Build the selected config and notify the caller. */
    fun save() {
        onSave(buildConfig())
    }

    // ── View construction ─────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val root = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(context.getColor(R.color.surface_dark))
            val p = px(16)
            setPadding(p, p, p, p)
        }

        root.addView(buildTabRow())

        val detail = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        detailArea = detail
        root.addView(detail)

        refreshDetail()
        return root
    }

    private fun buildTabRow(): View {
        val row = LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = px(12)
            }
        }

        val built = mutableMapOf<Tab, MaterialButton>()

        fun addTab(tab: Tab, label: String, last: Boolean = false) {
            val btn = MaterialButton(
                context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text         = label
                cornerRadius = px(8)
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (!last) marginEnd = px(4)
                }
                applyTabStyle(this, tab == activeTab)
                setOnClickListener { selectTab(tab) }
            }
            row.addView(btn)
            built[tab] = btn
        }

        addTab(Tab.APP,   context.getString(R.string.choose_app))
        addTab(Tab.URL,   context.getString(R.string.enter_url))
        addTab(Tab.CLEAR, context.getString(R.string.clear), last = true)

        tabButtons = built
        return row
    }

    private fun selectTab(tab: Tab) {
        if (tab == Tab.CLEAR) {
            // Clear tapped: save Empty immediately and let caller close.
            onSave(ButtonConfig.Empty)
            return
        }
        activeTab = tab
        tabButtons.forEach { (t, btn) -> applyTabStyle(btn, t == activeTab) }
        refreshDetail()
    }

    private fun applyTabStyle(btn: MaterialButton, active: Boolean) {
        if (active) {
            btn.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
            btn.strokeWidth        = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            btn.strokeColor        = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            btn.strokeWidth        = px(1)
        }
    }

    private fun refreshDetail() {
        val container = detailArea ?: return
        container.removeAllViews()
        when (activeTab) {
            Tab.APP   -> container.addView(buildAppPicker())
            Tab.URL   -> container.addView(buildUrlEditor())
            Tab.CLEAR -> Unit
        }
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private fun buildAppPicker(): View {
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        appScrollView = scroll

        val list = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val rows = mutableListOf<View>()
        for (i in apps.indices) {
            val row = buildAppRow(apps[i], i == selectedAppIndex)
            val idx = i
            row.setOnClickListener {
                selectedAppIndex = idx
                updateAppRowHighlights(rows, idx)
                save()
            }
            list.addView(row)
            rows.add(row)
        }
        appRows = rows

        scroll.addView(list)
        return scroll
    }

    private fun buildAppRow(app: ResolveInfo, selected: Boolean): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, px(56))
            setPadding(px(8), px(8), px(8), px(8))
            gravity      = Gravity.CENTER_VERTICAL
            setBackgroundColor(rowBg(selected))

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(12) }
                scaleType    = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(try {
                    context.packageManager.getApplicationIcon(app.activityInfo.packageName)
                } catch (_: Exception) { null })
            })

            addView(TextView(context).apply {
                text     = app.loadLabel(context.packageManager)
                textSize = 16f
                setTextColor(context.getColor(R.color.text_primary))
            })
        }
    }

    private fun moveAppSelection(delta: Int): Boolean {
        if (apps.isEmpty()) return false
        val next = (selectedAppIndex + delta).coerceIn(0, apps.lastIndex)
        if (next == selectedAppIndex) return true
        selectedAppIndex = next
        updateAppRowHighlights(appRows, next)
        scrollToSelected()
        return true
    }

    private fun updateAppRowHighlights(rows: List<View>, selectedIndex: Int) {
        for (i in rows.indices) rows[i].setBackgroundColor(rowBg(i == selectedIndex))
    }

    private fun scrollToSelected() {
        val scroll = appScrollView ?: return
        if (selectedAppIndex < appRows.size) {
            scroll.post { scroll.smoothScrollTo(0, appRows[selectedAppIndex].top) }
        }
    }

    private fun rowBg(selected: Boolean) =
        context.getColor(if (selected) R.color.button_inactive else R.color.surface_dark)

    // ── URL editor ────────────────────────────────────────────────────────────

    private fun buildUrlEditor(): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, px(8), 0, 0)

            val edit = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                hint         = context.getString(R.string.url_hint)
                inputType    = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setText(if (initialConfig is ButtonConfig.UrlLauncher) initialConfig.url else "")
                setTextColor(context.getColor(R.color.text_primary))
                setHintTextColor(context.getColor(R.color.text_secondary))
                requestFocus()
            }
            urlEditText = edit
            addView(edit)
        }
    }

    // ── Config assembly ───────────────────────────────────────────────────────

    private fun buildConfig(): ButtonConfig {
        return when (activeTab) {
            Tab.APP -> {
                val app = apps.getOrNull(selectedAppIndex) ?: return ButtonConfig.Empty
                ButtonConfig.AppLauncher(
                    packageName = app.activityInfo.packageName,
                    label       = app.loadLabel(context.packageManager).toString()
                )
            }
            Tab.URL -> {
                val url = urlEditText?.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) ButtonConfig.UrlLauncher(url, url) else ButtonConfig.Empty
            }
            Tab.CLEAR -> ButtonConfig.Empty
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
