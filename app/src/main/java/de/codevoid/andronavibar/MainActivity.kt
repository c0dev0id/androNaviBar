package de.codevoid.andronavibar

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var prefs:           SharedPreferences
    private val buttons = mutableListOf<LauncherButton>()
    private lateinit var buttonScroll:    ScrollView
    private lateinit var buttonPanel:     LinearLayout
    private lateinit var configureButton: FocusableButton
    private lateinit var reservedArea:    FrameLayout
    private lateinit var appWidgetHost:   SafeAppWidgetHost
    private val widgetViews = mutableMapOf<Int, AppWidgetHostView>()

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

    /** Non-null while a music player pane is displayed in reservedArea. */
    private var activeMusicPlayerPane: MusicPlayerPaneContent? = null

    /** Non-null while a config pane is displayed in reservedArea. */
    private var activeConfigPane: ConfigPaneContent? = null

    /** Holds a partially-bound widget config while waiting for the system bind dialog result. */
    private var pendingWidgetConfig: ButtonConfig.WidgetLauncher? = null
    private var pendingWidgetButtonIndex: Int = 0

    /** Slot index of the button whose config pane is open (-1 = none). */
    private var configPaneButtonIndex = -1

    /** Loading spinner overlay, shown while a pane's content is being prepared. */
    private var loadingSpinner: ProgressBar? = null

    private enum class FocusOwner { BUTTONS, PANE }

    /** Which region currently owns D-pad input. */
    private var focusOwner = FocusOwner.BUTTONS

    /** Index of the button whose content/config pane is displayed (-1 = none). */
    private var activeButtonIndex = -1

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemBars()
        prefs         = getSharedPreferences(LauncherApplication.PREFS_NAME, MODE_PRIVATE)
        reservedArea  = findViewById(R.id.reservedArea)
        buttonScroll  = findViewById(R.id.buttonScroll)
        buttonPanel   = findViewById(R.id.buttonPanel)
        appWidgetHost = SafeAppWidgetHost(this, APP_WIDGET_HOST_ID)
        cleanupOrphanedWidgetId()
        focusedIndex  = prefs.getInt("focused_index", 0)

        val count = prefs.getInt("button_count", DEFAULT_BUTTON_COUNT)
        for (i in 0 until count) {
            val btn = layoutInflater.inflate(
                R.layout.launcher_button_item, buttonPanel, false
            ) as LauncherButton
            btn.index = i
            wireButton(btn, i)
            btn.loadConfig(prefs)
            buttonPanel.addView(btn)
            buttons.add(btn)
        }

        configureButton = createConfigureButton()
        buttonPanel.addView(configureButton)

        preCreateWidgetViews()
        focusedIndex = focusedIndex.coerceIn(0, buttons.size)

        buttonPanel.post {
            adjustButtonHeights()
            scrollToFocused()
        }
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

                when (focusOwner) {
                    FocusOwner.PANE -> {
                        if (activeConfigPane != null) {
                            activeConfigPane?.handleKey(keyCode)
                        } else {
                            val handled = activeAppsGridPane?.handleKey(keyCode)
                                ?: activeMusicPlayerPane?.handleKey(keyCode)
                                ?: false
                            if (!handled && keyCode == 22) {    // RIGHT at pane edge
                                setFocusOwner(FocusOwner.BUTTONS)
                            }
                        }
                    }
                    FocusOwner.BUTTONS -> when (keyCode) {
                        19 -> moveFocus(-1)                     // DPAD_UP
                        20 -> moveFocus(+1)                     // DPAD_DOWN
                        21 -> enterPane()                       // LEFT → enter pane
                        66 -> {                                 // ROUND BUTTON 1
                            if (focusedIndex < buttons.size)
                                buttons[focusedIndex].activate()
                        }
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
                            activeConfigPane != null -> {
                                dismissConfigPane()
                                deactivateActiveButton()
                                setFocusOwner(FocusOwner.BUTTONS)
                            }
                            focusOwner == FocusOwner.PANE ->
                                setFocusOwner(FocusOwner.BUTTONS)
                        }
                    }
                    // Long press already handled by LauncherApplication.
                }
            }
        }
    }

    // ── Focus management ──────────────────────────────────────────────────────

    private fun setFocus(index: Int) {
        focusedIndex = index.coerceIn(0, buttons.size)
        prefs.edit().putInt("focused_index", focusedIndex).apply()
        updateFocus()
        scrollToFocused()
    }

    private fun moveFocus(delta: Int) {
        setFocus(focusedIndex + delta)
    }

    private fun updateFocus() {
        for (i in buttons.indices) {
            buttons[i].isFocusedButton = (focusOwner == FocusOwner.BUTTONS && i == focusedIndex)
        }
        configureButton.isFocusedButton = (focusOwner == FocusOwner.BUTTONS && focusedIndex == buttons.size)
    }

    private fun setFocusOwner(owner: FocusOwner) {
        focusOwner = owner
        updateFocus()
        if (owner == FocusOwner.PANE) {
            activeMusicPlayerPane?.setInitialFocus()
            activeAppsGridPane?.setInitialFocus()
        } else {
            activeMusicPlayerPane?.clearFocus()
            activeAppsGridPane?.clearFocus()
        }
    }

    /**
     * Show a toggle button's content pane. If the button is already active
     * (its pane is displayed), toggle it off instead.
     */
    private fun activateToggleButton(index: Int, showPane: () -> Unit) {
        if (activeButtonIndex == index) {
            dismissCurrentPane()
            deactivateActiveButton()
            return
        }
        dismissCurrentPane()
        deactivateActiveButton()
        activeButtonIndex = index
        buttons[index].isActiveButton = true
        showPane()
    }

    private fun deactivateActiveButton() {
        if (activeButtonIndex >= 0) {
            buttons[activeButtonIndex].isActiveButton = false
            activeButtonIndex = -1
        }
    }

    private fun dismissCurrentPane() {
        activeWebPane?.unload();         activeWebPane = null
        activeWidgetPane?.unload();      activeWidgetPane = null
        activeAppsGridPane?.unload();    activeAppsGridPane = null
        activeMusicPlayerPane?.unload(); activeMusicPlayerPane = null
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

    private fun showWebPane(url: String) {
        val pane = WebPaneContent(this, url)
        pane.onContentReady = { hideLoading() }
        activeWebPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
    }

    private fun showWidgetPane(appWidgetId: Int) {
        val hv = widgetViews[appWidgetId] ?: return
        val pane = WidgetPaneContent(this, hv, appWidgetId)
        pane.onContentReady = { hideLoading() }
        activeWidgetPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
    }

    private fun showAppsGridPane(apps: List<AppEntry>) {
        val pane = AppsGridPaneContent(this, apps)
        pane.onContentReady = { hideLoading() }
        activeAppsGridPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
    }

    private fun showMusicPlayerPane(playerPackage: String) {
        val pane = MusicPlayerPaneContent(this, playerPackage)
        pane.onContentReady = { hideLoading() }
        activeMusicPlayerPane = pane
        pane.load { pane.show(reservedArea); showLoading() }
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
            widgetViews.remove(orphan)
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

    private fun finishWidgetSetup() {
        val cfg = pendingWidgetConfig ?: return
        pendingWidgetConfig = null
        clearPendingWidgetId()
        buttons[pendingWidgetButtonIndex].saveConfig(prefs, cfg)
        if (cfg.appWidgetId != -1) {
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(cfg.appWidgetId)
            if (info != null) {
                widgetViews[cfg.appWidgetId] = appWidgetHost.createView(this, cfg.appWidgetId, info)
            }
            // Button is already active from the config→bind flow
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

    // ── Config pane ───────────────────────────────────────────────────────────

    private fun openConfigPane(buttonIndex: Int) {
        dismissConfigPane()
        dismissCurrentPane()
        deactivateActiveButton()
        setFocus(buttonIndex)
        configPaneButtonIndex = buttonIndex
        activeButtonIndex = buttonIndex
        buttons[buttonIndex].isActiveButton = true

        val pane = ConfigPaneContent(
            context       = this,
            buttonIndex   = buttonIndex,
            initialConfig = buttons[buttonIndex].config,
            onSave        = { newConfig ->
                if (newConfig is ButtonConfig.WidgetLauncher && newConfig.appWidgetId == -1) {
                    // Widget bind flow — button stays active through the process.
                    val newId   = appWidgetHost.allocateAppWidgetId()
                    savePendingWidgetId(newId)
                    val oldCfg  = buttons[configPaneButtonIndex].config
                    if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1) {
                        appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                        widgetViews.remove(oldCfg.appWidgetId)
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
                    val oldCfg = buttons[configPaneButtonIndex].config
                    if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1 &&
                        (newConfig !is ButtonConfig.WidgetLauncher ||
                         newConfig.appWidgetId != oldCfg.appWidgetId)) {
                        appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                        widgetViews.remove(oldCfg.appWidgetId)
                    }
                    buttons[configPaneButtonIndex].saveConfig(prefs, newConfig)
                    dismissConfigPane()
                    deactivateActiveButton()
                    setFocusOwner(FocusOwner.BUTTONS)
                }
            },
            onCancel = {
                dismissConfigPane()
                deactivateActiveButton()
                setFocusOwner(FocusOwner.BUTTONS)
            },
            onClear  = {
                val oldCfg = buttons[configPaneButtonIndex].config
                if (oldCfg is ButtonConfig.WidgetLauncher && oldCfg.appWidgetId != -1) {
                    appWidgetHost.deleteAppWidgetId(oldCfg.appWidgetId)
                    widgetViews.remove(oldCfg.appWidgetId)
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
        pane.load { pane.show(reservedArea) }
        setFocusOwner(FocusOwner.PANE)
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
                    pendingWidgetConfig?.let {
                        if (it.appWidgetId != -1) {
                            appWidgetHost.deleteAppWidgetId(it.appWidgetId)
                            widgetViews.remove(it.appWidgetId)
                        }
                    }
                    pendingWidgetConfig = null
                    clearPendingWidgetId()
                    deactivateActiveButton()
                    setFocusOwner(FocusOwner.BUTTONS)
                }
                return
            }
            CONFIGURE_WIDGET_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    finishWidgetSetup()
                } else {
                    pendingWidgetConfig?.let {
                        if (it.appWidgetId != -1) {
                            appWidgetHost.deleteAppWidgetId(it.appWidgetId)
                            widgetViews.remove(it.appWidgetId)
                        }
                    }
                    pendingWidgetConfig = null
                    clearPendingWidgetId()
                    deactivateActiveButton()
                    setFocusOwner(FocusOwner.BUTTONS)
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
            activeConfigPane != null -> {
                dismissConfigPane()
                deactivateActiveButton()
                setFocusOwner(FocusOwner.BUTTONS)
            }
            focusOwner == FocusOwner.PANE ->
                setFocusOwner(FocusOwner.BUTTONS)
        }
        // Home launcher never exits on back.
    }

    // ── Button setup ────────────────────────────────────────────────────────

    private fun wireButton(btn: LauncherButton, i: Int) {
        btn.onFocusRequested = {
            if (activeConfigPane == null) {
                if (focusOwner == FocusOwner.PANE) setFocusOwner(FocusOwner.BUTTONS)
                setFocus(i)
            }
        }
        btn.onUrlActivated         = { url  -> activateToggleButton(i) { showWebPane(url) } }
        btn.onWidgetActivated      = { id   -> activateToggleButton(i) { showWidgetPane(id) } }
        btn.onAppsGridActivated    = { apps -> activateToggleButton(i) { showAppsGridPane(apps) } }
        btn.onMusicPlayerActivated = { pkg  -> activateToggleButton(i) { showMusicPlayerPane(pkg) } }
    }

    private fun createConfigureButton(): FocusableButton {
        val btn = FocusableButton(this)
        val m = dpToPx(4)
        btn.layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(m, m, m, m)
        }
        btn.text = getString(R.string.config_button)
        btn.textSize = 28f
        btn.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        btn.setTextColor(getColor(R.color.text_primary))
        btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.button_inactive))
        btn.cornerRadius = dpToPx(FocusableButton.CORNER_RADIUS_DP)
        // Configure button is touch-activated — bypass the two-tap model.
        btn.setOnTouchListener(null)
        btn.setOnClickListener {
            if (activeConfigPane == null && focusedIndex < buttons.size) {
                openConfigPane(focusedIndex)
            }
        }
        return btn
    }

    private fun adjustButtonHeights() {
        val totalH = buttonScroll.height
        val margin = dpToPx(4) * 2  // top + bottom margin per button
        val btnH = totalH / MAX_VISIBLE_BUTTONS - margin
        for (btn in buttons) {
            val lp = btn.layoutParams as LinearLayout.LayoutParams
            lp.height = btnH
            btn.layoutParams = lp
        }
        val clp = configureButton.layoutParams as LinearLayout.LayoutParams
        clp.height = btnH
        configureButton.layoutParams = clp
    }

    private fun scrollToFocused() {
        val target: View = if (focusedIndex < buttons.size) {
            buttons.getOrNull(focusedIndex) ?: return
        } else {
            configureButton
        }
        buttonScroll.smoothScrollTo(0, target.top)
    }

    /** Move focus into the active content pane (if it has interactive elements). */
    private fun enterPane() {
        if (activeAppsGridPane != null || activeMusicPlayerPane != null) {
            setFocusOwner(FocusOwner.PANE)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MAX_VISIBLE_BUTTONS             = 6
        private const val DEFAULT_BUTTON_COUNT            = 6
        private const val IMAGE_REQUEST_CODE              = 1001
        private const val BIND_WIDGET_REQUEST_CODE        = 1002
        private const val CONFIGURE_WIDGET_REQUEST_CODE   = 1003
        private const val APP_WIDGET_HOST_ID              = 1536
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
