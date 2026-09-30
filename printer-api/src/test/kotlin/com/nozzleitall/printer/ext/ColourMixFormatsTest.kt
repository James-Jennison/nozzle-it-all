package com.nozzleitall.printer.ext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Colour mixing (0.2.0): the shared request/response JSON both Desktop's FullSpectrum.kt/PrusaColorMix.kt (CLI
 * transport) and Android's AndroidColourMixing.kt (JNI transport) build and parse - the single source of truth
 * requirement 1 asked for. Desktop's own PrusaColorMixTest/FullSpectrumTest already exercise this against the real
 * engine (assumeTrue-gated); these tests are pure-JVM and cover the shapes those integration tests don't reach on
 * their own: the `{"error":...}` failure path, and round-tripping every field without a live engine.
 */
class ColourMixFormatsTest {
    @Test fun errorOfReadsTheEnginesFailureConvention() {
        assertEquals("bad slot", FullSpectrumFormat.errorOf(JSONObject("""{"error":"bad slot"}""")))
        assertNull(FullSpectrumFormat.errorOf(JSONObject("""{"ok":true}""")))
        assertNull(FullSpectrumFormat.errorOf(JSONObject("""{"error":""}""")))
        assertEquals("no such id", PrusaColorMixFormat.errorOf(JSONObject("""{"error":"no such id"}""")))
        assertNull(PrusaColorMixFormat.errorOf(JSONObject("""{"ok":true}""")))
    }

    @Test fun baseRequestCarriesOpPhysicalAndDefinitions() {
        val req = FullSpectrumFormat.baseRequest("add", listOf("#FF0000", "#00FF00"), "1:0:1:50")
        assertEquals("add", req.getString("op"))
        assertEquals(2, req.getJSONArray("physical").length())
        assertEquals("#FF0000", req.getJSONArray("physical").getString(0))
        assertEquals("1:0:1:50", req.getString("definitions"))
    }

    @Test fun parseMixesReadsRowsRemapAndWarning() {
        val response = JSONObject(
            """{"definitions":"1:0:1:50:#800080:Mix:1:-1:", "added_id": 1, "remap": {"2": 3}, "warning": "clamped",
               "rows": [{"id":1,"a":0,"b":1,"mix_b_percent":50,"gradient_ids":"0/1","gradient_weights":"50/50",
                          "display":"#800080","label":"Mix","enabled":true,"ui_mode":1,"manual_pattern":"solid"}]}""",
        )
        val mixes = FullSpectrumFormat.parseMixes(response)
        assertEquals("1:0:1:50:#800080:Mix:1:-1:", mixes.definitions)
        assertEquals(1, mixes.addedId)
        assertEquals(mapOf(2 to 3), mixes.remap)
        assertEquals("clamped", mixes.warning)
        assertEquals(1, mixes.rows.size)
        val row = mixes.rows.single()
        assertEquals(1, row.id); assertEquals(0, row.a); assertEquals(1, row.b); assertEquals(50, row.mixBPercent)
        assertEquals(listOf(0, 1), row.components); assertEquals(listOf(50, 50), row.weights)
        assertEquals("#800080", row.displayHex); assertEquals("Mix", row.label); assertTrue(row.enabled)
        assertEquals(1, row.uiMode); assertEquals("solid", row.pattern)
    }

    @Test fun parseMixesFallsBackToAAndBWhenNoGradientFieldsArePresent() {
        // A plain (non-gradient) two-colour mix: no gradient_ids/gradient_weights at all - components/weights fall
        // back to [a, b] / [100 - mixBPercent, mixBPercent], the real shape `add`'s own basic two-colour op returns.
        val response = JSONObject("""{"definitions":"d","rows":[{"id":2,"a":3,"b":4,"mix_b_percent":30}]}""")
        val row = FullSpectrumFormat.parseMixes(response).rows.single()
        assertEquals(listOf(3, 4), row.components)
        assertEquals(listOf(70, 30), row.weights)
        assertEquals("#26A69A", row.displayHex) // the engine's own documented default preview colour
        assertEquals(-1, row.uiMode)
    }

    @Test fun parseMixesDefaultsAreSafeWhenOptionalFieldsAreMissing() {
        val mixes = FullSpectrumFormat.parseMixes(JSONObject("""{"definitions":"","rows":[]}"""))
        assertEquals("", mixes.definitions)
        assertNull(mixes.addedId)
        assertTrue(mixes.remap.isEmpty())
        assertNull(mixes.warning)
        assertTrue(mixes.rows.isEmpty())
    }

    @Test fun virtualExtruderRoundTripsThroughToJsonAndParse() {
        val v = PrusaColorMixFormat.Virtual(
            id = 6, kind = "blend",
            components = listOf(PrusaColorMixFormat.Component(1, 2.0 / 3), PrusaColorMixFormat.Component(2, 1.0 / 3)),
            colorOverride = "#800080",
        )
        val back = PrusaColorMixFormat.parse(v.toJson())
        assertEquals(v.id, back.id)
        assertEquals(v.kind, back.kind)
        assertEquals(v.components, back.components)
        // toJson()/parse() here is the *file* round-trip (no effective_color marker, so parse() can't tell an
        // override from PrusaSlicer's own written colour apart - see parse()'s own comment) - the written "color"
        // comes back as effectiveHex, not colorOverride, exactly like reading a real sidecar someone else wrote.
        assertEquals(v.colorOverride, back.effectiveHex)
        assertNull(back.colorOverride)
        assertTrue(v.isBlend)
        assertFalse(PrusaColorMixFormat.Virtual(7, "gradient", v.components).isBlend)
    }

    @Test fun sidecarRoundTripsPhysicalAndVirtualLists() {
        val physical = listOf("#FF0000", "#00FF00", "#0000FF")
        val virtual = listOf(PrusaColorMixFormat.Virtual(4, "blend", listOf(PrusaColorMixFormat.Component(1, 0.5), PrusaColorMixFormat.Component(2, 0.5))))
        val bytes = PrusaColorMixFormat.sidecar(physical, virtual).toByteArray()
        val (count, back) = PrusaColorMixFormat.readSidecar(bytes)!!
        assertEquals(3, count)
        assertEquals(virtual.single().components, back.single().components)
        assertEquals(virtual.single().id, back.single().id)
    }

    @Test fun sliceRequestJsonIsTheEnginesOwnValidVirtualExtrudersShape() {
        val v = PrusaColorMixFormat.Virtual(5, "blend", listOf(PrusaColorMixFormat.Component(1, 0.5), PrusaColorMixFormat.Component(2, 0.5)))
        val json = JSONObject(PrusaColorMixFormat.sliceRequestJson(listOf(v)))
        assertEquals(1, json.getInt("version"))
        assertEquals(1, json.getJSONArray("virtual_extruders").length())
        assertEquals(5, json.getJSONArray("virtual_extruders").getJSONObject(0).getInt("id"))
    }

    @Test fun readSidecarReturnsNullForGarbage() {
        assertNull(PrusaColorMixFormat.readSidecar("not json".toByteArray()))
    }
}
