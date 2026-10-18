package com.nozzleitall.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import com.nozzleitall.project.Paint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class ViewportRasterTest {
    @Test fun timingUsesNearestRankP95ForTheActualSampleCount() {
        val samples=LongArray(30){if(it==29)100 else 10}.sortedArray()
        assertEquals(28,viewportP95Index(samples.size));assertEquals(10L,samples[viewportP95Index(samples.size)])
        assertEquals(0,viewportP95Index(1));assertEquals(94,viewportP95Index(100))
    }
    @Test fun coverageAtAreaThresholdAndLargeGuardMatchesFrozenOracle() {
        for(area in listOf(0f,0.5e-8f,1e-8f,1.5e-8f,1e-7f))for(sign in listOf(-1f,1f)) {
            val a=DepthSurface(64f,48f);val b=ReferenceDepthSurface(64f,48f)
            val p=floatArrayOf(0f,0f,0f,1f,0f,0f,1f,area*sign,0f);val colors=IntArray(3){0xffaabbcc.toInt()}
            a.triangle(p,0,colors,0,100f);b.triangle(p,0,colors,0,100f);assertArrayEquals(b.pixels,a.pixels)
            val long=floatArrayOf(-100000f,-100000f,0f,100000f,100000f,0f,100000f,100000.01f*sign,0f)
            a.clear();b.clear();a.triangle(long,0,colors,0,100f);b.triangle(long,0,colors,0,100f);assertArrayEquals(b.pixels,a.pixels)
        }
    }
    @Test fun shadowScratchReusesLargestBufferAcrossMoreThanFourMeshSizes() {
        val scratch=ShadowScratch();val largest=scratch.get(120_000)
        repeat(3) { for(count in listOf(3,300,900,6000,12000,60000,120000))assertSame(largest,scratch.get(count)) }
    }
    @Test fun lightingGroupsKeepUlpDifferencesAndResolveHashCollisions() {
        val mesh=torus();val normals=FloatArray(mesh.triangles.size*3)
        for(c in mesh.triangles.indices) {
            normals[c*3]=Float.fromBits(1f.toRawBits()+(c%137))
            normals[c*3+1]=(c%11).toFloat();normals[c*3+2]=1f
        }
        val groups=LightingGroups(DisplayMesh(mesh,normals,mesh.bounds()))
        val seen=HashMap<List<Int>,Int>();val reverse=HashMap<Int,List<Int>>()
        for(c in mesh.triangles.indices) {
            val key=(0..2).map { normals[c*3+it].toRawBits() };val id=groups.ids[c]
            assertEquals(seen.getOrPut(key){id},id);assertEquals(reverse.getOrPut(id){key},key)
        }
        assertNotEquals(groups.ids[0],groups.ids[1])
    }
    @Test fun conservativeSpansMatchFrozenPixelOracleForClippingSliversAndBothWindings() {
        val random=Random(1739);val actual=DepthSurface(64f,48f);val expected=ReferenceDepthSurface(64f,48f)
        repeat(600) { i ->
            actual.clear();expected.clear()
            val triangle=FloatArray(9) { if(it%3==2)random.nextFloat()*40-18 else random.nextFloat()*240-80 }
            if(i%3==0) {
                triangle[0]=-20f;triangle[1]=-20f;triangle[3]=80f;triangle[4]=80f
                triangle[6]=80f+(i%11)*.00001f;triangle[7]=80f+(i%7)*.001f
            }
            val colors=IntArray(3){random.nextInt() or 0xff000000.toInt()}
            actual.triangle(triangle,0,colors,0,20f);expected.triangle(triangle,0,colors,0,20f)
            assertArrayEquals("coverage/colors case$i",expected.pixels,actual.pixels)
        }
    }
    private fun torus():Mesh {
        val rows=16;val columns=24;val v=FloatArray(rows*columns*3);val t=IntArray(rows*columns*6)
        for(r in 0 until rows)for(c in 0 until columns) {
            val u=r*2*PI/rows;val a=c*2*PI/columns;val at=(r*columns+c)*3
            v[at]=((32+12*cos(a))*cos(u)).toFloat();v[at+1]=((32+12*cos(a))*sin(u)).toFloat();v[at+2]=(12+12*sin(a)).toFloat()
            val k=(r*columns+c)*6;val n=((r+1)%rows)*columns;val next=(c+1)%columns
            t[k]=r*columns+c;t[k+1]=n+c;t[k+2]=n+next;t[k+3]=r*columns+c;t[k+4]=n+next;t[k+5]=r*columns+next
        }
        return Mesh(v,t,Array(t.size/3){Paint.whole(it%3+1)})
    }
    @Test fun sharedGeometryLightingAndMirrorTransformsMatchFrozenRenderer() {
        val mesh=torus();val display=displayMesh(mesh);val scope=CanvasDrawScope();val canvas=Canvas(ImageBitmap(320,240))
        for(pitch in listOf(-90f,0f,55f,95f,180f,270f))for(mirror in listOf(1f,-1f)) {
            val camera=ViewCamera().apply { distance=300f;this.pitch=pitch }
            val proj=Projector(320f,240f,camera,128f,128f)
            val actual=DepthSurface(320f,240f);val expected=ReferenceDepthSurface(320f,240f)
            scope.draw(Density(1f),LayoutDirection.Ltr,canvas,Size(320f,240f)) {
                for(i in 0..1) {
                    val o=PlateObject(i,"test",mesh,Color.Black,100f+i*60,128f,i*37f,1f,i==1,listOf(null,Color.Red,Color.Green,Color.Blue),sx=mirror,sy=1.2f,sz=.7f)
                    referenceObject(proj,o,display,expected);drawObject(proj,o,display,actual)
                }
            }
            assertArrayEquals("pitch$pitch/mirror$mirror",expected.pixels,actual.pixels)
        }
    }
    @Test fun primitiveShadowBuffersMatchFrozenShadow() {
        val mesh=torus();val d=displayMesh(mesh);val scratch=ShadowScratch()
        scratch.get(120_000).positions.fill(12345f) // Prior larger mesh must leave no tail geometry.
        fun image(reference:Boolean,pitch:Float,pan:Offset):IntArray {
            val camera=ViewCamera().apply { fitBed(256f,256f);this.pitch=pitch;this.pan=pan }
            val proj=Projector(320f,240f,camera,128f,128f)
            val bitmap=ImageBitmap(320,240);val canvas=Canvas(bitmap)
            CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,canvas,Size(320f,240f)) {
                val o=PlateObject(1,"test",mesh,Color.Gray,128f,128f,27f,1f,false,sx=-1f)
                if(reference)referenceShadow(proj,o,d) else drawShadow(proj,o,d,scratch)
            }
            return IntArray(320*240).also { bitmap.readPixels(it) }
        }
        for(pitch in listOf(-90f,0f,55f,95f,180f,270f))for(pan in listOf(Offset.Zero,Offset(125f,75f),Offset(-125f,-75f)))
            assertArrayEquals("shadow pitch$pitch/pan$pan",image(true,pitch,pan),image(false,pitch,pan))
    }
    @Test fun boundsRejectOffscreenObjectsButNeverRejectClampCrossingBox() {
        val mesh=torus();val b=mesh.bounds();val camera=ViewCamera().apply { distance=100f;pitch=0f;yaw=0f }
        val proj=Projector(320f,240f,camera,128f,128f)
        assertTrue(outsideViewport(proj,PlateObject(1,"off",mesh,Color.Gray,100_000f,100_000f,0f,1f,false),b))
        val crossing=floatArrayOf(-1f,-1f,0f,1f,1f,200f)
        assertFalse(outsideViewport(proj,PlateObject(1,"cross",mesh,Color.Gray,100_000f,100_000f,0f,1f,false),crossing))
    }
    @Test fun displayCacheIncludesBudgetAndKindAndRetainsLiveResults() {
        val mesh=torus();val a=displayMesh(mesh,1234,0)
        assertSame(a,displayMesh(mesh,1234,0));assertNotSame(a,displayMesh(mesh,1235,0));assertNotSame(a,displayMesh(mesh,1234,1))
    }
}
