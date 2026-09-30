package net.jamesjennison.klippercompanion

/**
 * PrinterKind.USB_SERIAL's gate, in the same shape as SnapmakerSstp/SnapmakerSacp: nothing risky is sent until a real
 * board has been checked and this is flipped, and every refusal happens before a single byte goes out over the wire.
 *
 * What "risky" means here, per the work order: streaming a full print over the USB connection; uploading a file with
 * M28/M29 (a real state machine for that is implemented below, entirely behind [UPLOAD_VERIFIED]); starting an SD
 * file (M23/M24); pause/resume/stop; setting a temperature; homing or jogging; running a fan. The only commands this
 * app ever sends automatically while merely *connected and reading status* are M110 N0, M115, M105, M155 S<n> (only
 * once the firmware has advertised Cap:AUTOREPORT_TEMP:1), M27 and M20 - see MarlinSerial's own header comment.
 *
 * There is no console here and never will be in this change: nothing in :domain, :transport or :app for USB_SERIAL
 * accepts a user-typed or otherwise arbitrary G-code string.
 */
object UsbSerialPrinter {
    /** Whether starting a print, or any control that heats/moves/extrudes, has been confirmed on a real USB-connected printer. */
    const val START_VERIFIED = false

    /** Whether M28/M29 file upload (SD-card write) has been confirmed on a real board. Independent of [START_VERIFIED]
     * because an upload never starts anything by itself - but it still writes to the printer's storage, so it gets its
     * own flag rather than riding along with "may this app start a print". */
    const val UPLOAD_VERIFIED = false

    fun startNotVerified(remoteName: String): String =
        "USB-connected printers aren't verified on real hardware yet. $remoteName was not sent. " +
            "Copy the sliced file to the printer's SD card or USB stick and start it from the printer's own screen."

    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName))
    }

    fun controlNotVerified(what: String): String =
        "USB-connected printers aren't verified on real hardware yet: $what isn't offered."

    fun requireControlVerified(what: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(controlNotVerified(what))
    }

    fun uploadNotVerified(remoteName: String): String =
        "USB-connected printers aren't verified on real hardware yet. Uploading $remoteName to the printer's storage " +
            "isn't offered. Copy the sliced file to the printer's SD card or USB stick manually."

    fun requireUploadVerified(remoteName: String, verified: Boolean = UPLOAD_VERIFIED) {
        if (!verified) throw ApiFailure(uploadNotVerified(remoteName))
    }

    /** A send of a sliced file over this transport never saves it anywhere on its own; see [requireUploadVerified]. */
    const val CANNOT_SAVE_TO_PRINTER =
        "Nozzle It All can't save a file to a USB-connected printer's storage. Copy it to the printer's SD card or " +
            "USB stick manually, then print it from the printer's own screen."

    // ---- M28/M29 upload state machine (built now, entirely behind UPLOAD_VERIFIED) --------------------------------

    /**
     * The M28/M29 SD-file-write handshake: M28 asks the firmware to open a file for writing on its SD card; Marlin's
     * own reply is a line containing "Writing to file" once it is ready to receive raw file bytes (see Cura's
     * USBPrinterOutputDevice._sendCommand handling of the M28 reply and PrusaSlicer's GCodeSender.cpp upload path,
     * both of which wait for that acknowledgement before writing any file data - cross-checked between the two, not
     * copied from either). Sending file bytes before that ack arrives would either be silently dropped or interpreted
     * as G-code by the firmware, so this state machine refuses to hand back a single byte to write until the ack is
     * seen, and gives up (without ever having sent anything) if the firmware answers with anything else first.
     *
     * This class only decides what is safe to do; it never touches a serial port. [UsbSerialPrinterService] is the
     * only thing that would ever construct one, and only once [UPLOAD_VERIFIED] is true.
     */
    class SdUpload(private val remoteName: String) {
        enum class State { AWAITING_WRITING_TO_FILE, READY_TO_WRITE, ABORTED, DONE }

        var state: State = State.AWAITING_WRITING_TO_FILE
            private set

        /** The M28 command to send to start the handshake: "M28 <remoteName>". Never sent unless [UPLOAD_VERIFIED]. */
        fun startCommand(): String = "M28 $remoteName"

        /**
         * Feeds one raw reply line from the firmware after M28 was sent. Returns true once [state] is
         * [State.READY_TO_WRITE] (file bytes may now be sent); false otherwise (including once [State.ABORTED]).
         * "Writing to file" is Marlin's own ack text (case-insensitive, matched as a substring since firmwares vary
         * on exact punctuation/wording around it).
         */
        fun onLineAfterM28(line: String): Boolean {
            if (state != State.AWAITING_WRITING_TO_FILE) return state == State.READY_TO_WRITE
            return when {
                line.contains("writing to file", ignoreCase = true) -> { state = State.READY_TO_WRITE; true }
                // An explicit error, or anything else at all, means the file was not opened for writing: abort rather
                // than guess. (A "busy" line is not "anything else" here on purpose - Marlin's own file-open reply
                // never overlaps with the busy-protocol text, so this class doesn't special-case it.)
                else -> { state = State.ABORTED; false }
            }
        }

        /** True only while file bytes may be written; false before the ack and forever after [abort]/[finish]. */
        fun canWriteFileBytes(): Boolean = state == State.READY_TO_WRITE

        /** M29 closes the file. Only meaningful once [canWriteFileBytes]. */
        fun finishCommand(): String {
            check(state == State.READY_TO_WRITE) { "Cannot finish an upload that was never ready to write." }
            state = State.DONE
            return "M29"
        }

        fun abort() { state = State.ABORTED }
    }
}
