package de.codevoid.andronavibar.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
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

    /** Fired when a Bookmark Collection button is activated; MainActivity shows the bookmarks pane. */
    var onBookmarkCollectionActivated: ((buttonIndex: Int) -> Unit)? = null

    /** Fired when a Nav Target Collection button is activated; MainActivity shows the nav targets pane. */
    var onNavTargetCollectionActivated: ((buttonIndex: Int) -> Unit)? = null

    // ── Edit mode ─────────────────────────────────────────────────────────────

    /** Set by MainActivity when entering/exiting edit mode. */
    var isEditMode: Boolean = false
        set(value) { field = value; invalidate() }

    /** Called instead of activate() when isEditMode is true and the center is tapped. */
    var onEditTapped: (() -> Unit)? = null

    /** Called when the delete zone (right edge) is tapped in edit mode. */
    var onDeleteTapped: (() -> Unit)? = null

    // ── Edit overlay drawing ──────────────────────────────────────────────────

    private val overlayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 26, 26, 26)
    }
    private val dragLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.dpToPx(2).toFloat()
    }
    private val deleteZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 180, 40, 40)
    }
    private val deleteCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.dpToPx(3).toFloat()
    }

    override fun onDrawContent(canvas: Canvas) {
        super.onDrawContent(canvas)
        if (isEditMode) drawEditOverlay(canvas)
    }

    private fun drawEditOverlay(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val zoneW = h  // each zone is a square matching the button height

        canvas.save()
        canvas.clipPath(drawPath)

        // ── Drag handle zone (left, over the icon slot) ───────────────────
        canvas.drawRect(barW, 0f, barW + zoneW, h, overlayBgPaint)
        val lineW = zoneW * 0.45f
        val lineX0 = barW + (zoneW - lineW) / 2f
        val lineX1 = lineX0 + lineW
        for (i in 0..2) {
            val y = h * (i + 1) / 4f
            canvas.drawLine(lineX0, y, lineX1, y, dragLinePaint)
        }

        // ── Delete zone (right edge) ──────────────────────────────────────
        canvas.drawRect(w - zoneW, 0f, w, h, deleteZonePaint)
        val pad = zoneW * 0.28f
        val dx0 = w - zoneW + pad;  val dx1 = w - pad
        val dy0 = h * 0.28f;        val dy1 = h * 0.72f
        canvas.drawLine(dx0, dy0, dx1, dy1, deleteCrossPaint)
        canvas.drawLine(dx1, dy0, dx0, dy1, deleteCrossPaint)

        canvas.restore()
    }

    // ── Touch zone handling (edit mode) ───────────────────────────────────────

    private enum class TouchZone { NONE, DRAG, DELETE }
    private var touchZone = TouchZone.NONE

    /** Long-press on the drag handle initiates system drag-and-drop. */
    private val dragGestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val clip = ClipData.newPlainText("btn_drag", index.toString())
                startDragAndDrop(clip, DragShadowBuilder(this@LauncherButton), index, 0)
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditMode) return super.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchZone = when {
                event.x < barW + height -> TouchZone.DRAG
                event.x >= width - height -> TouchZone.DELETE
                else -> TouchZone.NONE
            }
        }

        return when (touchZone) {
            TouchZone.DRAG -> {
                dragGestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchZone = TouchZone.NONE
                }
                true
            }
            TouchZone.DELETE -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    touchZone = TouchZone.NONE
                    onDeleteTapped?.invoke()
                } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchZone = TouchZone.NONE
                }
                true
            }
            TouchZone.NONE -> {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchZone = TouchZone.NONE
                }
                super.onTouchEvent(event)
            }
        }
    }

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
                type == "bookmark" -> {
                    ButtonConfig.BookmarkCollection(
                        row.label ?: "",
                        UrlIcon.fromRow(row.iconType, row.iconData)
                    )
                }
                type == "navtarget" -> {
                    ButtonConfig.NavTargetCollection(
                        row.label ?: "",
                        UrlIcon.fromRow(row.iconType, row.iconData),
                        value ?: ""
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
            is ButtonConfig.BookmarkCollection -> {
                val (it, id) = UrlIcon.toRow(newConfig.icon)
                ButtonRow("bookmark", null, newConfig.label, iconType = it, iconData = id)
                    .also { cleanStaleIconFile(newConfig.icon) }
            }
            is ButtonConfig.NavTargetCollection -> {
                val (it, id) = UrlIcon.toRow(newConfig.icon)
                ButtonRow("navtarget", newConfig.appPackage, newConfig.label, iconType = it, iconData = id)
                    .also { cleanStaleIconFile(newConfig.icon) }
            }
            is ButtonConfig.Empty -> {
                clearConfig(db)
                return
            }
        }
        db.saveButton(index, row)
        applyConfig()
    }

    fun clearConfig(db: LauncherDatabase) {
        config = ButtonConfig.Empty
        buttonIconFile(context.filesDir, index).delete()
        db.clearButton(index)
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
            is ButtonConfig.BookmarkCollection -> {
                text = cfg.label.ifEmpty { context.getString(R.string.type_bookmark) }
                buttonIcon = resolveIcon(cfg.icon)
            }
            is ButtonConfig.NavTargetCollection -> {
                text = cfg.label.ifEmpty { context.getString(R.string.type_navtarget) }
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
            is ButtonConfig.BookmarkCollection -> {
                onBookmarkCollectionActivated?.invoke(index)
            }
            is ButtonConfig.NavTargetCollection -> {
                onNavTargetCollectionActivated?.invoke(index)
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
