# Slicer profile provenance (WO-13)

Every `machine.json`/`process.json`/`filament.json` here is a **flattened** OrcaSlicer
profile: resolved offline from the vendored `orcaslicer-android-engine` checkout's
`resources/profiles/` (upstream OrcaSlicer, AGPL-3.0-or-later) by walking each
profile's `"inherits"` chain and merging child-over-parent, the same resolution
OrcaSlicer's own GUI (`PresetBundle`) does at runtime. Flattened rather than left as
`inherits` chains because this app's JNI bridge loads profiles via a sequence of
plain `ConfigBase::load()` calls with no inherits resolution of its own (see
`app/src/main/cpp/bridge/slic3r_jni.cpp`). See `docs/WORK_ORDER.md`'s WO-13 entry.

| Pack | Machine (bundled OrcaSlicer name) | Process | Filament |
|---|---|---|---|
| `snapmaker_u1` | Snapmaker U1 (0.4 nozzle) | 0.20 Standard @Snapmaker U1 (0.4 nozzle) | Snapmaker PLA @U1 |
| `bambu_generic` | Bambu Lab A1 0.4 nozzle | 0.20mm Standard @BBL A1 | Bambu PLA Basic @BBL A1 |
| `prusa_generic` | Prusa MK4 0.4 nozzle | 0.20mm Standard @MK4 | Generic PLA @Prusa MK4 |
| `generic_klipper` | MyKlipper 0.4 nozzle (OrcaSlicer's own generic-Klipper template, vendor "Custom") | 0.20mm Standard @MyKlipper | Generic PLA @System |
| `elegoo_centauri_carbon_cosmos` | Elegoo Centauri Carbon 0.4 nozzle **+ the COSMOS G-code overlay below** | 0.20mm Standard @Elegoo CC 0.4 nozzle | Elegoo PLA @ECC |

## The COSMOS overlay (safety-critical - see FirmwareIdentity.kt)

`elegoo_centauri_carbon_cosmos/machine.json` is not just OrcaSlicer's bundled
"Elegoo Centauri Carbon 0.4 nozzle" preset (that preset targets Elegoo's own stock
firmware, `host_type: elegoolink` - wrong for a COSMOS-flashed printer entirely). It
has the **OpenCentauri COSMOS OrcaSlicer profile** merged on top: `machine_start_gcode`/
`machine_end_gcode`/`machine_pause_gcode`/`change_filament_gcode` overridden to call
COSMOS's own macros (`PRINT_START`/`PRINT_END`/`PAUSE`/`M600`), `host_type`/
`printer_agent` set to `moonraker`.

Source: [OpenCentauri's own install docs](https://docs.opencentauri.cc/klipper-conversion/cosmos/install/)
link this exact bundle as the required profile: "Elegoo Centauri Carbon 0.4 nozzle -
Cosmos" from `https://cloud.orcaslicer.com/b/3fad3c38f25f`, authored by OrcaSlicer
Cloud user `mudkip` (518 subscribers at the time this was pulled, 2026-09-22),
`base_id: LyL8izVzYFaXHlPb`, bundle version `0.07`, "Compatible OrcaSlicer version:
2.3.2 and later". OpenCentauri's own docs page links to this bundle directly (and the
bundle's own description links back to `github.com/OpenCentauri/cosmos`), which is
why it's trusted as authoritative here rather than an arbitrary community profile.

**This is the exact fix for the real risk this whole data model exists for**: per
OpenCentauri's docs, printing against COSMOS 26.07.0+ with the *wrong* start/end
G-code (the old `M729`/`M8213`-based sequence) triggers a hard emergency stop
mid-print. This overlay is that fix, transcribed from the live rendered page via the
browser's accessibility tree (Monaco editor, not a plain download link) on
2026-09-22 and verified structurally sound by resolving its full inherits chain
(86 total keys, correct `gcode_flavor: klipper`, correct 256x256 `printable_area`
matching the real device).

**Real, flagged gap**: only the *current* (26.07.0+) COSMOS profile generation is
bundled - see `CosmosProfileGeneration.LEGACY` in `FirmwareIdentity.kt`. A printer
whose live firmware resolves to `LEGACY` has no matching bundled profile yet;
`checkCentauriCarbonFirmwareMatch` will correctly report a `Mismatch` rather than
silently falling back to the current-generation profile.

## What hasn't been verified

Bambu and Prusa packs are unverified against real hardware - the owner doesn't own
either (matching this project's established "build it, mark it unverified" pattern
elsewhere - see `docs/WORK_ORDER.md`'s WO-9). `generic_klipper` and
`elegoo_centauri_carbon_cosmos`'s underlying vendor data haven't been slice-tested
through this app's own pipeline yet either (only the machine-chain resolution itself
was checked, not an actual slice) - see WO-13's own status for what's confirmed vs.
still open.
