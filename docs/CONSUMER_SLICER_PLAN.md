# Consumer Mobile Slicer & Printer-Control Platform — Planning Document

**Status:** Planning only. No implementation performed. Requires explicit owner approval before Phase 0 begins.
**Prepared:** 2026-09-22, against branch `codex/android-mvp`, HEAD `9cd5fb4`. Revised same day as the plan evolved through owner discussion: Phase 10 (Discover) and Phase 11 (Account/Sync) approved with concrete targets, and the target platform scope expanded from Android-only to Android + iOS + multi-OS Desktop, then **narrowed again the same day: iOS (Phase 12) deferred entirely, macOS deferred from the Desktop port (Phase 13 is now Windows/Linux only)** (§6a) — the repository audit below (§2) describes the app's *current, real* state, which remains Android-only today; §6a is where the multi-platform target and its real implications are addressed.
**Method:** Every architectural claim below was verified — against the real repository (four parallel research passes with file:line citations; one initial pass that ran in an isolated worktree returned stale/wrong results and was discarded and re-run directly against the working tree, noted here for transparency, not hidden) and, where the plan makes claims about external services or technology maturity (MyMiniFactory/MakerWorld/Printables API status, Compose Multiplatform/KMP readiness, iOS slicing-engine prior art), against live external research, not prior knowledge alone.

**Currency note:** this is a point-in-time plan/audit (`HEAD 9cd5fb4`) and isn't continuously rewritten as phases land — some gaps it describes as missing (e.g. multi-object scenes, project persistence) are now partial, per Phase 1/WO-17. **`docs/WORK_ORDER.md` is the current, authoritative status for anything it and this document disagree on**; this plan remains the source for the longer-term roadmap/phase structure itself.

---

## 1. Executive Summary

"Nozzle It All" is further along toward "consumer mobile slicer" than a fresh audit might suggest: it already has a **real, on-device, AGPL-3.0-licensed OrcaSlicer-derived native slicing engine** (not a cloud call, not a mock), a working 3D build-plate viewer with move/rotate/scale and support painting, real build-volume bounds checking, and control integrations for four printer ecosystems (Klipper/Moonraker, Bambu Lab, Prusa Link, Snapmaker U1). What it lacks is almost everything *around* that core: multi-object scenes, materials/multicolor, a model library/discover surface, project persistence, printer-capability abstraction, and a account/cloud layer of any kind — by design, not oversight (the app's own Settings tab states "Local network only — no cloud account, no telemetry").

This plan's central tension, and the first thing the owner must decide, is exactly that: several competitor capability groups (Discover/marketplace, cloud slicing, cross-device sync) assume a cloud account. This plan does **not** assume the owner wants that. It treats local-first as the load-bearing identity of the app, and designs every cloud-touching capability as optional, clearly bounded, and off by default, so the owner can adopt as much or as little of the "consumer app" feature set as fits that identity — rather than presenting cloud/account infrastructure as a foregone conclusion.

The recommended path is: **evolve the existing native engine and 3D viewer into a real multi-object build-plate workspace (Phase 1), formalize printer capabilities into one real abstraction (Phase 2), add materials as a first-class model (Phase 3), then layer progressive slicing UX, live control, and multicolor on top** — reaching a complete core product on Android first, before multiplying platforms.

**Owner-confirmed target platforms, latest revision (2026-09-22): Android + Desktop (Windows/Linux)** — iOS is deferred entirely (Phase 12 paused, not cancelled outright — see §6a), and macOS is deferred out of the Desktop port specifically (Phase 13 is now Windows/Linux only). This followed an earlier same-day revision that had briefly expanded scope to Android + iOS + multi-OS Desktop; the iOS/App Store legal research done during that window (below) is preserved in §6a in case iOS is picked back up later, but it is not active scope right now. Kotlin Multiplatform + Compose Multiplatform remains the recommended approach for the Desktop port (verified production-ready), sharing the existing Kotlin/Compose codebase, with the `:shared` module extraction still folded into Phase 0 so it isn't retrofitted later if iOS returns to scope.

---

## 2. Current-State Repository Audit

Verified branch `codex/android-mvp`, HEAD `9cd5fb4`, clean working tree.

### 2.1 Platform & build — **Complete**
- Android-only. Single Gradle module (`:app`, `settings.gradle.kts:4`). No iOS/KMP/Flutter anywhere.
- `compileSdk 36`, `minSdk 28` (justified by a Boost.Locale/iconv native requirement, `app/build.gradle.kts:4-8`), `targetSdk 36`. Kotlin 2.2.21, AGP 8.13.2, Compose BOM 2026.06.00.
- No DI framework (no Hilt/Koin/Dagger anywhere) — dependencies are wired by hand (`viewModelFactory { initializer { ... } }`, `MainActivity.kt`).
- **Real native slicing engine**: `app/src/main/cpp/bridge/` contains `slic3r_engine.cpp/.hpp`, `slic3r_jni.cpp`, `cli_test.cpp`, `thumbnail_render.cpp/.hpp`. Cross-compiles upstream OrcaSlicer's `libslic3r` for Android via a sibling local project (`orcaslicer-android-engine`, `ORCASLICER_ENGINE_ROOT` in `CMakeLists.txt:35-49`, build fails without it — a real, non-portable build dependency worth documenting for any new contributor/CI). **License: AGPL-3.0-or-later** (`slic3r_engine.cpp:1-4`, `THIRD_PARTY_NOTICES.md:71-93`).

### 2.2 Navigation — **Partial (functional, ad-hoc)**
No Navigation-Compose. `var tab by rememberSaveable { mutableIntStateOf(0) }` (`MainActivity.kt:226`) drives a flat `NavigationBar` over 5 tabs (`MainActivity.kt:313-314`): **Home, Control, Files, Prepare, Settings**. A second piece of state (`detailAddress`) plus one `BackHandler` is the entire "back stack." No deep-linking.

### 2.3 State management — **Complete (ViewModel core), Partial (session persistence)**
Real `ViewModel` + `StateFlow`: `PrinterModel.kt:69,81`, `ScreenState` (`PrinterModel.kt:11-24`, ~24 fields: connection, snapshot, catalog, camera, file metadata, history, saved printers/profiles). `SliceAndPrintPanel.kt` — the slicing-session UI I wrote this session — uses `remember(uri){...}` throughout (paintState, transformState, bedShape, customization, sliced output, etc.), **never** `rememberSaveable`. None of it survives process death. This is still true for that single-object share-intent/Prepare-tab flow specifically, which Phase 1 deliberately left unchanged. **Phase 1 (WO-17, see `docs/WORK_ORDER.md`) closed the equivalent gap for the separate Projects flow**: `ProjectViewModel`/`ProjectFileStore` persist a multi-object build plate through Room + `filesDir`, verified to survive a simulated process death.

### 2.4 Testing — **Strong on protocol/native, zero CI**
369 unit `@Test` methods (60 files), 136 instrumented `@Test` methods (42 files). A meaningful cluster already covers the native slicing path (`MeshPreviewDeviceTest`, `PaintSessionDeviceTest`, `SlicingCoordinatorTest`/`...DeviceTest`, `SlicingProfilePacksTest`/`...DeviceTest`, `NativeEngineSmokeTest`, `BedShapeTest`, `BedMeshTest`/`...DeviceTest`, `SliceCustomizationTest`). **No CI config exists** (`.github/workflows` absent) — every gate today runs manually on a developer's machine against a physical device. This is a real risk for a growing codebase (see §20 Risks).

### 2.5 Running work log — **`docs/WORK_ORDER.md` exists, 673 lines, 12 WO entries** (WO-4 through WO-15; WO-1/2/3 already closed and not itemized further). Only one real forward-looking TODO found repo-wide: `MainActivity.kt:643`, "A MakerWorld model browser is planned but not built yet." `docs/FEATURE_PARITY_ROADMAP.md` also exists as a broader, older roadmap — this plan supersedes it for the areas it covers and should be reconciled/merged by the owner post-approval, not run in parallel.

### 2.6 Settings / account — **No account system exists, by design**
Printer profiles persist in `SharedPreferences` (`PrinterPreferences.kt`), API keys/access codes in an encrypted `security-crypto` store, kept deliberately separate. Repo-wide search found no login/account/cloud-sync/OAuth code. Exact UI string, `MainActivity.kt:657`: **"Local network only — no cloud account, no telemetry."** This is a product identity statement, not an accidental gap — treat it as a constraint, not a checklist item to "fix."

### 2.7 Printer integrations — **4 vendors, uneven depth**
`PrinterKind` (`M1Data.kt:7`): `GENERIC_KLIPPER, SNAPMAKER_U1_PAXX, BAMBU_LAB, PRUSA_LINK`.
| Vendor | Transport | Control depth |
|---|---|---|
| Generic Klipper/Moonraker | HTTP REST | **Complete** — heaters, fans, macros, LEDs, start/pause/resume/cancel, files, timelapse playback, console |
| Snapmaker U1/PAXX | Moonraker-shaped HTTP (+ optional SSH plugin bridge, Bespok3d) | **Complete** via Moonraker path |
| Bambu Lab | MQTT/TLS:8883 + FTPS:990 upload + proprietary chamber-cam | **Partial** — job control + status + camera only; heaters/fans/macros/history/timelapse "deliberately left unimplemented" (`BambuPrinterService.kt:20-26`) |
| Prusa Link | HTTP + Digest Auth | **Partial** — start/pause/resume/cancel + status; no camera, no file metadata, no heater/fan control |

Elegoo Centauri Carbon is **not** a `PrinterKind` — it's a `SlicingPrinterModel` layered on Moonraker, distinguished only by slicer-profile family + a live firmware-generation check (`FirmwareIdentity.kt`). **This dual-enum shape (`PrinterKind` for transport, `SlicingPrinterModel` for slicing) is a real architectural seam** — see §11.

No LAN auto-discovery, no QR pairing, no cloud-account pairing anywhere (`AddPrinterWizard.kt`, 4-step manual-entry flow only). **Missing**, not partial.

No jog/movement controls, no filament load/unload command, no bed-leveling *trigger* (mesh is read-only-visualized) found anywhere in the control surface — real, load-bearing gaps for a "full printer control" claim.

### 2.8 Monitoring, camera, notifications — **Partial to Complete, unevenly**
Live status: 2s foreground polling per saved printer (`SavedPrinterMonitor.kt`) → real progress/layer/temps/duration, no server-reported "time remaining" (client-derived from slicer metadata). Background: a single app-wide foreground `Service` (`PrintMonitorService.kt`), opt-in, 20s interval, 6 real alert kinds (`COMPLETED/ERROR/CANCELLED/OFFLINE/BACK_ONLINE/PAUSED`) — filament-runout alerting is explicitly absent (no sensor field to diff against yet, `PrintAlerts.kt:14-16`). Camera: WebRTC + MJPEG fallback, real; timelapse *playback/download* is real, timelapse *triggering* (`RENDER_TIMELAPSE`) is not implemented anywhere — the app only consumes clips the Moonraker plugin already rendered. **AI failure/spaghetti detection: confirmed entirely absent**, zero references anywhere.

### 2.9 3D workspace / slicing (my own work this session, cross-checked) — **the strongest existing subsystem**
- Import: STL/3MF/OBJ only (`SlicingCoordinator.kt:112-120`), via share-intent or `OpenDocument()` file picker. **3MF is flattened**: both preview (`load_mesh_preview`) and paint-session code call `model.mesh()`, which merges every object into one `TriangleMesh` — per-object structure, per-object color, and any 3MF project metadata do not survive that call today. **No STEP support.**
- Real GLES30 3D viewer (`ModelViewer.kt`) with orbit/pan/zoom, a real Transform mode (move/rotate Z/uniform-scale, mathematically identical between GL preview and the native slice transform), and real support painting (`AABBMesh`/`TriangleSelector`, the same machinery upstream OrcaSlicer's own GUI uses).
- Real build-volume bounds checking against the actual per-printer `machine.json` bed shape (`BedShape.kt`), gating the real Slice action.
- Slicing settings: 3 fields only (layer height, infill %, supports on/off — `SliceCustomization.kt`), no simple/advanced tiering.
- Real post-slice 3D toolpath preview with a layer slider and real G-code-footer-parsed stats (time/filament) — `SlicedPreview.kt`, `GcodeStats.kt`.
- **Single object, single material, single plate only** for the share-intent/Prepare-tab flow described above — that flow itself is unchanged. **A separate multi-object flow now exists (Phase 1, WO-17, Files → Projects)**: real add/duplicate/remove of objects on a shared plate, each with its own move/rotate/scale, rendered by a genuinely separate `ProjectGLRenderer` (not a retrofit of `ModelViewer`'s `MeshGLRenderer`). Still no auto-arrange, no collision detection, and nothing on that screen calls the slicer yet (`engine::slice_multi_object` exists and is device-tested, but isn't wired into this UI) — see `docs/WORK_ORDER.md`.
- No filament/material model connects to slicing at all — the three settings above are the entire "material" surface.
- Spoolman integration (`Spoolman.kt`) is **read-only inventory display**, structurally disconnected from `SlicingCoordinator`/`SliceAndPrintPanel` — it cannot influence a slice today.
- Project persistence now exists as of Phase 1/WO-17 (§2.3) — no model library/Discover (confirmed absent, only the one `MainActivity.kt:643` placeholder string exists), no print-history/reprint concept beyond a raw pass-through of the printer's own job log.

---

## 3. Competitor-Derived Capability Map (patterns, not clones)

| Source | What to take | What to explicitly not copy |
|---|---|---|
| Prusa EasyPrint | Real on-device model prep: move/rotate/scale/place-on-face/duplicate/arrange, multi-object plates, progressive quality controls, multicolor painting | PrusaSlicer's own visual identity/branding; its cloud-slicing dependency (this app should slice locally by default, see §10) |
| Bambu Handy/MakerWorld | Printer/material-aware one-tap printing, AMS-style tool/material mapping *as a pattern* (not the AMS name/UX), polished live dashboard, print history | Account-gated cloud slicing as a requirement; MakerWorld's marketplace *infrastructure* (a real backend this repo doesn't have and shouldn't casually acquire) |
| Creality Cloud | Fleet/multi-printer dashboard concepts, richer mobile slicer settings, AI failure-detection *as an eventual capability-gated option* | Implying AI detection exists before it's actually built (see §9 rule: no fake functionality) |
| Anycubic App | Compact mobile slicing density, filament-profile sync, calibration workflows | — |
| Elegoo Matrix | Profile-driven "just print" simplicity, repeat-printing UX | — |
| Snapmaker App/Space | Printer-aware model compatibility checks, multicolor project handling matching this app's *own* Snapmaker U1 integration | — |

---

## 4. Consolidated Product Requirements (what "done" looks like)

A beginner can: import a model → see it on a real bed → tap Print → watch it print, live, in ≤3 real decisions (printer, material, quality preset) when defaults are trustworthy. An advanced user can: build a multi-object plate, assign colors per object, tune real slicer parameters grouped sanely, inspect a real toolpath preview, and manage several printers from one dashboard. Nothing in the UI ever performs an operation it doesn't actually implement (§9's own "no fake functionality" rule is treated as absolute, matching this app's existing engineering discipline — every WORK_ORDER.md entry this session documents real device verification specifically because the owner has zero tolerance for false claims).

---

## 5. Recommended Information Architecture

The competitor-derived structure proposed in the prompt (**Discover / Prepare / Printers / Activity / Profile**) is directionally right but should be **sequenced, not delivered at once**, and **Discover should not exist until an owner decision authorizes a cloud/community backend** (§14). Recommended:

**Near-term (Phases 1–7, no new top-level tabs required beyond renaming):**
- **Home** → becomes the multi-printer dashboard (already close to this).
- **Control** → unchanged.
- **Files** → unchanged (gains reprint, §2.9).
- **Prepare** → becomes the real workspace: `Model → Arrange → Material → Settings → Preview → Print` (matches the prompt's proposed flow inside Prepare — this part of the prompt's IA is directly adopted).
- **Settings** → gains a real Filaments/Materials section (Phase 3) and printer-capability display (Phase 2).

**Later (Phase 10, only if approved):** a 6th tab, **Discover**, added last, never required for the other 5 to function.

This avoids restructuring navigation twice and keeps every phase shippable on its own.

---

## 6. Proposed Technical Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Compose UI (per-tab screens)                                     │
│  Home │ Control │ Files │ Prepare (Model/Arrange/Material/        │
│         Settings/Preview/Print) │ Settings │ [Discover — Phase 10]│
├─────────────────────────────────────────────────────────────────┤
│ ViewModel layer                                                   │
│  PrinterModel (existing, real) │ NEW: ProjectModel (Phase 1)      │
│  NEW: MaterialModel (Phase 3)                                     │
├─────────────────────────────────────────────────────────────────┤
│ Domain / capability layer                                         │
│  NEW: PrinterCapabilities (Phase 2, replaces PrinterKind +         │
│         SlicingPrinterModel duality)                               │
│  NEW: Project (Phase 1) — multi-object scene, transforms,          │
│         material assignments, settings, persisted                  │
│  NEW: MaterialProfile / ToolMapping (Phase 3/8)                    │
├─────────────────────────────────────────────────────────────────┤
│ Persistence                                                       │
│  SharedPreferences (existing, printer profiles)                   │
│  NEW: Room database (Phase 1) — projects, print history, materials │
├─────────────────────────────────────────────────────────────────┤
│ Native slicing engine (existing, real, AGPL-3.0-or-later)          │
│  JNI bridge → OrcaSlicer-derived libslic3r                         │
│  EXTEND (not replace): multi-object apply, per-object material     │
├─────────────────────────────────────────────────────────────────┤
│ Printer transport layer (existing, real, per-vendor)                │
│  Moonraker HTTP │ Bambu MQTT/FTPS │ Prusa Link HTTP │ (extensible) │
└─────────────────────────────────────────────────────────────────┘
```

Nothing here proposes ripping out working code. The native engine, the GL viewer, the printer services, and the ViewModel/StateFlow pattern are all sound and are extended, not replaced.

---

## 6a. Multi-Platform Strategy — Android, Desktop (Windows/Linux); iOS and macOS deferred (revised 2026-09-22)

This section was originally written when the owner confirmed the target was genuinely **Android + iOS + a multi-OS Desktop companion app**, not Android-only. **Later the same day, the owner narrowed that: iOS (Phase 12) is deferred entirely, and macOS is deferred out of the Desktop port specifically** — active target platforms are now **Android + Desktop (Windows/Linux)**. The iOS and macOS research/architecture below is left in place rather than deleted, since it's real, verified work that stays directly useful if either platform is picked back up later — but none of it is active scope right now. Sections below are marked inline where they describe deferred-platform work.

### UI / shared-logic framework — recommend Kotlin Multiplatform + Compose Multiplatform

**Verified**: Compose Multiplatform for iOS reached stable, production-ready status with CMP 1.8.0 (JetBrains, May 2025) with feature parity with Jetpack Compose for common use cases, type-safe navigation, and first-class accessibility support (VoiceOver/AssistiveTouch on iOS). Compose for Desktop has been stable considerably longer. Real companies (Netflix, Cash App, McDonald's, per JetBrains' own case studies) run Kotlin Multiplatform in production today.

This is the natural extension of the existing codebase, not a new direction: this app is **already 100% Kotlin and already Jetpack Compose**. Kotlin Multiplatform (KMP) lets the existing domain/data layer — `PrinterModel`, `ScreenState`, the Moonraker/Bambu MQTT/Prusa Link/Snapmaker protocol clients, `Project`/material models (Phase 1/3) — become a genuinely shared `:shared` module consumed by Android, iOS, and Desktop targets, while Compose Multiplatform lets most of the *UI* itself (not just logic) be shared too, with platform-specific code only where a real platform difference exists (the native engine bridge, camera/file-picker platform APIs, notifications).

**Explicitly not recommended**: Flutter or React Native. Both would mean discarding the existing Kotlin/Compose codebase for a full rewrite in a different language, for no capability gain — neither framework changes the hard part of this project (the native slicing engine, below), and this app's own `THIRD_PARTY_NOTICES.md` already documents that its own reference app (Helix) used React Native/Expo and *still* had to drop into platform-specific native code (Kotlin, for its Android-only slicing bridge) for exactly the piece that matters most here — real evidence that a cross-platform UI framework doesn't make the native-engine problem go away, whichever one is chosen.

### Native slicing engine — per platform, verified against real prior art

**Android**: unchanged, already real (§7).

**Desktop (Windows/Linux — active scope; macOS deferred)**: the easiest of the remaining targets, and easier than Android was. Upstream OrcaSlicer *is* a desktop application first — Windows/Linux (and macOS, were it in scope) are its primary, natively-supported build targets already, with no cross-compilation novelty required (unlike Android, which had "no existing Android port... genuinely new native cross-compilation work" per this app's own WO-13 history). The practical decision is only *how* to embed it: link the same headless (`SLIC3R_GUI=OFF`) `libslic3r` build pattern this app already uses for Android into a JVM/Kotlin-Native desktop target (via JNI-equivalent interop or a small C API, matching this app's existing `slic3r_engine.cpp` bridge shape), rather than bundling the full upstream wxWidgets desktop GUI this app doesn't need or want (it has its own UI). macOS was deferred for scope reasons, not a technical blocker — the same headless build pattern applies there too if it's picked back up.

**iOS — deferred, 2026-09-22.** Kept below for reference only; not active scope. Was assessed as harder than Desktop, comparable in kind to the Android effort — but with real, verified prior art to learn from, which meaningfully de-risks it if picked back up: **`NilsN3DP/SlicerMobile`** (GitHub, AGPL-3.0, same license family this app already handles) ships the actual PrusaSlicer 2.9.6 engine — the same `libslic3r` lineage OrcaSlicer forked from — on iPad, iPhone, *and* Android, via a real C API wrapper and a SwiftUI iOS app. A second independent project, `adickin/ISlicer`, does the same thing (STL load → slice via `libslic3r` → export G-code to the Files app). Real community discussion (Prusa forum) on why porting to iOS is painful specifically identifies **wxWidgets** — the desktop GUI toolkit — as "the majority of the work," not `libslic3r`/PrusaSlicer's core itself. **This app has already sidestepped that exact pain point**: its Android engine is built headless (`SLIC3R_GUI=OFF`) with no wxWidgets dependency at all, so the one part of iOS porting that real-world reports flag as genuinely hard doesn't apply here. The realistic remaining iOS work, were it resumed, is a normal (if substantial) cross-compilation exercise — Boost/CGAL/GMP/MPFR/OpenVDB/OCCT for iOS device (arm64) and simulator targets via an Xcode/CMake toolchain, following the same shape of effort WO-13 already completed once for Android — not an unprecedented one.

### Module restructuring — real, explicit scope, not hand-waved

Today: a single Gradle `:app` Android module (§2.1). Multi-platform (even Android + Desktop only) means a real module-boundary migration: a `:shared` KMP module (domain/data/protocol clients/native-engine interop abstraction), plus `:androidApp` and `:desktopApp` targets — `:iosApp` is deferred along with iOS itself, but the `:shared` module is still worth designing to not preclude adding it later. This is itself a meaningful engineering task — budget it as real scope, not a build-file tweak, and do it *before* Desktop UI work starts, not concurrently with it.

### AGPL + Apple App Store distribution — researched in depth, 2026-09-22 (reference only — iOS is deferred)

This was flagged as an open legal question while iOS was in scope; it's preserved here since the research is real and directly reusable if iOS is picked back up, though a real lawyer would still be the only authoritative answer at that point.

**The actual conflict, precisely**: Apple's minimum end-user terms require the license granted to an app's end user be "non-transferable," and (A)GPL requires that recipients retain the freedom to redistribute the software themselves. The FSF's own published position is that Apple's terms, layered on top, take away freedoms the (A)GPL is supposed to guarantee downstream recipients — this is a real, still-unresolved tension in general, not a settled non-issue (FSF: "More about the App Store GPL Enforcement").

**But the practical picture is more nuanced than "AGPL apps can't be on the App Store," and the nuance matters directly for this app**: verified live today (2026-09-22), both **Element Classic** and **Element X** — real, current, AGPL-3.0-licensed Matrix chat apps — are published on the Apple App Store right now, by **Vector Creations Limited**, the company that primarily authors and stewards Element's own codebase. The distinguishing factor, confirmed by checking who actually publishes it: **a project's own primary copyright holder can make this call for their own code** (almost certainly backed by a contributor agreement that gives them the standing to do so) — a materially different, lower-risk position than a *downstream* distributor shipping *someone else's* multi-contributor AGPL codebase through the App Store without that codebase's copyright holders' explicit agreement. Real GitHub issues from other projects in exactly this downstream position (`element-hq/element-ios#7839`, asking Element to clarify its own App Store approach for a *fork*; `openzigs/onyourleft#432`, titled "adopt a §7 additional permission while there is still one copyright holder") confirm this is a live, actively-discussed distinction in the open-source community, not something this plan is inventing.

**This app's actual position is the riskier one, precisely stated**: the native slicing engine is not this app's owner's own original code — it's derived from upstream OrcaSlicer, a large, independently-governed, multi-contributor AGPL-3.0-or-later project this app's owner does not control the copyright of. There is no realistic path to a blanket App Store exception without OrcaSlicer's own maintainers/contributor base granting one (a real §7 "additional permission," which (A)GPLv3 explicitly allows a copyright holder to add) — and nothing found in this research indicates OrcaSlicer has done so.

**Concrete, real options, not a single verdict** (final call is a legal one, not a technical one):
1. **Ask OrcaSlicer's actual maintainers directly whether they'd grant a §7 App Store exception.** Cheap to ask, and the honest first step — this plan should not assume the answer either way without actually asking.
2. **Ship iOS via TestFlight / ad-hoc / enterprise distribution, not the public App Store.** Sidesteps the conflict entirely (Apple's restrictive EULA terms are specifically an App Store mechanism) — the safe default absent an explicit exception, already this plan's stated fallback (§22 question 8).
3. **A "thin app + separately-fetched engine" architecture**: ship an iOS app binary through the App Store that does not itself bundle the AGPL-covered native engine, with the engine obtained by the user through a separate, non-App-Store channel (a real pattern other projects facing this exact tension have used) — real added complexity and a worse day-one experience, kept as a fallback option, not the default plan.
4. **Real legal review specific to this exact situation** (multi-contributor upstream AGPL dependency this app's owner doesn't hold copyright to, not self-authored code) remains the only way to get an authoritative answer for a public App Store release — this research sharpens the question a lawyer needs to answer, it doesn't replace needing one.

Moot while iOS is deferred (Desktop was never blocked by this — no locked store involved for Windows/Linux) — kept for reference in case iOS returns to scope, in which case it would only affect **public Apple App Store distribution specifically**, not development, TestFlight, or sideloaded distribution.

### Recommended sequencing — do not block the existing Android roadmap

The existing Android roadmap (§16) remains the right path for reaching a *complete core product* on Android — a full Desktop expansion before that exists would mean building (and maintaining) two incomplete apps instead of one complete one. Recommended approach:
1. **Fold the `:shared` KMP module extraction into/immediately after Phase 0** — while the native multi-object bridge work and CI foundation are being built anyway, structure the domain/data layer (printer protocol clients, `ScreenState`-equivalent, and, once they exist, `Project`/`PrinterCapabilities`/`MaterialProfile` from Phases 1–3) as genuinely platform-agnostic Kotlin from the start, rather than retrofitting it later. This is real, moderate extra scope in Phase 0, not free, but far cheaper than a separate extraction pass after Phases 1–9 are already built Android-only — and keeps the door open to adding iOS back later without a second extraction pass.
2. **Continue Phases 1–9 Android-first** as already scoped — the core product (multi-object workspace, materials, slicing UX, live control) should reach real maturity on one platform before multiplying platforms.
3. **Phase 13 — Desktop Port (Windows/Linux)**, sequenced after Phase 9, consuming the by-then-mature `:shared` module and Compose Multiplatform UI, plus its own native engine work (§6a above) and its own CI coverage (a conventional multi-OS GitHub Actions runner matrix, not AWS Device Farm, which targets mobile devices specifically). **Phase 12 (iOS) is deferred and not currently sequenced** — its dependency on Phase 9 and Phase 0's `:shared` extraction (documented in §17) still applies whenever it's picked back up.
4. Phases 10 (Discover) and 11 (Account/Sync) become genuinely cross-platform benefits once §6a's shared module exists — a `:shared`-module-based sync/Discover client works identically across Android and Desktop with no per-platform reimplementation, which is itself a real argument for sequencing the KMP extraction (step 1) before, not after, Phases 10/11 land.

---

## 7. Slicer Engine — Recommendation and Alternatives Considered

**Recommendation: keep and extend the existing on-device OrcaSlicer-derived engine. Do not add a cloud-slicing backend as a requirement.**

| Option | Verdict | Why |
|---|---|---|
| **Keep native on-device engine (current)** | **Recommended** | Already real, already AGPL-compliant (this app is itself open-source-compatible via its existing `THIRD_PARTY_NOTICES.md` practice), zero backend operating cost, works fully offline, matches the app's stated local-first identity, and already supports the hardest features (support painting, transform-aware slicing, per-printer bed shapes). Real cost: slicing is CPU/RAM-bound on the phone (already proven workable for real models this session) and slice latency scales with model complexity — acceptable for a mobile-first "prepare then print" flow that isn't real-time. |
| Embedded alternative engine (e.g. a from-scratch or different open-source slicer) | Rejected | No technical justification — the existing engine already does everything asked for in this plan's scope; switching would mean re-deriving all of WO-13/14/15's work for no capability gain. |
| Server/cloud slicing | Rejected as a *requirement*; **acceptable only as an explicitly optional, user-opt-in fallback** (e.g. for a very old/low-RAM device) | Directly contradicts the app's stated "no cloud account" identity; introduces real backend operating cost and a new privacy surface (uploading a user's model to a server) that must be opt-in, never default. If ever built, it must be a drop-in alternate implementation of the same `SlicingCoordinator` interface, gated behind a clearly-labeled setting, not a silent fallback. |
| Hybrid local/cloud | Rejected for v1 | Only makes sense once a cloud backend already exists for a real reason (Discover/community, Phase 10) — building it earlier is complexity with no near-term payoff. |

**Licensing implication, made explicit**: because the native engine is AGPL-3.0-or-later, this repository is already, in effect, obligated to make its own corresponding source available to anyone it's distributed to (AGPL's network-use clause is not triggered by local-only on-device use, but distributing the compiled app at all triggers standard AGPL source-availability obligations). This is already true today, not a new obligation created by this plan — but it does mean **any new native code added to the slicing path inherits the same AGPL obligations**, and any future *cloud* slicing service (if ever built) would trigger AGPL's network-interaction clause much more directly and must be reviewed with that in mind before being built.

---

## 8. 3D Rendering — Recommendation

**Keep raw GLES30** (current `ModelViewer.kt`/`SlicedPreview.kt` approach), extended into a proper (if lightweight) scene model: a `List<RenderableObject>` (mesh + own model matrix + own material color + own selection state) instead of the current single-mesh assumption. This directly unlocks multi-object rendering (Phase 1) without adding a second heavy native rendering dependency (Filament/Sceneform were already correctly rejected in this codebase's own architecture notes, and that reasoning still holds — no new requirement in this plan needs PBR-quality lighting). If a future Discover feed (Phase 10) needs marketing-quality static renders, generate those via the *existing* CPU rasterizer (`thumbnail_render.cpp`) at higher resolution rather than adding a new rendering stack.

---

## 9. File / Project Model

**Canonical formats**: STL, 3MF, OBJ remain the supported import set (STEP is out of scope — no open, license-clean, mobile-viable STEP kernel is bundled today, and OrcaSlicer's own desktop STEP support depends on OCCT, which — per `THIRD_PARTY_NOTICES.md` — is already bundled for slicing but not currently exercised for import; wiring STEP import is a real, non-trivial follow-on, not assumed in this plan's phases).

**3MF handling must change**: today `model.mesh()` flattens everything before it ever reaches the viewer or the slicer. Phase 1 must stop doing this — the native `load_and_place_model`/paint-session/slice paths need to preserve `Model::objects` as a real list (libslic3r already supports this; the flattening is this app's own choice, not an engine limitation) so per-object transform, per-object material, and (for 3MF specifically) imported per-object metadata survive.

**New `Project` entity** (Phase 1, persisted in Room):
```
Project(
  id, name, createdAt, modifiedAt,
  objects: List<ProjectObject>,   // multi-object, Phase 1
  plates: List<Plate>,            // multi-plate, Phase 1/9
  targetPrinterId: String?,       // Phase 2 capability reference
  materialAssignments: Map<objectId, MaterialAssignment>, // Phase 3/8
  settings: SliceSettings,        // basic/advanced, Phase 4/9
)
ProjectObject(
  id, sourceFile: Uri, plateId,
  transform: ObjectTransform,     // already exists as ModelTransform — promote to per-object
  materialId: String?,
)
```
This is additive to, not a replacement of, the existing `ModelTransform`/`BedShape` work — it wraps today's single-object logic in a list.

---

## 10. Printer Abstraction Model

Replace the `PrinterKind` + `SlicingPrinterModel` duality (§2.7) with one real capability object, resolved once per printer (at pairing time, refreshed on reconnect where the protocol allows querying it):

```
PrinterCapabilities(
  transport: PrinterTransport,        // Moonraker | BambuMqtt | PrusaLink | (extensible)
  buildVolume: Vec3,                   // from bed_shape / printable_area, already read today
  bedShape: BedShape,                  // already real (BedShape.kt)
  nozzleDiameterMm: Double,
  toolCount: Int,                      // 1 today for every integration; >1 unlocks Phase 8 UI
  hasCamera: Boolean,
  hasFilamentSensor: Boolean,          // gates real runout alerting, closes a documented gap
  supportsJog: Boolean,
  supportsBedLevelingTrigger: Boolean,
  supportsMacros: Boolean,
  supportsTimelapseTrigger: Boolean,
  bundledSlicerProfile: SlicingProfilePack?, // existing SlicingProfilePacks.kt, unchanged
)
```
Every `when(profile.kind)`/`nonKlipper`-style conditional scattered across `MainActivity.kt` and the printer-control panels should read this object instead. This is the single most important refactor for "support more printer brands without rewriting the app" (§13's requirement) — it is explicitly scoped as Phase 2, before materials or advanced slicing, because almost everything downstream (jog controls, filament-sensor-driven alerts, per-tool material UI) depends on knowing what a given printer can actually do.

---

## 11. Material / Filament Model

Promote material from "three slicer settings" to a first-class entity, while explicitly supporting the full range of real hardware this plan must not hardcode around (§5 of the original brief): single-material, independent multi-toolhead, AMS/CFS-style systems, and true multicolor systems like the Snapmaker U1 this app already integrates with.

```
MaterialProfile(id, type, manufacturer, colorHex, tempNozzle, tempBed, source: Bundled|Spoolman|Custom)
ToolSlot(index, capability: SingleExtruder|IndependentTool|AmsSlot)   // from PrinterCapabilities.toolCount
MaterialAssignment(objectId, toolSlotIndex, materialProfileId)
```
Spoolman (already real, read-only today) becomes a **source** for `MaterialProfile` rather than a disconnected inventory viewer — Phase 3 wires it into this model without changing what Spoolman itself does. Automatic closest-color matching, purge/flush estimation, and toolchange visualization (Phase 8) are all downstream consumers of `MaterialAssignment`, not new data models of their own.

---

## 12. Backend / Cloud Requirements — What Genuinely Needs One

| Capability | Needs a backend? | Notes |
|---|---|---|
| Slicing | **No** | Native on-device, already real |
| Printer control/monitoring | **No** | Direct device/LAN protocols, already real |
| Materials/multicolor | **No** | Local data model + existing Spoolman LAN integration |
| Print history/reprint | **No** | Local Room DB (Phase 1 infrastructure) |
| Multi-printer dashboard | **No** | Already local |
| Discover/model library/marketplace | **Revised: No backend of this app's own required for a basic v1** | MyMiniFactory's public API (§16 Phase 10) is designed for direct third-party-app consumption (OAuth 2.0 client running in the mobile app itself, no server intermediary needed) — a thin client against it needs no new backend at all. A backend only becomes necessary if this app later wants its own search index, caching layer, ratings, or a self-hosted catalog beyond what MyMiniFactory's API already provides. Still Phase 10 only, gated on owner approval. |
| Cross-device project sync | **Yes — owner-approved 2026-09-22, real new backend required** | The one capability in this whole plan that genuinely needs an account system of this app's own. See Phase 11, §16. |
| Camera relay (remote viewing off-LAN) | **Revised: No — already solved** | Verified in the real code: `Moonraker.kt:79-87` already whitelists Tailscale's own address ranges (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`, `*.ts.net`) for plain `http://`, specifically because a Tailscale address "never leaves your private tailnet" — and camera streaming goes through the same connection as everything else, no separate local-only gate. A user with Tailscale installed already gets remote camera access today, with zero relay server or backend of this app's own. **Not a gap. Removed from the open-questions list.** A *different*, narrower feature — turnkey remote access for a user unwilling to install Tailscale themselves — remains genuinely out of scope and is not currently planned. |

**Phases 0 through 9 require no backend.** Phase 10 (Discover), if built, does not strictly require one either for a v1 — MyMiniFactory's public API (§16) is designed for a client app to call directly via OAuth 2.0. **Phase 11 (cross-device project sync) is the one phase in this entire plan that requires this app to stand up and operate its own backend and account system** — see Phase 11, §16, for the real architecture options and recommendation.

---

## 13. Security / Privacy Implications

The app's existing model (credentials in encrypted storage, everything else in-repo `SharedPreferences`, no telemetry, no account) should be the **default privacy posture for every new subsystem**, not something bolted on later:
- `Project`/material data in the new Room DB: local-only by default. Cross-device sync (Phase 11, owner-approved) is opt-in — a project is never uploaded anywhere until the user explicitly signs in and enables sync for it, matching this app's existing "explain failures / preserve intent" discipline; a user who never opts in gets identical local-only behavior to today.
- Any future cloud slicing fallback (§7): must upload nothing without explicit per-action user confirmation, and must say so in the UI at the point of use, matching this app's existing "explain failures / preserve intent" discipline.
- Printer credentials: continue using the existing encrypted-store pattern for anything new (e.g. a future cloud-relay auth token, if Phase 10's camera-relay is ever built) — never add a second, less-protected credential path.

---

## 14. Gap Matrix

Legend — Existing app: Complete / Partial / Mock-UI-only / Backend-only / Missing. Target: P0 / P1 / P2 / P3 / Not planned.

**Note, 2026-09-22**: this matrix (like the rest of §2-§14) was audited at a point in time and
isn't kept in sync line-by-line as Phase 1 lands. `docs/WORK_ORDER.md`'s WO-17 entries are the
current, authoritative status for the "Multi-object plate/arrange" and "Project persistence" rows
below — both moved from Missing to Partial this session (a real Room-backed `Project`/
`ProjectObject` workspace with add/duplicate/remove/placement exists at Files → Projects; manual
arrange and slicing from that screen are still open).

| Capability | Existing app | Prusa/EasyPrint | Bambu Handy | Creality Cloud | Anycubic | Elegoo Matrix | Snapmaker | **Target** |
|---|---|---|---|---|---|---|---|---|
| On-device real slicing | **Complete** | cloud-assisted | n/a (profile-driven) | cloud | app-side | n/a | app-side | **P0 (keep, extend)** |
| Move/rotate/scale | **Complete** | yes | limited | yes | yes | limited | yes | **P0 (multi-object)** |
| Multi-object plate/arrange | **Partial (WO-17: add/duplicate/remove/placement; no auto-arrange, no slicing from this screen yet)** | yes | yes | yes | yes | limited | yes | **P0** |
| Multi-plate | **Missing** | yes | yes | limited | no | no | no | **P1** |
| Support painting | **Complete** | yes | limited | limited | limited | no | yes | **P0 (keep)** |
| Build-volume bounds check | **Complete** | yes | yes | yes | yes | yes | yes | **P0 (keep, extend for multi-object)** |
| Basic/advanced slicing tiers | **Missing** | yes | n/a | yes | yes | n/a | yes | **P0 basic / P2 advanced** |
| Sliced 3D preview + stats | **Complete** | yes | yes | yes | yes | limited | yes | **P0 (keep, extend per-material)** |
| Printer capability abstraction | **Missing (2 conflicting enums)** | n/a | yes | yes | yes | yes | yes | **P0** |
| Printer discovery (LAN/QR) | **Missing** | yes | yes | yes | yes | yes | yes | **P1** |
| Jog / manual move | **Missing** | yes | yes | yes | yes | yes | yes | **P1** |
| Bed-leveling trigger | **Missing (read-only viz)** | yes | yes | yes | yes | yes | yes | **P1** |
| Material profile model | **Missing** | yes | yes | yes | yes | yes | yes | **P0** |
| Spoolman → slicing integration | **Backend-only (disconnected)** | n/a | n/a | n/a | n/a | n/a | n/a | **P0 (wire it in)** |
| Multicolor/multi-tool mapping | **Missing** | yes | yes (AMS) | yes | yes | limited | yes (U1) | **P1/P2** |
| Toolchange/purge visualization | **Missing** | yes | yes | limited | limited | no | yes | **P2** |
| Live printer dashboard | **Partial** | yes | yes | yes | yes | yes | yes | **P0 (extend: multi-printer)** |
| Camera live view | **Complete (2 protocols)** | yes | yes | yes | yes | yes | yes | **P0 (keep)** |
| Timelapse trigger | **Missing (playback only)** | yes | yes | yes | limited | no | limited | **P1** |
| AI failure detection | **Missing** | no | limited | yes | limited | no | no | **P2, capability-gated, honest-or-absent** |
| Notifications (typed) | **Partial (6 of ~9 types)** | yes | yes | yes | yes | yes | yes | **P0/P1** |
| Print history w/ reprint | **Backend-only (no reprint)** | yes | yes | yes | yes | yes | yes | **P1** |
| Project persistence | **Partial (WO-17: Room-backed, survives process death; no rename/delete from the project list yet)** | yes | yes | yes | yes | yes | yes | **P0** |
| Multi-printer dashboard | **Complete (flat list)** | n/a | yes | yes | limited | limited | limited | **P0 (keep)** |
| Account/cloud sync | **Missing (by design)** | yes | yes | yes | yes | yes | yes | **P1 — owner-approved 2026-09-22 (Phase 11)** |
| Model discover/marketplace | **Missing** | yes | yes | yes | limited | limited | yes | **P3, owner-gated** |
| STEP import | **Missing** | no | no | no | no | no | no | **Not planned** |

---

## 15. Prioritization

**P0 — Core product** (required to truthfully call this a consumer mobile slicer): multi-object build plate, project persistence, real material model wired into slicing, printer capability abstraction, basic-tier slicing UX, extended bounds checking, multi-printer dashboard (already have, keep).

**P1 — Strong consumer experience**: printer discovery (LAN/QR), jog/bed-leveling controls, timelapse triggering, print history with reprint, richer notification coverage, multi-plate, **account & cross-device project sync (Phase 11, owner-approved 2026-09-22)**.

**P2 — Advanced**: full multicolor/tool mapping, toolchange/purge visualization, advanced slicing tier, capability-gated failure detection.

**P3 — Ecosystem expansion**: Discover/model library (Phase 10, integration target owner-approved, phase itself still gated), a possible future marketplace beyond browsing/downloading. Remote camera relay is **not** in this list — already solved today via Tailscale (§12), not a planned feature.

**Platform expansion (owner-confirmed target, tracked separately from the P0–P3 feature tiers above, since it's a platform axis, not a feature)**: Desktop (Windows/Linux, Phase 13) is **owner-confirmed as a genuine target**, sequenced after Android reaches P0+P1 core-product maturity (Phase 9), not before — see §6a and §16. iOS (Phase 12) and macOS were both deferred, 2026-09-22 (§6a) — not part of the active platform axis right now. Treat "which platforms" and "which features" as two independent prioritization axes: a feature's P0–P3 tier applies once ported to each platform, it doesn't need re-deciding per platform.

---

## 16. Phased Implementation Roadmap

Each phase is independently shippable and testable. No phase requires a backend (see §12) through Phase 9.

### Phase 0 — Foundation Audit & Prerequisites
**Objective**: remove technical debt that would block every later phase.
**Scope**: (a) add CI (§2.4 gap — no `.github/workflows` exists; at minimum run `testDebugUnitTest`/`lintDebug`/`assembleDebug` on push), with the real instrumented suite (136 `@Test` methods today) running against **AWS Device Farm** (owner has access, confirmed 2026-09-22) instead of only ever a single physical device by hand — real device/OS-version fragmentation coverage this app has never had, and would have caught at least one real bug from this session on its own (the AGP auto-uninstall issue was device-agnostic, but the repeated dozing/lock-screen interference during manual testing is exactly the kind of noise a device farm's controlled environment avoids); (b) introduce Room and the `Project`/`ProjectObject` entities (empty, unused yet) as pure infrastructure; (c) stop flattening 3MF/multi-object models in the native bridge (`load_and_place_model` keeps `Model::objects` as a list instead of calling `model.mesh()` early) — this is the one native change every later phase depends on.
**Architecture changes**: native bridge signature changes (additive — new multi-object-aware functions alongside, not replacing, today's single-object ones, so nothing already shipped breaks). New Room module. New CI pipeline: build → upload APK + test APK to Device Farm → run instrumented suite across a real device pool → gate merge on results.
**Data model changes**: `Project` schema (§9), empty/unused this phase.
**API changes**: none external.
**UI/screens affected**: none visible to the user.
**Tests**: CI itself is the test; Room migration tests; a native test proving multi-object `.3mf` now round-trips per-object (extend `MeshPreviewDeviceTest`-style coverage); the existing instrumented suite re-verified green across Device Farm's device pool, not just the one physical device used throughout this session.
**Acceptance criteria**: CI green on a real PR, including a real Device Farm run across multiple device models; a multi-object 3MF fixture loads with >1 object reported by the native layer (even though nothing in the UI uses that yet).
**Risks**: **two separate problems, both must be solved, neither solves the other.** (1) The native `ORCASLICER_ENGINE_ROOT` external dependency (§2.1) has no documented CI story — CI cannot *build* the native target at all until this is solved (vendor a prebuilt artifact, or a self-hosted runner with the dependency preinstalled, per open question 5, §22). (2) Even once CI can build, Device Farm only runs an already-built APK — it answers "where do tests run," not "how does the native build happen." **This remains the single largest Phase 0 risk**, now split into two concrete sub-problems instead of one vague one. Device Farm explicitly does **not** cover printer-hardware integration testing (phones/tablets only) — that stays real-device-against-real-printer, unchanged from this project's existing practice.
**Non-goals**: no user-visible change.

### Phase 1 — Project & Multi-Object Workspace
**Objective**: turn the existing single-object viewer into a real multi-object build plate with persistence.
**Scope**: object list, add/duplicate/delete, per-object transform (promote existing `ModelTransform` to per-object), auto-arrange (a real bin-packing pass, not a stub), collision detection between objects, `Project` save/load/rename/drafts.
**Dependencies**: Phase 0's multi-object-aware native bridge and Room schema.
**Architecture changes**: `MeshGLRenderer` becomes a `List<RenderableObject>` renderer (§8). `SliceAndPrintPanel.kt`'s state moves from `remember(uri)` to a `ProjectViewModel` backed by Room.
**Data model changes**: `Project`/`ProjectObject` go from unused to load-bearing.
**API changes**: none external.
**UI/screens affected**: Prepare tab's Model/Arrange steps rebuilt; Files tab gains "saved projects."
**Tests**: arrange/collision unit tests with deterministic fixtures; project save/restore across simulated process death; large-model/memory-pressure device tests.
**Acceptance criteria**: import 2+ objects, arrange, background the app, reopen, project state intact; slice a real multi-object plate and get correct per-object G-code.
**Risks**: auto-arrange is a real algorithmic task (2D bin packing with rotation) — budget real time for it, don't underscope.
**Non-goals**: multi-plate (Phase 9), per-object material (Phase 3/8 — this phase's objects share one material).

### Phase 2 — Printer Capability Layer
**Objective**: replace `PrinterKind`/`SlicingPrinterModel` duality with the real `PrinterCapabilities` model (§10).
**Scope**: capability resolution per printer, migrate every conditional UI path (heater/fan/jog/camera/macro panels, Control tab) to read capabilities instead of ad-hoc kind checks.
**Dependencies**: none beyond current codebase — can run in parallel with Phase 1.
**Data model changes**: new `PrinterCapabilities`; `PrinterProfile` gains a reference to it.
**UI/screens affected**: Control tab, Settings/printer-management, Add Printer wizard (now also captures/derives capability info).
**Tests**: one capability-resolution test per existing vendor integration; UI tests confirming a capability-gated control (e.g. jog) is hidden for printers that lack it.
**Acceptance criteria**: adding a 5th printer vendor requires only a new `PrinterTransport` implementation and a capability-resolution function — zero new `when(kind)` branches in UI code.
**Risks**: real regression risk touching every existing control panel — needs the existing 100+ device tests re-run in full, not spot-checked.

### Phase 3 — Materials
**Objective**: make material a first-class, slicing-connected entity.
**Scope**: `MaterialProfile`/`ToolSlot`/`MaterialAssignment` (§11), wire Spoolman as a `MaterialProfile` source, single-material-per-project UI (multi-tool UI is Phase 8).
**Dependencies**: Phase 1 (`Project`), Phase 2 (`ToolSlot` capability count).
**UI/screens affected**: new Materials section in Settings; material picker in Prepare.
**Tests**: Spoolman-sourced profile round-trip; material selection actually changes slicer config overrides (extend existing `SliceCustomization` tests).
**Acceptance criteria**: pick a Spoolman spool, slice, confirm the resulting G-code's temperatures match that spool's profile.
**Risks**: Spoolman's own data model may not map 1:1 to every field `MaterialProfile` wants — expect some lossy defaulting, documented not hidden.

### Phase 4 — Basic Slicing (beginner tier)
**Objective**: real basic-mode settings (§4 of the original brief) with printer/material/model-aware defaults.
**Scope**: quality preset (Draft/Standard/Fine → real layer-height mapping), strength (infill %), supports on/auto/off, adhesion/brim, copies. Intelligent defaulting engine reading `PrinterCapabilities` + `MaterialProfile` + model geometry.
**Dependencies**: Phases 1–3.
**Tests**: golden-default tests (given printer X + material Y + model Z, defaults must match an approved table).
**Acceptance criteria**: a new user can import → accept defaults → reach Print in ≤3 taps for a typical model.

### Phase 5 — Slice Preview & Validation
**Objective**: extend the existing toolpath preview (already real) with per-material coloring and pre-slice validation.
**Scope**: continuous validation (bounds/collision — already real for single object, extend to multi-object) vs slice-time validation (profile/material/nozzle compatibility — new). Per-material toolpath coloring.
**Dependencies**: Phases 1–4.
**Acceptance criteria**: a genuinely invalid configuration (e.g. unavailable material) is caught before slicing starts, with an actionable message, not a native exception surfacing raw.

### Phase 6 — Print Pipeline
**Objective**: reliable slice → transfer → print across all 4 vendor integrations uniformly.
**Scope**: unify the currently-inconsistent upload/start paths (Bambu FTPS, Moonraker HTTP, Prusa Link) behind one `PrintPipeline` interface driven by `PrinterCapabilities`.
**Acceptance criteria**: the same Prepare→Print flow works identically (from the user's perspective) across all 4 vendors, even though the transport differs.

### Phase 7 — Live Printer Control
**Objective**: close the real control gaps found in the audit (§2.7): jog, filament load/unload, bed-leveling trigger, timelapse trigger — all capability-gated (Phase 2).
**Acceptance criteria**: on a printer that supports it, jog/bed-leveling/timelapse-trigger genuinely work against real hardware; on one that doesn't, the control simply doesn't appear (no dead buttons — §20).

### Phase 8 — Multicolor / Multimaterial
**Objective**: real tool/material mapping, per-object and per-painted-region material assignment, purge/flush estimation, toolchange visualization.
**Dependencies**: Phases 1–3 (project, capabilities, materials) must all be solid first — this is explicitly the highest-risk phase per the original brief, and this plan agrees: do it after the foundation, not concurrently with it.
**Acceptance criteria**: on the Snapmaker U1 (an integration this app already has), assign 2+ colors to a real model, slice, and confirm the G-code contains real toolchange sequences at the correct layers.

### Phase 9 — Advanced Slicing
**Objective**: progressive-disclosure expert controls (§4's advanced tier), multi-plate.
**Acceptance criteria**: every advanced control maps to a real, tested config override (no raw slicer-key dumping — grouped, explained, validated).

### Phase 10 — Discover / Community — **integration target owner-approved 2026-09-22; phase itself still sequenced after Phases 0–9, not scoped build-ready here**
**Objective**: a real (not backend-of-our-own-required, see §12) model discovery surface, built as a client against MyMiniFactory's public API.
**Integration target, verified 2026-09-22, owner-approved same day**: MyMiniFactory publishes a genuine, sanctioned third-party developer API (`MyMiniFactory/api-documentation` on GitHub) — real OAuth 2.0 authorization-code login, documented REST endpoints (`/search`, `/objects/{id}`, `/objects/{id}/files`, user objects/collections/comments), and per-object license metadata in the API response itself (remix-allowed / commercial-use-allowed / exclusive / paid-"store"-license flags) that a real UI can surface honestly rather than guess at. Published third-party guidelines require attribution (link back to the object's MyMiniFactory page, credit the creator) — a satisfiable requirement, not a gray-area risk. This is the recommended Phase 10 integration target. By contrast, **MakerWorld and Printables have no official third-party API** — both are reverse-engineered (undocumented internal GraphQL/REST, in MakerWorld's case requiring a WebView login-cookie hack and an interactive CAPTCHA-solving download flow, confirmed by reading Helix's own source, `FatBoy721/Helix`) — fragile, ToS-gray-area, and explicitly not recommended as the primary path; either could still be added later as an optional, clearly-labeled "unofficial, may break" source if ever justified.
**Watch item, not a plan**: MyMiniFactory acquired Thingiverse from Ultimaker on 2026-02-12 (confirmed via multiple independent outlets and the official announcement). The two platforms are explicitly stated to "remain independent," with no announced timeline or commitment to a unified catalog/API. If that changes, the existing MyMiniFactory integration would gain Thingiverse's much larger library at no additional engineering cost — worth re-checking before Phase 10 kicks off, but nothing to design around today.
**Non-goal for this plan**: this plan deliberately does not design the backend, data model, or moderation/ratings system for this phase — that is a separate planning exercise once the owner decides whether a cloud/account layer is wanted at all, given the app's stated identity (§2.6).

### Phase 11 — Account & Cross-Device Project Sync — **owner-approved 2026-09-22**
**Objective**: a project started on one device (phone/tablet) becomes visible and editable on another, for the same user.
**Why this is different from every other phase**: it's the one capability in this entire plan that cannot be satisfied locally or via a third-party OAuth pass-through (unlike Phase 10's MyMiniFactory integration, or remote camera/control access, which Tailscale already solves today, §12). Sync needs *somewhere* to put a project that isn't tied to one device — that requires this app to either run its own backend or delegate identity/storage to a provider, and either way means introducing the account system the app has explicitly avoided until now (§2.6). This is a real, deliberate exception to that identity, not a slippery-slope opening of it — Discover and remote access remain account-free.

**Architecture options considered:**

| Option | Verdict | Why |
|---|---|---|
| **Self-hosted minimal sync backend, owner-operated** (a small authenticated REST/WebSocket service storing `Project` blobs, keyed to an account) | **Recommended** | Matches the app's existing pattern of the owner running/maintaining real infrastructure (the `orcaslicer-android-engine` cross-compile host, the Bespok3d daemon work already in this codebase) rather than depending on a third party's data practices for a user's own project data. Full control over retention, encryption at rest, and what "delete my account" actually deletes. Real ongoing cost: the owner must run and secure a server, which is a genuine new operational commitment this app has never had. |
| Third-party Backend-as-a-Service (Firebase, Supabase, etc.) | Rejected as the default, acceptable as a *fallback if self-hosting proves impractical* | Fastest to build, but hands user project data to a third party's infrastructure and privacy practices — in real tension with "no cloud account, no telemetry" even though it's opt-in. If self-hosting turns out to be a real blocker (owner time/cost), revisit this specifically, not silently. |
| Piggyback identity (Sign in with Google/Apple) + owner-run storage only | Viable hybrid | Avoids building password/account-recovery flows from scratch (real, unglamorous work) while keeping the actual project *data* on infrastructure the owner controls. Worth strong consideration as a way to cut Phase 11's scope without handing data storage to a third party. |

**Scope**: account creation/login (recommend starting with the hybrid — piggyback identity for auth, owner-run storage for data), `Project` upload/download/conflict resolution (last-write-wins is an acceptable v1; real merge is a stretch goal, not required), an explicit, visible "synced" vs "local-only" project state so nothing silently leaves the device without the user knowing (matches §20's "preserve user intent" principle).
**Dependencies**: Phase 1 (`Project`/Room model must exist and be stable before anything syncs it).
**Data model changes**: `Project` gains `ownerAccountId`, `syncedAt`, `remoteRevision`. New server-side schema (out of scope for this document — a real, separate backend design task once this phase is actually scoped for build).
**API changes**: new — this is the first phase in the whole plan that introduces a first-party backend API surface for this app.
**UI/screens affected**: new account/login screen (a genuine first for this app), a "synced" indicator in the project list, Settings gains account management.
**Tests**: conflict-resolution fixtures (two devices editing the same project), account deletion actually deletes server-side data, offline-then-reconnect sync correctness, sync failure doesn't corrupt the local copy.
**Acceptance criteria**: create a project on device A, see it on device B under the same account, edit on B, see the edit reflected on A; deleting the account removes the server-side copy; losing network mid-sync never loses or corrupts the local project.
**Risks**: this is a real, ongoing operational commitment (a live backend to run, secure, and pay for) that nothing else in this plan requires — budget for it accordingly, and treat account-security (password reset, session handling, data-deletion correctness) with the same rigor as printer-credential storage already gets in this codebase (§13). Real scope-creep risk: "sync" tends to quietly grow into "sync everything" (materials, settings, print history) — this phase's acceptance criteria are scoped to `Project` only; expanding sync to other data is a separate, later decision.
**Non-goals**: real-time multi-user collaborative editing (out of scope — this is per-user sync, not shared editing); syncing printer credentials or profiles (those stay local/encrypted-on-device, matching the existing security model, §13).

### Phase 12 — iOS Port — **DEFERRED, owner decision 2026-09-22** (kept for reference, not active scope)
**Status**: iOS was confirmed as a target platform, then deferred the same day. Everything below is preserved as-is in case iOS is picked back up later; it is not currently sequenced into the roadmap. §17's dependency diagram no longer shows it as an active phase.
**Objective**: a real iOS app, sharing the `:shared` KMP module and (where practical) Compose Multiplatform UI extracted in Phase 0, not a from-scratch parallel codebase.
**Scope**: iOS target added to the `:shared`/Compose Multiplatform module structure; the native slicing engine cross-compiled for iOS device (arm64) and simulator targets, following the same shape of work WO-13 already completed for Android, informed by real prior art (`NilsN3DP/SlicerMobile`, `adickin/ISlicer` — §6a) rather than starting from zero; platform-specific bridges for camera, file picker, and notifications where Compose Multiplatform doesn't already abstract them; iOS-specific instrumented test coverage via AWS Device Farm's iOS device pool (owner-confirmed access, §16 Phase 0).
**Dependencies**: Phase 0's `:shared` module extraction; Phases 1–9's Android-proven core product (this phase ports a mature product, it does not re-design one).
**Architecture changes**: new `:iosApp` target / Xcode project consuming `:shared`'s produced framework; a second native-engine build pipeline (iOS toolchain) alongside the existing Android one.
**Data model changes**: none new — reuses `Project`/`PrinterCapabilities`/`MaterialProfile` from earlier phases via the shared module.
**API changes**: none beyond what Phases 10/11 already defined (now genuinely cross-platform once consumed from `:shared`).
**Tests**: the existing Android instrumented-test *intent* re-expressed for iOS (native slicing correctness, UI flows) — not a 1:1 port of every Android test file, since the two platforms' testing tooling differs, but equivalent coverage of the same real behaviors.
**Acceptance criteria**: a real model slices successfully on a real iOS device via the ported native engine; the core Prepare→Print flow works end-to-end against at least one real printer integration; Device Farm's iOS pool runs the suite in CI. **Development, TestFlight, and sideloaded/ad-hoc distribution require none of the above legal work and can proceed on this criteria alone. A separate, explicit gate — resolving the AGPL/App Store question (§6a, §22 question 8: ask OrcaSlicer for a §7 exception, and/or a real legal review) — is required specifically before, and only before, public Apple App Store submission.** Owner decision, 2026-09-22: develop now, resolve that gate later, but track it as a real named gate on public release, not let it lapse unaddressed once development is underway.
**Risks**: **public App Store distribution of the AGPL-3.0 native engine is a real, researched-but-unresolved risk (§6a)** — this app's position (downstream user of upstream OrcaSlicer, not its copyright holder) is meaningfully riskier than existing App Store precedent for AGPL apps (which are published by their own primary copyright holders). Not a blocker to development, TestFlight, or sideloaded distribution — only to a public App Store release, and only until §6a's option 1 (ask OrcaSlicer for a §7 exception) or option 4 (real legal review) resolves it. iOS cross-compilation of the Boost/CGAL/GMP/MPFR/OpenVDB/OCCT dependency chain is separately real, non-trivial work even with prior art to reference — do not underscope it as "just recompile for a different target."
**Non-goals**: iOS-exclusive UI paradigms/widgets beyond what Compose Multiplatform already provides; feature parity ahead of Android (Android remains the lead platform for new capability development).

### Phase 13 — Desktop Port (Windows/Linux) — **owner-confirmed target platform, 2026-09-22; macOS deferred out of this phase the same day**
**Objective**: a real multi-OS desktop companion app (Windows/Linux), sharing the same `:shared` module.
**Scope**: desktop target added to the module structure; native engine embedded via the same headless (`SLIC3R_GUI=OFF`) pattern already used on Android, built for each desktop OS/arch using upstream OrcaSlicer's own already-existing, natively-supported desktop build tooling (§6a) — the easiest of the native-engine ports remaining in scope, since no novel cross-compilation is required; desktop-appropriate window/input handling (mouse/keyboard alongside or instead of touch) for the 3D workspace and control screens. macOS is deferred, not technically blocked — the same headless build pattern applies there too if it's added back later.
**Dependencies**: Phase 0's shared module extraction; Phases 1–9's Android-proven core product (this phase ports a mature product, it does not re-design one).
**Architecture changes**: new `:desktopApp` target; a native-engine build pipeline for desktop OrcaSlicer's own existing toolchain, adapted to this app's headless embedding pattern rather than its own wxWidgets GUI.
**Tests**: CI across Windows/Linux runners (e.g. GitHub Actions' own multi-OS runner matrix — a more conventional setup than AWS Device Farm, which targets mobile devices specifically).
**Acceptance criteria**: the same core Prepare→Print flow works on both desktop OSes against at least one real printer integration.
**Risks**: desktop input/interaction patterns (mouse/keyboard, resizable windows, multi-monitor) are a real UX difference from the touch-first mobile design this whole plan otherwise assumes — budget real design time, not just a recompile.
**Non-goals**: replacing or feature-matching the full upstream OrcaSlicer desktop GUI — this remains a companion app in this project's own UI, not a competing general-purpose desktop slicer. macOS support, unless/until it's added back to scope.

---

## 17. Dependencies Between Phases

```
Phase 0 (Android CI + :shared KMP extraction)
   │
   ├──▶ Phase 1 ──┬─▶ Phase 3 ──▶ Phase 4 ──▶ Phase 5 ──▶ Phase 6 ──▶ Phase 7
   │    │           Phase 2 ──┘                                        │
   │    │                                                              ▼
   │    │                                                Phase 8 ──▶ Phase 9 (Android core product complete)
   │    │                                                              │
   │    │                                                              ▼
   │    │                                        Phase 10 (Discover)   Phase 13 (Desktop port, Windows/Linux)
   │    │
   │    └──────────────────────────────────────────────▶ Phase 11 (Account & Sync)

   (Phase 12 - iOS port - deferred, not shown; would depend on Phase 9 and Phase 0's :shared
    module the same way Phase 13 does, if picked back up)
```
Phase 1 and Phase 2 can run in parallel (different subsystems: 3D workspace vs printer transport). Phases 3 through 9 are sequential — each depends on data models the previous phase introduced — and represent **Android reaching a complete core product**, the gate before Phase 13 begins. **Phase 11 depends only on Phase 1** and can run in parallel with anything from Phase 2 onward. **Phase 13 (Desktop) depends on Phase 9** (a mature core product to port, not a moving target) **and Phase 0's `:shared` module extraction**. Phase 12 (iOS) is deferred and not currently sequenced; its dependencies would be identical to Phase 13's if it's resumed.

---

## 18. Risks and Mitigations

| Risk | Phase | Mitigation |
|---|---|---|
| No CI today; native build has an undocumented external dependency (`ORCASLICER_ENGINE_ROOT`) | 0 | Solve CI's native build story *before* any other phase — treat as a blocking prerequisite, not parallel work |
| Auto-arrange is a real algorithm, easy to underscope | 1 | Budget it as its own multi-day task, not a one-line bin-pack call |
| Capability-layer migration touches every existing control panel (100+ device tests) | 2 | Full device-test re-run required, not spot checks; land behind a flag if needed to de-risk |
| Multicolor is the highest-complexity phase in the whole roadmap | 8 | Sequenced last among the "core" phases deliberately; do not start until 1–3 are stable in production use |
| AGPL obligations for the native engine already exist; adding a cloud slicing path would add new ones | 7 (cloud fallback, if ever built) | Legal review before any cloud slicing code is written, not after |
| Owner's stated "no cloud account" identity is now a deliberate, approved exception (Phases 10 and 11) rather than a blanket rule — future phases must not assume further cloud features are welcome just because these two were approved | 10, 11 | Treat each cloud-touching capability as its own decision (as this session did for Discover, sync, and camera relay individually); never generalize "the owner approved cloud things" into skipping this scrutiny for something new |
| Phase 11 is a first-time operational commitment (a live backend to run/secure/pay for) with no existing precedent in this codebase to build on | 11 | Budget real time for account-security review (password reset, session handling, data-deletion correctness) at the same rigor as this app's existing encrypted printer-credential storage; do not treat it as "just a REST API" |

---

## 19. Test Strategy

Extends the existing (strong) pattern of real device tests against real hardware/fixtures, already used throughout WO-13/14/15:
- **Slicer correctness**: golden-G-code fixture tests per material/printer combination (extend `SlicingCoordinatorTest`).
- **Project serialization / 3MF preservation**: round-trip a real multi-object 3MF, assert object count and per-object transforms survive.
- **Transforms & auto-arrange**: deterministic fixture models with known correct arrangements.
- **Printer capability negotiation**: one test per vendor asserting the right capabilities resolve.
- **Filament/multicolor mapping**: assign→slice→grep G-code for expected toolchange sequences (same pattern as the existing `PaintSessionDeviceTest.paintedSupportsActuallyReachTheSlicedGcode`).
- **Invalid configs / slicing failures / transfer failures / network interruption / printer disconnect**: already a strong existing pattern (`SlicingCoordinator`'s `FirmwareBlocked`/`Failed` outcomes) — extend, don't reinvent.
- **UI state restoration**: process-death simulation for `Project` persistence (new, Phase 1).
- **Large models / memory pressure / slow devices**: needed once multi-object plates exist — not meaningfully testable today with a single object. **AWS Device Farm (owner-confirmed access, Phase 0, §16) is the real mechanism for this** — run the same instrumented suite across genuinely low-end/older real device models, not just whatever device happens to be on hand, surfacing memory-pressure and performance issues this project's current one-device manual-testing practice structurally cannot catch.
- **Multi-device/OS-version fragmentation**: new category, enabled by Device Farm — the existing 136 instrumented tests re-run across a real device/Android-version matrix in CI (Phase 0), not spot-checked on one physical phone as they are today.
- **Accessibility**: not currently covered anywhere in the test suite (real gap) — add once the multi-object UI (the highest-touch-target-density screen) exists. Device Farm supports accessibility-focused test runs (e.g. Espresso Accessibility Checks) across its device pool, which is the natural place to add this once there's UI worth checking.

---

## 20. UX Principles (binding, not aspirational)

Beginner-first, progressive disclosure, capability-driven, **no fake functionality** (already this app's own stated standard — every commit this session was verified on real hardware before being called done, and this plan holds every future phase to the same bar), explain failures, preserve user intent (3MF/project/material state must never silently vanish), mobile-native (not a shrunk desktop slicer), fast (heavy computation — slicing — should never block the UI thread; it doesn't today), recoverable (drafts, interrupted slices, transient network issues must not destroy work — directly motivates Phase 1's persistence work), accessible (a real, currently-absent gap — see §19).

---

## 21. Explicitly Deferred Functionality

STEP import, cloud/hybrid slicing as a default path, AI failure detection until a real detector exists, a marketplace beyond browse/download (Phase 10 stays a discovery surface, not a place to publish/sell), real-time multi-user collaborative project editing (Phase 11 is per-user sync, not shared editing), printer fleet "queue" orchestration beyond a flat multi-printer list, firmware-update flows (no vendor integration here exposes one today), turnkey remote access for users unwilling to install Tailscale (today's remote camera/control access is Tailscale-based and already real, §12 — a from-scratch relay for non-Tailscale users is a different, unplanned feature).

---

## 22. Open Questions Requiring a Product-Owner Decision

1. ~~Does the owner want any cloud/account-backed capability at all...~~ **Resolved, owner-approved 2026-09-22, settled item by item, not as a blanket yes:**
   - **Discover** (Phase 10): approved, target is MyMiniFactory (question 6, below) — no account of this app's own, OAuth pass-through only.
   - **Cross-device project sync** (Phase 11): approved — the one capability that does require this app to stand up its own account system. See Phase 11, §16, for the real architecture recommendation.
   - **Remote (off-LAN) camera relay**: turned out to be **already solved**, not an open question — verified in the real code (`Moonraker.kt:79-87`) that a Tailscale address is already trusted and camera streaming already works over it today, with no relay server of this app's own. Removed from scope entirely; see §12.
2. **Is a cloud-slicing fallback wanted** for low-end devices, even as a strictly opt-in feature? (§7) If no, Phase 7's `PrintPipeline` work simplifies.
3. **STEP import** — worth the OCCT integration effort, or permanently out of scope? OCCT is already bundled for slicing; the marginal cost is import-path work, not a new dependency.
4. **AI failure detection** — is this worth building at all, or is it explicitly rejected as out of character for a local-first app (most implementations require either a cloud model or meaningful on-device ML, both new dependencies)?
5. **CI native-build story** (§16 Phase 0) — does the owner want to solve `ORCASLICER_ENGINE_ROOT` for CI by vendoring a prebuilt artifact, running a self-hosted runner with the dependency preinstalled, or something else? This is a real decision, not purely technical.
6. ~~If Phase 10 is approved, is MyMiniFactory's smaller-but-official catalog an acceptable trade for API legitimacy...~~ **Resolved, owner-approved 2026-09-22: yes — MyMiniFactory is the settled Phase 10 integration target**, over a larger but reverse-engineered/fragile integration with MakerWorld or Printables (§16 Phase 10). Still open: whether to also add Thingiverse once (if ever) MyMiniFactory unifies its API following its 2026-02-12 acquisition of Thingiverse from Ultimaker — currently confirmed to remain a separate, independent platform with no announced integration timeline; re-check before Phase 10 implementation begins, not before.

   *Note on the local-first tension (§1): the MyMiniFactory decision by itself requires no account/cloud system of this app's own — its OAuth flow authenticates the user directly to MyMiniFactory (the same shape as this app's existing Spoolman/printer-credential integrations), not to an account this app creates or stores. Phase 11 is the actual, deliberate exception to local-first — approved with that distinction explicit, not by generalizing from the Discover decision.*
7. ~~Is the target platform Android-only?~~ **Resolved, owner-confirmed 2026-09-22, revised later the same day: Android + Desktop (Windows/Linux)** — iOS and macOS deferred (§6a). Recommend Kotlin Multiplatform + Compose Multiplatform, sequenced so Desktop (Phase 13) begins only after Android's core product (Phases 0–9) is mature, with a shared module extracted early (Phase 0) rather than retrofitted later — that module also keeps iOS addable later without a second extraction pass, if it's picked back up.
8. **Superseded, 2026-09-22 (later same day): Phase 12 (iOS) itself was deferred**, so the App Store legal question is moot for now rather than resolved-and-gated. The original decision ("develop Phase 12 now, resolve the App Store legal question before public submission") and the underlying research (§6a) are preserved as-is in case iOS is picked back up — the same sequencing logic (develop first, resolve the App Store question only before public submission) would still apply then.
9. **New: how much of Compose Multiplatform's UI should be genuinely shared vs. platform-native?** This plan recommends sharing as much as practical (§6a) but doesn't mandate 100% — some screens (e.g. desktop's mouse/keyboard-driven 3D workspace) may warrant platform-specific treatment. A real UX call, not a purely technical one, best made once Phase 13 (and Phase 12, if it returns to scope) is actually being scoped in detail rather than pre-decided here.

---

## 23. Recommended Answers (summary, per the owner's 5 closing questions; updated 2026-09-22 after settling Phase 10/11 scope)

1. **Recommended architecture**: keep the existing native on-device OrcaSlicer engine and GLES30 viewer for Android; add a real multi-object `Project` model (Room-backed) and a unified `PrinterCapabilities` abstraction; extract a genuinely platform-agnostic `:shared` Kotlin Multiplatform module (Phase 0) so the confirmed Desktop target (§6a) reuses it rather than reimplementing printer protocols and domain logic twice more, and so iOS stays addable later without a second extraction pass if it's picked back up; adopt Compose Multiplatform (verified production-ready) rather than rewriting the UI in a different framework/language. Cloud/account features are no longer blanket-deferred — Discover (MyMiniFactory, account-free OAuth pass-through) and account/sync (Phase 11, this app's own first backend) are both approved, decided individually on their own merits; remote camera/control access needs no new architecture at all, already solved via existing Tailscale support.
2. **Recommended phase order**: 0 → 1 ∥ 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 (Android core product complete) → 13 (Desktop port, Windows/Linux), with **Phase 11 buildable in parallel any time after Phase 1**, and **Phase 10** sequenced after Phase 1 as well, whenever the owner wants Discover live. Phase 13 deliberately waits for Phase 9 — porting a mature product once is far cheaper than porting a moving target. Phase 12 (iOS) is deferred and not part of this order; it would slot in parallel with Phase 13 (same dependencies) if resumed.
3. **Exact P0 boundary**: unchanged in feature terms — multi-object build plate + project persistence, printer capability abstraction, a real material model wired into slicing, basic-tier slicing UX, multi-printer dashboard (already have). Platform scope is tracked separately (§15): Android is the lead platform for all P0–P3 feature work; Desktop (Windows/Linux) is a confirmed target sequenced after Android's P0+P1 maturity; iOS and macOS are deferred.
4. **Largest technical risks**: (a) no CI + an undocumented native build dependency (`ORCASLICER_ENGINE_ROOT`) — solve first; (b) auto-arrange is real algorithmic work, not a checkbox; (c) multicolor is genuinely the hardest core-product phase and is sequenced last among the core phases on purpose; (d) **Phase 11 is a first-time operational commitment** (a live backend to run, secure, and pay for) with no existing precedent in this codebase; (e) the AGPL-3.0 native engine's compatibility with Apple App Store distribution terms (§6a, §22 question 8) is preserved as real research but is currently moot — Phase 12 (iOS) is deferred.
5. **First implementation milestone recommended once approved**: **Phase 0** — CI (including AWS Device Farm for the existing Android instrumented suite, owner-confirmed access) + native-build-in-CI story, the non-user-visible native change that stops flattening multi-object models, **and now also the `:shared` KMP module extraction**, since retrofitting that after Phases 1–9 are built Android-only would be far more expensive than designing for it from the start. Everything downstream — Phase 1 through Phase 13 — depends on some piece of Phase 0, making it unambiguously the right place to start.

---

*End of planning document. No code was written or modified as part of producing this plan.*
