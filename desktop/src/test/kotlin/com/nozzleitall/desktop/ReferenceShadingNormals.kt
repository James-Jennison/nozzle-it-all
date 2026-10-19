package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import kotlin.math.sqrt

/** Area-weighted normals per triangle corner, excluding faces across a sharp crease.
 * Filtering the incident faces BEFORE averaging keeps flat caps flat: testing an already
 * averaged vertex normal can incorrectly accept a blend of perpendicular cap/side faces.
 * This is display-only; positions, topology, paint and slicing input are never modified.
 */
internal fun referenceCornerNormals(mesh: Mesh): FloatArray {
    val v = mesh.vertices; val t = mesh.triangles
    val faces = FloatArray(t.size)
    val lengths = FloatArray(mesh.triangleCount)
    val incident = Array(v.size / 3) { mutableListOf<Int>() }
    for (f in 0 until mesh.triangleCount) {
        val a = t[f*3]*3; val b = t[f*3+1]*3; val c = t[f*3+2]*3
        val ux = v[b]-v[a]; val uy = v[b+1]-v[a+1]; val uz = v[b+2]-v[a+2]
        val wx = v[c]-v[a]; val wy = v[c+1]-v[a+1]; val wz = v[c+2]-v[a+2]
        val nx = uy*wz-uz*wy; val ny = uz*wx-ux*wz; val nz = ux*wy-uy*wx
        faces[f*3] = nx; faces[f*3+1] = ny; faces[f*3+2] = nz
        lengths[f] = sqrt(nx*nx+ny*ny+nz*nz)
        for (k in 0..2) incident[t[f*3+k]].add(f)
    }
    val out = FloatArray(t.size*3)
    val creaseCos = 0.76604444f // cos(40 degrees), matching the viewport's intended crease policy.
    for (f in 0 until mesh.triangleCount) for (k in 0..2) {
        var nx = 0f; var ny = 0f; var nz = 0f
        for (other in incident[t[f*3+k]]) {
            val dot = faces[f*3]*faces[other*3] + faces[f*3+1]*faces[other*3+1] + faces[f*3+2]*faces[other*3+2]
            if (lengths[f] > 0f && lengths[other] > 0f && dot >= creaseCos*lengths[f]*lengths[other]) {
                nx += faces[other*3]; ny += faces[other*3+1]; nz += faces[other*3+2]
            }
        }
        val length = sqrt(nx*nx+ny*ny+nz*nz)
        val at = (f*3+k)*3
        if (length > 0f) { out[at] = nx/length; out[at+1] = ny/length; out[at+2] = nz/length }
    }
    return out
}
