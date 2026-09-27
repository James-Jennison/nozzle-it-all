# Completion report: Nozzle It All product family, 2026-09-26

Covers the governing brief: Desktop plus the Advanced Workspace, Android, the Web App, the public site, and
multi-vendor printer support with PAXX U1 as the flagship. Everything is committed locally. Nothing has been pushed,
deployed or published, and no physical printer actions were taken.

## Where things are

| Piece | Location | State |
|---|---|---|
| Shared printer model (families, capability schema 2, extensions) | `printer-api` | Done, tested |
| Adapters: PAXX U1, Moonraker/Klipper, OctoPrint, PrusaLink, Bambu Lab LAN | `adapter-*` | Done, tested; hardware status in the capability matrix |
| Optional Stock U1 adapter (helper process, only part allowed to use a vendor cloud) | `stock-u1-adapter` | Done, tested; live Snapmaker account UNVERIFIED |
| Project format (3MF + Nozzle manifest, unknown data preserved, profile and family recorded) | `project-format`, `web/src/project` | Done; Kotlin and Web read each other's files |
| Profiles: 376 shared, indexed with source commit and licence | `app/src/main/assets/slicer_profiles`, `scripts/build_profile_index.py` | Done |
| Desktop (Compose): capability-driven screens, any profile, local connector | `desktop` | Done, tested |
| Shared slicing engine, native (Desktop) | `engine/native` | Built from OrcaSlicer 824b216f + the Android bridge |
| Shared slicing engine, WebAssembly (Web App) | `engine/wasm` | Built from the same source and bridge |
| Web App (local-first, in-browser slicing, PWA) | `web` | Done; not deployed |
| Advanced Workspace (Orca-derived, separate process) | `/mnt/faststorage/Snapmaker-Orca/nozzle-advanced-workspace`, branch `nozzle/advanced-workspace` | Done; not pushed |
| Android | `app` | Uses shared terms and tokens |
| Public site | `site-src`, `site` | Updated; not deployed |
| Linux packages | `dist/linux` (built by `scripts/package_linux.sh`) | Built with provenance |

## Test evidence (this session)

- **Kotlin modules:** 65 tests pass:
  - printer-api 26, adapter-paxx 13, OctoPrint 4, PrusaLink 2, Bambu 3, Stock U1 4, project-format 12, domain 1.
- **Desktop:** 25 tests pass:
  - acceptance 11, multi-vendor 7, local connector 2, presets 1, slice engine 4.
  - Also passes with only the PAXX adapter shipped (`-PnozzleAdapters=paxx`). The only failure seen in that run was `bambu_generic`, before the native engine replaced the Orca fork; it has not been rerun with only PAXX since.
  - Four makers' profiles (Snapmaker U1, Prusa, Bambu Lab, generic Klipper) plus the Prusa XL 5-tool slice with no printer connected.
- **Web App unit tests:** 23 pass.
- **Web App Playwright:** 34 tests pass in Chromium and Firefox:
  - cross-origin isolation;
  - slice, save, reopen, re-slice and export G-code;
  - slicing for a Prusa MK4 profile with nothing connected;
  - cancel;
  - every page at 390 and 1440 px (one heading, named controls, no sideways scroll);
  - dark and light themes.
  - Screenshots are in `web/test-results/evidence`.
- **Android:** 635 unit tests pass (1 skipped). On the Razr 2026, 314 of 315 device tests passed; the failure was a stale "Unavailable" expectation, now "Offline", and those tests pass. Updated in place (versionCode 274, data kept).
- **Engines:** a 20 mm cube gives 100 layers on every profile, in both engines:

  | Profile | Native (Desktop) | WebAssembly (Web App) |
  |---|---|---|
  | Snapmaker U1 | 3.70 g | 3.70 g |
  | Prusa | 4.10 g | 4.11 g |
  | Bambu Lab | 4.24 g | 4.25 g |
  | Generic Klipper | 4.11 g | 4.12 g |
  | Prusa XL 5-tool | 3.66 g | 3.66 g |

- **Advanced Workspace:**
  - headless slice and multi-material;
  - Nozzle manifest round-trips byte-identical;
  - branded identity and data directory;
  - OrcaSlicer and Snapmaker Orca settings untouched;
  - nothing listens on the old port 13619;
  - no internet connections or DNS lookups in 60 s with Stock U1 off;
  - TLS certificates verified (untrusted ones refused, per-printer CA file honoured);
  - no blocking certificate prompt;
  - branded setup wizard with no telemetry page;
  - `cmake --install` works.
- **Packages (extracted and run, not installed with dpkg):**
  - Desktop starts under a virtual display and uses its own data directories.
  - The Stock U1 helper starts on its own runtime and speaks the adapter protocol.

## Invariants checked by tests

- Screens decide from capabilities, never vendor names (`screensFollowCapabilitiesNotVendorNames`, `VendorNeutralCoreTest`).
- A missing or failing adapter affects only its own printers.
- No built-in adapter may use a vendor cloud. Stock U1 is never built in, and the Desktop package excludes it (`verifyPaxxBaseline`).
- Transport-based adapters can't declare controls they can't perform (`TransportSession` refuses).
- Projects carry the profile and family, never addresses or credentials. The connector never returns secrets.
- The public site never calls an untested family "verified".
- Shared profile folders hold only profile files.

## Bugs found and fixed along the way

- Desktop offered Heat buttons for OctoPrint, PrusaLink and Bambu printers that couldn't use them.
- Desktop could not slice Bambu Lab profiles: the Orca fork is older than the profiles' source. Fixed by the shared native engine.
- In the Web App, cancel was ignored while the engine was still loading.
- A single-material print showed "2 toolhead changes" (Web and Desktop).
- Web App: Prepare had no page heading and overflowed narrow screens; the family chooser didn't wrap.
- The public site said the Bambu camera works on every model; it works on A1 and P1 only.
- The Advanced Workspace:
  - accepted any TLS certificate;
  - blocked startup with a certificate prompt;
  - contacted Snapmaker and GitHub at startup;
  - showed a telemetry opt-in.
- An Orca CLI test run wrote a stray `result.json` into a shared profile folder. It was removed, and a test now guards against this.

## Not done, or UNVERIFIED

- **Hardware:** only PAXX U1 and Klipper are verified on hardware. Stock U1, Bambu Lab, OctoPrint and PrusaLink remain UNVERIFIED. No physical printer actions were taken this session.
- **Bambu send:** Bambu printers need a `.gcode.3mf`, and `.bgcode` for PrusaLink isn't produced either. Desktop and Web explain this rather than offering the send.
- **Engine differences:** the native and WebAssembly engines agree on layers, but differ slightly in toolpath order (up to 0.01 g and 22 s). The likely cause is libc++ versus libstdc++; this isn't confirmed.
- **Safari:** there is no single-threaded fallback yet.
- **Cancel mid-slice:** only cancel during engine start-up is tested in the browser; the test cube slices too fast to cancel mid-slice.
- **Web App deployment:** not deployed to app.nozzleitall.com; that needs your go-ahead. The site changes are also undeployed.
- **Advanced Workspace:**
  - Stock U1 code is still linked but never started.
  - The Stock MQTT path still skips host-name checks.
  - An `https://` U1 address with a self-signed certificate fails in the native U1 path. PAXX uses HTTP, so this doesn't affect it.
  - The session-save test ran on the build before the last two changes.
- **Android:** 3MF interchange with Desktop and Web, and automatic PAXX detection, are not done this session. Device tests ran on the Razr 2026 only.
- **Packages:** not installed system-wide with dpkg. No Windows or macOS builds.
- **Connector:** the Web App shows no controls for a connector printer until Desktop has connected to it once; its capabilities endpoint returns 503 until then.

## Packages (`dist/linux`, source `cbd3878`)

| Package | Size | SHA-256 |
|---|---|---|
| `nozzle-it-all_0.1.0-1_amd64.deb` | 89 MB | `4cae6993dd985d7e898d0ea1ddb55233029065c634e9d941640557d370cac80f` |
| `nozzle-stock-u1-adapter_0.1.0-1_amd64.deb` | 19 MB | `3b4173d612e02d1479d8ed9b9ef219ea9dc3c9fe396d4e1ae4c8cd9e921c9aa4` |
| `nozzle-advanced-workspace_0.1.0-1_amd64.deb` | 102 MB | `d48f6ec79c0a5b2f388caaa26eb679b207b4fed42ecf97b9906c4a590672664f` |

Build details are in `dist/linux/PROVENANCE.txt`: engine commit and flags, Java, adapters included, workspace branch and commit.
