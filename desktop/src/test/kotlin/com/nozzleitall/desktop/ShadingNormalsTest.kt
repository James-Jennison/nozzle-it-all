package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.cornerNormals
import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Test

class ShadingNormalsTest {
    /** Original exhaustive filter, deliberately kept small as a correctness oracle. */
    private fun reference(m:Mesh):FloatArray {
        val faces=FloatArray(m.triangleCount*3);val lengths=FloatArray(m.triangleCount)
        val incident=Array(m.vertexCount){mutableListOf<Int>()}
        for(f in 0 until m.triangleCount) {
            val a=m.triangles[f*3]*3;val b=m.triangles[f*3+1]*3;val c=m.triangles[f*3+2]*3;val v=m.vertices
            val x=v[b]-v[a];val y=v[b+1]-v[a+1];val z=v[b+2]-v[a+2];val u=v[c]-v[a];val w=v[c+1]-v[a+1];val q=v[c+2]-v[a+2]
            faces[f*3]=y*q-z*w;faces[f*3+1]=z*u-x*q;faces[f*3+2]=x*w-y*u
            lengths[f]=kotlin.math.sqrt(faces[f*3]*faces[f*3]+faces[f*3+1]*faces[f*3+1]+faces[f*3+2]*faces[f*3+2])
            for(k in 0..2)incident[m.triangles[f*3+k]].add(f)
        }
        val out=FloatArray(m.triangleCount*9)
        for(c in m.triangles.indices) {
            val f=c/3;var x=0f;var y=0f;var z=0f
            for(other in incident[m.triangles[c]]) {
                val dot=faces[f*3]*faces[other*3]+faces[f*3+1]*faces[other*3+1]+faces[f*3+2]*faces[other*3+2]
                if(lengths[f]>0 && lengths[other]>0 && dot>=.76604444f*lengths[f]*lengths[other]) { x+=faces[other*3];y+=faces[other*3+1];z+=faces[other*3+2] }
            }
            val n=kotlin.math.sqrt(x*x+y*y+z*z);if(n>0) { out[c*3]=x/n;out[c*3+1]=y/n;out[c*3+2]=z/n }
        }
        return out
    }
    @Test fun highValenceCreasesMatchExhaustiveFilterWithoutMutatingGeometry() {
        for(count in listOf(63,256,1024)) {
            val v=FloatArray((count+1)*3);val t=IntArray(count*3)
            for(i in 0 until count) {
                val a=i*2*Math.PI/count;v[(i+1)*3]=(100*kotlin.math.cos(a)).toFloat();v[(i+1)*3+1]=(100*kotlin.math.sin(a)).toFloat();v[(i+1)*3+2]=(22*kotlin.math.sin(7*a)).toFloat()
                t[i*3]=0;t[i*3+1]=i+1;t[i*3+2]=(i+1)%count+1
            }
            val m=Mesh(v,t);val before=v.copyOf()
            assertArrayEquals(reference(m),cornerNormals(m),0.00001f);assertArrayEquals(before,v,0f)
        }
    }
    @Test fun coincidentAndDegenerateHighValenceFacesRemainFinite() {
        val vertices=floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f)
        val t=IntArray(600) { when(it%6){0,3,4,5->0;1->1;else->2} };val m=Mesh(vertices,t)
        assertArrayEquals(reference(m),cornerNormals(m),0.00001f)
    }
    // Two flat cap triangles share their boundary vertices with vertical walls.
    private fun cappedMesh() = Mesh(floatArrayOf(
        0f,0f,0f, 1f,0f,0f, 1f,1f,0f, 0f,1f,0f,
        0f,0f,1f, 1f,0f,1f, 1f,1f,1f, 0f,1f,1f
    ), intArrayOf(0,1,2, 0,2,3, 0,4,5, 0,5,1, 1,5,6, 1,6,2, 2,6,7, 2,7,3, 3,7,4, 3,4,0))

    @Test fun planarCapHasOneNormalEvenAtSharedSidewallCorners() {
        val m = cappedMesh(); val vertices = m.vertices.copyOf(); val triangles = m.triangles.copyOf()
        val n = cornerNormals(m)
        for (c in 0 until 6) assertArrayEquals(floatArrayOf(0f,0f,1f), n.copyOfRange(c*3,c*3+3),0.000001f)
        assertArrayEquals(vertices,m.vertices,0f); assertArrayEquals(triangles,m.triangles)
    }

    @Test fun shallowCurvesStillHaveSmoothSharedCornerNormals() {
        val m = Mesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 0f,-1f,0.3f),intArrayOf(0,1,2, 1,0,3))
        val n = cornerNormals(m)
        assertArrayEquals(n.copyOfRange(0,3),n.copyOfRange(12,15),0.000001f)
        assertTrue(n[1] > 0f); assertTrue(n[2] > 0.9f)
    }

    @Test fun degenerateFacesAndUnusedVerticesStayFinite() {
        val m = Mesh(floatArrayOf(0f,0f,0f, 1f,0f,0f, 0f,1f,0f, 9f,9f,9f),intArrayOf(0,0,1, 0,1,2))
        val n = cornerNormals(m)
        assertEquals(m.triangleCount*9,n.size); assertTrue(n.all { it.isFinite() })
        assertArrayEquals(floatArrayOf(0f,0f,1f),n.copyOfRange(9,12),0f)
    }
}
