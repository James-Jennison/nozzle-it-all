#!/usr/bin/env bash
# Prepares the Android engine root for the nozzle-engine base (P-0020), in the layout app/src/main/cpp/CMakeLists.txt
# expects of any ORCASLICER_ENGINE_ROOT:
#   $ROOT/orcaslicer               export of the pinned nozzle-engine commit (engine/fork/ENGINE_PIN.json); it already
#                                  contains every patch the Snapmaker base applies and its own JNI bridge (nozzle/bridge)
#   $ROOT/deps/install/arm64-v8a   the same dependency prefix as the Snapmaker base (GMP with C++ classes), made by
#                                  engine/snapmaker/android/prepare_engine_root.sh and linked here, read only
# Overridable: NOZZLE_ANDROID_FORK_ROOT, NOZZLE_ANDROID_SM_ROOT (where the Snapmaker root with the prefix is; it is prepared
# first when missing, with that script's own overrides). Then:  heavy-gradle -PnozzleEngine=fork :app:assembleDebug
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ROOT="${NOZZLE_ANDROID_FORK_ROOT:-/mnt/faststorage/build-work/nozzle-android-fork}"
SM_ROOT="${NOZZLE_ANDROID_SM_ROOT:-/mnt/faststorage/build-work/nozzle-android-sm}"
[ -f "$SM_ROOT/deps/install/arm64-v8a/include/gmpxx.h" ] || NOZZLE_ANDROID_SM_ROOT="$SM_ROOT" bash "$NOZZLE_ROOT/engine/snapmaker/android/prepare_engine_root.sh" >&2
mkdir -p "$ROOT"
"$NOZZLE_ROOT/engine/fork/export_source.sh" "$ROOT/orcaslicer"
[ -e "$ROOT/deps" ] || ln -s "$SM_ROOT/deps" "$ROOT/deps"
echo "engine root ready: $ROOT (nozzle-engine $(cat "$ROOT/orcaslicer/.nozzle-engine-commit"); deps: $SM_ROOT/deps)" >&2
echo "$ROOT"
