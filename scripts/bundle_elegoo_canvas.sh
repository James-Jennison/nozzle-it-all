#!/usr/bin/env bash
# Elegoo's own stock-firmware Centauri Carbon and Centauri Carbon 2 profiles (CANVAS multi-colour), from ElegooSlicer.
#
#   NOZZLE_ENGINE=<nozzle-engine> scripts/bundle_elegoo_canvas.sh [<ElegooSlicer checkout>]     (default /mnt/faststorage/ElegooSlicer)
#
# NOZZLE_ENGINE: a desktop nozzle-engine at (or after) the pin, which decides each preset's compatibility (--compatible-presets).
# Elegoo ships no Color Mixing preset and Nozzle It All adds none: no color mixing on CANVAS (docs/upstream/PROVENANCE.md P-0044).
#
# Writes the 0.4 mm packs (app/src/main/assets/slicer_profiles/elegoo_centauri_carbon{,_2}_canvas: every platform) and
# the whole families (engine/profiles/library/...: nozzle sizes, process presets, Elegoo filament presets; Desktop).
# The index.json entries (tools 4 = CANVAS's four slots) are kept by hand, like the COSMOS AFC pack's.
#
# --pin adds a setting only where ElegooSlicer's profile leaves it unset and ElegooSlicer's own default differs from
# the Snapmaker Orca engine's, so a slice matches ElegooSlicer (see docs/upstream/PROVENANCE.md, Elegoo CANVAS entry).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ES="${1:-/mnt/faststorage/ElegooSlicer}"
PROFILES="$ES/resources/profiles"
COMMIT="$(git -C "$ES" rev-parse --short=10 HEAD)"
export PYTHONDONTWRITEBYTECODE=1
PINS=(--pin 'flush_multiplier="1"'                    # ElegooSlicer 1, Snapmaker Orca 0.3 (purge length at every change)
      --pin 'independent_support_layer_height="1"'    # ElegooSlicer 1, Snapmaker Orca 0
      --pin 'slowdown_for_curled_perimeters=["0"]'    # ElegooSlicer 0, Snapmaker Orca 1
      --pin 'curr_bed_type="Textured PEI Plate"')     # the machines' default_bed_type 4, which ElegooSlicer's GUI selects
SRC="Elegoo profiles from ElegooSlicer (github.com/ELEGOO-3D/ElegooSlicer $COMMIT, AGPL-3.0); see docs/upstream/PROVENANCE.md (Elegoo stock-firmware CANVAS profiles)"

family() { # <library id> <machine name prefix>
  python3 "$ROOT/scripts/bundle_printer_library.py" --profiles "$PROFILES" --vendor Elegoo --nested "${PINS[@]}" --source "$SRC" \
    --engine "${NOZZLE_ENGINE:?set NOZZLE_ENGINE to a nozzle-engine}" \
    --machine "$2 0.2 nozzle" --machine "$2 0.4 nozzle" --machine "$2 0.6 nozzle" --machine "$2 0.8 nozzle" \
    --out "$ROOT/engine/profiles/library/$1"
}
pack() { # <pack id> <machine> <process> <filament>
  python3 "$ROOT/scripts/flatten_orca_profile.py" --profiles "$PROFILES" --vendor Elegoo --nested "${PINS[@]}" \
    --machine "$2" --process "$3" --filament "$4" --out "$ROOT/app/src/main/assets/slicer_profiles/$1"
}

family elegoo_centauri_carbon_canvas "Elegoo Centauri Carbon"
family elegoo_centauri_carbon_2_canvas "Elegoo Centauri Carbon 2"
pack elegoo_centauri_carbon_canvas "Elegoo Centauri Carbon 0.4 nozzle" "0.20mm Standard @Elegoo CC 0.4 nozzle" "Elegoo PLA @ECC"
pack elegoo_centauri_carbon_2_canvas "Elegoo Centauri Carbon 2 0.4 nozzle" "0.20mm Standard @Elegoo CC2 0.4 nozzle" "Elegoo PLA @ECC2"
