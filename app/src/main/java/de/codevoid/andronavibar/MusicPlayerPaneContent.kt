package de.codevoid.andronavibar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.media.MediaBrowserService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import de.codevoid.andronavibar.ui.SquareButton

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

    // Transport controls — SquareButtons with shared focus ring and two-tap model
    private var controlViews: List<SquareButton> = emptyList()
    private var focusIndex: Int = 1  // default: play/pause

    // Media browser — connects directly to the player's MediaBrowserService.
    // No notification listener permission needed.
    private var mediaBrowser: MediaBrowser? = null
    private var mediaController: MediaController? = null
    private var isShuffleOn = false

    // Pre-rendered transport icons — allocated once in buildLayout(), swapped by reference.
    private lateinit var playIcon: BitmapDrawable
    private lateinit var pauseIcon: BitmapDrawable
    private lateinit var shuffleOnIcon: BitmapDrawable
    private lateinit var shuffleOffIcon: BitmapDrawable

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
    }

    // ── Key handling ────────────────────────────────────────────────────────

    fun handleKey(keyCode: Int): Boolean {
        return when (keyCode) {
            21 -> { moveFocus(focusIndex - 1); true }                                 // LEFT
            22 -> {                                                                    // RIGHT
                if (focusIndex < controlViews.lastIndex) { moveFocus(focusIndex + 1); true }
                else false                                                             // edge → parent
            }
            66 -> { activateControl(focusIndex); true }                                // ACTIVATE
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
            val p = context.resources.dpToPx(24)
            setPadding(p, p, p, context.resources.dpToPx(8))
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
            textSize = 28f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(12)
            }
            setTextColor(context.getColor(R.color.text_primary))
        }
        upper.addView(titleView)

        artistView = TextView(context).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(4)
            }
            setTextColor(context.getColor(R.color.text_primary))
        }
        upper.addView(artistView)

        albumView = TextView(context).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = context.resources.dpToPx(2)
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

        val btnSize = context.resources.dpToPx(108)
        val iconSz  = context.resources.dpToPx(54)
        val spacing = context.resources.dpToPx(30)
        val iconColor = context.getColor(R.color.text_primary)
        val accentColor = context.getColor(R.color.colorPrimary)

        // Pre-render all icon variants once
        playIcon = BitmapDrawable(context.resources, drawPlay(iconSz, iconColor))
        pauseIcon = BitmapDrawable(context.resources, drawPause(iconSz, iconColor))
        shuffleOffIcon = BitmapDrawable(context.resources, drawShuffle(iconSz, iconColor))
        shuffleOnIcon = BitmapDrawable(context.resources, drawShuffle(iconSz, accentColor))

        val icons = listOf(
            BitmapDrawable(context.resources, drawPrev(iconSz, iconColor)),
            playIcon,
            BitmapDrawable(context.resources, drawNext(iconSz, iconColor)),
            shuffleOffIcon,
            BitmapDrawable(context.resources, drawLaunch(iconSz, iconColor))
        )

        val builtViews = mutableListOf<SquareButton>()

        for (i in icons.indices) {
            val idx = i
            val btn = SquareButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    if (i > 0) marginStart = spacing
                }

                icon = icons[i]
                this.iconSize = iconSz
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                setPadding(0, 0, 0, 0)

                backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_inactive))

                isFocusedButton = false
                onFocusRequested = { moveFocus(idx) }
                setOnClickListener { activateControl(idx) }
            }
            controlRow.addView(btn)
            builtViews.add(btn)
        }

        controlViews = builtViews
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
        controlViews.getOrNull(focusIndex)?.isFocusedButton = false
        focusIndex = clamped
        controlViews.getOrNull(focusIndex)?.isFocusedButton = true
    }

    fun setInitialFocus() {
        // Focus enters from the button column on the right, so land on the
        // rightmost control — the closest spatial neighbor.
        focusIndex = controlViews.lastIndex
        controlViews.getOrNull(focusIndex)?.isFocusedButton = true
    }

    fun clearFocus() {
        controlViews.getOrNull(focusIndex)?.isFocusedButton = false
    }

    // ── Control actions ─────────────────────────────────────────────────────

    private fun activateControl(index: Int) {
        when (index) {
            0 -> mediaController?.transportControls?.skipToPrevious() ?: launchPlayerApp()
            1 -> {
                val mc = mediaController
                if (mc != null) {
                    if (mc.playbackState?.state == PlaybackState.STATE_PLAYING)
                        mc.transportControls.pause()
                    else
                        mc.transportControls.play()
                } else {
                    launchPlayerApp()
                }
            }
            2 -> mediaController?.transportControls?.skipToNext() ?: launchPlayerApp()
            3 -> {
                val mc = mediaController ?: return
                val newMode = if (isShuffleOn)
                    PlaybackState.SHUFFLE_MODE_NONE
                else
                    PlaybackState.SHUFFLE_MODE_ALL
                mc.transportControls.setShuffleMode(newMode)
            }
            4 -> launchPlayerApp()
        }
    }

    private fun launchPlayerApp() {
        if (playerPackage.isEmpty()) return
        val intent = context.packageManager.getLaunchIntentForPackage(playerPackage) ?: return
        context.startActivity(intent)
        // Retry browser connection — the service may not have been running before.
        if (mediaController == null) {
            disconnectFromMediaSession()
            connectToMediaSession()
        }
    }

    // ── Media browser ───────────────────────────────────────────────────────

    private fun connectToMediaSession() {
        if (playerPackage.isEmpty()) return

        val intent = Intent(MediaBrowserService.SERVICE_INTERFACE)
        intent.setPackage(playerPackage)
        val services = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        if (services.isEmpty()) return

        val si = services[0].serviceInfo
        val component = ComponentName(si.packageName, si.name)
        val browser = MediaBrowser(context, component, browserCallback, null)
        mediaBrowser = browser
        browser.connect()
    }

    private fun disconnectFromMediaSession() {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
    }

    private val browserCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser?.sessionToken ?: return
            attachToController(MediaController(context, token))
        }
        override fun onConnectionSuspended() { detachController() }
    }

    private fun attachToController(controller: MediaController) {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = controller
        controller.registerCallback(mediaCallback)
        updateMetadata(controller.metadata)
        updatePlaybackState(controller.playbackState)
        syncShuffleMode(controller.shuffleMode)
    }

    private fun detachController() {
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = null
        showPlaceholder()
        updatePlayPauseIcon(false)
        syncShuffleMode(PlaybackState.SHUFFLE_MODE_NONE)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
        override fun onShuffleModeChanged(shuffleMode: Int) {
            syncShuffleMode(shuffleMode)
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
        val btn = controlViews.getOrNull(1) ?: return
        btn.icon = if (isPlaying) pauseIcon else playIcon
        btn.backgroundTintList = ColorStateList.valueOf(
            if (isPlaying) context.getColor(R.color.colorPrimary) else Color.TRANSPARENT
        )
    }

    private fun syncShuffleMode(mode: Int) {
        isShuffleOn = mode != PlaybackState.SHUFFLE_MODE_NONE
        val btn = controlViews.getOrNull(3) ?: return
        btn.icon = if (isShuffleOn) shuffleOnIcon else shuffleOffIcon
        btn.backgroundTintList = ColorStateList.valueOf(
            if (isShuffleOn) context.getColor(R.color.colorPrimary) else Color.TRANSPARENT
        )
    }

    private fun showPlaceholder() {
        placeholderView?.visibility = View.VISIBLE
        artView?.visibility = View.GONE
        titleView?.visibility = View.GONE
        artistView?.visibility = View.GONE
        albumView?.visibility = View.GONE
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

    private fun drawLaunch(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE
            strokeWidth = size * 0.08f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val m = size * 0.22f
        val r = size - m
        val mid = size * 0.5f
        // Box, open at top-right corner
        val path = Path().apply {
            moveTo(mid, m)
            lineTo(m, m)
            lineTo(m, r)
            lineTo(r, r)
            lineTo(r, mid)
        }
        canvas.drawPath(path, paint)
        // Diagonal arrow from center to top-right
        canvas.drawLine(mid, mid, r, m, paint)
        val arr = size * 0.15f
        canvas.drawLine(r, m, r - arr, m, paint)
        canvas.drawLine(r, m, r, m + arr, paint)
        return bmp
    }
}
