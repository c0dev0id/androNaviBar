package de.codevoid.andronavibar

import android.content.ComponentName

// ── Icon type for URL buttons ─────────────────────────────────────────────────

sealed class UrlIcon {
    object None : UrlIcon()
    /** User-provided image; stored at the button's icon file. */
    object CustomFile : UrlIcon()
    /** Emoji rendered onto a tinted background; no file on disk. */
    data class Emoji(val emoji: String) : UrlIcon()

    companion object {
        fun fromRow(iconType: String?, iconData: String?): UrlIcon = when (iconType) {
            "custom" -> CustomFile
            "emoji"  -> Emoji(iconData ?: "")
            else     -> None
        }

        fun toRow(icon: UrlIcon): Pair<String?, String?> = when (icon) {
            is None       -> null to null
            is CustomFile -> "custom" to null
            is Emoji      -> "emoji" to icon.emoji
        }
    }
}

// ── Apps grid entry ───────────────────────────────────────────────────────────

data class AppEntry(val packageName: String, val label: String)

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
        val icon: UrlIcon = UrlIcon.None,
        val openInBrowser: Boolean = false
    ) : ButtonConfig()

    data class WidgetLauncher(
        val provider: ComponentName,
        val appWidgetId: Int,           // -1 until bound
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    data class MusicPlayer(
        val playerPackage: String,      // fallback app to launch when nothing is playing
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    /** A collection of URL bookmarks shown as a list pane when activated. */
    data class BookmarkCollection(
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    /** A collection of navigation targets (label + URI) launched via a configured app. */
    data class NavTargetCollection(
        val label: String,
        val icon: UrlIcon = UrlIcon.None,
        val appPackage: String          // app to launch with each target URI
    ) : ButtonConfig()
}
