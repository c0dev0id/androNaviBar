package de.codevoid.andronavibar

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.codevoid.andronavibar.ui.FocusableButton
import java.net.HttpURLConnection
import java.net.URL

/**
 * Content pane for URL buttons configured with "open in external browser".
 *
 * Shows the button label, a large site icon (custom, emoji, or fetched favicon),
 * and a focusable "Open in Browser" button. The user presses LEFT to enter the
 * pane, then CONFIRM to open the URL in the system browser.
 */
class UrlLauncherPaneContent(
    private val context: Context,
    private val url: String,
    private val label: String,
    private val icon: UrlIcon,
    private val buttonIndex: Int,
    private val onOpenInBrowser: () -> Unit
) : PaneContent {

    private var rootView: LinearLayout? = null
    private var openButton: FocusableButton? = null

    override fun load(onReady: () -> Unit) {
        onReady()
    }

    override fun show(container: ViewGroup) {
        val res = context.resources

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        rootView = root

        if (label.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = label
                textSize = 32f
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    bottomMargin = res.dpToPx(16)
                }
            })
        }

        val iconSize = res.dpToPx(128)
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = res.dpToPx(32)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        loadIconInto(iconView)
        root.addView(iconView)

        val btn = FocusableButton(context).apply {
            text = context.getString(R.string.open_in_browser)
            textSize = 22f
            cornerRadius = res.dpToPx(FocusableButton.CORNER_RADIUS_DP)
            layoutParams = LinearLayout.LayoutParams(res.dpToPx(300), WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { onOpenInBrowser() }
        }
        openButton = btn
        root.addView(btn)

        container.addView(root)
    }

    override fun unload() {
        val root = rootView ?: return
        rootView = null
        openButton = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    /** Returns true when the key was consumed (CONFIRM opens the browser). */
    fun handleKey(keyCode: Int): Boolean = when (keyCode) {
        66 -> { onOpenInBrowser(); true }
        else -> false
    }

    fun setInitialFocus() { openButton?.isFocusedButton = true }
    fun clearFocus()       { openButton?.isFocusedButton = false }

    // ── Icon loading ─────────────────────────────────────────────────────────

    private fun loadIconInto(imageView: ImageView) {
        when (icon) {
            is UrlIcon.CustomFile -> {
                val file = buttonIconFile(context.filesDir, buttonIndex)
                val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                if (bmp != null) imageView.setImageBitmap(bmp) else fetchFavicon(imageView)
            }
            is UrlIcon.Emoji -> imageView.setImageDrawable(context.renderEmojiDrawable(icon.emoji))
            is UrlIcon.None  -> fetchFavicon(imageView)
        }
    }

    private fun fetchFavicon(imageView: ImageView) {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        val domain = try {
            android.net.Uri.parse(normalized).host ?: return
        } catch (_: Exception) { return }

        Thread {
            try {
                val conn = URL("https://www.google.com/s2/favicons?domain=$domain&sz=256")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                if (bmp != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (imageView.isAttachedToWindow) imageView.setImageBitmap(bmp)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
}
