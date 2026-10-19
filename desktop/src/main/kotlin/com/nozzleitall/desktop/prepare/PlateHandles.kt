package com.nozzleitall.desktop.prepare

import kotlin.math.atan2
import kotlin.math.hypot

/** What a press on the plate grabbed. */
enum class Grab { MOVE, ROTATE, SCALE }

/** The maths behind dragging objects on the plate, apart from drawing and pointer events. [reach] is min x, max x, min y, max y from the object's centre. */
object PlateHandles {
    /** Whether the bed point ([px], [py]) is over the footprint of an object at ([x], [y]). */
    fun footprintHit(reach: FloatArray, x: Float, y: Float, px: Float, py: Float): Boolean =
        reach.size == 4 && px >= x + reach[0] && px <= x + reach[1] && py >= y + reach[2] && py <= y + reach[3]

    /** The ring the turn handle sits on: just outside the footprint's corners. */
    fun ringRadius(reach: FloatArray): Float =
        if (reach.size == 4) hypot(maxOf(-reach[0], reach[1]), maxOf(-reach[2], reach[3])) + 8f else 20f

    /** The scale handle: the footprint's far corner on the bed. */
    fun scaleKnob(reach: FloatArray, x: Float, y: Float): Pair<Float, Float> = if (reach.size == 4) (x + reach[1]) to (y + reach[3]) else x to y

    /** The turn handle, on the ring at the object's own heading. */
    fun rotateKnob(reach: FloatArray, x: Float, y: Float, rotZ: Float): Pair<Float, Float> {
        val r = ringRadius(reach); val a = Math.toRadians(rotZ.toDouble())
        return (x + r * kotlin.math.cos(a).toFloat()) to (y + r * kotlin.math.sin(a).toFloat())
    }

    /** The turn after dragging the handle from [from] to [to] around the centre ([cx], [cy]); [snap] rounds to that many degrees (0 = whole degrees). Result in 0..360. */
    fun rotationAfterDrag(rot0: Float, cx: Float, cy: Float, fromX: Float, fromY: Float, toX: Float, toY: Float, snap: Float = 0f): Float {
        val a0 = atan2((fromY - cy).toDouble(), (fromX - cx).toDouble()); val a1 = atan2((toY - cy).toDouble(), (toX - cx).toDouble())
        var deg = rot0 + Math.toDegrees(a1 - a0).toFloat()
        deg = if (snap > 0f) Math.round(deg / snap) * snap else Math.round(deg).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    /** The size after dragging the corner handle from [from] to [to]: it follows the handle's distance from the centre. */
    fun scaleAfterDrag(scale0: Float, cx: Float, cy: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
        val d0 = hypot(fromX - cx, fromY - cy); val d1 = hypot(toX - cx, toY - cy)
        return if (d0 < 1e-3f) scale0 else (scale0 * d1 / d0).coerceIn(0.05f, 20f)
    }
}
