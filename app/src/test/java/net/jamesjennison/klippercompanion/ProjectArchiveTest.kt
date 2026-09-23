package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.project.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }; return out.toByteArray()
    }
    private fun manifest(objects: String = """[{"id":"o1","file":"a.stl","plateId":"p1","offsetXMm":1.5,"offsetYMm":-2,"rotationZDeg":90,"scale":1.25,"materialId":null,"materialDisplayName":"PLA","materialTempNozzleC":210,"materialTempBedC":60,"toolSlotIndex":2}]""", plates: String = """[{"id":"p1","name":"Plate 1","position":0}]""") =
        """{"format":1,"name":"My part","plates":$plates,"objects":$objects}""".toByteArray()
    private fun read(bytes: ByteArray) = ProjectArchive.read(ByteArrayInputStream(bytes), Files.createTempDirectory("pa").toFile())
    private fun invalid(bytes: ByteArray): String = assertThrows(InvalidProjectArchive::class.java) { read(bytes) }.message!!

    @Test fun roundTripsPlatesObjectsTransformsAndMaterials() {
        val dir = Files.createTempDirectory("pa-src").toFile()
        val f1 = java.io.File(dir, "a.stl").apply { writeText("solid a") }; val f2 = java.io.File(dir, "b.3mf").apply { writeText("b") }
        val plates = listOf(Plate("p1", "proj", 0, "Plate 1"), Plate("p2", "proj", 1, "Plate 2"))
        val objects = listOf(
            ProjectObject("o1", "proj", f1.toURI().toString(), "p1", 1f, 2f, 45f, 1.5f, "m", "PETG", 240, 80, 1),
            ProjectObject("o2", "proj", f1.toURI().toString(), "p2"), // duplicate sharing a file
            ProjectObject("o3", "proj", f2.toURI().toString(), null),
        )
        val out = ByteArrayOutputStream(); ProjectArchive.write("Proj", plates, objects, { java.io.File(java.net.URI(it.sourceFileUri)) }, out)
        val outDir = Files.createTempDirectory("pa-out").toFile()
        val parsed = ProjectArchive.read(ByteArrayInputStream(out.toByteArray()), outDir)
        assertEquals("Proj", parsed.name); assertEquals(listOf("Plate 1", "Plate 2"), parsed.plates.map { it.name })
        assertEquals(3, parsed.objects.size)
        with(parsed.objects[0]) { assertEquals("p1", plateId); assertEquals(45f, rotationZDeg); assertEquals(1.5f, scale); assertEquals("PETG", materialDisplayName); assertEquals(240, materialTempNozzleC); assertEquals(1, toolSlotIndex) }
        assertNull(parsed.objects[2].plateId)
        assertEquals("solid a", java.io.File(outDir, "a.stl").readText()); assertEquals(setOf("a.stl", "b.3mf"), outDir.list()!!.toSet())
    }

    @Test fun acceptsAWellFormedArchive() { assertEquals(1, read(zip("project.json" to manifest(), "models/a.stl" to "x".toByteArray())).objects.size) }

    @Test fun rejectsPathTraversalAndUnexpectedEntries() {
        for (bad in listOf("models/../evil.stl", "../evil.stl", "/abs/evil.stl", "models/sub/a.stl", "models/a.exe", "models/.hidden.stl", "other.txt", "models/a b.stl"))
            invalid(zip("project.json" to manifest(), bad to byteArrayOf(1)))
    }

    @Test fun rejectsBrokenManifests() {
        val model = "models/a.stl" to "x".toByteArray()
        assertEquals("Missing project.json.", invalid(zip(model)))
        assertEquals("Unreadable project.json.", invalid(zip("project.json" to "not json".toByteArray(), model)))
        assertEquals("Unsupported project format.", invalid(zip("project.json" to """{"format":2,"objects":[]}""".toByteArray(), model)))
        assertEquals("Object refers to a missing model file.", invalid(zip("project.json" to manifest(), "models/zzz.stl" to byteArrayOf(1))))
        assertEquals("Object refers to a missing plate.", invalid(zip("project.json" to manifest(plates = "[]"), model)))
        assertEquals("Invalid scale.", invalid(zip("project.json" to manifest(objects = """[{"id":"o","file":"a.stl","plateId":null,"offsetXMm":0,"offsetYMm":0,"rotationZDeg":0,"scale":0}]"""), model)))
        assertTrue(invalid(zip("project.json" to manifest(objects = """[{"id":"o","file":"a.stl","plateId":null,"offsetXMm":1e30,"offsetYMm":0,"rotationZDeg":0,"scale":1}]"""), model)).startsWith("Invalid number"))
    }

}
