package com.nozzleitall.testgrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Things the JVM accepts but Android doesn't, or that only showed up on a real device. */
class PortabilityTest {
    /**
     * Android's regex engine is ICU, which reads "[:" at the start of a character-class item as a POSIX class name
     * ("[:alpha:]"). "[:-]" compiled on the JVM but crashed Test Mode on the first device run (Redactor's static
     * initializer, while building the bundle). Every regex in Test Grid code must write a leading colon as "\:".
     */
    @Test fun noRegexOpensACharacterClassWithAColon() {
        val dirs = listOf("test-grid/src/main", "app/src/main/java/net/jamesjennison/klippercompanion/testgrid").map { File(Support.root, it) }
        val offenders = dirs.flatMap { d -> d.walk().filter { it.extension == "kt" }.toList() }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if ("Regex(" in line && Regex("""(?<!\\)\[\:""").containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), offenders)
        // The fixed patterns still do their job.
        assertTrue(Redactor().text("mac 3c:22:fb:12:34:56 X-Api-Key: abc123def").let { !it.contains("3c:22") && !it.contains("abc123def") })
    }

    @Test fun multiPartOffsetsKeepThePartsWhereTheModelPutThem() {
        val bounds = listOf(AcceptanceModel.MULTI_A, AcceptanceModel.MULTI_B).map { StlGeometry.bounds(AcceptanceModel.stl(it)) }
        val offsets = StlGeometry.partOffsets(bounds)
        // Part A's stripes span x 0-30 (centre 15), part B's 10-40 (centre 25); together 0-40 (centre 20).
        assertEquals(-5.0, offsets[0].first, 1e-6); assertEquals(0.0, offsets[0].second, 1e-6)
        assertEquals(5.0, offsets[1].first, 1e-6); assertEquals(0.0, offsets[1].second, 1e-6)
    }
}
