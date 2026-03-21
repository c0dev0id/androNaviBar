package de.codevoid.andronavibar

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.view.MotionEvent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var buttons: List<MaterialButton>
    private var configMode = false
    private var focusedIndex = 0
    private val handler = Handler(Looper.getMainLooper())

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
            buttons[i].setOnClickListener { onButtonClick(i) }
            buttons[i].setOnLongClickListener {
                toggleConfigMode()
                true
            }
            buttons[i].setOnTouchListener { _, event ->
                // Consume ACTION_DOWN on unfocused buttons outside config mode so
                // no pressed-state overlay or ripple fires on a focus-only tap.
                if (event.action == MotionEvent.ACTION_DOWN && !configMode && i != focusedIndex) {
                    focusedIndex = i
                    saveFocus()
                    updateButtonStyles()
                    true
                } else false
            }
        }

        loadButtons()
        updateButtonStyles()
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

    private val pressedKeys = mutableSetOf<Int>()

    private val remoteListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "com.thorkracing.wireddevices.keypress") return

            if (intent.hasExtra("key_press")) {
                val keyCode = intent.getIntExtra("key_press", 0)
                if (!pressedKeys.add(keyCode)) return  // auto-repeat, ignore
                when (keyCode) {
                    19 -> moveFocus(-1)     // UP
                    20 -> moveFocus(+1)     // DOWN
                    66 -> activateFocused() // ROUND BUTTON 1 — select
                }
            } else if (intent.hasExtra("key_release")) {
                pressedKeys.remove(intent.getIntExtra("key_release", 0))
            }
        }
    }

    private fun moveFocus(delta: Int) {
        focusedIndex = (focusedIndex + delta).coerceIn(0, buttons.lastIndex)
        saveFocus()
        updateButtonStyles()
    }

    private fun saveFocus() {
        prefs.edit().putInt("focused_index", focusedIndex).apply()
    }

    private fun activateFocused() {
        onButtonClick(focusedIndex)
    }

    private fun toggleConfigMode() {
        configMode = !configMode
        updateButtonStyles()
    }

    private fun updateButtonStyles() {
        val configStroke = resources.getDimensionPixelSize(R.dimen.config_stroke_width)
        for (i in buttons.indices) {
            val btn = buttons[i]
            btn.foreground = if (i == focusedIndex) makeFocusRing() else null
            if (configMode) {
                btn.strokeColor = ColorStateList.valueOf(getColor(R.color.config_border))
                btn.strokeWidth = configStroke
            } else {
                btn.strokeWidth = 0
            }
        }
    }

    private fun makeFocusRing(): InsetDrawable {
        val gap = dpToPx(4)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()  // button 16dp corner + 4dp gap
            setStroke(dpToPx(6), getColor(R.color.colorPrimary))
            setColor(Color.TRANSPARENT)
        }
        return InsetDrawable(ring, -gap)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun flashButton(index: Int) {
        val btn = buttons[index]
        btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.colorPrimary))
        handler.postDelayed({
            btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.button_inactive))
        }, 150L)
    }

    private fun onButtonClick(index: Int) {
        if (configMode) {
            showConfigDialog(index)
        } else {
            flashButton(index)
            launchButton(index)
        }
    }

    private fun launchButton(index: Int) {
        val type = prefs.getString("btn_${index}_type", null) ?: return
        val value = prefs.getString("btn_${index}_value", null) ?: return

        when (type) {
            "app" -> {
                val intent = packageManager.getLaunchIntentForPackage(value)
                if (intent != null) {
                    startActivity(intent)
                }
            }
            "url" -> {
                val url = if (value.startsWith("http://") || value.startsWith("https://"))
                    value else "https://$value"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }
    }

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
                    2 -> clearButton(index)
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
                val pkgName = app.activityInfo.packageName
                val label = app.loadLabel(packageManager).toString()
                saveButton(index, "app", pkgName, label)
                loadButtons()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUrlDialog(index: Int) {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setText(prefs.getString("btn_${index}_value", ""))
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
                    saveButton(index, "url", url, url)
                    loadButtons()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveButton(index: Int, type: String, value: String, label: String) {
        prefs.edit()
            .putString("btn_${index}_type", type)
            .putString("btn_${index}_value", value)
            .putString("btn_${index}_label", label)
            .apply()
    }

    private fun clearButton(index: Int) {
        prefs.edit()
            .remove("btn_${index}_type")
            .remove("btn_${index}_value")
            .remove("btn_${index}_label")
            .apply()
        loadButtons()
    }

    private fun loadButtons() {
        for (i in buttons.indices) {
            val type = prefs.getString("btn_${i}_type", null)
            val label = prefs.getString("btn_${i}_label", null)

            if (type != null && label != null) {
                buttons[i].text = label
                buttons[i].icon = getButtonIcon(i)
            } else {
                buttons[i].text = getString(R.string.empty)
                buttons[i].icon = null
            }
        }
    }

    private fun getButtonIcon(index: Int): Drawable? {
        val type = prefs.getString("btn_${index}_type", null) ?: return null
        val value = prefs.getString("btn_${index}_value", null) ?: return null

        return when (type) {
            "app" -> {
                try {
                    packageManager.getApplicationIcon(value)
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    override fun onBackPressed() {
        if (configMode) {
            toggleConfigMode()
        }
        // Do nothing when not in config mode - this is a home app
    }
}
