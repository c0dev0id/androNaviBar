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

            val options = ActivityOptions.makeBasic()

            // Hint at right-half positioning for the split screen.
            // The system may adjust these bounds, but this signals our preference.
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val bounds = Rect(screenWidth / 2, 0, screenWidth, screenHeight)
            options.setLaunchBounds(bounds)

            startActivity(launchIntent, options.toBundle())
        }

        finish()
    }
}
