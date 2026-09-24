package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MmfArchiveTest {
    private fun zip(vararg e: Pair<String, ByteArray>): File = File.createTempFile("mmf", ".zip").also { f ->
        ZipOutputStream(f.outputStream()).use { z -> e.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }
    private fun dir() = Files.createTempDirectory("mmfx").toFile()

    @Test fun keepsOnlyModelsFlattensPathsAndSkipsJunk() {
        val out = MmfArchive.extractModels(zip("bust/head.STL" to byteArrayOf(1, 2), "bust/../../evil.stl" to byteArrayOf(3), "__MACOSX/._head.stl" to byteArrayOf(9), "readme.txt" to byteArrayOf(1), "render.png" to byteArrayOf(1), "a b/part (1).3mf" to byteArrayOf(4), "obj/x.obj" to byteArrayOf(5)), dir())
        assertEquals(setOf("head.STL", "evil.stl", "part__1_.3mf", "x.obj"), out.map { it.name }.toSet())
        out.forEach { assertTrue("stays inside the target directory", it.parentFile.canonicalFile == out[0].parentFile.canonicalFile) }
    }

    @Test fun duplicateNamesGetUniqueSuffixesAndEmptyFilesAreDropped() {
        val out = MmfArchive.extractModels(zip("a/part.stl" to byteArrayOf(1), "b/part.stl" to byteArrayOf(2), "c/empty.stl" to ByteArray(0)), dir())
        assertEquals(2, out.size); assertEquals(2, out.map { it.name }.toSet().size)
    }

    @Test fun tooManyModelsAndOversizeAreRefused() {
        val many = (1..MmfArchive.MAX_MODELS + 1).map { "m$it.stl" to byteArrayOf(1) }.toTypedArray()
        assertThrows(MmfException.UnsafeDownload::class.java) { MmfArchive.extractModels(zip(*many), dir()) }
        val notZip = File.createTempFile("bad", ".zip").apply { writeText("not a zip") }
        assertTrue("a non-zip yields no models rather than crashing", MmfArchive.extractModels(notZip, dir()).isEmpty())
    }
}
