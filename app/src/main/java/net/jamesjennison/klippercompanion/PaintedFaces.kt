package net.jamesjennison.klippercompanion

import com.nozzleitall.project.Paint
import com.nozzleitall.project.ThreeMf
import java.io.File

/**
 * The colours a model arrived with (AI auto-paint's 3MF, or any painted 3MF) as overlay triangles in each filament's colour, laid on
 * the model's own triangles so the editor shows what will print where. Empty for a file without per-face colour, or whose triangles
 * don't line up with the preview's (then the model simply shows unpainted, never wrongly painted).
 */
object PaintedFaces {
    private const val LIFT = 0.04f // mm above the surface, under the brush dabs' lift, so a dab still shows on top

    fun groups(file: File, g: MeshGeometry): List<OverlayGroup> {
        if (!file.extension.equals("3mf", ignoreCase = true)) return emptyList()
        val paint = runCatching { ThreeMf.read(file).objects.singleOrNull()?.mesh?.paint }.getOrNull() ?: return emptyList()
        if (paint.size != g.triangleCount) return emptyList()
        val byTool = HashMap<Int, ArrayList<Int>>()
        for (i in paint.indices) {
            val s = paint[i] ?: continue
            val tool = runCatching { Paint.states(Paint.decode(s)).filter { it > 0 }.minOrNull() }.getOrNull() ?: continue
            byTool.getOrPut(tool) { ArrayList() }.add(i)
        }
        val d = g.vertexData
        return byTool.toSortedMap().map { (tool, faces) ->
            val out = FloatArray(faces.size * 18)
            var o = 0
            for (f in faces) for (v in 0 until 3) {
                val at = f * 18 + v * 6
                out[o++] = d[at] + d[at + 3] * LIFT; out[o++] = d[at + 1] + d[at + 4] * LIFT; out[o++] = d[at + 2] + d[at + 5] * LIFT
                out[o++] = d[at + 3]; out[o++] = d[at + 4]; out[o++] = d[at + 5]
            }
            OverlayGroup(OverlayBuilders.paintColor(PaintKind.MATERIAL, tool), out)
        }
    }
}
