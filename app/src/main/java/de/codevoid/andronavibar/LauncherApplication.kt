package de.codevoid.andronavibar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Registers a persistent remote listener for the global home/app toggle.
 *
 * As a HOME launcher, Android keeps this process alive indefinitely, so a
 * receiver registered here stays active even when another app is in the
 * foreground — no foreground service or notification required.
 *
 * Trigger: hold Round Button 2 (key 111) for 3 seconds.
 *   Launcher in foreground → moveTaskToBack(true); Android reveals the previous app.
 *   Other app in foreground → bring launcher to front.
 *
 * Short presses of key 111 (< 3 s) are handled by MainActivity (cancel/dismiss).
 * This receiver only acts when the full hold duration elapses.
 */
class LauncherApplication : Application() {

    private val toggleHandler = Handler(Looper.getMainLooper())
    private var key111Held = false

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != REMOTE_ACTION) return
            when {
                intent.hasExtra("key_press") -> {
                    if (intent.getIntExtra("key_press", 0) == TOGGLE_KEY && !key111Held) {
                        key111Held = true
                        toggleHandler.postDelayed(::performToggle, TOGGLE_HOLD_MS)
                    }
                }
                intent.hasExtra("key_release") -> {
                    if (intent.getIntExtra("key_release", 0) == TOGGLE_KEY) {
                        key111Held = false
                        toggleHandler.removeCallbacksAndMessages(null)
                    }
                }
            }
        }
    }

    private fun performToggle() {
        key111Held = false
        val activity = mainActivity?.get()
        if (activity != null) {
            // Launcher is in the foreground — let Android switch back to the previous app.
            activity.moveTaskToBack(true)
        } else {
            // Another app is in the foreground — bring the launcher to front.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(remoteReceiver, IntentFilter(REMOTE_ACTION), RECEIVER_EXPORTED)
    }

    companion object {
        const val REMOTE_ACTION  = "com.thorkracing.wireddevices.keypress"
        const val PREFS_NAME     = "button_config"
        /** Round Button 2 — short press cancels; 3-second hold triggers global toggle. */
        const val TOGGLE_KEY     = 111
        const val TOGGLE_HOLD_MS = 3_000L

        /** Weak reference to the active MainActivity; set in onResume, cleared in onPause. */
        var mainActivity: WeakReference<MainActivity>? = null
    }
}
