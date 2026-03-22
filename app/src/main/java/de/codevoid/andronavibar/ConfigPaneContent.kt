package de.codevoid.andronavibar

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
 *   │  scrollable app list       │   ← App tab: list + label field below
 *   │  — or —                    │
 *   │  URL / label / icon fields │   ← URL tab
 *   └────────────────────────────┘
 *   [ Cancel ]         [ Save ]      ← action row
 *
 * Remote: DPAD_UP/DOWN scrolls app list; key 66 (Round Button 1) saves; key 111
 * (Round Button 2) cancels — same as Back. Custom image: MainActivity calls
 * onImageReady() once the picked image has been copied to the button's icon file.
 */
class ConfigPaneContent(
    private val context:       Context,
    private val buttonIndex:   Int,
    private val initialConfig: ButtonConfig,
    private val onSave:        (ButtonConfig) -> Unit,
    private val onCancel:      ()             -> Unit,
    private val onClear:       ()             -> Unit
) : PaneContent {

    private enum class Tab { APP, URL, WIDGET, APPS, MUSIC, CLEAR }
    private enum class IconOption { NONE, CUSTOM, EMOJI }

    // ── State ─────────────────────────────────────────────────────────────────

    private var activeTab: Tab = when (initialConfig) {
        is ButtonConfig.AppLauncher    -> Tab.APP
        is ButtonConfig.UrlLauncher    -> Tab.URL
        is ButtonConfig.WidgetLauncher -> Tab.WIDGET
        is ButtonConfig.AppsGrid       -> Tab.APPS
        is ButtonConfig.MusicPlayer    -> Tab.MUSIC
        is ButtonConfig.Empty          -> Tab.APP
    }

    private var apps:             List<ResolveInfo> = emptyList()
    private var selectedAppIndex: Int               = 0

    private var selectedPackages: MutableSet<String> = when (initialConfig) {
        is ButtonConfig.AppsGrid -> initialConfig.apps.map { it.packageName }.toMutableSet()
        else                     -> mutableSetOf()
    }

    private var selectedIconOption: IconOption = run {
        val icon = when (initialConfig) {
            is ButtonConfig.UrlLauncher    -> initialConfig.icon
            is ButtonConfig.WidgetLauncher -> initialConfig.icon
            is ButtonConfig.AppsGrid       -> initialConfig.icon
            is ButtonConfig.MusicPlayer    -> initialConfig.icon
            else                           -> null
        }
        when (icon) {
            is UrlIcon.CustomFile -> IconOption.CUSTOM
            is UrlIcon.Emoji      -> IconOption.EMOJI
            else                  -> IconOption.NONE
        }
    }

    /** Set to true by MainActivity after a new custom image has been written to the icon file. */
    private var hasPendingImage = false

    // ── Callbacks wired by MainActivity ───────────────────────────────────────

    var onPickImageRequest: (() -> Unit)? = null

    /** Called by MainActivity after it has already copied the picked image to the icon file. */
    fun onImageReady() {
        hasPendingImage = true
        refreshIconDetail()
    }

    // ── Live view references (valid between show() and unload()) ──────────────

    private var rootView:       View?                           = null
    private var detailArea:     ViewGroup?                      = null
    private var tabButtons:     Map<Tab, MaterialButton>        = emptyMap()
    private var appRows:        List<View>                      = emptyList()
    private var appScrollView:  ScrollView?                     = null
    private var appLabelEdit:   EditText?                       = null
    private var urlEditText:       EditText?  = null
    private var urlLabelEdit:      EditText?  = null
    private var urlOpenInBrowser:  CheckBox?  = null
    private var iconOptionBtns: Map<IconOption, MaterialButton> = emptyMap()
    private var iconDetailArea: ViewGroup?                      = null
    private var emojiEdit:      EditText?                       = null

    private var widgetProviders:      List<AppWidgetProviderInfo> = emptyList()
    private var selectedProviderIndex: Int                        = 0
    private var widgetRows:           List<View>                  = emptyList()
    private var widgetScrollView:     ScrollView?                 = null
    private var widgetLabelEdit:      EditText?                   = null

    private var appsScrollView:    ScrollView? = null
    private var appsCheckRows:     List<View>  = emptyList()
    private var appsGridLabelEdit: EditText?   = null

    private var selectedMusicAppIndex: Int      = 0
    private var musicAppRows:          List<View>  = emptyList()
    private var musicScrollView:       ScrollView? = null
    private var musicLabelEdit:        EditText?   = null

    // ── PaneContent ───────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = context.packageManager
            .queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

        if (initialConfig is ButtonConfig.AppLauncher) {
            selectedAppIndex = apps
                .indexOfFirst { it.activityInfo.packageName == initialConfig.packageName }
                .coerceAtLeast(0)
        }

        widgetProviders = AppWidgetManager.getInstance(context)
            .installedProviders
            .sortedBy { it.loadLabel(context.packageManager).lowercase() }

        if (initialConfig is ButtonConfig.WidgetLauncher) {
            selectedProviderIndex = widgetProviders
                .indexOfFirst { it.provider == initialConfig.provider }
                .coerceAtLeast(0)
        }

        if (initialConfig is ButtonConfig.MusicPlayer) {
            selectedMusicAppIndex = apps
                .indexOfFirst { it.activityInfo.packageName == initialConfig.playerPackage }
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
        rootView        = null
        detailArea      = null
        tabButtons      = emptyMap()
        appRows         = emptyList()
        appScrollView   = null
        appLabelEdit    = null
        urlEditText      = null
        urlLabelEdit     = null
        urlOpenInBrowser = null
        iconOptionBtns        = emptyMap()
        iconDetailArea        = null
        emojiEdit             = null
        widgetRows            = emptyList()
        widgetScrollView      = null
        widgetLabelEdit       = null
        appsScrollView        = null
        appsCheckRows         = emptyList()
        appsGridLabelEdit     = null
        musicAppRows          = emptyList()
        musicScrollView       = null
        musicLabelEdit        = null
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    fun handleKey(keyCode: Int): Boolean {
        return when {
            keyCode == 19 && activeTab == Tab.APP    -> moveAppSelection(-1)
            keyCode == 20 && activeTab == Tab.APP    -> moveAppSelection(+1)
            keyCode == 19 && activeTab == Tab.WIDGET -> moveWidgetSelection(-1)
            keyCode == 20 && activeTab == Tab.WIDGET -> moveWidgetSelection(+1)
            keyCode == 19 && activeTab == Tab.APPS   -> { appsScrollView?.smoothScrollBy(0, -px(56)); true }
            keyCode == 20 && activeTab == Tab.APPS   -> { appsScrollView?.smoothScrollBy(0, +px(56)); true }
            keyCode == 19 && activeTab == Tab.MUSIC  -> moveMusicSelection(-1)
            keyCode == 20 && activeTab == Tab.MUSIC  -> moveMusicSelection(+1)
            keyCode == 66                            -> { save(); true }
            else                                     -> false
        }
    }

    fun save() {
        onSave(buildConfig())
    }

    fun cancel() {
        onCancel()
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

        root.addView(buildActionRow())

        refreshDetail()
        return root
    }

    private fun buildActionRow(): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12) }

            addView(MaterialButton(
                context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text         = context.getString(R.string.cancel)
                cornerRadius = px(16)
                textSize     = 18f
                setTextColor(context.getColor(R.color.text_primary))
                strokeColor  = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
                strokeWidth  = px(1)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = px(8) }
                setOnClickListener { cancel() }
            })

            addView(MaterialButton(context).apply {
                text               = context.getString(R.string.save)
                cornerRadius       = px(16)
                textSize           = 18f
                backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams       = LinearLayout.LayoutParams(0, WRAP, 1f)
                setOnClickListener { save() }
            })
        }
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
                cornerRadius = px(16)
                textSize     = 16f
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

        addTab(Tab.APP,    context.getString(R.string.tab_app))
        addTab(Tab.URL,    context.getString(R.string.tab_url))
        addTab(Tab.WIDGET, context.getString(R.string.widget_tab))
        addTab(Tab.APPS,   context.getString(R.string.tab_apps))
        addTab(Tab.MUSIC,  context.getString(R.string.tab_music))
        addTab(Tab.CLEAR,  context.getString(R.string.clear), last = true)

        tabButtons = built
        return row
    }

    private fun selectTab(tab: Tab) {
        if (tab == Tab.CLEAR) { onClear(); return }
        activeTab = tab
        tabButtons.forEach { (t, btn) -> applyTabStyle(btn, t == activeTab) }
        refreshDetail()
    }

    private fun applyTabStyle(btn: MaterialButton, active: Boolean) {
        if (active) {
            btn.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
            btn.strokeWidth        = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.strokeColor        = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            btn.strokeWidth        = px(1)
        }
    }

    private fun refreshDetail() {
        val container = detailArea ?: return
        container.removeAllViews()
        when (activeTab) {
            Tab.APP    -> container.addView(buildAppPicker())
            Tab.URL    -> container.addView(buildUrlEditor())
            Tab.WIDGET -> container.addView(buildWidgetPicker())
            Tab.APPS   -> container.addView(buildAppsGridPicker())
            Tab.MUSIC  -> container.addView(buildMusicPicker())
            Tab.CLEAR  -> Unit
        }
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private fun buildAppPicker(): View {
        val outer = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
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
                scrollToSelected()
                appLabelEdit?.setText(apps[idx].loadLabel(context.packageManager))
            }
            list.addView(row)
            rows.add(row)
        }
        appRows = rows

        scroll.addView(list)
        outer.addView(scroll)

        // Label field below the app list
        val labelEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            hint      = context.getString(R.string.label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setText(when {
                initialConfig is ButtonConfig.AppLauncher -> initialConfig.label
                apps.isNotEmpty()                         -> apps[selectedAppIndex].loadLabel(context.packageManager)
                else                                      -> ""
            })
        }
        appLabelEdit = labelEdit
        outer.addView(labelEdit)

        return outer
    }

    private fun buildAppRow(app: ResolveInfo, selected: Boolean): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, px(64))
            setPadding(px(8), px(8), px(8), px(8))
            gravity      = Gravity.CENTER_VERTICAL
            setBackgroundColor(rowBg(selected))

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) }
                scaleType    = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(try {
                    context.packageManager.getApplicationIcon(app.activityInfo.packageName)
                } catch (_: Exception) { null })
            })

            addView(TextView(context).apply {
                text     = app.loadLabel(context.packageManager)
                textSize = 18f
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
        appLabelEdit?.setText(apps[next].loadLabel(context.packageManager))
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

    // ── Widget picker ─────────────────────────────────────────────────────────

    private fun buildWidgetPicker(): View {
        val outer = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        if (widgetProviders.isEmpty()) {
            outer.addView(TextView(context).apply {
                text     = context.getString(R.string.no_widgets)
                textSize = 18f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            })
            return outer
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        widgetScrollView = scroll

        val list = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val rows = mutableListOf<View>()
        for (i in widgetProviders.indices) {
            val row = buildWidgetRow(widgetProviders[i], i == selectedProviderIndex)
            val idx = i
            row.setOnClickListener {
                selectedProviderIndex = idx
                updateWidgetRowHighlights(rows, idx)
            }
            list.addView(row)
            rows.add(row)
        }
        widgetRows = rows

        scroll.addView(list)
        outer.addView(scroll)

        // Scroll to current selection after layout
        scroll.post {
            if (selectedProviderIndex < rows.size) {
                scroll.smoothScrollTo(0, rows[selectedProviderIndex].top)
            }
        }

        // Label field
        val labelEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            hint      = context.getString(R.string.label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setText(if (initialConfig is ButtonConfig.WidgetLauncher) initialConfig.label else "")
        }
        widgetLabelEdit = labelEdit
        outer.addView(labelEdit)

        // Icon options (same set as URL tab)
        outer.addView(buildIconOptionRow())
        val iconDetail = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
        }
        iconDetailArea = iconDetail
        outer.addView(iconDetail)
        refreshIconDetail()

        return outer
    }

    private fun buildWidgetRow(info: AppWidgetProviderInfo, selected: Boolean): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, px(64))
            setPadding(px(8), px(8), px(8), px(8))
            gravity      = Gravity.CENTER_VERTICAL
            setBackgroundColor(rowBg(selected))

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) }
                scaleType    = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(try {
                    info.loadIcon(context, context.resources.displayMetrics.densityDpi)
                } catch (_: Exception) { null })
            })

            addView(TextView(context).apply {
                text     = info.loadLabel(context.packageManager)
                textSize = 18f
                setTextColor(context.getColor(R.color.text_primary))
            })
        }
    }

    private fun moveWidgetSelection(delta: Int): Boolean {
        if (widgetProviders.isEmpty()) return false
        val next = (selectedProviderIndex + delta).coerceIn(0, widgetProviders.lastIndex)
        if (next == selectedProviderIndex) return true
        selectedProviderIndex = next
        updateWidgetRowHighlights(widgetRows, next)
        widgetScrollView?.post {
            if (next < widgetRows.size) widgetScrollView!!.smoothScrollTo(0, widgetRows[next].top)
        }
        return true
    }

    private fun updateWidgetRowHighlights(rows: List<View>, selectedIndex: Int) {
        for (i in rows.indices) rows[i].setBackgroundColor(rowBg(i == selectedIndex))
    }

    private fun rowBg(selected: Boolean) =
        context.getColor(if (selected) R.color.button_inactive else R.color.surface_dark)

    // ── Apps grid picker ──────────────────────────────────────────────────────

    private fun buildAppsGridPicker(): View {
        val outer = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        appsScrollView = scroll

        val list = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val rows = mutableListOf<View>()
        for (app in apps) {
            val pkg = app.activityInfo.packageName
            val row = buildAppCheckRow(app, selectedPackages.contains(pkg))
            list.addView(row)
            rows.add(row)
        }
        appsCheckRows = rows

        scroll.addView(list)
        outer.addView(scroll)

        val labelEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            hint      = context.getString(R.string.label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setText(if (initialConfig is ButtonConfig.AppsGrid) initialConfig.label else "")
        }
        appsGridLabelEdit = labelEdit
        outer.addView(labelEdit)

        outer.addView(buildIconOptionRow())
        val iconDetail = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
        }
        iconDetailArea = iconDetail
        outer.addView(iconDetail)
        refreshIconDetail()

        return outer
    }

    private fun buildAppCheckRow(app: android.content.pm.ResolveInfo, checked: Boolean): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, px(64))
            setPadding(px(8), px(8), px(8), px(8))
            gravity      = Gravity.CENTER_VERTICAL

            val cb = CheckBox(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked ->
                    val pkg = app.activityInfo.packageName
                    if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                }
            }
            addView(cb)

            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(px(44), px(44)).apply {
                    marginStart = px(8); marginEnd = px(12)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(try {
                    context.packageManager.getApplicationIcon(app.activityInfo.packageName)
                } catch (_: Exception) { null })
            })

            addView(TextView(context).apply {
                text     = app.loadLabel(context.packageManager)
                textSize = 18f
                setTextColor(context.getColor(R.color.text_primary))
            })

            // Tapping anywhere on the row toggles the checkbox.
            setOnClickListener { cb.isChecked = !cb.isChecked }
        }
    }

    // ── Music player picker ────────────────────────────────────────────────────

    private fun buildMusicPicker(): View {
        val outer = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        musicScrollView = scroll

        val list = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val rows = mutableListOf<View>()
        for (i in apps.indices) {
            val row = buildAppRow(apps[i], i == selectedMusicAppIndex)
            val idx = i
            row.setOnClickListener {
                selectedMusicAppIndex = idx
                updateMusicRowHighlights(rows, idx)
            }
            list.addView(row)
            rows.add(row)
        }
        musicAppRows = rows

        scroll.addView(list)
        outer.addView(scroll)
        scroll.post {
            if (selectedMusicAppIndex < rows.size) {
                scroll.smoothScrollTo(0, rows[selectedMusicAppIndex].top)
            }
        }

        val labelEdit = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            hint      = context.getString(R.string.label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setText(if (initialConfig is ButtonConfig.MusicPlayer) initialConfig.label else "")
        }
        musicLabelEdit = labelEdit
        outer.addView(labelEdit)

        outer.addView(buildIconOptionRow())
        val iconDetail = LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
        }
        iconDetailArea = iconDetail
        outer.addView(iconDetail)
        refreshIconDetail()

        return outer
    }

    private fun moveMusicSelection(delta: Int): Boolean {
        if (apps.isEmpty()) return false
        val next = (selectedMusicAppIndex + delta).coerceIn(0, apps.lastIndex)
        if (next == selectedMusicAppIndex) return true
        selectedMusicAppIndex = next
        updateMusicRowHighlights(musicAppRows, next)
        musicScrollView?.post {
            if (next < musicAppRows.size) musicScrollView!!.smoothScrollTo(0, musicAppRows[next].top)
        }
        return true
    }

    private fun updateMusicRowHighlights(rows: List<View>, selectedIndex: Int) {
        for (i in rows.indices) rows[i].setBackgroundColor(rowBg(i == selectedIndex))
    }

    // ── URL editor ────────────────────────────────────────────────────────────

    private fun buildUrlEditor(): View {
        return LinearLayout(context).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, px(8), 0, 0)

            val urlEdit = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                hint         = context.getString(R.string.url_hint)
                inputType    = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setText(if (initialConfig is ButtonConfig.UrlLauncher) initialConfig.url else "")
                setTextColor(context.getColor(R.color.text_primary))
                setHintTextColor(context.getColor(R.color.text_secondary))
                requestFocus()
            }
            urlEditText = urlEdit
            addView(urlEdit)

            val labelEdit = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
                hint      = context.getString(R.string.display_name_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setText(if (initialConfig is ButtonConfig.UrlLauncher) initialConfig.label else "")
                setTextColor(context.getColor(R.color.text_primary))
                setHintTextColor(context.getColor(R.color.text_secondary))
            }
            urlLabelEdit = labelEdit
            addView(labelEdit)

            val browserCheck = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
                text         = context.getString(R.string.open_in_browser)
                isChecked    = (initialConfig as? ButtonConfig.UrlLauncher)?.openInBrowser == true
                setTextColor(context.getColor(R.color.text_primary))
            }
            urlOpenInBrowser = browserCheck
            addView(browserCheck)

            addView(buildIconOptionRow())

            val iconDetail = LinearLayout(context).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(8) }
            }
            iconDetailArea = iconDetail
            addView(iconDetail)
            refreshIconDetail()
        }
    }

    private fun buildIconOptionRow(): View {
        val row = LinearLayout(context).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(12) }
        }

        val built = mutableMapOf<IconOption, MaterialButton>()

        fun addOption(opt: IconOption, label: String, last: Boolean = false) {
            val btn = MaterialButton(
                context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text         = label
                cornerRadius = px(16)
                textSize     = 16f
                setTextColor(context.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (!last) marginEnd = px(4)
                }
                applyIconOptionStyle(this, opt == selectedIconOption)
                setOnClickListener { selectIconOption(opt) }
            }
            row.addView(btn)
            built[opt] = btn
        }

        addOption(IconOption.NONE,   context.getString(R.string.icon_none))
        addOption(IconOption.CUSTOM, context.getString(R.string.icon_image))
        addOption(IconOption.EMOJI,  context.getString(R.string.icon_emoji), last = true)

        iconOptionBtns = built
        return row
    }

    private fun selectIconOption(opt: IconOption) {
        selectedIconOption = opt
        iconOptionBtns.forEach { (o, btn) -> applyIconOptionStyle(btn, o == selectedIconOption) }
        refreshIconDetail()
    }

    private fun applyIconOptionStyle(btn: MaterialButton, active: Boolean) {
        if (active) {
            btn.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
            btn.strokeWidth        = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.strokeColor        = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            btn.strokeWidth        = px(1)
        }
    }

    private fun refreshIconDetail() {
        val area = iconDetailArea ?: return
        area.removeAllViews()
        when (selectedIconOption) {
            IconOption.NONE -> Unit

            IconOption.EMOJI -> {
                val edit = EditText(context).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    hint         = context.getString(R.string.emoji_hint)
                    inputType    = InputType.TYPE_CLASS_TEXT
                    setSingleLine()
                    setText(when {
                        initialConfig is ButtonConfig.UrlLauncher    && initialConfig.icon is UrlIcon.Emoji -> initialConfig.icon.emoji
                        initialConfig is ButtonConfig.WidgetLauncher && initialConfig.icon is UrlIcon.Emoji -> initialConfig.icon.emoji
                        initialConfig is ButtonConfig.AppsGrid       && initialConfig.icon is UrlIcon.Emoji -> initialConfig.icon.emoji
                        initialConfig is ButtonConfig.MusicPlayer    && initialConfig.icon is UrlIcon.Emoji -> initialConfig.icon.emoji
                        else -> ""
                    })
                    setTextColor(context.getColor(R.color.text_primary))
                    setHintTextColor(context.getColor(R.color.text_secondary))
                }
                emojiEdit = edit
                area.addView(edit)
            }

            IconOption.CUSTOM -> {
                val statusText = when {
                    hasPendingImage -> context.getString(R.string.image_picked)
                    (initialConfig is ButtonConfig.UrlLauncher    && initialConfig.icon is UrlIcon.CustomFile) ||
                    (initialConfig is ButtonConfig.WidgetLauncher && initialConfig.icon is UrlIcon.CustomFile) ||
                    (initialConfig is ButtonConfig.AppsGrid       && initialConfig.icon is UrlIcon.CustomFile) ||
                    (initialConfig is ButtonConfig.MusicPlayer    && initialConfig.icon is UrlIcon.CustomFile) ->
                        context.getString(R.string.image_current)
                    else -> null
                }
                if (statusText != null) {
                    area.addView(TextView(context).apply {
                        text         = statusText
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        setTextColor(context.getColor(R.color.text_secondary))
                    })
                }
                area.addView(MaterialButton(context).apply {
                    text         = context.getString(R.string.pick_image)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(4) }
                    setOnClickListener { onPickImageRequest?.invoke() }
                })
            }
        }
    }

    // ── Config assembly ───────────────────────────────────────────────────────

    private fun buildConfig(): ButtonConfig {
        return when (activeTab) {
            Tab.APP -> {
                val app = apps.getOrNull(selectedAppIndex) ?: return ButtonConfig.Empty
                val defaultLabel = app.loadLabel(context.packageManager).toString()
                val label = appLabelEdit?.text?.toString()?.trim().orEmpty().ifEmpty { defaultLabel }
                ButtonConfig.AppLauncher(
                    packageName = app.activityInfo.packageName,
                    label       = label
                )
            }
            Tab.URL -> {
                val url = urlEditText?.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return ButtonConfig.Empty
                val label = urlLabelEdit?.text?.toString()?.trim().orEmpty()
                val icon  = when (selectedIconOption) {
                    IconOption.NONE   -> UrlIcon.None
                    IconOption.CUSTOM -> UrlIcon.CustomFile
                    IconOption.EMOJI  -> UrlIcon.Emoji(
                        emojiEdit?.text?.toString()?.trim() ?: ""
                    )
                }
                ButtonConfig.UrlLauncher(url, label, icon,
                    openInBrowser = urlOpenInBrowser?.isChecked == true)
            }
            Tab.WIDGET -> {
                val prov = widgetProviders.getOrNull(selectedProviderIndex)
                    ?: return ButtonConfig.Empty
                // Preserve the existing appWidgetId if provider hasn't changed
                val existingId = if (initialConfig is ButtonConfig.WidgetLauncher &&
                                     initialConfig.provider == prov.provider)
                                     initialConfig.appWidgetId else -1
                val label = widgetLabelEdit?.text?.toString()?.trim().orEmpty()
                    .ifEmpty { prov.loadLabel(context.packageManager) }
                val icon  = when (selectedIconOption) {
                    IconOption.NONE   -> UrlIcon.None
                    IconOption.CUSTOM -> UrlIcon.CustomFile
                    IconOption.EMOJI  -> UrlIcon.Emoji(
                        emojiEdit?.text?.toString()?.trim() ?: ""
                    )
                }
                ButtonConfig.WidgetLauncher(prov.provider, existingId, label, icon)
            }
            Tab.APPS -> {
                val selectedApps = apps
                    .filter { selectedPackages.contains(it.activityInfo.packageName) }
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
                val label = appsGridLabelEdit?.text?.toString()?.trim().orEmpty()
                val icon  = when (selectedIconOption) {
                    IconOption.NONE   -> UrlIcon.None
                    IconOption.CUSTOM -> UrlIcon.CustomFile
                    IconOption.EMOJI  -> UrlIcon.Emoji(emojiEdit?.text?.toString()?.trim() ?: "")
                }
                ButtonConfig.AppsGrid(selectedApps, label, icon)
            }
            Tab.MUSIC -> {
                val app = apps.getOrNull(selectedMusicAppIndex)
                val playerPkg = app?.activityInfo?.packageName ?: ""
                val label = musicLabelEdit?.text?.toString()?.trim().orEmpty()
                val icon  = when (selectedIconOption) {
                    IconOption.NONE   -> UrlIcon.None
                    IconOption.CUSTOM -> UrlIcon.CustomFile
                    IconOption.EMOJI  -> UrlIcon.Emoji(emojiEdit?.text?.toString()?.trim() ?: "")
                }
                ButtonConfig.MusicPlayer(playerPkg, label, icon)
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
