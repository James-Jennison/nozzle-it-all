// Adapted in part from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later, whose
// Bespok3dBootstrapPackagesTest.kt exercises the same asset-verification contract (case
// selection and the appProjectDir asset-lookup trick only; the bundle format itself differs,
// see Bespok3dBootstrapPackages.kt's header).
package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class Bespok3dBootstrapPackagesTest {
    private fun bundleBytes(): ByteArray {
        val candidates = listOf(File("src/main/assets/${Bespok3dBootstrapPackages.ASSET_PATH}"), File("app/src/main/assets/${Bespok3dBootstrapPackages.ASSET_PATH}"))
        return (candidates.firstOrNull { it.exists() } ?: throw IllegalStateException("Bootstrap asset not found at any of $candidates")).readBytes()
    }
    @Test fun verifiesTheCommittedBundleAndEveryPayloadHash() {
        val packages = Bespok3dBootstrapPackages.load(ByteArrayInputStream(bundleBytes()))
        assertEquals("bespok3d-daemon", packages.daemon.name)
        assertTrue(packages.daemon.files.isNotEmpty())
        assertEquals("bespok3d-jinni-snapmaker-u1", packages.jinni.name)
        assertTrue(packages.jinni.files.isNotEmpty())
        (packages.daemon.files + packages.jinni.files).forEach { file ->
            assertTrue(file.path.isNotBlank())
            assertTrue(file.bytes.isNotEmpty())
            assertTrue(file.mode in 0..0b111_111_111)
        }
    }
    @Test fun rejectsAnyChangeToTheBundleBytes() {
        val corrupted = bundleBytes()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { Bespok3dBootstrapPackages.load(ByteArrayInputStream(corrupted)) }
    }
    @Test fun rejectsTraversalAbsoluteWindowsAndAmbiguousPaths() {
        for (path in listOf("../escape", "dir/../escape", "/absolute", "C:\\escape", "dir//file", "./file"))
            assertThrows(IllegalArgumentException::class.java) { Bespok3dBootstrapPackages.validateRelativePath(path) }
        assertEquals("wheels/package.whl", Bespok3dBootstrapPackages.validateRelativePath("wheels/package.whl"))
    }
}
