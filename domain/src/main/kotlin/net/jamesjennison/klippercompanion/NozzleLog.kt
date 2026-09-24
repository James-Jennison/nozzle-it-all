package net.jamesjennison.klippercompanion

// Phase 9S: platform-neutral logging for shared code. The Android app points [sink] at android.util.Log at start-up;
// until then (unit tests, a desktop build) messages are dropped.
object NozzleLog {
    @Volatile var sink: (level: Char, tag: String, message: String) -> Unit = { _, _, _ -> }
    fun i(tag: String, message: String) = sink('i', tag, message)
    fun w(tag: String, message: String) = sink('w', tag, message)
}
