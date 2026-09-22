package net.jamesjennison.klippercompanion

// WO-13 Phase 1: on-device slicing must never hand a printer G-code built for the wrong
// firmware's start/end sequence. Concretely verified risk (docs.opencentauri.cc, and live
// against a real Elegoo Centauri Carbon at 192.168.1.114 running "OpenCentauri Cosmos" /
// "Release - 26.08.0"): from COSMOS 26.07.0 onward, printing with the pre-COSMOS or
// OpenCentauri-patched profile's start/end G-code (M729/M8213) triggers a hard emergency stop
// mid-print, not just a bad print.

// What `printer/info` actually reports. Confirmed live: a Snapmaker U1 reports
// app="" (field absent -> read as blank), version="1.6.0.267_20260815150420"; the Centauri
// Carbon above reports app="OpenCentauri Cosmos", version="Release - 26.08.0".
data class FirmwareIdentity(val app: String, val version: String)

// The three incompatible Elegoo Centauri Carbon firmware states (verified against
// docs.opencentauri.cc/klipper-conversion and /patched-firmware): Stock and OpenCentauri-patched
// share one slicer profile (both are Elegoo's own non-Klipper firmware, patched or not); COSMOS
// is a full Klipper/Kalico replacement with its own, different start/end G-code, and is the only
// one of the three actually reachable via Moonraker at all (Stock/patched don't speak it).
enum class CentauriCarbonFirmware { STOCK, OPEN_CENTAURI_PATCHED, COSMOS }

// Stock and OpenCentauri-patched firmware don't run Moonraker/Klipper, so they're never
// detectable this way - a live FirmwareIdentity read only ever confirms COSMOS (or a non-Centauri
// printer). Null means "not detected as COSMOS via this read" - the caller decides what that
// implies for the profile actually in hand (see requiresCosmosProfileRevalidation below).
fun classifyCentauriCarbonFirmware(identity: FirmwareIdentity): CentauriCarbonFirmware? =
    if (identity.app.contains("OpenCentauri Cosmos", ignoreCase = true) || identity.app.contains("COSMOS", ignoreCase = true)) CentauriCarbonFirmware.COSMOS
    else null

// COSMOS's own release versioning is plain dotted-numeric ("26.08.0"), sometimes prefixed
// ("Release - 26.08.0", confirmed live). Parses out the numeric triple; null if unparseable
// rather than guessing, since a wrong guess here is exactly the kind of mistake this whole
// mechanism exists to prevent.
internal fun parseCosmosVersion(raw: String): Triple<Int, Int, Int>? {
    val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(raw) ?: return null
    val (major, minor, patch) = match.destructured
    return Triple(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
}

private val COSMOS_NEW_PROFILE_FLOOR = Triple(26, 7, 0)

// True if this COSMOS version requires the new COSMOS-specific OrcaSlicer profile (the one whose
// start/end G-code COSMOS 26.07.0+ actually expects) rather than the older, pre-26.07.0 one.
// Null (not false) when the version string can't be parsed at all - an unparseable version must
// never be silently treated as "old enough to be safe."
fun cosmosRequiresCurrentProfile(version: String): Boolean? {
    val parsed = parseCosmosVersion(version) ?: return null
    return compareValuesBy(parsed, COSMOS_NEW_PROFILE_FLOOR, { it.first }, { it.second }, { it.third }) >= 0
}

// The one thing print-generation actually needs to decide: does the slicer profile revision the
// caller has in hand (identified by `profileCosmosGeneration` - "current" for the post-26.07.0
// COSMOS profile, "legacy" for the pre-26.07.0 one, or null for a profile that isn't
// COSMOS-specific at all) match what this printer's *live, just-read* firmware actually needs.
// Deliberately takes a live reading, not a cached PrinterProfile field: firmware upgrades happen
// on the printer, out of band from this app, and the whole point is to never trust a stale
// association for something that can hard-fault the printer.
sealed class FirmwareMatchResult {
    object Match : FirmwareMatchResult()
    data class Mismatch(val reason: String) : FirmwareMatchResult()
    // The live printer couldn't be classified as any known Centauri Carbon firmware state (e.g.
    // it isn't a Centauri Carbon at all, or the read failed) - not itself an error, but callers
    // slicing specifically for a Centauri Carbon profile must treat this as "can't confirm safe."
    data class Unknown(val reason: String) : FirmwareMatchResult()
}

fun checkCentauriCarbonFirmwareMatch(live: FirmwareIdentity?, profileCosmosGeneration: CosmosProfileGeneration?): FirmwareMatchResult {
    if (profileCosmosGeneration == null) return FirmwareMatchResult.Match // profile isn't Centauri-Carbon-specific; nothing to check
    if (live == null) return FirmwareMatchResult.Unknown("Could not read the printer's current firmware before slicing for a Centauri Carbon/COSMOS profile.")
    val firmware = classifyCentauriCarbonFirmware(live)
        ?: return FirmwareMatchResult.Unknown("This printer's firmware (\"${live.app.ifBlank { "unknown" }}\") could not be confirmed as COSMOS. A Centauri Carbon/COSMOS slicer profile must not be used against a printer that isn't verified to run COSMOS.")
    if (firmware != CentauriCarbonFirmware.COSMOS) return FirmwareMatchResult.Unknown("This printer's firmware could not be confirmed as COSMOS.")
    val requiresCurrent = cosmosRequiresCurrentProfile(live.version)
        ?: return FirmwareMatchResult.Unknown("This printer's COSMOS version (\"${live.version}\") could not be parsed to confirm which slicer profile it needs.")
    val actual = if (requiresCurrent) CosmosProfileGeneration.CURRENT else CosmosProfileGeneration.LEGACY
    return if (actual == profileCosmosGeneration) FirmwareMatchResult.Match
    else FirmwareMatchResult.Mismatch(
        "This printer is running COSMOS ${live.version}, which needs the " +
            "${if (requiresCurrent) "current (26.07.0+)" else "legacy (pre-26.07.0)"} COSMOS slicer profile, " +
            "but the profile selected is the ${profileCosmosGeneration.name.lowercase()} one. " +
            "Printing with the wrong one can trigger a hard emergency stop mid-print on real COSMOS hardware."
    )
}

// Which COSMOS profile generation a bundled slicer profile targets - see WO-13's profile-pack
// asset naming (docs/WORK_ORDER.md).
enum class CosmosProfileGeneration { CURRENT, LEGACY }
