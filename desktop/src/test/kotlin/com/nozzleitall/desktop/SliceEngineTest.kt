package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SliceEngine's engine-independent parts: G-code statistics and the native engine request (the Web App's format). */
class SliceEngineTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun gcode(vararg lines: String) = Files.createTempFile("stats", ".gcode").toFile().apply { writeText(lines.joinToString("\n")); deleteOnExit() }

    @Test fun toolChangesCountOnlySwitchesToADifferentTool() {
        assertEquals(2, SliceEngine.parseStats(gcode("T0", "G1 X1", "T0", "T1", "G1 X2", "T0")).toolChanges)
        // Single-material start G-code selecting T0 twice is not a tool change.
        assertEquals(0, SliceEngine.parseStats(gcode("T0", "T0")).toolChanges)
    }

    @Test fun multiToolRecipeMatchesTheSharedFixture() {
        val fixture = JSONObject(File(root, "schemas/fixtures/multitool.json").readText())
        val input = fixture.getJSONObject("input")
        val slotsJson = input.getJSONArray("slots")
        val slots = (0 until slotsJson.length()).map { i -> slotsJson.getJSONObject(i).let { ProjectManifest.MaterialSlot(i + 1, it.optString("type"), colorHex = it.optString("colorHex")) } }
        val temps = (0 until slotsJson.length()).associate { i -> i + 1 to slotsJson.getJSONObject(i).let { if (it.has("nozzleC")) it.getInt("nozzleC") else null } }
        val got = SliceEngine.multiToolOverrides(input.getDouble("diameter"), slots, { temps[it.slot] }, input.getInt("fallbackNozzleC"))
        val expected = fixture.getJSONObject("expected").let { e -> e.keySet().associateWith { e.getString(it) } }
        assertEquals(expected, got)
    }

    @Test fun nativeRequestPlacesObjectsRelativeToTheBedCentre() {
        val cache = Files.createTempDirectory("prof").toFile()
        val dir = ProfileCatalog.materialize(cache, "snapmaker_u1")
        val (bedW, bedD) = SliceEngine.bedOf(File(dir, "machine.json").readText())
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val b = cube.bounds()
        val obj = ModelObject(1, "cube", cube, Transform.translate(bedW / 2 - (b[0] + b[3]) / 2, bedD / 2 - (b[1] + b[4]) / 2, 0.0))
        val job = Files.createTempDirectory("job").toFile()
        val text = SliceEngine.nativeRequest(SliceRequest(Project3mf(listOf(obj)), dir, QualityPreset.STANDARD, false, 15,
            listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF"))), job, File(job, "plate.gcode"))
        val lines = text.trim().lines().map { it.split('\t') }
        assertEquals(listOf("machine.json", "process.json", "filament.json"), lines.filter { it[0] == "profile" }.map { File(it[1]).name })
        assertEquals(mapOf("layer_height" to "0.2", "sparse_infill_density" to "15%", "enable_support" to "0"), lines.filter { it[0] == "set" }.associate { it[1] to it[2] })
        val o = lines.single { it[0] == "object" }
        assertEquals(0.0, o[2].toDouble(), 1e-6); assertEquals(0.0, o[3].toDouble(), 1e-6)
        assertEquals(0.0, o[4].toDouble(), 1e-9); assertEquals(1.0, o[5].toDouble(), 1e-9); assertEquals("0", o[6])
        assertEquals(cube.triangleCount, MeshIO.readStl(File(o[1]).readBytes()).triangleCount)
    }

    @Test fun nativeEngineIsFoundNextToTheAppFirstEvenWithoutItsExecutableBit() {
        // Packaged app resources lose the executable bit; the engine is still found (and made runnable).
        val resources = Files.createTempDirectory("res").toFile()
        val bundled = File(resources, SliceEngine.NATIVE_ENGINE_NAME).apply { writeText("#!/bin/sh\n"); setExecutable(false) }
        val env = mapOf("NOZZLE_HOME" to Files.createTempDirectory("home").toFile().absolutePath, "NOZZLE_ENGINE" to "/nonexistent")
        val found = SliceEngine.locateNative(env) { k -> if (k == "compose.application.resources.dir") resources.absolutePath else null }
        assertEquals(true, found != null && found.canExecute() && found.readText() == bundled.readText())
        assertEquals(true, SliceEngine.isNative(found!!))
    }
}
