# Nozzle It All Web: in-browser slicing and browser-to-printer research

Research date: 2026-09-26. Scope: can OrcaSlicer's headless engine run as
WebAssembly in a Web Worker for a local-first PWA (app.nozzleitall.com), and
what can a browser page do when it talks to a Snapmaker U1 (PAXX firmware,
Moonraker) on the LAN or over Tailscale. Research only: nothing was built or
measured locally. Statements marked **(verify)** are unconfirmed and need a
test on real browsers or hardware.

## Verdict

**Feasible, and others have already done it.** At least three independent
projects compile OrcaSlicer's libslic3r to Emscripten, run it in a Worker, and
publish release artifacts. The strongest one, OrcaWasm, ships single-threaded
and multithreaded wasm32 builds of OrcaSlicer v2.4.2, OCCT included, at about
28.6 to 29.0 MB of raw `.wasm` each. The dependency work (Boost, oneTBB,
GMP/MPFR/CGAL, OCCT) is solved there. OpenVDB, OpenCV and Draco are stubbed
out, and FreeType and OpenSSL are replaced with header shims. Our Android
cross-compile hit the same dependency set, so we already know the problems.

The weak part is **browser-to-printer**, not slicing. Chrome and Edge (147+)
can reach `http://192.168.x.x` and `ws://` from an HTTPS page after one
Local Network Access prompt. Safari cannot reach plain-HTTP LAN devices from an
HTTPS page at all, and Firefox is unclear. Moonraker must also list our origin
in `cors_domains`. A user-run local connector (Desktop bridge) is needed for
Safari, for Firefox until testing shows otherwise, and for any printer whose
`moonraker.conf` the user cannot edit.

## 1. Prior art

| Project | What it is | Engine / threading | Size | Status (2026-09-26) |
|---|---|---|---|---|
| [Hiosdra/OrcaWasm](https://github.com/Hiosdra/OrcaWasm) | Standalone Emscripten build of OrcaSlicer v2.4.2 with a C API (session, profile, multi-plate slice, progress, cancel, arrange/orient, STEP to STL) | wasm32; `slicer.wasm` (ST, TBB header shim) and `slicer-mt.wasm` (real oneTBB + pthreads, "for COOP/COEP hosts"); Emscripten 3.1.74 | Release `wasm-v2.4.2-patch22`: ST 28,634,013 B, MT 28,965,011 B (+~0.1 MB JS) | Active, AGPL-3.0, CI publishes immutable releases; released on the research date |
| [OrcaWeb demo](https://hiosdra.github.io/OrcaWeb/) (same author) | Browser front end for the above | Worker, off main thread | Search snippet: "whole engine, OCCT included, is roughly 29 MB" | Page returned 404 when fetched; the snippet came from search results only |
| [Noisyfox/OrcaSlicerNeo](https://github.com/Noisyfox/OrcaSlicerNeo) | React/TS/Vite front end + libslic3r wasm for Electron and Web | **wasm64**; threaded (oneTBB) and serial (TBB shim) builds; picks threaded when `crossOriginIsolated` | Not stated; about 50 GB of disk for the dependency build | Active since 2026-08; requires Chrome 133+ (wasm64); AGPL |
| [kimgh06/Three_Slicer](https://github.com/kimgh06/Three_Slicer) ([demo](https://slicer.kimgh06.com/)) | "Reverse-engineered from OrcaSlicer" wasm kernel + npm packages (`three-slicer` AGPL, `three-slicer-viewer` MIT); FFF + SLA (PrusaSlicer 2.9.6 SLA chain) | Worker protocol; streaming slice | Not stated | Active, 26 stars; hollowing and organic trees are reported as unsupported |
| [allanwrench28/orcaslicer-wasm](https://github.com/allanwrench28/orcaslicer-wasm) | Headless wasm module | n/a | n/a | Stale since 2025-10 |
| [Cloud-CNC/cura-wasm](https://github.com/Cloud-CNC/cura-wasm) | CuraEngine in Emscripten, npm package | Single-threaded: Emscripten has no OpenMP | n/a | Older; README calls performance "decent but not great" |
| [SuperSlicer discussion #2946](https://github.com/supermerill/SuperSlicer/discussions/2946) | 2022 request to build libslic3r for wasm | n/a | n/a | Maintainer said it was possible; no work was done |
| [Kiri:Moto / grid.space](https://grid.space/kiri/) ([docs](https://docs.grid.space/kiri-moto/)) | From-scratch JS slicer; "almost entirely Javascript with a few minor modules compiled into WASM", work spread across Web Workers, three.js | Many workers | Small | Mature. It proves the privacy-first in-browser slicer UX, but it is not libslic3r |
| [SimplyPrint cloud slicer](https://simplyprint.io/features/slicer/orcaslicer), [linuxserver/docker-orcaslicer](https://github.com/linuxserver/docker-orcaslicer) | Server-side / streamed desktop | n/a | n/a | Not local-first; listed only for contrast |

Takeaway: we should not start from zero. OrcaWasm's `patches/apply.py`
(780 lines, idempotent), `overrides/`, `wasm/shims*` and its CI workflow
(1019 lines) are a working recipe. It is AGPL, the same licence as our engine.

## 2. Local precedent: the Android cross-compile

Source: `/mnt/faststorage/orcaslicer-android-engine/docs/PLAN.md`.
`scripts/` contains: `build_assimp, build_boost, build_cereal, build_cgal,
build_draco, build_eigen, build_expat, build_freetype, build_gmp, build_jpeg,
build_libnoise, build_libslic3r, build_mpfr, build_nlopt, build_occt,
build_opencv, build_openexr, build_openssl, build_openvdb, build_png,
build_tbb, build_zlib` (.sh), plus `env.sh, fetch_orcaslicer.sh,
package_dist.sh`.

Lessons from Android that apply directly to wasm:
- Several dependencies that look optional are pulled in unconditionally.
  `src/libslic3r/CMakeLists.txt` requires Cereal, EXPAT, PNG, JPEG, FreeType,
  OpenSSL, libnoise, NLopt, Assimp, Draco and OpenCV. The top-level CMake
  requires OpenGL, glfw3 and CURL even with `SLIC3R_GUI=OFF`. The fix is
  `patches/android-headless-engine.patch`, and OrcaWasm has the equivalent
  patch (section 1 of `apply.py`).
- **GMP/MPFR cannot be avoided.** libigl's exact boolean path
  (`MeshBoolean.cpp`, `CutSurface.cpp`) needs
  `Exact_predicates_exact_constructions_kernel_with_sqrt` →
  CGAL_Core → GMP+MPFR.
- Pins: CGAL 5.6.x, Eigen 5.0.1, OCCT 7.6.0 on Android. OCCT 7.7+ merged the
  STEP toolkits into `TKDESTEP`, which breaks `OCCT_LIBS`. OrcaWasm uses OCCT
  7.8.1 against Orca v2.4.2, so newer Orca sources have moved on. Pin to
  whatever our Orca commit's `deps/*.cmake` says.
- OpenEXR/IlmBase::Half was needed only because `FindOpenVDB.cmake` demands
  it. That dependency disappears once OpenVDB is dropped.
- A NanoSVG implementation TU is needed (the upstream one is GUI-only).
- `export_gcode("cube.gcode")` fails on an empty `parent_path()`. Always pass
  a path with a directory, such as `/work/out.gcode`, in MEMFS/OPFS too.
- Android `.so`: 57 MB stripped (with OpenCV, OCCT and Draco); the CLI binary
  is 19.8 MB stripped.

## 3. Which libslic3r features use each heavy dependency

Determined by grepping the local Orca checkout
(`/mnt/faststorage/orcaslicer-android-engine/orcaslicer/src/libslic3r`):

| Dependency | Used by | FDM slicing impact if removed |
|---|---|---|
| OpenVDB | `OpenVDBUtils.*`, `SLA/Hollowing.cpp`, `CSGMesh/VoxelizeCSGMesh.hpp` (SLA CSG), and an old commented-out path in `Support/TreeSupport3D.cpp` ("Old version using OpenVDB ... extremely slow") | **None.** Only SLA hollowing and voxel CSG are lost. Fuzzy skin uses **libnoise**, not OpenVDB |
| OCCT | `Format/STEP.*` (STEP import), `CAD/*` (the parametric CAD tab, `SLIC3R_CAD`), `Shape/TextShape.cpp` (text emboss outlines), `Format/svg.cpp` (SVG import) | STEP import, text emboss and SVG shapes are lost. Everything else still works |
| OpenCV | `TextureToColor/*`, `ObjColorUtils.hpp`, `TexturePainting.cpp` | Only OBJ texture/vertex-colour to filament quantisation is lost |
| Draco | `Format/DRC.cpp` | `.drc` import only |
| Assimp | `Format/AssimpImport.cpp` | Extra mesh formats only (STL/3MF/OBJ have native readers) |
| OpenSSL | `openssl/md5.h` in `Utils.hpp`, `bbs_3mf.cpp` (plate G-code .md5) | Only a small MD5 is needed; replace it with a shim |
| FreeType | `PrintConfig.cpp`, text shapes | A shim works; Emscripten also has a `USE_FREETYPE` port |
| CGAL + GMP/MPFR | `MeshBoolean.cpp`, `CutSurface.cpp`, `Triangulation.cpp`, `Geometry/VoronoiUtilsCgal.cpp` (Arachne), `TextureToColor/*` | **Required**: cut, booleans, negative volumes, Arachne checks |
| NLopt | `Arrange.cpp`, `Optimize/NLoptOptimizer.hpp`, `SLA/*` | Required for arrange |
| libnoise | Fuzzy skin | Required for fuzzy skin |

OrcaWasm confirms this in practice. It compiles with
`-DSLIC3R_NO_OPENVDB=1 -DSLIC3R_NO_OPENCV=1`, and it overrides
`OpenVDBUtils.*`, `SLA/Hollowing.cpp`, `ObjColorUtils.*`, `Format/DRC.cpp`,
`Format/svg.cpp` and `Shape/TextShape.cpp` with stubs. It uses header shims
for `openvdb.h`, `freetype/*` and `openssl/md5.h`.

## 4. Per-dependency feasibility

| Dependency | Verdict | Evidence / notes |
|---|---|---|
| Eigen, cereal, clipper/clipper2, admesh, qhull, poly2tri, miniz, semver | **Feasible** | Header-only or vendored plain C/C++ |
| zlib, libpng, libjpeg | **Feasible** | Emscripten ports (`-sUSE_ZLIB/-sUSE_LIBPNG/-sUSE_LIBJPEG`); OrcaWasm uses them. They must be rebuilt with the same EH mode (`-fwasm-exceptions`, `-sSUPPORT_LONGJMP=wasm`) |
| expat, NLopt, libnoise | **Feasible** | Plain CMake C/C++; OrcaWasm builds all three |
| Boost (1.83/1.84) | **Needs patch** | Build with b2 and the `clang-emscripten` toolset; consume via legacy `FindBoost` (`Boost_NO_BOOST_CMAKE=ON`). Trap: libslic3r always compiles with `BOOST_LOG_NO_THREADS=1`, so `libboost_log.a` must be built with the same flag even for the MT variant, or ~30 `v2s_st` symbols are left undefined. For ST, define `BOOST_THREAD_DONT_USE_PTHREAD`. Boost.Locale: std backend only, no ICU. OrcaWasm also patches `utils.cpp` for single-threaded Boost.Log |
| oneTBB | **Feasible (official)** | oneTBB ships [WASM_Support.md](https://github.com/uxlfoundation/oneTBB/blob/master/WASM_Support.md): pthreads by default, or `-DEMSCRIPTEN_WITHOUT_PTHREAD=true`. It warns that a Worker cannot spawn workers without the browser thread, which can leave the app "running in serial". The fix is `-sPROXY_TO_PTHREAD` or pre-warming the pool. OrcaWasm pins v2021.13.2 and pre-allocates `-sPTHREAD_POOL_SIZE=navigator.hardwareConcurrency+4`. The ST fallback is a header shim for `tbb/*` and `oneapi/tbb/*` (OrcaWasm `wasm/shims`) |
| GMP 6.3.0 / MPFR 4.2.1 | **Feasible** | autotools `--host=wasm32-unknown-emscripten --disable-assembly --disable-shared` (OrcaWasm CI). Portable C only, so it is slower than native asm, but that is acceptable |
| CGAL 5.6.x | **Feasible** | Header-only against the GMP/MPFR above |
| OCCT 7.x | **Needs patch / optional** | OrcaWasm builds OCCT 7.8.1. It takes about 45 minutes in CI, and OCCT signal handling must be disabled because it relies on setjmp/longjmp. It is a large share of binary size. Recommend a **separate lazy-loaded STEP module** (OrcaWasm already exposes `cad_to_stl`) rather than putting it in the core engine |
| OpenVDB (+OpenEXR/Half, Blosc) | **Drop** | SLA only (section 3). Remove it with a `SLIC3R_NO_OPENVDB` guard plus stubs |
| OpenCV | **Drop** | Only texture-to-colour. Stub it |
| Draco, Assimp | **Drop** (for now) | Import formats only |
| OpenSSL | **Drop, shim MD5** | A tiny public-domain MD5 behind `openssl/md5.h` |
| FreeType | **Shim or port** | Shim if text emboss is out of scope; otherwise use the Emscripten port |
| libslic3r itself | **Needs patch** | OrcaWasm's list: narrowing fixes for 32-bit `size_t` (`GCode.hpp`), Eigen deduction (`AABBTreeLines.hpp`), `thread_local` in `FuzzySkin.cpp` for ST, Platform `static_assert`, thumbnails jpg→png, `Thread.cpp` thread-naming stub, and several Arachne guards for degenerate or empty shapes (`SkeletalTrapezoidation.cpp`, `WallToolPaths.*`) |

## 5. Recommended build strategy

1. **Start from our Android pipeline plus OrcaWasm's recipe**, pinned to the
   *same Orca commit* the Android engine uses. G-code should then match across
   Android, Desktop and Web, so compare output byte-for-byte on fixture
   projects. Reuse OrcaWasm patches that apply cleanly and keep our own patch
   file for the rest (as `patches/android-headless-engine.patch` does).
2. **Emscripten version:** pin **3.1.74** first. It is proven for this exact
   stack in OrcaWasm CI, including mixed C/C++ exceptions, longjmp and
   pthreads. The current emsdk tag is 6.0.10. Upgrading is a separate,
   deliberate step, with the ST/MT smoke slice and G-code diff as the gate.
3. **Flags (from OrcaWasm's `wasm/CMakeLists.txt`, adjusted):** `-O3`,
   `-fwasm-exceptions` everywhere, `-sSUPPORT_LONGJMP=wasm`,
   `-sALLOW_MEMORY_GROWTH=1`, `-sINITIAL_MEMORY=256MB`,
   `-sMAXIMUM_MEMORY=4GB`, `-sSTACK_SIZE=16MB` (libslic3r recursion is deep),
   `-sMODULARIZE -sENVIRONMENT=worker,node`, `-sFORCE_FILESYSTEM=1`.
   Node is included for CI smoke tests. OrcaWasm uses `-sMALLOC=emmalloc`;
   for the MT build, evaluate `-sMALLOC=mimalloc` because emmalloc uses a
   global lock **(verify)**.
4. **Two artifacts from one source:**
   - `engine-mt.wasm`: `-pthread` (not the deprecated `-sUSE_PTHREADS`), real
     oneTBB, `PTHREAD_POOL_SIZE` pre-allocated. Load it when
     `self.crossOriginIsolated === true`.
   - `engine-st.wasm`: TBB header shim, no SharedArrayBuffer. This is the
     fallback for non-isolated contexts and embedded webviews.
   - Instantiate both in a **dedicated Worker** that the UI drives through a
     message protocol (progress, cancel via `PrintBase::cancel()`, and G-code
     returned as a transferable ArrayBuffer or written to OPFS).
5. **Compile out:** OpenVDB, OpenCV, Draco, Assimp, OpenSSL (MD5 shim),
   `SLIC3R_CAD`, SLA hollowing, and all GUI code. **Load STEP support on
   demand** as a separate OCCT wasm module that converts STEP to mesh before
   the main engine. Keep CGAL/GMP/MPFR, NLopt and libnoise.
6. **wasm32, not wasm64, for v1.** Memory64 has shipped in Chrome/Edge 133+
   and Firefox 134+, but **not in Safari (desktop or iOS)** ([caniuse
   wf-wasm-memory64](https://caniuse.com/wf-wasm-memory64),
   [chromestatus](https://chromestatus.com/feature/5070065734516736)). wasm64
   also doubles pointer-heavy memory, as OrcaWasm's probe notes. OrcaWasm
   concluded that "there is no measured workload here that needs" wasm64.
   Revisit only if a real project exceeds about 3.5 GB.
7. **CI:** the dependency build is roughly 1.5 to 3 hours cold (OrcaWasm: deps
   about 100 minutes, OCCT about 45 minutes, main build about 60 minutes).
   Cache the dependency prefix per variant; ST and MT object code differs.

## 6. Expected size and memory

- **Binary:** about 29 MB of raw `.wasm` with OCCT (OrcaWasm measured). Without
  OCCT, expect meaningfully less; the exact figure depends on our feature set
  **(verify by building)**. Over the wire, Brotli should cut this substantially
  **(estimate: roughly 6 to 10 MB; not measured)**. Serve it with
  `Content-Encoding: br`, use `WebAssembly.compileStreaming`, and cache it in
  the service worker. Keep one engine per content-hashed URL so offline PWA
  updates are atomic.
- **Memory:** the wasm32 ceiling is 4 GB per module. In practice:
  - Desktop Chrome/Firefox can grow toward 4 GB.
  - Mobile browsers often fail allocations well below that **(verify on the
    Razr and a mid-range device)**.
  - Each pthread needs its own stack (default 64 KB in Emscripten; set
    `-sDEFAULT_PTHREAD_STACK_SIZE` explicitly if Orca needs more **(verify)**).
  - Plan for **pre-slice memory estimation** (triangle count × layers) and a
    friendly "project too large for this browser, use Desktop" message instead
    of an OOM abort (`-sABORTING_MALLOC=0` as OrcaWasm does, and catch
    `std::bad_alloc` in the bridge).
- **Speed:** MT wasm is typically slower than native (bounds checks, no
  asm GMP, 128-bit SIMD only via `-msimd128`). Budget for 1.5 to 3× native
  **(estimate; benchmark Benchy and a large multi-plate project)**.

## 7. Browser platform support matrix

| Capability | Chrome / Edge desktop | Firefox desktop | Safari macOS | Chrome Android | Notes / source |
|---|---|---|---|---|---|
| SharedArrayBuffer (needs cross-origin isolation) | Yes (68+; isolation gated since 92) | Yes (79+) | Yes (15.2+) | Yes | [caniuse](https://caniuse.com/sharedarraybuffer); needs `COOP: same-origin` and `COEP: require-corp` (`credentialless` is not portable to Safari **(verify)**) |
| Wasm threads + native Wasm EH | Yes | Yes | Yes | Yes | Follows from SAB support; Wasm EH is in all current engines |
| Memory64 | 133+ | 134+ | **No** | Yes (recent) | [caniuse](https://caniuse.com/wf-wasm-memory64) |
| WebGL2 | Yes | Yes | Yes | Yes | Universal baseline for the 3D viewer |
| WebGPU | Yes (113+) | Windows 141+, macOS ARM 145+; Linux/Android in progress | Yes (Safari 26) | Yes (121+, Android 12+) | [web.dev](https://web.dev/blog/webgpu-supported-major-browsers), [gpuweb status](https://github.com/gpuweb/gpuweb/wiki/Implementation-Status). Use it as an enhancement only; WebGL2 is the baseline |
| OPFS + `createSyncAccessHandle` (worker) | Yes | Yes | Yes | Yes | [MDN](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemFileHandle/createSyncAccessHandle): "widely available" since March 2023 |
| File System Access pickers (`showOpenFilePicker`, `showSaveFilePicker`) | Yes (105+) | **No** (Mozilla position: harmful) | **No** | **No** | [caniuse](https://caniuse.com/native-filesystem-api). Fall back to `<input type=file>` plus download |
| Service worker / offline | Yes | Yes | Yes | Yes | Universal |
| PWA install | Yes | Windows "Web Apps / taskbar tabs" since 142-143; Linux behind a pref | "Add to Dock" (macOS 14+) | Yes | [Firefox docs](https://firefox-source-docs.mozilla.org/browser/components/taskbartabs/docs/index.html) |
| Storage quota per origin | Up to 60% of disk | About 10% best-effort, about 50% if `persist()` | 1 GiB initially, then prompts; script-created data evicted after 7 days without interaction unless persisted or installed | Up to 60% of disk | [MDN quotas](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria). Always call `navigator.storage.persist()` |
| Local Network Access permission | Yes (fetch 142+, WebSocket 147+, WS `targetAddressSpace` 154) | Rolling out (149 with ETP strict, 151+ gradually) | **None** (no LNA, strict mixed content) | Yes (147 for WS) | See section 8 |

Implications:
- COOP/COEP applies to the whole page. It breaks cross-origin `<img>` and
  iframes that lack CORP/CORS, which includes a **printer webcam MJPEG `<img>`**.
  Fetch camera frames through CORS, or through the connector, instead of using
  a plain `<img src=http://printer/...>`.
- Keep the non-isolated ST engine as a working fallback so that pages which
  cannot be isolated still slice.

## 8. Browser-to-printer connectivity

### What the platforms do (2026)

- **Chrome/Edge Local Network Access (LNA)** replaced the Private Network Access
  preflight model ([Chrome blog](https://developer.chrome.com/blog/local-network-access),
  [spec](https://wicg.github.io/local-network-access/)).
  - A public HTTPS site requesting a local or loopback address triggers a
    permission prompt (Chrome 142+). Chrome 145 split it into `local-network`
    and `loopback-network`. The prompt is only available to secure contexts.
  - **Once granted, mixed content is relaxed** for private-IP literals and
    `.local` hosts. Other hostnames need `fetch(url, {targetAddressSpace:"local"})`.
  - WebSockets have been gated since Chrome 147 on desktop and Android
    ([intent](https://groups.google.com/a/chromium.org/g/blink-dev/c/O6GMKt44Ups)).
    Chrome 154 (2026-09-22) added `targetAddressSpace` to the WebSocket
    constructor ([release notes](https://developer.chrome.com/release-notes/154)).
  - Service and shared workers can only make local requests after the page has
    obtained the permission. Background Fetch is LNA-gated from 154. Android
    WebView is not covered.
  - The spec's "local" space **includes 100.64.0.0/10**, which covers
    Tailscale CGNAT addresses. A `100.x.y.z` Tailscale IP therefore gets the
    same prompt and should get the same mixed-content relaxation as a
    private-IP literal **(verify in Chrome)**.
- **Firefox** has its own LNA prompt, enabled with ETP Strict in 149 and rolling
  out from 151 ([FOSDEM 2026 talk](https://fosdem.org/2026/schedule/event/QCSKWL-firefox-local-network-access/),
  [admin policy](https://firefox-admin-docs.mozilla.org/reference/policies/localnetworkaccess/)).
  Behaviour has changed between releases: [bug 2059274](https://bugzilla.mozilla.org/show_bug.cgi?id=2059274)
  records silent blocking in 155 that was reverted in 154/156/ESR 153.
  Firefox treats `http://127.0.0.1`/`localhost` as potentially trustworthy
  (not mixed content). Whether Firefox relaxes mixed content for
  `http://192.168.x.x` after an LNA grant is **not confirmed (verify)**. Assume
  it is blocked.
- **Safari** has no LNA and blocks active mixed content, including `ws://`
  ([WebKit 89068](https://bugs.webkit.org/show_bug.cgi?id=89068)). It even
  treats loopback `http://127.0.0.1` as mixed content: [WebKit
  171934](https://bugs.webkit.org/show_bug.cgi?id=171934) is still unresolved.
  From `https://app.nozzleitall.com`, Safari can only reach a printer at an
  **HTTPS/WSS endpoint with a publicly trusted certificate**.
- **Moonraker CORS** ([config docs](https://moonraker.readthedocs.io/en/latest/configuration/)):
  - `[authorization] cors_domains` must include `https://app.nozzleitall.com`.
    `*` is documented as a developer-only setting.
  - The `X-Api-Key` header forces a preflight, which Moonraker answers when
    the origin matches.
  - Moonraker already answers `Access-Control-Request-Private-Network` with
    `Access-Control-Allow-Private-Network: true` (`application.py`; [PR
    #469](https://github.com/Arksine/moonraker/pull/469)).
  - The **WebSocket** handler's `check_origin` rejects cross-origin upgrades
    unless the origin matches `cors_domains` or a trusted IP
    (`websockets.py`). This means `cors_domains` is mandatory for both HTTP
    and WS.
  - Whether PAXX exposes `moonraker.conf` for user edits, and what the stock
    U1 `cors_domains` contains, is **unknown (verify on the U1)**.
- **Tailscale HTTPS** ([docs](https://tailscale.com/kb/1153/enabling-https)):
  - `tailscale cert` issues a Let's Encrypt certificate for
    `<machine>.<tailnet>.ts.net` via DNS-01. It needs MagicDNS, and the private
    key stays local. `tailscale serve` can front a local port with it.
  - The certificate lasts 90 days, and renewal is the operator's job unless
    `serve`/Caddy handles it.
  - **Machine names appear in public Certificate Transparency logs.**
  - The result is a secure origin that works in every browser, Safari
    included. LNA (Chrome) still prompts because the name resolves to 100.x.
  - It only works while the viewing device is on the tailnet, and it needs
    Tailscale running **on the printer or on an always-on LAN node proxying to
    it**. Whether PAXX can run tailscaled is **unknown (verify)**.

### What direct connectivity is realistically possible

| Browser | LAN `http://192.168.x.x:7125` + `ws://` | Tailscale `100.x` IP (http) | Tailscale `*.ts.net` HTTPS (serve/cert) |
|---|---|---|---|
| Chrome/Edge desktop, Chrome Android | **Yes** after one LNA prompt, if `cors_domains` is set | Likely yes, same prompt **(verify)** | Yes (prompt + CORS) |
| Firefox | Doubtful; plan for blocked **(verify)** | Doubtful **(verify)** | Yes, plus a possible LNA prompt |
| Safari macOS/iOS | **No** (mixed content) | **No** | Yes, if CORS is set |

Chrome-family users with an editable `moonraker.conf` can go **direct**. That
is the majority case on desktop and Android. Everyone else needs HTTPS on the
printer side (Tailscale serve) or the connector. Direct mode also requires the
user to edit `moonraker.conf`. The app should detect a CORS failure and show
the exact line to add instead of a generic network error.

### What the user-run local connector (Desktop bridge) must provide

1. **Loopback-only listener**, `127.0.0.1`/`::1` on a fixed, registered port
   with a fallback range. It must never bind to `0.0.0.0`. Chrome shows the
   `loopback-network` LNA prompt, and Firefox treats loopback HTTP as secure.
   Safari needs TLS on loopback: either the connector is the Desktop app itself
   and Safari users use Desktop, or the connector serves HTTPS through a
   Tailscale certificate. **Do not install a local root CA.**
2. **Strict origin checks.** Exact-match allowlist
   (`https://app.nozzleitall.com`, plus a dev origin behind a flag). CORS
   headers go only to that origin. `Origin` is checked on every WebSocket
   upgrade. `Host` must be `127.0.0.1:<port>` or `localhost:<port>` to defeat
   DNS rebinding. Answer `Access-Control-Allow-Private-Network` on preflight.
3. **Pairing and authentication.** The connector shows a short one-time code
   and the web app submits it. They exchange it for a random bearer token that
   is scoped to this origin and device, stored in IndexedDB, and sent in
   `Authorization`, never in cookies or URLs. The connector UI lists paired
   clients and can revoke them, and tokens expire. Pairing attempts are rate
   limited.
4. **Not an open proxy.** The connector talks only to printers the user added
   in the connector (or confirmed via mDNS discovery). It exposes a typed
   printer API (status, upload with streamed progress, start/pause/cancel,
   macros from our existing adapter allowlist, camera frames) rather than
   arbitrary URL forwarding. It holds Moonraker API keys itself, so the browser
   never sees them. It reuses the Kotlin `printer-api`/`adapter-paxx` code from
   the Desktop KMP build.
5. **Versioned API.** Use a `/v1/` prefix and `GET /v1/hello` → `{apiVersion,
   minClient, connectorVersion, capabilities[]}`. The web app refuses
   incompatible majors with an "update connector" message. It also needs
   compatible additions, a WebSocket event stream with sequence numbers, and
   idempotent upload IDs.
6. **Operations.** It auto-starts with the Desktop app, auto-updates via
   signed releases, keeps a local audit log of commands, and never collects
   telemetry of model data.

## 9. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| AGPL network clause: serving the engine (and the app that links it) from app.nozzleitall.com is conveying AGPL code | Must publish Corresponding Source for the engine and app | Publish the source repo and link it from the app. This is already the model for the Android engine |
| Mobile memory limits below 4 GB | OOM on large projects | Pre-flight size estimate, `ABORTING_MALLOC=0`, "open in Desktop" fallback |
| oneTBB in workers stalls to serial or deadlocks if the pool is not pre-warmed | Slow or hung slices | `PTHREAD_POOL_SIZE` pre-allocation, engine as the Worker's "main", ST/MT comparison test (OrcaWasm `compare-st-mt.mjs`) |
| COOP/COEP breaks third-party embeds and printer camera `<img>` | Broken UI pieces | Fetch over CORS, or route media through the connector |
| Our patches drift from upstream Orca | Painful upgrades | Idempotent patch script, pinned Orca commit shared with Android, G-code diff gate |
| Browser LNA/mixed-content rules still changing (Firefox regressions in 2026, Chrome WS rules at 147/154) | Direct mode breaks silently | Capability probe at connect time, clear error with remedy, connector always available |
| Safari cannot do direct LAN at all | iOS/macOS Safari users have no direct control | Tailscale HTTPS path or Desktop app; say so honestly in the UI |
| Tailscale CT-log exposure of machine names | Privacy leak | Warn users to use non-identifying names |
| Emscripten upgrade regressions (3.1.74 → 6.x) | Build breaks | Upgrade only behind the smoke-slice and G-code diff gate |
| 29 MB wasm first load | Slow first run on mobile data | Brotli, service-worker cache, lazy OCCT, progress UI |

## 10. Next steps (proposed, not started)

1. Build OrcaWasm's ST artifact locally as-is, slice a U1 fixture profile in
   Node, and diff against the Android engine's G-code.
2. Port its patches onto our pinned Orca commit, drop OCCT, then measure raw
   and Brotli size and peak memory for Benchy and a large multi-plate project.
3. On the real U1: check whether `moonraker.conf` `cors_domains` is editable
   and whether PAXX can run Tailscale. From an HTTPS test page, try Chrome
   `fetch` + `ws://` to `192.168.x.x` and `100.x`, Firefox, and Safari. Record
   the results in this file.
