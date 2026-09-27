package com.nozzleitall.desktop

import com.nozzleitall.printer.FirmwareFamily
import com.nozzleitall.printer.PrinterConfig
import com.nozzleitall.printer.PrinterIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Saved printers. Addresses and names live in printers.json; API keys live in printer-secrets.json (owner-only
 * permissions) so the main file can be backed up or shared without secrets. Unknown fields written by a newer version
 * are kept.
 */
class PrinterStore(private val paths: AppPaths) {
    fun load(): List<PrinterConfig> {
        val list = runCatching { JSONObject(paths.printers.readText()).optJSONArray("printers") }.getOrNull() ?: return emptyList()
        val secrets = runCatching { JSONObject(paths.secrets.readText()) }.getOrDefault(JSONObject())
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val firmware = FirmwareFamily.entries.firstOrNull { it.name == o.optString("firmware") } ?: FirmwareFamily.PAXX
            val extras = o.optJSONObject("extras")?.let { e -> e.keySet().associateWith { e.optString(it) } } ?: emptyMap()
            PrinterConfig(PrinterIdentity(id, o.optString("name"), o.optString("model", "Snapmaker U1"), firmware, o.optString("address")),
                o.optString("adapter", "paxx-lan"), secrets.optString(id), extras)
        }
    }

    fun save(printers: List<PrinterConfig>) {
        val previous = runCatching { JSONObject(paths.printers.readText()) }.getOrDefault(JSONObject())
        val previousById = previous.optJSONArray("printers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optString("id") } } ?: emptyMap()
        val arr = JSONArray(printers.map { p ->
            (previousById[p.identity.id] ?: JSONObject()).put("id", p.identity.id).put("name", p.identity.displayName).put("model", p.identity.model)
                .put("firmware", p.identity.firmware.name).put("address", p.identity.address).put("adapter", p.adapterId).put("extras", JSONObject(p.extras))
        })
        atomicWrite(paths.printers, previous.put("version", 1).put("printers", arr).toString(2), private = false)
        atomicWrite(paths.secrets, JSONObject(printers.filter { it.secret.isNotBlank() }.associate { it.identity.id to it.secret }).toString(), private = true)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun atomicWrite(target: File, text: String, private: Boolean) {
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeText(text)
            if (private) runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
