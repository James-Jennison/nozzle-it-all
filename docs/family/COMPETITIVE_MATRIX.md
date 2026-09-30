# Nozzle It All family: competitive matrix

Research date: **2026-09-26**. Scope: Nozzle It All Android (shipping to closed test), Desktop (Compose Desktop plus
an Orca-derived Advanced Workspace, being built) and Web (browser app, being built). Primary printer: Snapmaker U1
on PAXX firmware over LAN.

Method. OrcaSlicer, Bambu Studio and Snapmaker Orca facts come from the earlier research pass (same day, sources
S1-S7). Every other competitor fact was checked on 2026-09-26 against the official release notes, docs or repository
cited. Nozzle facts come from this repository (README, `docs/CONSUMER_SLICER_PLAN.md` §3/§14 and addendum,
`docs/WORK_ORDER.md` WO-34/35/37/40/41, and a code grep of `app/src/main` on 2026-09-26).

Cell conventions: `yes` = documented in the cited source; `no` = the cited source says it is absent or it is
structurally impossible for that product; `?` = not confirmed in an official source this pass (do not quote it as
fact). Numbers in brackets are source IDs from the list at the end.

---

## 1. Capability x slicer matrix

Columns: **Orca** = OrcaSlicer 2.4.2 · **BBS** = Bambu Studio 2.8.2 · **SM-Orca** = Snapmaker Orca 2.4.0 ·
**Prusa** = PrusaSlicer 2.9.6 (3.0 in alpha) · **SS** = SuperSlicer 2.7.6x (prereleases only; last 2025-11-19) ·
**Cura** = UltiMaker Cura 5.13 · **iM** = ideaMaker 5.5.0 · **S3D** = Simplify3D 5.1.2 · **Kiri** = Kiri:Moto 4.7.3 ·
**Android** = third-party Android slicers (Slice Beam, DuckySlicer, Your One Slicer; details in §1a).

| Capability | Orca | BBS | SM-Orca | Prusa | SS | Cura | iM | S3D | Kiri | Android apps |
|---|---|---|---|---|---|---|---|---|---|---|
| Model import | STL/3MF/STEP/OBJ/AMF/DRC/SVG [2] | yes incl. STEP [4] | Orca base [6] | yes [8] | Prusa base [12] | yes [13] | yes [17] | yes [18] | STL/OBJ/3MF, SVG/PNG to 3D [21] | STL/OBJ/3MF; Slice Beam adds STEP [25] |
| Mesh repair | fix model, simplify [2] | fix model [5] | Orca base | ? | ? | ? | ? | ? | separate Mesh:Tool app [24] | ? |
| Manipulation (cut, boolean, emboss) | cut, mesh boolean [2] | cut, boolean [5] | Orca base | emboss, text tool [11] | emboss [12] | rotate, select-face [15] | text insertion [17] | ? | ? | move/rotate/scale (DuckySlicer) [26] |
| Multi-plate | yes [1] | yes [4] | yes | up to 9 beds, one config [10] | no (2.7 base) ? | ? | ? | ? | ? | ? |
| Orient / arrange / packing | auto-orient, arrange [1] | auto-arrange/orient [4] | Orca base | arrange [10] | Prusa base | ? | 2D arrange 2.0 (FFF); 3D packing (SLS) [17] | ? | ? | auto-arrange (DuckySlicer) [26] |
| Support painting | yes [1] | yes [4] | yes | yes [9] | yes [12] | support blocker tool [16] | support painting [17] | manual supports [18] | manual supports [23] | DuckySlicer [26] |
| Seam painting | yes [1] | yes | yes | yes; scarf seams [9] | ? | paint seams (5.11) [14] | ? | ? | ? | ? |
| Variable layer height | VLH + height ranges [1] | adaptive/smooth [4] | Orca base | yes [8] | yes [12] | adaptive layers [16] | adaptive layers [17] | adaptive (5.0) [18] | adaptive + layer ranges [23] | ? |
| Modifier meshes | yes, height-range modifiers [1] | yes | yes | yes, height-range modifier [11] | yes | per-model, cutting/infill mesh [16] | ? | multi-process settings [18] | layer ranges [23] | ? |
| Calibration tools | temp, flow, PA, retraction, max flow, cornering, input shaping, VFA [3] | temp/flow/PA/retraction/max flow/VFA + device cal. [4] | Orca base | ? | calibration menu (flow, retraction, bridging) [12] | none built in ? | ? | ? | ? | ? |
| Material profiles | yes, inheriting [1] | device filament mgmt [4] | official colour library, filament sync [6] | yes | yes | yes; materials repo CC0 [30] | ? | ? | device + profile [21] | per-filament library (Your One Slicer) [27] |
| Multi-material / colour prep | per-feature filament (2.4.0) [1] | colour prediction, auto colour decomposition [4] | filament-to-nozzle mapping, Full Spectrum mixing [6] | ColorMix (2.9.6) [8] | wipe tower [12] | paint extruder assignment (5.11) [14] | colour painting [17] | ? | multi-extruder [23] | up to 4 extruders, paint (Your One Slicer) [27] |
| Purge / transition mgmt | prime tower, flush options [1] | flush into infill/object [4] | toolchanger family [6] | wipe tower | wipe tower | ? | ? | ? | purge tower [23] | wipe tower (Your One Slicer) [27] |
| Presets | inherit; optional Orca Cloud sync [1] | yes | merged Standard presets [6] | yes | yes | recommended/custom [15] | ideaMaker Library templates [17] | high-speed profiles [18] | shared profiles [21] | OrcaSlicer catalog (DuckySlicer) [26] |
| Adaptive / auto defaults | adaptive layers [1] | smooth/adaptive [4] | Orca base | ? | ? | recommended mode hides risky infill [15] | floating model detection [17] | "automated print optimizations" [19] | ? | ? |
| Estimates | yes | pause point display [4] | Orca base | yes | yes | yes | yes | yes | ? | yes |
| G-code preview | yes | yes | yes | yes | yes [12] | layer height mm (5.12) [15] | improved (5.4.2) [17] | layer analysis [18] | yes [21] | yes (all three) |
| Project portability | 3MF, .gcode.3mf [2] | 3MF | 3MF | 3MF | 3MF | 3MF [13] | ? | ? | .kmz workspace [21] | ? |
| Discovery and sending | 17+ host types, printer agents [1] | Device tab; LAN mode [4] | U1 LAN/account binding [7] | PrusaLink/Connect, remembers destination [11] | Prusa base | UltiMaker network, USB (opt-in) [14] | RaiseCloud, OctoPrint/Repetier [17] | Klipper/OctoPrint/Duet/Bambu [18] | OctoPrint plugin/API [22] | OctoPrint, Moonraker (DuckySlicer) [26]; U1, Bambu LAN [27] |
| Queues and history | ? | send to up to 6 (cloud only) [4] | ? | Connect ? | no | Digital Factory ? | RaiseCloud ? | multi-printer admin [19] | no | ? |
| Cameras and monitoring | device tab (BBL/Moonraker) [1] | device tab + camera [4] | status/control, detection prefs [6][7] | Connect ? | no | UltiMaker monitor ? | RaiseCloud [17] | live preview tracking [18] | no | monitoring + camera (Your One Slicer) [27] |
| Recovery | printer-side | printer-side | printer-side | printer-side | printer-side | printer-side | ? | ? | no | ? |
| Extensions | post-processing; Python plugins on main only [1] | proprietary network plugin [4] | Orca base | post-processing | post-processing | Marketplace plugins, post-processing scripts [12][15] | ? | ? | JS slicing APIs [20] | no |
| Accessibility / locales | 23 locales [1] | ? | ? | UI font size persisted [11] | ? | ? | ? | dark mode [18] | localisation doc [20] | 22 languages (DuckySlicer) [26] |
| Beginner usability | presets, wizards | strong for Bambu | U1 quick start [7] | favourites panel [8] | expert-leaning | recommended mode [15] | templates | ? | "strong editorial stance on complexity" [20] | touch-first |
| Expert efficiency | expert/developer modes [1] | yes | yes | yes | most settings | custom mode | yes | multi-process [18] | moderate | limited |
| Offline and privacy | local; opt-in cloud | LAN mode, opt-in telemetry, Authorization Control [4] | local + optional account [7] | local; Connect optional | local | local; Digital Factory optional | local; RaiseCloud optional | local; paid licence [19] | all processing in browser, no trackers [20] | on-device (all three) |
| Android slicing | no [1] | no; Handy = cloud slicing [4] | no | no; EasyPrint = cloud [28] | no | no | no | no | browser only | yes (native engines) |
| Browser slicing | no | no | no | EasyPrint (cloud) [28] | no | no | no | no | yes, local JS + some WASM [21] | no |

### 1a. Android slicers (the only direct Android competitors found)

| App | Engine | Licence | Latest | Notes | Source |
|---|---|---|---|---|---|
| Slice Beam | PrusaSlicer core | AGPL-3.0 | 0.3.0, 2025-04-14 (no push since) | STL/STP/STEP/OBJ/3MF, on-device, no subscription | [25] |
| DuckySlicer | OrcaSlicer profile catalog | AGPL-3.0 | v0.2.0-alpha.13, 2026-09-18 | alpha; U1 defaults; arrange, support painting, OctoPrint/Moonraker, 22 languages; arm64 only | [26] |
| Your One Slicer (`u1-slicer-for-android`) | Snapmaker Orca 2.2.4 via JNI | AGPL-3.0 | v4.0.4, 2026-08-29 | U1 up to 4 extruders; early-beta Bambu LAN; rejected as a vendored dependency by the owner (WO-13: unverified binary provenance) | [27] |
| OrcaXR | OrcaSlicer, Android XR | no licence file | v0.0.2, 2026-05-14 | niche; not reusable | [29] |
| Bambu Handy, Prusa EasyPrint | cloud slicing | proprietary service | - | not on-device | [4][28] |

Finding: at least three AGPL Android slicers now slice on-device, and one (Your One Slicer) targets the same U1
printer. "Slices on the phone" is no longer unique by itself; printer control, multi-printer breadth and cross-device
continuity are what remain distinctive (see §3 and §5).

---

## 2. Nozzle It All state per capability and platform (2026-09-26)

States: **EXISTING** (built and device-tested) · **PARTIAL** (some real parts, named gaps) · **MISSING** ·
**UNVERIFIED** (code path exists or is claimed, but no evidence it works) · **N/A**.
Desktop and Web have no user interface built. Shared plain-JVM `:domain`/`:transport` modules (WO-34) and uncommitted
`:printer-api`/`:adapter-paxx`/`:stock-u1-adapter` modules exist; they are libraries, not features, so they count as
MISSING unless noted.

| Capability | Android | Desktop | Web | Evidence / gap |
|---|---|---|---|---|
| Model import | PARTIAL | MISSING | MISSING | STL/3MF/OBJ yes (README); STEP not planned (§14) |
| Mesh repair | UNVERIFIED | MISSING | MISSING | no repair/simplify code in `app/src/main`; whatever libslic3r does on load is not surfaced or tested |
| Manipulation | EXISTING | MISSING | MISSING | move/rotate/scale/duplicate/mirror/lay-flat/measure/native cut (WO-34 9c); no boolean, emboss |
| Multi-plate | EXISTING | MISSING | MISSING | plates, move-to-plate, slice all plates (WO-34 9b) |
| Orient / arrange / packing | PARTIAL | MISSING | MISSING | auto-orient, auto-arrange, collision detection; no dense packing |
| Support painting | EXISTING | MISSING | MISSING | enforcer/blocker strokes and regions, device-proven (WO-34 9d) |
| Seam painting | EXISTING | MISSING | MISSING | seam stroke moves seam in device test (9d); fuzzy-skin painting MISSING |
| Variable layer height | MISSING | MISSING | MISSING | no layer-height-profile/adaptive code found; addendum says "dedicated UI" open |
| Modifier meshes | PARTIAL | MISSING | MISSING | box regions (modifier/blocker/enforcer), no gizmo, no arbitrary mesh or height-range modifier |
| Calibration | PARTIAL | MISSING | MISSING | temp tower, PA tower (Klipper), flow cube; none printed on hardware (9e) |
| Material profiles | EXISTING | MISSING | MISSING | `MaterialProfile`, Spoolman temperatures reach sliced G-code (WO-19) |
| Multi-material / colour prep | PARTIAL | MISSING | MISSING | U1/XL per-object and painted-region tools device-tested in UI; no physical multi-tool print; Bambu AMS not wired (WO-35) |
| Purge / transition mgmt | PARTIAL | MISSING | MISSING | toolchange preview and purge estimate built; prime tower ignored by engine on U1/XL; swap path unit-tested only |
| Presets | EXISTING | MISSING | MISSING | Basic/Advanced/Expert tiers, 27 keys, custom inheriting profiles, compare (9a) |
| Adaptive / auto defaults | PARTIAL | MISSING | MISSING | geometry-driven support default (WO-20); no adaptive layers |
| Estimates | EXISTING | MISSING | MISSING | time, grams, per-tool grams, toolchange count, purge (WO-35) |
| G-code preview | EXISTING | MISSING | MISSING | layers, per-tool colours, OOM-safe (WO-21/35, addendum) |
| Project portability | PARTIAL | MISSING | MISSING | `.nozzleproj` zip export/import (9b); no 3MF project round-trip with Orca/Prusa |
| Discovery and sending | EXISTING | PARTIAL | MISSING | LAN scan verified on real U1/CC1 (WO-37); QR pairing missing; Bambu print ack unverified. Desktop: uncommitted PAXX LAN adapter, no UI |
| Queues and history | PARTIAL | MISSING | MISSING | history + reprint for Klipper/U1; no queue (WO-11) |
| Cameras and monitoring | EXISTING | MISSING | MISSING | 2 camera protocols, dashboard, 8 notification types; AI detection missing |
| Recovery | PARTIAL | MISSING | MISSING | no automatic replay of mutating requests; interrupted-slice explanation; encrypted printer backup; no mid-print object exclusion |
| Extensions | MISSING | MISSING | MISSING | printer macros exist (control, not slicer extension); no post-processing scripts or plugins |
| Accessibility | PARTIAL | MISSING | MISSING | icon descriptions, contrast-checked brand colours (WO-41); no localisation (strings hard-coded) |
| Beginner usability | PARTIAL | MISSING | MISSING | onboarding (WO-40), basic tier; no usability testing with real users |
| Expert efficiency | PARTIAL | MISSING | MISSING | 27 catalogued keys + search; no full settings tree, no keyboard workflow |
| Offline and privacy | EXISTING | MISSING | MISSING | no account, no analytics, direct-to-printer traffic (README) |
| Android slicing | EXISTING | N/A | N/A | native engine, 376 bundled profiles |
| Browser slicing | N/A | N/A | MISSING | see note below |

Note on Web slicing. The brief for this document says the Web App slices locally via WebAssembly. The repository's
latest recorded decision (WORK_ORDER.md, "Decision record, 2026-09-25") says Web is a PWA in front of an engine the
user runs (Desktop agent or self-hosted Engine Service), with WASM only as an optional spike (plan Phase 14x). This
document records browser slicing as MISSING either way; the decision record should be updated if WASM-local is now the
committed direction.

---

## 3. Candidate features, categorised

1 = Nozzle essential (every platform, default UI) · 2 = Differentiating · 3 = Advanced Workspace feature (Desktop,
opt-in; later other platforms where sensible) · 4 = Later · 5 = Rejected.

| # | Feature | Cat. | Reason |
|---|---|---|---|
| 1 | Import STL/3MF/OBJ with automatic mesh repair and a plain repair report | 1 | downloaded models are often broken; every desktop competitor fixes on import |
| 2 | Move/rotate/scale/lay-flat/auto-orient/duplicate | 1 | table stakes in all ten products |
| 3 | Auto-arrange on multi-plate projects | 1 | Orca, BBS, Prusa 2.9 all have it; already built on Android |
| 4 | Support painting and blockers | 1 | universal; directly affects print success |
| 5 | Printer-aware basic tier (printer, material, quality) | 1 | the "3 decisions" requirement (plan §4) |
| 6 | Material profiles with printer-reported filament (U1 slot sync) | 1 | SM-Orca syncs U1 filaments [6]; the primary printer needs it |
| 7 | Per-object and painted colour assignment mapped to U1 tools | 1 | U1 is a 4-tool machine; every U1 competitor does this |
| 8 | Layer preview with per-tool colour, time/grams/purge estimates | 1 | must see before print; universal |
| 9 | LAN discovery, send, start with confirmation | 1 | core of a printer-management product |
| 10 | Live status, camera, pause/resume/cancel, notifications | 1 | core; already existing on Android |
| 11 | History with reprint | 1 | cheap, high value; exists for Klipper/U1 |
| 12 | Offline, account-free operation | 1 | product promise (README) |
| 13 | Project save plus 3MF export that Orca/Prusa can open | 1 | users must be able to leave; `.nozzleproj` alone is a lock-in |
| 14 | Printer-first home: pick printer, then job; state drives what is offered | 2 | slicers are settings-first; only vendor apps do this, for one brand |
| 15 | Task flows ("print again", "print this in these colours", "tune this material") | 2 | no competitor structures prep around tasks |
| 16 | Same project on phone, desktop and browser (file handoff now, sync via user-run Engine Service later) | 2 | no competitor spans Android + desktop + browser with one engine |
| 17 | Remote control through a user-owned route (Tailscale etc.), no vendor relay | 2 | competitors' remote paths use vendor clouds (Bambu, Raise, Prusa Connect) |
| 18 | Honest capability gating and "result unknown" handling for lost responses | 2 | safety behaviour competitors do not document |
| 19 | Spoolman spool data flowing into slice temperatures | 2 | built and proven (WO-19); none of the slicers above document it |
| 20 | Guided calibration that ends by saving a material profile | 2 | Orca has the tests [3], but results are copied by hand |
| 21 | Pre-slice compatibility warnings (materials, temperatures, tool mix) | 2 | built (WO-35); prevents failed multi-material prints |
| 22 | Localisation and screen-reader support as release criteria | 2 | wx/Qt slicers document little accessibility; phone-first product can lead |
| 23 | Full Orca-derived settings tree with search and compare | 3 | experts need every key; keep it out of the default flow |
| 24 | Variable layer height editor and adaptive layers | 3 | all major desktop slicers have it; needs precise pointer input |
| 25 | Height-range and mesh modifiers | 3 | expert tool; Android keeps box regions |
| 26 | Seam and fuzzy-skin painting | 3 | seam exists on Android; fuzzy skin is cosmetic, expert-only |
| 27 | Cut, mesh boolean, emboss/text | 3 | Orca/BBS/Prusa have it; desktop-precision work |
| 28 | Flush-volume matrix and per-feature filament assignment | 3 | Orca 2.4.0 feature [1]; only filament-swap machines benefit |
| 29 | Full calibration suite (retraction, max flow, VFA, input shaping, cornering) | 3 | Orca parity [3]; expert tuning |
| 30 | Post-processing scripts | 3 | every desktop slicer has them; run only in Desktop with explicit opt-in |
| 31 | STEP import | 3 | Orca/BBS/Slice Beam have it; OCCT dependency too heavy for phone and WASM first |
| 32 | Print queue and bed-cleared workflow | 4 | WO-11; needs multi-printer users first |
| 33 | Mid-print object exclusion | 4 | Klipper supports it and G-code labels now unique (d9ee14f); needs UI and hardware test |
| 34 | AI failure detection | 4 | WO-10 architecture decision open; honest-or-absent rule |
| 35 | Full Spectrum style colour mixing | 4 | Snapmaker-specific [6]; after basic multi-colour is proven on hardware |
| 36 | Bambu AMS multi-material | 4 | no hardware to verify (WO-35) |
| 37 | Dense 3D packing | 4 | ideaMaker applies it to SLS [17]; little FDM value |
| 38 | Nozzle-hosted cloud slicing (Plan B) | 4 | deferred by owner 2026-09-25; changes privacy promise |
| 39 | Third-party code plugins (Python/JS) | 5 | security and support cost; post-processing (30) covers the need |
| 40 | Vendor-cloud-gated features (Bambu network plugin, account-required slicing) | 5 | proprietary, breaks offline promise |
| 41 | SLA/SLS/CNC/laser modes | 5 | out of product scope (Kiri, ideaMaker cover these) |
| 42 | Own model marketplace/social hosting | 5 | needs a backend and moderation; MyMiniFactory client covers discovery |
| 43 | Telemetry/analytics | 5 | contradicts README "no analytics service" |

---

## 4. Licensing: what may be reused in an AGPL-3.0 project

Nozzle is AGPL-3.0-or-later. Reuse always keeps copyright notices and states changes; trademarks, logos and product
names are never reused regardless of code licence.

| Source | Licence (checked 2026-09-26) | Code reuse into Nozzle | Notes | Source |
|---|---|---|---|---|
| OrcaSlicer | AGPL-3.0 | yes | already the engine base; vendor profiles carry their own attribution, keep it | [1][30] |
| Bambu Studio | AGPL-3.0 | yes, source only | the Bambu network plugin is proprietary and optional: never bundle or call it | [4][30] |
| Snapmaker Orca | AGPL-3.0 | yes | useful for U1 filament sync, nozzle mapping, Full Spectrum logic | [6][30] |
| PrusaSlicer | AGPL-3.0 | yes | libslic3r upstream of Orca; 3.0 alpha code equally usable | [8][30] |
| SuperSlicer | AGPL-3.0 | yes | only prereleases since 2025; calibration generators are the interesting part | [12][30] |
| CuraEngine | AGPL-3.0 | yes | different codebase from libslic3r; ideas more than code | [30] |
| Cura frontend, Uranium | LGPL-3.0 | yes (LGPL-3.0 code may be conveyed under GPL-3.0, which may be combined with AGPL-3.0 via GPL-3.0 §13) | Python/QML, little practical reuse | [30] |
| Cura fdm_materials | CC0-1.0 | yes | material data usable with no conditions; still attribute as courtesy | [30] |
| Kiri:Moto (grid-apps) | MIT | yes | permissive; keep the MIT notice; candidate reference for browser-side preview/workers | [24] |
| Slice Beam, DuckySlicer, Your One Slicer | AGPL-3.0 | yes | check each file's provenance; owner rejected Your One Slicer's prebuilt `.so` (WO-13) | [25][26][27] |
| OrcaXR | no licence file | no | all rights reserved by default | [29] |
| ideaMaker | proprietary | **patterns only** | never code, assets, text, layouts or presets | [17] |
| Simplify3D | proprietary (paid perpetual licence) | **patterns only** | never code, assets, text, layouts or presets (profiles included) | [19] |
| Bambu Handy, Prusa EasyPrint, Raise/Digital Factory clouds | proprietary services | patterns only | no API reuse without a published, sanctioned API | [4][28] |

Distribution notes: the AGPL source offer is already in-app (WO-39). The Web App serves AGPL code to browsers, so the
page must link corresponding source for the exact deployed build, including any WASM engine build and its patches.

---

## 5. How Nozzle differs structurally

Every desktop product in §1 is organised around a **settings tree attached to a plate**: the user loads a model,
chooses printer/filament/process presets, and edits keys. Device control, where it exists, is a separate tab bolted
on, often for one vendor (BBS Device tab, SM-Orca U1 binding, Cura for UltiMaker, ideaMaker for Raise3D). Kiri:Moto
simplifies the tree but has almost no printer control. The Android slicers are phone ports of the same model.

Nozzle is organised the other way round:

1. **Printer-centred.** The home is the user's printers and their live state. A job starts from a printer ("print on
   the U1"), so profile, tool count, loaded filament and capabilities are known before any setting is shown. The
   capability model (`PrinterCapabilities`, WO-18) decides what is offered; unsupported actions are absent, not
   greyed out.
2. **Task-centred.** Flows are named after what the user wants: print this, print it again, print it in these colours,
   tune this material, find out why it failed. Settings appear only as the answer to a task step, grouped and
   validated (plan §16 Phase 9 acceptance: "no raw slicer-key dumping").
3. **One engine, three surfaces.** The same Orca-derived engine and shared domain logic run on phone, desktop and
   browser, so a project and its result travel between devices. The full settings tree exists once, in the Desktop
   Advanced Workspace, as an expert escape hatch rather than the default.
4. **Local-first control.** Printer traffic goes directly to addresses the user owns; remote access uses the user's
   own route; no account or relay. Competitors' remote features depend on vendor clouds.
5. **Honesty as a feature.** Lost responses are "unknown" until the printer is checked; unverified integrations are
   labelled; features that cannot be proven (AI detection) stay absent.

The practical consequence for the matrix: Nozzle should match competitors on category 1, lead on category 2, and put
category 3 in the Advanced Workspace instead of growing the default UI toward a settings tree.

---

## 6. Open points and limits of this research

- `?` cells were not confirmed in an official source during this pass; several (Cura calibration, Prusa Connect
  queue, S3D mesh repair) are commonly reported but were not re-verified here.
- Nozzle Android state is from docs plus a code grep; Variable layer height and mesh repair should be confirmed by a
  device test before being quoted externally.
- Physical multi-tool printing, Bambu print acknowledgement and calibration prints remain unverified on hardware
  (WO-35, WO-37, WO-34).
- Web slicing direction (WASM-local vs user-run engine) conflicts between the brief and the 2026-09-25 decision record.

---

## Sources (all accessed 2026-09-26)

1. OrcaSlicer v2.4.2 release (2026-07-07): https://github.com/OrcaSlicer/OrcaSlicer/releases/tag/v2.4.2
2. OrcaSlicer import/export and prepare docs: https://www.orcaslicer.com/wiki/general_settings/import_export.html
3. OrcaSlicer calibration guide: https://www.orcaslicer.com/wiki/calibration_guide
4. Bambu Studio repository and 2.8.2.61 release notes (2026-08-21): https://github.com/bambulab/BambuStudio
5. Bambu Lab wiki (cut, boolean, fix model): https://wiki.bambulab.com
6. Snapmaker Orca v2.4.0 release (2026-09-21): https://github.com/Snapmaker/OrcaSlicer/releases/tag/v2.4.0
7. Snapmaker Orca quick start for U1: https://wiki.snapmaker.com/en/snapmaker_orca/qsg
8. PrusaSlicer 2.9.6 release (2026-06-25): https://github.com/prusa3d/PrusaSlicer/releases/tag/version_2.9.6
9. PrusaSlicer 2.9 announcement: https://blog.prusa3d.com/prusaslicer-2-9-whats-new_107659/
10. Prusa KB, multiple build plates: https://help.prusa3d.com/article/multiple-build-plates-on-prusaslicer_823894
11. PrusaSlicer 3.0.0-alpha12 (2026-09-21): https://github.com/prusa3d/PrusaSlicer/releases/tag/version_3.0.0-alpha12
12. SuperSlicer releases (latest 2.7.62.0-beta2, 2025-11-19): https://github.com/supermerill/SuperSlicer/releases
13. Cura 5.13.0 (2026-05-28): https://github.com/Ultimaker/Cura/releases/tag/5.13.0
14. Cura 5.11.0 (2025-10-23, paint-on seams/extruders): https://github.com/Ultimaker/Cura/releases/tag/5.11.0
15. Cura 5.12.0 (2026-03-05): https://github.com/Ultimaker/Cura/releases/tag/5.12.0
16. Cura per-model settings and support blocker: https://support.makerbot.com/s/article/1667417981430 and https://support.makerbot.com/s/article/1667411336405
17. ideaMaker release notes (5.5.0, 2026-09-09): https://www.raise3d.com/download/ideamaker-release-notes/
18. Simplify3D release notes (5.1.2): https://www.simplify3d.com/products/simplify3d-software/release-notes/
19. Simplify3D purchase/FAQ: https://www.simplify3d.com/buy-now/ and https://www.simplify3d.com/resources/faq/
20. Kiri:Moto docs index: https://github.com/GridSpace/grid-apps/blob/master/docs/kiri-moto/index.md
21. Kiri:Moto FAQ: https://github.com/GridSpace/grid-apps/blob/master/docs/kiri-moto/faq.md
22. Kiri:Moto integrations/OctoPrint: https://github.com/GridSpace/grid-apps/blob/master/docs/kiri-moto/integrations.md
23. Kiri:Moto FDM parameters/menu source: https://github.com/GridSpace/grid-apps/tree/master/src/kiri/mode/fdm
24. grid-apps readme and licence (MIT; release 4.7.3, 2026-08-16): https://github.com/GridSpace/grid-apps
25. Slice Beam: https://github.com/utkabobr/SliceBeam and https://play.google.com/store/apps/details?id=ru.ytkab0bp.slicebeam
26. DuckySlicer: https://github.com/ashcastle/duckyslicer
27. Your One Slicer / u1-slicer-for-android: https://github.com/taylormadearmy/u1-slicer-for-android
28. Prusa EasyPrint: https://blog.prusa3d.com/prusa_easy_print_on_phone_tablet_110894/
29. OrcaXR: https://github.com/ignacio82/OrcaXR
30. Licence metadata via GitHub API (`/repos/{owner}/{repo}`) for OrcaSlicer, BambuStudio, Snapmaker/OrcaSlicer,
    PrusaSlicer, SuperSlicer, Cura (LGPL-3.0), CuraEngine (AGPL-3.0), Uranium (LGPL-3.0), fdm_materials (CC0-1.0).
