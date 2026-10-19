package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Mesh arrays are owned by their Mesh and replaced, not mutated, by editing commands.
 * Mesh has identity equality. Presentation changes deliberately do not invalidate this key. */
internal data class DisplayKey(val mesh: Mesh, val kind: Int, val budget: Int = 150_000)
internal fun displayKeys(objects: List<PlateObject>) = objects.map { DisplayKey(it.mesh, it.paintKind) }.distinct()

/** A tagged result cannot be drawn or used for painting against another source snapshot,
 * even in the composition before LaunchedEffect has cancelled the previous request. */
internal data class PreparedDisplays(val keys: List<DisplayKey>, val displays: Map<DisplayKey, DisplayMesh>) {
    fun bind(objects: List<PlateObject>): List<Pair<PlateObject, DisplayMesh>> =
        if (displayKeys(objects) != keys) emptyList() else objects.map { it to displays.getValue(DisplayKey(it.mesh, it.paintKind)) }
}

// One CPU preparation at a time across all viewports. Cancelled queued requests do not
// start CPU work; no per-edit executor/thread or unbounded parallel copies of meshes.
private val preparationDispatcher = Dispatchers.Default.limitedParallelism(1)

internal suspend fun prepareDisplays(keys: List<DisplayKey>,
    compute: (DisplayKey, () -> Unit) -> DisplayMesh = { key, check ->
        displayMesh(key.mesh, key.budget, key.kind, check).also { check(); it.lightingGroups }
    }): PreparedDisplays = withContext(preparationDispatcher) {
    val context = coroutineContext
    val check = { context.ensureActive() }
    check()
    preparationDiagnostic("started", keys.size)
    try {
        val values = keys.associateWith { key -> check(); compute(key, check).also { check() } }
        PreparedDisplays(keys, values)
    } finally { preparationDiagnostic("worker-ended", keys.size) }
}

/** Opt-in, metadata-only local acceptance diagnostics; no model names, paths or content. */
internal fun preparationDiagnostic(event: String, count: Int) {
    if (System.getenv("NOZZLE_VIEWPORT_TIMING") == "1")
        println("NIA_PREPARATION event=$event objects=$count time_ns=${System.nanoTime()} ui=${javax.swing.SwingUtilities.isEventDispatchThread()}")
}
