package net.jamesjennison.klippercompanion

/**
 * The Marlin/Prusa firmware line protocol spoken over a USB-serial connection (PrinterKind.USB_SERIAL): line numbers
 * with a checksum, "ok"/"Resend:"/"busy:"/temperature-report parsing, M115 capability parsing, and a one-line send
 * window. Pure Kotlin, no I/O - [UsbSerialPrinterService] (module :transport) does the actual reading/writing.
 *
 * Referenced against the host-side implementations of this same protocol (never copied - see docs/upstream/
 * PROVENANCE.md P-0038 for exact citations):
 *  - Cura's USBPrinting plugin (LGPL-3.0), /mnt/faststorage/Test Slicer/Cura/Cura/plugins/USBPrinting/
 *    USBPrinterOutputDevice.py: line numbering/checksum, "ok"/"resend"/temperature-line handling.
 *  - PrusaSlicer (AGPL-3.0), src/libslic3r/GCodeSender.cpp and src/slic3r/Utils/Serial.cpp: the same protocol from a
 *    second, independent implementation.
 *
 * Every command this object ever proposes sending automatically is read-only/harmless: M110 N0 (reset the line
 * counter), M115 (firmware/capability report), M105 (temperature poll), M155 S<n> (temperature auto-report, only
 * when the firmware advertised Cap:AUTOREPORT_TEMP:1), M27 (SD status) and M20 (SD file list). Nothing that heats,
 * moves, extrudes or writes EEPROM/files is represented here at all - there is no function in this file that could
 * produce such a command.
 */
object MarlinSerial {

    // ---- line numbering + checksum ------------------------------------------------------------------------------

    /**
     * The classic RepRap/Marlin checksum: XOR of every byte in "N<line> <command>" (the line-number prefix and the
     * command, NOT the checksum itself or the trailing '*'), reproduced independently from Cura's protocol.py-style
     * computeChecksum used by USBPrinterOutputDevice (cura/USBPrinting/USBPrinterOutputDevice.py) and PrusaSlicer's
     * GCodeSender.cpp `checksum()` - both compute the same XOR fold, cross-checked against each other rather than
     * copied from either.
     */
    fun checksum(data: String): Int {
        var c = 0
        for (b in data.toByteArray(Charsets.US_ASCII)) c = c xor (b.toInt() and 0xFF)
        return c and 0xFF
    }

    /** Builds "N<line> <command>*<checksum>\n", the line-numbered, checksummed form Marlin's resend logic expects. */
    fun numberedLine(lineNumber: Long, command: String): String {
        val body = "N$lineNumber $command"
        return "$body*${checksum(body)}\n"
    }

    /** M110 N0: resets the firmware's expected line counter to 0 before the first numbered line is sent. Read-only/harmless: it renumbers, nothing else. */
    const val RESET_LINE_NUMBER = "M110 N0"

    // ---- reply parsing --------------------------------------------------------------------------------------------

    sealed class Reply {
        /** A plain "ok", optionally carrying inline temperatures (Marlin appends them to "ok" while a heater is on). */
        data class Ok(val temperatures: TemperatureReport?) : Reply()
        /** "Resend:<n>" or "rs <n>" (Marlin's alternate short form) - go back and resend from line n. */
        data class Resend(val line: Long) : Reply()
        /** "busy: processing" / "echo:busy processing" - the firmware is still working on the previous line; wait, don't resend. */
        object Busy : Reply()
        /** "Error:..." - a firmware-reported error line. */
        data class Error(val message: String) : Reply()
        /** "start" - the firmware just (re)booted, e.g. after a restart-board action. */
        object Start : Reply()
        /** An asynchronous temperature report (M105 reply or a M155 auto-report push), not tied to an "ok". */
        data class Temperature(val report: TemperatureReport) : Reply()
        /** Anything else (an echo, a G-code comment reply, etc.) - shown, not acted on. */
        data class Other(val line: String) : Reply()
    }

    data class ToolTemperature(val index: Int, val current: Double, val target: Double?)
    data class TemperatureReport(val hotends: List<ToolTemperature>, val bed: ToolTemperature?)

    private val RESEND_RE = Regex("""(?i)^(?:resend|rs)\s*:?\s*(\d+)""")
    private val ERROR_RE = Regex("""(?i)^error\s*:\s*(.*)$""")
    private val TEMP_TOKEN_RE = Regex("""(?i)\b(T\d*|B)\s*:\s*(-?\d+(?:\.\d+)?)\s*(?:/\s*(-?\d+(?:\.\d+)?))?""")

    /**
     * Parses temperature tokens out of a line: "T:210.0 /210.0 B:60.0 /60.0" or multi-extruder "T0:210 /210 T1:0 /0
     * B:60 /60" (Cura's temperature regex and PrusaSlicer's GCodeSender both key on the same "T"/"Tn"/"B" prefixes).
     * A bare "T:" with no index means hotend 0. Returns null when the line has no recognizable temperature token.
     */
    fun parseTemperatures(line: String): TemperatureReport? {
        val matches = TEMP_TOKEN_RE.findAll(line).toList()
        if (matches.isEmpty()) return null
        val hotends = mutableListOf<ToolTemperature>()
        var bed: ToolTemperature? = null
        for (m in matches) {
            val label = m.groupValues[1].uppercase()
            val current = m.groupValues[2].toDoubleOrNull() ?: continue
            val target = m.groupValues[3].toDoubleOrNull()
            if (label == "B") bed = ToolTemperature(0, current, target)
            else {
                val index = label.removePrefix("T").toIntOrNull() ?: 0
                hotends.add(ToolTemperature(index, current, target))
            }
        }
        if (hotends.isEmpty() && bed == null) return null
        return TemperatureReport(hotends, bed)
    }

    /** Parses one line already stripped of its trailing newline. Blank lines are [Reply.Other] with an empty string. */
    fun parseReply(rawLine: String): Reply {
        val line = rawLine.trim()
        if (line.isEmpty()) return Reply.Other("")
        val lower = line.lowercase()
        if (lower == "start") return Reply.Start
        RESEND_RE.find(line)?.let { return Reply.Resend(it.groupValues[1].toLong()) }
        if (lower.startsWith("busy:") || lower.startsWith("echo:busy")) return Reply.Busy
        ERROR_RE.find(line)?.let { return Reply.Error(it.groupValues[1].trim()) }
        if (lower == "ok" || lower.startsWith("ok ") || lower.startsWith("ok\t")) {
            val rest = line.removePrefix("ok").removePrefix("ok").trim()
            return Reply.Ok(parseTemperatures(rest))
        }
        // Marlin sometimes prefixes an inline "ok" earlier in the token stream, e.g. "ok T:210 /210 B:60 /60".
        if (Regex("""(?i)^ok\b""").containsMatchIn(line)) return Reply.Ok(parseTemperatures(line))
        parseTemperatures(line)?.let { return Reply.Temperature(it) }
        return Reply.Other(line)
    }

    // ---- M115 capabilities -----------------------------------------------------------------------------------------

    data class Capabilities(
        val firmwareName: String = "",
        val machineType: String = "",
        val extruderCount: Int = 1,
        val caps: Map<String, Boolean> = emptyMap(),
    ) {
        /** Cap:AUTOREPORT_TEMP:1 - the firmware pushes M155 S<n> temperature reports on its own once armed. */
        val supportsAutoReportTemp: Boolean get() = caps["AUTOREPORT_TEMP"] == true
        val supportsSdCard: Boolean get() = caps["SDCARD"] == true
    }

    private val M115_FIELD_RE = Regex("""(FIRMWARE_NAME|MACHINE_TYPE|EXTRUDER_COUNT)\s*:\s*("[^"]*"|[^\s]+)""")
    private val CAP_LINE_RE = Regex("""(?i)^\s*Cap\s*:\s*([A-Z0-9_]+)\s*:\s*([01])\s*$""")

    /**
     * Parses the full text of an M115 reply (one or more lines, ending in "ok"): the FIRMWARE_NAME/MACHINE_TYPE/
     * EXTRUDER_COUNT fields on the first line, and every "Cap:NAME:0"/"Cap:NAME:1" line Marlin's EXTENDED_CAPABILITIES_REPORT
     * emits (one capability per line - see Marlin's own M115.cpp report shape, referenced only for the "Cap:NAME:bit"
     * line shape, not copied). Unknown fields/caps are simply absent, not an error.
     */
    fun parseCapabilities(reply: String): Capabilities {
        var firmwareName = ""; var machineType = ""; var extruderCount = 1
        val caps = mutableMapOf<String, Boolean>()
        for (rawLine in reply.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            CAP_LINE_RE.find(line)?.let { m ->
                caps[m.groupValues[1].uppercase()] = m.groupValues[2] == "1"
                return@let
            }
            for (m in M115_FIELD_RE.findAll(line)) {
                val value = m.groupValues[2].trim('"')
                when (m.groupValues[1]) {
                    "FIRMWARE_NAME" -> firmwareName = value
                    "MACHINE_TYPE" -> machineType = value
                    "EXTRUDER_COUNT" -> extruderCount = value.toIntOrNull() ?: extruderCount
                }
            }
        }
        return Capabilities(firmwareName, machineType, extruderCount, caps)
    }

    // ---- SD status / file list --------------------------------------------------------------------------------------

    data class SdStatus(val printing: Boolean, val bytesPrinted: Long?, val bytesTotal: Long?, val filename: String = "")

    private val M27_RE = Regex("""(?i)SD printing byte\s+(\d+)\s*/\s*(\d+)""")

    /**
     * Parses M27's reply. "Not SD printing" (Marlin's own text when idle) means [SdStatus.printing] is false with no
     * byte counts. "SD printing byte 1234/5678" (optionally on its own line, with a preceding filename line some
     * firmwares add) gives the counts. Read-only: M27 never starts, stops or selects anything.
     */
    fun parseSdStatus(reply: String): SdStatus {
        val lines = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.equals("Not SD printing", ignoreCase = true) }) return SdStatus(false, null, null)
        for (line in lines) {
            M27_RE.find(line)?.let { m -> return SdStatus(true, m.groupValues[1].toLong(), m.groupValues[2].toLong()) }
        }
        return SdStatus(false, null, null)
    }

    /** Parses M20's "Begin file list" / one filename per line / "End file list" reply into a plain file list. */
    fun parseFileList(reply: String): List<String> {
        val lines = reply.lines().map { it.trim() }
        val begin = lines.indexOfFirst { it.equals("Begin file list", ignoreCase = true) }
        val end = lines.indexOfFirst { it.equals("End file list", ignoreCase = true) }
        val body = if (begin >= 0 && end > begin) lines.subList(begin + 1, end) else lines.filter { it.isNotEmpty() && !it.equals("ok", ignoreCase = true) }
        // Some firmwares append " 12345" (a byte size) after the filename; keep only the name.
        return body.filter { it.isNotEmpty() }.map { it.substringBefore(' ').trim() }.filter { it.isNotEmpty() }
    }

    // ---- send window: one line in flight, awaiting "ok", with resend --------------------------------------------

    /**
     * Tracks the "one line awaiting ok" send window plus resend recovery, entirely as state transitions - no I/O.
     * [UsbSerialPrinterService] owns the actual socket write; this only decides *what* to send next given what has
     * come back. Modeled after Cura's USBPrinterOutputDevice (a `1` line in flight, `_num_extra_lines` untouched
     * here since this app never streams a full job - see START_VERIFIED below) and PrusaSlicer's GCodeSender.cpp
     * resend loop, both of which resend by line number rather than by re-deriving from scratch.
     */
    class SendWindow {
        private val history = LinkedHashMap<Long, String>() // line number -> full numbered line (for resend)
        private var nextLineNumber = 1L
        private var awaiting: Long? = null

        val hasLineInFlight: Boolean get() = awaiting != null

        /** True only while nothing is awaiting a reply: the one place that gates whether a new line may be sent. */
        fun canSend(): Boolean = awaiting == null

        /** Prepares the next numbered/checksummed line for [command] and marks it as awaiting "ok". Throws if a line is already in flight. */
        fun prepareSend(command: String): String {
            check(canSend()) { "A line is already awaiting ok." }
            val n = nextLineNumber++
            val line = numberedLine(n, command)
            history[n] = line
            awaiting = n
            // Keep only enough history to satisfy a firmware asking to resend the last handful of lines.
            while (history.size > 64) history.remove(history.keys.first())
            return line
        }

        sealed class Outcome {
            /** The in-flight line was acknowledged; the window is open for the next [prepareSend]. */
            object Cleared : Outcome()
            /** The firmware asked to resend [line]'s [text] verbatim; it is now the (new) line in flight. */
            data class Resend(val line: Long, val text: String) : Outcome()
            /** Busy, an inline temperature push, or anything else that isn't an ok/resend: still waiting. */
            object StillWaiting : Outcome()
        }

        /** Reports [reply] for the line currently in flight (or for an out-of-band push while idle). */
        fun onReply(reply: Reply): Outcome = when (reply) {
            is Reply.Ok -> { awaiting = null; Outcome.Cleared }
            is Reply.Resend -> {
                val text = history[reply.line] ?: return Outcome.StillWaiting // nothing that old kept in history; nothing safe to resend
                awaiting = reply.line
                Outcome.Resend(reply.line, text)
            }
            else -> Outcome.StillWaiting
        }
    }
}
