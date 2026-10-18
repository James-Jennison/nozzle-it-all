package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.Mesh
import kotlin.math.sqrt

/** Area-weighted normals per triangle corner, excluding faces across a sharp crease.
 * Filtering the incident faces BEFORE averaging keeps flat caps flat: testing an already
 * averaged vertex normal can incorrectly accept a blend of perpendicular cap/side faces.
 * This is display-only; positions, topology, paint and slicing input are never modified.
 */
internal fun cornerNormals(mesh: Mesh, checkCancelled: () -> Unit = {}): FloatArray {
    val v = mesh.vertices; val t = mesh.triangles
    val faces = FloatArray(t.size)
    val lengths = FloatArray(mesh.triangleCount)
    // CSR adjacency: no per-vertex lists or boxed triangle indices.
    val counts = IntArray(v.size / 3)
    for (f in 0 until mesh.triangleCount) {
        if (f and 1023 == 0) checkCancelled()
        val a = t[f*3]*3; val b = t[f*3+1]*3; val c = t[f*3+2]*3
        val ux = v[b]-v[a]; val uy = v[b+1]-v[a+1]; val uz = v[b+2]-v[a+2]
        val wx = v[c]-v[a]; val wy = v[c+1]-v[a+1]; val wz = v[c+2]-v[a+2]
        val nx = uy*wz-uz*wy; val ny = uz*wx-ux*wz; val nz = ux*wy-uy*wx
        faces[f*3] = nx; faces[f*3+1] = ny; faces[f*3+2] = nz
        lengths[f] = sqrt(nx*nx+ny*ny+nz*nz)
        for (k in 0..2) counts[t[f*3+k]]++
    }
    val offsets = IntArray(counts.size+1)
    for (i in counts.indices) offsets[i+1] = offsets[i]+counts[i]
    val cursor = offsets.copyOf(); val corners = IntArray(t.size)
    for (c in t.indices) corners[cursor[t[c]]++] = c
    val out = FloatArray(t.size*3)
    val creaseCos = 0.76604444f // cos(40 degrees), matching the viewport's intended crease policy.
    for (vertex in counts.indices) {
        if (vertex and 1023 == 0) checkCancelled()
        val start=offsets[vertex]; val end=offsets[vertex+1]
        if (start == end) continue
        var sx=0f;var sy=0f;var sz=0f
        for (i in start until end) { val f=corners[i]/3; if(lengths[f]>0f) { sx+=faces[f*3];sy+=faces[f*3+1];sz+=faces[f*3+2] } }
        val sumLength=sqrt(sx*sx+sy*sy+sz*sz)
        val ux=sx/sumLength;val uy=sy/sumLength;val uz=sz/sumLength
        // If every face is within20 degrees of one axis, every pair is within40.
        // This proves (rather than assumes) every query accepts the identical sum.
        val allSmooth=sumLength>0f && (start until end).all { i ->
            val f=corners[i]/3
            lengths[f]==0f || faces[f*3]*ux+faces[f*3+1]*uy+faces[f*3+2]*uz >= 0.939703f*lengths[f]
        }
        val tree=if(!allSmooth && end-start>32) NormalConeTree(faces,lengths,corners.copyOfRange(start,end),checkCancelled) else null
        val sum=if(tree!=null)DoubleArray(3) else null
        for (i in start until end) {
            if (i and 1023 == 0) checkCancelled()
            val corner=corners[i];val f=corner/3
            if(lengths[f]<=0f) continue
            var nx=0f;var ny=0f;var nz=0f
            if(allSmooth) { nx=sx;ny=sy;nz=sz }
            else if(tree!=null) {
                sum!!.fill(0.0);tree.sum(f,creaseCos,sum)
                nx=sum[0].toFloat();ny=sum[1].toFloat();nz=sum[2].toFloat()
            } else for (j in start until end) {
                val other=corners[j]/3
                val dot=faces[f*3]*faces[other*3]+faces[f*3+1]*faces[other*3+1]+faces[f*3+2]*faces[other*3+2]
                if(lengths[other]>0f && dot>=creaseCos*lengths[f]*lengths[other]) { nx+=faces[other*3];ny+=faces[other*3+1];nz+=faces[other*3+2] }
            }
            val length=sqrt(nx*nx+ny*ny+nz*nz);val at=corner*3
            if(length>0f) { out[at]=nx/length;out[at+1]=ny/length;out[at+2]=nz/length }
        }
    }
    return out
}

/** Direction-space BVH for unusually high-valence creased vertices. Bounds can
 * accept/reject whole cones; ambiguous leaves retain the original exact filter.
 * The margin prevents a rounded bound from accepting a face across a crease.
 */
private class NormalConeTree(val faces:FloatArray,val lengths:FloatArray,val corners:IntArray,val checkCancelled:()->Unit) {
    private class Node(val start:Int,val end:Int,val lo:FloatArray,val hi:FloatArray,val sum:DoubleArray,var left:Node?=null,var right:Node?=null)
    private fun coordinate(corner:Int,axis:Int):Float { val f=corner/3;return if(lengths[f]>0f) faces[f*3+axis]/lengths[f] else 0f }
    private fun build(start:Int,end:Int):Node {
        checkCancelled()
        val lo=FloatArray(3){Float.POSITIVE_INFINITY};val hi=FloatArray(3){Float.NEGATIVE_INFINITY};val sum=DoubleArray(3)
        for(i in start until end) for(a in 0..2) {
            val value=coordinate(corners[i],a);lo[a]=minOf(lo[a],value);hi[a]=maxOf(hi[a],value)
            if(lengths[corners[i]/3]>0f) sum[a]+=faces[corners[i]/3*3+a]
        }
        val node=Node(start,end,lo,hi,sum)
        if(end-start<=16) return node
        val axis=(0..2).maxBy { hi[it]-lo[it] };val mid=(start+end)/2
        // Median partition keeps depth bounded even when many directions coincide.
        var low=start;var high=end-1
        while(low<high) {
            val pivot=coordinate(corners[(low+high)/2],axis);var i=low;var j=high
            while(i<=j) {
                while(coordinate(corners[i],axis)<pivot) i++
                while(coordinate(corners[j],axis)>pivot) j--
                if(i<=j) { val tmp=corners[i];corners[i]=corners[j];corners[j]=tmp;i++;j-- }
            }
            if(mid<=j) high=j else if(mid>=i) low=i else break
        }
        node.left=build(start,mid);node.right=build(mid,end);return node
    }
    private val root=build(0,corners.size)
    fun sum(face:Int,cos:Float,out:DoubleArray) {
        val x=faces[face*3]/lengths[face];val y=faces[face*3+1]/lengths[face];val z=faces[face*3+2]/lengths[face]
        fun query(node:Node) {
            var low=0.0;var high=0.0
            for(a in 0..2) { val q=when(a){0->x;1->y;else->z}.toDouble();low+=q*(if(q>=0)node.lo[a] else node.hi[a]);high+=q*(if(q>=0)node.hi[a] else node.lo[a]) }
            if(high<cos-0.00001) return
            if(low>cos+0.00001) { for(a in 0..2)out[a]+=node.sum[a];return }
            if(node.left!=null) { query(node.left!!);query(node.right!!);return }
            for(i in node.start until node.end) {
                val other=corners[i]/3
                val dot=faces[face*3]*faces[other*3]+faces[face*3+1]*faces[other*3+1]+faces[face*3+2]*faces[other*3+2]
                if(lengths[other]>0f && dot>=cos*lengths[face]*lengths[other]) for(a in 0..2)out[a]+=faces[other*3+a]
            }
        }
        query(root)
    }
}
