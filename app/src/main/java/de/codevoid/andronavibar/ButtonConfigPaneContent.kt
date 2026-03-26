package de.codevoid.andronavibar

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.service.media.MediaBrowserService
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import android.graphics.drawable.GradientDrawable

/**
 * Config pane for a single configurable button, shown in reservedArea during edit mode.
 *
 * Lifecycle: load() → show() → [user edits] → save() or discard() → unload()
 *
 * Two rebuild paths:
 *   rebuild()       — re-render from current pendingRow (no DB read); used after app/music picks.
 *   rebuildFromDb() — reload pendingRow from DB then rebuild; used after widget bind completes.
 */
class ButtonConfigPaneContent(
    private val activity: Activity,
    private val db: LauncherDatabase,
    val buttonIndex: Int,
    private val onSaved: () -> Unit,
    private val onDiscarded: () -> Unit,
    private val onPickImage: (buttonIndex: Int) -> Unit,
    private val onWidgetBind: (buttonIndex: Int, provider: ComponentName) -> Unit,
    private val onWidgetCleanup: (appWidgetId: Int) -> Unit
) : PaneContent {

    private var container: ViewGroup? = null
    private var pendingRow: ButtonRow = ButtonRow()

    override fun load(onReady: () -> Unit) {
        pendingRow = db.loadButton(buttonIndex) ?: ButtonRow()
        onReady()
    }

    override fun show(container: ViewGroup) {
        this.container = container
        buildForm(container)
    }

    override fun unload() {
        container?.removeAllViews()
        container = null
    }

    /** Re-render from current pendingRow (preserves unsaved edits). */
    fun rebuild() {
        val c = container ?: return
        c.removeAllViews()
        buildForm(c)
    }

    /** Reload pendingRow from DB then rebuild. Call after widget bind completes. */
    fun rebuildFromDb() {
        pendingRow = db.loadButton(buttonIndex) ?: pendingRow
        rebuild()
    }

    // ── Form construction ─────────────────────────────────────────────────────

    private fun buildForm(container: ViewGroup) {
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

        val typeTitle = when (pendingRow.type) {
            "app"       -> activity.getString(R.string.tab_app)
            "url"       -> activity.getString(R.string.tab_url)
            "widget"    -> activity.getString(R.string.widget_tab)
            "music"     -> activity.getString(R.string.tab_music)
            "bookmark"  -> activity.getString(R.string.type_bookmark)
            "navtarget" -> activity.getString(R.string.type_navtarget)
            else        -> activity.getString(R.string.configure)
        }
        form.addView(makeText(typeTitle, 22f, bold = true))
        form.addView(gap(16))

        buildLabelField(form)
        form.addView(gap(16))
        when (pendingRow.type) {
            "app"       -> buildAppFields(form)
            "url"       -> buildUrlFields(form)
            "widget"    -> buildWidgetFields(form)
            "music"     -> buildMusicFields(form)
            "bookmark"  -> buildBookmarkFields(form)
            "navtarget" -> buildNavTargetFields(form)
        }
        if (pendingRow.type != "app") {
            form.addView(gap(16))
            buildIconSection(form)
        }
        form.addView(gap(20))
        buildActionButtons(form)
    }

    private fun buildLabelField(form: LinearLayout) {
        form.addView(makeText(activity.getString(R.string.label_hint), 13f, secondary = true))
        form.addView(gap(4))
        form.addView(makeEdit(pendingRow.label ?: "", activity.getString(R.string.label_hint)) { v ->
            pendingRow = pendingRow.copy(label = v)
        })
    }

    private fun buildAppFields(form: LinearLayout) {
        val pkg = pendingRow.value ?: ""
        form.addView(makeText(activity.getString(R.string.tab_app), 14f, secondary = true))
        form.addView(gap(4))
        form.addView(makeText(
            if (pkg.isEmpty()) activity.getString(R.string.choose_app) else pkg,
            14f
        ))
        form.addView(gap(8))
        form.addView(makeButton(activity.getString(R.string.choose_app) + "…") {
            pickApp { chosen ->
                val label = appLabel(chosen)
                pendingRow = pendingRow.copy(
                    value = chosen,
                    label = if ((pendingRow.label ?: "").isEmpty()) label else pendingRow.label
                )
                rebuild()
            }
        })
    }

    private fun buildUrlFields(form: LinearLayout) {
        form.addView(makeText(activity.getString(R.string.tab_url), 14f, secondary = true))
        form.addView(gap(4))
        form.addView(makeEdit(pendingRow.value ?: "", activity.getString(R.string.url_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
        ) { v -> pendingRow = pendingRow.copy(value = v) })
        form.addView(gap(10))
        form.addView(makeCheck(activity.getString(R.string.open_in_browser), pendingRow.openBrowser) { v ->
            pendingRow = pendingRow.copy(openBrowser = v)
        })
    }

    private fun buildWidgetFields(form: LinearLayout) {
        val provider = pendingRow.value
        val widgetId = pendingRow.widgetId
        form.addView(makeText(activity.getString(R.string.widget_tab), 14f, secondary = true))
        form.addView(gap(4))
        form.addView(makeText(when {
            provider == null           -> activity.getString(R.string.no_widgets)
            widgetId != null && widgetId != -1 -> "Bound: ${ComponentName.unflattenFromString(provider)?.shortClassName ?: provider}"
            else                       -> "Selected (not bound): ${ComponentName.unflattenFromString(provider)?.shortClassName ?: provider}"
        }, 14f))
        form.addView(gap(8))
        form.addView(makeButton("Choose Widget…") {
            pickWidget { chosen ->
                val oldId = pendingRow.widgetId
                if (oldId != null && oldId != -1) onWidgetCleanup(oldId)
                pendingRow = pendingRow.copy(value = chosen.flattenToString(), widgetId = null)
                // Save to DB now so the bind flow picks up current label/icon
                db.saveButton(buttonIndex, pendingRow)
                onWidgetBind(buttonIndex, chosen)
            }
        })
    }

    private fun buildMusicFields(form: LinearLayout) {
        val pkg = pendingRow.value ?: ""
        form.addView(makeText(activity.getString(R.string.tab_music), 14f, secondary = true))
        form.addView(gap(4))
        form.addView(makeText(
            if (pkg.isEmpty()) "No player selected" else pkg, 14f
        ))
        form.addView(gap(8))
        form.addView(makeButton("Choose Player…") {
            pickMusicPlayer { chosen ->
                pendingRow = pendingRow.copy(value = chosen)
                rebuild()
            }
        })
    }

    private fun buildBookmarkFields(form: LinearLayout) {
        val items = db.getCollectionItems(buttonIndex)
        form.addView(makeText(activity.getString(R.string.type_bookmark), 14f, secondary = true))
        form.addView(gap(4))
        items.forEach { item ->
            form.addView(makeCollectionItemRow(
                label      = item.label,
                subtitle   = item.uri,
                onEdit     = { showBookmarkDialog(item) },
                onDelete   = { db.deleteCollectionItem(item.id); rebuild() }
            ))
            form.addView(gap(4))
        }
        form.addView(gap(4))
        form.addView(makeButton(activity.getString(R.string.add_bookmark)) { showBookmarkDialog(null) })
    }

    private fun showBookmarkDialog(edit: CollectionItem?) {
        val p = activity.resources.dpToPx(16)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        val labelEdit = EditText(activity).apply {
            hint = activity.getString(R.string.label_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(edit?.label ?: "")
        }
        val uriEdit = EditText(activity).apply {
            hint = activity.getString(R.string.url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(edit?.uri ?: "")
        }
        val openBrowserCheck = CheckBox(activity).apply {
            text = activity.getString(R.string.open_in_browser)
            isChecked = edit?.openBrowser ?: false
        }
        layout.addView(labelEdit)
        layout.addView(gap(8))
        layout.addView(uriEdit)
        layout.addView(gap(8))
        layout.addView(openBrowserCheck)

        AlertDialog.Builder(activity)
            .setTitle(if (edit == null) activity.getString(R.string.add_bookmark) else activity.getString(R.string.edit_item))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val sortOrder = edit?.sortOrder ?: db.getCollectionItems(buttonIndex).size
                val item = CollectionItem(
                    id             = edit?.id ?: 0,
                    buttonPosition = buttonIndex,
                    sortOrder      = sortOrder,
                    label          = labelEdit.text.toString(),
                    uri            = uriEdit.text.toString(),
                    openBrowser    = openBrowserCheck.isChecked
                )
                if (edit == null) db.addCollectionItem(item) else db.updateCollectionItem(item)
                rebuild()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun buildNavTargetFields(form: LinearLayout) {
        val pkg = pendingRow.value ?: ""
        form.addView(makeText(activity.getString(R.string.tab_app), 14f, secondary = true))
        form.addView(gap(4))
        form.addView(makeText(
            if (pkg.isEmpty()) activity.getString(R.string.choose_app) else pkg, 14f
        ))
        form.addView(gap(8))
        form.addView(makeButton(activity.getString(R.string.choose_app) + "…") {
            pickApp { chosen ->
                pendingRow = pendingRow.copy(value = chosen)
                rebuild()
            }
        })
        form.addView(gap(16))

        val items = db.getCollectionItems(buttonIndex)
        form.addView(makeText(activity.getString(R.string.type_navtarget), 14f, secondary = true))
        form.addView(gap(4))
        items.forEach { item ->
            form.addView(makeCollectionItemRow(
                label    = item.label,
                subtitle = item.uri,
                onEdit   = { showNavTargetDialog(item) },
                onDelete = { db.deleteCollectionItem(item.id); rebuild() }
            ))
            form.addView(gap(4))
        }
        form.addView(gap(4))
        form.addView(makeButton(activity.getString(R.string.add_target)) { showNavTargetDialog(null) })
    }

    private fun showNavTargetDialog(edit: CollectionItem?) {
        val p = activity.resources.dpToPx(16)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        val labelEdit = EditText(activity).apply {
            hint = activity.getString(R.string.label_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(edit?.label ?: "")
        }
        val uriEdit = EditText(activity).apply {
            hint = "geo:0,0?q=..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(edit?.uri ?: "")
        }
        layout.addView(labelEdit)
        layout.addView(gap(8))
        layout.addView(uriEdit)

        AlertDialog.Builder(activity)
            .setTitle(if (edit == null) activity.getString(R.string.add_target) else activity.getString(R.string.edit_item))
            .setView(layout)
            .setPositiveButton(activity.getString(R.string.save)) { _, _ ->
                val sortOrder = edit?.sortOrder ?: db.getCollectionItems(buttonIndex).size
                val item = CollectionItem(
                    id             = edit?.id ?: 0,
                    buttonPosition = buttonIndex,
                    sortOrder      = sortOrder,
                    label          = labelEdit.text.toString(),
                    uri            = uriEdit.text.toString()
                )
                if (edit == null) db.addCollectionItem(item) else db.updateCollectionItem(item)
                rebuild()
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun makeCollectionItemRow(
        label: String, subtitle: String,
        onEdit: () -> Unit, onDelete: () -> Unit
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(activity.getColor(R.color.surface_card))
            cornerRadius = activity.resources.dpToPx(8).toFloat()
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            val p = activity.resources.dpToPx(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            isClickable = true
            isFocusable = true
            setOnClickListener { onEdit() }
        }
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        textCol.addView(makeText(label.ifEmpty { "—" }, 14f))
        textCol.addView(makeText(subtitle, 12f, secondary = true))
        row.addView(textCol)
        row.addView(makeButton("×") { onDelete() }.also { btn ->
            btn.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })
        return row
    }

    private fun buildIconSection(form: LinearLayout) {
        val iconType = pendingRow.iconType
        form.addView(makeText("Icon", 14f, secondary = true))
        form.addView(gap(6))

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val m = activity.resources.dpToPx(3)

        fun iconBtn(label: String, active: Boolean, action: () -> Unit) =
            makeButton(label) { action(); rebuild() }.also { btn ->
                btn.isSelected = active
                (btn.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = 0; it.weight = 1f
                    it.setMargins(0, 0, m, 0)
                }
            }

        row.addView(iconBtn(activity.getString(R.string.icon_none), iconType == null) {
            buttonIconFile(activity.filesDir, buttonIndex).delete()
            pendingRow = pendingRow.copy(iconType = null, iconData = null)
        })
        row.addView(iconBtn(activity.getString(R.string.icon_emoji), iconType == "emoji") {
            pendingRow = pendingRow.copy(iconType = "emoji", iconData = "")
        })
        row.addView(iconBtn(activity.getString(R.string.icon_image), iconType == "custom") {
            pendingRow = pendingRow.copy(iconType = "custom", iconData = null)
            onPickImage(buttonIndex)
        })
        form.addView(row)

        if (iconType == "emoji") {
            form.addView(gap(8))
            form.addView(makeEdit(pendingRow.iconData ?: "", activity.getString(R.string.emoji_hint)) { v ->
                pendingRow = pendingRow.copy(iconData = v)
            })
        }
        if (iconType == "custom") {
            val exists = buttonIconFile(activity.filesDir, buttonIndex).exists()
            form.addView(gap(6))
            form.addView(makeText(
                if (exists) activity.getString(R.string.image_current)
                else activity.getString(R.string.pick_image),
                13f, secondary = true
            ))
            if (exists) form.addView(makeButton(activity.getString(R.string.pick_image)) {
                onPickImage(buttonIndex)
            })
        }
    }

    private fun buildActionButtons(form: LinearLayout) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val m = activity.resources.dpToPx(6)
        row.addView(makeButton(activity.getString(R.string.save)) { save() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, m, 0) }
        })
        row.addView(makeButton(activity.getString(R.string.cancel)) { onDiscarded() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        })
        form.addView(row)
    }

    private fun save() {
        db.saveButton(buttonIndex, pendingRow)
        onSaved()
    }

    // ── Pickers ───────────────────────────────────────────────────────────────

    private fun pickApp(onSelected: (String) -> Unit) {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = activity.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(activity.packageManager).toString() }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.choose_app))
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> onSelected(apps[i].first) }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun pickWidget(onSelected: (ComponentName) -> Unit) {
        val providers = android.appwidget.AppWidgetManager.getInstance(activity).installedProviders
        if (providers.isEmpty()) {
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.no_widgets))
                .setPositiveButton(activity.getString(R.string.ok), null)
                .show()
            return
        }
        val labels = providers.map { p ->
            try {
                val appLabel = activity.packageManager.getApplicationLabel(
                    activity.packageManager.getApplicationInfo(p.provider.packageName, 0)
                ).toString()
                "$appLabel / ${p.loadLabel(activity.packageManager)}"
            } catch (_: Exception) { p.provider.flattenToString() }
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.widget_tab))
            .setItems(labels.toTypedArray()) { _, i -> onSelected(providers[i].provider) }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun pickMusicPlayer(onSelected: (String) -> Unit) {
        val intent = android.content.Intent(MediaBrowserService.SERVICE_INTERFACE)
        @Suppress("DEPRECATION")
        val services = activity.packageManager.queryIntentServices(intent, 0)
        if (services.isEmpty()) {
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.no_music_playing))
                .setPositiveButton(activity.getString(R.string.ok), null)
                .show()
            return
        }
        val labels = services.map { it.loadLabel(activity.packageManager).toString() }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.tab_music))
            .setItems(labels.toTypedArray()) { _, i -> onSelected(services[i].serviceInfo.packageName) }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun makeText(text: String, sizeSp: Float, bold: Boolean = false, secondary: Boolean = false): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(activity.getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
    }

    private fun makeEdit(
        value: String, hint: String,
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
        onChange: (String) -> Unit
    ): EditText {
        return EditText(activity).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            setHintTextColor(activity.getColor(R.color.text_secondary))
            setTextColor(activity.getColor(R.color.text_primary))
            setBackgroundColor(activity.getColor(R.color.surface_card))
            val p = activity.resources.dpToPx(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { onChange(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }

    private fun makeCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        return CheckBox(activity).apply {
            text = label
            isChecked = checked
            setTextColor(activity.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
    }

    private fun makeButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
    }

    private fun gap(dp: Int) = android.view.View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, activity.resources.dpToPx(dp))
    }

    private fun appLabel(pkg: String): String = try {
        activity.packageManager.getApplicationLabel(
            activity.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }
}
