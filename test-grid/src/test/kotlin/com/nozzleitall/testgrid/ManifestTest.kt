package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestTest {
    private fun minimal(): JSONObject = JSONObject(java.io.File(Support.root, "test-grid/src/main/resources/testgrid/suites/paxx-u1.json").readText())

    private fun parseProblem(o: JSONObject): String = try { Suite.parse(o); fail("expected the suite to be rejected"); "" } catch (e: SuiteFormatException) { e.message!! }

    @Test fun everyBundledSuiteParsesAndValidates() {
        val suites = SuiteCatalog.bundled()
        assertEquals(9, suites.size)
        assertEquals(listOf("paxx-u1", "cosmos-centauri-carbon"), suites.filter { it.coverage == Coverage.REFERENCE }.map { it.id })
        assertTrue(suites.filter { it.coverage == Coverage.FIXTURE }.map { it.id }.containsAll(listOf("snapmaker-u1-stock", "elegoo-centauri-carbon-stock", "opencentauri-patched", "bambu-lan", "prusalink", "generic-klipper", "octoprint")))
        suites.forEach { s -> assertTrue(s.id, s.tests.isNotEmpty()); assertEquals(64, s.digest.length) }
    }

    @Test fun newerMajorVersionIsRejectedAsIncompatible() {
        val o = minimal().put("version", JSONArray().put(2).put(0))
        try { Suite.parse(o); fail() } catch (e: IncompatibleSuiteException) { assertEquals(2, e.major); assertTrue(e.message!!.contains("newer")) }
    }

    @Test fun wrongSchemaAndMissingVersionAreRejected() {
        parseProblem(minimal().put("schema", "something.else"))
        parseProblem(minimal().apply { remove("version") })
    }

    @Test fun newerMinorWithUnknownFieldsIsAcceptedAndKeepsThem() {
        val o = minimal().put("version", JSONArray().put(1).put(7)).put("futureTopLevel", JSONObject().put("x", 1))
        o.getJSONArray("tests").getJSONObject(0).put("futureTestField", "kept")
        o.getJSONArray("tests").getJSONObject(0).getJSONArray("steps").getJSONObject(0).put("futureStepField", true)
        val s = Suite.parse(o)
        assertEquals(7, s.formatMinor)
        assertTrue(s.unknown.has("futureTopLevel"))
        assertEquals("kept", s.tests[0].unknown.getString("futureTestField"))
        assertTrue(s.tests[0].steps[0].unknown.getBoolean("futureStepField"))
    }

    @Test fun unknownStepKindFromANewerMinorParsesButIsUnsupported() {
        val o = minimal().put("version", JSONArray().put(1).put(3))
        o.getJSONArray("tests").getJSONObject(0).getJSONArray("steps").put(JSONObject().put("id", "future").put("kind", "measure_with_lidar"))
        val s = Suite.parse(o)
        assertNull(s.tests[0].steps.last().kind)
        assertEquals(listOf("future"), s.tests[0].unsupportedSteps.map { it.id })
    }

    private fun controlsTest(o: JSONObject): JSONObject = (0 until o.getJSONArray("tests").length()).map { o.getJSONArray("tests").getJSONObject(it) }.first { it.getString("id") == "controls-idle" }

    @Test fun aConsequentialStepCannotDropItsConfirmation() {
        val o = minimal(); controlsTest(o).getJSONArray("steps").getJSONObject(0).put("requiresConfirmation", false)
        assertTrue(parseProblem(o).contains("must require operator confirmation"))
    }

    @Test fun stepLimitsCannotBeExceeded() {
        val hot = minimal(); controlsTest(hot).getJSONArray("steps").getJSONObject(0).getJSONObject("params").put("celsius", 250)
        assertTrue(parseProblem(hot).contains("celsius must be 0 to 120"))
        val far = minimal(); (0 until controlsTest(far).getJSONArray("steps").length()).map { controlsTest(far).getJSONArray("steps").getJSONObject(it) }.first { it.getString("kind") == "jog" }.getJSONObject("params").put("mm", 50)
        assertTrue(parseProblem(far).contains("at most 10.0 mm"))
    }

    @Test fun aStepAboveTheTestsSafetyLevelIsRejected() {
        val o = minimal(); controlsTest(o).put("safetyLevel", 1)
        assertTrue(parseProblem(o).contains("above the test's"))
    }

    @Test fun mutatingTestsMustDeclareItAndForbidRetryAfterUncertainty() {
        val a = minimal(); controlsTest(a).put("mutatesPrinterState", false)
        assertTrue(parseProblem(a).contains("mutatesPrinterState must be true"))
        val b = minimal(); controlsTest(b).put("uncertainResultProhibitsRetry", false)
        assertTrue(parseProblem(b).contains("uncertain result must prohibit retry"))
        val c = minimal(); controlsTest(c).put("requiresOperatorConfirmation", false)
        assertTrue(parseProblem(c).contains("requiresOperatorConfirmation must be true"))
    }

    @Test fun requiredEvidenceMustBeCollectedAndDependenciesMustComeEarlier() {
        val a = minimal(); controlsTest(a).put("requiredEvidence", JSONArray().put(JSONObject().put("id", "ghost").put("kind", "photo").put("description", "x")))
        assertTrue(parseProblem(a).contains("no attach/observe step collects it"))
        val b = minimal(); controlsTest(b).put("dependsOn", JSONArray().put("print-single"))
        assertTrue(parseProblem(b).contains("must name an earlier test"))
    }

    @Test fun thereIsNoArbitraryCommandStep() {
        val o = minimal(); controlsTest(o).getJSONArray("steps").put(JSONObject().put("id", "raw").put("kind", "gcode").put("params", JSONObject().put("script", "M112")))
        // Parses (a newer minor might name new kinds), but the test is blocked because this build can't run the step.
        assertEquals(listOf("raw"), Suite.parse(o).test("controls-idle")!!.unsupportedSteps.map { it.id })
        assertTrue(StepKind.entries.none { it.id in setOf("gcode", "macro", "script", "shell") })
    }

    @Test fun versionsCompareNumerically() {
        assertEquals(true, Versions.atLeast("0.10.0", "0.9.9"))
        assertEquals(false, Versions.atLeast("0.1.0", "0.2"))
        assertEquals(true, Versions.atLeast("0.1.0-debug", "0.1.0"))
        assertNull(Versions.atLeast("unknown", "0.1.0"))
    }
}
