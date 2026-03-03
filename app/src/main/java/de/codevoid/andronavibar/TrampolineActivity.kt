package de.codevoid.andronavibar

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
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

            startActivity(launchIntent, buildLaunchOptions().toBundle())
        }

        finish()
    }

    /**
     * Builds ActivityOptions for launching MainActivity in split-screen.
     *
     * Split-screen behavior is triggered entirely by the intent flags
     * (FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK) on large-screen
     * (≥600dp) Android 12L+ devices. No hidden APIs or launch bounds hints are
     * needed on standard Android 14 tablets in landscape mode.
     */
    private fun buildLaunchOptions(): ActivityOptions {
        return ActivityOptions.makeBasic()
    }
}
