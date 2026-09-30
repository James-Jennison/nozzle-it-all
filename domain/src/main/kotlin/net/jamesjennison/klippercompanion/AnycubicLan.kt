package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anycubic printers on stock firmware in LAN mode (Kobra 3 / 3 Max, Kobra S1 / S1 Max, Kobra X, with or without an
 * ACE / ACE Pro; PrinterKind.ANYCUBIC_LAN): pure message builders and parsers, no I/O. AnycubicLanPrinterService does
 * the talking.
 *
 * Sources (docs/upstream/PROVENANCE.md P-0036), cited as file:line:
 * - PRIMARY, ported from: anycubic-orca-plugin 458eee7 (AGPL-3.0), `anycubic_orca_plugin/anycubic_lan.py` ("acl.py:N"),
 *   `tests/test_plugin.py` ("acl-test:N"), `docs/orcaslicer-plugin.md` ("acl-doc:N").
 * - CROSS-CHECK: kobra-connect 3edba24 (Apache-2.0), `kobra_connect/handshake.py` ("kc-handshake:N"), `client.py`
 *   ("kc-client:N"), `models.py` ("kc-models:N"), `moonraker_bridge/state.py` ("kc-state:N"), `docs/mqtt-commands.md`
 *   ("kc-doc:N").
 * Where the two disagree the choice and the reason are written at the rule ("DISAGREE"). Nothing here has been run
 * against a printer.
 *
 * The protocol:
 * - `GET http://<ip>:18910/info` gives a `token`, `modelId`, `ctrlInfoUrl` and the upload URL (acl.py:422-430;
 *   kc-handshake:75-88).
 * - `POST <ctrlInfoUrl>?ts=&nonce=&sign=&did=` returns `{code:200, data:{token, info}}`; `info` is AES-128-CBC with key
 *   `token[16:32]` and IV `data.token`, holding the MQTT `username`, `password`, `deviceId` and `broker` (acl.py:431-459;
 *   kc-handshake:90-124).
 * - MQTT 3.1.1 over TLS on port 9883 with those credentials; commands go to
 *   `anycubic/anycubicCloud/v1/{slicer|web}/printer/<modelId>/<deviceId>/<type>`, reports come back under
 *   `anycubic/anycubicCloud/v1/printer/<...>/<modelId>/<deviceId>/...` (acl.py:462-554; kc-client:143-145,248,286).
 * - Sliced files go by multipart POST to the printer's `fileUploadurl` (`.../gcode_upload?s=<token>`); an upload never
 *   starts a print (acl.py:725-790; kc-doc:538-545).
 *
 * Starting a print, and anything that heats or moves the printer, is gated: see [START_VERIFIED].
 */
object AnycubicLan {
    /**
     * Whether starting a print from Nozzle It All (`print/start` with the ACE mapping) and the controls that heat or move
     * the printer (pause / resume / stop, temperatures, homing, ACE feed / unload / drying) have been confirmed on a real
     * Anycubic printer. Until then the service uploads a sliced file and refuses the start with [startNotVerified], and
     * refuses every such control with [controlNotVerified] before anything is sent. The messages are built and
     * unit-tested (the references' wire format), but a wrong start heats and moves the printer and a wrong mapping feeds
     * the wrong spool. Flip only with a real Kobra 3 / S1 run recorded (docs/upstream/PROVENANCE.md), as
     * CrealityCfs.START_VERIFIED.
     */
    const val START_VERIFIED = false

    /** The printer's HTTP daemon (acl.py:391; kc-handshake:75). */
    const val HTTP_PORT = 18910
    /** The printer's own MQTT broker in LAN mode (acl.py:467; kc-doc:6). */
    const val MQTT_PORT = 9883
    /** Both references use a 60 s keep-alive (acl.py:481; kc-client:209). */
    const val KEEP_ALIVE_SECONDS = 60
    const val TOPIC_ROOT = "anycubic/anycubicCloud/v1"
    /** Every ACE / ACE Pro box has four slots (acl.py:1202 iterates 0..3). */
    const val SLOTS_PER_ACE = 4
    private const val MAX_BOXES = 8

    // ---- the gate -----------------------------------------------------------------------------------------------------

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the printer. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any start message is built. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    /** [what] names the control, e.g. "pausing a print". Same "isn't verified on real hardware yet" wording as the start. */
    fun controlNotVerified(what: String): String =
        "Nothing was sent: $what on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Use the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before a control message is built. */
    fun requireControlVerified(what: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(controlNotVerified(what))
    }

    // ---- handshake: GET /info -------------------------------------------------------------------------------------------

    const val CLOUD_MODE = "This Anycubic printer is in cloud mode. Turn on LAN mode on the printer's screen, then try again."
    const val NOT_LAN_HANDSHAKE = "This printer doesn't answer with Anycubic's signed LAN handshake (a Kobra 3 / S1 generation printer in LAN mode is needed)."

    /**
     * `/info`. [token] is the master token the handshake signs with and whose second half is the AES key: a secret, never
     * shown or logged ([toString] leaves it out). [fileUploadUrl] carries its own `?s=` upload token (AGENTS.md of the
     * primary, section 4.1), so it is a secret too.
     */
    class Info(val token: String, val modelId: String, val ctrlInfoUrl: String, val fileUploadUrl: String?, val serial: String,
               val deviceName: String, val modelName: String) {
        override fun toString(): String = "AnycubicLan.Info(modelId=$modelId, modelName=$modelName)"
    }

    /**
     * Parses `/info`. DISAGREE: kc-handshake:80-88 refuses an `/info` without `token`, `ctrlInfoUrl` or `modelId`;
     * acl.py:426-430 falls back to model "20025" and `http://<host>:18910/ctrl`. The strict rule is kept: a guessed model id
     * would subscribe to another model's topics (kc-doc:12 lists 20024 Kobra 3, 20025 Kobra S1, 20026 Kobra 3 Max ...).
     * `ctrlType == "cloud"` means LAN mode is off (kc-handshake:77-78; the primary doesn't check). The upload URL is the
     * top-level `fileUploadurl` or `urls.fileUploadurl` (acl.py:741-743, acl-test:76; kc-doc:79-80). `cn` is the serial
     * (acl.py:1492; kc-handshake:118); `deviceName` / `modelName` name it (acl.py:1493-1496).
     */
    fun parseInfo(body: String): Info {
        val o = try { JSONObject(body) } catch (_: org.json.JSONException) { throw ApiFailure(NOT_LAN_HANDSHAKE) }
        if (str(o, "ctrlType").equals("cloud", ignoreCase = true)) throw ApiFailure(CLOUD_MODE)
        val token = str(o, "token")
        val ctrl = str(o, "ctrlInfoUrl")
        val model = o.opt("modelId")?.takeIf { it != JSONObject.NULL }?.toString()?.trim().orEmpty()
        if (token.length < 32 || ctrl.isEmpty() || model.isEmpty()) throw ApiFailure(NOT_LAN_HANDSHAKE)
        val upload = str(o, "fileUploadurl").ifEmpty { o.optJSONObject("urls")?.let { str(it, "fileUploadurl") }.orEmpty() }
        return Info(token, model, ctrl, upload.ifEmpty { null }, str(o, "cn").take(64), str(o, "deviceName").take(80), str(o, "modelName").take(80))
    }

    /**
     * [url] (a URL the printer handed out: `ctrlInfoUrl`, `fileUploadurl`) if it is plain HTTP on [host], the printer this
     * profile names; null otherwise, and then nothing is sent to it. Not in either reference (both follow the URL
     * wherever it points); kept so a reply can't send the signed handshake or a file to another machine.
     */
    fun printerUrl(url: String?, host: String): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = try { java.net.URI(u) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val h = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        return u.takeIf { h.equals(host.removePrefix("[").removeSuffix("]"), ignoreCase = true) }
    }

    // ---- handshake: POST /ctrl ----------------------------------------------------------------------------------------

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /** `sign = md5(md5(token[:16]) + str(ts) + nonce)`, lower-case hex (acl.py:434-436; kc-handshake:44-47 agree). */
    fun sign(token: String, ts: Long, nonce: String): String = md5Hex(md5Hex(token.take(16)) + ts + nonce)

    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** Six letters or digits (acl.py:432; kc-handshake:91 agree). */
    fun nonce(random: java.util.Random = SecureRandom()): String = (1..6).map { ALNUM[random.nextInt(ALNUM.length)] }.joinToString("")

    /**
     * The client id sent as `did`. DISAGREE: acl.py:402 always sends one fixed id (and repeats it as X-BBL-Device-ID in
     * the upload, acl.py:752); kc-handshake:92 makes a random 32-character upper-case letters-and-digits id per handshake.
     * Kept: kobra-connect's shape, random per service, so this app doesn't reuse the plugin's own id; the upload header
     * carries the same value, as the primary pairs them.
     */
    fun newDeviceId(random: java.util.Random = SecureRandom()): String = (1..32).map { UPPER_DIGITS[random.nextInt(UPPER_DIGITS.length)] }.joinToString("")

    private const val UPPER_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** The query the `/ctrl` POST carries, in order (acl.py:438; kc-handshake:94-99 agree). */
    fun ctrlQuery(token: String, ts: Long, nonce: String, did: String): List<Pair<String, String>> =
        listOf("ts" to ts.toString(), "nonce" to nonce, "sign" to sign(token, ts, nonce), "did" to did)

    /**
     * The MQTT credentials. Secrets: held in memory for one service only, never stored, shown or logged ([toString]
     * leaves them out). [deviceCert] / [deviceKey] are the PEM client certificate and key the handshake may return
     * (`devicecrt` / `devicepk`, kc-handshake:119-120; the primary ignores them).
     */
    class Credentials(val username: String, val password: String, val deviceId: String, val brokerPort: Int,
                      val deviceCert: String, val deviceKey: String) {
        override fun toString(): String = "AnycubicLan.Credentials(deviceId=$deviceId, brokerPort=$brokerPort)"
    }

    /**
     * The `/ctrl` reply decrypted. `code` must be 200 (acl.py:443; kc-handshake:102). The broker port is `broker`'s
     * (`mqtts://host:port`, kc-handshake:107-110), else [MQTT_PORT] (acl.py:467 always uses 9883; the primary's own test
     * fixture's broker is `mqtts://10.30.14.52:9883`, acl-test:52). The broker HOST is not taken from the reply: the
     * printer's own address is dialled, as acl.py:481 does (kc-handshake:111 would follow the reply).
     */
    fun parseCtrl(body: String, token: String): Credentials {
        val o = try { JSONObject(body) } catch (_: org.json.JSONException) { throw ApiFailure("The printer's LAN handshake reply wasn't understood.") }
        if (int(o, "code") != 200) throw ApiFailure("The printer refused the LAN handshake" + (str(o, "message").takeIf { it.isNotEmpty() }?.let { ": ${it.take(120)}" } ?: "."))
        val data = o.optJSONObject("data") ?: throw ApiFailure("The printer's LAN handshake reply wasn't understood.")
        val plain = try { decrypt(str(data, "info"), token, str(data, "token")) } catch (_: Exception) {
            throw ApiFailure("The printer's LAN handshake reply couldn't be decrypted.")
        }
        val username = str(plain, "username"); val deviceId = str(plain, "deviceId")
        if (username.isEmpty() || deviceId.isEmpty()) throw ApiFailure("The printer's LAN handshake reply had no MQTT credentials.")
        val port = BROKER.find(str(plain, "broker"))?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: MQTT_PORT
        return Credentials(username, str(plain, "password"), deviceId, port, str(plain, "devicecrt"), str(plain, "devicepk"))
    }

    private val BROKER = Regex("""^mqtts?://([^:/]+):(\d+)""")

    /**
     * AES-128-CBC, PKCS#7, key `token[16:32]`, IV the reply's `data.token` (acl.py:447-456; kc-handshake:50-58).
     * DISAGREE: the primary uses `data.token` as the IV as it comes (it must be 16 bytes); kc-handshake:53 truncates or
     * NUL-pads it to 16. Kept: kobra-connect's rule, identical for a 16-byte token and not a crash otherwise.
     */
    fun decrypt(infoBase64: String, token: String, localToken: String): JSONObject {
        require(token.length >= 32) { "token too short" }
        val key = token.substring(16, 32).toByteArray(Charsets.UTF_8)
        val iv = localToken.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val clear = cipher.doFinal(Base64.getDecoder().decode(infoBase64.filterNot { it.isWhitespace() }))
        return JSONObject(String(clear, Charsets.UTF_8))
    }

    // ---- MQTT topics and messages ---------------------------------------------------------------------------------------

    private val ID = Regex("""^[A-Za-z0-9_.-]{1,64}$""")

    /** Model and device ids go into topics; one with `/`, `+` or `#` in it would widen or redirect them, so it is refused. */
    fun requireTopicSafe(modelId: String, deviceId: String) {
        if (!ID.matches(modelId) || !ID.matches(deviceId)) throw ApiFailure("The printer's LAN handshake gave an id Nozzle It All can't use.")
    }

    /**
     * Where a command goes: `.../slicer/printer/<model>/<device>/<type>`, or `.../web/printer/...` for [web] (acl.py:538-539;
     * kc-client:144-145,301; kc-doc:18,23).
     */
    fun commandTopic(modelId: String, deviceId: String, type: String, web: Boolean = false): String {
        requireTopicSafe(modelId, deviceId)
        return "$TOPIC_ROOT/${if (web) "web" else "slicer"}/printer/$modelId/$deviceId/$type"
    }

    /**
     * The report subscription. DISAGREE: acl.py:489 subscribes `.../printer/+/<model>/<device>/#`; kc-client:286 and
     * kc-doc:35 `.../printer/public/<model>/<device>/#`, and acl-doc:135 says telemetry arrives under
     * `.../printer/slicer/...`. Kept: the primary's `+`, which covers both.
     */
    fun reportFilter(modelId: String, deviceId: String): String {
        requireTopicSafe(modelId, deviceId)
        return "$TOPIC_ROOT/printer/+/$modelId/$deviceId/#"
    }

    /** One outgoing command before it gets its ids: `type`, `action`, `data` and which namespace it goes to. */
    class Command(val type: String, val action: String, val data: Any?, val web: Boolean = false) {
        /**
         * `{type, action, timestamp (ms), msgid (UUID v4), data}` (acl.py:529-536; kc-client:302-309; kc-doc:40-47,577-578).
         * A query's data is JSON null (acl.py:557, kc-client:245, kc-doc:62). DISAGREE: kc-client:303 uses a bare hex
         * uuid; the primary and kc-doc:61 use the dashed form, kept.
         */
        fun payload(msgid: String = UUID.randomUUID().toString(), timestamp: Long = System.currentTimeMillis()): JSONObject =
            JSONObject().put("type", type).put("action", action).put("timestamp", timestamp).put("msgid", msgid).put("data", data ?: JSONObject.NULL)
        fun topic(modelId: String, deviceId: String): String = commandTopic(modelId, deviceId, type, web)
    }

    /**
     * `info` / `query` on the slicer topic (acl.py:557 with publish_command's default namespace, acl.py:538; kc-doc:54-63).
     * DISAGREE: kc-client:239 sends its queries on the web topic; the primary and kobra-connect's own doc agree on
     * slicer, kept. Read-only.
     */
    fun infoQuery(): Command = Command("info", "query", null)

    /** `multiColorBox` / `getInfo`: the ACE slots (acl.py:562-572; kobra-connect has no ACE support to compare). Read-only. */
    fun aceQuery(): Command = Command("multiColorBox", "getInfo", null)

    /**
     * The type a report answers: its payload's `type` (acl.py:500-503), else the topic's `.../<type>/report`
     * (kc-client:248-254). Null for anything else.
     */
    fun reportType(topic: String, payload: JSONObject): String? {
        str(payload, "type").takeIf { it.isNotEmpty() }?.let { return it }
        if (!topic.endsWith("/report")) return null
        return topic.removeSuffix("/report").substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    // ---- status -------------------------------------------------------------------------------------------------------

    /**
     * The `info` report's `data` in this app's Moonraker vocabulary. The references' rules, merged:
     * - printing: `state == "busy"` or the project's state `printing` (acl.py:1122); paused instead when the project's
     *   `pause` is 1 or 2 (kc-state:243-244), cancelled when it is 4, stopping (kc-state:245-246);
     * - paused: project state `pause` (acl.py:1124) or `paused` (kc-state:230), or `state == "pause"`;
     * - complete: `finish` / `complete` (acl.py:1126; kc-state:235);
     * - cancelled: `stoped` / `stopped` / `cancel` / `cancelled` (acl.py:1128 calls them idle; kc-state:237 cancelled,
     *   which this app also shows as ready);
     * - error: `error` (kc-state:236; the primary has none);
     * - anything else standby (acl.py:1130-1133; kc-state:249).
     */
    fun stateName(data: JSONObject): String {
        val raw = str(data, "state").lowercase(Locale.ROOT)
        val project = project(data)
        val proj = project?.let { str(it, "state").lowercase(Locale.ROOT) }.orEmpty()
        val pause = project?.let { int(it, "pause") } ?: 0
        return when {
            raw == "busy" || proj == "printing" -> when (pause) { 1, 2 -> "paused"; 4 -> "cancelled"; else -> "printing" }
            proj == "pause" || proj == "paused" || raw == "pause" -> "paused"
            proj == "finish" || proj == "complete" -> "complete"
            proj in setOf("stoped", "stopped", "cancel", "cancelled") -> "cancelled"
            proj == "error" || raw == "error" -> "error"
            else -> "standby"
        }
    }

    /**
     * The job object. DISAGREE on precedence only: acl.py:1119 reads `last_project` then `project`; kc-models:500-501
     * reads `project`. Kept: `project` first (the current job, when both are sent), then `last_project`.
     */
    fun project(data: JSONObject): JSONObject? = data.optJSONObject("project") ?: data.optJSONObject("last_project")

    /**
     * `data` of an `info` report: `temp.curr_nozzle_temp` / `target_nozzle_temp` / `curr_hotbed_temp` /
     * `target_hotbed_temp` (acl.py:1136-1140; kc-models:436-441 agree), the project's `progress` (percent), `curr_layer`,
     * `total_layers`, `filename` (acl.py:1143-1147; kc-models:468-474 agree) and `print_time` in minutes (kc-state:257-262;
     * the primary doesn't read it).
     */
    fun snapshot(data: JSONObject): PrinterSnapshot {
        val state = stateName(data)
        val project = project(data)
        val temp = data.optJSONObject("temp") ?: JSONObject()
        val progress = ((project?.let { number(it, "progress") } ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        return PrinterSnapshot(true, state, project?.let { str(it, "filename").substringAfterLast('/') }.orEmpty(), progress,
            number(temp, "curr_nozzle_temp"), number(temp, "target_nozzle_temp"), number(temp, "curr_hotbed_temp"), number(temp, "target_hotbed_temp"),
            project?.let { number(it, "print_time") }?.takeIf { it > 0 }?.times(60.0),
            project?.let { int(it, "curr_layer") }?.takeIf { it > 0 }, project?.let { int(it, "total_layers") }?.takeIf { it > 0 }, "extruder")
    }

    /** Idle enough to start: what [stateName] reports as neither printing nor paused. */
    fun isIdle(state: String): Boolean = state in setOf("standby", "complete", "cancelled", "error")

    // ---- ACE / ACE Pro slots -------------------------------------------------------------------------------------------

    /**
     * One ACE slot. [box] is the box's position in the report (acl.py:1186 numbers boxes by order), [index] its slot 0-3.
     * [status] is the raw slot status (acl-doc:167: 4 loaded, 5 ready).
     */
    data class AceSlot(val box: Int, val index: Int, val status: Int, val loaded: Boolean, val type: String?, val colorHex: String?,
                       val rgb: List<Int>?, val sku: String?, val active: Boolean) {
        /** Box n slot m feeds tool 4n+m (acl.py:1193,1217 number trays the same way). */
        val tool: Int get() = box * SLOTS_PER_ACE + index
        val label: String get() = "ACE ${box + 1} · slot ${index + 1}"
    }

    /**
     * The `multiColorBox` report's `data` to its slots, as acl.py:1178-1214 reads it: boxes under `multi_color_box` (or
     * `multiColorBox`); each box's slots under `slots` (or `box_info.slot_info`), keyed by `index`; slots 0-3 always, a
     * missing one empty. A slot is loaded when its `status` is 4 or 5, or above 0 with a `type` (acl.py:1208). Its colour
     * is `color` `[r, g, b]` (acl.py:1209-1214); the box's `loaded_slot` is the active one (acl.py:1191-1194). Empty
     * slots are kept, as unloaded. kobra-connect reads no ACE data, so none of this is cross-checked.
     */
    fun parseAce(data: JSONObject?): List<AceSlot> {
        if (data == null) return emptyList()
        val boxes = data.optJSONArray("multi_color_box")?.takeIf { it.length() > 0 } ?: data.optJSONArray("multiColorBox") ?: return emptyList()
        val out = ArrayList<AceSlot>()
        for (b in 0 until minOf(boxes.length(), MAX_BOXES)) {
            val box = boxes.optJSONObject(b) ?: continue
            val loadedSlot = int(box, "loaded_slot") ?: -1
            val raw = box.optJSONArray("slots")?.takeIf { it.length() > 0 } ?: box.optJSONObject("box_info")?.optJSONArray("slot_info") ?: JSONArray()
            val byIndex = HashMap<Int, JSONObject>()
            for (i in 0 until minOf(raw.length(), 16)) { val s = raw.optJSONObject(i) ?: continue; byIndex.putIfAbsent(int(s, "index") ?: i, s) }
            for (index in 0 until SLOTS_PER_ACE) {
                val s = byIndex[index] ?: JSONObject()
                val status = int(s, "status") ?: 0
                val type = str(s, "type")
                val loaded = status == 4 || status == 5 || (status > 0 && type.isNotEmpty())
                val rgb = rgb(s.optJSONArray("color"))
                out += AceSlot(b, index, status, loaded, type.takeIf { loaded && it.isNotEmpty() },
                    if (loaded) rgb?.let { String.format(Locale.ROOT, "#%02X%02X%02X", it[0], it[1], it[2]) } else null,
                    if (loaded) rgb else null, str(s, "sku").takeIf { loaded && it.isNotEmpty() }, loaded && loadedSlot == index)
            }
        }
        return out
    }

    private fun rgb(a: JSONArray?): List<Int>? {
        if (a == null || a.length() < 3) return null
        val v = (0 until 3).map { (a.opt(it) as? Number)?.toInt() ?: return null }
        return v.takeIf { c -> c.all { it in 0..255 } }
    }

    /** The slots as this app shows them; a loaded slot with no type is still loaded. */
    fun filamentSlots(slots: List<AceSlot>): List<FilamentSlot> = slots.map { s ->
        FilamentSlot(s.tool, if (s.loaded) (s.type?.uppercase(Locale.ROOT) ?: "LOADED (TYPE NOT REPORTED)") else null, s.colorHex,
            active = s.active, name = s.label)
    }

    /**
     * A material name to the base type the print start sends (acl.py:1054-1099 `resolve_filament_info`'s `base_type`): an
     * exact catalog name (acl.py:875-1020), else the first of PETG, PLA, ABS, ASA, TPU, PC, PA, PVA it contains, else PLA.
     */
    fun baseType(raw: String?): String {
        val upper = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        CATALOG_BASE[upper]?.let { return it }
        return listOf("PETG", "PLA", "ABS", "ASA", "TPU", "PC", "PA", "PVA").firstOrNull { it in upper } ?: "PLA"
    }

    private val CATALOG_BASE = mapOf("PLA" to "PLA", "PLA MATTE" to "PLA", "PLA+" to "PLA", "PLA HIGH SPEED" to "PLA", "PLA SILK" to "PLA",
        "PLA GLOW" to "PLA", "PLA LUMINOUS" to "PLA", "PLA TRANSLUCENT" to "PLA", "PLA-CF" to "PLA", "PETG" to "PETG", "PETG-CF" to "PETG",
        "ABS" to "ABS", "ASA" to "ASA", "TPU" to "TPU", "TPU 95A" to "TPU", "PC" to "PC", "PA" to "PA", "PVA" to "PVA")

    // ---- upload -------------------------------------------------------------------------------------------------------

    /**
     * The name to upload as: ASCII letters, digits, `.`, `_` and `-` only, as CrealityCfs.safeFileName (the primary sends
     * the local file's base name, acl.py:734; a name that is safe in a multipart header and on the printer's screen is
     * kept here).
     */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "print.gcode" }
        return base.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("").take(120)
    }

    /**
     * Where the upload goes: `/info`'s upload URL (with its `?s=` token), else `http://<host>:<port>/gcode_upload`
     * (acl.py:741-745; kc-doc:541-545 agree on the URL). Null when the printer names another host (see [printerUrl]).
     */
    fun uploadUrl(info: Info, host: String, port: Int): String? =
        if (info.fileUploadUrl != null) printerUrl(info.fileUploadUrl, host)
        else "http://${if (':' in host) "[$host]" else host}:$port/gcode_upload"

    /**
     * The upload's headers, byte for byte acl.py:747-757 (the primary presents itself as Anycubic Slicer Next; the
     * printer's daemon is not known to accept other clients). [did] is the handshake's `did` (see [newDeviceId]).
     * kobra-connect doesn't upload (its bridge refuses, moonraker_bridge/bridge.py:712-716), so none of this is cross-checked.
     */
    fun uploadHeaders(did: String, fileLength: Long): List<Pair<String, String>> = listOf(
        "User-Agent" to "AnycubicSlicerNext/1.3.7.3",
        "X-BBL-Client-Name" to "AnycubicSlicerNext",
        "X-BBL-Client-Type" to "slicer",
        "X-BBL-Client-Version" to "01.03.07.03",
        "X-BBL-Device-ID" to did,
        "X-BBL-Language" to "en-US",
        "X-BBL-OS-Type" to "linux",
        "X-BBL-OS-Version" to "7.1.9",
        "X-File-Length" to fileLength.toString(),
    )

    /** Multipart field names: `filename` (text) then `gcode` (the file, application/octet-stream) (acl.py:767-770). */
    const val UPLOAD_NAME_FIELD = "filename"
    const val UPLOAD_FILE_FIELD = "gcode"

    /** The upload reply: `code` 200 and the printer's name for the file in `data.gcode` (acl.py:782-789, acl-test:1066-1070). */
    fun parseUploadReply(body: String, name: String): String {
        val o = try { JSONObject(body) } catch (_: org.json.JSONException) { throw ApiFailure("Could not upload $name to the printer: its reply wasn't understood.") }
        if (int(o, "code") != 200) throw ApiFailure("Could not upload $name to the printer" + (str(o, "message").takeIf { it.isNotEmpty() }?.let { ": ${it.take(120)}" } ?: "."))
        return o.optJSONObject("data")?.let { str(it, "gcode") }?.takeIf { it.isNotEmpty() } ?: name
    }

    // ---- print start (gated) ------------------------------------------------------------------------------------------

    /** One `ams_box_mapping` entry: file tool [paintIndex] from ACE slot [amsIndex] (acl.py:2121-2129). */
    data class BoxMapping(val paintIndex: Int, val amsIndex: Int, val rgb: List<Int>, val materialType: String) {
        fun toJson(): JSONObject = JSONObject().put("paint_index", paintIndex).put("ams_index", amsIndex)
            .put("paint_color", JSONArray(rgb + 255)).put("ams_color", JSONArray(rgb)).put("material_type", materialType)
    }

    /**
     * The ACE mapping for a start, ported from acl.py:2066-2164. [toolheadMap] is PrinterAction.StartJob's: entry T is the
     * [AceSlot.tool] feeding file tool T, -1 unmapped; empty means single colour.
     * - Each mapped tool: `paint_index` T, `ams_index` the slot's index, both colours the slot's (the primary sends the
     *   mapping's target colour as both, acl.py:2075-2083,2125-2126), `material_type` the file's declared type as a base
     *   type, else the slot's, else PLA (acl.py:2086-2119).
     * - Nothing mapped, with an ACE: slot 1 of the first box (acl.py:2133-2164). The primary sends it even when that slot
     *   is empty; refused here instead, before anything is sent.
     * - No ACE reported: no mapping, and [useAms] false.
     * The primary only reads the first box (acl.py:2098-2102,2141) and sends the slot's own index, so a tool mapped to a
     * second ACE is refused. Throws IllegalArgumentException for a refusal; nothing is sent then.
     */
    fun boxMapping(toolheadMap: List<Int>, slots: List<AceSlot>, fileFilaments: List<SlicedFileFilaments.Filament> = emptyList()): List<BoxMapping> {
        val mapped = toolheadMap.withIndex().filter { it.value >= 0 }
        if (mapped.isEmpty()) {
            val first = slots.firstOrNull { it.box == 0 && it.index == 0 } ?: return emptyList()
            require(first.loaded) { "The print would feed from ${first.label}, which has nothing loaded." }
            return listOf(BoxMapping(0, 0, first.rgb ?: DEFAULT_RGB, baseType(first.type)))
        }
        return mapped.map { (t, tool) ->
            val slot = slots.firstOrNull { it.tool == tool } ?: throw IllegalArgumentException("Material ${t + 1} is mapped to a slot this printer doesn't report. Check its ACE, then start again.")
            require(slot.box == 0) { "Material ${t + 1} is mapped to ${slot.label}; only the first ACE can be used from Nozzle It All." }
            require(slot.loaded) { "Material ${t + 1} is mapped to ${slot.label}, which has nothing loaded." }
            val declared = fileFilaments.firstOrNull { it.tool == t }?.type?.takeIf { it.isNotBlank() && !it.equals("GENERIC", ignoreCase = true) }
            BoxMapping(t, slot.index, slot.rgb ?: DEFAULT_RGB, baseType(declared ?: slot.type))
        }
    }

    /** The primary's default colour when none is known (acl.py:2083,2135). */
    private val DEFAULT_RGB = listOf(239, 237, 227)

    /**
     * `print` / `start` on the slicer topic (acl.py:792-850). Starts heating and motion: built only after
     * [requireStartVerified]. `md5` is the md5 of the FILE NAME, as acl.py:841 computes it when none is passed (it never
     * is); `url` is the primary's fixed placeholder. DISAGREE: kc-client:325-331 sends only `filename`, `filetype` and
     * `taskid`; kc-doc:140-176's full form has `filepath "/"`, `task_mode 1` and four-element `ams_color`, where the
     * primary sends `filepath` null, no `task_mode` and three-element `ams_color`. Kept: the primary's full form, the one
     * written for an ACE start; the doc's example is not code.
     */
    fun startPrint(filename: String, fileSize: Long, mapping: List<BoxMapping>, useAms: Boolean = mapping.isNotEmpty(),
                   autoLeveling: Boolean = true, vibrationCompensation: Boolean = false, flowCalibration: Boolean = false, timelapse: Boolean = false): Command {
        val ams = JSONObject().put("use_ams", useAms).put("ams_box_mapping", JSONArray().apply { if (useAms) mapping.forEach { put(it.toJson()) } })
        val zeroed = { JSONObject().put("status", 0).put("count", 0).put("type", 0) }
        val task = JSONObject().put("auto_leveling", if (autoLeveling) 1 else 0).put("vibration_compensation", if (vibrationCompensation) 1 else 0)
            .put("flow_calibration", if (flowCalibration) 1 else 0).put("dry_mode", 0)
            .put("timelapse", JSONObject().put("status", if (timelapse) 1 else 0).put("count", 0).put("type", 0))
            .put("ai_settings", zeroed())
            .put("drying_settings", JSONObject().put("status", 0).put("target_temp", 0).put("duration", 0).put("remain_time", 0))
            .put("model_objects_skip_parts", JSONArray())
        val data = JSONObject().put("taskid", "-1").put("url", "https://anycubic.com/store/aaa.gcode").put("filename", filename)
            .put("md5", md5Hex(filename)).put("filepath", JSONObject.NULL).put("filetype", 1).put("project_type", 1).put("filesize", fileSize)
            .put("ams_settings", ams).put("task_settings", task)
        return Command("print", "start", data)
    }

    // ---- controls (gated; built for when START_VERIFIED is flipped) ------------------------------------------------------

    /** `print` / `pause` | `resume` | `stop` with `{taskid: "-1"}` on the slicer topic (acl.py:623-630; kc-client:313-323; kc-doc:188-238 agree). */
    fun pause(): Command = Command("print", "pause", JSONObject().put("taskid", "-1"))
    fun resume(): Command = Command("print", "resume", JSONObject().put("taskid", "-1"))
    fun stop(): Command = Command("print", "stop", JSONObject().put("taskid", "-1"))

    /**
     * Temperatures. DISAGREE: acl.py:590-605 sends `tempature` / `set` `{type 0|1|2, target_hotbed_temp,
     * target_nozzle_temp}` on the web topic; kc-client:333-340 and kc-doc:255-293 send `print` / `update`
     * `{taskid "-1", settings {target_nozzle_temp | target_hotbed_temp}}`, the doc on the web topic. Kept: kobra-connect's,
     * which its code and its doc both give, on the web topic, where the primary and the doc agree. Heats the printer.
     */
    fun setTemperatures(nozzle: Int?, bed: Int?): Command {
        require(nozzle != null || bed != null) { "No temperature given." }
        val settings = JSONObject().apply { nozzle?.let { put("target_nozzle_temp", it) }; bed?.let { put("target_hotbed_temp", it) } }
        return Command("print", "update", JSONObject().put("taskid", "-1").put("settings", settings), web = true)
    }

    /**
     * Home all axes: `axis` / `move` `{axis 4, move_type 2, distance 0}` (acl.py:665-668). DISAGREE: kc-doc:551-553 says
     * the MQTT protocol has no homing (only Rinkhals' Moonraker does). The primary's is built, gated and never sent.
     */
    fun homeAll(): Command = Command("axis", "move", JSONObject().put("axis", 4).put("move_type", 2).put("distance", 0))

    /** ACE feed (1 load, 2 unload, 3 retract) (acl.py:674-687; not in kobra-connect). Moves filament. */
    fun feedFilament(slotIndex: Int, feedType: Int, boxId: Int = 0): Command {
        require(slotIndex in 0 until SLOTS_PER_ACE && feedType in 1..3) { "No such ACE slot or feed." }
        return Command("multiColorBox", "feedFilament", JSONObject().put("multi_color_box", JSONArray().put(JSONObject().put("id", boxId)
            .put("feed_status", JSONObject().put("slot_index", slotIndex).put("type", feedType)))))
    }

    /** ACE drying (acl.py:689-710; not in kobra-connect). Heats the dryer. */
    fun setDrying(enabled: Boolean, targetTemp: Int = 55, durationMin: Int = 240, boxId: Int = 0): Command =
        Command("multiColorBox", "setDry", JSONObject().put("multi_color_box", JSONArray().put(JSONObject().put("id", boxId)
            .put("drying_status", JSONObject().put("status", if (enabled) 1 else 0).put("target_temp", if (enabled) targetTemp else 0)
                .put("duration", if (enabled) durationMin else 0).put("remain_time", if (enabled) durationMin else 0)))))

    private fun int(o: JSONObject, k: String): Int? = when (val v = o.opt(k)) { is Number -> v.toInt(); is String -> v.trim().toDoubleOrNull()?.toInt(); else -> null }
    private fun number(o: JSONObject, k: String): Double? = when (val v = o.opt(k)) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() }
    private fun str(o: JSONObject, k: String): String = when (val v = o.opt(k)) { is String -> v.trim(); is Number -> v.toString(); else -> "" }
}
