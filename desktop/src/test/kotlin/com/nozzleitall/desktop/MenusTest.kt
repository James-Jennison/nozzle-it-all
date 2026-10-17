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

/** The menu bar's Plate and View menus and the items added to File, Edit and Help. */
class MenusTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    private fun app(): AppState {
        val tmp = Files.createTempDirectory("menus").toFile()
        return AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    private fun AppState.item(menu: String, label: String) = appMenus(this) {}.first { it.title == menu }.items.filterNotNull().first { it.label == label }

    @Test fun theBarHasPlateAndViewMenusBetweenEditAndGo() {
        assertEquals(listOf("File", "Edit", "Plate", "View", "Go", "Help"), appMenus(app()) {}.map { it.title })
    }

    @Test fun plateMenuAddsMovesBetweenAndDeletesPlates() {
        val a = app(); val p = a.prepare
        assertFalse(a.item("Plate", "Delete this plate").enabled); assertFalse(a.item("Plate", "Next plate").enabled)
        a.item("Plate", "Add plate").run()
        assertEquals(2, p.plateNames.size); assertEquals(1, p.currentPlate)
        assertTrue(a.item("Plate", "Previous plate").enabled); assertFalse(a.item("Plate", "Next plate").enabled)
        a.item("Plate", "Previous plate").run(); assertEquals(0, p.currentPlate)
        a.item("Plate", "Delete this plate").run(); assertEquals(1, p.plateNames.size)
    }

    @Test fun viewMenuSetsTheCameraAndIsometricRestoresIt() {
        val a = app(); val c = a.prepare.camera
        a.item("View", "Top view").run(); assertEquals(0f, c.pitch, 0f)
        a.item("View", "Side view").run(); assertEquals(89f, c.pitch, 0f)
        val yaw = c.yaw; a.item("View", "Rotate view right").run(); assertEquals(yaw + 90f, c.yaw, 0f)
        a.item("View", "Isometric view").run(); assertEquals(-35f, c.yaw, 0f); assertEquals(55f, c.pitch, 0f)
    }

    @Test fun deleteAllClearsThePlateAndReloadNeedsASavedFile() {
        val a = app(); val p = a.prepare
        assertFalse(a.item("Edit", "Delete all").enabled); assertFalse(a.item("File", "Reload from disk").enabled)
        p.items += PrepItem(1, "cube", cube, 50f, 50f); p.items += PrepItem(2, "cube 2", cube, 90f, 50f)
        a.item("Edit", "Delete all").run()
        // The project has not been saved, so there is nothing to ask about; it just empties.
        assertTrue(p.items.isEmpty()); assertNull(p.selected)
    }

    @Test fun fileMenuListsTheNewestProjectsFirstAndOpensOne() {
        val a = app()
        assertTrue(appMenus(a) {}.first { it.title == "File" }.items.filterNotNull().none { it.label.startsWith("Recent: ") })
        val old = File(a.library.dir, "Old.3mf"); val new = File(a.library.dir, "New.3mf")
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), old); old.setLastModified(1_000_000)
        ThreeMf.writeAtomically(Project3mf(listOf(ModelObject(1, "cube", cube))), new); new.setLastModified(2_000_000)
        val recent = appMenus(a) {}.first { it.title == "File" }.items.filterNotNull().filter { it.label.startsWith("Recent: ") }
        assertEquals(listOf("Recent: New", "Recent: Old"), recent.map { it.label })
    }
}
