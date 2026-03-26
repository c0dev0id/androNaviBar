package de.codevoid.andronavibar

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.codevoid.andronavibar.LauncherDatabase
import android.content.ClipData
import android.content.ClipDescription
import android.content.res.ColorStateList
import android.view.DragEvent
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.dynamicanimation.animation.FloatValueHolder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.util.Log
import de.codevoid.andronavibar.ui.FocusableButton
import de.codevoid.andronavibar.ui.LauncherButton
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var db:              LauncherDatabase
    private val buttons = mutableListOf<LauncherButton>()
    private lateinit var buttonScroll:    ScrollView
    private lateinit var buttonPanel:     LinearLayout
    private lateinit var dashboardButton: FocusableButton
    private lateinit var appsButton:      FocusableButton
    private lateinit var reservedArea:         FrameLayout
    private lateinit var editChromeContainer: LinearLayout
    private lateinit var appWidgetHost:        SafeAppWidgetHost
    private val widgetViews = mutableMapOf<Int, AppWidgetHostView>()

    private var focusedIndex = 0
    private var scrollSpring: SpringAnimation? = null

    // ── Window focus ──────────────────────────────────────────────────────────

    /**
     * True only when this window actually has input focus. False in split-screen
     * when another window is active, or when fully backgrounded.
     *
     * Remote navigation (up/down/activate, config pane keys) is gated on this flag.
     * Key 111 (Round Button 2) is exempt: short press always dismisses the config
     * pane; long press is handled globally by LauncherApplication.
     */
    private var isWindowFocused = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        isWindowFocused = hasFocus
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── Pane coordination ─────────────────────────────────────────────────────

    /** Non-null while the dashboard pane is displayed in reservedArea. */
    private var activeDashboardPane: DashboardPaneContent? = null

    /** Non-null while a web pane is displayed in reservedArea. */
    private var activeWebPane: WebPaneContent? = null

    /** Non-null while an app launcher pane is displayed in reservedArea. */
    private var activeAppLauncherPane: AppLauncherPaneContent? = null

    /** Non-null while a URL launcher pane (browser-mode) is displayed in reservedArea. */
    private var activeUrlLauncherPane: UrlLauncherPaneContent? = null

    /** One persistent pane per widget ID — created on first show, kept in reservedArea (GONE). */
    private val widgetPanes = mutableMapOf<Int, WidgetPaneContent>()
    private var visibleWidgetId: Int = -1

    /** Non-null while an apps grid pane is displayed in reservedArea. */
    private var activeAppsGridPane: AppsGridPaneContent? = null

    /** Non-null while a music player pane is displayed in reservedArea. */
    private var activeMusicPlayerPane: MusicPlayerPaneContent? = null

    /** Non-null while a bookmarks pane is displayed in reservedArea. */
    private var activeBookmarksPane: BookmarksPaneContent? = null

    /** Non-null while a nav targets pane is displayed in reservedArea. */
    private var activeNavTargetsPane: NavTargetsPaneContent? = null

    /** Non-null while the global settings pane is displayed in reservedArea. */
    private var activeGlobalSettingsPane: GlobalSettingsPaneContent? = null

    /** Non-null while a per-button config pane is displayed in reservedArea during edit mode. */
    private var activeButtonConfigPane: ButtonConfigPaneContent? = null

    /** Non-null while the type picker pane is displayed in reservedArea during edit mode. */
    private var activeTypePickerPane: TypePickerPaneContent? = null

    /** The one pane currently visible and receiving remote key events. */
    private var keyPane: PaneContent? = null

    // ── Pane cache keys ───────────────────────────────────────────────────────
    // Each tracks the identity of the currently-cached pane so we can detect
    // when a button's target has changed and the old pane must be replaced.

    private var cachedWebUrl:         String? = null
    private var cachedAppLauncherPkg: String? = null
    private var cachedUrlLauncherUrl: String? = null
    private var cachedMusicPkg:       String? = null

    /** Holds a partially-bound widget config while waiting for the system bind dialog result. */
    private var pendingWidgetConfig: ButtonConfig.WidgetLauncher? = null
    private var pendingWidgetButtonIndex: Int = 0
    private var pendingWidgetFromButtonConfig = false

    /** Loading spinner overlay, shown while a pane's content is being prepared. */
    private var loadingSpinner: ProgressBar? = null

    private enum class FocusOwner { BUTTONS, PANE }

    /** Which region currently owns D-pad input. */
    private var focusOwner = FocusOwner.BUTTONS

    /** Index of the button whose content/config pane is displayed (-1 = none). */
    private var activeButtonIndex = -1

    /** True when the launcher is in edit mode (touch-only; remote is suppressed). */
    private var editMode = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemBars()
        db                  = LauncherDatabase.getInstance(this)
        reservedArea        = findViewById(R.id.reservedArea)
        buttonScroll        = findViewById(R.id.buttonScroll)
        buttonPanel         = findViewById(R.id.buttonPanel)
        editChromeContainer = findViewById(R.id.editChromeContainer)
        appWidgetHost = SafeAppWidgetHost(this, APP_WIDGET_HOST_ID)
        cleanupOrphanedWidgetId()

        // Dashboard fixed at top of column (panel position 0).
        dashboardButton = createFixedButton(getString(R.string.dashboard), R.drawable.ic_dashboard) { activateDashboardButton() }
        dashboardButton.setOnLongClickListener { enterEditMode(); true }
        buttonPanel.addView(dashboardButton)

        var count = db.getButtonCount()
        if (count == 0) {
            // Fresh install: seed default empty button rows
            repeat(DEFAULT_BUTTON_COUNT) { db.addButton() }
            count = DEFAULT_BUTTON_COUNT
        }
        for (i in 0 until count) {
            val btn = layoutInflater.inflate(
                R.layout.launcher_button_item, buttonPanel, false
            ) as LauncherButton
            btn.index = i
            wireButton(btn, i)
            btn.loadConfig(db)
            buttonPanel.addView(btn)
            buttons.add(btn)
        }

        // Apps fixed at bottom of column (panel position buttons.size + 1).
        appsButton = createFixedButton(getString(R.string.tab_apps), R.drawable.ic_apps) { activateAppsButton() }
        appsButton.setOnLongClickListener { enterEditMode(); true }
        buttonPanel.addView(appsButton)

        preCreateWidgetViews()

        // focusedIndex is a panel position: 0=Dashboard, 1..N=configurable, N+1=Apps.
        // Default to 0 (Dashboard). Clamp saved values to valid range.
        focusedIndex = db.getFocusedIndex().coerceIn(0, buttons.size + 1)

        buttonPanel.post {
            adjustButtonHeights()
            scrollToFocused(animate = false)
        }

        updateFocus()
        activateDashboardButton()

        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Pass through when a text field owns Android focus (cursor navigation, etc.)
        if (currentFocus is android.widget.EditText) return super.dispatchKeyEvent(event)
        // Only handle keyboard-sourced events here. Physical remote buttons arrive via
        // the broadcast receiver; intercepting their injected KeyEvents too would double-fire.
        if (event.source and android.view.InputDevice.SOURCE_KEYBOARD == 0)
            return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event.keyCode)
                return true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (focusOwner == FocusOwner.PANE && activeAppsGridPane != null) {
                        armKey66LongPress()
                    } else {
                        handleKey(66)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    if (cancelKey66LongPress()) handleKey(66)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        widgetRebindAttempted = false
        appWidgetHost.startListening()
        LauncherApplication.mainActivity = WeakReference(this)
        registerReceiver(
            remoteListener,
            IntentFilter(LauncherApplication.REMOTE_ACTION),
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        LauncherApplication.mainActivity = null
        isWindowFocused = false
        try { unregisterReceiver(remoteListener) } catch (_: Exception) {}
        pressedKeys.clear()
        key111PressedAt = 0L
        cancelKey66LongPress()
        scrollSpring?.cancel()
        scrollSpring = null
    }

    // ── Remote input ──────────────────────────────────────────────────────────

    private val pressedKeys = mutableSetOf<Int>()

    /**
     * Monotonic timestamp of the most recent key-111 press. Used to distinguish
     * a short press (< TOGGLE_HOLD_MS → cancel) from a long press (≥ TOGGLE_HOLD_MS
     * → handled by LauncherApplication; no additional action here).
     */
    private var key111PressedAt = 0L

    /**
     * Pending long-press runnable for key-66 when the apps grid has focus.
     * Posted on press; fires after LONG_PRESS_MS. Cancelled on release if not
     * yet fired (= short press → normal activate). Null when not pending.
     */
    private var key66LongPressRunnable: Runnable? = null
    private val longPressHandler = Handler(Looper.getMainLooper())

    private fun armKey66LongPress() {
        cancelKey66LongPress()
        val r = Runnable {
            key66LongPressRunnable = null
            activeAppsGridPane?.handleLongPress()
        }
        key66LongPressRunnable = r
        longPressHandler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelKey66LongPress(): Boolean {
        val r = key66LongPressRunnable ?: return false
        longPressHandler.removeCallbacks(r)
        key66LongPressRunnable = null
        return true
    }

    private val remoteListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LauncherApplication.REMOTE_ACTION) return

            if (intent.hasExtra("key_press")) {
                val keyCode = intent.getIntExtra("key_press", 0)
                if (editMode) return
                if (!pressedKeys.add(keyCode)) return  // auto-repeat, ignore

                if (keyCode == LauncherApplication.TOGGLE_KEY) {
                    // Record press time; act on release to distinguish short vs long.
                    key111PressedAt = SystemClock.elapsedRealtime()
                    return
                }

                // Long-press confirm in the apps grid: arm a 500ms timer that fires
                // immediately on hold, without waiting for key release.
                if (keyCode == 66 && focusOwner == FocusOwner.PANE && activeAppsGridPane != null) {
                    armKey66LongPress()
                    return
                }

                handleKey(keyCode)

            } else if (intent.hasExtra("key_release")) {
                val keyCode = intent.getIntExtra("key_release", 0)
                if (editMode) return
                pressedKeys.remove(keyCode)

                if (keyCode == 66) {
                    // If the runnable hadn't fired yet (released before 500ms) →
                    // treat as short press. If it already fired, cancelKey66LongPress
                    // returns false and we do nothing.
                    if (cancelKey66LongPress()) handleKey(66)
                    return
                }

                if (keyCode == LauncherApplication.TOGGLE_KEY && key111PressedAt > 0L) {
                    val held = SystemClock.elapsedRealtime() - key111PressedAt
                    key111PressedAt = 0L
                    if (held < LauncherApplication.TOGGLE_HOLD_MS) {
                        if (focusOwner == FocusOwner.PANE) {
                            setFocusOwner(FocusOwner.BUTTONS)
                        }
                    }
                    // Long press already handled by LauncherApplication.
                }
            }
        }
    }

    // ── Input routing ─────────────────────────────────────────────────────────

    /**
     * Central key handler called by both the hardware remote receiver and the
     * on-screen swipe gesture detector. Key codes match Android DPAD constants:
     * 19=UP, 20=DOWN, 21=LEFT, 22=RIGHT, 66=CONFIRM.
     */
    private fun handleKey(keyCode: Int) {
        if (editMode) return
        // When a pane shows a modal Dialog the Activity loses window focus, but remote
        // keys must still reach the pane so the user can dismiss the popup.
        if (!isWindowFocused) {
            if (activeDashboardPane?.hasModalDialog == true) {
                activeDashboardPane?.handleKey(keyCode)
            } else if (activeAppsGridPane?.hasModalDialog == true) {
                activeAppsGridPane?.handleKey(keyCode)
            }
            return
        }
        when (focusOwner) {
            FocusOwner.PANE -> {
                val handled = keyPane?.handleKey(keyCode) ?: false
                if (!handled && keyCode == 22) setFocusOwner(FocusOwner.BUTTONS)
            }
            FocusOwner.BUTTONS -> when (keyCode) {
                19 -> moveFocus(-1)
                20 -> moveFocus(+1)
                21 -> {
                    if (focusedIndex == activeButtonIndex) refreshCurrentPane()
                    enterPane()
                }
                66 -> when {
                    focusedIndex == 0              -> activateDashboardButton()
                    focusedIndex in 1..buttons.size -> buttons[focusedIndex - 1].activate()
                    focusedIndex == buttons.size + 1 -> activateAppsButton()
                }
            }
        }
    }

    private fun activateDashboardButton() {
        if (editMode) return
        if (activeButtonIndex == 0) return
        hideCurrentPane()
        deactivateActiveButton()
        activeButtonIndex = 0
        dashboardButton.isActiveButton = true
        showDashboardPane()
    }

    private fun activateAppsButton() {
        if (editMode) return
        if (activeButtonIndex == buttons.size + 1) return
        hideCurrentPane()
        deactivateActiveButton()
        activeButtonIndex = buttons.size + 1
        appsButton.isActiveButton = true
        showAllAppsPane()
    }

    // ── Focus management ──────────────────────────────────────────────────────
    //
    // focusedIndex is a panel position:
    //   0          → dashboardButton
    //   1..N       → buttons[focusedIndex - 1]
    //   N+1        → appsButton   (N = buttons.size)

    private fun setFocus(index: Int) {
        val maxPos = buttons.size + 1
        focusedIndex = when {
            index <= 0      -> 0
            index >= maxPos -> maxPos
            else -> {
                val btnIdx = (index - 1).coerceIn(0, buttons.lastIndex)
                nearestVisibleButton(btnIdx) + 1
            }
        }
        db.setFocusedIndex(focusedIndex)
        updateFocus()
        scrollToFocused()
    }

    private fun moveFocus(delta: Int) {
        val next = focusedIndex + (if (delta > 0) 1 else -1)
        if (next in 0..(buttons.size + 1)) setFocus(next)
    }

    private fun nearestVisibleButton(index: Int): Int =
        index.coerceIn(0, buttons.lastIndex.coerceAtLeast(0))

    private fun updateFocus() {
        val inButtons = focusOwner == FocusOwner.BUTTONS
        dashboardButton.isFocusedButton = inButtons && focusedIndex == 0
        for (i in buttons.indices) {
            buttons[i].isFocusedButton = inButtons && focusedIndex == i + 1
        }
        appsButton.isFocusedButton = inButtons && focusedIndex == buttons.size + 1
    }

    private fun setFocusOwner(owner: FocusOwner) {
        focusOwner = owner
        if (owner == FocusOwner.PANE) {
            updateFocus()
            activeDashboardPane?.setInitialFocus()
            activeMusicPlayerPane?.setInitialFocus()
            activeAppsGridPane?.setInitialFocus()
            activeAppLauncherPane?.setInitialFocus()
            activeUrlLauncherPane?.setInitialFocus()
        } else {
            // Snap cursor to the active button so it's immediately highlighted on return.
            if (activeButtonIndex >= 0) {
                focusedIndex = activeButtonIndex
                db.setFocusedIndex(focusedIndex)
            }
            updateFocus()
            scrollToFocused()
            activeDashboardPane?.clearFocus()
            activeMusicPlayerPane?.clearFocus()
            activeAppsGridPane?.clearFocus()
            activeAppLauncherPane?.clearFocus()
            activeUrlLauncherPane?.clearFocus()
        }
    }

    // ── Edit mode ────────────────────────────────────────────────────────────

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        dismissCurrentPane()
        deactivateActiveButton()
        setFocusOwner(FocusOwner.BUTTONS)
        buttons.forEach { it.isEditMode = true }
        updateEditModeUI()
    }

    fun exitEditMode() {
        if (!editMode) return
        editMode = false
        buttons.forEach { it.isEditMode = false }
        updateEditModeUI()
        activateDashboardButton()
    }

    private fun updateEditModeUI() {
        if (editMode) {
            // Propagate edit mode flag to all buttons (delete overlays appear via invalidate).
            buttons.forEach { it.isEditMode = true }

            // Build and show chrome: [+ Add] row then [Done][Settings] row.
            editChromeContainer.removeAllViews()

            val addBtn = createFixedButton(getString(R.string.edit_add)) { addNewButton() }
            editChromeContainer.addView(addBtn)

            val doneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            val m = resources.dpToPx(4)
            val doneBtn = createFixedButton(getString(R.string.edit_done)) { exitEditMode() }
            val settingsBtn = createFixedButton(getString(R.string.edit_settings)) { showGlobalSettingsPane() }
            doneRow.addView(doneBtn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, m, 0) })
            doneRow.addView(settingsBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
            editChromeContainer.addView(doneRow)

            editChromeContainer.visibility = View.VISIBLE

            // Pad the scroll view so the last button isn't hidden behind the chrome.
            editChromeContainer.post {
                buttonScroll.setPadding(0, 0, 0, editChromeContainer.height)
                adjustButtonHeights()
            }

            buttonPanel.setOnDragListener(buttonDragListener)
        } else {
            buttons.forEach { it.isEditMode = false }
            editChromeContainer.visibility = View.GONE
            editChromeContainer.removeAllViews()
            buttonScroll.setPadding(0, 0, 0, 0)
            buttonPanel.setOnDragListener(null)
            adjustButtonHeights()
        }
    }

    // ── Edit mode: drag-to-reorder ────────────────────────────────────────────

    private val buttonDragListener = View.OnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED ->
                event.clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
            DragEvent.ACTION_DROP -> {
                val fromIndex = event.localState as? Int ?: return@OnDragListener false
                val toIndex = dropTargetIndex(event.y)
                if (fromIndex != toIndex) moveButton(fromIndex, toIndex)
                true
            }
            else -> true
        }
    }

    /** Map a drag Y coordinate (in buttonPanel space) to a buttons[] index. */
    private fun dropTargetIndex(dragY: Float): Int {
        for (i in buttons.indices) {
            if (dragY < buttons[i].top + buttons[i].height / 2f) return i
        }
        return buttons.lastIndex.coerceAtLeast(0)
    }

    private fun moveButton(from: Int, to: Int) {
        moveButtonIconFiles(from, to)
        db.moveButton(from, to)
        val moved = buttons.removeAt(from)
        buttons.add(to, moved)
        for (i in buttons.indices) {
            buttons[i].index = i
            buttons[i].loadConfig(db)
        }
        rebuildButtonPanel()
    }

    private fun moveButtonIconFiles(from: Int, to: Int) {
        val tmp = File(filesDir, "icon_tmp.png")
        tmp.delete()
        val fromFile = buttonIconFile(filesDir, from)
        if (fromFile.exists()) fromFile.copyTo(tmp, overwrite = true)
        val dir = if (from < to) 1 else -1
        var i = from
        while (i != to) {
            val next = i + dir
            val src = buttonIconFile(filesDir, next)
            val dst = buttonIconFile(filesDir, i)
            if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
            i = next
        }
        val dst = buttonIconFile(filesDir, to)
        if (tmp.exists()) tmp.copyTo(dst, overwrite = true) else dst.delete()
        tmp.delete()
    }

    // ── Edit mode: add / remove ───────────────────────────────────────────────

    private fun addNewButton() {
        showTypePickerPane()
    }

    private fun showTypePickerPane() {
        activeButtonConfigPane?.unload(); activeButtonConfigPane = null
        activeTypePickerPane?.unload();   activeTypePickerPane = null
        val pane = TypePickerPaneContent(
            activity = this,
            onTypeSelected = { typeKey ->
                activeTypePickerPane?.unload(); activeTypePickerPane = null
                createButtonWithType(typeKey)
            },
            onCancelled = {
                activeTypePickerPane?.unload(); activeTypePickerPane = null
            }
        )
        activeTypePickerPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun createButtonWithType(typeKey: String) {
        val i = db.addButton()
        db.saveButton(i, ButtonRow(type = typeKey))
        val btn = layoutInflater.inflate(
            R.layout.launcher_button_item, buttonPanel, false
        ) as LauncherButton
        btn.index = i
        btn.isEditMode = true
        wireButton(btn, i)
        btn.loadConfig(db)
        buttons.add(btn)
        rebuildButtonPanel()
        showButtonConfigPane(i)
    }

    private fun removeButton(index: Int) {
        if (buttons.size <= 1) return
        // Shift icon files down to close the gap.
        buttonIconFile(filesDir, index).delete()
        for (i in index until buttons.lastIndex) {
            val src = buttonIconFile(filesDir, i + 1)
            val dst = buttonIconFile(filesDir, i)
            if (src.exists()) src.copyTo(dst, overwrite = true) else dst.delete()
        }
        buttonIconFile(filesDir, buttons.lastIndex).delete()

        // Release any widget binding before DB remove.
        val cfg = buttons[index].config
        if (cfg is ButtonConfig.WidgetLauncher && cfg.appWidgetId != -1) {
            releaseWidgetId(cfg.appWidgetId)
        }

        db.removeButton(index)
        buttons.removeAt(index)

        for (i in buttons.indices) {
            buttons[i].index = i
            buttons[i].loadConfig(db)
        }

        if (focusedIndex > buttons.size) {
            focusedIndex = buttons.size.coerceAtLeast(1)
            db.setFocusedIndex(focusedIndex)
        }

        rebuildButtonPanel()
    }

    /** Rebuild buttonPanel from scratch, preserving chrome views when in edit mode. */
    private fun rebuildButtonPanel() {
        buttonPanel.removeAllViews()
        buttonPanel.addView(dashboardButton)
        for (btn in buttons) buttonPanel.addView(btn)
        buttonPanel.addView(appsButton)
        adjustButtonHeights()
    }

    /** Show a toggle button's content pane (no-op if already active). */
    private fun activateToggleButton(panelPos: Int, showPane: () -> Unit) {
        if (activeButtonIndex == panelPos) return
        hideCurrentPane()
        deactivateActiveButton()
        activeButtonIndex = panelPos
        buttons[panelPos - 1].isActiveButton = true
        showPane()
    }

    private fun deactivateActiveButton() {
        when {
            activeButtonIndex == 0 -> dashboardButton.isActiveButton = false
            activeButtonIndex in 1..buttons.size -> buttons[activeButtonIndex - 1].isActiveButton = false
            activeButtonIndex == buttons.size + 1 -> appsButton.isActiveButton = false
        }
        activeButtonIndex = -1
    }

    /** Send ACTION_APPWIDGET_UPDATE to the active widget pane, if any. */
    private fun refreshCurrentPane() {
        widgetPanes[visibleWidgetId]?.refresh()
    }

    /**
     * Hide the current pane without destroying it. Cached panes stay attached
     * (View.GONE) so they can be re-shown instantly. Settings pane is not
     * cached and is fully unloaded.
     */
    private fun hideCurrentPane() {
        activeDashboardPane?.hide()
        activeWebPane?.hide()
        activeAppLauncherPane?.hide()
        activeUrlLauncherPane?.hide()
        widgetPanes.values.forEach { it.hide() }
        activeAppsGridPane?.hide()
        activeMusicPlayerPane?.hide()
        activeBookmarksPane?.hide()
        activeNavTargetsPane?.hide()
        activeGlobalSettingsPane?.unload(); activeGlobalSettingsPane = null
        keyPane = null
        hideLoading()
    }

    /** Fully unload all panes and clear every ref and cache key. */
    private fun dismissCurrentPane() {
        activeDashboardPane?.unload();      activeDashboardPane = null
        activeWebPane?.unload();            activeWebPane = null;            cachedWebUrl = null
        activeAppLauncherPane?.unload();    activeAppLauncherPane = null;    cachedAppLauncherPkg = null
        activeUrlLauncherPane?.unload();    activeUrlLauncherPane = null;    cachedUrlLauncherUrl = null
        widgetPanes.values.forEach { it.unload() }; widgetPanes.clear()
        activeAppsGridPane?.unload();       activeAppsGridPane = null
        activeMusicPlayerPane?.unload();    activeMusicPlayerPane = null;    cachedMusicPkg = null
        activeBookmarksPane?.unload();      activeBookmarksPane = null
        activeNavTargetsPane?.unload();     activeNavTargetsPane = null
        activeGlobalSettingsPane?.unload(); activeGlobalSettingsPane = null
        activeButtonConfigPane?.unload();   activeButtonConfigPane = null
        activeTypePickerPane?.unload();     activeTypePickerPane = null
        keyPane = null
        hideLoading()
    }

    // ── Loading spinner ────────────────────────────────────────────────────────

    private fun showLoading() {
        hideLoading()
        val spinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        loadingSpinner = spinner
        reservedArea.addView(spinner)
    }

    private fun hideLoading() {
        loadingSpinner?.let { reservedArea.removeView(it) }
        loadingSpinner = null
    }

    // ── Content panes ────────────────────────────────────────────────────────

    private fun showDashboardPane() {
        activeDashboardPane?.let { pane ->
            pane.show(reservedArea); keyPane = pane; return
        }
        val pane = DashboardPaneContent(this)
        pane.onConfigRequested = { showGlobalSettingsPane() }
        activeDashboardPane = pane
        pane.load { pane.show(reservedArea); keyPane = pane }
    }

    private var cachedAllApps: List<AppEntry>? = null

    private fun showAllAppsPane() {
        activeAppsGridPane?.let { pane ->
            pane.show(reservedArea); keyPane = pane; return
        }
        val snapshot = cachedAllApps
        val pane = AppsGridPaneContent(this, db, snapshot ?: emptyList())
        if (snapshot != null) pane.onContentReady = { hideLoading() }
        activeAppsGridPane = pane
        keyPane = pane
        pane.load { pane.show(reservedArea); showLoading() }

        Thread {
            val fresh = loadAllApps()
            Handler(Looper.getMainLooper()).post {
                if (fresh != cachedAllApps) {
                    cachedAllApps = fresh
                    activeAppsGridPane?.updateAppList(fresh)
                }
                if (snapshot == null) hideLoading()
            }
        }.start()
    }

    private fun loadAllApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    private fun showWebPane(url: String) {
        activeWebPane?.let { pane ->
            if (cachedWebUrl == url) { pane.show(reservedArea); keyPane = pane; return }
            pane.unload(); activeWebPane = null; cachedWebUrl = null
        }
        val pane = WebPaneContent(this, url)
        pane.onContentReady = { hideLoading() }
        activeWebPane = pane
        cachedWebUrl = url
        keyPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
    }

    private fun showAppLauncherPane(packageName: String, label: String) {
        activeAppLauncherPane?.let { pane ->
            if (cachedAppLauncherPkg == packageName) { pane.show(reservedArea); keyPane = pane; return }
            pane.unload(); activeAppLauncherPane = null; cachedAppLauncherPkg = null
        }
        val pane = AppLauncherPaneContent(this, packageName, label) {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) startActivity(intent)
        }
        activeAppLauncherPane = pane
        cachedAppLauncherPkg = packageName
        keyPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun showUrlLauncherPane(url: String, label: String, icon: UrlIcon, buttonIndex: Int) {
        activeUrlLauncherPane?.let { pane ->
            if (cachedUrlLauncherUrl == url) { pane.show(reservedArea); keyPane = pane; return }
            pane.unload(); activeUrlLauncherPane = null; cachedUrlLauncherUrl = null
        }
        val pane = UrlLauncherPaneContent(this, url, label, icon, buttonIndex) {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
        activeUrlLauncherPane = pane
        cachedUrlLauncherUrl = url
        keyPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private var widgetRebindAttempted = false

    private fun showWidgetPane(appWidgetId: Int) {
        widgetPanes[appWidgetId]?.let { pane ->
            pane.show(reservedArea); keyPane = pane
            visibleWidgetId = appWidgetId
            return
        }
        val hv = widgetViews[appWidgetId] ?: run {
            // Not pre-created — attempt late creation (handles bindings configured
            // after onCreate, or a first launch after preCreateWidgetViews skipped a null info).
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
            if (info == null) {
                // Truly stale binding — system has no record of this ID. Deactivate
                // cleanly so the button doesn't get stuck in the active state.
                deactivateActiveButton()
                return
            }
            appWidgetHost.createView(this, appWidgetId, info).also { widgetViews[appWidgetId] = it }
        }
        val pane = WidgetPaneContent(this, hv, appWidgetId)
        pane.onContentReady = { hideLoading() }
        widgetPanes[appWidgetId] = pane
        visibleWidgetId = appWidgetId
        keyPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
        // After the first layout pass, check whether the widget hit a
        // SecurityException (stale content:// URI permissions on API 34).
        // OnGlobalLayoutListener fires after onMeasure(), unlike View.post().
        if (!widgetRebindAttempted) {
            hv.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    hv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if ((hv as? SafeAppWidgetHostView)?.hasSecurityError == true) {
                        rebindWidget(appWidgetId)
                    }
                }
            })
        }
    }

    /**
     * Delete a stale widget binding and create a fresh one to the same
     * provider.  The new binding triggers onUpdate() in the provider,
     * which pushes RemoteViews through the system service — re-granting
     * content:// URI permissions that were lost after an app update.
     */
    private fun rebindWidget(oldAppWidgetId: Int) {
        widgetRebindAttempted = true
        val mgr = AppWidgetManager.getInstance(this)
        val info = mgr.getAppWidgetInfo(oldAppWidgetId) ?: return
        val buttonIndex = buttons.indexOfFirst {
            (it.config as? ButtonConfig.WidgetLauncher)?.appWidgetId == oldAppWidgetId
        }
        if (buttonIndex == -1) return
        val oldConfig = buttons[buttonIndex].config as? ButtonConfig.WidgetLauncher ?: return

        releaseWidgetId(oldAppWidgetId)

        val newId = appWidgetHost.allocateAppWidgetId()
        if (!mgr.bindAppWidgetIdIfAllowed(newId, info.provider)) {
            Log.w(TAG, "Widget rebind denied for ${info.provider}")
            releaseWidgetId(newId)
            return
        }

        val newConfig = oldConfig.copy(appWidgetId = newId)
        buttons[buttonIndex].saveConfig(db, newConfig)
        widgetViews[newId] = appWidgetHost.createView(this, newId, info)

        dismissCurrentPane()
        showWidgetPane(newId)
    }

    private fun showMusicPlayerPane(playerPackage: String) {
        activeMusicPlayerPane?.let { pane ->
            if (cachedMusicPkg == playerPackage) { pane.show(reservedArea); keyPane = pane; return }
            pane.unload(); activeMusicPlayerPane = null; cachedMusicPkg = null
        }
        val pane = MusicPlayerPaneContent(this, playerPackage)
        pane.onContentReady = { hideLoading() }
        activeMusicPlayerPane = pane
        cachedMusicPkg = playerPackage
        keyPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
    }

    private fun showBookmarksPane(buttonIndex: Int) {
        activeBookmarksPane?.let { pane -> pane.show(reservedArea); keyPane = pane; return }
        val pane = BookmarksPaneContent(
            context              = this,
            buttonIndex          = buttonIndex,
            db                   = db,
            onUrlActivated       = { url -> showWebPane(url) },
            onUrlBrowserActivated = { url ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
        )
        activeBookmarksPane = pane
        keyPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun showNavTargetsPane(buttonIndex: Int) {
        activeNavTargetsPane?.let { pane -> pane.show(reservedArea); keyPane = pane; return }
        val row = db.loadButton(buttonIndex)
        val appPackage = row?.value ?: ""
        val pane = NavTargetsPaneContent(
            context     = this,
            buttonIndex = buttonIndex,
            appPackage  = appPackage,
            db          = db
        )
        activeNavTargetsPane = pane
        keyPane = pane
        pane.load { pane.show(reservedArea) }
    }

    // ── Widget binding ────────────────────────────────────────────────────────

    private fun releaseWidgetId(appWidgetId: Int) {
        appWidgetHost.deleteAppWidgetId(appWidgetId)
        widgetViews.remove(appWidgetId)
    }

    /**
     * If the process was killed between allocateAppWidgetId() and
     * onActivityResult(), the allocated ID is orphaned. Clean it up on
     * the next launch.
     */
    private fun cleanupOrphanedWidgetId() {
        val orphan = db.getPendingWidgetId()
        if (orphan != -1) {
            releaseWidgetId(orphan)
            db.setPendingWidgetId(null)
        }
    }

    private fun savePendingWidgetId(id: Int) {
        db.setPendingWidgetId(id)
    }

    private fun clearPendingWidgetId() {
        db.setPendingWidgetId(null)
    }

    private fun completeWidgetBinding(appWidgetId: Int) {
        if (pendingWidgetConfig == null) return
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        if (info?.configure != null) {
            // Let the system construct the configure Intent via AppWidgetService.
            // This grants the configure activity proper permissions through an
            // IntentSender — manually constructing the Intent bypasses this and
            // causes crashes in many widget providers.
            //
            // MODE_BACKGROUND_ACTIVITY_START_ALLOWED is required on API 34+:
            // the IntentSender comes from the system process (uid 1000) which
            // has no visible window, so Android's BAL rules block the launch
            // unless the foreground caller explicitly opts in.
            val opts = android.app.ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            @Suppress("DEPRECATION")
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                this, appWidgetId, 0, CONFIGURE_WIDGET_REQUEST_CODE, opts.toBundle()
            )
        } else {
            finishWidgetSetup()
        }
    }

    private fun cancelPendingWidget() {
        val fromButtonConfig = pendingWidgetFromButtonConfig
        pendingWidgetConfig?.let {
            if (it.appWidgetId != -1) releaseWidgetId(it.appWidgetId)
        }
        pendingWidgetConfig           = null
        pendingWidgetFromButtonConfig = false
        clearPendingWidgetId()
        if (fromButtonConfig) {
            val row = db.loadButton(pendingWidgetButtonIndex)
            if (row != null) db.saveButton(pendingWidgetButtonIndex, row.copy(widgetId = null))
            buttons.getOrNull(pendingWidgetButtonIndex)?.loadConfig(db)
            activeButtonConfigPane?.rebuildFromDb()
        } else {
            deactivateActiveButton()
            setFocusOwner(FocusOwner.BUTTONS)
        }
    }

    private fun finishWidgetSetup() {
        val cfg              = pendingWidgetConfig ?: return
        val fromButtonConfig = pendingWidgetFromButtonConfig
        pendingWidgetConfig           = null
        pendingWidgetFromButtonConfig = false
        clearPendingWidgetId()
        buttons[pendingWidgetButtonIndex].saveConfig(db, cfg)
        if (cfg.appWidgetId != -1) {
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(cfg.appWidgetId)
            if (info != null) {
                widgetViews[cfg.appWidgetId] = appWidgetHost.createView(this, cfg.appWidgetId, info)
            }
        }
        if (fromButtonConfig) {
            activeButtonConfigPane?.rebuildFromDb()
        } else if (cfg.appWidgetId != -1) {
            showWidgetPane(cfg.appWidgetId)
            setFocusOwner(FocusOwner.PANE)
        } else {
            deactivateActiveButton()
            setFocusOwner(FocusOwner.BUTTONS)
        }
    }

    /**
     * Pre-create AppWidgetHostViews for all configured widgets.
     * Called in onCreate() before startListening() so the views are
     * registered in the host's mViews map when the system delivers
     * cached RemoteViews — matching the standard launcher pattern.
     */
    private fun preCreateWidgetViews() {
        val mgr = AppWidgetManager.getInstance(this)
        for (btn in buttons) {
            val cfg = btn.config
            if (cfg is ButtonConfig.WidgetLauncher && cfg.appWidgetId != -1) {
                val info = mgr.getAppWidgetInfo(cfg.appWidgetId) ?: continue
                widgetViews[cfg.appWidgetId] = appWidgetHost.createView(this, cfg.appWidgetId, info)
            }
        }
    }

    // ── Image picker result ───────────────────────────────────────────────────

    private var pendingIconButtonIndex: Int = 0

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BIND_WIDGET_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    val id = pendingWidgetConfig?.appWidgetId ?: -1
                    if (id != -1) completeWidgetBinding(id)
                } else {
                    cancelPendingWidget()
                }
                return
            }
            CONFIGURE_WIDGET_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    finishWidgetSetup()
                } else {
                    cancelPendingWidget()
                }
                return
            }
        }

        if (requestCode == IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dest = buttonIconFile(filesDir, pendingIconButtonIndex)
            try {
                val isSvg = contentResolver.getType(uri) == "image/svg+xml"
                if (isSvg) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val svg = SVG.getFromInputStream(input)
                        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                        svg.renderToCanvas(Canvas(bmp), RectF(0f, 0f, 512f, 512f))
                        dest.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bmp.recycle()
                    }
                } else {
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                activeButtonConfigPane?.rebuild()
            } catch (_: Exception) { /* ignore failed pick */ }
        }
    }

    // ── Back key ──────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (focusOwner == FocusOwner.PANE) {
            setFocusOwner(FocusOwner.BUTTONS)
        }
        // Home launcher never exits on back.
    }

    // ── Button setup ────────────────────────────────────────────────────────

    private fun wireButton(btn: LauncherButton, i: Int) {
        btn.setOnLongClickListener { enterEditMode(); true }
        btn.onEditTapped = { showButtonConfigPane(buttons.indexOf(btn)) }
        btn.onDeleteTapped = { removeButton(buttons.indexOf(btn)) }
        // Use buttons.indexOf(btn) + 1 for panel position so re-indexing after
        // add/remove always resolves to the correct position at activation time.
        btn.onAppLauncherActivated = { pkg, lbl ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showAppLauncherPane(pkg, lbl) }
        }
        btn.onUrlActivated = { url ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showWebPane(url) }
        }
        btn.onUrlLauncherActivated = { url, lbl, ic ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showUrlLauncherPane(url, lbl, ic, i) }
        }
        btn.onWidgetActivated = { id ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showWidgetPane(id) }
        }
        btn.onMusicPlayerActivated = { pkg ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showMusicPlayerPane(pkg) }
        }
        btn.onBookmarkCollectionActivated = { idx ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showBookmarksPane(idx) }
        }
        btn.onNavTargetCollectionActivated = { idx ->
            val p = buttons.indexOf(btn) + 1
            activateToggleButton(p) { showNavTargetsPane(idx) }
        }
    }

    private fun createFixedButton(label: String, iconRes: Int? = null, onClick: () -> Unit): FocusableButton {
        val btn = FocusableButton(this)
        val m = resources.dpToPx(4)
        btn.layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(m, m, m, m)
        }
        btn.text = label
        btn.textSize = 28f
        btn.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        btn.setTextColor(getColor(R.color.text_primary))
        btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.button_inactive))
        if (iconRes != null) btn.buttonIcon = getDrawable(iconRes)
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun adjustButtonHeights() {
        val panelPad = resources.dpToPx(8) * 2  // buttonPanel top + bottom padding
        val totalH = buttonScroll.height - panelPad - buttonScroll.paddingBottom
        val margin = resources.dpToPx(4) * 2  // top + bottom margin per button
        val slots = (buttons.size + 2).coerceIn(1, MAX_VISIBLE_BUTTONS)  // +2 for dashboard + apps
        val btnH = totalH / slots - margin
        for (btn in buttons) {
            val lp = btn.layoutParams as LinearLayout.LayoutParams
            lp.height = btnH
            btn.layoutParams = lp
        }
        listOf(dashboardButton, appsButton).forEach { fixed ->
            val lp = fixed.layoutParams as LinearLayout.LayoutParams
            lp.height = btnH
            fixed.layoutParams = lp
        }
    }

    private fun scrollToFocused(animate: Boolean = true) {
        val target: View = when {
            focusedIndex == 0              -> dashboardButton
            focusedIndex in 1..buttons.size -> buttons[focusedIndex - 1]
            else                           -> appsButton
        }
        val slotH = target.measuredHeight +
            ((target.layoutParams as? LinearLayout.LayoutParams)
                ?.let { it.topMargin + it.bottomMargin } ?: 0)
        val scrollY = (target.top - 2 * slotH).coerceAtLeast(0).toFloat()
        if (!animate) {
            scrollSpring?.cancel()
            scrollSpring = null
            buttonScroll.scrollTo(0, scrollY.toInt())
            return
        }
        val spring = scrollSpring
        if (spring != null && spring.isRunning) {
            // Redirect in-flight animation to the new target without losing velocity
            spring.animateToFinalPosition(scrollY)
        } else {
            val holder = FloatValueHolder(buttonScroll.scrollY.toFloat())
            scrollSpring = SpringAnimation(holder).apply {
                setSpring(SpringForce(scrollY).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                    stiffness    = SpringForce.STIFFNESS_LOW
                })
                addUpdateListener { _, value, _ -> buttonScroll.scrollTo(0, value.toInt()) }
                start()
            }
        }
    }

    /** Move focus into the active content pane (if it has interactive elements). */
    private fun enterPane() {
        if (activeDashboardPane != null || activeAppsGridPane != null
            || activeMusicPlayerPane != null || activeAppLauncherPane != null
            || activeUrlLauncherPane != null || activeGlobalSettingsPane != null) {
            setFocusOwner(FocusOwner.PANE)
        }
    }

    // ── Edit mode pane routing ───────────────────────────────────────────────

    private fun showButtonConfigPane(index: Int) {
        activeButtonConfigPane?.unload()
        val pane = ButtonConfigPaneContent(
            activity         = this,
            db               = db,
            buttonIndex      = index,
            onSaved          = {
                buttons.getOrNull(index)?.loadConfig(db)
                activeButtonConfigPane?.unload()
                activeButtonConfigPane = null
            },
            onDiscarded      = {
                activeButtonConfigPane?.unload()
                activeButtonConfigPane = null
            },
            onPickImage      = { btnIdx ->
                pendingIconButtonIndex = btnIdx
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, IMAGE_REQUEST_CODE)
            },
            onWidgetBind     = { btnIdx, provider ->
                val oldCfg = buttons.getOrNull(btnIdx)?.config
                if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1) {
                    releaseWidgetId(oldCfg.appWidgetId)
                }
                val btnData = db.loadButton(btnIdx)
                val label   = btnData?.label ?: ""
                val icon    = UrlIcon.fromRow(btnData?.iconType, btnData?.iconData)
                val newId   = appWidgetHost.allocateAppWidgetId()
                savePendingWidgetId(newId)
                pendingWidgetConfig             = ButtonConfig.WidgetLauncher(provider, newId, label, icon)
                pendingWidgetButtonIndex        = btnIdx
                pendingWidgetFromButtonConfig   = true
                val mgr = AppWidgetManager.getInstance(this)
                if (mgr.bindAppWidgetIdIfAllowed(newId, provider)) {
                    completeWidgetBinding(newId)
                } else {
                    val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newId)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(bindIntent, BIND_WIDGET_REQUEST_CODE)
                }
            },
            onWidgetCleanup  = { appWidgetId -> releaseWidgetId(appWidgetId) }
        )
        activeButtonConfigPane = pane
        pane.load { pane.show(reservedArea) }
    }

    // ── Global settings pane ─────────────────────────────────────────────────

    private fun showGlobalSettingsPane() {
        activeGlobalSettingsPane?.unload()
        val pane = GlobalSettingsPaneContent(this)
        activeGlobalSettingsPane = pane
        pane.load { pane.show(reservedArea) }
    }

    companion object {
        private const val TAG = "aR2Launcher"
        private const val LONG_PRESS_MS                   = 600L
        private const val MAX_VISIBLE_BUTTONS             = 6
        private const val DEFAULT_BUTTON_COUNT            = 6
        private const val IMAGE_REQUEST_CODE              = 1001
        private const val BIND_WIDGET_REQUEST_CODE        = 1002
        private const val CONFIGURE_WIDGET_REQUEST_CODE   = 1003
        private const val APP_WIDGET_HOST_ID              = 1536
        private const val LOCATION_PERMISSION_REQUEST     = 1004
    }
}
