package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.project.UndoHistory
import org.junit.Assert.*
import org.junit.Test

class UndoHistoryTest {
    private var now = 0L
    private fun history(limit: Int = 50) = UndoHistory<Int>(limit) { now }
    @Test fun undoRedoWalkTheTimelineAndNewEditsDropRedo() {
        val h = history(); h.record(1); h.record(2)
        assertEquals(2, h.undo(3)); assertEquals(1, h.undo(2)); assertNull(h.undo(1))
        assertEquals(2, h.redo(1)); assertTrue(h.canRedo)
        h.record(2); assertFalse(h.canRedo)
    }
    @Test fun gestureUpdatesCoalesceWithinTheWindowOnly() {
        val h = history(); now = 0; h.record(10, "a"); now = 300; h.record(11, "a"); now = 600; h.record(12, "a")
        assertEquals(10, h.undo(13)); assertNull(h.undo(10)) // one step
        now = 5000; h.record(20, "a"); now = 5100; h.record(21, "b"); assertEquals(21, h.undo(22)); assertEquals(20, h.undo(21))
        now = 9000; h.record(30, "a"); now = 9900; h.record(31, "a") // window exceeded -> separate
        assertEquals(31, h.undo(32)); assertEquals(30, h.undo(31))
    }
    @Test fun historyIsBounded() {
        val h = history(3); (1..10).forEach { h.record(it) }
        var count = 0; var cur = 11; while (true) { cur = h.undo(cur) ?: break; count++ }
        assertEquals(3, count); assertEquals(8, cur)
    }
    @Test fun clearForgetsEverything() { val h = history(); h.record(1); h.clear(); assertFalse(h.canUndo); assertFalse(h.canRedo) }
}
