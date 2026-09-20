package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercised against the real captured P1S status report (`push_status`, msg 0)
 * copied from Helix's scripts/fixtures/bambu-p1s-report.json, so the field
 * names and their string-or-number types are the printer's, not invented.
 */
class BambuSnapshotTest {

    private fun fixture(): JSONObject = JSONObject(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("bambu-p1s-report.json")) {
            "bambu-p1s-report.json is missing from the test resources"
        }.bufferedReader().use { it.readText() }
    )

    @Test
    fun mapsTheCapturedP1sReport() {
        val snapshot = BambuSnapshot.parse(fixture())

        assertTrue(snapshot.ready)
        // gcode_state FINISH is Klipper's "complete".
        assertEquals("complete", snapshot.state)
        // subtask_name is the only human-readable label a P-series offers;
        // gcode_file is "" in this capture.
        assertEquals("14min44s, Bambu PLA Basic, A1", snapshot.filename)
        assertEquals(1f, snapshot.progress, 0f)
        assertEquals(31.84375, snapshot.nozzle!!, 1e-6)
        assertEquals(0.0, snapshot.nozzleTarget!!, 1e-6)
        assertEquals(30.0, snapshot.bed!!, 1e-6)
        assertEquals(0.0, snapshot.bedTarget!!, 1e-6)
        assertEquals(192, snapshot.currentLayer)
        assertEquals(192, snapshot.totalLayers)
        assertEquals("extruder", snapshot.activeExtruder)
        // Bambu reports only mc_remaining_time; there is no elapsed-time field.
        assertNull(snapshot.printDuration)
    }

    /** A finished job must not present as an active print in the dashboard. */
    @Test
    fun aFinishedJobIsNotAnActivePrint() {
        val snapshot = BambuSnapshot.parse(fixture())

        assertEquals("", snapshot.activeFilename)
        assertEquals(0f, snapshot.activeProgress, 0f)
        assertEquals("standby", snapshot.displayState)
    }

    @Test
    fun mapsAnInFlightPrint() {
        val report = fixture()
        report.getJSONObject("print").apply {
            put("gcode_state", "RUNNING")
            put("mc_percent", 42)
            put("layer_num", 80)
            put("nozzle_target_temper", 220)
            put("bed_target_temper", 60)
        }

        val snapshot = BambuSnapshot.parse(report)

        assertEquals("printing", snapshot.state)
        assertEquals(0.42f, snapshot.progress, 1e-6f)
        assertEquals("14min44s, Bambu PLA Basic, A1", snapshot.activeFilename)
        assertEquals(0.42f, snapshot.activeProgress, 1e-6f)
        assertEquals(80, snapshot.currentLayer)
        assertEquals(220.0, snapshot.nozzleTarget!!, 1e-6)
        assertEquals(60.0, snapshot.bedTarget!!, 1e-6)
    }

    /** PREPARE is heating/levelling: visibly busy, so not "standby". */
    @Test
    fun mapsEveryGcodeStateHelixDoes() {
        fun stateFor(gcodeState: String): String =
            BambuSnapshot.parse(fixture().also { it.getJSONObject("print").put("gcode_state", gcodeState) }).state

        assertEquals("standby", stateFor("IDLE"))
        assertEquals("printing", stateFor("PREPARE"))
        assertEquals("printing", stateFor("SLICING"))
        assertEquals("printing", stateFor("RUNNING"))
        assertEquals("paused", stateFor("PAUSE"))
        assertEquals("complete", stateFor("FINISH"))
        assertEquals("error", stateFor("FAILED"))
        assertEquals("standby", stateFor("SOMETHING_NEW"))
    }

    /** Bambu sends numbers as strings about as often as it sends them as numbers. */
    @Test
    fun readsStringAndNumericTelemetryAlike() {
        val report = fixture()
        report.getJSONObject("print").apply {
            put("mc_percent", "37")
            put("nozzle_temper", "215.5")
            put("layer_num", "17")
            put("total_layer_num", "0")
        }

        val snapshot = BambuSnapshot.parse(report)

        assertEquals(0.37f, snapshot.progress, 1e-6f)
        assertEquals(215.5, snapshot.nozzle!!, 1e-6)
        assertEquals(17, snapshot.currentLayer)
        // A zero layer count is "unknown", not a real total.
        assertNull(snapshot.totalLayers)
    }

    @Test
    fun fallsBackToGcodeFileWhenThereIsNoSubtaskName() {
        val report = fixture()
        report.getJSONObject("print").apply {
            put("subtask_name", "")
            put("gcode_file", "/data/Metadata/plate_1.gcode")
        }

        assertEquals("/data/Metadata/plate_1.gcode", BambuSnapshot.parse(report).filename)
    }

    @Test
    fun leavesMissingTelemetryNullRatherThanGuessing() {
        val snapshot = BambuSnapshot.parse(JSONObject("""{"print":{"gcode_state":"IDLE"}}"""))

        assertEquals("standby", snapshot.state)
        assertEquals("", snapshot.filename)
        assertEquals(0f, snapshot.progress, 0f)
        assertNull(snapshot.nozzle)
        assertNull(snapshot.bed)
        assertNull(snapshot.currentLayer)
        assertNull(snapshot.totalLayers)
    }

    @Test(expected = org.json.JSONException::class)
    fun rejectsAReportWithNoPrintSection() {
        BambuSnapshot.parse(JSONObject("""{"info":{"command":"get_version"}}"""))
    }
}
