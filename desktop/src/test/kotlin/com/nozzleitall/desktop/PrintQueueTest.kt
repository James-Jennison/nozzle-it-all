package com.nozzleitall.desktop

import com.nozzleitall.desktop.queue.PrintQueue
import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrintQueueTest {
    private fun tmp() = Files.createTempDirectory("queue").toFile()
    private fun gcode(dir: File, text: String = "G28\n") = File.createTempFile("job", ".gcode", dir).apply { writeText(text) }

    @Test fun anItemKeepsItsOwnCopyOfTheFileInOrderAndSurvivesARestart() {
        val d = tmp(); val src = gcode(d, "G1 X1\n")
        val q = PrintQueue(File(d, "q"))
        val a = q.add("Possum", src, "p1", listOf(0, 1)); val b = q.add("Cube", gcode(d), null)
        src.delete()   // the original can go away
        assertEquals("G1 X1\n", q.file(a).readText())
        assertEquals(listOf("Possum", "Cube"), q.items().map { it.name })
        val again = PrintQueue(File(d, "q"))
        assertEquals(listOf(a.id, b.id), again.items().map { it.id }); assertEquals(listOf(0, 1), again.items()[0].toolheadMap); assertNull(again.items()[1].printerId)
    }

    @Test fun moveRemoveAndRetarget() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        val a = q.add("A", gcode(d), null); val b = q.add("B", gcode(d), null); val c = q.add("C", gcode(d), null)
        q.move(c.id, -2); assertEquals(listOf("C", "A", "B"), q.items().map { it.name })
        q.move(c.id, -5); assertEquals("stays inside the list", listOf("C", "A", "B"), q.items().map { it.name })
        q.retarget(a.id, "p9"); assertEquals("p9", q.items().first { it.id == a.id }.printerId)
        val file = q.file(b); q.remove(b.id)
        assertFalse("removing deletes the stored file", file.exists()); assertEquals(listOf("C", "A"), q.items().map { it.name })
    }

    @Test fun theNextItemForAPrinterIsItsOwnOrAnyOneInOrder() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        q.add("for-other", gcode(d), "other"); val any = q.add("any", gcode(d), null); q.add("for-me", gcode(d), "me")
        assertEquals(any.id, q.nextFor("me")!!.id)
        q.remove(any.id); assertEquals("for-me", q.nextFor("me")!!.name); assertEquals("for-other", q.nextFor("other")!!.name)
        assertNull(q.nextFor("nobody"))
    }

    @Test fun aRemovedPrintersItemsFallBackToAny() {
        val d = tmp(); val q = PrintQueue(File(d, "q"))
        q.add("x", gcode(d), "gone"); q.forgetPrinter("gone"); assertNull(q.items()[0].printerId)
    }

    @Test fun anIndexedItemWhoseFileIsMissingIsDropped() {
        val d = tmp(); val q = PrintQueue(File(d, "q")); val a = q.add("x", gcode(d), null)
        q.file(a).delete(); assertTrue(PrintQueue(File(d, "q")).items().isEmpty())
    }

    @Test fun aPrinterThatFinishesWithSomethingQueuedIsAnnouncedOnceAndOnlyWhenWorkingBefore() {
        val t = tmp(); val paths = AppPaths(File(t, "c"), File(t, "d"), File(t, "k")).ensure()
        val fleet = Fleet(paths, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), stockHelper = { null })
        val sent = mutableListOf<Pair<String, String>>(); fleet.notifier = { a, b -> sent += a to b }
        val entry = PrinterEntry(PrinterConfig(PrinterIdentity("p1", "CC1", "Elegoo CC1", PrinterFamily.KLIPPER, "http://127.0.0.1:1"), "moonraker"))
        val ready = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN)
        val busy = PrinterStatus(PrinterState.PRINTING, ConnectionRoute.LAN, job = JobProgress("a.gcode", 0.5f))
        fleet.enqueue("Possum", gcode(t), "p1", emptyList())
        fleet.noteFree(entry, ready)                  // idle at start: nothing to say
        assertTrue(sent.isEmpty())
        fleet.noteFree(entry, busy); fleet.noteFree(entry, PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN)); fleet.noteFree(entry, ready)
        assertEquals(1, sent.size); assertEquals("CC1 is free", sent[0].first); assertTrue(sent[0].second.contains("Possum"))
        fleet.noteFree(entry, ready); assertEquals("not again while it stays free", 1, sent.size)
        fleet.shutdown()
    }
}
