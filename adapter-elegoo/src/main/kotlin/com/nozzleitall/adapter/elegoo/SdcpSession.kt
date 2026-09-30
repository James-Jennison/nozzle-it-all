package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** How long each step may take. Tests shorten them; the defaults suit a printer on the LAN. */
data class ElegooTimeouts(val connectMs: Long = 4_000, val statusMs: Long = 2_500, val slotsMs: Long = 2_500, val commandMs: Long = 10_000,
                          val discoveryMs: Int = 1_500)

/**
 * A Centauri Carbon on stock firmware, over SDCP (see [Sdcp] for the sources). One WebSocket per session, opened when
 * first needed and reopened after it drops; every status reading asks for a fresh status (Cmd 0) and the CANVAS slots
 * (Cmd 324), both read-only. Commands wait for the printer's Ack: 0 is Accepted, any other Ack is the printer refusing
 * (Rejected); no reply, or a connection lost after sending, is Unknown. Nothing is ever retried.
 *
 * The printer's MainboardID addresses every request. It comes from the saved printer ("serial"), else from asking the
 * printer by UDP discovery, else from the first message the printer sends.
 */
class SdcpSession internal constructor(
    override val identity: PrinterIdentity,
    override val capabilities: Capabilities,
    private val host: ElegooHost,
    mainboardId: String?,
    private val wsPort: Int = host.port ?: Sdcp.WEBSOCKET_PORT,
    /** elegoo-link uploads to the printer's bare address (UrlUtils::extractEndpoint, so port 80); the SDCP document says 3030. */
    private val httpPort: Int = 80,
    private val discoveryPort: Int = Sdcp.DISCOVERY_PORT,
    private val timeouts: ElegooTimeouts = ElegooTimeouts(),
) : PrinterSession {
    private val route = routeFor(host.host)
    @Volatile var mainboardId: String? = mainboardId?.trim()?.ifEmpty { null }; private set
    private val client = OkHttpClient.Builder().connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    private val upload by lazy { ElegooUpload(HttpUrl.Builder().scheme("http").host(host.host).port(httpPort).build()) }

    private val lock = Object()
    @Volatile private var conn: Connection? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    // "generatePrinterRequestId" in elegoo-link's message_adapter.cpp: a counter from 100000, sent as a string.
    private val requestIds = AtomicInteger(100_000)
    private var latestStatus: JSONObject? = null
    private var statusSeq = 0L
    private var statusAtMillis = 0L
    @Volatile private var slots: Canvas.State? = null
    private var slotTimeouts = 0
    private var slotsPausedUntil = 0L
    private var lastPingMillis = 0L
    @Volatile private var printerError: String? = null

    /** One WebSocket. Callbacks from a replaced connection are ignored. */
    private inner class Connection : WebSocketListener() {
        val opened = CountDownLatch(1)
        @Volatile var alive = false
        @Volatile var failure: Throwable? = null
        lateinit var socket: WebSocket

        override fun onOpen(webSocket: WebSocket, response: Response) { alive = true; opened.countDown() }
        override fun onMessage(webSocket: WebSocket, text: String) { if (conn === this) handle(text) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); lost() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure = t; opened.countDown(); lost() }
        private fun lost() {
            alive = false
            if (conn === this) pending.values.forEach { it.completeExceptionally(IOException("The connection to the printer was lost.")) }
        }
    }

    private fun handle(text: String) {
        if (text.length > ElegooNet.MAX_MESSAGE_BYTES || text == "pong") return
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        if (mainboardId == null) Sdcp.mainboardIdOf(json)?.let { mainboardId = it }
        val topic = json.optString("Topic")
        when {
            json.has("Status") -> synchronized(lock) { latestStatus = json; statusSeq++; statusAtMillis = System.currentTimeMillis(); lock.notifyAll() }
            topic.startsWith("sdcp/error") -> printerError = json.optJSONObject("Data")?.optJSONObject("Data")?.opt("ErrorCode")?.let { "The printer reported error $it." }
            else -> json.optJSONObject("Data")?.optString("RequestID")?.takeIf { it.isNotEmpty() }?.let { pending.remove(it)?.complete(json) }
        }
    }

    private fun open(): Connection {
        conn?.takeIf { it.alive }?.let { return it }
        synchronized(this) {
            conn?.takeIf { it.alive }?.let { return it }
            conn?.socket?.cancel()
            if (mainboardId == null) mainboardId = ElegooDiscovery.askSdcp(host.host, discoveryPort, timeouts.discoveryMs)?.mainboardId?.ifEmpty { null }
            val c = Connection()
            conn = c
            c.socket = client.newWebSocket(Request.Builder().url("http://${host.urlHost}:$wsPort${Sdcp.WEBSOCKET_PATH}").build(), c)
            if (!c.opened.await(timeouts.connectMs, TimeUnit.MILLISECONDS) || !c.alive) {
                c.socket.cancel()
                throw IOException(c.failure?.message?.let { "Couldn't connect to the printer: $it" } ?: "The printer did not answer.")
            }
            return c
        }
    }

    /** Sends one request; the future completes with the printer's response. Null if it couldn't be queued (nothing sent). */
    private fun send(c: Connection, cmd: Int, data: JSONObject = JSONObject()): Pair<String, CompletableFuture<JSONObject>>? {
        val id = requestIds.getAndIncrement().toString()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val text = Sdcp.request(cmd, data, mainboardId ?: "", id, System.currentTimeMillis() / 1000).toString()
        if (!c.socket.send(text)) { pending.remove(id); return null }
        return id to future
    }

    private fun await(id: String, future: CompletableFuture<JSONObject>, ms: Long): JSONObject? = try {
        future.get(ms, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) { null } catch (e: ExecutionException) { null } finally { pending.remove(id) }

    override fun status(): PrinterStatus {
        val c = try { open() } catch (e: Exception) { return PrinterStatus(PrinterState.OFFLINE, route, message = e.message ?: "The printer did not answer.") }
        val now = System.currentTimeMillis()
        if (now - lastPingMillis > 10_000) { c.socket.send(Sdcp.HEARTBEAT); lastPingMillis = now }
        // Cmd 0 makes the printer report its status again on the status topic (SDCP document).
        val seq = synchronized(lock) { statusSeq }
        val asked = send(c, Sdcp.CMD_STATUS) ?: return PrinterStatus(PrinterState.OFFLINE, route, message = "The connection to the printer was lost.")
        val message = synchronized(lock) {
            val until = System.currentTimeMillis() + timeouts.statusMs
            while (statusSeq == seq && System.currentTimeMillis() < until && c.alive) lock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
            pending.remove(asked.first)
            latestStatus?.takeIf { statusSeq != seq || System.currentTimeMillis() - statusAtMillis < 30_000 }
        } ?: return PrinterStatus(if (c.alive) PrinterState.UNKNOWN else PrinterState.OFFLINE, route, message = "The printer didn't send its status.")
        readSlots(c)
        val r = Sdcp.parseStatus(message) ?: return PrinterStatus(PrinterState.UNKNOWN, route, message = "The printer's status was not understood.")
        return PrinterStatus(r.state, route, r.job, if (r.bed != null || r.bedTarget != null) Temperature(r.bed, r.bedTarget) else null,
            Canvas.toolheads(slots, r.nozzle, r.nozzleTarget), r.message ?: printerError, observedAtMillis = synchronized(lock) { statusAtMillis })
    }

    /** Cmd 324. A printer without CANVAS support (or older firmware) that doesn't answer is asked again only every minute. */
    private fun readSlots(c: Connection) {
        if (System.currentTimeMillis() < slotsPausedUntil) return
        val (id, future) = send(c, Sdcp.CMD_CANVAS) ?: return
        val reply = await(id, future, timeouts.slotsMs)
        if (reply == null) { if (++slotTimeouts >= 2) { slotsPausedUntil = System.currentTimeMillis() + 60_000; slotTimeouts = 0 }; return }
        slotTimeouts = 0
        val result = reply.optJSONObject("Data")?.optJSONObject("Data") ?: return
        if ((result.opt("Ack") as? Number)?.toInt() != 0) { slots = null; return }
        // elegoo-link's Centauri Carbon adapter reads the slots straight from Data.Data; its CC2 adapter from a
        // "canvas_info" object. Both shapes are accepted.
        slots = Canvas.parse(result.optJSONObject("canvas_info") ?: result)
    }

    override fun cameras(): List<CameraEndpoint> = emptyList()
    override fun snapshot(camera: CameraEndpoint): ByteArray = throw IOException("Nozzle It All doesn't show this printer's camera yet.")

    override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult = try {
        upload.sdcp(file, ElegooNet.validateRemoteName(remoteName), progress)
    } catch (e: IllegalArgumentException) { UploadResult.Failed(e.message ?: "Invalid upload.") }
      catch (e: IOException) { UploadResult.Failed(e.message ?: "The upload failed.") }

    override fun perform(action: PrinterAction): ActionOutcome {
        // Validation first: an invalid or unsupported request is rejected without contacting the printer.
        val (cmd, data) = try {
            when (action) {
                is PrinterAction.StartJob -> {
                    if (!capabilities.startPrint) return unsupported()
                    Sdcp.CMD_START_PRINT to Sdcp.startPrintData(ElegooNet.validateRemoteName(action.remotePath), Canvas.slotMap(action.toolheadMap, slots))
                }
                PrinterAction.Pause -> if (capabilities.pausePrint) Sdcp.CMD_PAUSE to JSONObject() else return unsupported()
                PrinterAction.Resume -> if (capabilities.resumePrint) Sdcp.CMD_RESUME to JSONObject() else return unsupported()
                PrinterAction.Cancel -> if (capabilities.cancelPrint) Sdcp.CMD_STOP to JSONObject() else return unsupported()
                is PrinterAction.UploadAndStart -> return ActionOutcome.Rejected("Send the file first, then start it.")
                else -> return unsupported()
            }
        } catch (e: IllegalArgumentException) { return ActionOutcome.Rejected(e.message ?: "Invalid command.") }
        val c = try { open() } catch (e: Exception) { return ActionOutcome.Rejected("Couldn't reach the printer. Nothing was sent.") }
        if (mainboardId == null) return ActionOutcome.Rejected("Nozzle It All couldn't identify this printer (no mainboard ID). Nothing was sent.")
        val (id, future) = send(c, cmd, data) ?: return ActionOutcome.Rejected("The connection to the printer closed before sending. Nothing was sent.")
        val reply = try { future.get(timeouts.commandMs, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { return ActionOutcome.Unknown("The printer didn't confirm. The command may or may not have run; check the printer before trying again.") }
            catch (e: ExecutionException) { return ActionOutcome.Unknown("${e.cause?.message ?: "No reply"} The command may or may not have run; check the printer before trying again.") }
            finally { pending.remove(id) }
        return when (val ack = (reply.optJSONObject("Data")?.optJSONObject("Data")?.opt("Ack") as? Number)?.toInt()) {
            0 -> ActionOutcome.Accepted
            null -> ActionOutcome.Unknown("The printer's reply was not understood. Check the printer before trying again.")
            else -> ActionOutcome.Rejected(Sdcp.ackReason(ack))
        }
    }

    private fun unsupported() = ActionOutcome.Rejected("${identity.displayName} doesn't support that from Nozzle It All.")

    override fun close() {
        conn?.let { it.alive = false; it.socket.close(1000, null) }
        conn = null
        pending.values.forEach { it.completeExceptionally(IOException("Closed.")) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
