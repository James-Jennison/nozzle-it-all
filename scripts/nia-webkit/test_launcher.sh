#!/usr/bin/env bash
# Checks what the AppImage's launcher hands to the application, without starting the application:
#   test_launcher.sh <appdir>
# Run on a machine (or in a container) that can run the application. Prints one line per check and
# "LAUNCHER-OK", or stops at the first check that does not hold.
set -euo pipefail

APPDIR="$(cd -- "$1" && pwd)"
LAUNCHER="$(ls "$APPDIR"/libexec/*-env | head -n1)"
PROBE='printf "LD=%s\nEXEC=%s\nBUNDLE=%s\nENGINE=%s\n" "$LD_LIBRARY_PATH" "${WEBKIT_EXEC_PATH:-}" "${WEBKIT_INJECTED_BUNDLE_PATH:-}" "${NIA_BROWSER_ENGINE:-}"'

probe() { env "$@" APPDIR="$APPDIR" "$LAUNCHER" /bin/sh -c "$PROBE" 2>/dev/null; }
value() { sed -n "s/^$2=//p" <<<"$1"; }
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; exit 1; fi; }

HAS_ENGINE=0; [ -f "$APPDIR/lib/webkit2gtk-4.1/engine-record.txt" ] && HAS_ENGINE=1
HOST_WEBRTC=0; ldconfig -p | grep -q "libgstwebrtc-1.0.so.0 (" && HOST_WEBRTC=1

# Whatever the launcher is started with, the engine's helper directories are its own or absent.
out="$(probe WEBKIT_EXEC_PATH=/somewhere/else WEBKIT_INJECTED_BUNDLE_PATH=/somewhere/else NIA_BROWSER_ENGINE=bundled)"
if [ "$HAS_ENGINE" = 1 ] && [ "$HOST_WEBRTC" = 1 ]; then
    check "the bundled engine is chosen and its helper programs are the bundle's" \
        '[ "$(value "$out" EXEC)" = "$APPDIR/lib/webkit2gtk-4.1" ] && [ "$(value "$out" BUNDLE)" = "$APPDIR/lib/webkit2gtk-4.1/injected-bundle" ] && [ "$(value "$out" ENGINE)" = bundled ]'
    check "the bundle's lib folder is on the loader path" '[[ ":$(value "$out" LD):" == *":$APPDIR/lib:"* ]]'
else
    check "without the engine, or without the host's WebRTC library, nothing of the engine is set" \
        '[ -z "$(value "$out" EXEC)" ] && [ -z "$(value "$out" BUNDLE)" ] && [ -z "$(value "$out" ENGINE)" ] && [[ ":$(value "$out" LD):" != *":$APPDIR/lib:"* ]]'
fi

out="$(probe NIA_HOST_WEBKIT=1 WEBKIT_EXEC_PATH=/somewhere/else WEBKIT_INJECTED_BUNDLE_PATH=/somewhere/else NIA_BROWSER_ENGINE=bundled)"
check "NIA_HOST_WEBKIT=1: nothing of the engine is set, inherited values included" \
    '[ -z "$(value "$out" EXEC)" ] && [ -z "$(value "$out" BUNDLE)" ] && [ -z "$(value "$out" ENGINE)" ] && [[ ":$(value "$out" LD):" != *":$APPDIR/lib:"* ]]'

# A bundled library is on the path exactly when this machine has none of that name.
out="$(probe)"
path=":$(value "$out" LD):"
for dir in "$APPDIR"/lib/orca-runtime/only/*; do
    name="$(basename "$dir")"
    if ldconfig -p | grep -q "^[[:space:]]$name (libc6,x86-64[),]"; then
        check "$name is this machine's own" '[[ "$path" != *":$dir:"* ]]'
    else
        check "$name is the bundle's" '[[ "$path" == *":$dir:"* ]]'
    fi
done
check "the whole bundled folder is not on the path" '[[ "$path" != *":$APPDIR/lib/orca-runtime:"* ]]'

out="$(probe NIA_BUNDLED_LIBS=1)"
check "NIA_BUNDLED_LIBS=1 puts the whole bundled folder ahead" '[[ ":$(value "$out" LD):" == *":$APPDIR/lib/orca-runtime:"* ]]'
echo LAUNCHER-OK
