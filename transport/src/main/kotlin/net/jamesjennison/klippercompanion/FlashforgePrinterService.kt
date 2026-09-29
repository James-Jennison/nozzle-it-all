package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.FLASHFORGE: a Flashforge AD5X (with its IFS) or Adventurer 5M on Flashforge's local HTTP API, port 8898. The
 * request shapes are FlashforgeIfs's (ported from upstream OrcaSlicer's Flashforge print host; docs/upstream/PROVENANCE.md
 * P-0033); this class only carries them. Every call needs the printer's serial number ([serial]) and LAN access code
 * ([checkCode], the "check code"); without both nothing is sent. The code is a credential: it goes in a JSON body or a
 * header, never in a URL or a log.
 *
 * Offered: live status and temperatures (read-only; the status field names are unverified, see FlashforgeIfs.snapshot),
 * the IFS slots (read-only), and uploading a sliced G-code file (`/uploadGcode` with `printNow: false`). Starting it is
 * refused until FlashforgeIfs.START_VERIFIED: the file is uploaded and the person is told to start it on the printer.
 * No control endpoint (pause, resume, cancel, homing, jogging, temperatures) is sent. Built from sources only; not yet run
 * against a real printer.
 */
class FlashforgePrinterService(address: String, private val serial: String, private val checkCode: String, port: Int = FlashforgeIfs.PORT) :
    PrinterService, FilamentSlotReader {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    // Orca addresses the API by host alone (Flashforge.cpp make_http_url / extract_host_name), whatever port was typed.
    private val api: HttpUrl = HttpUrl.Builder().scheme("http").host(base.host).port(port).build()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    @Volatile private var last: Pair<Long, JSONObject>? = null

    private fun requireCredentials() { if (serial.isBlank() || checkCode.isBlank()) throw ApiFailure(FlashforgeIfs.MISSING_CREDENTIALS) }

    /** One POST; a reply that isn't JSON, or carries a nonzero `code`/`err`, is a failure (Flashforge.cpp:152-183). */
    private fun post(endpoint: String, body: RequestBody, extraHeaders: Map<String, String> = emptyMap(), http: OkHttpClient = client): JSONObject {
        val builder = Request.Builder().url(api.newBuilder().addPathSegment(endpoint).build()).post(body)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val json = try {
            http.newCall(request).execute().use { r ->
                val raw = r.body?.source()?.let { s -> s.request(2_000_001); s.buffer.readByteArray() } ?: ByteArray(0)
                if (raw.size > 2_000_000) throw ApiFailure("The Flashforge printer's reply is larger than supported.")
                val text = String(raw, Charsets.UTF_8)
                val parsed = try { JSONObject(text) } catch (_: org.json.JSONException) { null }
                if (!r.isSuccessful) throw ApiFailure(parsed?.let { FlashforgeIfs.apiError(it) } ?: "The Flashforge printer answered HTTP ${r.code}.")
                parsed ?: throw ApiFailure("The Flashforge printer sent an unreadable reply.")
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not reach the Flashforge printer at ${base.host}: ${e.message ?: "connection failed"}")
        }
        FlashforgeIfs.apiError(json)?.let { throw ApiFailure(it) }
        return json
    }

    /** `POST /detail` with the serial and check code (Flashforge.cpp:494-498). Read-only. */
    private fun detail(): JSONObject {
        requireCredentials()
        val body = FlashforgeIfs.authBody(serial.trim(), checkCode.trim()).toString().toRequestBody("application/json".toMediaType())
        return post("detail", body).also { last = System.currentTimeMillis() to it }
    }

    override fun snapshot(): PrinterSnapshot = FlashforgeIfs.snapshot(detail())

    /** The IFS slots from `/detail` (reusing the last reply for 5 s). No station reported: no slots. */
    override fun filamentSlots(): FilamentSlotStatus {
        val reply = last?.takeIf { System.currentTimeMillis() - it.first < 5_000 }?.second ?: detail()
        val station = FlashforgeIfs.parseStation(reply)
        if (!station.present || station.slots.isEmpty()) return FilamentSlotStatus(emptyList(), "")
        return FilamentSlotStatus(FlashforgeIfs.filamentSlots(station), "the printer's IFS")
    }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read a Flashforge printer's file list yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                FlashforgeIfs.requireStartVerified(name, uploaded = false)
                throw ApiFailure("Unsupported command for a Flashforge printer: starting a file already on the printer isn't built yet.")
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for a Flashforge printer: pause, resume and cancel aren't built yet. Use the printer's screen.")
            else -> throw ApiFailure("Unsupported command for a Flashforge printer.")
        }
    }

    /**
     * Uploads the sliced file with `printNow: false`; while FlashforgeIfs.START_VERIFIED is false that is all, and the start
     * is refused with the reason. Once verified: the printer must be idle, the file's tools map to IFS slots one-to-one
     * (T n -> IFS slot n+1, as ElegooProfiles.toolheadMap), and the mapping is checked (loaded slot, matching material family)
     * before the one `printNow: true` upload that starts it.
     */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = FlashforgeIfs.safeFileName(request.remoteName)
        if (!FlashforgeIfs.START_VERIFIED) { upload(file, name, printNow = false); FlashforgeIfs.requireStartVerified(name) }
        val reply = detail()
        val snapshot = FlashforgeIfs.snapshot(reply)
        if (!FlashforgeIfs.isIdle(snapshot)) throw ApiFailure("The printer is ${snapshot.state}; start a print when it is ready.")
        val station = FlashforgeIfs.parseStation(reply)
        val slots = if (station.present) station.slots else emptyList()
        val map = file.bufferedReader().useLines { ElegooProfiles.toolheadMap(it, if (slots.isEmpty()) 64 else slots.size) }
        if (slots.isEmpty() && map.isNotEmpty()) throw ApiFailure("Nothing was sent: this file uses more than one filament, but the printer reports no IFS.")
        val filaments = file.bufferedReader().useLines { SlicedFileFilaments.read(it) }
        val mappings = try { FlashforgeIfs.mappings(map, slots, filaments) } catch (e: IllegalArgumentException) { throw ApiFailure("Nothing was sent: ${e.message}") }
        FlashforgeIfs.requireStartVerified(name)
        upload(file, name, printNow = true, mappings = mappings)
    }

    /** `POST /uploadGcode`: multipart field `gcodeFile`, every option a header (FlashforgeIfs.uploadHeaders; Flashforge.cpp:557-618). */
    private fun upload(file: File, name: String, printNow: Boolean, mappings: List<FlashforgeIfs.Mapping> = emptyList()) {
        requireCredentials()
        val headers = FlashforgeIfs.uploadHeaders(serial.trim(), checkCode.trim(), file.length(), printNow, mappings)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("gcodeFile", name, file.asRequestBody("application/octet-stream".toMediaType())).build()
        try { post("uploadGcode", body, headers, uploadClient) }
        catch (e: ApiFailure) { throw ApiFailure("Could not upload $name: ${e.message}") }
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
