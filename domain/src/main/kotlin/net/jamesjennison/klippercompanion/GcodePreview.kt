package net.jamesjennison.klippercompanion

import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.math.*

data class ToolpathSegment(val x1:Float,val y1:Float,val x2:Float,val y2:Float,val layer:Int,val byteEnd:Long=0,val tool:Int=0)
data class Toolpath(val segments:List<ToolpathSegment>,val heights:List<Float>,val sampled:Boolean,val ignoredMotion:Boolean,val travels:List<ToolpathSegment> = emptyList(),val byteSize:Long=0,val toolChanges:List<ToolChange> = emptyList(),val toolsUsed:Set<Int> = setOf(0))
/** A tool change seen in the G-code (`T1` etc.): the layer it happened on and the tool selected. */
data class ToolChange(val layer:Int,val tool:Int)
/** Approximate XY extrusion geometry, never a G-code executor. IJ arcs use relative centers. */
object GcodePreview {
    const val MAX_BYTES=256L*1024*1024
    private const val MAX_SEGMENTS=120_000
    private val PARAMETERIZED_COMMANDS=setOf("G92","G0","G00","G1","G01","G2","G02","G3","G03")
    private val token=Regex("([A-Z_]+)([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))?")
    fun parse(input:InputStream,cancelled:()->Boolean={Thread.currentThread().isInterrupted}):Toolpath {
        var bytes=0L;var x=0.0;var y=0.0;var z=0.0;var e=0.0;var xyzAbsolute=true;var eAbsolute=true;var units=1.0
        var offsetX=0.0;var offsetY=0.0;var knownX=false;var knownY=false
        val segments=ArrayList<ToolpathSegment>();val heights=ArrayList<Float>();var layer=-1;var extrusionZ=Double.NaN
        var stride=1L;var moves=0L;var ignored=false;var tool=0;val toolChanges=ArrayList<ToolChange>();val pendingTools=ArrayList<Int>();val toolsUsed=sortedSetOf(0)
        val travels=ArrayList<ToolpathSegment>();var travelStride=1L;var travelMoves=0L
        fun travel(ax:Double,ay:Double,bx:Double,by:Double) {
            if(travelMoves++%travelStride==0L) travels.add(ToolpathSegment((ax+offsetX).toFloat(),(ay+offsetY).toFloat(),(bx+offsetX).toFloat(),(by+offsetY).toFloat(),layer.coerceAtLeast(0),bytes))
            if(travels.size>=60_000){val retained=travels.filterIndexed{i,_->i%2==0};travels.clear();travels.addAll(retained);travelStride*=2}
        }
        fun segment(ax:Double,ay:Double,bx:Double,by:Double,height:Double) {
            // Spiral (vase-mode) output changes Z on almost every move: past the layer budget the rest is drawn into the last layer instead of failing.
            if((layer<0||abs(height-extrusionZ)>0.001)&&heights.size<10_000) {heights.add(height.toFloat());layer++;extrusionZ=height}
            // A tool change belongs to the layer of the next extrusion (the layer counter advances lazily on Z changes).
            if(pendingTools.isNotEmpty()){pendingTools.forEach{toolChanges.add(ToolChange(layer.coerceAtLeast(0),it))};pendingTools.clear()}
            if(moves++%stride==0L)segments.add(ToolpathSegment((ax+offsetX).toFloat(),(ay+offsetY).toFloat(),(bx+offsetX).toFloat(),(by+offsetY).toFloat(),layer,bytes,tool))
            if(segments.size>=MAX_SEGMENTS){val retained=segments.filterIndexed{i,_->i%2==0};segments.clear();segments.addAll(retained);stride*=2}
        }
        val line=StringBuilder()
        fun consume() {
            val clean=StringBuilder();var depth=0
            for(c in line) {if(c==';'&&depth==0)break;when(c){'('->depth++;')'->{require(depth>0){"Unbalanced G-code comment."};depth--};else->if(depth==0)clean.append(c)}}
            require(depth==0){"Unbalanced G-code comment."}
            val text=clean.toString().trim().uppercase(java.util.Locale.ROOT);line.setLength(0)
            if(text.isEmpty())return
            val tokens=token.findAll(text).toList().let {all->if(all.firstOrNull()?.groupValues?.get(1)=="N") all.drop(1) else all};val command=tokens.firstOrNull()?.let{it.groupValues[1]+it.groupValues[2]}?:return
            // Only the motion/origin commands below ever read `v`. Tokenizing every other line's free text as
            // numeric G-code words is what made a Klipper `EXCLUDE_OBJECT_DEFINE NAME=<uuid>.stl...` line
            // whose UUID contained e.g. `b36836200` parse as B=36836200 and fail the whole preview.
            val v=if(command in PARAMETERIZED_COMMANDS)tokens.drop(1).mapNotNull{t->t.groupValues[2].toDoubleOrNull()?.let{t.groupValues[1] to it}}.toMap() else emptyMap()
            require(v.values.all{it.isFinite()&&abs(it)<=10_000_000}){"Unsupported coordinate magnitude."}
            when(command) {
                "G90"->xyzAbsolute=true;"G91"->xyzAbsolute=false;"M82"->eAbsolute=true;"M83"->eAbsolute=false
                "G20"->units=25.4;"G21"->units=1.0;"G17"->Unit
                "G18","G19","G90.1"->throw IllegalArgumentException("Only XY-plane arcs are supported.")
                "G92"->{v["X"]?.times(units)?.let{offsetX=if(knownX)offsetX+x-it else 0.0;x=it;knownX=true};v["Y"]?.times(units)?.let{offsetY=if(knownY)offsetY+y-it else 0.0;y=it;knownY=true};z=v["Z"]?.times(units)?:z;e=v["E"]?.times(units)?:e}
                "G0","G00","G1","G01","G2","G02","G3","G03"->{
                    fun next(key:String,old:Double,absolute:Boolean)=v[key]?.times(units)?.let{if(absolute)it else old+it}?:old
                    val nx=next("X",x,xyzAbsolute);val ny=next("Y",y,xyzAbsolute);val nz=next("Z",z,xyzAbsolute);val ne=next("E",e,eAbsolute)
                    require(listOf(nx,ny,nz,ne).all{it.isFinite()&&abs(it)<=10_000_000}){"Coordinate range exceeded."}
                    val arc=command in setOf("G2","G02","G3","G03")
                    if(arc) {
                        if(ne<=e)ignored=true // Omit travel arcs; only their endpoint matters.
                        else {
                        require(knownX&&knownY&&!v.containsKey("R")&&(v.containsKey("I")||v.containsKey("J"))&&(ne<=e||abs(nz-z)<0.001)&&(ne<=e||(v["P"]?:1.0)==1.0)){"Extrusion preview supports single-turn XY IJ arcs at fixed Z; travel arcs only update the endpoint."}
                        val cx=x+(v["I"]?:0.0)*units;val cy=y+(v["J"]?:0.0)*units
                        val radius=hypot(x-cx,y-cy);require(radius>0.0001&&abs(hypot(nx-cx,ny-cy)-radius)<=maxOf(0.05,radius*0.001)){"Arc endpoints disagree with its center."}
                        val start=atan2(y-cy,x-cx);val end=atan2(ny-cy,nx-cx);val clockwise=command in setOf("G2","G02")
                        var sweep=if(clockwise)start-end else end-start;while(sweep<=0)sweep+=2*PI
                        val steps=ceil(maxOf(sweep*radius,sweep/(PI/36))).toInt();require(steps in 1..10_000){"Arc exceeds supported geometry budget."}
                                                if(ne>e) {var ax=x;var ay=y
                            for(i in 1..steps){if(cancelled())throw InterruptedIOException("Preview cancelled");val angle=start+(if(clockwise)-1 else 1)*sweep*i/steps;val bx=if(i==steps)nx else cx+radius*cos(angle);val by=if(i==steps)ny else cy+radius*sin(angle);segment(ax,ay,bx,by,nz);ax=bx;ay=by}
                        }
                        }
                    }else if(nx!=x||ny!=y) {if(knownX&&knownY){if(ne>e)segment(x,y,nx,ny,nz)else travel(x,y,nx,ny)}else ignored=true}
                    x=nx;y=ny;z=nz;e=ne;if(xyzAbsolute&&v.containsKey("X"))knownX=true;if(xyzAbsolute&&v.containsKey("Y"))knownY=true
                }
                "G28"->{knownX=false;knownY=false;offsetX=0.0;offsetY=0.0;ignored=true}
                else->if(command.length in 2..3&&command[0]=='T'&&command.drop(1).all(Char::isDigit)){val t=command.drop(1).toInt();if(t!=tool){tool=t;pendingTools.add(t)};toolsUsed.add(t)}
                else if(command.startsWith("G")&&command !in setOf("G4","G10","G11"))ignored=true
            }
        }
        val buffer=ByteArray(32768)
        while(true){if(cancelled())throw InterruptedIOException("Preview cancelled");val count=input.read(buffer);if(count<0)break;require(bytes+count<=MAX_BYTES){"Preview supports files up to 256 MiB."}
            for(i in 0 until count){bytes++;val c=buffer[i].toInt() and 255;require(c!=0){"Binary G-code is unsupported."};if(c==10){if(cancelled())throw InterruptedIOException("Preview cancelled");consume()}else{require(line.length<16_384){"G-code line too long."};line.append(c.toChar())}}
        }
        if(line.isNotEmpty())consume();require(segments.isNotEmpty()){"No supported extrusion paths found."}
        pendingTools.forEach{toolChanges.add(ToolChange(layer.coerceAtLeast(0),it))}
        return Toolpath(segments,heights,stride>1||travelStride>1,ignored,travels,bytes,toolChanges,toolsUsed)
    }
}
