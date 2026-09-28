#!/usr/bin/env bash
# CI form of prepare_engine_root.sh: the Snapmaker root's dependency prefix is prepared by its own CI script (which
# fetches what it needs into the cache), then the nozzle-engine commit is fetched into the cache and exported.
# Usage: ci_engine_root.sh CACHE ANDROID_DEPS_PREFIX   -> prints the engine root on its last line
set -euo pipefail
NOZZLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CACHE="$1"; DEPS="$2"
SM_ROOT="$(bash "$NOZZLE_ROOT/engine/snapmaker/android/ci_engine_root.sh" "$CACHE" "$DEPS" | tail -1)"
NOZZLE_ENGINE_CHECKOUT=/nonexistent NOZZLE_ENGINE_CACHE="$CACHE/nozzle-engine-src" NOZZLE_ANDROID_SM_ROOT="$SM_ROOT" \
  NOZZLE_ANDROID_FORK_ROOT="$CACHE/android-root-fork" bash "$NOZZLE_ROOT/engine/fork/android/prepare_engine_root.sh" | tail -1
