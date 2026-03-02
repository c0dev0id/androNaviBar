package de.codevoid.andronavibar

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class MainActivity : Activity() {

    private var enteredMultiWindowMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val multiWindowCheck = Runnable {
        if (!enteredMultiWindowMode && !isInMultiWindowMode) finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning = true
        setContentView(R.layout.activity_main)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!enteredMultiWindowMode) handler.postDelayed(multiWindowCheck, 500)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(multiWindowCheck)
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
            enteredMultiWindowMode = true
            handler.removeCallbacks(multiWindowCheck)
        } else if (enteredMultiWindowMode) {
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
