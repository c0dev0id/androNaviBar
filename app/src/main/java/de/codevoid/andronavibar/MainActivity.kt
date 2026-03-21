package de.codevoid.andronavibar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.FrameLayout
import android.os.Bundle

class MainActivity : Activity() {

    private lateinit var prefs:        SharedPreferences
    private lateinit var buttons:      List<LauncherButton>
    private lateinit var reservedArea: FrameLayout

    private var focusedIndex = 0

    // ── Pane coordination ─────────────────────────────────────────────────────

    /** Non-null while a config pane is displayed in reservedArea. */
    private var activeConfigPane: ConfigPaneContent? = null

    private val paneFocused get() = activeConfigPane != null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs        = getSharedPreferences("button_config", MODE_PRIVATE)
        reservedArea = findViewById(R.id.reservedArea)
        focusedIndex = prefs.getInt("focused_index", 0)

        buttons = listOf(
            findViewById(R.id.button0),
            findViewById(R.id.button1),
            findViewById(R.id.button2),
            findViewById(R.id.button3),
            findViewById(R.id.button4)
        )

        for (i in buttons.indices) {
            buttons[i].index            = i
            buttons[i].onFocusRequested = { setFocus(i) }
            buttons[i].onConfigRequested = { openConfigPane(i) }
            buttons[i].loadConfig(prefs)
        }

        updateFocus()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            remoteListener,
            IntentFilter("com.thorkracing.wireddevices.keypress"),
            Context.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(remoteListener) } catch (_: Exception) {}
        pressedKeys.clear()
    }

    // ── Remote input ──────────────────────────────────────────────────────────

    private val pressedKeys = mutableSetOf<Int>()

    private val remoteListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "com.thorkracing.wireddevices.keypress") return

            if (intent.hasExtra("key_press")) {
                val keyCode = intent.getIntExtra("key_press", 0)
                if (!pressedKeys.add(keyCode)) return  // auto-repeat, ignore

                if (paneFocused) {
                    when (keyCode) {
                        111 -> activeConfigPane?.save()          // ROUND BUTTON 2 — save + close
                        else -> activeConfigPane?.handleKey(keyCode)
                    }
                } else {
                    when (keyCode) {
                        19  -> moveFocus(-1)                     // DPAD_UP
                        20  -> moveFocus(+1)                     // DPAD_DOWN
                        66  -> buttons[focusedIndex].activate()  // ROUND BUTTON 1 — activate
                        // 21 LEFT, 22 RIGHT — reserved: pane focus in/out, tab navigation
                        // 111 ROUND BUTTON 2 — no-op outside pane (home app never exits)
                        // 136 LEVER UP, 137 LEVER DOWN — reserved: button list page up/down
                    }
                }
            } else if (intent.hasExtra("key_release")) {
                pressedKeys.remove(intent.getIntExtra("key_release", 0))
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
            initialConfig = buttons[buttonIndex].config,
            onSave        = { newConfig ->
                buttons[buttonIndex].saveConfig(prefs, newConfig)
                dismissConfigPane()
            },
            onClear = {
                buttons[buttonIndex].clearConfig(prefs)
            }
        )

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

    // ── Back key ──────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        // Back = dismiss without saving (cancel). Home app never exits on back.
        dismissConfigPane()
    }
}
