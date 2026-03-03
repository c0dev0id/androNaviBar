package de.codevoid.andronavibar

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
     * Sets a launch bounds hint covering the right half of the screen.
     * Combined with FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK,
     * this triggers split-screen or freeform multi-window mode on Android 14+
     * large-screen devices.
     */
    private fun buildLaunchOptions(screenBounds: Rect): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(
            Rect(screenBounds.width() / 2, 0, screenBounds.width(), screenBounds.height())
        )
        return options
    }
}
