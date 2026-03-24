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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
        fun onRemoveButton(index: Int)
        fun onPickImage(buttonIndex: Int)
        fun onWidgetBind(buttonIndex: Int, provider: ComponentName)
        fun onWidgetCleanup(appWidgetId: Int)
    }

    private var rootView: LinearLayout? = null
    private var buttonListContainer: LinearLayout? = null
    private var detailContainer: FrameLayout? = null
    private var selectedButtonIndex: Int = -1
    private val pendingEdits = mutableMapOf<String, String?>()
    private var dragFromIndex = -1
    private var dragGhostIndex = -1

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
        pendingEdits.clear()
        iconCache.clear()
        cachedApps = null
        cachedWidgets = null
    }

    /** Refresh the detail editor (e.g. after type change or image pick). */
    fun rebuild() {
        if (selectedButtonIndex >= 0) refreshDetailEditor()
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
            selectedButtonIndex = -1
            pendingEdits.clear()
            replaceEntry(prev)
        }
        selectedButtonIndex = index
        refreshDetailEditor()
        setEntryHighlight(index, true)
    }

    private fun clearDetailEditor() {
        val prev = selectedButtonIndex
        detailContainer?.removeAllViews()
        selectedButtonIndex = -1
        pendingEdits.clear()
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

        val type = getEditValue("btn_${index}_type")
        val label = getEditValue("btn_${index}_label") ?: ""

        // Header
        content.addView(TextView(context).apply {
            text = "Button ${index + 1}" + if (label.isNotEmpty()) " \u2014 $label" else ""
            textSize = 22f
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
            val iconType = getEditValue("btn_${index}_icon_type")
            val iconData = getEditValue("btn_${index}_icon_data")
            content.addView(buildIconSelector(index, iconType, iconData))
        }

        // Save / Cancel
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(16) }
            gravity = Gravity.START
        }

        btnRow.addView(makeActionButton("Save") {
            val idx = selectedButtonIndex
            val edit = prefs.edit()
            for ((key, value) in pendingEdits) {
                if (value != null) edit.putString(key, value) else edit.remove(key)
            }
            edit.apply()
            // Clean up orphaned icon file
            if (prefs.getString("btn_${idx}_icon_type", null) != "custom") {
                buttonIconFile(context.filesDir, idx).delete()
            }
            iconCache.remove(idx)
            callbacks.onReloadButton(idx)
            clearDetailEditor()
        })

        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.resources.dpToPx(12), 0)
        })

        btnRow.addView(makeActionButton("Cancel") {
            clearDetailEditor()
        })

        content.addView(btnRow)
        scroll.addView(content)
        container.addView(scroll)
    }

    /** Read a value from pending edits, falling back to saved prefs. */
    private fun getEditValue(key: String): String? =
        if (pendingEdits.containsKey(key)) pendingEdits[key] else prefs.getString(key, null)

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(context.getColor(R.color.surface_dark))
        }

        // Left side: detail editor (starts empty)
        val detail = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
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
            val p = context.resources.dpToPx(16)
            setPadding(p, p, p, p)
        }
        buttonListContainer = list

        list.setOnDragListener { _, event ->
            val count = prefs.getInt("button_count", 6)
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    dragFromIndex = event.localState as? Int ?: -1
                    dragGhostIndex = dragFromIndex
                    if (dragFromIndex in 0 until count) {
                        list.getChildAt(dragFromIndex)?.alpha = 0.4f
                    }
                    dragFromIndex >= 0
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val target = dropTargetIndex(list, event.y)
                    if (target in 0 until count && target != dragGhostIndex) {
                        val child = list.getChildAt(dragGhostIndex)
                        if (child != null) {
                            list.removeViewAt(dragGhostIndex)
                            list.addView(child, target)
                            dragGhostIndex = target
                        }
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    if (dragFromIndex >= 0 && dragGhostIndex != dragFromIndex) {
                        if (selectedButtonIndex >= 0) clearDetailEditor()
                        moveButtonPrefs(dragFromIndex, dragGhostIndex)
                        iconCache.clear()
                        callbacks.onReloadAll()
                    }
                    rebuildButtonList()
                    dragFromIndex = -1
                    dragGhostIndex = -1
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (dragFromIndex >= 0) {
                        rebuildButtonList()
                        dragFromIndex = -1
                        dragGhostIndex = -1
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
                bottomMargin = context.resources.dpToPx(6)
            }
            val p = context.resources.dpToPx(12)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.resources.dpToPx(12).toFloat()
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
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = context.resources.dpToPx(12)
            }
            isSingleLine = true
        })

        // Drag handle — long press starts drag
        val handleSize = context.resources.dpToPx(40)
        val handle = TextView(context).apply {
            text = "\u2261" // ≡
            textSize = 32f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(handleSize, handleSize).apply {
                marginStart = context.resources.dpToPx(12)
            }
        }
        handle.setOnLongClickListener {
            val clip = ClipData(
                ClipDescription("button", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(index.toString())
            )
            row.startDragAndDrop(clip, View.DragShadowBuilder(row), index, 0)
            true
        }
        row.addView(handle)

        // Delete button
        row.addView(TextView(context).apply {
            text = "\u2715" // ✕
            textSize = 22f
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(handleSize, handleSize).apply {
                marginStart = context.resources.dpToPx(16)
            }
            setOnClickListener {
                val count = prefs.getInt("button_count", 6)
                if (count <= 1) return@setOnClickListener
                if (selectedButtonIndex >= 0) clearDetailEditor()
                removeButtonAt(index)
            }
        })

        // Tap opens detail editor on the left side
        row.setOnClickListener { showDetailEditor(index) }

        return row
    }

    private fun buildIconPreview(index: Int): View? {
        val iconType = prefs.getString("btn_${index}_icon_type", null)
        val iconData = prefs.getString("btn_${index}_icon_data", null)
        val size = context.resources.dpToPx(40)

        // Emoji: lightweight TextView, no caching needed
        if (iconType == "emoji" && !iconData.isNullOrEmpty()) {
            return TextView(context).apply {
                text = iconData
                textSize = 24f
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
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Type: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val types = listOf(
            null     to "Empty",
            "app"    to "App",
            "url"    to "URL",
            "widget" to "Widget",
            "apps"   to "Apps Grid",
            "music"  to "Music Player"
        )

        val labels = types.map { it.second }
        val selectedIdx = types.indexOfFirst { it.first == currentType }.coerceAtLeast(0)

        row.addView(makeChooser("Button type", labels, selectedIdx) { position ->
            val newType = types[position].first
            if (newType != currentType) {
                changeButtonType(index, newType)
                rebuild()
            }
        })

        return row
    }

    // ── Label field ─────────────────────────────────────────────────────────

    private fun buildLabelField(index: Int, currentLabel: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Label: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val edit = EditText(context).apply {
            setText(currentLabel)
            textSize = 18f
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
                pendingEdits["btn_${index}_label"] = s?.toString() ?: ""
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
        val currentPkg = getEditValue("btn_${index}_value")

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "App: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        if (installedApps.isEmpty()) {
            row.addView(TextView(context).apply {
                text = "No apps found"
                textSize = 18f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            return row
        }

        val labels = installedApps.map { it.loadLabel(context.packageManager).toString() }
        val selectedIndex = if (currentPkg != null) {
            installedApps.indexOfFirst { it.activityInfo.packageName == currentPkg }
                .coerceAtLeast(0)
        } else -1

        row.addView(makeChooser("Select app", labels, selectedIndex) { position ->
            val app = installedApps[position]
            pendingEdits["btn_${index}_value"] = app.activityInfo.packageName
            pendingEdits["btn_${index}_label"] = app.loadLabel(context.packageManager).toString()
            rebuild()
        })

        return row
    }

    private fun buildUrlConfig(index: Int): View {
        val currentUrl = getEditValue("btn_${index}_value") ?: ""
        val currentOpenBrowser = getEditValue("btn_${index}_open_browser") == "true"

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
        }

        // URL field
        val urlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        urlRow.addView(TextView(context).apply {
            text = "URL: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val urlEdit = EditText(context).apply {
            setText(currentUrl)
            hint = "https://\u2026"
            textSize = 18f
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
                pendingEdits["btn_${index}_value"] = s?.toString() ?: ""
            }
        })

        urlRow.addView(urlEdit)
        wrapper.addView(urlRow)

        // Open in browser checkbox
        val checkbox = CheckBox(context).apply {
            text = "Open in browser"
            isChecked = currentOpenBrowser
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(4) }
        }
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            pendingEdits["btn_${index}_open_browser"] = if (isChecked) "true" else null
        }

        wrapper.addView(checkbox)
        return wrapper
    }

    private fun buildWidgetConfig(index: Int): View {
        val currentValue = getEditValue("btn_${index}_value")
        val currentProvider = currentValue?.let { ComponentName.unflattenFromString(it) }
        val currentWidgetId = getEditValue("btn_${index}_widget_id")
            ?.toIntOrNull() ?: -1

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
        }

        if (widgetProviders.isEmpty()) {
            wrapper.addView(TextView(context).apply {
                text = "No widget providers found"
                textSize = 18f
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
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val labels = widgetProviders.map { info ->
            val name = info.loadLabel(context.packageManager).toString()
            val appName = try {
                context.packageManager
                    .getApplicationInfo(info.provider.packageName, PackageManager.ApplicationInfoFlags.of(0))
                    .loadLabel(context.packageManager).toString()
                    .takeIf { it != name }
            } catch (_: PackageManager.NameNotFoundException) { null }
            val desc = info.loadDescription(context)?.toString()?.takeIf { it.isNotBlank() }
            val size = when {
                info.targetCellWidth > 0 && info.targetCellHeight > 0 ->
                    "${info.targetCellWidth}\u00D7${info.targetCellHeight} cells"
                info.minWidth > 0 && info.minHeight > 0 ->
                    "${info.minWidth}\u00D7${info.minHeight}dp"
                else -> null
            }
            val sub = listOfNotNull(appName, desc, size).joinToString(" \u00B7 ")
            if (sub.isEmpty()) name else "$name\n$sub"
        }
        val selectedIndex = if (currentProvider != null) {
            widgetProviders.indexOfFirst { it.provider == currentProvider }
        } else -1

        row.addView(makeChooser("Select widget", labels, selectedIndex) { position ->
            val provider = widgetProviders[position]
            pendingEdits["btn_${index}_value"] = provider.provider.flattenToString()
            if (getEditValue("btn_${index}_label").isNullOrEmpty()) {
                pendingEdits["btn_${index}_label"] = provider.loadLabel(context.packageManager)
            }
            // Widget binding is immediate (system side effect); let the
            // resulting widget_id in prefs be visible through getEditValue.
            pendingEdits.remove("btn_${index}_widget_id")
            callbacks.onWidgetBind(index, provider.provider)
        })
        wrapper.addView(row)

        // Bind status + retry button
        if (currentWidgetId != -1) {
            wrapper.addView(TextView(context).apply {
                text = "\u2713 Widget bound"
                textSize = 16f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = context.resources.dpToPx(6)
                }
            })
        } else if (currentProvider != null) {
            wrapper.addView(makeActionButton("Bind widget\u2026") {
                callbacks.onWidgetBind(index, currentProvider)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = context.resources.dpToPx(6)
                }
            })
        }

        return wrapper
    }

    private fun buildAppsGridConfig(index: Int): View {
        val currentApps = getEditValue("btn_${index}_apps") ?: ""
        val selectedPkgs = currentApps.split("|").filter { it.isNotEmpty() }.toMutableSet()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "${selectedPkgs.size} app${if (selectedPkgs.size != 1) "s" else ""} selected"
            textSize = 18f
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
        val currentApps = getEditValue("btn_${index}_apps") ?: ""
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
                pendingEdits["btn_${index}_apps"] = selectedPkgs.joinToString("|")
                rebuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildMusicConfig(index: Int): View {
        val currentPkg = getEditValue("btn_${index}_value")

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Player: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        if (installedApps.isEmpty()) {
            row.addView(TextView(context).apply {
                text = "No apps found"
                textSize = 18f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            return row
        }

        val labels = installedApps.map { it.loadLabel(context.packageManager).toString() }
        val selectedIndex = if (currentPkg != null) {
            installedApps.indexOfFirst { it.activityInfo.packageName == currentPkg }
        } else -1

        row.addView(makeChooser("Select player", labels, selectedIndex) { position ->
            pendingEdits["btn_${index}_value"] = installedApps[position].activityInfo.packageName
        })

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
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.resources.dpToPx(12) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(context).apply {
            text = "Icon: "
            textSize = 18f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })

        val options = listOf("none" to "None", "emoji" to "Emoji", "custom" to "Image")
        val labels = options.map { it.second }
        val selectedIdx = when (currentIconType) {
            "emoji"  -> 1
            "custom" -> 2
            else     -> 0
        }

        row.addView(makeChooser("Icon type", labels, selectedIdx) { position ->
            val key = options[position].first
            val currentKey = currentIconType ?: "none"
            if (key != currentKey) {
                applyIconOption(index, key)
                rebuild()
            }
        })

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
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(6)
            }
            setBackgroundColor(Color.TRANSPARENT)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pendingEdits["btn_${index}_icon_data"] = s?.toString() ?: ""
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
                topMargin = context.resources.dpToPx(6)
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
        val oldType = getEditValue("btn_${index}_type")
        if (oldType == "widget") {
            val widgetId = getEditValue("btn_${index}_widget_id")
                ?.toIntOrNull() ?: -1
            if (widgetId != -1) callbacks.onWidgetCleanup(widgetId)
        }

        // Clear all keys in pending
        for (suffix in BUTTON_PREF_SUFFIXES) {
            pendingEdits["btn_$index$suffix"] = null
        }

        if (typeKey != null) {
            pendingEdits["btn_${index}_type"] = typeKey
            when (typeKey) {
                "app" -> {
                    val first = installedApps.firstOrNull()
                    if (first != null) {
                        pendingEdits["btn_${index}_value"] = first.activityInfo.packageName
                        pendingEdits["btn_${index}_label"] =
                            first.loadLabel(context.packageManager).toString()
                    } else {
                        pendingEdits["btn_${index}_label"] = ""
                    }
                }
                "url" -> pendingEdits["btn_${index}_label"] = ""
                "widget" -> pendingEdits["btn_${index}_label"] = ""
                "apps" -> pendingEdits["btn_${index}_label"] = "Apps"
                "music" -> {
                    val first = installedApps.firstOrNull()
                    if (first != null) {
                        pendingEdits["btn_${index}_value"] = first.activityInfo.packageName
                    }
                    pendingEdits["btn_${index}_label"] = "Music"
                }
            }
        }
    }

    private fun applyIconOption(index: Int, option: String) {
        when (option) {
            "none" -> {
                pendingEdits["btn_${index}_icon_type"] = null
                pendingEdits["btn_${index}_icon_data"] = null
            }
            "emoji" -> {
                pendingEdits["btn_${index}_icon_type"] = "emoji"
                pendingEdits["btn_${index}_icon_data"] = ""
            }
            "custom" -> {
                pendingEdits["btn_${index}_icon_type"] = "custom"
                pendingEdits["btn_${index}_icon_data"] = null
                callbacks.onPickImage(index)
            }
        }
    }

    // ── Button remove / reorder ────────────────────────────────────────────

    /** Remove button at [index], shifting subsequent entries down. */
    private fun removeButtonAt(index: Int) {
        val count = prefs.getInt("button_count", 6)
        if (count <= 1) return
        val keys = BUTTON_PREF_SUFFIXES

        // Shift entries after index down by one
        val edit = prefs.edit()
        for (i in index until count - 1) {
            val next = i + 1
            for (k in keys) {
                val v = prefs.getString("btn_$next$k", null)
                if (v != null) edit.putString("btn_$i$k", v) else edit.remove("btn_$i$k")
            }
            edit.putBoolean("btn_${i}_active", prefs.getBoolean("btn_${next}_active", true))
            val src = buttonIconFile(context.filesDir, next)
            val dst = buttonIconFile(context.filesDir, i)
            if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
        }

        // Clear last slot
        for (k in keys) edit.remove("btn_${count - 1}$k")
        edit.remove("btn_${count - 1}_active")
        edit.apply()
        buttonIconFile(context.filesDir, count - 1).delete()

        callbacks.onRemoveButton(index)
        iconCache.clear()
        rebuildButtonList()
    }

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

    private fun makeChooser(
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ): TextView {
        val display = if (selectedIndex in labels.indices) labels[selectedIndex] else "Select\u2026"
        return TextView(context).apply {
            text = "$display \u25BE"
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setSingleChoiceItems(
                        labels.toTypedArray(),
                        selectedIndex.coerceAtLeast(0)
                    ) { dialog, which ->
                        dialog.dismiss()
                        onSelected(which)
                    }
                    .show()
            }
        }
    }

    private fun makeActionButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonStyle
        ).apply {
            text = label
            textSize = 18f
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
