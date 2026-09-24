package net.jamesjennison.klippercompanion

// Phase 9S: the cut needs the native engine, so it lives in the app module as an extension of the pure MeshEdit.
/** Cuts with the horizontal plane at local height [z] (native, capped). Either half is null when the plane misses it. */
fun MeshEdit.cut(m: TriMesh, z: Float): Pair<TriMesh?, TriMesh?> {
    val (upper, lower) = org.orcaslicer.engine.NativeEngine.nativeCutMesh(m.v, z).let { it[0] to it[1] }
    return upper.takeIf { it.isNotEmpty() }?.let { onBed(TriMesh(it)) } to lower.takeIf { it.isNotEmpty() }?.let { onBed(TriMesh(it)) }
}

