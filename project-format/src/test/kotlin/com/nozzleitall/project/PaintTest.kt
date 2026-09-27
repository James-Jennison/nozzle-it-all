package com.nozzleitall.project

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaintTest {
    @Test fun decodesTheEngineEncoding() {
        // Strings from a real Bambu Studio file (FLEXI PANGOLIN) and from libslic3r's Model.cpp.
        assertEquals(Paint.Node.Leaf(2), Paint.decode("8"))
        assertEquals(Paint.Node.Leaf(3), Paint.decode("0C"))
        assertEquals(Paint.Node.Leaf(1), Paint.decode("4"))
        // Model.cpp's "two corners the same" case: result0 + result1 + result1 + "2" is a two-side split, children 2, 1, 1.
        val n = Paint.decode("8442") as Paint.Node.Split
        assertEquals(2, n.sides)
        assertEquals(listOf(Paint.Node.Leaf(2), Paint.Node.Leaf(1), Paint.Node.Leaf(1)), n.children) // result0 (the string's start) is child 0
        for (s in listOf("8", "0C", "4", "8442", "00088A808802303", "080858013", "8801883", "8090003"))
            assertEquals(s, Paint.encode(Paint.decode(s)))
    }

    @Test fun highStatesUseTheEscapeNibbles() {
        for (state in listOf(0, 1, 2, 3, 17, 18, 32, 33, 64, 255)) assertEquals(Paint.Node.Leaf(state), Paint.decode(Paint.whole(state)))
        assertEquals("0FFC", Paint.whole(33)) // Snapmaker Orca: a second 0b1111 chunk above 32
        assertEquals("0C", Paint.whole(3))
        assertEquals("0FC", Paint.whole(18))
    }

    @Test fun remapRenumbersEveryLeaf() {
        assertEquals("C", Paint.remap("8") { if (it == 2) 3 else it }!!.let { Paint.encode(Paint.Node.Leaf(Paint.states(Paint.decode(it)).single())) }.takeLast(1))
        assertNull(Paint.remap("4") { 0 }) // nothing left painted
        val split = Paint.decode("8442")
        val moved = Paint.decode(Paint.remap("8442") { it + 1 }!!)
        assertEquals(Paint.states(split).map { it + 1 }.toSet(), Paint.states(moved))
    }

    @Test fun leavesCoverTheTriangle() {
        val a = floatArrayOf(0f, 0f, 0f); val b = floatArrayOf(4f, 0f, 0f); val c = floatArrayOf(0f, 4f, 0f)
        fun area(p: FloatArray, q: FloatArray, r: FloatArray) = kotlin.math.abs((q[0] - p[0]) * (r[1] - p[1]) - (r[0] - p[0]) * (q[1] - p[1])) / 2
        for (s in listOf("8442", "00088A808802303", "080858013")) {
            var total = 0f; var n = 0
            Paint.leaves(Paint.decode(s), a, b, c) { p, q, r, _ -> total += area(p, q, r); n++ }
            assertEquals("$s covers the whole triangle", 8f, total, 1e-4f)
            assertTrue(n > 1)
        }
    }

    @Test fun malformedPaintIsRefused() {
        assertThrows(ProjectFormatException::class.java) { Paint.decode("Z") }
        assertThrows(ProjectFormatException::class.java) { Paint.decode("3") } // a split with no children
    }

    /** A Bambu Studio style file: a painted part, a second part set to filament 3, and the file's filament list. */
    @Test fun bambuPaintAndPartFilamentsAreRead() {
        val part = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
<object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/></vertices>
<triangles><triangle v1="0" v2="1" v3="2" paint_color="8"/><triangle v1="0" v2="1" v3="3"/></triangles></mesh></object>
<object id="3" type="model"><mesh><vertices><vertex x="20" y="0" z="0"/><vertex x="30" y="0" z="0"/><vertex x="20" y="10" z="0"/></vertices>
<triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources><build/></model>"""
        val root = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
<metadata name="Application">BambuStudio-02.03.01.51</metadata><resources>
<object id="2" type="model"><components><component p:path="/3D/Objects/o.model" objectid="1"/><component p:path="/3D/Objects/o.model" objectid="3"/></components></object>
</resources><build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 100 100 0"/></build></model>"""
        val settings = """<?xml version="1.0" encoding="UTF-8"?><config><object id="2"><metadata key="name" value="Pangolin"/><metadata key="extruder" value="1"/>
<part id="1" subtype="normal_part"><metadata key="extruder" value="1"/></part><part id="3" subtype="normal_part"><metadata key="extruder" value="3"/></part></object></config>"""
        val filaments = """{"filament_colour":["#F7E6DE","#9D432C","#000000"],"filament_type":["PLA","PLA","PLA"],"filament_settings_id":["Bambu PLA Basic @BBL X1C","Bambu PLA Basic @BBL X1C","Generic PLA"]}"""
        val p = ThreeMf.read(ByteArrayInputStream(zip("3D/3dmodel.model" to root, "3D/Objects/o.model" to part, "Metadata/model_settings.config" to settings,
            "Metadata/project_settings.config" to filaments)))
        val o = p.objects.single()
        assertEquals("Pangolin", o.name)
        assertEquals(1, o.filament)
        assertEquals(listOf("8", null, "0C"), o.mesh.paint!!.toList()) // painted, the object's own, the filament-3 part
        assertEquals(listOf("#F7E6DE", "#9D432C", "#000000"), p.filaments.map { it.colorHex })
        assertFalse("model_settings.config" in p.passthrough.keys.joinToString())
        // Saved by Nozzle and read back: the paint is in the geometry itself.
        val again = ThreeMf.read(ByteArrayInputStream(ThreeMf.write(p.copy(metadata = mapOf("Application" to "Nozzle It All test")))))
        assertEquals(listOf("8", null, "0C"), again.objects.single().mesh.paint!!.toList())
    }

    @Test fun prusaPaintAttributeIsRead() {
        val root = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:slic3rpe="http://schemas.slic3r.org/3mf/2017/06"><resources>
<object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/></vertices>
<triangles><triangle v1="0" v2="1" v3="2" slic3rpe:mmu_segmentation="8"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>"""
        assertEquals("8", ThreeMf.read(ByteArrayInputStream(zip("3D/3dmodel.model" to root))).objects.single().mesh.paint!![0])
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
}

/** Bambu Studio stores preview images uncompressed with a trailing data descriptor; those files must open. */
class StoredEntryTest {
    @Test fun storedEntriesWithADataDescriptorOpen() {
        val model = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>
<object id="1" type="model"><mesh><vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/></vertices>
<triangles><triangle v1="0" v2="1" v3="2"/></triangles></mesh></object></resources><build><item objectid="1"/></build></model>""".toByteArray()
        val png = ByteArray(300) { it.toByte() }
        val f = java.io.File.createTempFile("bambu", ".3mf").apply { deleteOnExit(); writeBytes(zipWithStoredDescriptor(listOf("3D/3dmodel.model" to model, "Metadata/plate_1.png" to png))) }
        // The streaming reader is what used to fail on this layout.
        assertThrows(Exception::class.java) { java.util.zip.ZipInputStream(f.inputStream()).use { z -> while (z.nextEntry != null) z.readBytes() } }
        val p = ThreeMf.read(f)
        assertEquals(1, p.objects.single().mesh.triangleCount)
        assertArrayEquals(png, p.passthrough["Metadata/plate_1.png"])
    }

    /** Every entry STORED, with general-purpose flag bit 3: sizes and CRC are zero in the local header and follow the data. */
    private fun zipWithStoredDescriptor(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val central = java.io.ByteArrayOutputStream()
        fun le(o: java.io.OutputStream, v: Long, n: Int) { for (i in 0 until n) o.write(((v shr (8 * i)) and 0xFF).toInt()) }
        for ((name, data) in entries) {
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val offset = out.size().toLong(); val nb = name.toByteArray()
            le(out, 0x04034b50, 4); le(out, 20, 2); le(out, 8, 2); le(out, 0, 2); le(out, 0, 4); le(out, 0, 4); le(out, 0, 4); le(out, 0, 4); le(out, nb.size.toLong(), 2); le(out, 0, 2)
            out.write(nb); out.write(data)
            le(out, 0x08074b50, 4); le(out, crc, 4); le(out, data.size.toLong(), 4); le(out, data.size.toLong(), 4)
            le(central, 0x02014b50, 4); le(central, 20, 2); le(central, 20, 2); le(central, 8, 2); le(central, 0, 2); le(central, 0, 4)
            le(central, crc, 4); le(central, data.size.toLong(), 4); le(central, data.size.toLong(), 4); le(central, nb.size.toLong(), 2)
            le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 2); le(central, 0, 4); le(central, offset, 4); central.write(nb)
        }
        val cdOffset = out.size().toLong(); val cd = central.toByteArray(); out.write(cd)
        le(out, 0x06054b50, 4); le(out, 0, 2); le(out, 0, 2); le(out, entries.size.toLong(), 2); le(out, entries.size.toLong(), 2)
        le(out, cd.size.toLong(), 4); le(out, cdOffset, 4); le(out, 0, 2)
        return out.toByteArray()
    }
}
