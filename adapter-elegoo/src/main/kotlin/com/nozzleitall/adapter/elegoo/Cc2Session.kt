package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Centauri Carbon 2 over its own MQTT broker on the LAN (see [Cc2] for the sources). One MQTT connection per session:
 * connect with the access code, subscribe, register this client, then ask for the full status (1002) and, if it has
 * no slots in it, the CANVAS (2005); 6000 events are merged into the cached status between readings. Commands wait for
 * the reply with the same id: error_code 0 is Accepted, any other code is the printer refusing (Rejected); no reply, or
 * a connection lost after sending, is Unknown. Nothing is ever retried.
 *
 * The serial number names every topic. It comes from the saved printer ("serial"), else UDP discovery, else the
 * printer's /system/info (as elegoo-link's validateConnectionParams does when it has no serial).
 */
class Cc2Session internal constructor(
    override val identity: PrinterIdentity,
    override val capabilities: Capabilities,
    private val host: ElegooHost,
    serial: String?,
    accessCode: String,
    private val mqttPort: Int = Cc2.MQTT_PORT,
    private val httpPort: Int = 80,
    private val discoveryPort: Int = Cc2.DISCOVERY_PORT,
    private val timeouts: ElegooTimeouts = ElegooTimeouts(),
) : PrinterSession {
    private val route = routeFor(host.host)
    private val token = accessCode.trim().ifEmpty { Cc2.DEFAULT_ACCESS_CODE }
    @Volatile var serial: String? = serial?.trim()?.ifEmpty { null }; private set
    // elegoo-link ElegooCC2MqttProtocol(): "1_PC_" + a random 4-digit number; the registration request id is "<client>_req".
    private val clientId = "1_PC_" + (1000..9999).random()
    private val requestId = clientId + "_req"
    private val ids = AtomicInteger(100_000)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    @Volatile private var mqtt: MiniMqttClient? = null
    @Volatile private var registration: CompletableFuture<JSONObject>? = null
    private val lock = Any()
    private var fullStatus: JSONObject? = null
    private var statusAtMillis = 0L
    @Volatile private var slots: Canvas.State? = null
    private var slotTimeouts = 0
    private var slotsPausedUntil = 0L
    private var heartbeat: ScheduledExecutorService? = null
    private val base = HttpUrl.Builder().scheme("http").host(host.host).port(httpPort).build()
    private val upload by lazy { ElegooUpload(base) }

    private fun onMessage(topic: String, payload: ByteArray) {
        val json = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (e: Exception) { return }
        if (topic.endsWith("/register_response")) { if (json.optString("client_id") == clientId) registration?.complete(json); return }
        if (json.optString("type") == "PONG") return
        val method = (json.opt("method") as? Number)?.toInt()
        if (method == Cc2.EVENT_STATUS) {
            val delta = json.optJSONObject("result") ?: return
            synchronized(lock) { fullStatus?.let { Cc2.merge(it, delta); statusAtMillis = System.currentTimeMillis() } }
            return
        }
        (json.opt("id") as? Number)?.toInt()?.let { pending.remove(it)?.complete(json) }
    }

    private fun onLost(reason: String) {
        pending.values.forEach { it.completeExceptionally(IOException(reason)) }
    }

    private fun open(): MiniMqttClient {
        mqtt?.takeIf { it.alive }?.let { return it }
        synchronized(this) {
            mqtt?.takeIf { it.alive }?.let { return it }
            mqtt?.close(); heartbeat?.shutdownNow()
            val sn = serial ?: ElegooDiscovery.askCc2(host.host, discoveryPort, timeouts.discoveryMs)?.serial?.ifEmpty { null } ?: systemInfoSerial()
                ?: throw IOException("Nozzle It All couldn't read this printer's serial number. Add it to the printer's details.")
            serial = sn
            val client = MiniMqttClient(host.host, mqttPort, clientId, Cc2.USERNAME, token, 60, ::onMessage, ::onLost)
            try {
                client.connect(timeouts.connectMs.toInt())
                client.subscribe(listOf(Cc2.responseTopic(sn, clientId), Cc2.statusTopic(sn), Cc2.registerResponseTopic(sn, requestId)), 1, timeouts.connectMs)
                val reg = CompletableFuture<JSONObject>().also { registration = it }
                client.publish(Cc2.registerTopic(sn), Cc2.registration(clientId, requestId).toString())
                // getRegistrationTimeoutMs: 3 s.
                val reply = try { reg.get(maxOf(3_000L, timeouts.statusMs), TimeUnit.MILLISECONDS) } catch (e: Exception) { throw IOException("The printer didn't accept Nozzle It All's connection.") }
                val error = reply.optString("error", "fail")
                if (error != "ok") throw MqttRefused(if ("too many clients" in error) "The printer already has as many connections as it allows. Close another app connected to it, then try again."
                    else "The printer refused the connection: ${error.take(120)}")
            } catch (e: Exception) { client.close(); throw e }
            mqtt = client
            heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "elegoo-cc2-heartbeat").apply { isDaemon = true } }.apply {
                // getHeartbeatIntervalSeconds: 10 s application ping on the request topic; MQTT's own keep-alive is 60 s.
                scheduleWithFixedDelay({ runCatching { if (client.alive) client.publish(Cc2.requestTopic(sn, clientId), Cc2.HEARTBEAT) } }, 10, 10, TimeUnit.SECONDS)
                scheduleWithFixedDelay({ client.ping() }, 30, 30, TimeUnit.SECONDS)
            }
            return client
        }
    }

    /** GET /system/info with the access code, as elegoo-link does when it has no serial (header and query both, as upstream). */
    private fun systemInfoSerial(): String? = runCatching {
        val http = OkHttpClient.Builder().connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS).readTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).build()
        val url = base.newBuilder().encodedPath("/system/info").addQueryParameter("X-Token", token).build()
        http.newCall(Request.Builder().url(url).header("X-Token", token).build()).execute().use { r ->
            if (r.code != 200) return@use null
            val src = r.body?.source() ?: return@use null
            src.request(65_537)
            if (src.buffer.size > 65_536) return@use null
            JSONObject(src.buffer.readUtf8()).optJSONObject("system_info")?.optString("sn")?.trim()?.ifEmpty { null }
        }
    }.getOrNull()

    private fun send(client: MiniMqttClient, method: Int, params: JSONObject = JSONObject()): Pair<Int, CompletableFuture<JSONObject>> {
        val id = ids.getAndIncrement()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        try { client.publish(Cc2.requestTopic(serial!!, clientId), Cc2.request(id, method, params).toString()) }
        catch (e: IOException) { pending.remove(id); throw e }
        return id to future
    }

    private fun ask(client: MiniMqttClient, method: Int, ms: Long): JSONObject? {
        val (id, future) = try { send(client, method) } catch (e: IOException) { return null }
        return try { future.get(ms, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { null } catch (e: ExecutionException) { null } finally { pending.remove(id) }
    }

    override fun status(): PrinterStatus {
        val client = try { open() } catch (e: MqttRefused) { return PrinterStatus(PrinterState.ERROR, route, message = e.message) }
            catch (e: Exception) { return PrinterStatus(PrinterState.OFFLINE, route, message = e.message ?: "The printer did not answer.") }
        val reply = ask(client, Cc2.METHOD_STATUS, timeouts.statusMs)
        val result = reply?.optJSONObject("result")
        val status = synchronized(lock) {
            if (result != null && (result.opt("error_code") as? Number)?.toInt() == 0) { fullStatus = JSONObject(result.toString()); statusAtMillis = System.currentTimeMillis() }
            fullStatus?.takeIf { System.currentTimeMillis() - statusAtMillis < 30_000 }?.let { JSONObject(it.toString()) to statusAtMillis }
        } ?: return PrinterStatus(if (client.alive) PrinterState.UNKNOWN else PrinterState.OFFLINE, route, message = "The printer didn't send its status.")
        val (json, at) = status
        val inStatus = json.optJSONObject("canvas_info")?.let(Canvas::parse)
        if (inStatus != null) slots = inStatus else readSlots(client)
        val r = Cc2.parseStatus(json) ?: return PrinterStatus(PrinterState.UNKNOWN, route, message = "The printer's status was not understood.")
        return PrinterStatus(r.state, route, r.job, if (r.bed != null || r.bedTarget != null) Temperature(r.bed, r.bedTarget) else null,
            Canvas.toolheads(slots, r.nozzle, r.nozzleTarget), r.message, observedAtMillis = at)
    }

    /** Method 2005; asked again only every minute after it goes unanswered twice. */
    private fun readSlots(client: MiniMqttClient) {
        if (System.currentTimeMillis() < slotsPausedUntil) return
        val reply = ask(client, Cc2.METHOD_CANVAS, timeouts.slotsMs)
        if (reply == null) { if (++slotTimeouts >= 2) { slotsPausedUntil = System.currentTimeMillis() + 60_000; slotTimeouts = 0 }; return }
        slotTimeouts = 0
        val result = reply.optJSONObject("result") ?: return
        if ((result.opt("error_code") as? Number)?.toInt() != 0) { slots = null; return }
        slots = Canvas.parse(result.optJSONObject("canvas_info"))
    }

    override fun cameras(): List<CameraEndpoint> = emptyList()
    override fun snapshot(camera: CameraEndpoint): ByteArray = throw IOException("Nozzle It All doesn't show this printer's camera yet.")

    override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult = try {
        upload.cc2(file, ElegooNet.validateRemoteName(remoteName), token, progress)
    } catch (e: IllegalArgumentException) { UploadResult.Failed(e.message ?: "Invalid upload.") }
      catch (e: IOException) { UploadResult.Failed(e.message ?: "The upload failed.") }

    override fun perform(action: PrinterAction): ActionOutcome {
        val (method, params) = try {
            when (action) {
                is PrinterAction.StartJob -> {
                    if (!capabilities.startPrint) return unsupported()
                    Cc2.METHOD_START_PRINT to Cc2.startPrintParams(ElegooNet.validateRemoteName(action.remotePath), Canvas.slotMap(action.toolheadMap, slots))
                }
                PrinterAction.Pause -> if (capabilities.pausePrint) Cc2.METHOD_PAUSE to JSONObject() else return unsupported()
                PrinterAction.Cancel -> if (capabilities.cancelPrint) Cc2.METHOD_STOP to JSONObject() else return unsupported()
                is PrinterAction.UploadAndStart -> return ActionOutcome.Rejected("Send the file first, then start it.")
                // Resume has no LAN method in elegoo-link's table (1023 is commented out), so it isn't offered.
                else -> return unsupported()
            }
        } catch (e: IllegalArgumentException) { return ActionOutcome.Rejected(e.message ?: "Invalid command.") }
        val client = try { open() } catch (e: Exception) { return ActionOutcome.Rejected("Couldn't reach the printer. Nothing was sent.") }
        if (!client.alive) return ActionOutcome.Rejected("The connection to the printer closed before sending. Nothing was sent.")
        val (id, future) = try { send(client, method, params) }
            catch (e: IOException) { return ActionOutcome.Unknown("The connection failed while sending. The command may or may not have run; check the printer before trying again.") }
        val reply = try { future.get(timeouts.commandMs, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { return ActionOutcome.Unknown("The printer didn't confirm. The command may or may not have run; check the printer before trying again.") }
            catch (e: ExecutionException) { return ActionOutcome.Unknown("${e.cause?.message ?: "No reply"} The command may or may not have run; check the printer before trying again.") }
            finally { pending.remove(id) }
        return when (val code = (reply.optJSONObject("result")?.opt("error_code") as? Number)?.toInt()) {
            0 -> ActionOutcome.Accepted
            null -> ActionOutcome.Unknown("The printer's reply was not understood. Check the printer before trying again.")
            else -> ActionOutcome.Rejected(Cc2.errorReason(code))
        }
    }

    private fun unsupported() = ActionOutcome.Rejected("${identity.displayName} doesn't support that from Nozzle It All.")

    override fun close() {
        heartbeat?.shutdownNow(); heartbeat = null
        mqtt?.close(); mqtt = null
        pending.values.forEach { it.completeExceptionally(IOException("Closed.")) }
    }
}
