package de.codevoid.andronavibar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MusicPlayerPaneContent(
    private val context: Context,
    private val playerPackage: String
) : PaneContent {

    private var rootView: LinearLayout? = null

    // Upper section views
    private var artView: ImageView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var albumView: TextView? = null
    private var placeholderView: TextView? = null

    // Control buttons: [prev, play/pause, next, shuffle]
    private var controlViews: List<FrameLayout> = emptyList()
    private var controlIcons: List<ImageView> = emptyList()
    private var focusIndex: Int = 1  // default: play/pause

    // Media session
    private var sessionManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var hasNotificationAccess = false
    private var isShuffleOn = false

    var onContentReady: (() -> Unit)? = null

    // ── PaneContent ─────────────────────────────────────────────────────────

    override fun load(onReady: () -> Unit) { onReady() }

    override fun show(container: ViewGroup) {
        val root = buildLayout()
        rootView = root
        container.addView(root)
        connectToMediaSession()
        root.post {
            onContentReady?.invoke()
            onContentReady = null
        }
    }

    override fun unload() {
        disconnectFromMediaSession()
        (rootView?.parent as? ViewGroup)?.removeView(rootView)
        rootView = null
        artView = null
        titleView = null
        artistView = null
        albumView = null
        placeholderView = null
        controlViews = emptyList()
        controlIcons = emptyList()
    }

    // ── Key handling ────────────────────────────────────────────────────────

    fun handleKey(keyCode: Int): Boolean {
        return when (keyCode) {
            21 -> { moveFocus(focusIndex - 1); true }  // LEFT
            22 -> { moveFocus(focusIndex + 1); true }  // RIGHT
            66 -> { activateControl(focusIndex); true } // ACTIVATE
            else -> false
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(context.getColor(R.color.surface_dark))
        }

        // Upper 2/3: album art + song info
        val upper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 2f)
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dpToPx(24)
            setPadding(p, p, p, dpToPx(8))
        }

        artView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        upper.addView(artView)

        placeholderView = TextView(context).apply {
            text = context.getString(R.string.no_music_playing)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        upper.addView(placeholderView)

        titleView = TextView(context).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpToPx(12)
            }
            setTextColor(context.getColor(R.color.text_primary))
        }
        upper.addView(titleView)

        artistView = TextView(context).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpToPx(4)
            }
            setTextColor(context.getColor(R.color.text_primary))
        }
        upper.addView(artistView)

        albumView = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpToPx(2)
            }
            setTextColor(context.getColor(R.color.text_secondary))
        }
        upper.addView(albumView)

        root.addView(upper)

        // Lower 1/3: transport controls
        val lower = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            gravity = Gravity.CENTER
        }

        val controlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            gravity = Gravity.CENTER
        }

        val btnSize = dpToPx(72)
        val iconSize = dpToPx(36)
        val spacing = dpToPx(20)
        val iconColor = context.getColor(R.color.text_primary)

        val icons = listOf(
            drawPrev(iconSize, iconColor),
            drawPlay(iconSize, iconColor),
            drawNext(iconSize, iconColor),
            drawShuffle(iconSize, iconColor)
        )

        val builtViews = mutableListOf<FrameLayout>()
        val builtIcons = mutableListOf<ImageView>()

        for (i in icons.indices) {
            val frame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    if (i > 0) marginStart = spacing
                }
                background = makeButtonBg()
                if (i == focusIndex) foreground = makeFocusRing(btnSize)
                val idx = i
                setOnClickListener { activateControl(idx) }
            }

            val iv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                setImageBitmap(icons[i])
            }
            frame.addView(iv)
            controlRow.addView(frame)
            builtViews.add(frame)
            builtIcons.add(iv)
        }

        controlViews = builtViews
        controlIcons = builtIcons
        lower.addView(controlRow)
        root.addView(lower)

        // Start with placeholder visible, art/info hidden
        showPlaceholder()

        return root
    }

    // ── Focus management ────────────────────────────────────────────────────

    private fun moveFocus(newIndex: Int) {
        val clamped = newIndex.coerceIn(0, controlViews.lastIndex)
        if (clamped == focusIndex) return
        val btnSize = controlViews.getOrNull(0)?.width ?: dpToPx(72)
        controlViews.getOrNull(focusIndex)?.foreground = null
        focusIndex = clamped
        controlViews.getOrNull(focusIndex)?.foreground = makeFocusRing(btnSize)
    }

    fun setInitialFocus() {
        focusIndex = 1
        val btnSize = controlViews.getOrNull(0)?.width ?: dpToPx(72)
        for (i in controlViews.indices) {
            controlViews[i].foreground = if (i == focusIndex) makeFocusRing(btnSize) else null
        }
    }

    // ── Control actions ─────────────────────────────────────────────────────

    private fun activateControl(index: Int) {
        if (!hasNotificationAccess) {
            openNotificationSettings()
            return
        }
        when (index) {
            0 -> mediaController?.transportControls?.skipToPrevious() ?: launchPlayerApp()
            1 -> {
                val state = mediaController?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    mediaController?.transportControls?.pause()
                } else if (mediaController != null) {
                    mediaController?.transportControls?.play()
                } else {
                    launchPlayerApp()
                }
            }
            2 -> mediaController?.transportControls?.skipToNext() ?: launchPlayerApp()
            3 -> {
                val mc = mediaController ?: return
                isShuffleOn = !isShuffleOn
                mc.transportControls.sendCustomAction(
                    COMPAT_ACTION_SET_SHUFFLE,
                    android.os.Bundle().apply {
                        putInt(COMPAT_EXTRA_SHUFFLE, if (isShuffleOn) 1 else 0)
                    }
                )
                updateShuffleVisual()
            }
        }
    }

    private fun launchPlayerApp() {
        if (playerPackage.isEmpty()) return
        val intent = context.packageManager.getLaunchIntentForPackage(playerPackage) ?: return
        context.startActivity(intent)
    }

    private fun openNotificationSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── Media session ───────────────────────────────────────────────────────

    private val listenerComponent by lazy {
        ComponentName(context, MediaNotificationListener::class.java)
    }

    private fun connectToMediaSession() {
        val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = mgr

        try {
            val sessions = mgr.getActiveSessions(listenerComponent)
            hasNotificationAccess = true
            if (sessions.isNotEmpty()) attachToController(sessions[0])
        } catch (_: SecurityException) {
            hasNotificationAccess = false
            showPermissionMessage()
            return
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (controllers != null && controllers.isNotEmpty()) {
                attachToController(controllers[0])
            } else {
                detachController()
            }
        }
        sessionListener = listener
        mgr.addOnActiveSessionsChangedListener(listener, listenerComponent)
    }

    private fun disconnectFromMediaSession() {
        sessionListener?.let { sessionManager?.removeOnActiveSessionsChangedListener(it) }
        sessionListener = null
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
        sessionManager = null
    }

    private fun attachToController(controller: MediaController) {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = controller
        controller.registerCallback(mediaCallback)
        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)
        isShuffleOn = controller.extras?.getInt(COMPAT_EXTRA_SHUFFLE, 0) == 1
        updateShuffleVisual()
    }

    private fun detachController() {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
        showPlaceholder()
        updatePlayPauseIcon(false)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
        override fun onSessionDestroyed() {
            detachController()
        }
    }

    // ── UI updates ──────────────────────────────────────────────────────────

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata == null) { showPlaceholder(); return }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (title.isNullOrEmpty() && artist.isNullOrEmpty()) {
            showPlaceholder()
            return
        }

        placeholderView?.visibility = View.GONE
        artView?.visibility = if (art != null) View.VISIBLE else View.GONE
        art?.let { artView?.setImageBitmap(it) }

        titleView?.text = title ?: ""
        titleView?.visibility = if (!title.isNullOrEmpty()) View.VISIBLE else View.GONE
        artistView?.text = artist ?: ""
        artistView?.visibility = if (!artist.isNullOrEmpty()) View.VISIBLE else View.GONE
        albumView?.text = album ?: ""
        albumView?.visibility = if (!album.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        updatePlayPauseIcon(isPlaying)
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val iconSize = dpToPx(36)
        val color = context.getColor(R.color.text_primary)
        val bmp = if (isPlaying) drawPause(iconSize, color) else drawPlay(iconSize, color)
        controlIcons.getOrNull(1)?.setImageBitmap(bmp)
    }

    private fun updateShuffleVisual() {
        val iconSize = dpToPx(36)
        val color = if (isShuffleOn)
            context.getColor(R.color.colorPrimary) else context.getColor(R.color.text_primary)
        controlIcons.getOrNull(3)?.setImageBitmap(drawShuffle(iconSize, color))
    }

    private fun showPlaceholder() {
        placeholderView?.visibility = View.VISIBLE
        artView?.visibility = View.GONE
        titleView?.visibility = View.GONE
        artistView?.visibility = View.GONE
        albumView?.visibility = View.GONE
    }

    private fun showPermissionMessage() {
        placeholderView?.text = context.getString(R.string.notification_access_required)
        showPlaceholder()
    }

    // ── Icon drawing ────────────────────────────────────────────────────────

    private fun drawPlay(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = size * 0.2f
        val path = Path().apply {
            moveTo(m, m)
            lineTo(size - m, size / 2f)
            lineTo(m, size - m)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun drawPause(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = size * 0.2f
        val barW = size * 0.17f
        val gap = size * 0.08f
        val cx = size / 2f
        canvas.drawRoundRect(cx - gap - barW, m, cx - gap, size - m, barW * 0.3f, barW * 0.3f, paint)
        canvas.drawRoundRect(cx + gap, m, cx + gap + barW, size - m, barW * 0.3f, barW * 0.3f, paint)
        return bmp
    }

    private fun drawPrev(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = size * 0.2f
        val barW = size * 0.1f
        canvas.drawRoundRect(m, m, m + barW, size - m, barW * 0.3f, barW * 0.3f, paint)
        val path = Path().apply {
            moveTo(size - m, m)
            lineTo(m + barW + size * 0.05f, size / 2f)
            lineTo(size - m, size - m)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun drawNext(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = size * 0.2f
        val barW = size * 0.1f
        canvas.drawRoundRect(size - m - barW, m, size - m, size - m, barW * 0.3f, barW * 0.3f, paint)
        val path = Path().apply {
            moveTo(m, m)
            lineTo(size - m - barW - size * 0.05f, size / 2f)
            lineTo(m, size - m)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun drawShuffle(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = size * 0.08f; strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val m = size * 0.22f
        val r = size - m
        val arrSz = size * 0.13f
        // Two crossing lines
        canvas.drawLine(m, m, r, r, stroke)
        canvas.drawLine(m, r, r, m, stroke)
        // Arrowheads on right ends
        canvas.drawPath(Path().apply {
            moveTo(r, m); lineTo(r - arrSz * 1.4f, m); lineTo(r, m + arrSz * 1.4f); close()
        }, fill)
        canvas.drawPath(Path().apply {
            moveTo(r, r); lineTo(r - arrSz * 1.4f, r); lineTo(r, r - arrSz * 1.4f); close()
        }, fill)
        return bmp
    }

    // ── Visual helpers ──────────────────────────────────────────────────────

    private fun makeButtonBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(16).toFloat()
        setColor(context.getColor(R.color.button_inactive))
    }

    private fun makeFocusRing(size: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpToPx(16).toFloat()
        setStroke(dpToPx(3), context.getColor(R.color.colorPrimary))
        setColor(Color.TRANSPARENT)
    }

    private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        // AndroidX MediaSessionCompat action/extra keys — used via sendCustomAction
        // because the framework MediaController has no shuffle mode API.
        private const val COMPAT_ACTION_SET_SHUFFLE =
            "android.support.v4.media.session.action.SET_SHUFFLE_MODE"
        private const val COMPAT_EXTRA_SHUFFLE =
            "android.support.v4.media.session.extra.SHUFFLE_MODE"
    }
}
