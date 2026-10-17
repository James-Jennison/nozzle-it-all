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

/** Undo and redo of what is on the plates. */
class HistoryTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())

    private fun app(): AppState {
        val tmp = Files.createTempDirectory("history").toFile()
        return AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
    private fun PrepareState.edit(f: () -> Unit) { f(); changed() }

    @Test fun anEditCanBeUndoneAndRedone() {
        val p = app().prepare; p.history.mergeWithinMillis = -1
        assertFalse(p.history.canUndo); assertFalse(p.history.canRedo)
        p.edit { p.items += PrepItem(1, "cube", cube, 50f, 50f) }
        assertTrue(p.history.canUndo)
        p.edit { p.items[0].x = 120f; p.items[0].settings["wall_loops"] = "4" }
        p.history.undo()
        assertEquals(50f, p.items.single().x, 0f); assertNull(p.items.single().settings["wall_loops"]); assertTrue(p.history.canRedo)
        p.history.undo(); assertTrue(p.items.isEmpty()); assertFalse(p.history.canUndo)
        p.history.redo(); p.history.redo()
        assertEquals(120f, p.items.single().x, 0f); assertEquals("4", p.items.single().settings["wall_loops"]); assertFalse(p.history.canRedo)
    }

    @Test fun aNewEditDropsWhatCouldHaveBeenRedone() {
        val p = app().prepare; p.history.mergeWithinMillis = -1
        p.edit { p.items += PrepItem(1, "cube", cube, 50f, 50f) }
        p.edit { p.items[0].x = 80f }
        p.history.undo(); assertTrue(p.history.canRedo)
        p.edit { p.items[0].y = 70f }
        assertFalse(p.history.canRedo); assertEquals(50f, p.items[0].x, 0f); assertEquals(70f, p.items[0].y, 0f)
    }

    @Test fun editsInQuickSuccessionAreOneStep() {
        val p = app().prepare   // the default window: a drag's many small moves undo together
        p.edit { p.items += PrepItem(1, "cube", cube, 50f, 50f) }
        Thread.sleep(450)
        for (x in 51..60) p.edit { p.items[0].x = x.toFloat() }
        p.history.undo()
        assertEquals(50f, p.items.single().x, 0f)
    }

    @Test fun plateOverridesAndSelectionComeBack() {
        val p = app().prepare; p.history.mergeWithinMillis = -1
        p.edit { p.items += PrepItem(1, "a", cube, 50f, 50f); p.items += PrepItem(2, "b", cube, 120f, 50f); p.selected = 2 }
        p.edit { p.overrides["wall_loops"] = "5" }
        p.addPlate()   // marks the project changed itself
        p.history.undo()
        assertEquals(1, p.plateNames.size); assertEquals("5", p.overrides["wall_loops"]); assertEquals(2, p.selected)
        p.history.undo()
        assertNull(p.overrides["wall_loops"]); assertEquals(2, p.selected); assertEquals(listOf(1, 2), p.items.map { it.id })
        p.history.redo(); p.history.redo()
        assertEquals(2, p.plateNames.size); assertEquals("5", p.overrides["wall_loops"])
    }

    @Test fun anOpenedOrNewProjectStartsWithNothingToUndo() {
        val p = app().prepare; p.history.mergeWithinMillis = -1
        p.edit { p.items += PrepItem(1, "cube", cube, 50f, 50f) }
        assertTrue(p.history.canUndo)
        p.newProject(); assertFalse(p.history.canUndo); assertFalse(p.history.canRedo)
    }

    @Test fun editMenuHasUndoAndRedoThatFollowTheHistory() {
        val a = app(); val p = a.prepare; p.history.mergeWithinMillis = -1
        fun item(l: String) = appMenus(a) {}.first { it.title == "Edit" }.items.filterNotNull().first { it.label == l }
        assertFalse(item("Undo").enabled); assertFalse(item("Redo").enabled)
        p.edit { p.items += PrepItem(1, "cube", cube, 50f, 50f) }
        assertTrue(item("Undo").enabled); item("Undo").run(); assertTrue(p.items.isEmpty()); assertTrue(item("Redo").enabled)
        item("Redo").run(); assertEquals(1, p.items.size)
        // Undo and Redo wait for the focused control: Ctrl+Z in a text field undoes the typing, not the plate.
        assertTrue(item("Undo").shortcut!!.afterFocus); assertTrue(item("Redo").shortcut!!.afterFocus)
    }
}
