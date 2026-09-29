package net.jamesjennison.klippercompanion.testgrid

import android.content.Context
import androidx.core.content.edit

/**
 * Test Mode is for invited testers, so it is hidden until turned on: tapping a version line (Settings → Diagnostics, or About & credits)
 * [TAPS] times within [WINDOW_MS] toggles it. Stored per device; nothing else changes for a user who never does this.
 */
object TestModeAccess {
    const val TAPS = 7
    const val WINDOW_MS = 3_000L
    private const val PREFS = "testgrid"
    private const val KEY = "test_mode_enabled"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) } }

    /** Counts taps; [tap] returns true on the tap that completes [needed] taps within [windowMs] of the first. Pure. */
    class TapCounter(private val needed: Int = TAPS, private val windowMs: Long = WINDOW_MS, private val clock: () -> Long = System::currentTimeMillis) {
        private var first = 0L
        private var count = 0
        fun tap(): Boolean {
            val now = clock()
            if (count == 0 || now - first > windowMs) { first = now; count = 0 }
            count++
            if (count >= needed) { count = 0; return true }
            return false
        }
    }
}
