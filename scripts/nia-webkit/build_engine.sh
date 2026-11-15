#!/usr/bin/env bash
# Builds the browser engine that Nozzle It All Desktop's AppImage carries: the distribution's own WebKitGTK
# source package (GTK 3, API 4.1) with WebRTC switched on and the patches in patches/ applied.
#
# Run as root inside the same builder image the application is built in, so that the engine and the
# application agree on every library the AppImage bundles:
#   build_engine.sh <work-dir> [jobs]
# The engine is installed below <work-dir>/out/opt/nia-webkit; hand that directory to the AppImage build as
# NIA_WEBKIT_ENGINE_DIR. engine-record.txt in it names the source package version and the patches.
set -euo pipefail

WORK="$(cd -- "$1" && pwd)"
JOBS="${2:-$(nproc)}"
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PREFIX=/opt/nia-webkit

export DEBIAN_FRONTEND=noninteractive
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
apt-get update -qq
apt-get build-dep -y -qq webkit2gtk > "$WORK/apt.log" 2>&1
apt-get install -y -qq dpkg-dev libgstreamer-plugins-bad1.0-dev libssl-dev ninja-build >> "$WORK/apt.log" 2>&1

mkdir -p "$WORK/src" && cd "$WORK/src"
if ! ls -d webkit2gtk-*/ >/dev/null 2>&1; then
    apt-get source -qq webkit2gtk > "$WORK/source.log" 2>&1
    SRC="$(ls -d webkit2gtk-*/ | head -n1)"
    for patch in "$HERE"/patches/*.patch; do
        patch -d "$SRC" -p1 --no-backup-if-mismatch < "$patch"
    done
fi
SRC="$(cd -- "$(ls -d webkit2gtk-*/ | head -n1)" && pwd)"

COMPILER=()
if command -v clang++ >/dev/null 2>&1; then
    # The distribution builds its own package with clang, which the engine's drawing library prefers.
    COMPILER=(-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++)
fi

cmake -S "$SRC" -B "$WORK/build" -GNinja -DPORT=GTK -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    "${COMPILER[@]}" \
    -DUSE_GTK4=OFF -DENABLE_DOCUMENTATION=OFF -DENABLE_INTROSPECTION=OFF -DENABLE_WEBDRIVER=OFF -DENABLE_MINIBROWSER=OFF \
    -DUSE_LIBBACKTRACE=OFF -DUSE_SYSPROF_CAPTURE=OFF -DENABLE_JOURNALD_LOG=OFF -DENABLE_BUBBLEWRAP_SANDBOX=OFF \
    -DENABLE_SPEECH_SYNTHESIS=OFF \
    -DENABLE_WEB_RTC=ON -DENABLE_MEDIA_STREAM=ON -DUSE_GSTREAMER_WEBRTC=ON -DUSE_LIBRICE=OFF > "$WORK/cmake.log" 2>&1
ninja -C "$WORK/build" -j"$JOBS" > "$WORK/ninja.log" 2>&1
rm -rf "$WORK/out"
DESTDIR="$WORK/out" ninja -C "$WORK/build" install > "$WORK/install.log" 2>&1

{
    echo "source package: $(dpkg-parsechangelog -l "$SRC/debian/changelog" -S Source) $(dpkg-parsechangelog -l "$SRC/debian/changelog" -S Version)"
    echo "base: $(. /etc/os-release && echo "$PRETTY_NAME")"
    echo "compiler: $(grep -m1 'CMAKE_CXX_COMPILER:' "$WORK/build/CMakeCache.txt")"
    (cd "$HERE/patches" && sha256sum *.patch)
    (cd "$WORK/out$PREFIX" && sha256sum lib/libwebkit2gtk-4.1.so.0 lib/libjavascriptcoregtk-4.1.so.0)
} > "$WORK/out$PREFIX/engine-record.txt"
echo ENGINE-BUILD-OK
