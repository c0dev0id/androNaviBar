package de.codevoid.andronavibar

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap

/**
 * PaneContent that loads a URL in a full-pane WebView.
 *
 * Navigation within the WebView stays inside it (no external browser).
 * The pane is dismissed when another button is activated or the config
 * pane is opened.
 */
class WebPaneContent(
    private val context: Context,
    private val url: String
) : PaneContent {

    private var webView: WebView? = null

    /** Called once when the initial page finishes loading. */
    var onContentReady: (() -> Unit)? = null

    override fun load(onReady: () -> Unit) {
        onReady()   // WebView is lightweight to create; page loads async after show()
    }

    override fun hide() { webView?.visibility = View.GONE }

    override fun show(container: ViewGroup) {
        webView?.let { it.visibility = View.VISIBLE; return }

        val targetUrl = url   // capture before apply{} — inside apply, 'url' resolves to WebView.url (String?)
        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // Fire on first meaningful paint — onPageStarted indicates the
                    // server responded and the WebView has begun rendering.
                    onContentReady?.invoke()
                    onContentReady = null
                }
            }
            loadUrl(targetUrl)
        }
        webView = wv
        container.addView(wv)
    }

    override fun unload() {
        val wv = webView ?: return
        webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    /** Navigate back in WebView history. Returns true if consumed, false if at root. */
    fun goBack(): Boolean {
        val wv = webView ?: return false
        return if (wv.canGoBack()) { wv.goBack(); true } else false
    }
}
