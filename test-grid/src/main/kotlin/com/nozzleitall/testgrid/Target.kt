package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.ElegooProfiles
import net.jamesjennison.klippercompanion.FirmwareIdentity
import net.jamesjennison.klippercompanion.PrinterDiscovery
import net.jamesjennison.klippercompanion.PrinterKind
import net.jamesjennison.klippercompanion.PrinterTransport
import net.jamesjennison.klippercompanion.SlicingPrinterModel
import net.jamesjennison.klippercompanion.capabilitiesFor
import net.jamesjennison.klippercompanion.startVerifiedFor
import java.io.File

/**
 * What the operator picked. [label] and [address] stay on the device: the redactor masks them and the evidence
 * bundle never includes them.
 */
data class TargetDescription(
    val kind: TargetKind,
    val manufacturer: String,
    val model: String,
    val printerKind: PrinterKind,
    val adapter: String,
    val protocol: String,
    val label: String,
    val address: String,
    val slicingModel: SlicingPrinterModel?,
    /** For firmware the printer can't report (OpenCentauri-patched vs stock Elegoo): the operator's declaration. */
    val declaredFirmwareFamily: String? = null,
    /** Tool or filament slots the saved slicing profile declares (a Prusa XL 5T: 5, CANVAS: 4); more than one means multi-material. */
    val toolSlots: Int = 1,
)

/** A live, read-only identity reading. [hostname] is local-only. */
data class LiveIdentity(val firmware: FirmwareIdentity?, val hostname: String, val paxxExtendedConfig: Boolean, val afc: Boolean)

data class StatusReading(
    val state: String, val ready: Boolean, val nozzle: Double?, val nozzleTarget: Double?, val bed: Double?, val bedTarget: Double?,
    val progress: Float?, val filename: String, val observedAtMillis: Long,
    /** Klipper's toolhead.homed_axes ("xyz" when fully homed); null when the connection can't report it. */
    val homedAxes: String? = null,
    /** Toolhead position X, Y, Z in mm; null when not reported. */
    val position: List<Double>? = null,
) {
    val idle: Boolean get() = ready && state in IDLE_STATES

    companion object {
        /** Same idle rule as HeaterControls.idleStates, the gate every physical control in the app already uses. */
        val IDLE_STATES = setOf("standby", "complete", "cancelled")
    }
}

data class CameraInfo(val name: String, val url: String)

sealed class TransferOutcome {
    data class Verified(val remotePath: String, val sha256: String) : TransferOutcome()
    /** [sent] false: refused before anything reached the printer. */
    data class Refused(val reason: String, val sent: Boolean = false) : TransferOutcome()
    /** The request may have reached the printer. Never retried automatically. */
    data class Unknown(val reason: String) : TransferOutcome()
}

sealed class CommandOutcome {
    object Accepted : CommandOutcome()
    data class Rejected(val reason: String, val sent: Boolean) : CommandOutcome()
    data class Unknown(val reason: String) : CommandOutcome()
}

/** The only printer-changing actions a suite can reach. Each describes itself exactly as the operator approves it. */
sealed class ControlAction(val allowedStates: Set<String>) {
    abstract fun describe(): String

    data class SetTemperature(val heater: String, val celsius: Int) : ControlAction(StatusReading.IDLE_STATES) {
        override fun describe() = when {
            celsius == 0 && heater == "bed" -> "Turn the bed heater off"
            celsius == 0 -> "Turn the nozzle heater off"
            heater == "bed" -> "Heat the bed to $celsius °C"
            else -> "Heat the active nozzle to $celsius °C"
        }
    }
    object Home : ControlAction(StatusReading.IDLE_STATES) { override fun describe() = "Home all axes (G28). The toolhead and bed will move." }
    data class Jog(val axis: String, val mm: Double) : ControlAction(StatusReading.IDLE_STATES) {
        override fun describe() = "Move the $axis axis by ${if (mm > 0) "+" else ""}$mm mm (relative move, 3000 mm/min)"
    }
    data class StartPrint(val remotePath: String) : ControlAction(StatusReading.IDLE_STATES) {
        override fun describe() = "Start printing $remotePath. The printer will heat, move and extrude."
    }
    object Pause : ControlAction(setOf("printing")) { override fun describe() = "Pause the current print" }
    object Resume : ControlAction(setOf("paused")) { override fun describe() = "Resume the paused print. The printer will move and extrude." }
    object Cancel : ControlAction(setOf("printing", "paused")) { override fun describe() = "Cancel the current print. This cannot be undone." }
}

class UnsupportedByTarget(message: String) : Exception(message)

/**
 * The printer as the runner sees it. Read methods never change printer state. [upload], [delete] and [perform] are
 * called only after the operator approved that exact step, and at most once per approval; implementations must not
 * retry, and must report [TransferOutcome.Unknown]/[CommandOutcome.Unknown] whenever a request may have been sent
 * without a verified reply.
 */
interface TestTarget {
    val description: TargetDescription
    fun identity(): LiveIdentity
    fun status(): StatusReading
    /** Capability names from [CapabilityNames]; what this printer and adapter declare. */
    fun declaredCapabilities(): Set<String>
    fun cameras(): List<CameraInfo>
    fun cameraSnapshot(camera: CameraInfo): ByteArray
    fun files(): List<String>
    fun materialSlots(): List<String>
    /** The upload path's own refusal for [file], or null if it would accept it. Must not send anything. */
    fun uploadPreflight(file: File): String?
    fun upload(file: File, requestedName: String): TransferOutcome
    fun delete(remotePath: String): TransferOutcome
    fun perform(action: ControlAction): CommandOutcome
    /**
     * Sends [file] and starts printing it in one request, as the app's "Send and print" does for printers that only take
     * a file that way. Verified means the printer accepted and started it; the runner confirms a lost reply from state.
     */
    fun sendAndStart(file: File, requestedName: String): TransferOutcome = throw UnsupportedByTarget("This printer connection takes files through upload and start steps instead.")
    /** How the printer's own job history records the last print of [remotePath] ("completed", "cancelled"...), or null. Read-only. */
    fun jobResult(remotePath: String): String? = null
    /** Credentials, addresses, hostnames and names this target knows. The redactor masks every occurrence. */
    fun localSecrets(): Set<String>
}

/**
 * Capability names used by suites; the same words as docs/family/CAPABILITY_MATRIX.md where one exists. Derived from
 * the app's own PrinterCapabilities, never a second hand-maintained table.
 */
object CapabilityNames {
    const val STATUS = "status"
    const val FIRMWARE_IDENTITY = "firmware_identity"
    const val CAMERA = "camera"
    const val FILES = "files"
    const val UPLOAD_JOB = "upload_job"
    const val UPLOAD_AND_START = "upload_and_start"
    const val START_PRINT = "start_print"
    const val PAUSE = "pause_print"
    const val RESUME = "resume_print"
    const val CANCEL = "cancel_print"
    const val TEMPERATURES = "temperatures"
    const val MOTION = "motion"
    const val MATERIAL_STATE = "material_state"
    const val MULTI_MATERIAL = "multi_material"

    val KNOWN = setOf(STATUS, FIRMWARE_IDENTITY, CAMERA, FILES, UPLOAD_JOB, UPLOAD_AND_START, START_PRINT, PAUSE, RESUME, CANCEL,
        TEMPERATURES, MOTION, MATERIAL_STATE, MULTI_MATERIAL)

    fun declared(kind: PrinterKind, hardware: Map<String, Boolean> = emptyMap()): Set<String> {
        val c = capabilitiesFor(kind)
        val moonraker = c.transport == PrinterTransport.MOONRAKER
        return buildSet {
            add(STATUS)
            if (moonraker) { add(FIRMWARE_IDENTITY); add(FILES); add(UPLOAD_JOB); add(START_PRINT) }
            // A kind whose start is still gated off (startVerifiedFor) only uploads: it doesn't claim send-and-print.
            if (!moonraker && c.acceptsOnDeviceSlicedGcode && startVerifiedFor(kind)) add(UPLOAD_AND_START)
            if (c.supportsCamera) add(CAMERA)
            if (c.supportsPauseResumeCancel) { add(PAUSE); add(RESUME); add(CANCEL) }
            if (c.supportsKlipperExtras) add(TEMPERATURES)
            if (c.supportsJog) add(MOTION)
            if (c.hasMultiAce || c.transport in setOf(PrinterTransport.ELEGOO, PrinterTransport.CREALITY, PrinterTransport.FLASHFORGE) || hardware["canvas"] == true) add(MATERIAL_STATE)
            if (hardware["canvas"] == true || hardware["multi_tool"] == true) add(MULTI_MATERIAL)
        }
    }
}

/**
 * Firmware families the Test Grid grades separately. Two printers in different families never share a grade: a COSMOS
 * result says nothing about Stock or OpenCentauri-patched firmware, and a PAXX result says nothing about stock U1.
 */
object FirmwareFamilies {
    const val PAXX = "paxx-extended"
    const val SNAPMAKER_STOCK = "snapmaker-stock"
    const val COSMOS = "cosmos"
    const val KLIPPER = "klipper"
    const val ELEGOO_STOCK = "elegoo-stock"
    const val OPENCENTAURI_PATCHED = "opencentauri-patched"
    const val BAMBU = "bambu-lan"
    const val PRUSALINK = "prusalink"
    const val OCTOPRINT = "octoprint"
    const val CREALITY = "creality-lan"
    const val FLASHFORGE = "flashforge-lan"
    const val FLASHFORGE_LEGACY = "flashforge-legacy"
    const val DUET = "duet-rrf"
    const val ULTIMAKER = "ultimaker-lan"
    const val REPETIER = "repetier-server"

    /** TargetDescription.protocol of a Flashforge on the legacy console (AndroidTestTarget.adapterFor). */
    const val LEGACY_FLASHFORGE_PROTOCOL = "flashforge-tcp"

    data class Classified(val family: String, val kind: PrinterKind, val slicingModel: SlicingPrinterModel?, val hardware: Map<String, Boolean>, val detail: String)

    /**
     * Classifies from a live read with the same rules the network scan uses (PrinterDiscovery.classifyMoonraker), so the
     * Test Grid can't disagree with discovery. Non-Moonraker printers can't report firmware; their family comes from
     * the connection type, or the operator's declaration where two firmwares share one protocol.
     */
    fun classify(description: TargetDescription, identity: LiveIdentity?): Classified = when (capabilitiesFor(description.printerKind).transport) {
        PrinterTransport.MOONRAKER -> {
            val fw = identity?.firmware
            val found = PrinterDiscovery.classifyMoonraker(identity?.hostname.orEmpty(), fw?.app.orEmpty(), fw?.version.orEmpty(), "", identity?.afc == true, identity?.paxxExtendedConfig == true)
            val family = when {
                ElegooProfiles.isCosmos(found.slicingModel) -> COSMOS
                found.kind == PrinterKind.SNAPMAKER_U1_PAXX -> PAXX
                found.kind == PrinterKind.SNAPMAKER_U1 -> SNAPMAKER_STOCK
                else -> KLIPPER
            }
            val hardware = mapOf("canvas" to (family == COSMOS && identity?.afc == true),
                "multi_tool" to (found.kind == PrinterKind.SNAPMAKER_U1 || found.kind == PrinterKind.SNAPMAKER_U1_PAXX || description.toolSlots > 1),
                "extended_firmware" to (identity?.paxxExtendedConfig == true))
            Classified(family, found.kind, found.slicingModel, hardware, found.detail)
        }
        PrinterTransport.ELEGOO -> {
            val declared = description.declaredFirmwareFamily?.takeIf { it == OPENCENTAURI_PATCHED } ?: ELEGOO_STOCK
            // The printer can't report CANVAS over its LAN protocol; the saved slicing profile says whether it has one.
            val canvas = description.slicingModel?.name?.contains("CANVAS") == true
            Classified(declared, PrinterKind.ELEGOO, description.slicingModel, mapOf("canvas" to canvas, "multi_tool" to (description.toolSlots > 1)), "Elegoo LAN printer (firmware family as declared by the operator)")
        }
        PrinterTransport.BAMBU_MQTT -> Classified(BAMBU, PrinterKind.BAMBU_LAB, description.slicingModel, emptyMap(), "Bambu Lab LAN mode")
        // Multi-material (Prusa XL 5T, an MMU, a toolchanger behind OctoPrint) comes from the saved profile's slots.
        PrinterTransport.PRUSA_LINK -> Classified(PRUSALINK, PrinterKind.PRUSA_LINK, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "PrusaLink")
        PrinterTransport.OCTOPRINT -> Classified(OCTOPRINT, PrinterKind.OCTOPRINT, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "OctoPrint")
        // A CFS / IFS is one nozzle fed from several slots; its slots come from the saved profile's pack (FilamentChangers).
        PrinterTransport.CREALITY -> Classified(CREALITY, PrinterKind.CREALITY, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "Creality LAN (port 9999)")
        // Upstream's split: a profile without serial + access code is the legacy port-8899 console, graded as its own family.
        PrinterTransport.FLASHFORGE -> if (description.protocol == LEGACY_FLASHFORGE_PROTOCOL)
            Classified(FLASHFORGE_LEGACY, PrinterKind.FLASHFORGE, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "Flashforge legacy console (port 8899)")
        else Classified(FLASHFORGE, PrinterKind.FLASHFORGE, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "Flashforge local API (port 8898)")
        // One family for standalone RepRapFirmware and Duet Software Framework: the app chooses between them per request, as upstream does.
        PrinterTransport.DUET -> Classified(DUET, PrinterKind.DUET, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "Duet / RepRapFirmware (rr_* or DSF REST)")
        PrinterTransport.ULTIMAKER -> Classified(ULTIMAKER, PrinterKind.ULTIMAKER, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "UltiMaker LAN API (/api/v1, /cluster-api/v1)")
        // The server's firmware behind it isn't visible over upstream's API; one family for Repetier-Server.
        PrinterTransport.REPETIER -> Classified(REPETIER, PrinterKind.REPETIER, description.slicingModel, mapOf("multi_tool" to (description.toolSlots > 1)), "Repetier-Server")
    }
}

/** A target as inspected before a run: what it is, what it can do, and anything that disqualifies it. */
data class TargetSnapshot(
    val description: TargetDescription,
    val identity: LiveIdentity?,
    val classified: FirmwareFamilies.Classified,
    val capabilities: Set<String>,
    val status: StatusReading?,
    val problems: List<String>,
)

object TargetCheck {
    /** Read-only. Reads identity (where the protocol has one) and status, then classifies. */
    fun inspect(target: TestTarget): TargetSnapshot {
        val problems = mutableListOf<String>()
        val moonraker = capabilitiesFor(target.description.printerKind).transport == PrinterTransport.MOONRAKER
        val identity = if (moonraker) try { target.identity() } catch (e: Exception) { problems += "Could not read the printer's firmware identity: ${e.message}"; null } else null
        val status = try { target.status() } catch (e: Exception) { problems += "Could not read the printer's status: ${e.message}"; null }
        val classified = FirmwareFamilies.classify(target.description, identity)
        if (moonraker && identity != null && classified.kind != target.description.printerKind &&
            !(classified.kind == PrinterKind.GENERIC_KLIPPER && target.description.printerKind == PrinterKind.GENERIC_KLIPPER))
            problems += "This printer is saved as ${target.description.printerKind.name} but reads as ${classified.kind.name} (${classified.detail}). Fix the saved printer type first."
        val caps = try { target.declaredCapabilities() } catch (e: Exception) { CapabilityNames.declared(target.description.printerKind, classified.hardware) }
        return TargetSnapshot(target.description, identity, classified, caps + CapabilityNames.declared(classified.kind, classified.hardware).intersect(setOf(CapabilityNames.MATERIAL_STATE, CapabilityNames.MULTI_MATERIAL)), status, problems)
    }

    /** Why [suite] must not run against [snapshot]; empty when it may. Never infers one firmware family from another. */
    fun mismatches(suite: Suite, snapshot: TargetSnapshot, appVersion: String): List<String> {
        val out = snapshot.problems.toMutableList()
        when (Versions.atLeast(appVersion, suite.requiredNozzleVersion)) {
            null -> out += "Nozzle version \"$appVersion\" can't be compared with the suite's required ${suite.requiredNozzleVersion}."
            false -> out += "This suite needs Nozzle It All ${suite.requiredNozzleVersion} or newer (this is $appVersion)."
            true -> {}
        }
        val family = snapshot.classified.family
        if (family != suite.target.firmwareFamily) {
            out += "This suite is for ${suite.target.manufacturer} ${suite.target.model} on \"${suite.target.firmwareFamily}\" firmware; this printer reads as \"$family\". " +
                "Firmware families are graded separately; a result on one is never evidence for another."
        }
        if (suite.target.printerKinds.isNotEmpty() && snapshot.description.printerKind.name !in suite.target.printerKinds)
            out += "This suite expects a printer saved as ${suite.target.printerKinds.joinToString(" or ")}; this one is ${snapshot.description.printerKind.name}."
        val missing = suite.requiredCapabilities.filter { it !in snapshot.capabilities }
        if (missing.isNotEmpty()) out += "The printer does not declare required capabilities: ${missing.joinToString()}."
        return out
    }
}
