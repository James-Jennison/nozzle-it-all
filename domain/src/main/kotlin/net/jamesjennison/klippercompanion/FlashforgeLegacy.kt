package net.jamesjennison.klippercompanion

/**
 * The older Flashforge network protocol (Adventurer 3 / 4, Creator, Guider; PrinterKind.FLASHFORGE without a serial number
 * and access code): `~M` G-code lines over a plain TCP connection to port 8899. Pure message builders and the reply rule,
 * no I/O. FlashforgeLegacyPrinterService does the talking; FlashforgeIfs is the newer port-8898 HTTP API.
 *
 * Source (docs/upstream/PROVENANCE.md P-0035): upstream OrcaSlicer 5298e49d `src/slic3r/Utils/Flashforge.cpp` / `.hpp`
 * and the console it drives, `src/slic3r/Utils/TCPConsole.cpp` / `.hpp`. Nothing here has been run against a printer.
 *
 * - Which protocol: upstream uses the port-8898 local API only when BOTH the serial number and the access code are set,
 *   and this legacy console otherwise (Flashforge.cpp:329-332, 410-413): [usesLegacy].
 * - Framing (TCPConsole.cpp:26-107): each command is its text plus `\n` (the commands already end in `\r\n`, so they go out
 *   as `...\r\n\n`), then reply lines are read until one, trimmed and lower-cased, is exactly `ok`. File data goes out raw,
 *   without waiting for any reply. Connect 5 s, read and write 10 s (TCPConsole.hpp:27-35).
 * - Connection test: `~M601 S1` alone (Flashforge.cpp:329-345). Connect: `~M601 S1`, `~M115`, `~M650` (`~M640` for a
 *   Klipper-flavoured profile), `~M119` (Flashforge.cpp:364-391).
 * - Upload (Flashforge.cpp:410-492): connect; then on a new connection `~M28 <bytes> 0:/user/<name>` and the file in 4096-
 *   byte pieces; then, 3 s later and on another new connection, `~M29\r\n` to save it. Starting it is `~M23 0:/user/<name>`
 *   (Flashforge.cpp:393-408): gated, see [START_VERIFIED].
 *
 * Upstream reads none of the replies (it only waits for `ok`): `~M105` temperatures and `~M27` progress are declared in
 * Flashforge.hpp:63-64 but never sent or parsed. So this reads no printer state either ([probeSnapshot]).
 */
object FlashforgeLegacy {
    /**
     * Whether starting a print over the legacy protocol (`~M23 0:/user/<name>`) has been confirmed on a real Flashforge.
     * Until then an upload is `~M28` / data / `~M29` only (never `~M23`) and the start is refused with [startNotVerified].
     * Flip only with a real run recorded (docs/upstream/PROVENANCE.md), as FlashforgeIfs.START_VERIFIED.
     */
    const val START_VERIFIED = false

    const val PORT = 8899

    /** Same wording as FlashforgeIfs: the vendor's start isn't verified, whichever of its two protocols is used. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Flashforge printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before `~M23` is built or sent. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    /** Flashforge.cpp:329-332 / 410-413: the local HTTP API needs both; anything less is the legacy console. */
    fun usesLegacy(serial: String, checkCode: String): Boolean = serial.isBlank() || checkCode.isBlank()

    // The command texts, byte for byte as Flashforge.hpp:57-65 declares them.
    const val CONTROL = "~M601 S1\r\n"
    const val CONNECT_KLIPPER = "~M640\r\n"
    const val CONNECT_LEGACY = "~M650\r\n"
    const val DEVICE_INFO = "~M115\r\n"
    const val STATUS = "~M119\r\n"
    const val SAVE_FILE = "~M29\r\n"

    /** TCPConsole's line delimiter, appended to every command (never to file data). */
    const val NEWLINE = "\n"
    /** Flashforge.cpp:212 (m_bufferSize): the upload's piece size. */
    const val CHUNK_BYTES = 4096
    /** Flashforge.cpp:473: the delay before `~M29`. */
    const val SAVE_DELAY_MS = 3000L
    const val CONNECT_TIMEOUT_MS = 5000
    const val READ_TIMEOUT_MS = 10000

    /** A command as it goes on the wire (TCPConsole.cpp:43-47). */
    fun wire(command: String): String = command + NEWLINE

    /** The reply rule (TCPConsole.cpp:92-105): a line, trimmed and lower-cased, equal to `ok` ends the command's reply. */
    fun isDone(line: String): Boolean = line.trim().lowercase() == "ok"

    /** Upstream's connect() sequence (Flashforge.cpp:364-391). [klipper]: the printer profile's G-code flavour is Klipper. */
    fun connectSequence(klipper: Boolean = false): List<String> = listOf(CONTROL, DEVICE_INFO, if (klipper) CONNECT_KLIPPER else CONNECT_LEGACY, STATUS)

    /** `~M28 <bytes> 0:/user/<name>` (Flashforge.cpp:445-447); upstream adds no `\r\n` here, only the console's `\n`. */
    fun beginUpload(size: Long, name: String): String = "~M28 $size 0:/user/$name"

    /** `~M23 0:/user/<name>` (Flashforge.cpp:397). Only built behind [requireStartVerified]. */
    fun startPrint(name: String): String = "~M23 0:/user/$name"

    /**
     * sanitize_flashforge_filename (Flashforge.cpp:185-202): the base name, or `print` + [fallbackExtension] when there is
     * none; every BYTE of the UTF-8 name other than ASCII letters, digits, `.`, `_` and `-` becomes `_` (upstream loops over
     * a std::string's chars, so a two-byte character becomes `__`).
     */
    fun fileName(name: String, fallbackExtension: String = ".gcode"): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "print$fallbackExtension" }
        return base.toByteArray(Charsets.UTF_8).map { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")
    }

    /**
     * A Flashforge that answered `~M601 S1` with `ok`. Upstream reads no state, so the state is "unknown" and `ready` is
     * false: the app's send button (which needs a ready printer) stays off, so nothing is written to a printer whose state
     * nobody knows. The service's own upload remains for when a state read exists.
     */
    fun probeSnapshot(): PrinterSnapshot = PrinterSnapshot(false, "unknown")
}
