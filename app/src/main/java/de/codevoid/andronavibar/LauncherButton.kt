package de.codevoid.andronavibar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// ── Button configuration ──────────────────────────────────────────────────────

sealed class ButtonConfig {
    object Empty : ButtonConfig()

    data class AppLauncher(
        val packageName: String,
        val label: String
    ) : ButtonConfig()

    data class UrlLauncher(
        val url: String,
        val label: String
    ) : ButtonConfig()

    // Planned toggle/pane types:
    // data class Widget(...) : ButtonConfig()
    // data class MusicPlayer(...) : ButtonConfig()
    // data class Metrics(...) : ButtonConfig()
}

// ── LauncherButton ────────────────────────────────────────────────────────────

class LauncherButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    // Set by MainActivity during setup; used as the SharedPreferences key suffix.
    var index: Int = 0

    var config: ButtonConfig = ButtonConfig.Empty
        private set

    // ── Callbacks wired by MainActivity ──────────────────────────────────────

    /** Fired when this unfocused button receives ACTION_DOWN (focus-only tap). */
    var onFocusRequested: (() -> Unit)? = null

    /** Fired on long-press; MainActivity toggles config mode. */
    var onLongPressed: (() -> Unit)? = null

    /** Fired when activated in config mode; MainActivity shows the config dialog. */
    var onConfigRequest: (() -> Unit)? = null

    // ── Visual state ─────────────────────────────────────────────────────────

    var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = if (value) makeFocusRing() else null
        }

    var isInConfigMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                strokeColor = ColorStateList.valueOf(context.getColor(R.color.config_border))
                strokeWidth = dpToPx(2)
            } else {
                strokeWidth = 0
            }
        }

    // ── Pane loading (used by future toggle/pane types) ───────────────────────

    private var paneContent: PaneContent? = null
    private var isLoading = false

    /** CoroutineScope for async pane loading; cancelled when detached. */
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Internal ─────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    init {
        setOnClickListener { activate() }
        setOnLongClickListener {
            onLongPressed?.invoke()
            true
        }
        setOnTouchListener { _, event ->
            // Consume ACTION_DOWN on unfocused buttons outside config mode so the
            // Material pressed-state overlay does not fire on a focus-only tap.
            if (event.action == MotionEvent.ACTION_DOWN && !isInConfigMode && !isFocusedButton) {
                onFocusRequested?.invoke()
                true
            } else false
        }
    }

    // ── Config persistence ────────────────────────────────────────────────────

    fun loadConfig(prefs: SharedPreferences) {
        val type  = prefs.getString("btn_${index}_type",  null)
        val value = prefs.getString("btn_${index}_value", null)
        val label = prefs.getString("btn_${index}_label", null)
        config = when {
            type == "app" && value != null && label != null -> ButtonConfig.AppLauncher(value, label)
            type == "url" && value != null && label != null -> ButtonConfig.UrlLauncher(value, label)
            else -> ButtonConfig.Empty
        }
        applyConfig()
    }

    fun saveConfig(prefs: SharedPreferences, newConfig: ButtonConfig) {
        config = newConfig
        when (newConfig) {
            is ButtonConfig.AppLauncher -> prefs.edit()
                .putString("btn_${index}_type",  "app")
                .putString("btn_${index}_value", newConfig.packageName)
                .putString("btn_${index}_label", newConfig.label)
                .apply()
            is ButtonConfig.UrlLauncher -> prefs.edit()
                .putString("btn_${index}_type",  "url")
                .putString("btn_${index}_value", newConfig.url)
                .putString("btn_${index}_label", newConfig.label)
                .apply()
            is ButtonConfig.Empty -> clearConfig(prefs)
        }
        applyConfig()
    }

    fun clearConfig(prefs: SharedPreferences) {
        config = ButtonConfig.Empty
        prefs.edit()
            .remove("btn_${index}_type")
            .remove("btn_${index}_value")
            .remove("btn_${index}_label")
            .apply()
        applyConfig()
    }

    private fun applyConfig() {
        when (val cfg = config) {
            is ButtonConfig.Empty -> {
                text = context.getString(R.string.empty)
                icon = null
            }
            is ButtonConfig.AppLauncher -> {
                text = cfg.label
                icon = try { context.packageManager.getApplicationIcon(cfg.packageName) }
                       catch (_: Exception) { null }
            }
            is ButtonConfig.UrlLauncher -> {
                text = cfg.label
                icon = null
            }
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────

    fun activate() {
        if (isInConfigMode) {
            onConfigRequest?.invoke()
            return
        }
        when (val cfg = config) {
            is ButtonConfig.Empty -> Unit
            is ButtonConfig.AppLauncher -> {
                flashActivation()
                val intent = context.packageManager.getLaunchIntentForPackage(cfg.packageName)
                if (intent != null) context.startActivity(intent)
            }
            is ButtonConfig.UrlLauncher -> {
                flashActivation()
                val url = if (cfg.url.startsWith("http://") || cfg.url.startsWith("https://"))
                    cfg.url else "https://${cfg.url}"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            // TODO: toggle/pane types — call load() here; show() on onReady
        }
    }

    // ── Visuals ───────────────────────────────────────────────────────────────

    private fun flashActivation() {
        backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
        handler.postDelayed({
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
        }, 150L)
    }

    private fun makeFocusRing(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(16).toFloat()
        setStroke(dpToPx(6), context.getColor(R.color.colorPrimary))
        setColor(Color.TRANSPARENT)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }
}
