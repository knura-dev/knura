package io.knura.android

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the last crash's stack trace, so the app can show it on the next start. Without it a
 * crash only says "Knura keeps stopping", and reading Android's own log needs a computer and adb.
 * Nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                file.writeText("Knura ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE}, " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n" + e.stackTraceToString())
            } catch (ignored: Exception) {
                // Nothing more to do while crashing.
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The saved crash report, removed once read, or null if there is none. */
    fun takeLast(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = file.readText()
        file.delete()
        return text
    }
}
