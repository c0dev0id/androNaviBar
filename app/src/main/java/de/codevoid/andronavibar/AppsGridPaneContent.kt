package de.codevoid.andronavibar

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton
import de.codevoid.andronavibar.ui.LauncherButton

class AppsGridPaneContent(
    private val context: Context,
    private val prefs: SharedPreferences,
    private var allApps: List<AppEntry>
) : PaneContent {

    // ── Filter state ─────────────────────────────────────────────────────────

    private var filterOn = prefs.getBoolean(PREF_FILTER, true)
    private val hiddenPkgs: MutableSet<String> =
        prefs.getStringSet(PREF_HIDDEN, emptySet())!!.toMutableSet()

    /** Active letter-range label, e.g. "ABC"; null = show all. */
    private var activeRange: String? = null

    private var displayedApps: List<AppEntry> = emptyList()
    private val iconCache = mutableMapOf<String, android.graphics.drawable.Drawable?>()

    // ── Views ────────────────────────────────────────────────────────────────

    private var outerView: LinearLayout? = null
    private var gridContainer: FrameLayout? = null
    private var scrollView: ScrollView? = null
    private var cells: List<LauncherButton> = emptyList()
    private var rangeBtns: List<FocusableButton> = emptyList()
    private var filterBtn: FocusableButton? = null
    private var focusIndex = 0

    // Tile dimensions measured on first layout, reused on rebuilds.
    private var tileW = 0
    private var tileH = 0
    private var marginPx = 0

    private var activeDialog: AppContextDialog? = null
    private var pendingScrollY: Int? = null

    var onContentReady: (() -> Unit)? = null

    // ── PaneContent ──────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        outerView = outer
        container.addView(outer)

        outer.addView(buildFilterBar())

        val gc = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        gridContainer = gc
        outer.addView(gc)

        gc.post {
            val barH = context.resources.dpToPx(BAR_HEIGHT_DP)
            marginPx = context.resources.dpToPx(MARGIN_DP)
            tileW    = gc.width / COLUMNS - marginPx * 2
            tileH    = (outer.height - barH) / VISIBLE_ROWS - marginPx * 2
            buildDisplayList()
            buildGrid()
            onContentReady?.invoke()
            onContentReady = null
        }
    }

    override fun unload() {
        activeDialog?.dismissQuietly()
        activeDialog = null
        (outerView?.parent as? ViewGroup)?.removeView(outerView)
        outerView      = null
        gridContainer  = null
        scrollView     = null
        cells          = emptyList()
        rangeBtns      = emptyList()
        filterBtn      = null
    }

    // ── Key handling ─────────────────────────────────────────────────────────

    fun handleKey(keyCode: Int): Boolean {
        activeDialog?.let { return it.handleKey(keyCode) }
        if (displayedApps.isEmpty()) return false
        return when (keyCode) {
            19 -> { moveFocus(focusIndex - COLUMNS); true }
            20 -> { moveFocus(focusIndex + COLUMNS); true }
            21 -> { if (focusIndex % COLUMNS > 0) moveFocus(focusIndex - 1); true }
            22 -> {
                if (focusIndex % COLUMNS < COLUMNS - 1 && focusIndex < displayedApps.lastIndex) {
                    moveFocus(focusIndex + 1); true
                } else false
            }
            66 -> { launchFocused(); true }
            else -> false
        }
    }

    fun handleLongPress() {
        if (activeDialog != null) return
        val app = displayedApps.getOrNull(focusIndex) ?: return
        activeDialog = AppContextDialog(app, isHidden = app.packageName in hiddenPkgs)
    }

    /** Replace the app list and rebuild the grid if currently visible. */
    fun updateAppList(newApps: List<AppEntry>) {
        allApps = newApps
        iconCache.clear()
        if (gridContainer != null) rebuildGrid()
    }

    fun setInitialFocus() {
        val row = focusIndex / COLUMNS
        focusIndex = minOf(row * COLUMNS + COLUMNS - 1, displayedApps.lastIndex.coerceAtLeast(0))
        cells.getOrNull(focusIndex)?.isFocusedButton = true
        scrollToFocused()
    }

    fun clearFocus() {
        cells.getOrNull(focusIndex)?.isFocusedButton = false
    }

    // ── Filter bar ───────────────────────────────────────────────────────────

    private fun buildFilterBar(): LinearLayout {
        val res  = context.resources
        val barH = res.dpToPx(BAR_HEIGHT_DP)
        val vPad = res.dpToPx(8)
        val hPad = res.dpToPx(6)
        val btnH = barH - vPad * 2

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, barH)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            setBackgroundColor(context.getColor(R.color.surface_dark))
        }

        // Letter-range buttons — equal weight, tap once to activate, tap again to clear
        val built = mutableListOf<FocusableButton>()
        for (range in RANGES) {
            val btn = FocusableButton(context).apply {
                text = range
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, btnH, 1f)
                backgroundTintList = rangeTint(range)
                setOnClickListener { selectRange(if (activeRange == range) null else range) }
            }
            bar.addView(btn)
            built.add(btn)
        }
        rangeBtns = built

        // Divider
        bar.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(1), MATCH).apply {
                setMargins(res.dpToPx(6), 0, res.dpToPx(6), 0)
            }
            setBackgroundColor(context.getColor(R.color.text_secondary))
            alpha = 0.2f
        })

        // Hide/show filter toggle
        val filter = FocusableButton(context).apply {
            text = filterLabel()
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(110), btnH)
            backgroundTintList = filterTint()
            setOnClickListener { toggleFilter() }
        }
        filterBtn = filter
        bar.addView(filter)

        return bar
    }

    private fun filterLabel() = if (filterOn) "Filter: On" else "Filter: Off"
    private fun filterTint() = ColorStateList.valueOf(
        context.getColor(if (filterOn) R.color.button_active else R.color.button_inactive)
    )
    private fun rangeTint(range: String) = ColorStateList.valueOf(
        context.getColor(if (activeRange == range) R.color.button_active else R.color.button_inactive)
    )

    private fun selectRange(range: String?) {
        activeRange = range
        focusIndex = 0
        rangeBtns.forEachIndexed { i, btn -> btn.backgroundTintList = rangeTint(RANGES[i]) }
        rebuildGrid()
    }

    private fun toggleFilter() {
        filterOn = !filterOn
        prefs.edit().putBoolean(PREF_FILTER, filterOn).apply()
        filterBtn?.text = filterLabel()
        filterBtn?.backgroundTintList = filterTint()
        rebuildGrid()
    }

    // ── Grid ─────────────────────────────────────────────────────────────────

    private fun buildDisplayList() {
        val base   = if (filterOn) allApps.filter { it.packageName !in hiddenPkgs } else allApps
        val ranged = activeRange?.let { r -> base.filter { matchesRange(it.label, r) } } ?: base
        displayedApps = ranged.sortedBy { it.label.lowercase() }
        focusIndex = focusIndex.coerceIn(0, (displayedApps.size - 1).coerceAtLeast(0))
    }

    /**
     * Returns true if [label]'s first character falls within [range].
     * "YZ*" also matches digits, symbols, and any non-A-to-X character.
     */
    private fun matchesRange(label: String, range: String): Boolean {
        val first = label.firstOrNull()?.uppercaseChar() ?: return range == "YZ*"
        return if (range == "YZ*") first !in 'A'..'X' else range.indexOf(first) >= 0
    }

    private fun rebuildGrid(preserveScroll: Boolean = false) {
        val gc = gridContainer ?: return
        if (preserveScroll) pendingScrollY = scrollView?.scrollY
        gc.removeAllViews()
        scrollView = null
        cells = emptyList()
        buildDisplayList()
        buildGrid()
    }

    private fun buildGrid() {
        val gc = gridContainer ?: return

        val scroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        scrollView = scroll

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        }

        val built = mutableListOf<LauncherButton>()
        var row: LinearLayout? = null

        for (i in displayedApps.indices) {
            if (i % COLUMNS == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                }
                col.addView(row)
            }
            built.add(buildCell(displayedApps[i], i, row!!))
        }

        val rem = displayedApps.size % COLUMNS
        if (rem != 0) repeat(COLUMNS - rem) {
            row?.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
            })
        }

        cells = built
        scroll.addView(col)
        gc.addView(scroll)
        val targetY = pendingScrollY
        pendingScrollY = null
        if (targetY != null) scroll.post { scroll.scrollTo(0, targetY) }
        else scrollToFocused()
    }

    private fun buildCell(app: AppEntry, index: Int, parent: ViewGroup): LauncherButton {
        val cell = LayoutInflater.from(context)
            .inflate(R.layout.launcher_button_item, parent, false) as LauncherButton
        cell.layoutParams = LinearLayout.LayoutParams(tileW, tileH).apply {
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        cell.buttonIcon = iconCache.getOrPut(app.packageName) {
            try { context.packageManager.getApplicationIcon(app.packageName) }
            catch (_: Exception) { null }
        }
        cell.text = app.label
        cell.onFocusRequested = { moveFocus(index) }
        cell.setOnClickListener { launchApp(app) }
        cell.setOnLongClickListener {
            if (activeDialog == null) {
                activeDialog = AppContextDialog(app, isHidden = app.packageName in hiddenPkgs)
            }
            true
        }
        if (!filterOn && app.packageName in hiddenPkgs) cell.alpha = 0.3f
        parent.addView(cell)
        return cell
    }

    // ── Focus ─────────────────────────────────────────────────────────────────

    private fun moveFocus(newIndex: Int) {
        val clamped = newIndex.coerceIn(0, displayedApps.lastIndex)
        if (clamped == focusIndex) return
        cells.getOrNull(focusIndex)?.isFocusedButton = false
        focusIndex = clamped
        cells.getOrNull(focusIndex)?.isFocusedButton = true
        scrollToFocused()
    }

    private fun scrollToFocused() {
        val sv   = scrollView ?: return
        val cell = cells.getOrNull(focusIndex) ?: return
        sv.post { (cell.parent as? View)?.let { sv.smoothScrollTo(0, it.top) } }
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private fun launchFocused() { launchApp(displayedApps.getOrNull(focusIndex) ?: return) }

    private fun launchApp(app: AppEntry) {
        context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.let { context.startActivity(it) }
    }

    // ── Hide / show ───────────────────────────────────────────────────────────

    private fun hideApp(pkg: String) {
        hiddenPkgs.add(pkg)
        prefs.edit().putStringSet(PREF_HIDDEN, hiddenPkgs.toSet()).apply()
        rebuildGrid(preserveScroll = true)
    }

    private fun showApp(pkg: String) {
        hiddenPkgs.remove(pkg)
        prefs.edit().putStringSet(PREF_HIDDEN, hiddenPkgs.toSet()).apply()
        rebuildGrid(preserveScroll = true)
    }

    // ── Context dialog ────────────────────────────────────────────────────────

    private inner class AppContextDialog(
        private val app: AppEntry,
        private val isHidden: Boolean
    ) {
        private val dialog = Dialog(context)
        private var focusOnCancel = true
        private lateinit var actionBtn: FocusableButton
        private lateinit var cancelBtn: FocusableButton
        private var dismissed = false

        init { build() }

        private fun build() {
            val res  = context.resources
            val p    = res.dpToPx(32)
            val btnW = res.dpToPx(240)
            val btnH = res.dpToPx(80)
            val gap  = res.dpToPx(16)

            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val content = LinearLayout(context).apply {
                orientation  = LinearLayout.VERTICAL
                minimumWidth = res.dpToPx(540)
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.surface_card))
                    cornerRadius = res.dpToPx(16).toFloat()
                }
            }

            content.addView(TextView(context).apply {
                text = app.label
                textSize = 36f
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = res.dpToPx(24)
                }
            })

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }

            actionBtn = FocusableButton(context).apply {
                text = context.getString(if (isHidden) R.string.show_app else R.string.hide_app)
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply { marginEnd = gap }
                setOnClickListener { performAndDismiss() }
            }
            cancelBtn = FocusableButton(context).apply {
                text = context.getString(R.string.cancel)
                textSize = 24f
                isFocusedButton = true
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply { marginStart = gap }
                setOnClickListener { dismissQuietly() }
            }

            btnRow.addView(actionBtn)
            btnRow.addView(cancelBtn)
            content.addView(btnRow)

            dialog.setContentView(content)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.6f)
            }
            dialog.setOnCancelListener { dismissed = true; activeDialog = null }
            dialog.show()
        }

        fun handleKey(keyCode: Int): Boolean {
            when (keyCode) {
                21 -> setFocus(onCancel = false)   // LEFT → action
                22 -> setFocus(onCancel = true)    // RIGHT → cancel
                66 -> if (focusOnCancel) dismissQuietly() else performAndDismiss()
            }
            return true  // consume all keys while dialog is open
        }

        private fun setFocus(onCancel: Boolean) {
            focusOnCancel = onCancel
            cancelBtn.isFocusedButton = onCancel
            actionBtn.isFocusedButton = !onCancel
        }

        private fun performAndDismiss() {
            if (isHidden) showApp(app.packageName) else hideApp(app.packageName)
            dismissQuietly()
        }

        fun dismissQuietly() {
            if (!dismissed) { dismissed = true; activeDialog = null; dialog.dismiss() }
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val PREF_FILTER = "apps_filter_on"
        private const val PREF_HIDDEN = "apps_hidden_pkgs"
        private const val VISIBLE_ROWS  = 6
        private const val MARGIN_DP     = 4
        private const val COLUMNS       = 2
        private const val BAR_HEIGHT_DP = 60

        val RANGES = listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQR", "STU", "VWX", "YZ*")
    }
}
