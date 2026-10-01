package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** OrcaStrings matches libslic3r's escape_string_cstyle / unescape_string_cstyle and load_from_json's scalar handling. */
class OrcaStringsTest {
    @Test fun escapesAsOrcaSerializes() {
        assertEquals("""{if a == \"b\"}\nG28\r\nM117 C:\\x""", OrcaStrings.escape("{if a == \"b\"}\nG28\r\nM117 C:\\x"))
    }

    @Test fun unescapesAsOrcaLoads() {
        // Snapmaker Orca's own U1 start G-code, as its JSON text stores it.
        assertEquals(";===== date: 20260128 =====\n\nPRINT_START\n{if print_sequence == \"by object\"}",
            OrcaStrings.unescape(""";===== date: 20260128 =====\n\nPRINT_START\n{if print_sequence == \"by object\"}"""))
        assertEquals("any other escaped character is itself", "q", OrcaStrings.unescape("""\q"""))
        assertEquals("a trailing lone backslash is dropped", "G28", OrcaStrings.unescape("G28\\"))
        assertEquals("real line breaks pass through", "G28\nG29", OrcaStrings.unescape("G28\nG29"))
    }

    @Test fun roundTrips() {
        val text = "M117 \"C:\\dir\\name\"\r\nPRINT_START\n\\n is not a line break here"
        assertEquals(text, OrcaStrings.unescape(OrcaStrings.escape(text)))
    }

    @Test fun scalarReadsAStringOrAOneValueArray() {
        assertNull(OrcaStrings.scalar(null))
        assertEquals("G28\nG29", OrcaStrings.scalar("G28\\nG29"))
        assertEquals("G28\nG29", OrcaStrings.scalar(JSONArray().put("G28\\nG29")))
        assertEquals("G28\nG29", OrcaStrings.scalar(JSONArray().put("G28\\nG29").put("G28\\nG29")))
    }
}
