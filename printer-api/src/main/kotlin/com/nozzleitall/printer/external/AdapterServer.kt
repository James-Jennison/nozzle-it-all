package com.nozzleitall.printer.external

import com.nozzleitall.printer.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves one [DeviceAdapter] over the nozzle-adapter protocol on the given streams (a helper's stdin/stdout).
 * Requests run concurrently so a slow cloud call cannot block a status read for another printer.
 */
class AdapterServer(
    private val adapter: DeviceAdapter,
    private val account: AccountAdapter? = null,
    private val adapterVersion: String = "0",
) {
    private val sessions = ConcurrentHashMap<String, PrinterSession>()
    private val sessionIds = AtomicLong(1)

    fun serve(input: InputStream, output: OutputStream) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        val lock = Any()
        fun emit(o: JSONObject) = synchronized(lock) { writer.write(o.toString()); writer.write("\n"); writer.flush() }
        emit(AdapterProtocol.hello {
            put("adapter", JSONObject().put("id", adapter.id).put("displayName", adapter.displayName)
                .put("firmware", JSONArray(adapter.firmware.map { it.name })).put("mayUseVendorCloud", adapter.mayUseVendorCloud)
                .put("version", adapterVersion))
        })
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val hostHello = reader.readLine()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (AdapterProtocol.incompatibility(hostHello) != null) return
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "adapter-request").apply { isDaemon = true } }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (msg.optString("type") != "request") continue
                val id = msg.optLong("id")
                val method = msg.optString("method")
                if (method == "shutdown") { emit(AdapterProtocol.result(id, JSONObject())); break }
                pool.execute {
                    val reply = try { AdapterProtocol.result(id, handle(method, msg.optJSONObject("params") ?: JSONObject())) }
                        catch (e: UnsupportedMethod) { AdapterProtocol.error(id, "unsupported", e.message ?: method) }
                        catch (e: Exception) { AdapterProtocol.error(id, "failed", e.message ?: e.javaClass.simpleName) }
                    emit(reply)
                }
            }
        } finally {
            pool.shutdownNow()
            sessions.values.forEach { runCatching { it.close() } }
        }
    }

    private class UnsupportedMethod(method: String) : Exception("Unsupported method: $method")

    private fun session(p: JSONObject) = sessions[p.getString("session")] ?: throw IllegalStateException("Unknown session.")

    private fun handle(method: String, p: JSONObject): Any = when (method) {
        "probe" -> adapter.probe(p.getString("address"))?.let(AdapterProtocol::encode) ?: JSONObject.NULL
        "open" -> {
            val s = adapter.open(AdapterProtocol.decodeConfig(p.getJSONObject("config")))
            val id = "s${sessionIds.getAndIncrement()}"
            sessions[id] = s
            JSONObject().put("session", id).put("capabilities", AdapterProtocol.encode(s.capabilities))
        }
        "status" -> AdapterProtocol.encode(session(p).status())
        "cameras" -> JSONArray(session(p).cameras().map(AdapterProtocol::encode))
        "snapshot" -> JSONObject().put("jpegBase64", Base64.getEncoder().encodeToString(session(p).snapshot(AdapterProtocol.decodeCamera(p.getJSONObject("camera")))))
        "upload" -> when (val r = session(p).upload(File(p.getString("path")), p.getString("remoteName"))) {
            is UploadResult.Uploaded -> JSONObject().put("result", "uploaded").put("remotePath", r.remotePath)
            is UploadResult.Interrupted -> JSONObject().put("result", "interrupted").put("reason", r.reason)
            is UploadResult.Failed -> JSONObject().put("result", "failed").put("reason", r.reason)
        }
        "perform" -> {
            val action = AdapterProtocol.decodeAction(p.getJSONObject("action"))
            AdapterProtocol.encode(action?.let { session(p).perform(it) } ?: ActionOutcome.Rejected("This adapter does not know that command."))
        }
        "close" -> { sessions.remove(p.getString("session"))?.close(); JSONObject() }
        "account.status" -> encode(account?.account() ?: AdapterAccount(AdapterAccountState.NOT_REQUIRED))
        "account.signIn" -> encode(account?.beginSignIn() ?: AdapterAccount(AdapterAccountState.NOT_REQUIRED))
        "account.complete" -> encode(account?.completeSignIn(p.getString("response")) ?: AdapterAccount(AdapterAccountState.NOT_REQUIRED))
        "account.signOut" -> encode(account?.signOut() ?: AdapterAccount(AdapterAccountState.NOT_REQUIRED))
        else -> throw UnsupportedMethod(method)
    }

    private fun encode(a: AdapterAccount) = JSONObject().put("state", a.state.name).put("detail", a.detail).putOpt("signInUrl", a.signInUrl)
}
