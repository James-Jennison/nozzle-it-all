package com.nozzleitall.desktop.queue

import com.nozzleitall.desktop.PrinterStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A sliced file waiting for a printer. It keeps its own copy of the file, so it survives the project being edited or the cache being cleared. */
data class QueueItem(
    val id: String,
    val name: String,
    /** The file's name inside the queue folder. */
    val fileName: String,
    /** The printer it is meant for, or null for "whichever is free". */
    val printerId: String?,
    /** Which toolhead feeds each of the file's filaments; empty for a single-material printer. */
    val toolheadMap: List<Int>,
    val addedAtMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("fileName", fileName).put("addedAt", addedAtMillis)
        .put("toolheadMap", JSONArray(toolheadMap)).apply { printerId?.let { put("printerId", it) } }

    companion object {
        fun fromJson(j: JSONObject) = QueueItem(j.getString("id"), j.optString("name"), j.getString("fileName"), j.optString("printerId").ifBlank { null },
            j.optJSONArray("toolheadMap")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(), j.optLong("addedAt"))
    }
}

/**
 * The print queue: an ordered list of sliced files, kept in [dir] with a small index. It never starts anything: whoever shows it asks the
 * user before a file goes to a printer, because only a person can tell the plate is clear.
 */
class PrintQueue(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val index get() = File(dir, "queue.json")
    private var list: MutableList<QueueItem> = load().toMutableList()

    private fun load(): List<QueueItem> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).mapNotNull { runCatching { QueueItem.fromJson(a.getJSONObject(it)) }.getOrNull() }.filter { File(dir, it.fileName).isFile }
    }.getOrDefault(emptyList())

    private fun save() { dir.mkdirs(); PrinterStore.atomicWrite(index, JSONArray(list.map { it.toJson() }).toString(2), private = false) }

    fun items(): List<QueueItem> = list.toList()
    fun file(item: QueueItem): File = File(dir, item.fileName)

    fun add(name: String, source: File, printerId: String?, toolheadMap: List<Int> = emptyList()): QueueItem {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val stored = "$id.gcode"
        source.copyTo(File(dir, stored), overwrite = true)
        return QueueItem(id, name, stored, printerId, toolheadMap, clock()).also { list.add(it); save() }
    }

    fun remove(id: String) {
        val item = list.firstOrNull { it.id == id } ?: return
        list.remove(item); File(dir, item.fileName).delete(); save()
    }

    /** Moves an item [by] places (negative = earlier), staying inside the list. */
    fun move(id: String, by: Int) {
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from)); save()
    }

    /** Points an item at another printer, or at any. */
    fun retarget(id: String, printerId: String?) {
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        list[i] = list[i].copy(printerId = printerId); save()
    }

    /** The first item that may run on [printerId]: one meant for it, or one meant for any printer. */
    fun nextFor(printerId: String): QueueItem? = list.firstOrNull { it.printerId == null || it.printerId == printerId }

    /** Items whose printer was removed fall back to "any". */
    fun forgetPrinter(printerId: String) { var changed = false
        list.indices.forEach { if (list[it].printerId == printerId) { list[it] = list[it].copy(printerId = null); changed = true } }
        if (changed) save() }
}
