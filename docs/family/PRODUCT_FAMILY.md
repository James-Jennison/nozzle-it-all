# Nozzle It All product family

Nozzle It All is one product delivered four ways:

- **Nozzle It All for Desktop**
- **Nozzle It All for Android**
- **Nozzle It All Web**, the full browser-delivered app
- **nozzleitall.com**, the public website that explains and supports the three apps

This document is the design brief the implementation follows. The capability matrix
([CAPABILITY_MATRIX.md](CAPABILITY_MATRIX.md)) says what is actually built and verified today.

## 1. Brand brief

**Promise:** your printers, your projects, your network. Nozzle It All prepares, slices, sends and monitors 3D prints
on the device in front of you. It needs no Nozzle account and no Nozzle cloud, and printer traffic never goes through
Nozzle.

**Primary platform:** a Snapmaker U1 running PAXX firmware in LAN mode. Everything works offline: no Snapmaker account,
no cloud, no Flutter. Away from home, the user reaches their printer through their own private network (for example
Tailscale).

**Stock U1:** supported through an optional, separately installed component. It is loaded only when a Stock U1 is added,
and its failures stay inside it.

**Personality:** a calm workshop instrument. Precise, honest about state ("Result unknown", never an optimistic guess),
and never loud. Violet marks identity; print orange means heat and live printing; everything else is charcoal and slate
so the printer's own colours (loaded filament) stand out.

**Mark:** the Infill mark, a nozzle above a rhombus infill lattice (`brand/`). The same lattice and nozzle shapes
recur in the family's line icons (Desktop `ui/Icons.kt`), so icons read as Nozzle's without copying any icon set.

**Naming:** always "Nozzle It All". The short form in tight UI is "Nozzle". Product lines are "Nozzle It All for
Desktop", "Nozzle It All for Android" and "Nozzle It All Web". The specialist editor is the "Advanced Workspace". Never
"NIA", and never an upstream name (OrcaSlicer, Snapmaker Orca) as our product's name. Upstream names appear only in
attribution.

**Voice** (from `design/terminology/glossary.json`): say what happened, then what to do next. Use plain words. Name
the printer, the file and the toolhead. Never claim success that wasn't confirmed. Use sentence case, and no
exclamation marks in errors.

## 2. Single sources of truth and how they reach each platform

| Source | What it holds | Generated or used by |
|---|---|---|
| `design/tokens/nozzle.tokens.json` (DTCG format) | Colour roles for dark and light, status colours with icon shapes, type scale, spacing, radius, borders, elevation, sizes, motion | `scripts/generate_design_tokens.py` generates Compose Kotlin for Desktop and Android (`com.nozzleitall.design.NozzleTokens`), CSS custom properties for the Web App (`web/src/design/tokens.css`) and the website (`site-src/assets/tokens.css`), and a TypeScript copy |
| `design/terminology/glossary.json` | Product names, labels for states, routes, firmware, actions and outcomes, the PAXX, Stock, remote-access, cloud and Flutter explanations, tone rules, and banned internal words | Kotlin `Glossary` (printer-api), TypeScript `glossary.ts`; `GlossaryConsistencyTest` |
| `schemas/slicing/guided-presets.json` | Draft, Standard and Fine, with their exact engine keys | Web `GUIDED_PRESETS`; Desktop `QualityPreset` (checked against this file) |
| `project-format` + `schemas/fixtures/` | Canonical 3MF + `nozzle.project` manifest | Kotlin library (Desktop; Android adopting), TypeScript port (Web). Cross-written fixtures prove interchange |
| `printer-api` | The shared printer model, the adapter contract and the action/confirmation rules | Desktop (Kotlin), Web (`web/src/printers/model.ts` port), Android (to adopt) |

**Distribution and versioning.**
- Tokens and glossary carry semver `meta.version`.
- `generate_design_tokens.py --check` runs in `scripts/validate.sh` and CI. It fails when any generated file is stale,
  and when any text colour misses its WCAG target (7:1 for body text, 4.5:1 for other text and status, 3:1 for focus).
- Platforms never hand-edit generated files.
- A platform-specific adjustment is a new token or an override in the source, never a local constant.

## 3. Platform roles and sitemaps

```
Nozzle It All
├── Desktop  (Compose Desktop, Linux first; Windows packaging configured)
│   ├── Printers          fleet; home screen
│   ├── Projects          3MF library, trash with undo
│   ├── Prepare           plate, objects and materials, guided settings, slice, preview, send
│   ├── Print & Monitor   camera, job, temperatures, confirmed controls
│   ├── Materials & Toolheads
│   ├── Full Spectrum
│   ├── Advanced Workspace   (separate, behind a divider; opens the Orca-derived editor as its own window)
│   └── Settings          appearance, remote access, optional Stock U1, local connector, data, about
├── Android  (existing native app; tabs today: Home, Control, Files, Prepare, Discover, Settings)
│   └── target: Home/Printers, Projects, Prepare, Slice & Preview, Send, Monitor, Materials & Toolheads, Settings
│       (see ANDROID_AUDIT.md for the mapping; Android keeps touch-first navigation and its native slicer)
├── Web      (browser app, app.nozzleitall.com)
│   ├── Projects (home)   recent projects in this browser, open file, new from model
│   ├── Prepare           Models and plate → Printer and materials → Settings (guided + advanced) → Slice and preview → Export or send
│   ├── Printers          add (direct or through the local connector), monitor, confirmed controls
│   └── Settings          storage and persistence, engine capability, local connector pairing, privacy
└── nozzleitall.com (public website: explains, links to the apps, documents, supports; never pretends to be an app)
```

## 4. Journeys

| # | Journey | Desktop | Android | Web |
|---|---|---|---|---|
| 1 | Discover Nozzle, understand the platforms, PAXX vs Stock | Website | Website | Website |
| 2 | First run | Printers empty state explains PAXX offline use and private-network access; Add printer | Existing onboarding | Projects empty state: "sliced on this device, nothing uploaded" |
| 3 | Add a PAXX printer | Address → LAN probe → PAXX detected from its settings file → confirm | Wizard (PAXX chosen by hand today) | Address (direct) or pair with Desktop's connector |
| 4 | Import, prepare, slice, preview | Prepare | Prepare tab | Prepare steps |
| 5 | Save, reopen, move between platforms | Projects (3MF + manifest) | .nozzleproj today; 3MF interchange planned (ANDROID_AUDIT §15) | Stored in this browser; export or import 3MF |
| 6 | Send and start | Upload → Start print (confirm) | Existing send flow | Upload → Start print (confirm), direct or through the connector |
| 7 | Monitor | Print & Monitor | Home and Control | Printers → printer |
| 8 | Recover from failure | Unknown outcome → "Check printer" → commands resume; damaged project opens its geometry; workspace conflicts give three choices | Existing no-replay rules | Same guard as Desktop; damaged manifest → geometry opens |
| 9 | Add a Stock U1 | Detected as Stock → explained → optional support, off by default | Existing Bespok3d flow | Monitoring and sending over the LAN; account features not in the browser |
| 10 | Stock support fails | Stock printers go Offline with a reason; PAXX printers are unaffected (tested) | n/a | n/a |

## 5. Component inventory

| Component | Desktop (Compose) | Web (Preact + DOM) | Android (Compose) |
|---|---|---|---|
| Status pill (word + icon + colour) | `StatusPill` | `.pill` | state chips (to converge on the glossary) |
| Route badge | `RouteBadge` | `RouteBadge` | – |
| Printer card with state band and toolhead strip | `PrinterCard`, `ToolheadStrip` | `PrinterCard` | `PrinterTiles` |
| Confirm dialog (names the action; safe default focus; Escape cancels) | `ConfirmDialog` | `Confirm` (native `<dialog>`) | existing review dialogs |
| Unknown-outcome banner with "Check printer" | `ActionFlowUi` | `PrinterDetail` banner | existing notice banner |
| Empty, loading, offline, warning and failure states | `EmptyState`, `Banner` | `.empty`, `Banner` | existing |
| Progress | `ProgressBar` (semantics) | `role=progressbar` | existing |
| 3D plate and layer preview | `PlateViewer` (software renderer, no GL dependency) | `Viewer` (three.js / WebGL2) | GL ES viewer |
| Guided settings | Prepare step 3 | Settings step | Prepare tab |

## 6. Intentionally the same everywhere

- Names, and the meaning of every printer state, route, firmware, action and outcome (glossary).
- Colour roles and status colours. State is always shown as word + icon + colour, never colour alone.
- The confirm-then-execute-once rule, and "Result unknown → check the printer before trying again".
- The project file: canonical 3MF + Nozzle manifest, with unknown data preserved.
- Guided presets and the multi-material recipe (shared fixtures).
- The privacy promise and the PAXX / Stock / remote-access explanations.

## 7. Intentionally platform-specific

- **Desktop:**
  - navigation rail, dense side-by-side workbench and keyboard shortcuts (Ctrl+1…7);
  - the Advanced Workspace for specialist editing;
  - the local connector for the browser.
- **Android:**
  - touch-first tabs and bottom navigation, 48 dp targets;
  - background alerts and widgets;
  - on-device native slicing through JNI.
- **Web:**
  - top app bar and a stepped Prepare flow using links, landmarks and the native `<dialog>`;
  - projects in browser storage with explicit export;
  - an installable PWA;
  - WebAssembly slicing in a worker;
  - printer access limited by browser rules, with the connector as the answer.
- **Website:** marketing and documentation layout. It links to the apps and never imitates an app dashboard.

## 8. How Nozzle differs structurally from mainstream slicers

Mainstream slicers are organised around a settings tree. The plate sits in the middle, the printer is a profile
dropdown, and monitoring is a separate "device" tab bolted on.

Nozzle is organised around the printers you own and the task in front of you:

- **The home screen is your fleet.** It shows live state, loaded materials and routes, and every place starts from "which
  printer, which task".
- **Materials come from the printer.** Prepare uses what's loaded in the U1's toolheads; it isn't a list of profile
  names.
- **Settings are guided choices first.** Draft, Standard or Fine, supports, infill. Everything else is behind an explicit
  "Advanced", and the full engine is in the Advanced Workspace, a deliberately separate window you enter and leave on
  purpose.
- **Printer-changing actions have one safety model.** Review, confirm, execute once; unknown means check first. This is
  shared across platforms and enforced in shared code, not per screen.
- **One project file everywhere.** It moves between Desktop, Android, Web and other 3MF slicers without losing
  Nozzle's data.

## 9. Package diagrams

```
nozzle-it-all (Desktop, PAXX baseline)                      nozzle-stock-u1-adapter (optional, separate package)
├── Nozzle It All for Desktop (Compose, bundled JRE)          ├── helper process (nozzle-adapter protocol 1.x)
│   ├── printer-api        vendor-neutral model + rules        ├── Snapmaker account (system browser + pasted token)
│   ├── adapter-paxx       LAN Moonraker only                  └── LAN control of stock firmware via shared U1 session
│   ├── project-format     3MF + manifest
│   └── connector          127.0.0.1 only, paired              Never linked by the core: Desktop finds and starts it at
├── Advanced Workspace (Orca-derived, NOZZLE_BRANDING=ON,      run time only when the user enables Stock U1 support.
│   NOZZLE_STOCK_U1=OFF: no Flutter bundle, no 13619 server,
│   no cloud login or sync at startup)
└── U1 printer profiles (flattened, shared with Android)

nozzle-it-all-web (static files served from app.nozzleitall.com with COOP/COEP)
├── app shell (Preact), service worker, manifest
├── nozzle-engine.js + .wasm (libslic3r + Android's shared C++ pipeline, pthreads)
└── U1 profiles (bundled at build time from the same Android assets)
```

Mechanical enforcement:
- `verifyPaxxBaseline` (Desktop runtime classpath);
- `verifyAdapterBoundary` (printer-api imports);
- `verifyNoCloud` (adapter-paxx sources);
- `AdapterRegistry.registerBuiltIn` refuses cloud-capable adapters;
- the fork's `NOZZLE_STOCK_U1=OFF` build.

## 10. Asset inventory

| Asset | Source | Generated copies |
|---|---|---|
| Infill mark (violet, white, black) | `brand/mark-*.svg` | Desktop `resources/brand/`, Web `public/brand/` |
| App icon | `brand/favicon.svg` | `desktop/packaging/icons/nozzle-it-all-{16..512}.png`, `.ico` (Inkscape + ImageMagick), Web `public/brand/icon-*.png`, Android adaptive icon (`app/src/main/res/drawable/ic_launcher*.xml`) |
| Fonts: Space Grotesk, IBM Plex Sans and Mono (OFL 1.1) | `app/src/main/res/font/`, `site-src/assets/fonts/` | Desktop `resources/fonts/`, Web `public/fonts/` |
| Printer profiles (flattened Orca, AGPL) | `app/src/main/assets/slicer_profiles/` | Desktop resources at build time, Web bundle at build time |

Lovart's marketing boards are not used (brand/README.md).
