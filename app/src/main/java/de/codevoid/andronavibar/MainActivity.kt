package de.codevoid.andronavibar

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle

class MainActivity : Activity() {

    private var wasInMultiWindowMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning = true
        setContentView(R.layout.activity_main)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_QUIT) {
            finishAndRemoveTask()
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) {
            wasInMultiWindowMode = true
        } else if (wasInMultiWindowMode) {
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    companion object {
        const val ACTION_QUIT = "de.codevoid.andronavibar.ACTION_QUIT"
        var isRunning = false
    }
}
