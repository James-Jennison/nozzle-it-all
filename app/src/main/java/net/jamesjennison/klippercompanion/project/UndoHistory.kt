package net.jamesjennison.klippercompanion.project

// Phase 9S command/undo model: immutable-snapshot history. Every project mutation records the state it is
// about to replace; undo/redo swap snapshots. Snapshots are cheap because project state is immutable data.
class UndoHistory<T>(private val limit: Int = 50, private val clock: () -> Long = System::currentTimeMillis) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var coalesceKey: Any? = null
    private var lastRecordAt = 0L

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Record [before] as an undo point. Calls sharing a non-null [coalesceKey] within [windowMs] collapse into one point (gesture drags). */
    fun record(before: T, coalesceKey: Any? = null, windowMs: Long = 800) {
        val now = clock()
        val merge = coalesceKey != null && coalesceKey == this.coalesceKey && now - lastRecordAt <= windowMs && undoStack.isNotEmpty()
        lastRecordAt = now; this.coalesceKey = coalesceKey
        redoStack.clear()
        if (merge) return
        undoStack.addLast(before)
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current); coalesceKey = null
        return previous
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current); coalesceKey = null
        return next
    }

    fun clear() { undoStack.clear(); redoStack.clear(); coalesceKey = null }
}
