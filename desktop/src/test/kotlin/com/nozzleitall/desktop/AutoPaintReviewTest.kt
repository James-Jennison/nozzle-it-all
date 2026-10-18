package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

class AutoPaintReviewTest {
    @Test fun changingTargetSelectionInvalidatesEvenAnInflightPreparation() {
        val tmp = Files.createTempDirectory("ai-target").toFile()
        val app = AppState(AppPaths(File(tmp,"c"),File(tmp,"d"),File(tmp,"k")).ensure(), CoroutineScope(SupervisorJob()+Dispatchers.Default))
        val p = app.prepare; val mesh = grid()
        p.items.addAll(listOf(PrepItem(1,"first",mesh,40f,40f),PrepItem(2,"second",mesh,80f,40f)))
        p.selected = 1; p.aiItemId = 1 // Target already bound, but asynchronous preparation has not produced aiPrepared yet.
        p.selected = 2
        assertNull(p.aiItemId); assertNull(p.aiPrepared); assertNull(p.aiPreviewMesh)
        p.aiApply(); assertSame(mesh,p.items[0].mesh); assertSame(mesh,p.items[1].mesh)
        app.scope.cancel()
    }
    private fun grid(): Mesh {
        val width = 20
        val vertices = FloatArray((width + 1) * (width + 1) * 3)
        for (y in 0..width) for (x in 0..width) { val at = (y*(width+1)+x)*3; vertices[at] = x.toFloat(); vertices[at+1] = y.toFloat() }
        val triangles = mutableListOf<Int>()
        for (y in 0 until width) for (x in 0 until width) {
            val a = y*(width+1)+x; val b = a+1; val c = a+width+1; val d = c+1
            triangles.addAll(listOf(a,b,d,a,d,c))
        }
        return Mesh(vertices, triangles.toIntArray())
    }
    @Test fun orbitCrossesBothPolesAndCanReturnAfterAFullTurn() {
        val camera = ViewCamera(); camera.orbit(0f, -200f)
        assertEquals(115f, camera.pitch, 0.001f)
        camera.orbit(0f, -1000f); assertEquals(55f, camera.pitch, 0.001f)
        camera.orbit(1800f, 0f); assertEquals(-35f, camera.yaw, 0.001f)
        camera.fitBed(256f,256f); val fitted = camera.distance
        camera.distance = 80f; camera.pan = androidx.compose.ui.geometry.Offset(100f,200f)
        camera.reset(); assertEquals(55f, camera.pitch, 0f); assertEquals(fitted, camera.distance, 0f)
        assertEquals(androidx.compose.ui.geometry.Offset.Zero, camera.pan)
    }
    @Test fun regionCorrectionsAndResetNeverMutateOriginalDraft() {
        val draft = AutoPaintReview(intArrayOf(0,1,0), listOf(1,2))
        val corrected = draft.corrected(0,0)
        assertArrayEquals(intArrayOf(0,2,0), corrected.faces())
        assertArrayEquals(intArrayOf(1,2,1), draft.faces())
        assertArrayEquals(draft.faces(), corrected.reset().faces())
    }
    @Test fun keepPreservesMappedAndSubdividedPaintAndOtherKinds() {
        val m = grid(); val p = arrayOfNulls<String>(m.triangleCount); p[0] = Paint.whole(1)
        p[1] = Paint.encode(Paint.Node.Split(1,0,listOf(Paint.Node.Leaf(1),Paint.Node.Leaf(2))))
        val support = arrayOfNulls<String>(m.triangleCount); support[0] = Paint.whole(1)
        val original = m.withPaint(paint = p, supportPaint = support)
        val base = AutoPaintReview.base(original, listOf(3,4), 2)
        val result = AutoPaint.applied(base, IntArray(m.triangleCount) { if(it == 2) 1 else 0 })
        assertEquals(setOf(3,4), Paint.states(Paint.decode(result.paint!![1]!!)))
        assertEquals(Paint.whole(3), result.paint!![0]); assertEquals(Paint.whole(1), result.paint!![2])
        assertSame(support, result.supportPaint); assertEquals(Paint.whole(1), original.paint!![0])
        assertSame(original.vertices, result.vertices); assertSame(original.triangles, result.triangles)
    }
    @Test fun cleanupRetractsAnInteriorTipWithoutChangingGeometryOrInput() {
        val m = grid()
        val slots = IntArray(m.triangleCount) { f -> if ((f/2)%20 < 10) 1 else 2 }
        val tip = (10*20+10)*2+1; slots[tip] = 1
        val before = slots.copyOf(); val cleaned = cleanPaintBoundaries(m,slots)
        assertEquals(2, cleaned[tip]); assertArrayEquals(before, slots)
        assertTrue(cleaned.indices.count { cleaned[it] != slots[it] } < 20)
    }
    @Test fun cleanupKeepsSmallDetailsAndZeroAreasAndOpenEdges() {
        val m = grid(); val slots = IntArray(m.triangleCount) { 1 }; val detail = (10*20+10)*2
        slots[detail] = 2
        assertArrayEquals(slots, cleanPaintBoundaries(m,slots))
        slots[detail] = 0; assertArrayEquals(slots, cleanPaintBoundaries(m,slots))
        val lone = Mesh(floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f), intArrayOf(0,1,2))
        assertArrayEquals(intArrayOf(2), cleanPaintBoundaries(lone,intArrayOf(2)))
    }
    @Test fun cleanupMatchesTriangleSoupAdjacency() {
        val m = grid(); val soup = Mesh(FloatArray(m.triangles.size*3) { i -> m.vertices[m.triangles[i/3]*3+i%3] }, IntArray(m.triangles.size) { it })
        val slots = IntArray(m.triangleCount) { if ((it/2)%20 < 10) 1 else 2 }; slots[(10*20+10)*2+1] = 1
        assertArrayEquals(cleanPaintBoundaries(m,slots), cleanPaintBoundaries(soup,slots))
    }
    @Test fun cleanupNeverRetractsATipAcrossASharpCrease() {
        val flat = grid(); val verts = flat.vertices.copyOf()
        for (i in verts.indices step 3) if (verts[i] > 10f) verts[i+2] = (verts[i]-10f)*2f
        val folded = Mesh(verts,flat.triangles)
        val slots = IntArray(folded.triangleCount) { if ((it/2)%20 < 10) 1 else 2 }
        val tip = (10*20+10)*2+1; slots[tip] = 1
        assertEquals(1,cleanPaintBoundaries(folded,slots)[tip])
    }
}
