package de.codevoid.andronavibar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * Registers a persistent remote listener for the launcher ↔ app toggle.
 *
 * As a HOME launcher, Android keeps this process alive indefinitely, so a
 * receiver registered here stays active even when another app is in the
 * foreground — no foreground service or notification required.
 *
 * Toggle trigger: hold Lever Up (key 136) for 3 seconds.
 *   Launcher in foreground → launch last app that was started from the launcher.
 *   Other app in foreground → bring launcher to front.
 *
 * State is shared with MainActivity via SharedPreferences (PREFS_NAME):
 *   KEY_LAUNCHER_FOREGROUND  – written by MainActivity.onWindowFocusChanged / onPause
 *   KEY_LAST_LAUNCHED        – written by MainActivity when an AppLauncher button fires
 */
class LauncherApplication : Application() {

    private val toggleHandler = Handler(Looper.getMainLooper())
    private val heldKeys = mutableSetOf<Int>()

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != REMOTE_ACTION) return
            when {
                intent.hasExtra("key_press") -> {
                    val key = intent.getIntExtra("key_press", 0)
                    if (key == TOGGLE_KEY && heldKeys.add(key)) {
                        toggleHandler.postDelayed(::performToggle, TOGGLE_HOLD_MS)
                    }
                }
                intent.hasExtra("key_release") -> {
                    val key = intent.getIntExtra("key_release", 0)
                    if (key == TOGGLE_KEY) {
                        heldKeys.remove(key)
                        toggleHandler.removeCallbacksAndMessages(null)
                    }
                }
            }
        }
    }

    private fun performToggle() {
        heldKeys.remove(TOGGLE_KEY)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (prefs.getBoolean(KEY_LAUNCHER_FOREGROUND, true)) {
            // Launcher is up — switch to the last app launched from this launcher.
            val pkg = prefs.getString(KEY_LAST_LAUNCHED, null) ?: return
            packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let(::startActivity)
        } else {
            // Another app is up — bring launcher to front.
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
        const val PREFS_NAME              = "button_config"
        const val KEY_LAUNCHER_FOREGROUND = "launcher_foreground"
        const val KEY_LAST_LAUNCHED       = "last_launched_package"
        const val REMOTE_ACTION           = "com.thorkracing.wireddevices.keypress"
        /** Lever Up key code on the DMD remote. Change here to remap the toggle. */
        const val TOGGLE_KEY              = 136
        const val TOGGLE_HOLD_MS          = 3_000L
    }
}
