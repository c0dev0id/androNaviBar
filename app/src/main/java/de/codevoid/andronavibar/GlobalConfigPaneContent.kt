package de.codevoid.andronavibar

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Global configuration pane shown by the Configure button.
 *
 * Split-pane layout: right side shows a compact button list (active checkbox,
 * icon, label, drag handle) with immediate-save changes. Left side shows the
 * detail editor for a selected button with Save/Cancel.
 */
class GlobalConfigPaneContent(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val callbacks: Callbacks
) : PaneContent {

    interface Callbacks {
        fun onReloadButton(index: Int)
        fun onReloadAll()
        fun onActiveChanged(index: Int, active: Boolean)
        fun onAddButton()
        fun onRemoveLastButton()
        fun onPickImage(buttonIndex: Int)
        fun onWidgetBind(buttonIndex: Int, provider: ComponentName)
        fun onWidgetCleanup(appWidgetId: Int)
    }

    private var rootView: LinearLayout? = null
    private var buttonListContainer: LinearLayout? = null
    private var detailContainer: FrameLayout? = null
    private var selectedButtonIndex: Int = -1
    private var editSnapshot: Map<String, String?> = emptyMap()

    private val iconCache = mutableMapOf<Int, Drawable?>()

    private var cachedApps: List<ResolveInfo>? = null
    private var cachedWidgets: List<AppWidgetProviderInfo>? = null

    private val installedApps: List<ResolveInfo>
        get() = cachedApps ?: run {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager
                .queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
                .also { cachedApps = it }
        }

    private val widgetProviders: List<AppWidgetProviderInfo>
        get() = cachedWidgets ?: run {
            AppWidgetManager.getInstance(context)
                .installedProviders
                .sortedBy { it.loadLabel(context.packageManager).lowercase() }
                .also { cachedWidgets = it }
        }

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
        buttonListContainer = null
        detailContainer = null
        selectedButtonIndex = -1
        iconCache.clear()
        cachedApps = null
        cachedWidgets = null
    }

    /** Update the currently-edited entry and refresh the detail editor. */
    fun rebuild() {
        val idx = selectedButtonIndex
        if (idx >= 0) {
            iconCache.remove(idx)
            replaceEntry(idx)
            refreshDetailEditor()
        }
    }

    private fun rebuildButtonList() {
        val container = buttonListContainer ?: return
        container.removeAllViews()
        iconCache.clear()
        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) container.addView(buildButtonListEntry(i))
        container.addView(buildFooter())
    }

    /** Replace a single entry in the button list without touching other entries. */
    private fun replaceEntry(index: Int) {
        val container = buttonListContainer ?: return
        if (index < 0 || index >= container.childCount) return
        container.removeViewAt(index)
        container.addView(buildButtonListEntry(index), index)
    }

    // ── Detail editor (left side) ──────────────────────────────────────────

    private fun showDetailEditor(index: Int) {
        if (index == selectedButtonIndex) {
            clearDetailEditor()
            return
        }
        // Switching buttons: discard unsaved changes on the previous one
        if (selectedButtonIndex >= 0) {
            val prev = selectedButtonIndex
            restoreSnapshot(prev, editSnapshot)
            callbacks.onReloadButton(prev)
            iconCache.remove(prev)
            replaceEntry(prev)
        }
        selectedButtonIndex = index
        editSnapshot = snapshotButton(index)
        refreshDetailEditor()
        setEntryHighlight(index, true)
    }

    private fun clearDetailEditor() {
        val prev = selectedButtonIndex
        detailContainer?.removeAllViews()
        selectedButtonIndex = -1
        editSnapshot = emptyMap()
        if (prev >= 0) {
            iconCache.remove(prev)
            replaceEntry(prev)
        }
    }

    private fun setEntryHighlight(index: Int, selected: Boolean) {
        val view = buttonListContainer?.getChildAt(index) ?: return
        (view.background as? GradientDrawable)?.setColor(
            context.getColor(if (selected) R.color.colorPrimary else R.color.surface_card)
        )
    }

    private fun refreshDetailEditor() {
        val container = detailContainer ?: return
        val index = selectedButtonIndex
        if (index < 0) return
        container.removeAllViews()

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
            val p = context.resources.dpToPx(16)
            setPadding(p, p, p, p)
        }

        val type = prefs.getString("btn_${index}_type", null)
        val label = prefs.getString("btn_${index}_label", "") ?: ""

        // Header
        content.addView(TextView(context).apply {
            text = "Button ${index + 1}" + if (label.isNotEmpty()) " \u2014 $label" else ""
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        // Type selector
        content.addView(buildTypeSelector(index, type))

        // Label field
        if (type != null) content.addView(buildLabelField(index, label))

        // Type-specific config
        if (type != null) buildTypeConfig(index, type)?.let { content.addView(it) }

        // Icon selector (non-app types)
        if (type != null && type != "app") {
            val iconType = prefs.getString("btn_${index}_icon_type", null)
            val iconData = prefs.getString("btn_${index}_icon_data", null)
            content.addView(buildIconSelector(index, iconType, iconData))
        }

        // Save / Cancel
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(16) }
            gravity = Gravity.START
        }

        btnRow.addView(makeActionButton("Save") {
            editSnapshot = emptyMap()
            clearDetailEditor()
        })

        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.resources.dpToPx(12), 0)
        })

        btnRow.addView(makeActionButton("Cancel") {
            restoreSnapshot(index, editSnapshot)
            callbacks.onReloadButton(index)
            clearDetailEditor()
        })

        content.addView(btnRow)
        scroll.addView(content)
        container.addView(scroll)
    }

    private fun snapshotButton(index: Int): Map<String, String?> {
        val keys = BUTTON_PREF_SUFFIXES
        return keys.associateWith { k -> prefs.getString("btn_$index$k", null) }
    }

    private fun restoreSnapshot(index: Int, snapshot: Map<String, String?>) {
        if (snapshot.isEmpty()) return
        val edit = prefs.edit()
        for ((k, v) in snapshot) {
            if (v != null) edit.putString("btn_$index$k", v) else edit.remove("btn_$index$k")
        }
        edit.apply()
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(context.getColor(R.color.surface_dark))
        }

        // Left side: detail editor (starts empty)
        val detail = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 2f)
        }
        detailContainer = detail
        root.addView(detail)

        // Right side: scrollable button list
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        }

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
            val p = context.resources.dpToPx(12)
            setPadding(p, p, p, p)
        }
        buttonListContainer = list

        list.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> true
                DragEvent.ACTION_DROP -> {
                    val from = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull() ?: return@setOnDragListener false
                    val to = dropTargetIndex(list, event.y)
                    if (from != to && to >= 0) {
                        if (selectedButtonIndex >= 0) clearDetailEditor()
                        moveButtonPrefs(from, to)
                        iconCache.clear()
                        callbacks.onReloadAll()
                        rebuildButtonList()
                    }
                    true
                }
                else -> true
            }
        }

        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) list.addView(buildButtonListEntry(i))
        list.addView(buildFooter())

        scroll.addView(list)
        root.addView(scroll)
        return root
    }

    /** Determine which button slot the drop Y coordinate corresponds to. */
    private fun dropTargetIndex(list: LinearLayout, y: Float): Int {
        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) {
            val child = list.getChildAt(i) ?: continue
            val mid = child.top + child.height / 2f
            if (y < mid) return i
        }
        return count - 1
    }

    // ── Right-side button list entries ───────────────────────────────────────

    private fun buildButtonListEntry(index: Int): LinearLayout {
        val type = prefs.getString("btn_${index}_type", null)
        val label = prefs.getString("btn_${index}_label", "") ?: ""
        val active = prefs.getBoolean("btn_${index}_active", true)
        val displayName = label.ifEmpty { type?.replaceFirstChar { it.uppercase() } ?: "Empty" }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = context.resources.dpToPx(4)
            }
            val p = context.resources.dpToPx(8)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.resources.dpToPx(8).toFloat()
                setColor(context.getColor(
                    if (index == selectedButtonIndex) R.color.colorPrimary else R.color.surface_card
                ))
            }
        }

        // Active checkbox
        val checkbox = CheckBox(context).apply {
            isChecked = active
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnCheckedChangeListener { _, isChecked ->
                callbacks.onActiveChanged(index, isChecked)
            }
        }
        row.addView(checkbox)

        // Icon preview
        buildIconPreview(index)?.let { row.addView(it) }

        // Label
        row.addView(TextView(context).apply {
            text = displayName
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = context.resources.dpToPx(8)
            }
            isSingleLine = true
        })

        // Drag handle — long press starts drag
        val handle = TextView(context).apply {
            text = "\u2261" // ≡
            textSize = 20f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = context.resources.dpToPx(8)
            }
        }
        handle.setOnLongClickListener {
            val clip = ClipData(
                ClipDescription("button", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(index.toString())
            )
            row.startDragAndDrop(clip, View.DragShadowBuilder(row), null, 0)
            true
        }
        row.addView(handle)

        // Tap opens detail editor on the left side
        row.setOnClickListener { showDetailEditor(index) }

        return row
    }

    private fun buildIconPreview(index: Int): View? {
        val iconType = prefs.getString("btn_${index}_icon_type", null)
        val iconData = prefs.getString("btn_${index}_icon_data", null)
        val size = context.resources.dpToPx(28)

        // Emoji: lightweight TextView, no caching needed
        if (iconType == "emoji" && !iconData.isNullOrEmpty()) {
            return TextView(context).apply {
                text = iconData
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }

        // Drawable icons: cache to avoid repeated IPC / bitmap decode
        val drawable = iconCache.getOrPut(index) { loadIconDrawable(index) }
            ?: return null
        return ImageView(context).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun loadIconDrawable(index: Int): Drawable? {
        val iconType = prefs.getString("btn_${index}_icon_type", null)
        if (iconType == "custom") {
            val file = buttonIconFile(context.filesDir, index)
            return if (file.exists()) BitmapDrawable(context.resources, BitmapFactory.decodeFile(file.absolutePath)) else null
        }
        val type = prefs.getString("btn_${index}_type", null)
        if (type == "app") {
            val pkg = prefs.getString("btn_${index}_value", null) ?: return null
            return try { context.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
        }
        return null
    }

    // ── Type selector ───────────────────────────────────────────────────────

    private fun buildTypeSelector(index: Int, currentType: String?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
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
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
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

    // ── Type-specific configuration ──────────────────────────────────────────

    private fun buildTypeConfig(index: Int, type: String): View? {
        return when (type) {
            "app"    -> buildAppConfig(index)
            "url"    -> buildUrlConfig(index)
            "widget" -> buildWidgetConfig(index)
            "apps"   -> buildAppsGridConfig(index)
            "music"  -> buildMusicConfig(index)
            else     -> null
        }
    }

    private fun buildAppConfig(index: Int): View {
        val currentPkg = prefs.getString("btn_${index}_value", null)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "App: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        if (installedApps.isEmpty()) {
            row.addView(TextView(context).apply {
                text = "No apps found"
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            return row
        }

        val labels = installedApps.map { it.loadLabel(context.packageManager).toString() }
        val selectedIndex = if (currentPkg != null) {
            installedApps.indexOfFirst { it.activityInfo.packageName == currentPkg }
                .coerceAtLeast(0)
        } else 0

        val spinner = makeDarkSpinner(labels, selectedIndex) { position ->
            val app = installedApps[position]
            val appLabel = app.loadLabel(context.packageManager).toString()
            prefs.edit()
                .putString("btn_${index}_value", app.activityInfo.packageName)
                .putString("btn_${index}_label", appLabel)
                .apply()
            callbacks.onReloadButton(index)
            rebuild()
        }
        row.addView(spinner)

        return row
    }

    private fun buildUrlConfig(index: Int): View {
        val currentUrl = prefs.getString("btn_${index}_value", "") ?: ""
        val currentOpenBrowser = prefs.getString("btn_${index}_open_browser", null) == "true"

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
        }

        // URL field
        val urlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        urlRow.addView(TextView(context).apply {
            text = "URL: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val urlEdit = EditText(context).apply {
            setText(currentUrl)
            hint = "https://\u2026"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setBackgroundColor(Color.TRANSPARENT)
        }

        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("btn_${index}_value", s?.toString() ?: "").apply()
                callbacks.onReloadButton(index)
            }
        })

        urlRow.addView(urlEdit)
        wrapper.addView(urlRow)

        // Open in browser checkbox
        val checkbox = CheckBox(context).apply {
            text = "Open in browser"
            isChecked = currentOpenBrowser
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(4) }
        }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString("btn_${index}_open_browser", "true").apply()
            else prefs.edit().remove("btn_${index}_open_browser").apply()
            callbacks.onReloadButton(index)
        }

        wrapper.addView(checkbox)
        return wrapper
    }

    private fun buildWidgetConfig(index: Int): View {
        val currentValue = prefs.getString("btn_${index}_value", null)
        val currentProvider = currentValue?.let { ComponentName.unflattenFromString(it) }
        val currentWidgetId = prefs.getString("btn_${index}_widget_id", null)
            ?.toIntOrNull() ?: -1

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
        }

        if (widgetProviders.isEmpty()) {
            wrapper.addView(TextView(context).apply {
                text = "No widget providers found"
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            return wrapper
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Widget: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        // Placeholder + real providers
        val placeholder = "Select widget\u2026"
        val labels = listOf(placeholder) +
            widgetProviders.map { it.loadLabel(context.packageManager) }
        val selectedIndex = if (currentProvider != null) {
            val provIdx = widgetProviders.indexOfFirst { it.provider == currentProvider }
            if (provIdx >= 0) provIdx + 1 else 0
        } else 0

        val spinner = makeDarkSpinner(labels, selectedIndex) { position ->
            if (position == 0) return@makeDarkSpinner // placeholder
            val provider = widgetProviders[position - 1]
            prefs.edit()
                .putString("btn_${index}_value", provider.provider.flattenToString())
                .apply()
            val label = prefs.getString("btn_${index}_label", "") ?: ""
            if (label.isEmpty()) {
                prefs.edit().putString(
                    "btn_${index}_label",
                    provider.loadLabel(context.packageManager)
                ).apply()
            }
            callbacks.onWidgetBind(index, provider.provider)
        }
        row.addView(spinner)
        wrapper.addView(row)

        // Bind status + retry button
        if (currentWidgetId != -1) {
            wrapper.addView(TextView(context).apply {
                text = "\u2713 Widget bound"
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = context.resources.dpToPx(4)
                }
            })
        } else if (currentProvider != null) {
            wrapper.addView(makeActionButton("Bind widget\u2026") {
                callbacks.onWidgetBind(index, currentProvider)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = context.resources.dpToPx(4)
                }
            })
        }

        return wrapper
    }

    private fun buildAppsGridConfig(index: Int): View {
        val currentApps = prefs.getString("btn_${index}_apps", "") ?: ""
        val selectedPkgs = currentApps.split("|").filter { it.isNotEmpty() }.toMutableSet()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "${selectedPkgs.size} app${if (selectedPkgs.size != 1) "s" else ""} selected"
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        row.addView(makeActionButton("Choose\u2026") {
            showAppsPickerDialog(index)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        return row
    }

    private fun showAppsPickerDialog(index: Int) {
        val currentApps = prefs.getString("btn_${index}_apps", "") ?: ""
        val selectedPkgs = currentApps.split("|").filter { it.isNotEmpty() }.toMutableSet()

        val labels = installedApps.map {
            it.loadLabel(context.packageManager).toString()
        }.toTypedArray()
        val packages = installedApps.map { it.activityInfo.packageName }
        val checked = packages.map { selectedPkgs.contains(it) }.toBooleanArray()

        AlertDialog.Builder(context)
            .setTitle("Select apps")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selectedPkgs.add(packages[which])
                else selectedPkgs.remove(packages[which])
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.edit()
                    .putString("btn_${index}_apps", selectedPkgs.joinToString("|"))
                    .apply()
                callbacks.onReloadButton(index)
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildMusicConfig(index: Int): View {
        val currentPkg = prefs.getString("btn_${index}_value", null)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Player: "
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        if (installedApps.isEmpty()) {
            row.addView(TextView(context).apply {
                text = "No apps found"
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            return row
        }

        val labels = installedApps.map { it.loadLabel(context.packageManager).toString() }
        val selectedIndex = if (currentPkg != null) {
            installedApps.indexOfFirst { it.activityInfo.packageName == currentPkg }
                .coerceAtLeast(0)
        } else 0

        val spinner = makeDarkSpinner(labels, selectedIndex) { position ->
            val app = installedApps[position]
            prefs.edit()
                .putString("btn_${index}_value", app.activityInfo.packageName)
                .apply()
            callbacks.onReloadButton(index)
        }
        row.addView(spinner)

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
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(8) }
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
                topMargin = context.resources.dpToPx(4)
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
        val hasFile = buttonIconFile(context.filesDir, index).exists()
        return makeActionButton(if (hasFile) "Change image\u2026" else "Pick image\u2026") {
            callbacks.onPickImage(index)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = context.resources.dpToPx(4)
            }
        }
    }

    // ── Footer (add / remove) ───────────────────────────────────────────────

    private fun buildFooter(): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(16) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER
        }

        row.addView(makeActionButton("+ Add Button") {
            if (selectedButtonIndex >= 0) clearDetailEditor()
            callbacks.onAddButton()
            val newIndex = prefs.getInt("button_count", 6) - 1
            val container = buttonListContainer ?: return@makeActionButton
            // Insert before the footer
            container.addView(buildButtonListEntry(newIndex), container.childCount - 1)
        })

        row.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.resources.dpToPx(16), 0)
        })

        row.addView(makeActionButton("\u2212 Remove Last") { // −
            if (selectedButtonIndex >= 0) clearDetailEditor()
            val container = buttonListContainer ?: return@makeActionButton
            val count = prefs.getInt("button_count", 6)
            if (count <= 1) return@makeActionButton
            callbacks.onRemoveLastButton()
            // Remove last entry (before footer)
            iconCache.remove(count - 1)
            container.removeViewAt(container.childCount - 2)
        })

        wrapper.addView(row)

        wrapper.addView(makeActionButton("Check for Update") {
            (context as? android.app.Activity)?.let { UpdateChecker.check(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(16)
            }
        })

        return wrapper
    }

    // ── Type change ─────────────────────────────────────────────────────────

    private fun changeButtonType(index: Int, typeKey: String?) {
        // Clean up old widget if changing away from widget type
        val oldType = prefs.getString("btn_${index}_type", null)
        if (oldType == "widget") {
            val widgetId = prefs.getString("btn_${index}_widget_id", null)
                ?.toIntOrNull() ?: -1
            if (widgetId != -1) callbacks.onWidgetCleanup(widgetId)
        }

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
            when (typeKey) {
                "app" -> {
                    val first = installedApps.firstOrNull()
                    if (first != null) {
                        edit.putString("btn_${index}_value",
                            first.activityInfo.packageName)
                        edit.putString("btn_${index}_label",
                            first.loadLabel(context.packageManager).toString())
                    } else {
                        edit.putString("btn_${index}_label", "")
                    }
                }
                "url" -> edit.putString("btn_${index}_label", "")
                "widget" -> edit.putString("btn_${index}_label", "")
                "apps" -> edit.putString("btn_${index}_label", "Apps")
                "music" -> {
                    val first = installedApps.firstOrNull()
                    if (first != null) {
                        edit.putString("btn_${index}_value",
                            first.activityInfo.packageName)
                    }
                    edit.putString("btn_${index}_label", "Music")
                }
            }
        }

        edit.apply()
        buttonIconFile(context.filesDir, index).delete()
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
        if (option == "none") buttonIconFile(context.filesDir, index).delete()
    }

    // ── Button reorder ──────────────────────────────────────────────────────

    /** Move button at [from] to [to], shifting intermediate entries. */
    private fun moveButtonPrefs(from: Int, to: Int) {
        if (from == to) return
        val keys = BUTTON_PREF_SUFFIXES

        // Save the moved button's data
        val movedVals = keys.associateWith { k -> prefs.getString("btn_$from$k", null) }
        val movedActive = prefs.getBoolean("btn_${from}_active", true)
        val movedIcon = buttonIconFile(context.filesDir, from)
        val tmpIcon = File(context.filesDir, "btn_move_tmp.png")
        if (movedIcon.exists()) movedIcon.copyTo(tmpIcon, overwrite = true) else tmpIcon.delete()

        // Shift entries between from and to (single batch write)
        val dir = if (from < to) 1 else -1
        val edit = prefs.edit()
        var i = from
        while (i != to) {
            val next = i + dir
            for (k in keys) {
                val v = prefs.getString("btn_$next$k", null)
                if (v != null) edit.putString("btn_$i$k", v) else edit.remove("btn_$i$k")
            }
            edit.putBoolean("btn_${i}_active", prefs.getBoolean("btn_${next}_active", true))
            // Shift icon file
            val src = buttonIconFile(context.filesDir, next)
            val dst = buttonIconFile(context.filesDir, i)
            if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
            i = next
        }

        // Place moved button at destination
        for (k in keys) {
            val v = movedVals[k]
            if (v != null) edit.putString("btn_$to$k", v) else edit.remove("btn_$to$k")
        }
        edit.putBoolean("btn_${to}_active", movedActive)
        edit.apply()
        val dstIcon = buttonIconFile(context.filesDir, to)
        if (tmpIcon.exists()) tmpIcon.copyTo(dstIcon, overwrite = true) else dstIcon.delete()
        tmpIcon.delete()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeDarkSpinner(
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ): Spinner {
        val spinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPopupBackgroundDrawable(
                ColorDrawable(context.getColor(R.color.surface_card))
            )
        }

        val adapter = object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_item, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(context.getColor(R.color.text_primary))
                    textSize = 14f
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(context.getColor(R.color.text_primary))
                    textSize = 14f
                    val p = context.resources.dpToPx(12)
                    setPadding(p, p, p, p)
                }
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selectedIndex)

        // Only fire callback on genuine user interaction, not programmatic selection.
        var userTouched = false
        spinner.setOnTouchListener { _, _ ->
            userTouched = true
            false
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (!userTouched) return
                onSelected(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        return spinner
    }

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
            val hp = context.resources.dpToPx(8)
            val vp = context.resources.dpToPx(4)
            setPadding(hp, vp, hp, vp)
            cornerRadius = context.resources.dpToPx(8)
            backgroundTintList = ColorStateList.valueOf(
                context.getColor(if (active) R.color.colorPrimary else R.color.button_inactive)
            )
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = context.resources.dpToPx(4)
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
            cornerRadius = context.resources.dpToPx(12)
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { onClick() }
        }
    }

    companion object {
        private val BUTTON_PREF_SUFFIXES = listOf(
            "_type", "_value", "_label", "_icon_type", "_icon_data",
            "_widget_id", "_open_browser", "_apps"
        )
    }
}
