package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

data class BedMeshStatus(val profileName: String, val meshMin: List<Double>, val meshMax: List<Double>, val probedMatrix: List<List<Double>>) {
    val hasMesh: Boolean get() = probedMatrix.isNotEmpty() && probedMatrix.all { it.isNotEmpty() }
    val flatValues: List<Double> get() = probedMatrix.flatten().filter { it.isFinite() }
}
interface MeshReader : AutoCloseable {
    fun meshStatus(): BedMeshStatus
}
object BedMesh {
    private const val MAX_ROWS = 64
    private const val MAX_COLS = 64
    fun parse(result: JSONObject): BedMeshStatus {
        val status = result.getJSONObject("status")
        val mesh = status.optJSONObject("bed_mesh")
        fun doubles(array: JSONArray?): List<Double> =
            array?.let { (0 until it.length()).map { i -> it.optDouble(i) } }?.filter { it.isFinite() } ?: emptyList()
        val matrix = mesh?.optJSONArray("probed_matrix")
        val rows = matrix?.let { array ->
            require(array.length() <= MAX_ROWS) { "Bed mesh has too many rows." }
            (0 until array.length()).map { r ->
                val row = doubles(array.optJSONArray(r))
                require(row.size <= MAX_COLS) { "Bed mesh has too many columns." }
                row
            }
        } ?: emptyList()
        return BedMeshStatus(mesh?.optString("profile_name", "") ?: "", doubles(mesh?.optJSONArray("mesh_min")), doubles(mesh?.optJSONArray("mesh_max")), rows)
    }
}
