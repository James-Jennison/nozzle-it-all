#!/usr/bin/env bash
# Exports the pinned nozzle-engine commit (engine/fork/ENGINE_PIN.json) into DEST, without the upstream GUI's large
# resource folders, the same way the other engine bases are exported. The commit comes from the local checkout when it
# has it (NOZZLE_ENGINE_CHECKOUT, default the pin's local_checkout), otherwise it is fetched from the pinned repository
# into a cache (NOZZLE_ENGINE_CACHE). Usage: export_source.sh DEST
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIN="$NOZZLE_ROOT/engine/fork/ENGINE_PIN.json"
pin() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(eval("d"+sys.argv[2]))' "$PIN" "$1"; }
COMMIT="$(pin '["base"]["commit"]')"; REPO="$(pin '["base"]["repo"]')"; DEST="$1"
CHECKOUT="${NOZZLE_ENGINE_CHECKOUT:-$(pin '["base"]["local_checkout"]')}"
if ! git -C "$CHECKOUT" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
  CHECKOUT="${NOZZLE_ENGINE_CACHE:-/mnt/faststorage/build-work/nozzle-engine-src}"
  [ -d "$CHECKOUT/.git" ] || git init -q "$CHECKOUT"
  git -C "$CHECKOUT" cat-file -e "$COMMIT^{commit}" 2>/dev/null || git -C "$CHECKOUT" fetch -q --depth 1 "$REPO" "$COMMIT"
fi
if [ "$(cat "$DEST/.nozzle-engine-commit" 2>/dev/null)" != "$COMMIT" ]; then
  rm -rf "$DEST"; mkdir -p "$DEST"
  git -C "$CHECKOUT" archive --format=tar "$COMMIT" -- . ':!resources/profiles' ':!resources/web' ':!resources/images' \
    ':!resources/calib' ':!resources/handy_models' | tar -x -C "$DEST"
  echo "$COMMIT" > "$DEST/.nozzle-engine-commit"
fi
echo "$DEST (nozzle-engine $COMMIT)" >&2
