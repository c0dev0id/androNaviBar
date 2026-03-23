package de.codevoid.andronavibar

import android.content.ComponentName
import android.content.SharedPreferences

// ── Icon type for URL buttons ─────────────────────────────────────────────────

sealed class UrlIcon {
    object None : UrlIcon()
    /** User-provided image; stored at the button's icon file. */
    object CustomFile : UrlIcon()
    /** Emoji rendered onto a tinted background; no file on disk. */
    data class Emoji(val emoji: String) : UrlIcon()

    companion object {
        fun fromPrefs(prefs: SharedPreferences, index: Int): UrlIcon {
            val iconType = prefs.getString("btn_${index}_icon_type", null)
            val iconData = prefs.getString("btn_${index}_icon_data", null)
            return when (iconType) {
                "custom" -> CustomFile
                "emoji"  -> Emoji(iconData ?: "")
                else     -> None
            }
        }

        fun writeTo(edit: SharedPreferences.Editor, index: Int, icon: UrlIcon) {
            when (icon) {
                is None       -> edit.remove("btn_${index}_icon_type").remove("btn_${index}_icon_data")
                is CustomFile -> edit.putString("btn_${index}_icon_type", "custom").remove("btn_${index}_icon_data")
                is Emoji      -> edit.putString("btn_${index}_icon_type", "emoji").putString("btn_${index}_icon_data", icon.emoji)
            }
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

    data class AppsGrid(
        val apps: List<AppEntry>,
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()

    data class MusicPlayer(
        val playerPackage: String,      // fallback app to launch when nothing is playing
        val label: String,
        val icon: UrlIcon = UrlIcon.None
    ) : ButtonConfig()
}
