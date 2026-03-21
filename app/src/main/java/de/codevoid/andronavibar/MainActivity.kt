package de.codevoid.andronavibar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.os.Bundle
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var prefs:        SharedPreferences
    private lateinit var buttons:      List<LauncherButton>
    private lateinit var reservedArea: FrameLayout

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

    /** Non-null while a config pane is displayed in reservedArea. */
    private var activeConfigPane: ConfigPaneContent? = null

    private val paneFocused get() = activeConfigPane != null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemBars()
        prefs        = getSharedPreferences(LauncherApplication.PREFS_NAME, MODE_PRIVATE)
        reservedArea = findViewById(R.id.reservedArea)
        focusedIndex = prefs.getInt("focused_index", 0)

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
            buttons[i].loadConfig(prefs)
        }

        updateFocus()
    }

    override fun onResume() {
        super.onResume()
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

                if (paneFocused) {
                    activeConfigPane?.handleKey(keyCode)
                } else {
                    when (keyCode) {
                        19 -> moveFocus(-1)                  // DPAD_UP
                        20 -> moveFocus(+1)                  // DPAD_DOWN
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
                        // Short press → dismiss config pane (cancel); no focus check needed.
                        dismissConfigPane()
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

    // ── Config pane ───────────────────────────────────────────────────────────

    private fun openConfigPane(buttonIndex: Int) {
        dismissConfigPane()   // silently close any open pane (no save)
        setFocus(buttonIndex)

        val pane = ConfigPaneContent(
            context       = this,
            buttonIndex   = buttonIndex,
            initialConfig = buttons[buttonIndex].config,
            onSave        = { newConfig ->
                buttons[buttonIndex].saveConfig(prefs, newConfig)
                dismissConfigPane()
            },
            onCancel = { dismissConfigPane() },
            onClear  = { buttons[buttonIndex].clearConfig(prefs) }
        )

        pane.onPickImageRequest = {
            pendingIconButtonIndex = buttonIndex
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, IMAGE_REQUEST_CODE)
        }

        activeConfigPane = pane
        pane.load { pane.show(reservedArea) }
    }

    /**
     * Remove the config pane without saving. Always safe to call; no-op when
     * no pane is open. onSave is never fired from here.
     */
    private fun dismissConfigPane() {
        val pane = activeConfigPane ?: return
        activeConfigPane = null
        pane.unload()
    }

    // ── Image picker result ───────────────────────────────────────────────────

    private var pendingIconButtonIndex: Int = 0

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val dest = File(filesDir, "btn_${pendingIconButtonIndex}_icon.png")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                activeConfigPane?.onImageReady()
            } catch (_: Exception) { /* ignore failed pick */ }
        }
    }

    // ── Back key ──────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back = dismiss config pane without saving. Home app never exits on back.
        dismissConfigPane()
    }

    companion object {
        private const val IMAGE_REQUEST_CODE = 1001
    }
}
