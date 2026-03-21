package de.codevoid.andronavibar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

// ── Icon type for URL buttons ─────────────────────────────────────────────────

sealed class UrlIcon {
    object None : UrlIcon()
    /** User-provided image; stored at the button's icon file. */
    object CustomFile : UrlIcon()
    /** Emoji rendered onto a tinted background; no file on disk. */
    data class Emoji(val emoji: String) : UrlIcon()
}

// ── Button configuration ──────────────────────────────────────────────────────

sealed class ButtonConfig {
    object Empty : ButtonConfig()

    data class AppLauncher(
        val packageName: String,
        val label: String
    ) : ButtonConfig()

    data class UrlLauncher(
        val url: String,
        val label: String,              // empty = fall back to url for display
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    data class WidgetLauncher(
        val provider: ComponentName,
        val appWidgetId: Int,           // -1 until bound
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    // Planned toggle/pane types:
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

    /** Fired on long-press; MainActivity opens the config pane for this button. */
    var onConfigRequested: (() -> Unit)? = null

    /** Fired when a URL button is activated; MainActivity shows the URL in a WebView pane. */
    var onUrlActivated: ((String) -> Unit)? = null

    /** Fired when a Widget button is activated; MainActivity shows the widget pane. */
    var onWidgetActivated: ((Int) -> Unit)? = null

    // ── Visual state ─────────────────────────────────────────────────────────

    var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = if (value) makeFocusRing() else null
        }

    // ── Full-height icon ──────────────────────────────────────────────────────

    /**
     * Icon drawn as a full-height square on the left edge of the button.
     * Setting this automatically adjusts paddingStart and triggers a redraw.
     */
    private var buttonIcon: Drawable? = null
        set(value) {
            field = value
            updateIconPadding()
            invalidate()
        }

    private fun updateIconPadding() {
        val iconWidth = if (buttonIcon != null && height > 0) height else 0
        setPaddingRelative(iconWidth, paddingTop, paddingEnd, paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateIconPadding()
    }

    override fun onDraw(canvas: Canvas) {
        buttonIcon?.let { drawable ->
            val h = height.toFloat()
            val cornerRad = dpToPx(16).toFloat()
            // Inset by 2 × focus ring stroke (2 × 6dp = 12dp) on all sides so the
            // icon sits visually inside the focus frame rather than behind it.
            // The horizontal inset is halved (6dp) to compensate for Material3's
            // default insetTop/insetBottom (6dp each), which shrink the visible button
            // background away from the view's top/bottom edges — making the top/bottom
            // gap look smaller than the left gap at equal insets.
            val vInset = dpToPx(6) * 2          // 12dp top/bottom
            val hInset = dpToPx(9)              // 9dp left (= 12dp − M3's ~3dp insetTop)
            val iconSize = height - vInset * 2
            // Clip to the button's full rounded rect so the icon respects corner radius.
            val path = Path()
            path.addRoundRect(RectF(0f, 0f, width.toFloat(), h), cornerRad, cornerRad, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            drawable.setBounds(hInset, vInset, hInset + iconSize, vInset + iconSize)
            drawable.draw(canvas)
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    // ── Pane loading (used by future toggle/pane types) ───────────────────────

    private var paneContent: PaneContent? = null
    private var isLoading = false

    /** CoroutineScope for async work (pane loading, favicon fetch); cancelled when detached. */
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Internal ─────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    init {
        setOnClickListener { activate() }
        setOnLongClickListener {
            onConfigRequested?.invoke()
            true
        }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && !isFocusedButton) {
                onFocusRequested?.invoke()
                true
            } else false
        }
    }

    // ── Config persistence ────────────────────────────────────────────────────

    fun loadConfig(prefs: SharedPreferences) {
        val type  = prefs.getString("btn_${index}_type",  null)
        val value = prefs.getString("btn_${index}_value", null)

        config = when {
            type == "app" && value != null -> {
                val label = prefs.getString("btn_${index}_label", null)
                if (label != null) ButtonConfig.AppLauncher(value, label) else ButtonConfig.Empty
            }
            type == "widget" && value != null -> {
                val cn = ComponentName.unflattenFromString(value)
                if (cn == null) {
                    ButtonConfig.Empty
                } else {
                    val widgetId  = prefs.getString("btn_${index}_widget_id", null)?.toIntOrNull() ?: -1
                    val labelRaw  = prefs.getString("btn_${index}_label", "") ?: ""
                    val iconType  = prefs.getString("btn_${index}_icon_type", null)
                    val iconData  = prefs.getString("btn_${index}_icon_data", null)
                    val icon = when (iconType) {
                        "custom" -> UrlIcon.CustomFile
                        "emoji"  -> UrlIcon.Emoji(iconData ?: "")
                        else     -> UrlIcon.None
                    }
                    ButtonConfig.WidgetLauncher(cn, widgetId, labelRaw, icon)
                }
            }
            type == "url" && value != null -> {
                val labelRaw = prefs.getString("btn_${index}_label", "") ?: ""
                // Migrate: old code stored url as label — treat label==url as empty.
                val label = if (labelRaw == value) "" else labelRaw
                val iconType = prefs.getString("btn_${index}_icon_type", null)
                val iconData = prefs.getString("btn_${index}_icon_data", null)
                val icon = when (iconType) {
                    "custom" -> UrlIcon.CustomFile
                    "emoji"  -> UrlIcon.Emoji(iconData ?: "")
                    else     -> UrlIcon.None   // includes legacy "favicon" → no icon
                }
                ButtonConfig.UrlLauncher(value, label, icon)
            }
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
                .removeIconKeys()
                .apply()

            is ButtonConfig.UrlLauncher -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",  "url")
                    .putString("btn_${index}_value", newConfig.url)
                    .putString("btn_${index}_label", newConfig.label)
                when (val ico = newConfig.icon) {
                    is UrlIcon.None -> edit.removeIconKeys()
                    is UrlIcon.CustomFile -> edit
                        .putString("btn_${index}_icon_type", "custom")
                        .remove("btn_${index}_icon_data")
                    is UrlIcon.Emoji -> edit
                        .putString("btn_${index}_icon_type", "emoji")
                        .putString("btn_${index}_icon_data", ico.emoji)
                }
                edit.apply()

                // Delete stale icon file when not using a file-based icon.
                if (newConfig.icon is UrlIcon.None || newConfig.icon is UrlIcon.Emoji) {
                    iconFile().delete()
                }
            }

            is ButtonConfig.WidgetLauncher -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",      "widget")
                    .putString("btn_${index}_value",     newConfig.provider.flattenToString())
                    .putString("btn_${index}_label",     newConfig.label)
                    .putString("btn_${index}_widget_id", newConfig.appWidgetId.toString())
                when (val ico = newConfig.icon) {
                    is UrlIcon.None       -> edit.removeIconKeys()
                    is UrlIcon.CustomFile -> edit
                        .putString("btn_${index}_icon_type", "custom")
                        .remove("btn_${index}_icon_data")
                    is UrlIcon.Emoji      -> edit
                        .putString("btn_${index}_icon_type", "emoji")
                        .putString("btn_${index}_icon_data", ico.emoji)
                }
                edit.apply()
                if (newConfig.icon is UrlIcon.None || newConfig.icon is UrlIcon.Emoji) {
                    iconFile().delete()
                }
            }

            is ButtonConfig.Empty -> clearConfig(prefs)
        }
        applyConfig()
    }

    fun clearConfig(prefs: SharedPreferences) {
        config = ButtonConfig.Empty
        iconFile().delete()
        prefs.edit()
            .remove("btn_${index}_type")
            .remove("btn_${index}_value")
            .remove("btn_${index}_label")
            .remove("btn_${index}_widget_id")
            .removeIconKeys()
            .apply()
        applyConfig()
    }

    private fun SharedPreferences.Editor.removeIconKeys() = this
        .remove("btn_${index}_icon_type")
        .remove("btn_${index}_icon_data")

    private fun applyConfig() {
        icon = null   // always suppress MaterialButton's built-in icon slot
        when (val cfg = config) {
            is ButtonConfig.Empty -> {
                text = context.getString(R.string.empty)
                buttonIcon = null
            }
            is ButtonConfig.AppLauncher -> {
                text = cfg.label
                buttonIcon = try { context.packageManager.getApplicationIcon(cfg.packageName) }
                             catch (_: Exception) { null }
            }
            is ButtonConfig.UrlLauncher -> {
                text = cfg.label.ifEmpty { cfg.url }
                buttonIcon = when (val ico = cfg.icon) {
                    is UrlIcon.None       -> null
                    is UrlIcon.CustomFile -> loadIconFile()
                    is UrlIcon.Emoji      -> renderEmojiIcon(ico.emoji)
                }
            }
            is ButtonConfig.WidgetLauncher -> {
                text = cfg.label.ifEmpty { cfg.provider.packageName }
                buttonIcon = when (val ico = cfg.icon) {
                    is UrlIcon.None       -> null
                    is UrlIcon.CustomFile -> loadIconFile()
                    is UrlIcon.Emoji      -> renderEmojiIcon(ico.emoji)
                }
            }
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────

    fun activate() {
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
                onUrlActivated?.invoke(url)
            }
            is ButtonConfig.WidgetLauncher -> {
                flashActivation()
                if (cfg.appWidgetId != -1) onWidgetActivated?.invoke(cfg.appWidgetId)
            }
        }
    }

    // ── Icon helpers ──────────────────────────────────────────────────────────

    private fun iconFile() = File(context.filesDir, "btn_${index}_icon.png")

    private fun loadIconFile(): Drawable? {
        val file = iconFile()
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        return BitmapDrawable(resources, bmp)
    }

    private fun renderEmojiIcon(emoji: String): Drawable {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background: surface_card colour
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = context.getColor(R.color.surface_card)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Emoji centred at 65 % of the tile size
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize  = size * 0.65f
        }
        val fm = textPaint.fontMetrics
        val x  = size / 2f
        val y  = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(emoji, x, y, textPaint)

        return BitmapDrawable(resources, bmp)
    }

    // ── Visuals ───────────────────────────────────────────────────────────────

    private fun flashActivation() {
        backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.colorPrimary))
        handler.postDelayed({
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))
        }, 150L)
    }

    private fun makeFocusRing(): GradientDrawable = GradientDrawable().apply {
        shape        = GradientDrawable.RECTANGLE
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
