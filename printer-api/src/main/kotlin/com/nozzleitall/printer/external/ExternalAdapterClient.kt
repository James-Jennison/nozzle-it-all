package com.nozzleitall.printer.external

import com.nozzleitall.printer.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Account state an optional adapter may need. Vendor-neutral: the core only knows "this adapter wants a sign-in". */
enum class AdapterAccountState { NOT_REQUIRED, SIGNED_OUT, SIGNING_IN, SIGNED_IN, EXPIRED, UNAVAILABLE }
data class AdapterAccount(val state: AdapterAccountState, val detail: String = "", val signInUrl: String? = null)

interface AccountAdapter {
    fun account(): AdapterAccount
    /** Starts a system-browser sign-in owned by the adapter. Returns the URL the user should open. */
    fun beginSignIn(): AdapterAccount
    /** Finishes a sign-in with what the user brought back from the browser (for example a pasted token). */
    fun completeSignIn(response: String): AdapterAccount
    fun signOut(): AdapterAccount
}

/**
 * A [DeviceAdapter] running as a separate helper process (docs/protocols/ADAPTER_PROTOCOL.md). The process is started
 * on first use, never at application start. If it exits, its sessions report OFFLINE with the reason; nothing else in
 * the application is affected.
 */
class ExternalAdapterClient(
    private val command: List<String>,
    private val workingDirectory: File? = null,
    private val requestTimeoutMillis: Long = 20_000,
    private val log: (String) -> Unit = {},
) : DeviceAdapter, AccountAdapter, AutoCloseable {
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    @Volatile private var process: Process? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var helloInfo: JSONObject? = null
    @Volatile private var failure: String? = null

    override val id: String get() = info().optJSONObject("adapter")?.optString("id") ?: "external"
    override val displayName: String get() = info().optJSONObject("adapter")?.optString("displayName") ?: "External adapter"
    override val firmware: Set<FirmwareFamily> get() = info().optJSONObject("adapter")?.optJSONArray("firmware")
        ?.let { a -> (0 until a.length()).mapNotNull { n -> FirmwareFamily.entries.firstOrNull { it.name == a.optString(n) } }.toSet() } ?: emptySet()
    override val mayUseVendorCloud: Boolean get() = info().optJSONObject("adapter")?.optBoolean("mayUseVendorCloud", true) ?: true

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    private fun info(): JSONObject {
        helloInfo?.let { if (process?.isAlive == true) return it }
        start()
        return helloInfo ?: throw AdapterUnavailable(failure ?: "The adapter did not start.")
    }

    private fun start() {
        pending.clear(); helloInfo = null; failure = null
        val p = try {
            ProcessBuilder(command).apply { workingDirectory?.let { directory(it) } }.redirectError(ProcessBuilder.Redirect.PIPE).start()
        } catch (e: Exception) { failure = "The adapter could not be started: ${e.message}"; throw AdapterUnavailable(failure!!) }
        process = p
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
        val firstLine = CompletableFuture<String?>()
        Thread({
            try {
                firstLine.complete(reader.readLine())
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.length > AdapterProtocol.MAX_LINE_BYTES) { log("adapter: oversized line dropped"); continue }
                    val msg = try { JSONObject(line) } catch (e: Exception) { log("adapter: unreadable line dropped"); continue }
                    if (msg.optString("type") == "response") pending.remove(msg.optLong("id"))?.complete(msg)
                }
            } catch (_: Exception) { firstLine.complete(null) }
            val reason = "The adapter stopped (exit ${runCatching { p.waitFor(); p.exitValue() }.getOrNull()})."
            failure = reason
            pending.values.forEach { it.completeExceptionally(AdapterUnavailable(reason)) }; pending.clear()
        }, "nozzle-adapter-reader").apply { isDaemon = true }.start()
        Thread({ p.errorStream.bufferedReader().forEachLine { log("adapter stderr: $it") } }, "nozzle-adapter-stderr").apply { isDaemon = true }.start()

        val hello = try { firstLine.get(requestTimeoutMillis, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
        val parsed = hello?.let { runCatching { JSONObject(it) }.getOrNull() }
        val problem = if (parsed == null) "The adapter did not answer the protocol greeting." else AdapterProtocol.incompatibility(parsed)
        if (problem != null) { failure = problem; p.destroy(); throw AdapterUnavailable(problem) }
        send(AdapterProtocol.hello { put("host", "nozzle-it-all") })
        helloInfo = parsed
    }

    @Synchronized
    private fun send(message: JSONObject) {
        val w = writer ?: throw AdapterUnavailable(failure ?: "The adapter is not running.")
        w.write(message.toString()); w.write("\n"); w.flush()
    }

    fun call(method: String, params: JSONObject = JSONObject(), timeoutMillis: Long = requestTimeoutMillis): Any {
        info()
        val id = ids.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        try { send(AdapterProtocol.request(id, method, params)) } catch (e: Exception) { pending.remove(id); throw AdapterUnavailable(failure ?: "The adapter is not running.") }
        val reply = try { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { pending.remove(id); throw AdapterTimeout("The adapter did not answer \"$method\" in time.") }
            catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: AdapterUnavailable("The adapter failed.") }
        reply.optJSONObject("error")?.let { throw AdapterError(it.optString("code"), it.optString("message", "The adapter reported an error.")) }
        return reply.opt("result") ?: JSONObject.NULL
    }

    override fun probe(address: String): DiscoveredPrinter? =
        (call("probe", JSONObject().put("address", address)) as? JSONObject)?.let(AdapterProtocol::decodeDiscovered)

    override fun open(config: PrinterConfig): PrinterSession {
        val result = call("open", JSONObject().put("config", AdapterProtocol.encode(config))) as JSONObject
        return ExternalSession(result.getString("session"), config.identity, AdapterProtocol.decodeCapabilities(result.getJSONObject("capabilities")))
    }

    override fun account(): AdapterAccount = decodeAccount(call("account.status") as JSONObject)
    override fun beginSignIn(): AdapterAccount = decodeAccount(call("account.signIn") as JSONObject)
    override fun completeSignIn(response: String): AdapterAccount = decodeAccount(call("account.complete", JSONObject().put("response", response)) as JSONObject)
    override fun signOut(): AdapterAccount = decodeAccount(call("account.signOut") as JSONObject)
    private fun decodeAccount(o: JSONObject) = AdapterAccount(AdapterProtocol.enumOr(o.optString("state"), AdapterAccountState.UNAVAILABLE),
        o.optString("detail"), o.optString("signInUrl").takeIf { it.isNotBlank() })

    override fun close() {
        runCatching { send(AdapterProtocol.request(ids.getAndIncrement(), "shutdown")) }
        process?.let { p -> if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null; writer = null; helloInfo = null
    }

    private inner class ExternalSession(val sessionId: String, override val identity: PrinterIdentity, override val capabilities: Capabilities) : PrinterSession {
        private fun params() = JSONObject().put("session", sessionId)
        override fun status(): PrinterStatus = try {
            AdapterProtocol.decodeStatus(call("status", params()) as JSONObject)
        } catch (e: Exception) {
            // A stopped or unhealthy helper only ever makes its own printers offline.
            PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.VENDOR_CLOUD, message = e.message)
        }
        override fun cameras(): List<CameraEndpoint> = (call("cameras", params()) as JSONArray).let { a -> (0 until a.length()).map { AdapterProtocol.decodeCamera(a.getJSONObject(it)) } }
        override fun snapshot(camera: CameraEndpoint): ByteArray =
            Base64.getDecoder().decode((call("snapshot", params().put("camera", AdapterProtocol.encode(camera))) as JSONObject).getString("jpegBase64"))
        override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult = try {
            val r = call("upload", params().put("path", file.absolutePath).put("remoteName", remoteName), timeoutMillis = 30 * 60_000L) as JSONObject
            when (r.optString("result")) {
                "uploaded" -> UploadResult.Uploaded(r.getString("remotePath")).also { progress.onProgress(file.length(), file.length()) }
                "interrupted" -> UploadResult.Interrupted(r.optString("reason"))
                else -> UploadResult.Failed(r.optString("reason", "Upload failed."))
            }
        } catch (e: AdapterTimeout) { UploadResult.Interrupted(e.message ?: "Upload timed out.") }
          catch (e: Exception) { UploadResult.Failed(e.message ?: "Upload failed.") }
        override fun perform(action: PrinterAction): ActionOutcome = try {
            AdapterProtocol.decodeOutcome(call("perform", params().put("action", AdapterProtocol.encode(action)), timeoutMillis = 75_000) as JSONObject)
        } catch (e: AdapterError) { ActionOutcome.Rejected(e.message ?: "Rejected.") }
          catch (e: Exception) { ActionOutcome.Unknown(e.message ?: "No reply from the adapter.") }
        override fun close() { runCatching { call("close", params()) } }
    }
}

class AdapterTimeout(message: String) : Exception(message)
class AdapterError(val code: String, message: String) : Exception(message)
