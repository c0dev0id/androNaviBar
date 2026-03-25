package de.codevoid.andronavibar

import android.view.ViewGroup

/**
 * Contract between a LauncherButton and the left pane (reservedArea).
 *
 * Lifecycle: load() → onReady fires → show() → ... → unload()
 *
 * load() starts any async work (network, media session binding, widget inflation, etc.)
 * and must not block the main thread. onReady is always called on the main thread.
 *
 * show() is only called after onReady has fired and only if the button is still the
 * active pane owner. It attaches the prepared view to container.
 *
 * unload() detaches from container and releases all held resources.
 *
 * hide() makes the pane invisible without detaching it. The default no-op
 * is sufficient for panes that are never cached (e.g. GlobalConfigPaneContent).
 */
interface PaneContent {
    fun load(onReady: () -> Unit)
    fun show(container: ViewGroup)
    fun hide() {}
    fun unload()
}
