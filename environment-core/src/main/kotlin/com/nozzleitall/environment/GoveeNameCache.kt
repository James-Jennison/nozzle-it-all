package com.nozzleitall.environment

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Names/identifiers only, never credentials. All methods belong on an IO worker.
 * Atomic replacement preserves the last directory on failed writes. No network IO.
 */
class GoveeNameCache(private val file: File) {
    fun read(): List<GoveeDeviceName> = try {
        if (!file.exists()) emptyList() else {
            val bytes = file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= GoveeNameApi.MAX_BYTES)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            GoveeNameApi.parse(text)
        }
    } catch (_: Exception) { throw GoveeNameException(GoveeNameFailure.DIRECTORY) }

    fun write(devices: List<GoveeDeviceName>, commit: (() -> Unit) -> Unit = { it() }) {
        var temporary: File? = null
        try {
            val rows = JSONArray()
            devices.forEach { rows.put(JSONObject().put("device", it.device).put("sku", it.model).put("deviceName", it.name)) }
            val text = JSONObject().put("code", 200).put("data", rows).toString()
            GoveeNameApi.parse(text) // Same bounded validation as an imported directory.
            check(file.parentFile.isDirectory || file.parentFile.mkdirs())
            temporary = File.createTempFile(".govee-names-", ".tmp", file.parentFile)
            // Do not change permissions on an existing parent shared with other app data.
            if (Files.getFileStore(temporary.toPath()).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val staged = temporary.toPath()
            commit { Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: GoveeNameException) { throw e
        } catch (_: Exception) { throw GoveeNameException(GoveeNameFailure.DIRECTORY) }
        finally { temporary?.let { if (it.exists()) it.delete() } }
    }
}
