package com.nozzleitall.printer.ext

import com.nozzleitall.printer.ext.AutoPaintFormat.Provider
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPaintFormatTest {
    @Test fun requestsCarryTheDocumentedFields() {
        val seg = AutoPaintFormat.segmentRequest("/m.stl", "/out", listOf(AutoPaintFormat.Filament(1, "PLA", "#FF0000", "Red")), "benchy", "make it festive")
        assertEquals("segment", seg.getString("op")); assertEquals("#FF0000", seg.getJSONArray("filaments").getJSONObject(0).getString("color"))
        assertEquals("command", AutoPaintFormat.replyTextRequest(Provider.Command("claude -p"), "x").getString("kind"))
        assertEquals("openai", AutoPaintFormat.replyTextRequest(Provider.OpenAiCompatible("http://h/v1", "m"), "x").getString("kind"))
        assertEquals(3, AutoPaintFormat.assignRequest("/m.stl", "{}", 3).getInt("filament_count"))
    }

    @Test fun anAssignmentExpandsItsRunsToOneSlotPerFace() {
        val a = AutoPaintFormat.parseAssignment(JSONObject("""{"segment_to_filament":[1,0,2],"faces":7,"runs":[[2,1],[3,0],[2,2]]}"""))
        assertArrayEquals(intArrayOf(1, 1, 0, 0, 0, 2, 2), a.faceFilaments); assertEquals(4, a.paintedFaces); assertEquals(listOf(1, 0, 2), a.segmentToFilament)
    }

    @Test(expected = IllegalArgumentException::class) fun runsThatDoNotCoverTheModelAreRefused() {
        AutoPaintFormat.parseAssignment(JSONObject("""{"segment_to_filament":[1],"faces":5,"runs":[[2,1]]}"""))
    }

    @Test fun segmentationAndErrorsParse() {
        val s = AutoPaintFormat.parseSegmentation(JSONObject("""{"segments":[{},{}],"views":[{"name":"front","path":"/o/view-front.png"}],"prompt":"p","command_prompt":"cp"}"""))
        assertEquals(2, s.segmentCount); assertEquals("front", s.views.single().name); assertEquals("cp", s.commandPrompt)
        assertEquals("nope", AutoPaintFormat.error(JSONObject("""{"error":"nope"}""")))
        assertNull(AutoPaintFormat.error(JSONObject("""{"segments":[]}""")))
        assertEquals("http://h:11434/v1/chat/completions", AutoPaintFormat.chatUrl("http://h:11434/v1/"))
    }
}
