package com.nozzleitall.desktop.prepare

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.ProjectManifest
import com.nozzleitall.project.SourceFilament

/**
 * Undo and redo for what is on the plates: the objects (place, size, turn, filament, settings, parts, height ranges, paint),
 * the project's setting overrides, the plates and the selection. Every edit marks the project changed, and that records the
 * state it left behind. Edits in quick succession (dragging, typing) count as one step. Mixed filaments, the printer and
 * the slice are not part of it: a restored plate drops the slice, as any edit does.
 */
class PlateHistory(private val p: PrepareState) {
    private class ItemRecord(
        val id: Int, val name: String, val mesh: Mesh, val x: Float, val y: Float, val rotZ: Float, val scale: Float, val slot: Int,
        val paintSlots: List<Int>, val sources: List<SourceFilament>, val settings: Map<String, String>, val plate: Int,
        val sx: Float, val sy: Float, val sz: Float, val parts: List<ProjectManifest.ObjectPart>, val ranges: List<ProjectManifest.HeightRange>,
        val layerProfile: List<Double>, val ownFilament: Int?, val instanceOf: Int?,
    )
    private class Snapshot(val items: List<ItemRecord>, val overrides: Map<String, String>, val plateNames: List<String>, val currentPlate: Int, val selected: Int?, val nextId: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var current: Snapshot? = null
    private var lastRecorded = 0L
    private var restoring = false
    /** Edits closer together than this are one step. */
    var mergeWithinMillis = 400L

    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    private fun capture() = Snapshot(
        p.items.map { i -> ItemRecord(i.id, i.name, i.mesh, i.x, i.y, i.rotZ, i.scale, i.slot, i.paintSlots.toList(), i.sources, i.settings.toMap(), i.plate, i.sx, i.sy, i.sz,
            i.parts.toList(), i.ranges.toList(), i.layerProfile.toList(), i.ownFilament, i.instanceOf) },
        p.overrides.toMap(), p.plateNames.toList(), p.currentPlate, p.selected, p.nextItemId)

    /** The plate as it is now is the first step's starting point: a new or opened project has nothing to undo. */
    fun reset() { undoStack.clear(); redoStack.clear(); current = capture(); sync() }

    /** Called when the project changed: keeps the state before it for Undo. */
    fun recordChange(now: Long = System.currentTimeMillis()) {
        if (restoring) return
        val before = current
        if (before != null && now - lastRecorded > mergeWithinMillis) {
            undoStack.addLast(before); while (undoStack.size > LIMIT) undoStack.removeFirst()
            redoStack.clear()
        }
        current = capture(); lastRecorded = now; sync()
    }

    fun undo() { val to = undoStack.removeLastOrNull() ?: return; current?.let { redoStack.addLast(it) }; restore(to) }
    fun redo() { val to = redoStack.removeLastOrNull() ?: return; current?.let { undoStack.addLast(it) }; restore(to) }

    private fun restore(s: Snapshot) {
        restoring = true
        try {
            p.restorePlate(s.items.map { r ->
                PrepItem(r.id, r.name, r.mesh, r.x, r.y, r.rotZ, r.scale, r.slot, r.paintSlots, r.sources, r.settings, r.plate).also {
                    it.sx = r.sx; it.sy = r.sy; it.sz = r.sz; it.parts.addAll(r.parts); it.ranges.addAll(r.ranges); it.layerProfile.addAll(r.layerProfile)
                    it.ownFilament = r.ownFilament; it.instanceOf = r.instanceOf
                }
            }, s.overrides, s.plateNames, s.currentPlate, s.selected, s.nextId)
            current = s; lastRecorded = 0L
        } finally { restoring = false }
        sync()
    }

    private fun sync() { canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }
    companion object { const val LIMIT = 50 }
}
