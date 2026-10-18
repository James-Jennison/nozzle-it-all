package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.MeshIO
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MultiSelectionTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
    private fun app(): AppState {
        val tmp = Files.createTempDirectory("multi-selection").toFile()
        return AppState(AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure(), CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { a ->
            val p = a.prepare
            p.items.addAll(listOf(PrepItem(1, "one", cube, 40f, 50f), PrepItem(2, "two", cube, 80f, 50f), PrepItem(3, "three", cube, 120f, 50f), PrepItem(4, "other plate", cube, 40f, 50f, plate = 1)))
            p.plateNames += "Plate 2"; p.history.reset()
        }
    }
    @Test fun toggleRangeAndSelectAllStayOnCurrentPlate() {
        val p = app().prepare
        p.selectObject(1); p.selectObject(3, range = true, listRange = true)
        assertEquals(setOf(1, 2, 3), p.selectedIds); assertEquals(3, p.selected)
        p.selectObject(2, toggle = true); assertEquals(setOf(1, 3), p.selectedIds)
        p.selectObject(2, range = true); assertEquals(setOf(1, 2, 3), p.selectedIds)
        p.deselect(); p.selectAll(); assertEquals(setOf(1, 2, 3), p.selectedIds)
        assertFalse(p.dirty); assertFalse(p.history.canUndo)
        p.showPlate(1); assertTrue(p.selectedIds.isEmpty()); p.selectAll(); assertEquals(setOf(4), p.selectedIds)
        p.selected = 1; assertTrue(p.selectedIds.isEmpty())
    }
    @Test fun groupMoveUsesBaselineAndOneUndoRestoresSelection() {
        val p = app().prepare; p.selectObject(1); p.selectObject(2, toggle = true)
        p.beginSelectionTransform(1)
        p.moveSelection(1, 50f, 60f); p.moveSelection(1, 60f, 70f)
        p.endSelectionTransform()
        assertEquals(60f, p.items[0].x, 0f); assertEquals(100f, p.items[1].x, 0f)
        assertEquals(120f, p.items[2].x, 0f)
        p.history.undo(); assertEquals(40f, p.items[0].x, 0f); assertEquals(setOf(1, 2), p.selectedIds)
        assertFalse(p.history.canUndo)
        p.history.redo(); assertEquals(100f, p.items[1].x, 0f)
    }
    @Test fun togglingAnchorOffStillAnchorsTheNextRangeAtThatRow() {
        val p = app().prepare
        p.selectObject(1); p.selectObject(3, toggle = true); p.selectObject(3, toggle = true)
        p.selectObject(2, range = true, listRange = true)
        assertEquals(setOf(2, 3), p.selectedIds)
    }
    @Test fun separateNumericCommitsRemainSeparateUndoSteps() {
        val p = app().prepare; p.selectAll()
        p.editSelection { p.moveSelection(1, 50f, 50f) }
        p.editSelection { p.moveSelection(1, 60f, 50f) }
        p.history.undo(); assertEquals(50f, p.items[0].x, 0f)
        p.history.undo(); assertEquals(40f, p.items[0].x, 0f)
    }
    @Test fun rotationAndScalePreserveTheGroupAroundItsCentre() {
        val p = app().prepare; p.selectObject(1); p.selectObject(2, toggle = true)
        p.beginSelectionTransform(1); p.turnSelection(1, 45f); p.turnSelection(1, 90f); p.endSelectionTransform()
        assertEquals(60f, p.items[0].x, 0.001f); assertEquals(30f, p.items[0].y, 0.001f)
        assertEquals(70f, p.items[1].y, 0.001f); assertEquals(90f, p.items[1].rotZ, 0.001f)
        p.beginSelectionTransform(1); p.resizeSelection(1, 1.5f); p.resizeSelection(1, 2f); p.endSelectionTransform()
        assertEquals(10f, p.items[0].y, 0.001f); assertEquals(90f, p.items[1].y, 0.001f)
        assertEquals(2f, p.items[0].scale, 0f); assertEquals(1f, p.items[2].scale, 0f)
        p.history.undo(); assertEquals(1f, p.items[0].scale, 0f); assertEquals(90f, p.items[0].rotZ, 0.001f)
    }
    @Test fun deleteAndDuplicateAreAtomicAndUndoRestoresIds() {
        val p = app().prepare; p.selectObject(1); p.selectObject(3, toggle = true)
        p.removeSelected(); assertEquals(listOf(2, 4), p.items.map { it.id }); assertTrue(p.selectedIds.isEmpty())
        p.history.undo(); assertEquals(setOf(1, 3), p.selectedIds)
        p.duplicateSelected(); assertEquals(6, p.items.size); assertEquals(2, p.selectedIds.size)
        assertEquals(listOf("one copy", "three copy"), p.selectedItems().map { it.name })
        p.history.undo(); assertEquals(4, p.items.size); assertEquals(setOf(1, 3), p.selectedIds)
    }
    @Test fun menusSelectCurrentPlateAndLetTextControlsHandleCtrlAFirst() {
        val a = app()
        fun action(label: String) = appMenus(a) {}.first { it.title == "Edit" }.items.filterNotNull().first { it.label == label }
        assertTrue(action("Select all").shortcut!!.afterFocus)
        action("Select all").run(); assertEquals(setOf(1, 2, 3), a.prepare.selectedIds)
        action("Deselect").run(); assertTrue(a.prepare.selectedIds.isEmpty())
        assertNull(action("Arrange plate").shortcut)
    }
}
