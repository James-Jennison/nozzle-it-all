package net.jamesjennison.klippercompanion

import org.json.JSONArray

/**
 * OrcaSlicer's C-style escaping of string options (libslic3r Config.cpp escape_string_cstyle / unescape_string_cstyle).
 * Profiles keep a string option such as machine_start_gcode in Orca's serialized form, so a JSON value may carry "\n"
 * as a backslash and an "n" (Snapmaker Orca's own U1 machines do). The engine unescapes it on load
 * (ConfigOptionString::deserialize); code that reads or writes the text itself must do the same.
 */
object OrcaStrings {
    /** Escapes double quotes, \n, \r and backslash, as escape_string_cstyle does. */
    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            '\\', '"' -> append('\\').append(c)
            else -> append(c)
        }
    }

    /** Undoes [escape], as unescape_string_cstyle does: \n and \r become line breaks, any other escaped character itself. A trailing lone backslash is dropped. */
    fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { append(c); continue }
            if (i == s.length) break
            when (val e = s[i++]) { 'r' -> append('\r'); 'n' -> append('\n'); else -> append(e) }
        }
    }

    /**
     * The text of a single-string option as the engine loads it from profile JSON ([ConfigBase::load_from_json]): a
     * string, or a one-value array (or one whose values are all equal), unescaped. Null when absent.
     */
    fun scalar(v: Any?): String? = when (v) {
        null, org.json.JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.distinct().let { if (it.size == 1) unescape(it[0]) else it.joinToString(",") { s -> unescape(s) } }
        else -> unescape(v.toString())
    }
}
