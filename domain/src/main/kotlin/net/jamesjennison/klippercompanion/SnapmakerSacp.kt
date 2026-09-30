package net.jamesjennison.klippercompanion

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Snapmaker J1 and Artisan over SACP on TCP (PrinterKind.SNAPMAKER_SACP): the packet codec, payload builders and parsers,
 * and the status they add up to. Pure code, no I/O; SnapmakerSacpPrinterService does the talking.
 *
 * Sources (docs/upstream/PROVENANCE.md P-0037), cited as file:line:
 * - @snapmaker/snapmaker-sacp-sdk 0.1.1 (ISC), `package/dist/` ("sdk/<file>:N"): the wire format (communication/Header.js,
 *   Packet.js, Communication.js, Dispatcher.js, Response.js), the byte helpers (helper.js) and the payload models
 *   (the .js files under models/).
 * - Luban db573f5 (AGPL-3.0), `src/server/services/machine/` ("luban/<file>:N"): which commands Luban sends, to which
 *   peer, in which order, and how it reads the answers (sacp/SacpClient.ts, channels/SacpTcpChannel.ts,
 *   channels/SacpChannel.ts).
 * The unlicensed Snapmaker-SACP GitHub repository was deliberately not used. Nothing here has been run against a printer.
 *
 * The protocol:
 * - A packet is a 13-byte header, the payload and a 2-byte checksum (sdk/communication/Header.js:38-52, sdk/communication/Packet.js:20-26).
 * - Every multi-byte field is little-endian except the start-of-frame 0xAA55 (sdk/helper.js readSOF/writeSOF).
 * - Nozzle It All connects to TCP 8888 (luban/SacpTcpChannel.ts:68) and says hello with `0x01/0x05` to the screen
 *   (luban/SacpClient.ts:1301-1312). It then subscribes to the heartbeat and the temperatures and reads the job's
 *   progress (luban/SacpChannel.ts:896-1134).
 * - An upload is driven by the printer: Nozzle It All announces the file and the printer then asks for it chunk by chunk
 *   (luban/SacpClient.ts:933-996). An upload never starts a print.
 * - Starting a print, and anything that heats or moves the printer, is gated: see [START_VERIFIED].
 */
object SnapmakerSacp {
    /**
     * Whether starting a print from Nozzle It All (`0xb0/0x08`, luban/SacpClient.ts:386-392) and the controls that heat
     * or move the printer have been confirmed on a real J1 or Artisan. These are pause / resume / stop (0xac/0x03-0x06,
     * luban/SacpClient.ts:704-727), temperatures, homing (0x01/0x35) and moving (0x01/0x34). Until then the service uploads a sliced file and refuses
     * the start with [startNotVerified], and refuses every such control with [controlNotVerified], in both cases before
     * anything is sent. None of those messages is even built here. Flip only with a real printer run recorded in
     * docs/upstream/PROVENANCE.md, as AnycubicLan.START_VERIFIED.
     */
    const val START_VERIFIED = false

    /** luban/SacpTcpChannel.ts:68. */
    const val TCP_PORT = 8888
    /** Luban waits this long after the TCP connect before its hello (luban/SacpTcpChannel.ts:76 and 185, `setTimeout(..., 200)`). */
    const val HELLO_DELAY_MS = 200L
    /** Luban sends "Luban" as the client name (luban/SacpTcpChannel.ts:81); the field is free text (sdk/models/WifiConnectionInfo.js:6-12). */
    const val CLIENT_NAME = "Nozzle It All"
    /** Luban subscribes to the J1 / Artisan status at 1000 ms (luban/SacpChannel.ts:899, 976, 1034, 1129-1130). */
    const val SUBSCRIBE_INTERVAL_MS = 1000
    /** Ten seconds with no heartbeat and Luban drops the connection (luban/SacpChannel.ts:872-877). */
    const val HEARTBEAT_TIMEOUT_MS = 10_000L
    /** luban/SacpClient.ts:934. */
    const val CHUNK_BYTES = 60 * 1024

    // Peers and attributes (sdk/communication/Header.js:5-14).
    const val PEER_LUBAN = 0
    const val PEER_CONTROLLER = 1
    const val PEER_SCREEN = 2
    const val ATTR_REQUEST = 0
    const val ATTR_ACK = 1

    const val HEADER_BYTES = 13 // sdk/communication/Header.js:66
    private const val SOF_HI = 0xAA
    private const val SOF_LO = 0x55
    private const val VERSION = 0x01 // sdk/communication/Header.js:21

    // Commands this port sends or answers (set, id), with where Luban uses each.
    val HELLO = 0x01 to 0x05            // wifiConnection, to SCREEN, luban/SacpClient.ts:1309
    val BYE = 0x01 to 0x06              // wifiConnectionClose, to SCREEN (luban/SacpClient.ts:1320); the printer sends it too (1303-1307)
    val SUBSCRIBE = 0x01 to 0x00        // sdk/communication/Dispatcher.js:213-219
    val UNSUBSCRIBE = 0x01 to 0x01      // sdk/communication/Dispatcher.js:227-230
    val MACHINE_INFO = 0x01 to 0x21     // getMachineInfo, to CONTROLLER, luban/SacpClient.ts:599-603
    val HELLO_HEARTBEAT = 0xb0 to 0x0b  // wifiConnectionHeartBeat, to SCREEN, luban/SacpClient.ts:1314-1318
    val HEARTBEAT = 0x01 to 0xa0        // subscribeHeartbeat, luban/SacpClient.ts:534-538
    val NOZZLES = 0x10 to 0xa0          // subscribeNozzleInfo, luban/SacpClient.ts:808-812
    val BED = 0x14 to 0xa0              // subscribeHotBedTemperature, luban/SacpClient.ts:828-832
    val CURRENT_LINE = 0xac to 0xa0     // subscribeGetPrintCurrentLineNumber, luban/SacpClient.ts:222-226
    val PRINTING_TIME = 0xac to 0xa5    // subscribeGetPrintingTime, luban/SacpClient.ts:234-238
    val FILE_INFO = 0xac to 0x1a        // getPrintingFileInfo, to SCREEN (filePeerId, luban/SacpClient.ts:112), 203-220
    val UPLOAD_START = 0xb0 to 0x00     // uploadFile, to SCREEN, luban/SacpClient.ts:995
    val UPLOAD_CHUNK = 0xb0 to 0x01     // printer asks for a chunk, luban/SacpClient.ts:935-962
    val UPLOAD_DONE = 0xb0 to 0x02      // printer reports the result, luban/SacpClient.ts:966-976

    /** The subscriptions a status session opens, in Luban's order (luban/SacpChannel.ts:899, 976, 1034, 1129-1130). */
    val STATUS_SUBSCRIPTIONS = listOf(HEARTBEAT, BED, NOZZLES, CURRENT_LINE, PRINTING_TIME)

    // ---- the gate -----------------------------------------------------------------------------------------------------

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the printer. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any start message could be built. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    /** [what] names the control, e.g. "pausing a print". Same "isn't verified on real hardware yet" wording as the start. */
    fun controlNotVerified(what: String): String =
        "Nothing was sent: $what on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Use the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before a control message could be built. */
    fun requireControlVerified(what: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(controlNotVerified(what))
    }

    // ---- checksums (sdk/helper.js:141-173, "copied from Fabscreen") -----------------------------------------------------

    /** CRC-8, polynomial 0x07, initial 0, MSB first, over [length] bytes from [offset] (sdk/helper.js:157-173). */
    fun crc8(buffer: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            val b = buffer[i].toInt() and 0xFF
            for (j in 0 until 8) {
                val bit = (b shr (7 - j)) and 1
                val c07 = (crc shr 7) and 1
                crc = (crc shl 1) and 0xFF
                if (c07 != bit) crc = crc xor 0x07
            }
        }
        return crc and 0xFF
    }

    /**
     * The TCP/IP-style ones'-complement sum of big-endian 16-bit words. An odd last byte is added as the low byte, not
     * the high byte as in RFC 1071; that is how sdk/helper.js:141-155 does it and it is copied as it is.
     */
    fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length - 1) {
            sum += ((buffer[offset + i].toInt() and 0xFF) * 0x100 + (buffer[offset + i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if ((length and 1) > 0) sum += (buffer[offset + length - 1].toInt() and 0xFF).toLong()
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ---- packets ------------------------------------------------------------------------------------------------------

    /** One SACP packet (sdk/communication/Header.js:17-30, Packet.js:7-12). Equality compares the payload's bytes. */
    class Packet(
        val receiver: Int, val sender: Int, val attribute: Int, val sequence: Int,
        val commandSet: Int, val commandId: Int, val payload: ByteArray = ByteArray(0),
    ) {
        val command: Pair<Int, Int> get() = commandSet to commandId
        val isAck: Boolean get() = attribute == ATTR_ACK
        override fun equals(other: Any?): Boolean = other is Packet && receiver == other.receiver && sender == other.sender &&
            attribute == other.attribute && sequence == other.sequence && commandSet == other.commandSet &&
            commandId == other.commandId && payload.contentEquals(other.payload)
        override fun hashCode(): Int = listOf(receiver, sender, attribute, sequence, commandSet, commandId, payload.contentHashCode()).hashCode()
        override fun toString(): String = String.format(Locale.ROOT, "SACP(0x%02x/0x%02x %s seq=%d %d->%d, %d bytes)",
            commandSet, commandId, if (isAck) "ACK" else "REQ", sequence, sender, receiver, payload.size)
    }

    /**
     * The bytes of [p]. Header layout (sdk/communication/Header.js:38-52): SOF 0xAA55 (big-endian), length u16 (payload + 8,
     * sdk/communication/Dispatcher.js:126), version 1, receiver, CRC-8 of bytes 0..5, sender, attribute, sequence u16,
     * command set, command id. The checksum of bytes 7..end is appended as u16 (sdk/communication/Packet.js:20-26).
     */
    fun encode(p: Packet): ByteArray {
        require(p.payload.size <= 0xFFFF - 8) { "SACP payload too large" }
        val out = ByteArray(HEADER_BYTES + p.payload.size + 2)
        out[0] = SOF_HI.toByte(); out[1] = SOF_LO.toByte()
        putU16(out, 2, p.payload.size + 8)
        out[4] = VERSION.toByte()
        out[5] = p.receiver.toByte()
        out[6] = crc8(out, 0, 6).toByte()
        out[7] = p.sender.toByte()
        out[8] = p.attribute.toByte()
        putU16(out, 9, p.sequence)
        out[11] = p.commandSet.toByte()
        out[12] = p.commandId.toByte()
        System.arraycopy(p.payload, 0, out, HEADER_BYTES, p.payload.size)
        putU16(out, HEADER_BYTES + p.payload.size, checksum(out, 7, HEADER_BYTES + p.payload.size - 7))
        return out
    }

    /** A request from Nozzle It All (sender LUBAN, sdk/communication/Header.js:16, 21-30). */
    fun request(command: Pair<Int, Int>, receiver: Int, sequence: Int, payload: ByteArray = ByteArray(0)): Packet =
        Packet(receiver, PEER_LUBAN, ATTR_REQUEST, sequence, command.first, command.second, payload)

    /** The ACK to a printer's [request]: same command and sequence, back to its sender (sdk/communication/Dispatcher.js:175-191). */
    fun ackTo(request: Packet, payload: ByteArray): Packet =
        Packet(request.sender, PEER_LUBAN, ATTR_ACK, request.sequence, request.commandSet, request.commandId, payload)

    /** `++sequence % 0xffff`, so 0xFFFF is never used and the counter wraps to 0 (sdk/communication/Communication.js:69-73). */
    fun nextSequence(previous: Int): Int = (previous + 1) % 0xFFFF

    /**
     * Splits a TCP byte stream into packets (sdk/communication/Communication.js:120-230). It scans for 0xAA55, needs the
     * header's CRC-8 to match (else that byte is skipped, as the reference moves on to the next 0xAA), waits for
     * 7 + length bytes and drops a packet whose checksum over bytes 7..end-2 fails (Communication.js:224-230). A length
     * below 8 cannot hold a header and is skipped. Not thread-safe: one reader thread feeds it.
     */
    class Decoder {
        private var buf = ByteArray(0)

        fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<Packet> {
            buf = buf + bytes.copyOfRange(offset, offset + length)
            val out = mutableListOf<Packet>()
            var i = 0
            while (true) {
                while (i + 1 < buf.size && !((buf[i].toInt() and 0xFF) == SOF_HI && (buf[i + 1].toInt() and 0xFF) == SOF_LO)) i++
                if (i + 7 > buf.size) break
                if (crc8(buf, i, 6) != (buf[i + 6].toInt() and 0xFF)) { i++; continue }
                val len = u16(buf, i + 2)
                if (len < 8) { i++; continue }
                val total = 7 + len
                if (i + total > buf.size) break
                val frame = buf.copyOfRange(i, i + total)
                i += total
                if (checksum(frame, 7, total - 9) != u16(frame, total - 2)) continue
                out += Packet(frame[5].toInt() and 0xFF, frame[7].toInt() and 0xFF, frame[8].toInt() and 0xFF, u16(frame, 9),
                    frame[11].toInt() and 0xFF, frame[12].toInt() and 0xFF, frame.copyOfRange(HEADER_BYTES, total - 2))
            }
            buf = buf.copyOfRange(minOf(i, buf.size), buf.size)
            return out
        }

        /** Bytes held while waiting for the rest of a packet. */
        val pending: Int get() = buf.size
    }

    // ---- payload helpers (sdk/helper.js) ------------------------------------------------------------------------------

    private fun putU16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** A little-endian payload writer. `str` is sdk/helper.js:123-128: u16 byte length, then the UTF-8 bytes. */
    class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Long) = apply { for (s in 0 until 4) out.write(((v shr (8 * s)) and 0xFF).toInt()) }
        fun bytes(b: ByteArray) = apply {
            require(b.size <= 0xFFFF) { "SACP string too long" }
            u16(b.size); out.write(b, 0, b.size)
        }
        fun str(s: String) = bytes(s.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = out.toByteArray()
    }

    /** A little-endian payload reader (sdk/helper.js). Reading past the end throws IllegalArgumentException. */
    class Reader(private val b: ByteArray, var offset: Int = 0) {
        private fun need(n: Int) { require(offset + n <= b.size) { "SACP payload too short" } }
        fun u8(): Int { need(1); return b[offset++].toInt() and 0xFF }
        fun u16(): Int { need(2); val v = u16(b, offset); offset += 2; return v }
        fun u32(): Long { need(4); var v = 0L; for (s in 0 until 4) v = v or ((b[offset + s].toLong() and 0xFF) shl (8 * s)); offset += 4; return v }
        fun i16(): Int { val v = u16(); return if (v >= 0x8000) v - 0x10000 else v }
        fun i32(): Int = u32().toInt()
        /** `readFloat`: a signed int32 in thousandths (sdk/helper.js:95-98). */
        fun float(): Double = i32() / 1000.0
        /** `readString` (sdk/helper.js:107-116). */
        fun str(): String { val n = u16(); need(n); val s = String(b, offset, n, Charsets.UTF_8); offset += n; return s }
        val remaining: Int get() = b.size - offset
    }

    // ---- builders -----------------------------------------------------------------------------------------------------

    /** `WifiConnectionInfo(hostName, clientName, token)`: three strings (sdk/models/WifiConnectionInfo.js:13-19). */
    fun helloPayload(hostName: String, clientName: String = CLIENT_NAME, token: String = ""): ByteArray =
        Writer().str(hostName).str(clientName).str(token).build()

    /** `[set, id, interval u16]` (sdk/communication/Dispatcher.js:213-217). */
    fun subscribePayload(command: Pair<Int, Int>, intervalMs: Int = SUBSCRIBE_INTERVAL_MS): ByteArray =
        Writer().u8(command.first).u8(command.second).u16(intervalMs).build()

    /** `[set, id]` (sdk/communication/Dispatcher.js:229). */
    fun unsubscribePayload(command: Pair<Int, Int>): ByteArray = Writer().u8(command.first).u8(command.second).build()

    /** One zero byte (luban/SacpClient.ts:204, `Buffer.alloc(1, 0)`). */
    fun fileInfoPayload(): ByteArray = byteArrayOf(0)

    /** `Response(result)`: the result byte, then any data (sdk/communication/Response.js:14-18). */
    fun responsePayload(result: Int, data: ByteArray = ByteArray(0)): ByteArray = byteArrayOf(result.toByte()) + data

    /** Luban's number of chunks: `Math.ceil(length / 60 KiB)` (luban/SacpClient.ts:986). */
    fun chunkCount(length: Long): Int = ((length + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()

    /**
     * `0xb0/0x00` to the screen: file name, u32 length, u16 chunk count, md5 hex (luban/SacpClient.ts:988-993). Refuses a file
     * whose chunk count doesn't fit the u16 field.
     */
    fun uploadStartPayload(name: String, length: Long, md5Hex: String): ByteArray {
        val chunks = chunkCount(length)
        require(length in 0..0xFFFF_FFFFL && chunks <= 0xFFFF) { "The file is too large to send to a Snapmaker printer." }
        return Writer().str(name).u32(length).u16(chunks).str(md5Hex).build()
    }

    /** The printer's chunk request: md5 hex, then u16 index (luban/SacpClient.ts:936-937). */
    data class ChunkRequest(val md5Hex: String, val index: Int)

    fun parseChunkRequest(payload: ByteArray): ChunkRequest { val r = Reader(payload); return ChunkRequest(r.str(), r.u16()) }

    /** The chunk ACK: result 0, md5 hex, u16 index, then the chunk as a length-prefixed byte string (luban/SacpClient.ts:947-955). */
    fun chunkPayload(md5Hex: String, index: Int, chunk: ByteArray): ByteArray =
        Writer().u8(0).str(md5Hex).u16(index).bytes(chunk).build()

    /** A chunk that couldn't be read: the single byte 200 (luban/SacpClient.ts:944, 957-959). */
    fun chunkErrorPayload(): ByteArray = byteArrayOf(200.toByte())

    /** The printer's upload result: u8, 0 is success (luban/SacpClient.ts:967-975). */
    fun parseUploadResult(payload: ByteArray): Int = Reader(payload).u8()

    /** Lower-case md5 hex of a file's bytes, as luban/SacpClient.ts:977-983. */
    fun md5Hex(stream: java.io.InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = stream.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /**
     * The name the printer stores. Luban sends the file's own basename or a given name (luban/SacpClient.ts:984, 988); a path, a control
     * character or a name longer than 128 bytes is not sent.
     */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim()
        if (base.isEmpty() || base == "." || base == "..") throw ApiFailure("Nothing was sent: the file name isn't usable.")
        if (base.toByteArray(Charsets.UTF_8).size > 128) throw ApiFailure("Nothing was sent: the file name is too long for the printer.")
        return base
    }

    // ---- parsers ------------------------------------------------------------------------------------------------------

    /** `Response`: result u8, then data (sdk/communication/Response.js:14-18). An empty payload reads as result 0 with no data. */
    data class Response(val result: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Response && result == other.result && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * result + data.contentHashCode()
    }

    fun parseResponse(payload: ByteArray): Response =
        if (payload.isEmpty()) Response(0, ByteArray(0)) else Response(payload[0].toInt() and 0xFF, payload.copyOfRange(1, payload.size))

    /** `MachineInfo.fromBuffer` (sdk/models/MachineInfo.js:22-28). */
    data class MachineInfo(val type: Int, val hardwareVersion: Int, val serialNumber: Long, val firmwareVersion: String)

    fun parseMachineInfo(data: ByteArray): MachineInfo { val r = Reader(data); return MachineInfo(r.u8(), r.u8(), r.u32(), r.str()) }

    /**
     * The machine-type numbers the SDK names (sdk/models/MachineInfo.js:5-11). There is no Artisan value there; Luban
     * maps the number with SACP_TYPE_SERIES_MAP (luban/SacpTcpChannel.ts:106), which isn't in the sources used, so an
     * unnamed number is shown as it is.
     */
    fun machineTypeName(type: Int): String = when (type) {
        0 -> "A150"; 1 -> "A250"; 2 -> "A350"; 3 -> "A400"; 4 -> "J1"; else -> "type $type"
    }

    /** One `ExtruderInfo` entry (sdk/models/ExtruderInfo.js:19-35: 17 bytes). */
    data class Extruder(val index: Int, val filamentPresent: Boolean, val materialDetection: Int, val status: Int, val type: Int,
                        val diameter: Double, val temperature: Double, val target: Double)

    data class ExtruderInfo(val key: Int, val extruders: List<Extruder>)

    /** `ExtruderInfo.fromBuffer`: key, count, then 17-byte entries (sdk/models/ExtruderInfo.js:12-39). A cut-off entry is dropped. */
    fun parseExtruderInfo(data: ByteArray): ExtruderInfo {
        val r = Reader(data)
        val key = r.u8(); val count = r.u8()
        val list = mutableListOf<Extruder>()
        for (n in 0 until count) {
            if (r.remaining < 17) break
            list += Extruder(r.u8(), r.u8() != 0, r.u8(), r.u8(), r.u8(), r.float(), r.float(), r.float())
        }
        return ExtruderInfo(key, list)
    }

    /**
     * One `GetHotBed` zone (sdk/models/GetHotBed.js:19-28: 7 bytes). The current temperature is a thousandths float; the
     * target is a plain int16 (`readInt16`, sdk/models/GetHotBed.js:23), copied as it is.
     */
    data class BedZone(val index: Int, val temperature: Double, val target: Int)

    data class BedInfo(val key: Int, val zones: List<BedZone>)

    fun parseBedInfo(data: ByteArray): BedInfo {
        val r = Reader(data)
        val key = r.u8(); val count = r.u8()
        val zones = mutableListOf<BedZone>()
        for (n in 0 until count) {
            if (r.remaining < 7) break
            zones += BedZone(r.u8(), r.float(), r.i16())
        }
        return BedInfo(key, zones)
    }

    /** `GcodeCurrentLine.fromBuffer`: u32 at 0 (sdk/models/gcodeCurrentLine.js:11-13). */
    fun parseCurrentLine(data: ByteArray): Long = Reader(data).u32()

    /** Printing time: u32 seconds at 0 (luban/SacpChannel.ts:1131-1133, `readUInt32LE(0)` then `* 1000` as ms). */
    fun parsePrintingTime(data: ByteArray): Long = Reader(data).u32()

    /** The heartbeat's status key: u8 at 0 (luban/SacpChannel.ts:181, 869). */
    fun parseHeartbeat(data: ByteArray): Int = Reader(data).u8()

    /** `getPrintingFileInfo` data when result is 0: filename, u32 total lines, u32 estimated seconds (luban/SacpClient.ts:212-217). */
    data class FileInfo(val filename: String, val totalLines: Long, val estimatedSeconds: Long)

    fun parseFileInfo(data: ByteArray): FileInfo { val r = Reader(data); return FileInfo(r.str(), r.u32(), r.u32()) }

    // ---- status -------------------------------------------------------------------------------------------------------

    /**
     * What the subscriptions have said so far. Luban turns the heartbeat key into a workflow state with WORKFLOW_STATUS_MAP
     * (luban/SacpChannel.ts:183), which isn't in the sources used, so the key is kept but the state is always "unknown".
     * [left] / [right] follow luban/SacpChannel.ts:980-1036; the bed is zone 0 (968-979).
     */
    data class Status(
        val heartbeatKey: Int? = null,
        val lastHeartbeatAt: Long = 0L,
        val left: Extruder? = null,
        val right: Extruder? = null,
        val workNozzle: Int = 0,
        val bedTemperature: Double? = null,
        val bedTarget: Double? = null,
        val currentLine: Long? = null,
        val printingSeconds: Long? = null,
        val fileInfo: FileInfo? = null,
        val machine: MachineInfo? = null,
    ) {
        /** Luban's progress: current line over total lines (luban/SacpChannel.ts:1112), 0 until both are known. */
        val progress: Float get() {
            val total = fileInfo?.totalLines ?: 0L
            val line = currentLine ?: return 0f
            if (total <= 0L) return 0f
            return (line.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }

        /**
         * The printer as the app reads it. `state` is "unknown" (see the class comment), so [PrinterSnapshot.activeFilename]
         * stays blank: the file name is not shown as "printing" without a state that says so.
         */
        fun snapshot(ready: Boolean): PrinterSnapshot = PrinterSnapshot(
            ready = ready, state = "unknown", filename = fileInfo?.filename.orEmpty(), progress = progress,
            nozzle = left?.temperature, nozzleTarget = left?.target, bed = bedTemperature, bedTarget = bedTarget,
            printDuration = printingSeconds?.toDouble(),
            activeExtruder = if (left == null && right == null) "" else if (workNozzle == 1) "right nozzle" else "left nozzle",
        )

        fun toolheads(): List<ToolheadTemperature> = listOfNotNull(
            left?.let { ToolheadTemperature("Left nozzle", it.temperature, it.target) },
            right?.let { ToolheadTemperature("Right nozzle", it.temperature, it.target) },
        )
    }

    /**
     * Folds one subscription notification into [status]. A notification is a `Response` (sdk/communication/Dispatcher.js:
     * 96-103); the parsers read its data, as Luban's callbacks read `data.response.data`. Anything else is ignored, and so
     * is a notification whose data is too short.
     */
    fun apply(status: Status, command: Pair<Int, Int>, payload: ByteArray, now: Long): Status {
        val data = parseResponse(payload).data
        return try {
            when (command) {
                HEARTBEAT -> status.copy(heartbeatKey = parseHeartbeat(data), lastHeartbeatAt = now)
                BED -> parseBedInfo(data).zones.firstOrNull()
                    ?.let { status.copy(bedTemperature = it.temperature, bedTarget = it.target.toDouble()) } ?: status
                NOZZLES -> applyNozzles(status, parseExtruderInfo(data))
                CURRENT_LINE -> status.copy(currentLine = parseCurrentLine(data))
                PRINTING_TIME -> status.copy(printingSeconds = parsePrintingTime(data))
                else -> status
            }
        } catch (_: IllegalArgumentException) { status }
    }

    /**
     * luban/SacpChannel.ts:980-1036. One extruder: the module key picks the side (0 left, else right) and status 1 makes it
     * the working nozzle. Two: entry index 0 is left, 1 is right, and the right's status 1 makes it the working nozzle.
     */
    private fun applyNozzles(status: Status, info: ExtruderInfo): Status = when (info.extruders.size) {
        1 -> {
            val e = info.extruders[0]
            val work = if (e.status == 1) info.key else status.workNozzle
            if (info.key == 0) status.copy(left = e, workNozzle = work) else status.copy(right = e, workNozzle = work)
        }
        2 -> {
            val left = info.extruders.firstOrNull { it.index == 0 }
            val right = info.extruders.firstOrNull { it.index == 1 }
            status.copy(left = left ?: status.left, right = right ?: status.right, workNozzle = if (right?.status == 1) 1 else 0)
        }
        else -> status
    }

    /** The heartbeat is stale after [HEARTBEAT_TIMEOUT_MS] (luban/SacpChannel.ts:872-877). */
    fun heartbeatStale(status: Status, now: Long): Boolean = status.lastHeartbeatAt == 0L || now - status.lastHeartbeatAt > HEARTBEAT_TIMEOUT_MS
}
