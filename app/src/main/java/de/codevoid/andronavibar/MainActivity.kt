package de.codevoid.andronavibar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.FrameLayout
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var buttons: List<LauncherButton>
    private var configMode = false
    private var focusedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("button_config", MODE_PRIVATE)
        focusedIndex = prefs.getInt("focused_index", 0)

        buttons = listOf(
            findViewById(R.id.button0),
            findViewById(R.id.button1),
            findViewById(R.id.button2),
            findViewById(R.id.button3),
            findViewById(R.id.button4)
        )

        for (i in buttons.indices) {
            buttons[i].index = i
            buttons[i].onFocusRequested = { setFocus(i) }
            buttons[i].onLongPressed    = { toggleConfigMode() }
            buttons[i].onConfigRequest  = { showConfigDialog(i) }
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
                when (keyCode) {
                    19 -> moveFocus(-1)              // DPAD_UP
                    20 -> moveFocus(+1)              // DPAD_DOWN
                    66 -> buttons[focusedIndex].activate()  // ENTER / Round Button 1
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

    // ── Config mode ───────────────────────────────────────────────────────────

    private fun toggleConfigMode() {
        configMode = !configMode
        for (btn in buttons) btn.isInConfigMode = configMode
    }

    // ── Config dialogs ────────────────────────────────────────────────────────

    private fun showConfigDialog(index: Int) {
        val options = arrayOf(
            getString(R.string.choose_app),
            getString(R.string.enter_url),
            getString(R.string.clear)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.configure)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAppPicker(index)
                    1 -> showUrlDialog(index)
                    2 -> buttons[index].clearConfig(prefs)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppPicker(index: Int) {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val appNames = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_app)
            .setItems(appNames) { _, which ->
                val app = apps[which]
                buttons[index].saveConfig(prefs, ButtonConfig.AppLauncher(
                    packageName = app.activityInfo.packageName,
                    label       = app.loadLabel(packageManager).toString()
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUrlDialog(index: Int) {
        val currentUrl = (buttons[index].config as? ButtonConfig.UrlLauncher)?.url ?: ""
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setText(currentUrl)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_url)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    buttons[index].saveConfig(prefs, ButtonConfig.UrlLauncher(url, url))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onBackPressed() {
        if (configMode) toggleConfigMode()
        // Do nothing when not in config mode — this is a home app.
    }
}
