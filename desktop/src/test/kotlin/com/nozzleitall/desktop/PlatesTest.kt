package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Plates: Orca's layout, other slicers' plates read, and a multi-plate project saved and opened again. */
class PlatesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    @Test fun orcaPlateLayout() {
        // compute_colum_count: 1 -> 1, 2 -> 2, 4 -> 2, 5 -> 3, 9 -> 3, 10 -> 4.
        assertEquals(listOf(1, 2, 2, 3, 3, 4), listOf(1, 2, 4, 5, 9, 10).map { Plates.columns(it) })
        assertEquals(0.0 to 0.0, Plates.origin(0, 4, 270.0, 270.0))
        assertEquals(324.0 to 0.0, Plates.origin(1, 4, 270.0, 270.0))
        assertEquals(0.0 to -324.0, Plates.origin(2, 4, 270.0, 270.0))
    }

    @Test fun anotherSlicersPlatesAreRead() {
        val model = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
<object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices>
<triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="0" v2="3" v3="2"/></triangles></mesh></object>
<object id="2" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices>
<triangles><triangle v1="0" v2="2" v3="1"/><triangle v1="0" v2="1" v3="3"/><triangle v1="1" v2="2" v3="3"/><triangle v1="0" v2="3" v3="2"/></triangles></mesh></object>
</resources><build><item objectid="1" transform="1 0 0 0 1 0 0 0 1 100 100 0"/><item objectid="2" transform="1 0 0 0 1 0 0 0 1 424 100 0"/></build></model>"""
        val settings = """<?xml version="1.0" encoding="UTF-8"?><config><object id="1"><metadata key="name" value="a"/></object><object id="2"><metadata key="name" value="b"/></object>
<plate><metadata key="plater_id" value="1"/><metadata key="plater_name" value="Front"/><model_instance><metadata key="object_id" value="1"/><metadata key="instance_id" value="0"/></model_instance></plate>
<plate><metadata key="plater_id" value="2"/><metadata key="plater_name" value=""/><model_instance><metadata key="object_id" value="2"/><metadata key="instance_id" value="0"/></model_instance></plate></config>"""
        val bytes = java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { z ->
            for ((n, t) in listOf("3D/3dmodel.model" to model, "Metadata/model_settings.config" to settings, "Metadata/project_settings.config" to """{"printable_area":["0x0","270x0","270x270","0x270"]}""")) {
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } } }.toByteArray()
        val p = ThreeMf.read(ByteArrayInputStream(bytes))
        assertEquals(listOf(0, 1), p.objects.map { it.plate })
        assertEquals(listOf("Front", ""), p.plateNames)
        assertEquals(270.0 to 270.0, p.sourceBed)
    }

    @Test fun multiPlateProjectRoundTrips() {
        val tmp = Files.createTempDirectory("plates").toFile()
        val app = AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val p = app.prepare
        val stl = File(tmp, "cube.stl").apply { writeBytes(SliceEngine.stlBytes(cube)) }
        p.items += PrepItem(1, "front", cube, 100f, 120f)
        p.addPlate(); p.renamePlate(1, "Spares")
        p.items += PrepItem(2, "spare", cube, 60f, 70f, plate = 1).also { it.settings["wall_loops"] = "4" }
        p.currentPlate = 0
        // Slicing takes the plate shown, on its own bed.
        assertEquals(listOf(1), p.toProject(forSlice = true).objects.map { it.id })
        val saved = p.toProject()
        // Saved where Orca lays plates side by side, and recorded for Orca too.
        val spare = saved.objects.first { it.id == 2 }
        val (ox, _) = Plates.origin(1, 2, p.bed.first.toDouble(), p.bed.second.toDouble())
        assertEquals(60.0 + ox, spare.placement.apply((cube.bounds()[0] + cube.bounds()[3]) / 2.0, (cube.bounds()[1] + cube.bounds()[4]) / 2.0, 0.0)[0], 1e-6)
        val file = File(tmp, "p.3mf").apply { writeBytes(ThreeMf.write(saved)) }
        val xml = java.util.zip.ZipFile(file).use { z -> String(z.getInputStream(z.getEntry("Metadata/model_settings.config")).readBytes()) }
        assertTrue(xml.contains("plater_name\" value=\"Spares\"") && xml.contains("wall_loops\" value=\"4\""))
        // Opened again: two plates with their names, each object back on its own plate at its own place.
        p.open(file)
        assertEquals(listOf("Plate 1", "Spares"), p.plateNames.toList())
        val back = p.items.associateBy { it.id }
        assertEquals(0, back[1]!!.plate); assertEquals(1, back[2]!!.plate)
        assertEquals(60f, back[2]!!.x, 1e-3f); assertEquals(70f, back[2]!!.y, 1e-3f)
        assertEquals("4", back[2]!!.settings["wall_loops"])
        // A cube is closed: 8000 mm³, no open edges.
        assertEquals(8000.0, back[1]!!.volume, 1.0); assertEquals(0, back[1]!!.openEdges)
        stl.delete()
    }
}
