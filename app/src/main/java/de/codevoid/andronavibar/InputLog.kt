package de.codevoid.andronavibar

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal append-log for diagnosing input routing on real hardware.
 *
 * Writes to  <filesDir>/input.log  (rotates to input.log.1 when > MAX_BYTES).
 *
 * Pull with:
 *   adb exec-out run-as de.codevoid.andronavibar cat files/input.log > input.log
 *   adb exec-out run-as de.codevoid.andronavibar cat files/input.log.1 > input.log.1
 */
object InputLog {

    private const val MAX_BYTES = 256 * 1024  // 256 KB per file
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "input.log")
    }

    fun log(msg: String) {
        val file = logFile ?: return
        try {
            if (file.length() > MAX_BYTES) rotate(file)
            file.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {}
    }

    private fun rotate(file: File) {
        val old = File(file.parent, "input.log.1")
        old.delete()
        file.renameTo(old)
    }
}
