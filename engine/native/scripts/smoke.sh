#!/usr/bin/env bash
# Slices the 20 mm test cube with shared printer profiles using the native engine and the exact request the browser
# smoke test sends (engine/wasm/scripts/smoke_node.mjs). Usage: smoke.sh [engine binary] [profile id ...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ENGINE="${1:-/mnt/faststorage/build-work/nozzle-native/dist/nozzle-engine}"; shift || true
PROFILES=("$@"); [ ${#PROFILES[@]} -gt 0 ] || PROFILES=(snapmaker_u1 prusa_generic bambu_generic generic_klipper prusa_xl_5t)
"$ENGINE" --version
JOB="$(mktemp -d)"; trap 'rm -rf "$JOB"' EXIT
for id in "${PROFILES[@]}"; do
  d="$ROOT/app/src/main/assets/slicer_profiles/$id"
  printf 'out\t%s\nprofile\t%s\nprofile\t%s\nprofile\t%s\nset\tlayer_height\t0.2\nset\tsparse_infill_density\t15%%\nobject\t%s\t0\t0\t0\t1\t0\n' \
    "$JOB/$id.gcode" "$d/machine.json" "$d/process.json" "$d/filament.json" "$ROOT/site-src/assets/test-cube-20mm.stl" > "$JOB/$id.txt"
  t0=$(date +%s.%N)
  if "$ENGINE" "$JOB/$id.txt" > "$JOB/$id.progress" 2> "$JOB/$id.err"; then
    t1=$(date +%s.%N)
    g="$JOB/$id.gcode"
    printf '%-16s layers %-4s grams %-6s print time %-12s wall %.1f s  progress lines %s\n' "$id" \
      "$(grep -m1 '; total layer number:' "$g" | awk -F': ' '{print $2}')" \
      "$(grep -m1 'total filament used \[g\] =' "$g" | awk -F'= ' '{print $2}')" \
      "$(grep -m1 'estimated printing time (normal mode) =' "$g" | awk -F'= ' '{print $2}')" \
      "$(echo "$t1 - $t0" | bc)" "$(wc -l < "$JOB/$id.progress")"
  else
    echo "$id FAILED (exit $?): $(tail -1 "$JOB/$id.err")"
  fi
done
