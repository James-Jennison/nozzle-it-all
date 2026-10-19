package com.nozzleitall.desktop

import androidx.compose.ui.graphics.Color
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class DisplayPreparationTest {
    private fun mesh() = Mesh(floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f), intArrayOf(0,1,2))
    private fun obj(m: Mesh, kind: Int = 0, id: Int = 1, x: Float = 0f, selected: Boolean = false) =
        PlateObject(id,"fixture",m,Color.Red,x,0f,0f,1f,selected,paintKind=kind)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10,TimeUnit.SECONDS))

    @Test(timeout=20000) fun preparationRunsOffSwingAndMainKeepsDispatching() = runBlocking {
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        val job=async(Dispatchers.Main) {
            prepareDisplays(listOf(DisplayKey(mesh(),0))) { key, _ ->
                assertFalse(SwingUtilities.isEventDispatchThread());started.countDown()
                check(release.await(10,TimeUnit.SECONDS));displayMesh(key.mesh)
            }
        }
        try {
            await(started)
            withTimeout(3000) { withContext(Dispatchers.Main) { assertTrue(SwingUtilities.isEventDispatchThread()) } }
            assertFalse(job.isCompleted)
        } finally { release.countDown() }
        assertEquals(1,job.await().displays.size)
    }

    @Test(timeout=20000) fun nonCooperativeCancelledResultCannotPublishAndLatestWins() = runBlocking {
        val old=DisplayKey(mesh(),0);val latest=DisplayKey(mesh(),3)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val published=java.util.concurrent.CopyOnWriteArrayList<PreparedDisplays>()
        val first=launch(Dispatchers.Main) {
            published += prepareDisplays(listOf(old)) { key,_ ->
                started.countDown();check(release.await(10,TimeUnit.SECONDS));displayMesh(key.mesh)
            }
        }
        await(started)
        first.cancel()
        val second=launch(Dispatchers.Main) { published += prepareDisplays(listOf(latest)) }
        release.countDown();joinAll(first,second)
        assertEquals(listOf(listOf(latest)),published.map { it.keys })
    }

    @Test(timeout=20000) fun queuedCancelledWorkNeverStartsAndDisposalNeverPublishes() = runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val first=launch(Dispatchers.Main) { prepareDisplays(listOf(DisplayKey(mesh(),0))) { key,_ ->
            started.countDown();check(release.await(10,TimeUnit.SECONDS));displayMesh(key.mesh)
        }; fail("disposed viewport published") }
        await(started)
        val queued=launch { prepareDisplays(listOf(DisplayKey(mesh(),0))) { _,_ -> error("cancelled queued work started") } }
        yield();queued.cancel();first.cancel();release.countDown();joinAll(first,queued)
    }

    @Test fun bindRejectsChangedMeshPaintKindRemovalAndReorder() = runBlocking {
        val a=mesh();val b=mesh();val objects=listOf(obj(a),obj(b,id=2))
        val result=prepareDisplays(displayKeys(objects))
        assertEquals(2,result.bind(objects).size)
        assertTrue(result.bind(listOf(obj(mesh()),obj(b,id=2))).isEmpty())
        assertTrue(result.bind(listOf(obj(a,kind=1),obj(b,id=2))).isEmpty())
        assertTrue(result.bind(listOf(obj(a))).isEmpty())
        assertTrue(result.bind(objects.reversed()).isEmpty())
        assertTrue(result.bind(emptyList()).isEmpty())
    }

    @Test fun presentationChangesAndInstancesReuseGeometryAndUseLatestProperties() = runBlocking {
        val m=mesh();val initial=listOf(obj(m))
        var calls=0
        val result=prepareDisplays(displayKeys(initial)) { key,_ -> calls++;displayMesh(key.mesh) }
        val changed=listOf(obj(m,x=23f,selected=true),obj(m,id=2,x=42f))
        assertEquals(result.keys,displayKeys(changed))
        val bound=result.bind(changed)
        assertEquals(1,calls);assertSame(changed[0],bound[0].first)
        assertEquals(23f,bound[0].first.x);assertTrue(bound[0].first.selected)
        assertSame(bound[0].second,bound[1].second)
    }

    @Test fun failureDoesNotPoisonNextRequest() = runBlocking {
        val keys=listOf(DisplayKey(mesh(),0))
        try { prepareDisplays(keys) { _,_ -> throw IllegalArgumentException("fixture") };fail("expected failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(keys,prepareDisplays(keys).keys)
    }

    @Test fun normalAndExpansionWorkObserveCancellation() {
        val m=mesh()
        try { cornerNormals(m) { throw CancellationException("fixture") };fail("not cancelled") }
        catch (_: CancellationException) { }
        val painted=Mesh(m.vertices,m.triangles,arrayOf("1"))
        try { expandPaint(painted,checkCancelled={ throw CancellationException("fixture") });fail("not cancelled") }
        catch (_: CancellationException) { }
    }
}
