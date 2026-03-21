package de.codevoid.andronavibar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL as JavaURL

// ── Icon type for URL buttons ─────────────────────────────────────────────────

sealed class UrlIcon {
    object None : UrlIcon()
    data class Emoji(val emoji: String) : UrlIcon()
    /** Fetched async from the page's domain; stored at the button's icon file. */
    object Favicon : UrlIcon()
    /** User-provided image; stored at the button's icon file. */
    object CustomFile : UrlIcon()
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

    /** Fired on long-press; MainActivity opens the config pane for this button. */
    var onConfigRequested: (() -> Unit)? = null

    // ── Visual state ─────────────────────────────────────────────────────────

    var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = if (value) makeFocusRing() else null
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
            type == "url" && value != null -> {
                val labelRaw = prefs.getString("btn_${index}_label", "") ?: ""
                // Migrate: old code stored url as label — treat label==url as empty.
                val label = if (labelRaw == value) "" else labelRaw
                val iconType = prefs.getString("btn_${index}_icon_type", null)
                val iconData = prefs.getString("btn_${index}_icon_data", null)
                val icon = when (iconType) {
                    "emoji"   -> if (iconData != null) UrlIcon.Emoji(iconData) else UrlIcon.None
                    "favicon" -> UrlIcon.Favicon
                    "custom"  -> UrlIcon.CustomFile
                    else      -> UrlIcon.None
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
                when (val ic = newConfig.icon) {
                    is UrlIcon.None -> edit.removeIconKeys()
                    is UrlIcon.Emoji -> edit
                        .putString("btn_${index}_icon_type", "emoji")
                        .putString("btn_${index}_icon_data", ic.emoji)
                    is UrlIcon.Favicon -> edit
                        .putString("btn_${index}_icon_type", "favicon")
                        .remove("btn_${index}_icon_data")
                    is UrlIcon.CustomFile -> edit
                        .putString("btn_${index}_icon_type", "custom")
                        .remove("btn_${index}_icon_data")
                }
                edit.apply()

                // Delete stale icon file when switching away from file-based icon.
                if (newConfig.icon == UrlIcon.None || newConfig.icon is UrlIcon.Emoji) {
                    iconFile().delete()
                }
                // Trigger async favicon fetch.
                if (newConfig.icon == UrlIcon.Favicon) fetchFavicon(newConfig.url)
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
            .removeIconKeys()
            .apply()
        applyConfig()
    }

    private fun SharedPreferences.Editor.removeIconKeys() = this
        .remove("btn_${index}_icon_type")
        .remove("btn_${index}_icon_data")

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
                text = cfg.label.ifEmpty { cfg.url }
                icon = when (val ic = cfg.icon) {
                    is UrlIcon.None       -> null
                    is UrlIcon.Emoji      -> emojiToDrawable(ic.emoji)
                    is UrlIcon.Favicon,
                    is UrlIcon.CustomFile -> loadIconFile()
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
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            // TODO: toggle/pane types — call load() here; show() on onReady
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

    private fun emojiToDrawable(emoji: String): Drawable {
        val size = dpToPx(32)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize  = size * 0.75f
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        paint.getTextBounds(emoji, 0, emoji.length, bounds)
        Canvas(bmp).drawText(emoji, size / 2f, size / 2f - bounds.exactCenterY(), paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun fetchFavicon(pageUrl: String) {
        scope.launch {
            val domain = Uri.parse(pageUrl).host ?: return@launch
            val dest   = iconFile()
            val fetched = withContext(Dispatchers.IO) {
                try {
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=64"
                    val conn = JavaURL(faviconUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5_000
                    conn.readTimeout    = 5_000
                    conn.connect()
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
                        true
                    } else false
                } catch (_: Exception) { false }
            }
            if (fetched) applyConfig()
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
