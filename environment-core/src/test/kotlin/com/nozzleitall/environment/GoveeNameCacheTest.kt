package com.nozzleitall.environment

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GoveeNameCacheTest {
    @Test fun namesSurviveRestartWithoutAnyCredentialAndForgetLeavesAnEmptyDirectory() {
        val file = Files.createTempDirectory("nia-name-cache").resolve("directory.json").toFile()
        val names = listOf(GoveeDeviceName("FF:EE:DD:CC:BB:AA:11:22", "H5100", "Front left"))
        assertTrue(GoveeNameCache(file).read().isEmpty())
        GoveeNameCache(file).write(names)
        assertEquals(names, GoveeNameCache(file).read())
        assertFalse(file.readText().contains("api-key"))
        GoveeNameCache(file).write(emptyList())
        assertTrue(GoveeNameCache(file).read().isEmpty())
    }
    @Test fun cancellationBeforeAtomicCommitPreservesPreviousDirectory() {
        val file = Files.createTempDirectory("nia-name-cache-cancel").resolve("directory.json").toFile()
        val cache = GoveeNameCache(file)
        val old = listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Old name"))
        cache.write(old)
        assertEquals(GoveeNameFailure.CANCELLED, assertThrows(GoveeNameException::class.java) {
            cache.write(old.map { it.copy(name = "New name") }) { throw GoveeNameException(GoveeNameFailure.CANCELLED) }
        }.reason)
        assertEquals(old, cache.read())
        assertEquals(listOf("directory.json"), file.parentFile.list()!!.toList())
    }
    @Test fun corruptOversizedAndInvalidDirectoriesFailClosedButExplicitImportCanRepair() {
        val file = Files.createTempDirectory("nia-name-cache-invalid").resolve("directory.json").toFile()
        val cache = GoveeNameCache(file)
        for (text in listOf("not-json", " ".repeat(GoveeNameApi.MAX_BYTES + 1))) {
            file.writeText(text)
            assertEquals(GoveeNameFailure.DIRECTORY, assertThrows(GoveeNameException::class.java) { cache.read() }.reason)
        }
        cache.write(emptyList()); assertTrue(cache.read().isEmpty())
        assertThrows(GoveeNameException::class.java) { cache.write(listOf(GoveeDeviceName("x", "H5100", "bad\nname"))) }
        assertTrue(cache.read().isEmpty())
    }
}
