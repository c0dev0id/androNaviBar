package de.codevoid.andronavibar.ui

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import de.codevoid.andronavibar.AppEntry
import de.codevoid.andronavibar.ButtonConfig
import de.codevoid.andronavibar.R
import de.codevoid.andronavibar.UrlIcon
import de.codevoid.andronavibar.buttonIconFile
import de.codevoid.andronavibar.dpToPx
import de.codevoid.andronavibar.renderEmojiDrawable
import java.io.File

// ── LauncherButton ────────────────────────────────────────────────────────────

class LauncherButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : FocusableButton(context, attrs, defStyleAttr) {

    // Set by MainActivity during setup; used as the SharedPreferences key suffix.
    var index: Int = 0

    var config: ButtonConfig = ButtonConfig.Empty
        private set

    // ── Callbacks wired by MainActivity ──────────────────────────────────────

    /** Fired when an App button is activated; MainActivity shows the app launcher pane. */
    var onAppLauncherActivated: ((packageName: String, label: String) -> Unit)? = null

    /** Fired when a URL button (WebView mode) is activated; MainActivity shows the WebView pane. */
    var onUrlActivated: ((String) -> Unit)? = null

    /** Fired when a URL button (browser mode) is activated; MainActivity shows the launcher pane. */
    var onUrlLauncherActivated: ((url: String, label: String, icon: UrlIcon) -> Unit)? = null

    /** Fired when a Widget button is activated; MainActivity shows the widget pane. */
    var onWidgetActivated: ((Int) -> Unit)? = null

    /** Fired when an Apps Grid button is activated; MainActivity shows the apps grid pane. */
    var onAppsGridActivated: ((List<AppEntry>) -> Unit)? = null

    /** Fired when a Music Player button is activated; MainActivity shows the music pane. */
    var onMusicPlayerActivated: ((String) -> Unit)? = null

    // ── Visual state ─────────────────────────────────────────────────────────

    /** Suppress the white focus ring from FocusableButton — the left bar is the focus indicator. */
    override var isFocusedButton: Boolean = false
        set(value) {
            field = value
            foreground = null   // never draw the ring on launcher buttons
            invalidate()
        }

    /** Persistent highlight showing this button's content pane is displayed. */
    var isActiveButton: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // ── Full-height icon ──────────────────────────────────────────────────────

    /**
     * Icon drawn as a full-height square on the left edge of the button.
     * Setting this automatically adjusts paddingStart and triggers a redraw.
     */
    var buttonIcon: Drawable? = null
        set(value) {
            field = value
            updateIconPadding()
            invalidate()
        }

    // Pre-allocated draw objects — never allocate inside onDraw.
    private val cornerRad  = resources.dpToPx(FocusableButton.CORNER_RADIUS_DP).toFloat()
    private val barW       = resources.dpToPx(BAR_WIDTH_DP).toFloat()
    private val drawPath   = Path()
    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.button_active_body)
    }
    private val barPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.colorPrimary)
    }
    // Depth gradient: subtle top-highlight + bottom-shadow for a tactile, non-flat look.
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun updateIconPadding() {
        val iconWidth = if (buttonIcon != null && height > 0) height else 0
        setPaddingRelative(barW.toInt() + iconWidth, paddingTop, paddingEnd, paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawPath.rewind()
        drawPath.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerRad, cornerRad, Path.Direction.CW)
        depthPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(28, 255, 255, 255),  // 11% white highlight at top
                Color.TRANSPARENT,               // clear at 45%
                Color.argb(20, 0, 0, 0)         // 8% black shadow at bottom
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        updateIconPadding()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Depth gradient — always drawn, gives buttons a tactile top-lit look.
        canvas.save()
        canvas.clipPath(drawPath)
        canvas.drawRect(0f, 0f, w, h, depthPaint)
        canvas.restore()

        // Active body fill — drawn before super so it sits behind text/icon.
        if (isActiveButton) {
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(barW, 0f, w, h, fillPaint)
            canvas.restore()
        }

        buttonIcon?.let { drawable ->
            // Icon sits to the right of the bar with a small gap.
            val vInset = resources.dpToPx(FocusableButton.STROKE_WIDTH_DP) * 2
            val hInset = barW.toInt() + resources.dpToPx(4)
            val iconSize = height - vInset * 2
            canvas.save()
            canvas.clipPath(drawPath)
            drawable.setBounds(hInset, vInset, hInset + iconSize, vInset + iconSize)
            drawable.draw(canvas)
            canvas.restore()
        }

        super.onDraw(canvas)

        // Bar: white when focused (cursor), orange when active-only.
        if (isFocusedButton || isActiveButton) {
            barPaint.color = context.getColor(
                if (isFocusedButton) R.color.focus_ring else R.color.colorPrimary
            )
            canvas.save()
            canvas.clipPath(drawPath)
            canvas.drawRect(0f, 0f, barW, h, barPaint)
            canvas.restore()
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    init {
        setOnClickListener { activate() }
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
                    val widgetId = prefs.getString("btn_${index}_widget_id", null)?.toIntOrNull() ?: -1
                    val label    = prefs.getString("btn_${index}_label", "") ?: ""
                    ButtonConfig.WidgetLauncher(cn, widgetId, label, UrlIcon.fromPrefs(prefs, index))
                }
            }
            type == "url" && value != null -> {
                val labelRaw = prefs.getString("btn_${index}_label", "") ?: ""
                // Migrate: old code stored url as label — treat label==url as empty.
                val label = if (labelRaw == value) "" else labelRaw
                val openInBrowser = prefs.getString("btn_${index}_open_browser", null) == "true"
                ButtonConfig.UrlLauncher(value, label, UrlIcon.fromPrefs(prefs, index), openInBrowser)
            }
            type == "apps" -> {
                val label   = prefs.getString("btn_${index}_label", "") ?: ""
                val appsRaw = prefs.getString("btn_${index}_apps", "") ?: ""
                val entries = appsRaw.split("|").filter { it.isNotEmpty() }.mapNotNull { pkg ->
                    val appLabel = try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Exception) { return@mapNotNull null }
                    AppEntry(pkg, appLabel)
                }
                ButtonConfig.AppsGrid(entries, label, UrlIcon.fromPrefs(prefs, index))
            }
            type == "music" -> {
                val label     = prefs.getString("btn_${index}_label", "") ?: ""
                val playerPkg = prefs.getString("btn_${index}_value", "") ?: ""
                ButtonConfig.MusicPlayer(playerPkg, label, UrlIcon.fromPrefs(prefs, index))
            }
            else -> ButtonConfig.Empty
        }
        applyConfig()
    }

    fun saveConfig(prefs: SharedPreferences, newConfig: ButtonConfig) {
        config = newConfig
        when (newConfig) {
            is ButtonConfig.AppLauncher -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",  "app")
                    .putString("btn_${index}_value", newConfig.packageName)
                    .putString("btn_${index}_label", newConfig.label)
                UrlIcon.writeTo(edit, index, UrlIcon.None)
                edit.apply()
            }

            is ButtonConfig.UrlLauncher -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",  "url")
                    .putString("btn_${index}_value", newConfig.url)
                    .putString("btn_${index}_label", newConfig.label)
                if (newConfig.openInBrowser) edit.putString("btn_${index}_open_browser", "true")
                else edit.remove("btn_${index}_open_browser")
                UrlIcon.writeTo(edit, index, newConfig.icon)
                edit.apply()
                cleanStaleIconFile(newConfig.icon)
            }

            is ButtonConfig.WidgetLauncher -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",      "widget")
                    .putString("btn_${index}_value",     newConfig.provider.flattenToString())
                    .putString("btn_${index}_label",     newConfig.label)
                    .putString("btn_${index}_widget_id", newConfig.appWidgetId.toString())
                UrlIcon.writeTo(edit, index, newConfig.icon)
                edit.apply()
                cleanStaleIconFile(newConfig.icon)
            }

            is ButtonConfig.AppsGrid -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",  "apps")
                    .putString("btn_${index}_value", "")
                    .putString("btn_${index}_label", newConfig.label)
                    .putString("btn_${index}_apps",  newConfig.apps.joinToString("|") { it.packageName })
                UrlIcon.writeTo(edit, index, newConfig.icon)
                edit.apply()
                cleanStaleIconFile(newConfig.icon)
            }

            is ButtonConfig.MusicPlayer -> {
                val edit = prefs.edit()
                    .putString("btn_${index}_type",  "music")
                    .putString("btn_${index}_value", newConfig.playerPackage)
                    .putString("btn_${index}_label", newConfig.label)
                UrlIcon.writeTo(edit, index, newConfig.icon)
                edit.apply()
                cleanStaleIconFile(newConfig.icon)
            }

            is ButtonConfig.Empty -> clearConfig(prefs)
        }
        applyConfig()
    }

    fun clearConfig(prefs: SharedPreferences) {
        config = ButtonConfig.Empty
        buttonIconFile(context.filesDir, index).delete()
        val edit = prefs.edit()
            .remove("btn_${index}_type")
            .remove("btn_${index}_value")
            .remove("btn_${index}_label")
            .remove("btn_${index}_widget_id")
            .remove("btn_${index}_open_browser")
            .remove("btn_${index}_apps")
        UrlIcon.writeTo(edit, index, UrlIcon.None)
        edit.apply()
        applyConfig()
    }

    /** Delete stale icon file when not using a file-based icon. */
    private fun cleanStaleIconFile(icon: UrlIcon) {
        if (icon !is UrlIcon.CustomFile) buttonIconFile(context.filesDir, index).delete()
    }

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
                buttonIcon = resolveIcon(cfg.icon)
            }
            is ButtonConfig.WidgetLauncher -> {
                text = cfg.label.ifEmpty { cfg.provider.packageName }
                buttonIcon = resolveIcon(cfg.icon)
            }
            is ButtonConfig.AppsGrid -> {
                text = cfg.label.ifEmpty { context.getString(R.string.tab_apps) }
                buttonIcon = resolveIcon(cfg.icon)
            }
            is ButtonConfig.MusicPlayer -> {
                text = cfg.label.ifEmpty { context.getString(R.string.tab_music) }
                buttonIcon = resolveIcon(cfg.icon)
            }
        }
    }

    private fun resolveIcon(icon: UrlIcon): Drawable? = when (icon) {
        is UrlIcon.None       -> null
        is UrlIcon.CustomFile -> loadIconFile()
        is UrlIcon.Emoji      -> context.renderEmojiDrawable(icon.emoji)
    }

    // ── Activation ────────────────────────────────────────────────────────────

    fun activate() {
        when (val cfg = config) {
            is ButtonConfig.Empty -> Unit
            is ButtonConfig.AppLauncher -> {
                onAppLauncherActivated?.invoke(cfg.packageName, cfg.label)
            }
            is ButtonConfig.UrlLauncher -> {
                val url = if (cfg.url.startsWith("http://") || cfg.url.startsWith("https://"))
                    cfg.url else "https://${cfg.url}"
                if (cfg.openInBrowser) {
                    onUrlLauncherActivated?.invoke(url, cfg.label, cfg.icon)
                } else {
                    onUrlActivated?.invoke(url)
                }
            }
            is ButtonConfig.WidgetLauncher -> {
                if (cfg.appWidgetId != -1) onWidgetActivated?.invoke(cfg.appWidgetId)
            }
            is ButtonConfig.AppsGrid -> {
                onAppsGridActivated?.invoke(cfg.apps)
            }
            is ButtonConfig.MusicPlayer -> {
                onMusicPlayerActivated?.invoke(cfg.playerPackage)
            }
        }
    }

    // ── Icon helpers ──────────────────────────────────────────────────────────

    private fun loadIconFile(): Drawable? {
        val file = buttonIconFile(context.filesDir, index)
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        return BitmapDrawable(resources, bmp)
    }

    companion object {
        /** Width of the left accent/focus bar in dp. Large enough to be visible while riding. */
        const val BAR_WIDTH_DP = 12
    }

}
