# The browser engine in the Nozzle It All Desktop AppImage

The AppImage carries its own WebKitGTK (GTK 3, API 4.1) in place of the host's. The reason is WebRTC:
distributions build WebKitGTK without it, and a printer page that plays its camera by WebRTC (a Snapmaker
U1's) then shows no picture. A build made without `NIA_WEBKIT_ENGINE_DIR` carries no engine and uses the
host's, as OrcaSlicer does.

## What is built

`build_engine.sh` takes the WebKitGTK source package of the distribution the application's builder image is
based on, switches WebRTC on, keeps the image formats the distribution's own package leaves out (AVIF,
JPEG XL) out, and applies the patches in `patches/`:

| Patch | Why |
|---|---|
| `0001` | H.264 constrained baseline as camera-streamer offers it (`42e01f`) is listed first and so gets payload type 96, the one such an offer uses. |
| `0002` | GStreamer's `webrtcbin` does not implement the default "balanced" bundle policy and bundles nothing; a peer that bundles everything on one transport is then not received. Balanced is treated as max-bundle. |
| `0003` | A track whose offer names no stream (`a=msid`) gets the default stream other browsers give it, so a page that plays `event.streams[0]` has one. |
| `0004` | `WEBKIT_EXEC_PATH` names the directory of the helper programs in every build, not only a developer one. An AppImage is mounted at a different place on every run, so the path compiled into the library cannot be right. |

The engine is built in the application's builder image so that both agree on the libraries the AppImage
bundles. GTK, GStreamer and GStreamer's plugins are the host's at run time, as for the rest of the application.

## How it gets into the AppImage

`src/dev-utils/platform/unix/build_linux_image.sh.in` copies the two libraries to `lib/`, the helper programs
to `lib/webkit2gtk-4.1/` and the injected bundle below it when `NIA_WEBKIT_ENGINE_DIR` names the installed
engine. The launcher it writes puts `lib/` ahead of the host's libraries and sets `WEBKIT_EXEC_PATH` and
`WEBKIT_INJECTED_BUNDLE_PATH`. The engine needs the host's GStreamer WebRTC library
(`libgstwebrtc-1.0.so.0`); on a host without it, or with `NIA_HOST_WEBKIT=1` set, the launcher leaves the
bundled engine alone and the host's is used. `lib/webkit2gtk-4.1/engine-record.txt` names the source package version, the
patches and the libraries' hashes.

For a WebRTC camera to play, the host also needs GStreamer's WebRTC plugins: on Ubuntu and Debian the
packages `gstreamer1.0-plugins-bad` and `gstreamer1.0-nice`. Without them the engine still runs and the
page's WebRTC camera shows its error, as with the host's engine.

## Keeping it current

The engine is a web browser's and gets security fixes often. At each upstream merge into this line:

1. Build the engine again; the builder image's distribution supplies its current WebKitGTK source package.
2. If a patch no longer applies, see whether upstream fixed the matter (then drop the patch) or moved the
   code (then refresh it). Take only what the application needs; do not carry a patch whose reason is gone.
3. Run the printer page and camera scenarios with the new engine before it goes into a candidate.

## Licence

WebKitGTK is under the LGPL 2.1 or later and BSD licences. The source is the distribution's package named in
`engine-record.txt`; the changes to it are the patches in this directory.

## Bundled libraries and the host's plugins

The engine runs on the host's GStreamer, and the host's plugins load in the application's processes. The
launcher therefore puts a bundled library (`lib/orca-runtime`) on the loader path only where the host has none
of that name. With the bundle's older `libcrypto.so.3` ahead of the host's, the host's `libssl` does not load,
GStreamer's DTLS plugin is refused, and a WebRTC camera stays at "connecting" (seen on Ubuntu 26.04 with a
bundle built on 24.04). `NIA_BUNDLED_LIBS=1` puts every bundled library ahead, as before.

A host library is taken by its name alone: its version is not compared. Before the application starts the
launcher asks the loader (`ldd`) whether everything resolves that way, and if not puts the bundle's libraries
ahead as before, saying so. `NIA_LAUNCH_VERBOSE=1` prints, for each bundled library, whose copy is used.

## What keeps the engine honest

- **The source is pinned.** `build_engine.sh` names the source package version and stops when the distribution
  offers another; the record lists the source files' hashes.
- **The record travels.** Packaging refuses an engine without `engine-record.txt`, or whose libraries are not
  the ones the record names, and adds the hashes of the stripped copies it ships.
- **An old engine stops the build.** Packaging compares the engine's source package version with the
  distribution's own `libwebkit2gtk-4.1-0` on the build machine and stops when the engine is older
  (`NIA_WEBKIT_ENGINE_ALLOW_OLD=1` lets a build through knowingly). Refreshing the builder image is what makes
  this bite; do both at each upstream merge.
- **Nothing inherited.** The launcher clears `WEBKIT_EXEC_PATH` and `WEBKIT_INJECTED_BUNDLE_PATH` and sets them
  itself only for the bundled engine. Patch 0004 makes the engine read the first outside developer mode; who can
  set the application's environment can already load code into it (`LD_PRELOAD`), so this opens nothing new.
- **No process sandbox.** The engine is built without bubblewrap. wxWidgets does not switch WebKitGTK's sandbox
  on, so the application ran web content unsandboxed with the host's engine too; with this engine it cannot be
  switched on. A printer's page is content from the printer on the local network.
- **The patches are global to the engine**, and matter only where WebRTC is on: the printer page's view
  (and OrcaSlicer's own direct-LAN page, which already asked for it).
