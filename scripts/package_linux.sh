#!/usr/bin/env bash
# Builds the Linux packages into dist/linux with provenance and SHA-256 sums:
#   nozzle-it-all                   Desktop, the PAXX U1 / Klipper / OctoPrint / PrusaLink / Bambu adapters and the
#                                   shared slicing engine (engine/native)
#   nozzle-stock-u1-adapter         the optional Stock U1 adapter, installed separately with its own minimal Java
#                                   runtime; Desktop finds it at /opt/nozzle-stock-u1-adapter
#   nozzle-advanced-workspace       the Orca-derived Advanced Workspace (only when --workspace <install dir> is given)
# Run through heavy-build:  heavy-build -- scripts/package_linux.sh [--workspace DIR] [--engine FILE]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist/linux"
ENGINE="${NOZZLE_ENGINE:-/mnt/faststorage/build-work/nozzle-native/dist/nozzle-engine}"
WORKSPACE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --workspace) WORKSPACE="$2"; shift 2 ;;
    --engine) ENGINE="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[ -x "$ENGINE" ] || { echo "slicing engine not found at $ENGINE (build it with engine/native/scripts/build_engine.sh)" >&2; exit 1; }
mkdir -p "$OUT"
VERSION="$(cd "$ROOT" && ./gradlew -q :desktop:properties --property version 2>/dev/null | awk '/^version:/{print $2}')"
[ -n "$VERSION" ] && [ "$VERSION" != "unspecified" ] || VERSION="0.1.0"

echo "== Desktop ($VERSION)"
(cd "$ROOT" && ./gradlew -q :desktop:packageDeb :stock-u1-adapter:installDist -PnozzleEngine="$ENGINE")
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT

# Desktop integration jpackage doesn't do well: its postinst registers a launcher with xdg-desktop-menu, which silently
# does nothing on current Ubuntu, and names it "nozzle-it-all" with no StartupWMClass, so docks show a generic icon.
# Ship a proper launcher and themed icons in the standard places instead; the app names its window class to match.
PKG="$STAGE/desktop"
dpkg-deb -R "$ROOT"/desktop/build/compose/binaries/main/deb/nozzle-it-all_*_amd64.deb "$PKG"
install -Dm644 "$ROOT/desktop/packaging/linux/nozzle-it-all.desktop" "$PKG/usr/share/applications/nozzle-it-all.desktop"
for size in 16 32 48 64 128 256 512; do
  install -Dm644 "$ROOT/desktop/packaging/icons/nozzle-it-all-$size.png" "$PKG/usr/share/icons/hicolor/${size}x${size}/apps/nozzle-it-all.png"
done
install -Dm644 "$ROOT/desktop/packaging/icons/nozzle-it-all.svg" "$PKG/usr/share/icons/hicolor/scalable/apps/nozzle-it-all.svg"
rm -f "$PKG/opt/nozzle-it-all/lib/nozzle-it-all-nozzle-it-all.desktop"
sed -i '/xdg-desktop-menu/d' "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/prerm"
# ffmpeg plays printer cameras' H.264 streams at full frame rate (MJPEG from camera-streamer tops out near 4 fps).
sed -i 's/^Depends: /Depends: ffmpeg, /' "$PKG/DEBIAN/control"
cat > "$STAGE/refresh" <<'EOF'
if [ "$1" = configure ] || [ "$1" = remove ]; then
  command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
  command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
fi
EOF
for script in postinst postrm; do
  [ -f "$PKG/DEBIAN/$script" ] || printf '#!/bin/sh\nset -e\n' > "$PKG/DEBIAN/$script"
  sed -i '/^exit 0$/d' "$PKG/DEBIAN/$script"; cat "$STAGE/refresh" >> "$PKG/DEBIAN/$script"; echo "exit 0" >> "$PKG/DEBIAN/$script"
  chmod 755 "$PKG/DEBIAN/$script"
done
fakeroot dpkg-deb --build "$PKG" "$OUT/$(basename "$ROOT"/desktop/build/compose/binaries/main/deb/nozzle-it-all_*_amd64.deb)" >/dev/null

echo "== Stock U1 adapter"
PKG="$STAGE/stock"
mkdir -p "$PKG/DEBIAN" "$PKG/opt/nozzle-stock-u1-adapter"
cp -r "$ROOT/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib" "$PKG/opt/nozzle-stock-u1-adapter/"
mkdir -p "$PKG/opt/nozzle-stock-u1-adapter/bin"
# Desktop's runtime (jpackage) has no java launcher, so the helper carries its own minimal runtime: the modules jdeps
# finds, plus elliptic-curve TLS for Snapmaker's HTTPS services.
MODULES="$(jdeps --multi-release 17 --ignore-missing-deps --print-module-deps --class-path "$ROOT/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib/*" \
  "$ROOT"/stock-u1-adapter/build/install/nozzle-stock-u1-adapter/lib/stock-u1-adapter*.jar),jdk.crypto.ec"
jlink --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress=2 --output "$PKG/opt/nozzle-stock-u1-adapter/runtime"
cat > "$PKG/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter" <<'EOF'
#!/bin/sh
exec /opt/nozzle-stock-u1-adapter/runtime/bin/java -cp "/opt/nozzle-stock-u1-adapter/lib/*" com.nozzleitall.stocku1.StockU1HelperKt "$@"
EOF
chmod 755 "$PKG/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"
cat > "$PKG/DEBIAN/control" <<EOF
Package: nozzle-stock-u1-adapter
Version: $VERSION-1
Architecture: amd64
Maintainer: support@nozzleitall.com
Enhances: nozzle-it-all
Section: graphics
Priority: optional
Description: Optional Snapmaker U1 stock-firmware support for Nozzle It All
 Lets Nozzle It All for Desktop work with a Snapmaker U1 running stock firmware. It runs as a separate
 process, only when switched on in Settings, and is the only part of Nozzle It All that may contact
 Snapmaker's online services. Not needed for PAXX firmware or any other printer.
EOF
fakeroot dpkg-deb --build "$PKG" "$OUT/nozzle-stock-u1-adapter_${VERSION}-1_amd64.deb" >/dev/null

if [ -n "$WORKSPACE" ]; then
  echo "== Advanced Workspace"
  PKG="$STAGE/workspace"
  mkdir -p "$PKG/DEBIAN" "$PKG/opt/nozzle-advanced-workspace"
  cp -a "$WORKSPACE"/. "$PKG/opt/nozzle-advanced-workspace/"
  # Its own launcher (hidden from menus; Desktop opens it) so docks match the window class and show its icon.
  install -Dm644 "$WORKSPACE/resources/applications/com.nozzleitall.AdvancedWorkspace.desktop" "$PKG/usr/share/applications/com.nozzleitall.AdvancedWorkspace.desktop"
  sed -i -e 's#^Exec=.*#Exec=/opt/nozzle-advanced-workspace/bin/nozzle-advanced-workspace %U#' -e '$a NoDisplay=true' "$PKG/usr/share/applications/com.nozzleitall.AdvancedWorkspace.desktop"
  for size in 16 32 48 64 128 256 512; do
    install -Dm644 "$WORKSPACE/resources/images/nozzle-advanced-workspace_${size}px.png" "$PKG/usr/share/icons/hicolor/${size}x${size}/apps/com.nozzleitall.AdvancedWorkspace.png"
  done
  install -Dm644 "$WORKSPACE/resources/images/nozzle-advanced-workspace.svg" "$PKG/usr/share/icons/hicolor/scalable/apps/com.nozzleitall.AdvancedWorkspace.svg"
  printf '#!/bin/sh\nset -e\n' > "$PKG/DEBIAN/postinst"; cat "$STAGE/refresh" >> "$PKG/DEBIAN/postinst"; echo "exit 0" >> "$PKG/DEBIAN/postinst"
  cp "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/postrm"; chmod 755 "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/postrm"
  WS_BIN="$(cd "$PKG/opt/nozzle-advanced-workspace" && find . -type f -name nozzle-advanced-workspace -perm -u+x | head -1)"
  [ -n "$WS_BIN" ] || { echo "no nozzle-advanced-workspace executable under $WORKSPACE" >&2; exit 1; }
  cat > "$PKG/DEBIAN/control" <<EOF
Package: nozzle-advanced-workspace
Version: $VERSION-1
Architecture: amd64
Maintainer: support@nozzleitall.com
Depends: nozzle-it-all, libgtk-3-0t64 | libgtk-3-0, libwebkit2gtk-4.1-0, libgl1
Section: graphics
Priority: optional
Description: Nozzle It All Advanced Workspace
 The full slicer workspace for Nozzle It All, derived from OrcaSlicer. Opened from Nozzle It All for
 Desktop; keeps its own settings and never changes OrcaSlicer's or Snapmaker Orca's.
EOF
  fakeroot dpkg-deb --build "$PKG" "$OUT/nozzle-advanced-workspace_${VERSION}-1_amd64.deb" >/dev/null
fi

echo "== Provenance"
(cd "$OUT" && sha256sum -- *.deb > SHA256SUMS)
{
  echo "Nozzle It All Linux packages $VERSION"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -srm)"
  echo "source: $(git -C "$ROOT" rev-parse HEAD)$(git -C "$ROOT" diff --quiet && git -C "$ROOT" diff --cached --quiet || echo ' (with uncommitted changes)')"
  echo "java: $(java -version 2>&1 | head -1); gradle: $("$ROOT"/gradlew -q --version 2>/dev/null | awk '/^Gradle/{print $2}')"
  echo "stock adapter runtime modules: $MODULES"
  echo "slicing engine: $ENGINE sha256 $(sha256sum "$ENGINE" | cut -d' ' -f1)"
  [ -f "$ROOT/engine/native/PROVENANCE.txt" ] && sed 's/^/  /' "$ROOT/engine/native/PROVENANCE.txt"
  if [ -n "$WORKSPACE" ]; then
    WS_SRC="${NOZZLE_WORKSPACE_SRC:-/mnt/faststorage/Snapmaker-Orca/nozzle-advanced-workspace}"
    echo "advanced workspace: $WORKSPACE, binary sha256 $(sha256sum "$WORKSPACE/bin/nozzle-advanced-workspace" | cut -d' ' -f1)"
    [ -d "$WS_SRC" ] && echo "  source: $(git -C "$WS_SRC" rev-parse --abbrev-ref HEAD) $(git -C "$WS_SRC" rev-parse HEAD); built with NOZZLE_BRANDING=ON NOZZLE_STOCK_U1=OFF"
  fi
  echo "adapters in nozzle-it-all: $(dpkg-deb -c "$OUT"/nozzle-it-all_*_amd64.deb | grep -o 'adapter-[a-z]*' | sort -u | tr '\n' ' ')"
  echo; cat "$OUT/SHA256SUMS"
} > "$OUT/PROVENANCE.txt"
cat "$OUT/PROVENANCE.txt"
