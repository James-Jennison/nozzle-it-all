package com.nozzleitall.desktop.prepare

import kotlin.math.cos
import kotlin.math.sin

/** Immutable gesture baseline: repeated pointer events never compound a group transform. */
class SelectionTransforms(items: List<PrepItem>, private val primary: Int) {
    private data class Pose(val item: PrepItem, val x: Float, val y: Float, val angle: Float, val scale: Float)
    private val poses = items.map { Pose(it, it.x, it.y, it.rotZ, it.scale) }
    private val anchor = poses.first { it.item.id == primary }
    private val cx = poses.map { it.x }.average().toFloat()
    private val cy = poses.map { it.y }.average().toFloat()
    fun move(x: Float, y: Float) { if (x.isFinite() && y.isFinite()) poses.forEach { it.item.x = it.x + x - anchor.x; it.item.y = it.y + y - anchor.y } }
    fun turn(angle: Float) {
        if (!angle.isFinite()) return
        val delta = angle - if (poses.size == 1) anchor.angle else 0f
        val radians = Math.toRadians(delta.toDouble()); val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
        poses.forEach { p ->
            p.item.x = cx + (p.x - cx) * c - (p.y - cy) * s
            p.item.y = cy + (p.x - cx) * s + (p.y - cy) * c
            p.item.rotZ = ((p.angle + delta) % 360f + 360f) % 360f
        }
    }
    fun resize(scale: Float) {
        val factor = scale / if (poses.size == 1) anchor.scale else 1f
        if (!factor.isFinite() || factor <= 0f) return
        poses.forEach { p -> p.item.x = cx + (p.x - cx) * factor; p.item.y = cy + (p.y - cy) * factor; p.item.scale = p.scale * factor }
    }
}
