package de.codevoid.andronavibar

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle

/**
 * Invisible launcher entry point that implements split-screen toggle behavior.
 *
 * First tap:  launches MainActivity in split-screen adjacent to the current app.
 * Second tap: sends a quit intent to the running MainActivity.
 *
 * This activity finishes immediately and is never visible to the user.
 */
class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (MainActivity.isRunning) {
            val quitIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_QUIT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(quitIntent)
        } else {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                        or Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

            val screenBounds = windowManager.currentWindowMetrics.bounds
            startActivity(launchIntent, buildLaunchOptions(screenBounds).toBundle())
        }

        finish()
    }

    /**
     * Builds ActivityOptions for launching MainActivity in split-screen.
     *
     * Primary path: invokes the hidden setLaunchWindowingMode(4) API
     * (WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) via reflection. This is the
     * reliable mechanism on Android 14+ car head units and custom AOSP builds
     * where FLAG_ACTIVITY_LAUNCH_ADJACENT alone does not trigger split-screen
     * from a fullscreen context.
     *
     * Fallback path: if the hidden API is inaccessible (blocked by the device's
     * hidden API policy), sets a launch bounds hint covering the right half of
     * the screen, which works on standard freeform-capable devices.
     */
    @SuppressLint("DiscouragedPrivateApi", "BlockedPrivateApi")
    private fun buildLaunchOptions(screenBounds: Rect): ActivityOptions {
        val options = ActivityOptions.makeBasic()

        // Primary: explicitly request split-screen secondary windowing mode.
        // WINDOWING_MODE_SPLIT_SCREEN_SECONDARY = 4 (hidden API, works on most
        // Android 12+ custom builds such as car head units).
        try {
            val m = ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            m.invoke(options, 4)
        } catch (_: Exception) {
            // Hidden API inaccessible — fall back to bounds hint for freeform-capable devices.
            options.setLaunchBounds(
                Rect(screenBounds.width() / 2, 0, screenBounds.width(), screenBounds.height())
            )
        }

        return options
    }
}
