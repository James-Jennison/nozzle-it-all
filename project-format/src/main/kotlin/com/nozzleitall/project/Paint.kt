package com.nozzleitall.project

/**
 * Per-triangle colour paint, in the form every Orca-family slicer stores it (the 3MF `paint_color` attribute; PrusaSlicer's
 * `slic3rpe:mmu_segmentation` is the same encoding) and the engine reads back through libslic3r's own 3MF importer.
 *
 * Ported from libslic3r's TriangleSelector (deserialize, perform_split): a triangle's string is hex digits read last to
 * first, each digit one 4-bit code. A code's low two bits are the number of split sides (0 = a leaf). A split node's high
 * two bits are its special side, and its children (sides + 1 of them) follow depth-first, last child first. A leaf's
 * high two bits are its state; 0b11 means the state follows as (state - 3) in 4-bit chunks, each 0b1111 chunk adding 15
 * and continuing (Snapmaker Orca's TriangleSelector, which extends upstream Orca's single escape to states up to 255;
 * the two agree up to 32). State 0 is "the part's own filament"; state N is filament N (1-based).
 */
object Paint {
    sealed class Node {
        data class Leaf(val state: Int) : Node()
        data class Split(val sides: Int, val specialSide: Int, val children: List<Node>) : Node()
    }

    /** Parses one triangle's string. Throws [ProjectFormatException] for a malformed one. */
    fun decode(s: String): Node {
        val nibbles = IntArray(s.length) { i ->
            when (val ch = s[s.length - 1 - i]) {
                in '0'..'9' -> ch - '0'
                in 'A'..'F' -> ch - 'A' + 10
                in 'a'..'f' -> ch - 'a' + 10
                else -> throw ProjectFormatException("A painted triangle has an invalid colour code.")
            }
        }
        var at = 0
        fun next(): Int = if (at < nibbles.size) nibbles[at++] else throw ProjectFormatException("A painted triangle's colour code is cut short.")
        fun node(depth: Int): Node {
            if (depth > 24) throw ProjectFormatException("A painted triangle is subdivided too deeply.")
            val code = next()
            val sides = code and 0b11
            if (sides == 0) {
                val state = if (code and 0b1100 == 0b1100) { var chunks = 0; var n = next(); while (n == 0b1111) { chunks++; n = next() }; n + 15 * chunks + 3 } else code shr 2
                return Node.Leaf(state)
            }
            // Stored last child first; kept here in the engine's child order.
            val reversed = List(sides + 1) { node(depth + 1) }
            return Node.Split(sides, code shr 2, reversed.asReversed())
        }
        return node(0)
    }

    fun encode(n: Node): String {
        val nibbles = ArrayList<Int>()
        fun put(n: Node) {
            when (n) {
                is Node.Leaf -> when {
                    n.state < 3 -> nibbles += n.state shl 2
                    else -> { nibbles += 0b1100; var rest = n.state - 3; while (rest >= 15) { nibbles += 0b1111; rest -= 15 }; nibbles += rest }
                }
                is Node.Split -> { nibbles += (n.specialSide shl 2) or n.sides; n.children.asReversed().forEach(::put) }
            }
        }
        put(n)
        return buildString { for (i in nibbles.indices.reversed()) append("0123456789ABCDEF"[nibbles[i]]) }
    }

    /** The string for a whole triangle in one state. */
    fun whole(state: Int): String = encode(Node.Leaf(state))

    fun map(n: Node, f: (Int) -> Int): Node = when (n) {
        is Node.Leaf -> Node.Leaf(f(n.state))
        is Node.Split -> n.copy(children = n.children.map { map(it, f) })
    }

    /** [s] with every state passed through [f]; null when the result is all state 0 (nothing painted). */
    fun remap(s: String, f: (Int) -> Int): String? {
        val m = map(decode(s), f)
        return if (states(m).all { it == 0 }) null else encode(m)
    }

    fun states(n: Node): Set<Int> = when (n) { is Node.Leaf -> setOf(n.state); is Node.Split -> n.children.flatMapTo(HashSet()) { states(it) } }

    /**
     * The leaf triangles of a painted triangle with corners [a], [b], [c] (x,y,z each), as [emit] calls with nine
     * coordinates and a state. Midpoints are placed exactly as TriangleSelector::perform_split places them.
     */
    fun leaves(n: Node, a: FloatArray, b: FloatArray, c: FloatArray, emit: (FloatArray, FloatArray, FloatArray, Int) -> Unit) {
        when (n) {
            is Node.Leaf -> emit(a, b, c, n.state)
            is Node.Split -> {
                val corners = arrayOf(a, b, c)
                val v = List(3) { corners[(n.specialSide + it) % 3] }
                fun mid(p: FloatArray, q: FloatArray) = floatArrayOf((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2)
                val tris: List<Array<FloatArray>> = when (n.sides) {
                    1 -> { val m = mid(v[2], v[1]); listOf(arrayOf(v[0], v[1], m), arrayOf(m, v[2], v[0])) }
                    2 -> { val m1 = mid(v[1], v[0]); val m2 = mid(v[0], v[2])
                        listOf(arrayOf(v[0], m1, m2), arrayOf(m1, v[1], m2), arrayOf(v[1], v[2], m2)) }
                    else -> { val m0 = mid(v[1], v[0]); val m1 = mid(v[2], v[1]); val m2 = mid(v[0], v[2])
                        listOf(arrayOf(v[0], m0, m2), arrayOf(m0, v[1], m1), arrayOf(m1, v[2], m2), arrayOf(m0, m1, m2)) }
                }
                tris.forEachIndexed { i, t -> n.children.getOrNull(i)?.let { leaves(it, t[0], t[1], t[2], emit) } }
            }
        }
    }
}

/** A filament a 3MF from another slicer was set up with (its `filament_colour`, `filament_type`, profile name). */
data class SourceFilament(val index: Int, val colorHex: String?, val type: String?, val name: String?)
