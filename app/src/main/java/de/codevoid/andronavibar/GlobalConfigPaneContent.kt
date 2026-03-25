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
import de.codevoid.andronavibar.ui.FocusableButton
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

    // ── Remote navigation ──────────────────────────────────────────────────

    /** -1 = none; 0..count-1 = button entry; count = Add Button; count+1 = Check for Update. */
    private var focusRow: Int = -1
    /** 0=checkbox, 1=label+icon, 2=drag, 3=X; always 0 for footer rows. */
    private var focusCol: Int = 0

    private data class RowFocusTargets(
        val checkbox: CheckBox,
        val labelArea: View,
        val dragHandle: View,
        val deleteBtn: View
    )
    private val rowFocusTargets = mutableListOf<RowFocusTargets>()
    private var addButtonFocusView: View? = null
    private var updateButton: FocusableButton? = null
    private var configScrollView: ScrollView? = null

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
        focusRow = -1
        rowFocusTargets.clear()
        addButtonFocusView = null
        updateButton = null
        configScrollView = null
    }

    private fun triggerUpdateCheck() {
        val btn = updateButton ?: return
        val activity = context as? android.app.Activity ?: return
        UpdateChecker.check(
            activity,
            onStatus   = { text     -> btn.text             = text },
            onProgress = { progress -> btn.downloadProgress = progress }
        )
    }

    /** Refresh the detail editor (e.g. after type change or image pick). */
    fun rebuild() {
        if (selectedButtonIndex >= 0) refreshDetailEditor()
    }

    private fun rebuildButtonList() {
        val container = buttonListContainer ?: return
        container.removeAllViews()
        iconCache.clear()
        rowFocusTargets.clear()
        val count = prefs.getInt("button_count", 6)
        for (i in 0 until count) container.addView(buildButtonListEntry(i))
        container.addView(buildFooter())
        updateFocusRings()
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
        updateFocusRings()
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
        updateFocusRings()
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
        configScrollView = scroll

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

        // Icon + Label — single focus target for remote navigation
        val handleSize = context.resources.dpToPx(40)
        val labelArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = context.resources.dpToPx(12)
            }
            setOnClickListener { showDetailEditor(index) }
        }
        buildIconPreview(index)?.let { labelArea.addView(it) }
        labelArea.addView(TextView(context).apply {
            text = displayName
            textSize = 18f
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = context.resources.dpToPx(8)
            }
            isSingleLine = true
        })
        row.addView(labelArea)

        // Drag handle — long press starts drag (touch); UP/DOWN while focused (remote)
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
        val deleteBtn = TextView(context).apply {
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
        }
        row.addView(deleteBtn)

        // Touch fallback: tap anywhere on the row opens detail editor
        row.setOnClickListener { showDetailEditor(index) }

        // Register focus targets (creates or replaces the slot for this index)
        val targets = RowFocusTargets(checkbox, labelArea, handle, deleteBtn)
        if (index < rowFocusTargets.size) rowFocusTargets[index] = targets
        else rowFocusTargets.add(targets)

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

    private fun doAddButton() {
        if (selectedButtonIndex >= 0) clearDetailEditor()
        callbacks.onAddButton()
        val newIndex = prefs.getInt("button_count", 6) - 1
        val container = buttonListContainer ?: return
        // Insert before the footer
        container.addView(buildButtonListEntry(newIndex), container.childCount - 1)
        updateFocusRings()
    }

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

        val addBtn = makeActionButton("+ Add Button") { doAddButton() }
        addButtonFocusView = addBtn
        row.addView(addBtn)
        wrapper.addView(row)

        val updateBtn = FocusableButton(context).apply {
            text = "Check for Update"
            textSize = 18f
            cornerRadius = context.resources.dpToPx(12)
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
            setTextColor(context.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(16)
            }
            setOnClickListener { triggerUpdateCheck() }
        }
        updateButton = updateBtn
        wrapper.addView(updateBtn)

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

    // ── Remote navigation public API ─────────────────────────────────────────

    /** Called by MainActivity when focus enters this pane via LEFT key. */
    fun setInitialFocus() {
        focusRow = 0
        focusCol = 1   // start on label+icon column
        updateFocusRings()
        scrollToFocused()
    }

    /** Called by MainActivity when focus leaves this pane. */
    fun clearFocus() {
        focusRow = -1
        updateFocusRings()
    }

    /**
     * Handle a remote d-pad key. Returns true if consumed.
     * Returns false for RIGHT at the rightmost column so MainActivity exits the pane.
     */
    override fun handleKey(keyCode: Int): Boolean {
        val count = prefs.getInt("button_count", 6)
        return when (keyCode) {
            19 -> {  // UP
                if (focusRow in 0 until count && focusCol == 2) moveEntryUp(focusRow, count)
                else moveFocusRow(-1, count)
                true
            }
            20 -> {  // DOWN
                if (focusRow in 0 until count && focusCol == 2) moveEntryDown(focusRow, count)
                else moveFocusRow(+1, count)
                true
            }
            21 -> {  // LEFT — navigate columns; stop at col 0
                if (focusCol > 0) { focusCol--; updateFocusRings() }
                true
            }
            22 -> {  // RIGHT — navigate columns; exit pane at rightmost
                val maxCol = if (focusRow in 0 until count) 3 else 0
                if (focusCol < maxCol) { focusCol++; updateFocusRings(); true }
                else false   // let MainActivity route this as pane exit
            }
            66 -> { activateFocused(count); true }
            else -> false
        }
    }

    // ── Remote navigation internals ──────────────────────────────────────────

    private fun moveFocusRow(delta: Int, count: Int) {
        val totalRows = count + 2  // entries + Add Button + Check for Update
        val newRow = (focusRow + delta).coerceIn(0, totalRows - 1)
        if (newRow == focusRow) return
        focusRow = newRow
        if (focusRow >= count) focusCol = 0  // footer rows have only col 0
        updateFocusRings()
        scrollToFocused()
    }

    private fun activateFocused(count: Int) {
        when {
            focusRow in 0 until count -> when (focusCol) {
                0 -> rowFocusTargets.getOrNull(focusRow)?.checkbox?.toggle()
                1 -> showDetailEditor(focusRow)
                2 -> { focusCol = 1; updateFocusRings() }  // drop: move focus to label col
                3 -> {
                    if (count <= 1) return
                    if (selectedButtonIndex >= 0) clearDetailEditor()
                    val row = focusRow
                    focusRow = row.coerceAtMost(count - 2).coerceAtLeast(0)
                    focusCol = 1
                    removeButtonAt(row)   // calls rebuildButtonList() → updateFocusRings()
                }
            }
            focusRow == count -> {   // + Add Button
                doAddButton()
                val newCount = prefs.getInt("button_count", 6)
                focusRow = newCount - 1
                focusCol = 1
                scrollToFocused()
            }
            focusRow == count + 1 -> triggerUpdateCheck()  // Check for Update
        }
    }

    private fun moveEntryUp(row: Int, count: Int) {
        if (row <= 0) return
        if (selectedButtonIndex >= 0) clearDetailEditor()
        moveButtonPrefs(row, row - 1)
        callbacks.onReloadAll()
        focusRow = row - 1
        rebuildButtonList()
        scrollToFocused()
    }

    private fun moveEntryDown(row: Int, count: Int) {
        if (row >= count - 1) return
        if (selectedButtonIndex >= 0) clearDetailEditor()
        moveButtonPrefs(row, row + 1)
        callbacks.onReloadAll()
        focusRow = row + 1
        rebuildButtonList()
        scrollToFocused()
    }

    private fun scrollToFocused() {
        val sv = configScrollView ?: return
        val container = buttonListContainer ?: return
        val child = container.getChildAt(focusRow) ?: return
        sv.post { sv.smoothScrollTo(0, (child.top - sv.height / 3).coerceAtLeast(0)) }
    }

    private fun updateFocusRings() {
        val count = prefs.getInt("button_count", 6)
        for (row in rowFocusTargets.indices) {
            val t = rowFocusTargets[row]
            applyFocusRing(t.checkbox,  focusRow == row && focusCol == 0)
            applyFocusRing(t.labelArea, focusRow == row && focusCol == 1)
            applyFocusRing(t.dragHandle,focusRow == row && focusCol == 2)
            applyFocusRing(t.deleteBtn, focusRow == row && focusCol == 3)
        }
        addButtonFocusView?.let { applyFocusRing(it, focusRow == count) }
        updateButton?.let       { applyFocusRing(it, focusRow == count + 1) }
    }

    private fun applyFocusRing(view: View, focused: Boolean) {
        view.foreground = if (focused) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.resources.dpToPx(12).toFloat()
                setStroke(context.resources.dpToPx(6), context.getColor(R.color.focus_ring))
                setColor(Color.TRANSPARENT)
            }
        } else null
    }

    companion object {
        private val BUTTON_PREF_SUFFIXES = listOf(
            "_type", "_value", "_label", "_icon_type", "_icon_data",
            "_widget_id", "_open_browser"
        )
    }
}
