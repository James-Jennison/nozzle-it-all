package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Orca's instances: another copy of an object that follows its settings, parts and paint and is saved as an instance. */
class InstancesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    private fun state(): Pair<PrepareState, File> {
        val tmp = Files.createTempDirectory("instances").toFile()
        val app = AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        return app.prepare to tmp
    }

    @Test fun anInstanceFollowsItsObjectAndKeepsItsOwnPlace() {
        val (p, _) = state()
        p.items += PrepItem(1, "cube", cube, 50f, 50f, slot = 2).also { it.settings["wall_loops"] = "3" }
        p.selected = 1; p.addInstance()
        val inst = p.items.single { it.instanceOf == 1 }
        assertEquals(2, inst.slot); assertEquals("3", inst.settings["wall_loops"]); assertSame(p.items[0].mesh, inst.mesh)
        // A change on the object reaches the instance; the instance's own turn and size stay its own.
        p.items[0].settings["wall_loops"] = "5"; p.items[0].slot = 3
        inst.rotZ = 45f; inst.sx = -1f
        p.changed()
        assertEquals("5", inst.settings["wall_loops"]); assertEquals(3, inst.slot); assertEquals(45f, inst.rotZ, 0f); assertEquals(-1f, inst.sx, 0f)
        // An instance of an instance still follows the original object.
        p.selected = inst.id; p.addInstance()
        assertEquals(1, p.items.last().instanceOf)
    }

    @Test fun removingTheObjectLeavesTheInstanceAnObjectInItsOwnRight() {
        val (p, _) = state()
        p.items += PrepItem(1, "cube", cube, 50f, 50f).also { it.settings["wall_loops"] = "3" }
        p.selected = 1; p.addInstance()
        p.selected = 1; p.removeSelected()
        val inst = p.items.single()
        assertNull(inst.instanceOf); assertEquals("3", inst.settings["wall_loops"])
    }

    @Test fun instancesAreSavedAndOpenedAgain() {
        val (p, tmp) = state()
        p.items += PrepItem(1, "cube", cube, 50f, 50f).also { it.settings["wall_loops"] = "3" }
        p.selected = 1; p.addInstance()
        val file = File(tmp, "i.3mf").apply { writeBytes(ThreeMf.write(p.toProject())) }
        p.items.clear()
        p.open(file)
        assertEquals(2, p.items.size)
        assertEquals(listOf<Int?>(null, 1), p.items.sortedBy { it.id }.map { it.instanceOf })
    }
}
