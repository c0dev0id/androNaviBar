package de.codevoid.andronavibar.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import de.codevoid.andronavibar.ButtonConfig
import de.codevoid.andronavibar.ButtonRow
import de.codevoid.andronavibar.LauncherDatabase
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

    // Set by MainActivity during setup; used as the database row position.
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

    /** Fired when a Music Player button is activated; MainActivity shows the music pane. */
    var onMusicPlayerActivated: ((String) -> Unit)? = null

    // ── Edit mode ─────────────────────────────────────────────────────────────

    /** Set by MainActivity when entering/exiting edit mode. */
    var isEditMode: Boolean = false

    /** Called instead of activate() when isEditMode is true. Wired by MainActivity. */
    var onEditTapped: (() -> Unit)? = null

    // ── Internal ─────────────────────────────────────────────────────────────

    init {
        setOnClickListener {
            if (isEditMode) onEditTapped?.invoke()
            else activate()
        }
    }

    // ── Config persistence ────────────────────────────────────────────────────

    fun loadConfig(db: LauncherDatabase) {
        val row = db.loadButton(index)
        config = if (row == null) {
            ButtonConfig.Empty
        } else {
            val type = row.type
            val value = row.value
            when {
                type == "app" && value != null -> {
                    val label = row.label
                    if (label != null) ButtonConfig.AppLauncher(value, label) else ButtonConfig.Empty
                }
                type == "widget" && value != null -> {
                    val cn = ComponentName.unflattenFromString(value)
                    if (cn == null) {
                        ButtonConfig.Empty
                    } else {
                        ButtonConfig.WidgetLauncher(
                            cn, row.widgetId ?: -1,
                            row.label ?: "",
                            UrlIcon.fromRow(row.iconType, row.iconData)
                        )
                    }
                }
                type == "url" && value != null -> {
                    val labelRaw = row.label ?: ""
                    val label = if (labelRaw == value) "" else labelRaw
                    ButtonConfig.UrlLauncher(
                        value, label,
                        UrlIcon.fromRow(row.iconType, row.iconData),
                        row.openBrowser
                    )
                }
                type == "music" -> {
                    ButtonConfig.MusicPlayer(
                        value ?: "",
                        row.label ?: "",
                        UrlIcon.fromRow(row.iconType, row.iconData)
                    )
                }
                else -> ButtonConfig.Empty
            }
        }
        applyConfig()
    }

    fun saveConfig(db: LauncherDatabase, newConfig: ButtonConfig) {
        config = newConfig
        val row = when (newConfig) {
            is ButtonConfig.AppLauncher -> {
                val (it, id) = UrlIcon.toRow(UrlIcon.None)
                ButtonRow("app", newConfig.packageName, newConfig.label, iconType = it, iconData = id)
            }
            is ButtonConfig.UrlLauncher -> {
                val (it, id) = UrlIcon.toRow(newConfig.icon)
                ButtonRow("url", newConfig.url, newConfig.label,
                    openBrowser = newConfig.openInBrowser, iconType = it, iconData = id)
                    .also { cleanStaleIconFile(newConfig.icon) }
            }
            is ButtonConfig.WidgetLauncher -> {
                val (it, id) = UrlIcon.toRow(newConfig.icon)
                ButtonRow("widget", newConfig.provider.flattenToString(), newConfig.label,
                    widgetId = newConfig.appWidgetId, iconType = it, iconData = id)
                    .also { cleanStaleIconFile(newConfig.icon) }
            }
            is ButtonConfig.MusicPlayer -> {
                val (it, id) = UrlIcon.toRow(newConfig.icon)
                ButtonRow("music", newConfig.playerPackage, newConfig.label,
                    iconType = it, iconData = id)
                    .also { cleanStaleIconFile(newConfig.icon) }
            }
            is ButtonConfig.Empty -> {
                clearConfig(db)
                return
            }
        }
        // Preserve current active state
        val current = db.loadButton(index)
        db.saveButton(index, row.copy(active = current?.active ?: true))
        applyConfig()
    }

    fun clearConfig(db: LauncherDatabase) {
        config = ButtonConfig.Empty
        buttonIconFile(context.filesDir, index).delete()
        val current = db.loadButton(index)
        db.clearButton(index)
        if (current != null) db.setButtonActive(index, current.active)
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
}
