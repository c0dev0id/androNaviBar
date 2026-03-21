package de.codevoid.andronavibar

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var prefs:           SharedPreferences
    private lateinit var buttons:         List<LauncherButton>
    private lateinit var reservedArea:    FrameLayout
    private lateinit var dragHandlePanel: LinearLayout
    private lateinit var appWidgetHost:   AppWidgetHost

    private var focusedIndex = 0

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

    /** Non-null while a web pane is displayed in reservedArea. */
    private var activeWebPane: WebPaneContent? = null

    /** Non-null while a widget pane is displayed in reservedArea. */
    private var activeWidgetPane: WidgetPaneContent? = null

    /** Non-null while an apps grid pane is displayed in reservedArea. */
    private var activeAppsGridPane: AppsGridPaneContent? = null

    /** Non-null while a config pane is displayed in reservedArea. */
    private var activeConfigPane: ConfigPaneContent? = null

    /** Holds a partially-bound widget config while waiting for the system bind dialog result. */
    private var pendingWidgetConfig: ButtonConfig.WidgetLauncher? = null
    private var pendingWidgetButtonIndex: Int = 0

    /**
     * The current slot index of the button whose config pane is open.
     * Kept in sync with drag reorders so save/clear/image-pick always
     * target the right slot even after buttons have been moved around.
     */
    private var configPaneButtonIndex = -1

    private val paneFocused get() = activeConfigPane != null || activeAppsGridPane != null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemBars()
        prefs          = getSharedPreferences(LauncherApplication.PREFS_NAME, MODE_PRIVATE)
        reservedArea   = findViewById(R.id.reservedArea)
        appWidgetHost  = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        cleanupOrphanedWidgetId()
        focusedIndex   = prefs.getInt("focused_index", 0)

        buttons = listOf(
            findViewById(R.id.button0),
            findViewById(R.id.button1),
            findViewById(R.id.button2),
            findViewById(R.id.button3),
            findViewById(R.id.button4),
            findViewById(R.id.button5)
        )

        for (i in buttons.indices) {
            buttons[i].index             = i
            buttons[i].onFocusRequested  = { setFocus(i) }
            buttons[i].onConfigRequested = { openConfigPane(i) }
            buttons[i].onUrlActivated    = { url -> showWebPane(url) }
            buttons[i].onWidgetActivated    = { widgetId -> showWidgetPane(widgetId) }
            buttons[i].onAppsGridActivated  = { apps -> showAppsGridPane(apps) }
            buttons[i].loadConfig(prefs)
        }

        setupDragHandles()
        updateFocus()
    }

    override fun onResume() {
        super.onResume()
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
        appWidgetHost.stopListening()
        LauncherApplication.mainActivity = null
        isWindowFocused = false
        try { unregisterReceiver(remoteListener) } catch (_: Exception) {}
        pressedKeys.clear()
        key111PressedAt = 0L
    }

    // ── Remote input ──────────────────────────────────────────────────────────

    private val pressedKeys = mutableSetOf<Int>()

    /**
     * Monotonic timestamp of the most recent key-111 press. Used to distinguish
     * a short press (< TOGGLE_HOLD_MS → cancel) from a long press (≥ TOGGLE_HOLD_MS
     * → handled by LauncherApplication; no additional action here).
     */
    private var key111PressedAt = 0L

    private val remoteListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LauncherApplication.REMOTE_ACTION) return

            if (intent.hasExtra("key_press")) {
                val keyCode = intent.getIntExtra("key_press", 0)
                if (!pressedKeys.add(keyCode)) return  // auto-repeat, ignore

                if (keyCode == LauncherApplication.TOGGLE_KEY) {
                    // Record press time; act on release to distinguish short vs long.
                    key111PressedAt = SystemClock.elapsedRealtime()
                    return
                }

                // All other keys are gated on window focus.
                if (!isWindowFocused) return

                if (paneFocused) {
                    activeConfigPane?.handleKey(keyCode) ?: activeAppsGridPane?.handleKey(keyCode)
                } else {
                    when (keyCode) {
                        19 -> moveFocus(-1)                    // DPAD_UP
                        20 -> moveFocus(+1)                    // DPAD_DOWN
                        66 -> buttons[focusedIndex].activate() // ROUND BUTTON 1
                        // 21 LEFT, 22 RIGHT — reserved
                        // 136 LEVER UP, 137 LEVER DOWN — reserved
                    }
                }

            } else if (intent.hasExtra("key_release")) {
                val keyCode = intent.getIntExtra("key_release", 0)
                pressedKeys.remove(keyCode)

                if (keyCode == LauncherApplication.TOGGLE_KEY && key111PressedAt > 0L) {
                    val held = SystemClock.elapsedRealtime() - key111PressedAt
                    key111PressedAt = 0L
                    if (held < LauncherApplication.TOGGLE_HOLD_MS) {
                        when {
                            activeConfigPane != null         -> dismissConfigPane()
                            activeWebPane?.goBack() == false -> dismissWebPane()
                            activeWidgetPane != null         -> dismissWidgetPane()
                            activeAppsGridPane != null       -> dismissAppsGridPane()
                        }
                    }
                    // Long press already handled by LauncherApplication.
                }
            }
        }
    }

    // ── Focus management ──────────────────────────────────────────────────────

    private fun setFocus(index: Int) {
        focusedIndex = index
        prefs.edit().putInt("focused_index", focusedIndex).apply()
        updateFocus()
    }

    private fun moveFocus(delta: Int) {
        setFocus((focusedIndex + delta).coerceIn(0, buttons.lastIndex))
    }

    private fun updateFocus() {
        for (i in buttons.indices) {
            buttons[i].isFocusedButton = (i == focusedIndex)
        }
    }

    // ── Web pane ──────────────────────────────────────────────────────────────

    private fun showWebPane(url: String) {
        dismissConfigPane()
        dismissWebPane()
        dismissWidgetPane()
        dismissAppsGridPane()
        val pane = WebPaneContent(this, url)
        activeWebPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun dismissWebPane() {
        val pane = activeWebPane ?: return
        activeWebPane = null
        pane.unload()
    }

    // ── Widget pane ───────────────────────────────────────────────────────────

    private fun showWidgetPane(appWidgetId: Int) {
        dismissConfigPane()
        dismissWebPane()
        dismissWidgetPane()
        dismissAppsGridPane()
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId) ?: return
        val pane = WidgetPaneContent(this, appWidgetHost, appWidgetId, info)
        activeWidgetPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun dismissWidgetPane() {
        val pane = activeWidgetPane ?: return
        activeWidgetPane = null
        pane.unload()
    }

    // ── Apps grid pane ────────────────────────────────────────────────────────

    private fun showAppsGridPane(apps: List<AppEntry>) {
        dismissConfigPane()
        dismissWebPane()
        dismissWidgetPane()
        dismissAppsGridPane()
        val pane = AppsGridPaneContent(this, apps)
        activeAppsGridPane = pane
        pane.load { pane.show(reservedArea) }
    }

    private fun dismissAppsGridPane() {
        val pane = activeAppsGridPane ?: return
        activeAppsGridPane = null
        pane.unload()
    }

    // ── Widget binding ────────────────────────────────────────────────────────

    /**
     * If the process was killed between allocateAppWidgetId() and
     * onActivityResult(), the allocated ID is orphaned. Clean it up on
     * the next launch.
     */
    private fun cleanupOrphanedWidgetId() {
        val orphan = prefs.getInt("pending_widget_id", -1)
        if (orphan != -1) {
            appWidgetHost.deleteAppWidgetId(orphan)
            prefs.edit().remove("pending_widget_id").apply()
        }
    }

    private fun savePendingWidgetId(id: Int) {
        prefs.edit().putInt("pending_widget_id", id).apply()
    }

    private fun clearPendingWidgetId() {
        prefs.edit().remove("pending_widget_id").apply()
    }

    private fun completeWidgetBinding(appWidgetId: Int) {
        if (pendingWidgetConfig == null) return
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        if (info?.configure != null) {
            // No action — some configure activities crash when launched with
            // ACTION_APPWIDGET_CONFIGURE. The explicit component is sufficient.
            val intent = Intent().apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, CONFIGURE_WIDGET_REQUEST_CODE)
        } else {
            finishWidgetSetup()
        }
    }

    private fun finishWidgetSetup() {
        val cfg = pendingWidgetConfig ?: return
        pendingWidgetConfig = null
        clearPendingWidgetId()
        buttons[pendingWidgetButtonIndex].saveConfig(prefs, cfg)
        // Show the widget pane immediately so the user sees the result.
        if (cfg.appWidgetId != -1) {
            appWidgetHost.startListening()   // ensure host is active before creating view
            showWidgetPane(cfg.appWidgetId)
        }
    }

    // ── Config pane ───────────────────────────────────────────────────────────

    private fun openConfigPane(buttonIndex: Int) {
        dismissWebPane()
        dismissConfigPane()   // silently close any open pane (no save)
        setFocus(buttonIndex)
        configPaneButtonIndex = buttonIndex

        val pane = ConfigPaneContent(
            context       = this,
            buttonIndex   = buttonIndex,
            initialConfig = buttons[buttonIndex].config,
            onSave        = { newConfig ->
                if (newConfig is ButtonConfig.WidgetLauncher && newConfig.appWidgetId == -1) {
                    // Need to bind a widget — allocate an ID and start the system bind flow.
                    val newId   = appWidgetHost.allocateAppWidgetId()
                    savePendingWidgetId(newId)
                    val oldCfg  = buttons[configPaneButtonIndex].config
                    if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1) {
                        appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                    }
                    pendingWidgetConfig       = newConfig.copy(appWidgetId = newId)
                    pendingWidgetButtonIndex  = configPaneButtonIndex
                    dismissConfigPane()
                    val mgr = AppWidgetManager.getInstance(this)
                    if (mgr.bindAppWidgetIdIfAllowed(newId, newConfig.provider)) {
                        completeWidgetBinding(newId)
                    } else {
                        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newId)
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, newConfig.provider)
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, BIND_WIDGET_REQUEST_CODE)
                    }
                } else {
                    // Free old widget ID if we're replacing a widget with something else.
                    val oldCfg = buttons[configPaneButtonIndex].config
                    if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1 &&
                        (newConfig !is ButtonConfig.WidgetLauncher ||
                         newConfig.appWidgetId != oldCfg.appWidgetId)) {
                        appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                    }
                    buttons[configPaneButtonIndex].saveConfig(prefs, newConfig)
                    dismissConfigPane()
                }
            },
            onCancel = { dismissConfigPane() },
            onClear  = {
                val oldCfg = buttons[configPaneButtonIndex].config
                if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1) {
                    appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                }
                buttons[configPaneButtonIndex].clearConfig(prefs)
            }
        )

        pane.onPickImageRequest = {
            pendingIconButtonIndex = configPaneButtonIndex
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, IMAGE_REQUEST_CODE)
        }

        activeConfigPane = pane
        dragHandlePanel.visibility = View.VISIBLE
        pane.load { pane.show(reservedArea) }
    }

    /**
     * Remove the config pane without saving. Always safe to call; no-op when
     * no pane is open. onSave is never fired from here.
     */
    private fun dismissConfigPane() {
        val pane = activeConfigPane ?: return
        activeConfigPane = null
        configPaneButtonIndex = -1
        pane.unload()
        dragHandlePanel.visibility = View.GONE
        if (dragSourceIndex >= 0) cancelDrag()
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
                    // User denied the bind dialog; release the allocated ID.
                    pendingWidgetConfig?.let {
                        if (it.appWidgetId != -1) appWidgetHost.deleteAppWidgetId(it.appWidgetId)
                    }
                    pendingWidgetConfig = null
                    clearPendingWidgetId()
                }
                return
            }
            CONFIGURE_WIDGET_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    finishWidgetSetup()
                } else {
                    // User cancelled configuration; delete the bound-but-unconfigured widget.
                    pendingWidgetConfig?.let {
                        if (it.appWidgetId != -1) appWidgetHost.deleteAppWidgetId(it.appWidgetId)
                    }
                    pendingWidgetConfig = null
                    clearPendingWidgetId()
                }
                return
            }
        }

        if (requestCode == IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dest = File(filesDir, "btn_${pendingIconButtonIndex}_icon.png")
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
                activeConfigPane?.onImageReady()
            } catch (_: Exception) { /* ignore failed pick */ }
        }
    }

    // ── Back key ──────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            activeConfigPane != null         -> dismissConfigPane()
            activeWebPane?.goBack() == false -> dismissWebPane()
            activeWidgetPane != null         -> dismissWidgetPane()
            activeAppsGridPane != null       -> dismissAppsGridPane()
        }
        // If nothing is open, do nothing — home launcher never exits on back.
    }

    // ── Drag-to-reorder ───────────────────────────────────────────────────────

    private var dragSourceIndex = -1
    private var dragTargetIndex = -1
    private var ghostOverlay:   FrameLayout? = null
    private var ghostView:      ImageView?   = null

    private fun setupDragHandles() {
        dragHandlePanel = findViewById(R.id.dragHandlePanel)
        val m = dpToPx(4)
        for (i in buttons.indices) {
            dragHandlePanel.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                    setMargins(m, m, m, m)
                }
                text     = "⠿"
                gravity  = Gravity.CENTER
                textSize = 18f
                setTextColor(getColor(R.color.text_secondary))
                val idx = i
                setOnTouchListener { _, event -> onDragHandleTouch(idx, event) }
            })
        }
    }

    private fun onDragHandleTouch(index: Int, event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN   -> { startDrag(index, event.rawY); true }
            MotionEvent.ACTION_MOVE   -> { updateDrag(event.rawY);       true }
            MotionEvent.ACTION_UP     -> { finishDrag();                  true }
            MotionEvent.ACTION_CANCEL -> { cancelDrag();                  true }
            else -> false
        }
    }

    private fun startDrag(index: Int, rawY: Float) {
        dragSourceIndex = index
        dragTargetIndex = index

        val btn = buttons[index]
        val bmp = Bitmap.createBitmap(btn.width, btn.height, Bitmap.Config.ARGB_8888)
        btn.draw(Canvas(bmp))
        btn.alpha = 0f   // hide source; ghost takes its place

        val overlay = FrameLayout(this).also {
            it.isClickable = false
            it.isFocusable = false
            ghostOverlay = it
            addContentView(it, ViewGroup.LayoutParams(MATCH, MATCH))
        }

        val loc = IntArray(2)
        btn.getLocationOnScreen(loc)
        ghostView = ImageView(this).apply {
            setImageBitmap(bmp)
            alpha = 0.85f
            layoutParams = FrameLayout.LayoutParams(btn.width, btn.height).apply {
                leftMargin = loc[0]
                topMargin  = loc[1]
            }
            overlay.addView(this)
        }
    }

    private fun updateDrag(rawY: Float) {
        val ghost = ghostView ?: return
        val anchor = buttons[0]
        val btnH   = anchor.height
        if (btnH == 0) return

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val panelTop = loc[1]

        // Slide ghost vertically with the finger
        val params = ghost.layoutParams as FrameLayout.LayoutParams
        params.topMargin = (rawY.toInt() - btnH / 2)
            .coerceIn(panelTop, panelTop + buttons.size * btnH - btnH)
        ghost.requestLayout()

        // Recalculate drop target and animate buttons to show the gap
        val newTarget = ((rawY - panelTop) / btnH).toInt().coerceIn(0, buttons.lastIndex)
        if (newTarget != dragTargetIndex) {
            dragTargetIndex = newTarget
            animateDragShift()
        }
    }

    /** Translate buttons to visually open a gap at the current drop target. */
    private fun animateDragShift() {
        val from = dragSourceIndex
        val to   = dragTargetIndex
        val btnH = buttons[0].height.toFloat()
        for (i in buttons.indices) {
            val shift = when {
                i == from                                -> 0f
                from < to && i in (from + 1)..to        -> -btnH
                from > to && i in to     until from     ->  btnH
                else                                    ->  0f
            }
            buttons[i].animate().translationY(shift).setDuration(120).start()
        }
    }

    private fun finishDrag() {
        buttons.forEach { it.alpha = 1f; it.animate().translationY(0f).setDuration(120).start() }
        (ghostOverlay?.parent as? ViewGroup)?.removeView(ghostOverlay)
        ghostOverlay = null
        ghostView    = null
        val from = dragSourceIndex
        val to   = dragTargetIndex
        dragSourceIndex = -1
        dragTargetIndex = -1
        if (from >= 0 && from != to) reorderButtons(from, to)
    }

    private fun cancelDrag() {
        buttons.forEach { it.alpha = 1f; it.animate().translationY(0f).setDuration(120).start() }
        (ghostOverlay?.parent as? ViewGroup)?.removeView(ghostOverlay)
        ghostOverlay = null
        ghostView    = null
        dragSourceIndex = -1
        dragTargetIndex = -1
    }

    private fun reorderButtons(from: Int, to: Int) {
        val keys = listOf("_type", "_value", "_label", "_icon_type", "_icon_data", "_widget_id", "_open_browser", "_apps")
        // Snapshot all button prefs
        val snap = Array(buttons.size) { i ->
            keys.associateWith { k -> prefs.getString("btn_$i$k", null) }
        }.toMutableList()
        snap.add(to, snap.removeAt(from))

        // Write new order
        val edit = prefs.edit()
        for (i in snap.indices) {
            for (k in keys) {
                val v = snap[i][k]
                if (v != null) edit.putString("btn_$i$k", v) else edit.remove("btn_$i$k")
            }
        }

        // Keep focused_index and configPaneButtonIndex pointing at the same logical buttons
        fun rotate(idx: Int) = when {
            idx == from                         -> to
            from < to && idx in (from + 1)..to  -> idx - 1
            from > to && idx in to until from   -> idx + 1
            else                                -> idx
        }
        val newFocus = rotate(focusedIndex)
        if (configPaneButtonIndex >= 0) configPaneButtonIndex = rotate(configPaneButtonIndex)
        edit.putInt("focused_index", newFocus)
        edit.apply()

        rotateIconFiles(from, to)

        focusedIndex = newFocus
        for (b in buttons) b.loadConfig(prefs)
        updateFocus()
    }

    private fun rotateIconFiles(from: Int, to: Int) {
        fun f(i: Int) = File(filesDir, "btn_${i}_icon.png")
        val tmp = File(filesDir, "btn_drag_tmp.png")
        if (f(from).exists()) f(from).copyTo(tmp, overwrite = true) else tmp.delete()
        if (from < to) {
            for (i in from until to) {
                if (f(i + 1).exists()) f(i + 1).copyTo(f(i), overwrite = true) else f(i).delete()
            }
        } else {
            for (i in from downTo to + 1) {
                if (f(i - 1).exists()) f(i - 1).copyTo(f(i), overwrite = true) else f(i).delete()
            }
        }
        if (tmp.exists()) tmp.copyTo(f(to), overwrite = true) else f(to).delete()
        tmp.delete()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val IMAGE_REQUEST_CODE          = 1001
        private const val BIND_WIDGET_REQUEST_CODE    = 1002
        private const val CONFIGURE_WIDGET_REQUEST_CODE = 1003
        private const val APP_WIDGET_HOST_ID          = 1536
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
