# Android app audit (read-only), 2026-09-26

Scope: module `:app` (namespace `net.jamesjennison.klippercompanion`, applicationId `com.nozzleitall.app`),
plain-JVM `:domain` and `:transport`. Snapshot of `main` at `d9ee14f` plus the uncommitted working tree.
Nothing was built, run or installed for this audit; every claim below is from source or docs.

Path prefixes used below:
- `A/` = `app/src/main/java/net/jamesjennison/klippercompanion/`
- `D/` = `domain/src/main/kotlin/net/jamesjennison/klippercompanion/`
- `T/` = `transport/src/main/kotlin/net/jamesjennison/klippercompanion/`
- `C/` = `app/src/main/cpp/`

Build facts: `compileSdk 36`, `minSdk 28`, `targetSdk 36`, `arm64-v8a` only, NDK 27.1
(`app/build.gradle.kts:10-20,100`). `:app` depends only on `:domain` and `:transport`
(`app/build.gradle.kts:108-109`). The working tree also adds `:printer-api`, `:adapter-paxx` and
`:stock-u1-adapter` to `settings.gradle.kts:4` (uncommitted, untracked dirs); **`:app` does not depend on
any of them yet**.

## 1. Slicing engine

- Native OrcaSlicer (libslic3r) via JNI, compiled into `libslic3rengine.so`
  (`app/src/main/java/org/orcaslicer/engine/NativeEngine.kt:16`).
- Pin: `engine/ENGINE_PIN.json` - upstream `SoftFever/OrcaSlicer` commit `824b216f…`, patch
  `engine/android-headless-engine.patch` (sha256 `23913586…`), plus hash-pinned deps (CGAL, OCCT, assimp,
  boost 1.86, cereal, draco, …). The SBOM task embeds the pin (`app/build.gradle.kts:179`).
- CMake pulls the patched checkout from an absolute path outside the repo:
  `ORCASLICER_ENGINE_ROOT=/mnt/faststorage/orcaslicer-android-engine` (`C/CMakeLists.txt:33-42,64`), and
  fails if missing (`C/CMakeLists.txt:36-37`). The engine is therefore not buildable from this repo alone.
- JNI surface (`NativeEngine.kt:18-173`, implemented in `C/bridge/slic3r_jni.cpp:101-537`):
  single-file slice, Bambu `.gcode.3mf` bundle slice, multi-object slice (+`Ex` with per-object
  paint/volumes), mesh preview load, object count, mesh cut, paint sessions, cancel, progress.
- Engine glue: `C/bridge/slic3r_engine.cpp` (888 lines). Loads with
  `LoadStrategy::AddDefaultInstances | LoadModel` (`:109-110`), centres on bed (`:120-`), software
  thumbnail renderer for embedded G-code thumbnails (`C/bridge/thumbnail_render.cpp`, used at `:298,314`),
  Bambu bundle via `store_bbs_3mf` (`:318-380`).
- Profiles: 377 printer profile dirs bundled in `app/src/main/assets/slicer_profiles/` (9.0 MB), resolved
  per `SlicingPrinterModel` (`A/SlicingProfilePacks.kt:25-44`, `D/SlicingModelCatalog.kt:6,33`).
- Orchestration: `A/SlicingCoordinator.kt` - `slice()` (`:94`) and `sliceProject()` (`:146`) run under a
  mutex on `Dispatchers.IO`, refuse when the system is low on memory (`:46-49`), write an in-progress
  marker file (`:33-38`), and start/stop `SliceService` around the work (`:94,115,146,202`).

## 2. Model and project formats

- **Room**: `AppDatabase` version **7**, entities `Project`, `ProjectObject`, `Plate`
  (`A/project/AppDatabase.kt:64`), explicit migrations 1->7, no destructive fallback (`:20-74`). Only
  `app/schemas/.../7.json` is exported (earlier schema JSONs absent).
- `Project` fields: id, name, created/modified, `targetPrinterId`, `calibration`, `attribution`
  (`A/project/Project.kt:18-29`). `ProjectObject`: `sourceFileUri`, `plateId`, `offsetXMm`, `offsetYMm`,
  `rotationZDeg`, `scale` (uniform), material id/name/nozzle/bed temps, `toolSlotIndex`, `paintJson`,
  `volumesJson` (`Project.kt:50-78`). `Plate`: id, projectId, position, name (`Project.kt:88`).
- Model files copied into `filesDir/projects/<projectId>/…` (`A/project/ProjectFileStore.kt:15-16,25`).
- **`.nozzleproj`** (`A/project/ProjectArchive.kt`): ZIP with `project.json` + `models/<file>`
  (`:12,45-51`), `FORMAT = 1` (`:26`); import validates names (`stl|3mf|obj` only, no paths), sizes
  (256 MB/model, 1 GB total), 500-object cap, finite numbers, and re-encodes paint/volumes (`:27-31,56-108`).
  Export/import entry points: `A/project/ProjectViewModel.kt:308-338`; UI `A/ProjectEditorScreen.kt:599`.
- **What the archive drops**: `Project.targetPrinterId`, `calibration`, `attribution`, `createdAt`,
  `modifiedAt` are not written (`ProjectArchive.kt:35-44`); import stamps new ids/times
  (`ProjectViewModel.kt:318-330`). No slice settings (quality/infill/support/brim) are stored anywhere in
  the project - they are editor-local `remember` state (`A/ProjectEditorScreen.kt:245-248`).
- **3MF input**: accepted for import (`A/SlicingCoordinator.kt:265-272`) and stored verbatim as one
  `ProjectObject` per file (`ProjectViewModel.kt:143-165`). The engine loads geometry only
  (`LoadStrategy` lacks `LoadConfig`, `slic3r_engine.cpp:109-110`), so embedded print/filament settings,
  per-object settings, plate layout, colour painting and modifiers inside a source 3MF are **not** used;
  a multi-object 3MF becomes one app object. `.gcode.3mf` is excluded as a model (`SlicingCoordinator.kt:271`).
- **3MF output**: only Bambu `.gcode.3mf` sliced bundles (`Metadata/plate_1.gcode` + thumbnail)
  (`slic3r_engine.cpp:318-380`, `A/SliceAndPrintPanel.kt:352-363`). There is **no project 3MF export**.
- Share intent accepts `application/octet-stream`, `application/x-gcode`, `text/plain` only
  (`app/src/main/AndroidManifest.xml:13`); no `model/stl`/`model/3mf` MIME types and no VIEW filter for
  `.nozzleproj`/`.3mf`.

## 3. Rendering

- 3D model/arrange view: raw OpenGL ES 3.0 in a `GLSurfaceView` via `AndroidView`
  (`A/ModelViewer.kt:3-4,49-51,447`); meshes come from `nativeLoadMeshPreview` off the GL thread
  (`ModelViewer.kt:74-77`). Shared shader helpers `A/GLSupport.kt:13-19`.
- Sliced preview: GLES30 line toolpath renderer with per-tool colours (`A/SlicedPreview.kt:73,172-176`),
  used in the project editor (`A/ProjectEditorScreen.kt:945`) and the share flow (`A/SliceAndPrintPanel.kt:306`).
- 2D Compose `Canvas` layer view for printer-side/live G-code (`A/LayerPreview.kt:26,56`) and bed mesh
  (`A/BedMesh3DView.kt:45-92`). G-code parsing is in `D/GcodePreview.kt`.
- No Filament/Sceneform/WebGL.

## 4. Background work

- `SliceService` - foreground service, `specialUse`, `START_NOT_STICKY`, 1 s progress notification
  "Slicing on this device" (`A/SliceService.kt:14-56`; manifest `:14-17`).
- `PrintMonitorService` - foreground service, `specialUse`, `START_STICKY`, polls every saved printer every
  20 s (`A/PrintMonitorService.kt:20-43,58-88`), debounced disconnects (`:73-77`), alerts via `PrintAlerts`
  (`:78`, `D/PrintAlerts.kt:36`), also refreshes the widget (`:82-86`). Opt-in toggle in Settings
  (`A/MainActivity.kt:501`). Manifest `:18-21`.
- Glance home-screen widget `NozzlePrinterWidget` (`A/NozzlePrinterWidget.kt`; manifest `:26-29`).
- No WorkManager, no JobScheduler.
- Foreground polling in `PrinterModel` (a `ViewModel`, `A/PrinterModel.kt:59`) every 2 s
  (`:346`), tolerating one failed poll (`:293-298`); stopped entirely on `ON_STOP`
  (`A/MainActivity.kt:150-158`, `PrinterModel.kt:235-243`).

## 5. Storage and persistence

- Room DB (projects) - section 2. Project model files in `filesDir/projects`.
- SharedPreferences: `printer` (profiles), `alerts`, `appearance`, `custom_slice_profiles`,
  `bambu_cert_pins` (`A/NozzleApp.kt:12`), macro prefs.
- Secrets (API keys, Bambu access codes, Bespok3d tokens): `EncryptedSharedPreferences`
  `printer-credentials` with Keystore AES-256-GCM master key (`A/CredentialStore.kt`,
  `A/Bespok3d.kt` `Bespok3dConnectionStore`).
- Cache: `slice-inputs`, `sliced-output`, `slicer-profiles-active`, `live-file`, `bambu-prints`, `mmf-*`,
  `import-*`. Slice interruption marker `filesDir/slice-in-progress` (`A/SlicingCoordinator.kt:34`).
- Android backup disabled: `allowBackup="false"` (manifest `:12`) and all domains excluded
  (`app/src/main/res/xml/data_extraction_rules.xml`). Manual passphrase-encrypted printer backup
  (PBKDF2 210k + AES-GCM) covers printers only, not projects (`D/SettingsBackup.kt:6-22`).

## 6. Printer protocols per `PrinterKind`

`enum class PrinterKind { GENERIC_KLIPPER, SNAPMAKER_U1_PAXX, BAMBU_LAB, PRUSA_LINK, OCTOPRINT, SNAPMAKER_U1 }`
(`D/M1Data.kt:8`). Routing: `A/PrinterModel.kt:49-57`. Capabilities: `D/PrinterCapabilities.kt:78-118`.

| Kind | Transport | Send path | Real-hardware verified flag |
|---|---|---|---|
| GENERIC_KLIPPER | Moonraker HTTP/WS (`T/Moonraker.kt`) | `LiveFileChanges` upload + `printer/print/start` (`A/ProjectEditorScreen.kt:490-499`, `T/Moonraker.kt:59`) | true (`PrinterCapabilities.kt:82`) |
| SNAPMAKER_U1_PAXX | Moonraker | same | true (`:90`) |
| SNAPMAKER_U1 (stock) | Moonraker + Bespok3d | same | **false** (`:98`) |
| BAMBU_LAB | MQTT/TLS + FTPS (`T/BambuPrinterService.kt`, `T/BambuMqttConnection.kt`, `T/BambuFtpsClient.kt`) | `.gcode.3mf` bundle via `BambuPrintRequest` (`ProjectEditorScreen.kt:973-976`) | false (`:104`) |
| PRUSA_LINK | HTTP + Digest (`T/PrusaLinkPrinterService.kt`) | upload-and-print (`ProjectEditorScreen.kt:978-981`) | false (`:114`) |
| OCTOPRINT | HTTP API key (`T/OctoPrintPrinterService.kt`) | same as Prusa branch | false (`:109`) |

LAN discovery: `T/PrinterScanner.kt` sweeps the private /24 (`A/LocalNetwork.kt:5-12`), classified by
`D/PrinterDiscovery.kt:20-31`.

## 7. PAXX behaviour (existing)

- PAXX detection is **manual**: Moonraker cannot distinguish stock vs PAXX, so a discovered U1 defaults to
  `SNAPMAKER_U1` (stock) and the owner switches the type (`D/PrinterDiscovery.kt:26-28`). Kind chips
  "Snapmaker U1 (stock)" / "Snapmaker U1 (PAXX)" in `A/AddPrinterWizard.kt:127-128` and `A/M1Panels.kt:88-89`.
- multiACE panel (lane status/RFID, dryer, load/unload, switch) gated to `SNAPMAKER_U1_PAXX` only
  (`D/AceControls.kt:6-30`, `hasMultiAce = true` at `PrinterCapabilities.kt:90`; UI `A/AcePanel.kt`).
  Built without the owner having multiACE hardware (`AceControls.kt:8-10`).
- Bespok3d is hidden for PAXX and refused by the preflight (`PrinterCapabilities.kt:85-86`,
  `T/Bespok3dU1Preflight.kt:74`).
- Uncommitted `:adapter-paxx` (`PaxxLanAdapter.kt`, `U1Protocol.kt`, 13 tests) implements a new adapter
  API with a normalized state map (`U1Protocol.kt:31-40`) but is not wired into `:app`.

## 8. Stock U1 behaviour (existing)

- Same Moonraker LAN transport as PAXX; plus Bespok3d (`hasBespok3d = true`, `PrinterCapabilities.kt:95-99`).
- Enrollment: SSH to `root@<host>:22` with a per-call, never-persisted password and host-key pinning
  (`A/Bespok3d.kt:6-35`, `T/Bespok3dSsh.kt`, `T/Bespok3dU1Preflight.kt`, `T/Bespok3dU1Enrollment.kt`),
  installing a signed (OpenPGP) bootstrap bundle from `app/src/main/assets/bespok3d/bootstrap.zip`
  (`T/Bespok3dBootstrapPackages.kt`).
- Daemon: HTTPS on port 4269 with exact-certificate pinning (`T/Bespok3dClient.kt:343,601,675-703,707`).
  Plugin catalog from `raw.githubusercontent.com/Bespok3d/main-index` and GitHub releases
  (`Bespok3dClient.kt:190,321,441,718`) - an internet dependency for that feature only.
- Marked "Not verified on real hardware yet" in the UI (`A/PrinterTiles.kt:71-73`).
- Uncommitted `:stock-u1-adapter` (`StockU1Helper.kt`, 4 tests) not wired into `:app`.

## 9. Flutter

None. `grep -riE 'flutter|io\.flutter'` over `app`, `domain`, `transport`, root Gradle files,
`gradle.properties` and `gradle/` returned no matches; no `pubspec.yaml` exists. UI is Jetpack Compose +
Material 3 (`app/build.gradle.kts:110-115`).

## 10. Cloud dependencies

- MyMiniFactory Discover (optional tab): `T/MyMiniFactoryClient.kt:25` (`www.myminifactory.com/api/v2/`),
  OAuth `T/MyMiniFactoryAuth.kt:24`, redirect `https://nozzleitall.com/mmf-auth` -> `nozzleitall://mmf-auth`
  (`D/MyMiniFactory.kt:193-195`, manifest `:13`). API/client key from BuildConfig or user entry
  (`A/MmfSettings.kt:11-20`, `A/DiscoverScreen.kt:213`).
- Bespok3d plugin catalog/downloads from GitHub (section 8).
- Remote images (`A/RemoteImage.kt:32`, https only).
- Nothing else: no analytics, no crash reporting, no Nozzle-operated backend.

## 11. Offline behaviour

- Slicing is fully on-device with bundled profiles; importing, arranging, slicing and previewing need no
  network. Printer control needs LAN (or a user-supplied Tailscale/Cloudflare/https address,
  `A/RemoteAccessHelpPanel.kt:15-24`). Discover needs internet.
- No `ConnectivityManager.NetworkCallback` anywhere; network loss surfaces only as poll failures
  (`PrinterModel.kt:293-298`, `PrintMonitorService.kt:90-99`). `usesCleartextTraffic="true"` (manifest `:12`).
- No offline banner or explicit "no internet" state for Discover (not found in `A/DiscoverScreen.kt`).

## 12. Tests

- Unit (`app/src/test`): **92 files, 625 `@Test`**. Largest: `MoonrakerTest` 25, `PrinterModelTest` 24,
  `LiveFileChangesTest` 20, `PrintAlertsTest` 19, `ToolSlotsTest` 14, `SlicingModelCatalogTest` 14; also
  `ProjectArchiveTest`, `MyMiniFactoryClientTest`, `SliceValidationTest`.
- Device (`app/src/androidTest`): **77 files, 315 `@Test`**. Key suites: `CompanionScreenTest` 16,
  `MmfDiscoverDeviceTest` 16, `PlateToolbarDeviceTest` 12, `MmfLiveDeviceTest` 12,
  `SlicingCoordinatorDeviceTest` 11, `SlicingProfilePacksDeviceTest` 10, `NativeEngineSmokeTest`,
  `BasicSlicingDeviceTest`, `MultiObjectSlicingDeviceTest`, `ToolAssignmentSlicingDeviceTest`,
  `BambuBundleDeviceTest`, `ProjectViewModelDeviceTest`, `ProjectPersistenceDeviceTest`,
  `InterruptedSliceNoticeDeviceTest`, `MemoryPressureDeviceTest`, `SliceCancelDeviceTest`,
  `LivePrinterReadOnlyTest`/`LiveFileHardwareTest`/`LivePreviewHardwareTest` (opt-in real printer).
- `:domain` and `:transport` have no own tests (their logic is tested from `app/src/test`). Uncommitted
  modules: `:printer-api` 16, `:adapter-paxx` 13, `:stock-u1-adapter` 4.
- CI: Device Farm, suite split into 4 shards; Pixel 9a run 2026-09-25: 282 tests, 0 failures
  (`docs/WORK_ORDER.md:2516-2521`).

## 13. Consumer journeys and device evidence

Flow in code: Prepare tab -> `ProjectEditorScreen` stages `EDIT, SLICING, REVIEW, PRINTER_READY, STAGED`
(`A/ProjectEditorScreen.kt:80`), editor sub-tabs Model / Settings / Printer (`:571-575`), Slice (`:907`),
3D preview (`:945`), upload (`:490-499`), explicit Start print (`:970-984`). Share-intent single-model path:
`A/SliceAndPrintPanel.kt` (`A/MainActivity.kt:885`).

| Step | Status | Evidence |
|---|---|---|
| Import (file picker, share, MMF, .nozzleproj) | Works | `ProjectViewModelDeviceTest`, `MmfNavigationDeviceTest`, `M2ShareIntentTest`; `ProjectArchiveTest` |
| Prepare (arrange, rotate, scale, cut, paint, plates, materials, tools) | Works on device | `ProjectWorkspaceDeviceTest` (gesture fixes, `:141-223`), `ProjectPlatesDeviceTest`, `PaintSessionDeviceTest`, `MultiMaterialDeviceTest` |
| Slice | Works on device (Razr 2023, Pixel 9a) | `WORK_ORDER.md:403-409` (bed centring verified on Razr), `:1978-1990` (real U1 profile multi-tool T0/T1), `:84` (354/371 profiles sliced on Pixel 9a) |
| Preview | Works on device | `WORK_ORDER.md:420-426` (thumbnails decoded from device slice); `P09_VIEWER_ACCEPTANCE.md:26` |
| Send (upload) | Moonraker upload accepted on Razr against real printer | `docs/P08_LIVE_ACCEPTANCE.md:3-9` |
| Start print from on-device slice | **Not carried to completion** | `WORK_ORDER.md:339-344` and `:437-440`: "no physical print has been carried through to completion"; the one real attempt (CC1, before the centring fix) was cancelled after 14 s (`:390-399`) |
| Monitor | Works on device with real printers | heater controls PASS on real U1 (`M2_HEATER_CONTROLS_ACCEPTANCE.md:3`), widget with real print data on Razr (`WORK_ORDER.md:134-137`), `PRINTER_TILES_ACCEPTANCE.md:5`, `M1_ACCEPTANCE.md:16` |
| Bambu / Prusa / OctoPrint send | Unverified on hardware | `PrinterCapabilities.kt:104,109,114`; `WORK_ORDER.md:2429` |

## 14. Navigation vs target sitemap

Bottom bar (`A/MainActivity.kt:412-415`): **Home (0), Control (1), Files (2), Prepare (3), Discover (5),
Settings (4)**. Navigation is an `Int` tab + boolean overlay flags; no Navigation-Compose, no back stack
beyond one `BackHandler` (`:331`).

| Target | Status | Where today |
|---|---|---|
| Home / Printers | Merged | Home = printer tiles overview + per-printer detail (`:275,555-560`); printer add/edit/forget lives under Settings (`:508`) |
| Projects | Merged | "Projects" section inside Files, toggled by `showProjects` (`:248-261,359`) |
| Prepare | Exists | Prepare tab -> `ProjectEditorScreen` (auto-creates "New print …", `:326-329,942`) |
| Slice and Preview | Merged | Inside Prepare editor stages (`ProjectEditorScreen.kt:80,907,945`) |
| Send | Merged | Inside Prepare editor (`ProjectEditorScreen.kt:490-499,970-984`); Files tab "Start print" for existing printer files (`MainActivity.kt:845`) |
| Monitor | Merged | Home detail + Control tab (heaters, fans, jog, camera, console, macros) |
| Materials and Toolheads | Missing as a destination | Scattered: material picker in editor (`ProjectEditorScreen.kt:816,1124`), Toolheads/ACE/Spoolman as Control-tab overlays (`MainActivity.kt:210,225,238`) |
| Settings | Exists | Settings tab (printers, alerts, appearance, backup, self-check, about; `:450-510,864`) |
| (extra) Discover | Extra | MyMiniFactory tab (`:428-432`) |

## 15. Lifecycle resilience

Evidence:
- `MainActivity` has no `configChanges` (manifest `:13`) - rotation recreates the activity.
- `PrinterModel` is a real `ViewModel` (`MainActivity.kt:141`) - survives rotation; stops polling on
  `ON_STOP` and resumes on `ON_START` (`:150-158`); an in-flight command during backgrounding is reported as
  "Command interrupted. Outcome unknown" (`PrinterModel.kt:239-241`).
- `tab`, `detailAddress`, dialogs, file filters use `rememberSaveable` (`MainActivity.kt:239-261,273-274,355-359,377`).
- Slice process-death: marker file + "interrupted slice" notice on next start (`SlicingCoordinator.kt:33-38`,
  `MainActivity.kt:299`); tested by `InterruptedSliceNoticeDeviceTest` (uses `scenario.recreate()`, `:22,27`).
- Low memory: pre-slice check refuses with a message (`SlicingCoordinator.kt:46-49`); `MemoryPressureDeviceTest`.
- Project data survives process death via Room (`ProjectViewModelDeviceTest`, simulated by a second VM
  instance, `:20`).
- Network change: one-failure tolerance in foreground poll (`PrinterModel.kt:293-298`), `ConnectionDebounce`
  in background monitor (`PrintMonitorService.kt:73-77`).

Gaps:
- `editingProjectId` and `editingNewProjectName` are plain `remember` (`MainActivity.kt:281,317`): rotation
  closes an open project; on the Prepare tab the `LaunchedEffect(tab)` then auto-creates a new "New print"
  project (`:326-329`).
- `ProjectViewModel` is constructed with `remember { … }`, not `viewModel()` (`ProjectEditorScreen.kt:88`);
  editor `stage`, `editorTab`, `stagedFilename`, slice settings are `remember` (`:236,241,245-248,265`) - lost on
  recreation.
- Slicing runs in `rememberCoroutineScope` (`ProjectEditorScreen.kt:84,420-426`): recreation cancels the
  awaiting coroutine, so a completed slice's result is not delivered to the new UI.
- `SliceService` is `START_NOT_STICKY` with no resume; after process death the slice must be rerun.
- No `onTrimMemory` / `onLowMemory` handling (grep found none); GL surfaces and meshes are not released on
  memory pressure.
- No `NetworkCallback`; no reconnect on Wi-Fi return while foregrounded beyond the 2 s poll loop, and the
  background monitor waits up to 20 s.
- No recreation test for the project editor or share flow; only the slice notice is tested with `recreate()`.

## 16. User-facing strings exposing internals

| String (abridged) | Location |
|---|---|
| "Klipper is ${state}. Controls unavailable." (Klippy state leaks: "startup", "shutdown") | `A/PrinterModel.kt:307`; source `T/Moonraker.kt:227` |
| "No camera configured in Moonraker." | `A/PrinterModel.kt:341` |
| "multiACE command changed. Review it again." | `A/PrinterModel.kt:409` |
| "multiACE", "No multiACE hardware detected…", "Cannot verify multiACE." | `A/AcePanel.kt:42,58,62,68`; `D/PrinterService.kt:55` |
| "Only needed if Moonraker requires authentication; copy it from Fluidd's or Mainsail's settings." | `A/M1Panels.kt:82` |
| "Generic Klipper and Snapmaker U1 both talk to Moonraker … Bespok3d … multiACE …" | `A/M1Panels.kt:85` |
| "Which bundled OrcaSlicer profile to use…" | `A/M1Panels.kt:98` |
| "Page of available Moonraker records…" | `A/M1Panels.kt:134` |
| "Klipper / Moonraker (version)" discovery label | `D/PrinterDiscovery.kt:29` |
| "Recent Moonraker cache, up to 200 entries…" | `A/ConsolePanel.kt:62` |
| "…Moonraker does not promise this protection…" | `A/FileChangePanel.kt:59` |
| Remote-access help naming Moonraker | `A/RemoteAccessHelpPanel.kt:15-21` |
| "Restart Klipper to apply a saved change… MCUs are not reset." | `A/ConfigFilePanel.kt:129` |
| Spoolman / "moonraker-spoolman component" | `A/SpoolmanPanel.kt:17-44` |
| Bespok3d / "root SSH password" / SSH host key texts / "jinni" version | `A/Bespok3dPanel.kt:88-172`; `T/Bespok3dU1Preflight.kt:63-111` |
| Self-check rows "SSH keys (jsch)", "Bambu MQTT/TLS client (HiveMQ, Netty)", "…FTPS client (commons-net…)" shown as PASS/FAIL in Settings | `A/SelfCheck.kt:53-61`; `A/MainActivity.kt:453-458` |
| "…not every OrcaSlicer setting…" | `A/SliceAndPrintPanel.kt:260` |
| "A Bambu Lab printer in LAN mode exposes no macros, console or configuration…" | `A/MainActivity.kt:741-749` |
| "…COSMOS 26.07.0+" firmware warning | `A/AddPrinterWizard.kt:168` |
| "Metadata/plate_1.gcode" error context: "The sliced bundle has no embedded G-code to preview." | `A/SliceAndPrintPanel.kt:363` |

Acceptable-with-explanation (power features): macros, console, config editor, bed mesh, G-code file
lists (`A/MainActivity.kt:632,721-735,837`).

## 17. Printer-state wording vs shared vocabulary

Shared vocabulary: Offline, Connecting, Starting, Ready, Printing, Paused, Finished, Cancelled, Error, Unknown.

- Android carries Moonraker's raw `print_stats.state` strings (`standby, printing, paused, complete,
  cancelled, error`) end to end, and non-Klipper services map *into* those strings
  (`T/BambuPrinterService.kt:267-288`, `T/PrusaLinkPrinterService.kt:74-75`).
- Displayed by title-casing the raw string: `A/PrinterTiles.kt:69`, `A/NozzlePrinterWidget.kt:158`,
  `A/AddPrinterWizard.kt:102`; Settings list shows "Connected • standby" (`A/MainActivity.kt:527`).
- `displayState` rewrites `complete`/`cancelled` to `standby` (`D/PrinterService.kt:14`) - **Finished and
  Cancelled are never shown**; "Standby" is shown where the vocabulary says Ready.
- Klippy not-ready states (`startup`, `shutdown`, `error`, "not ready") surface raw
  (`T/Moonraker.kt:227`, `A/PrintMonitorService.kt:95`) instead of Starting/Error.
- "Unavailable" (`PrintMonitorService.kt:98`), lowercase "offline" (`NozzlePrinterWidget.kt:132`),
  "Offline" (`MainActivity.kt:527`) - no Connecting state; no Unknown state (Bambu unknown
  `gcode_state` and Prusa unknown states fall back to `standby`, `BambuPrinterService.kt:288`,
  `PrusaLinkPrinterService.kt:75`; Prusa adds non-vocabulary `busy`).
- The uncommitted `:printer-api` already defines `PrinterState` with the shared glossary ids
  (`printer-api/src/main/kotlin/com/nozzleitall/printer/PrinterModel.kt:32-`), and `:adapter-paxx` maps
  Moonraker to it (`U1Protocol.kt:31-40`, `standby -> READY`, `complete -> FINISHED`); `:app` does not use it.

## 18. What `.nozzleproj` needs to round-trip with a canonical 3MF + Nozzle manifest

Today `.nozzleproj` is a private ZIP (`project.json` + raw model files), not a 3MF. To share one format
with Desktop and Web (3MF with `Metadata/` or `Auxiliaries/.nozzle/manifest.json`):

1. **Geometry into the 3MF core model**: write each object's mesh into `3D/3dmodel.model` (Android keeps
   the original STL/OBJ/3MF file). Needs a 3MF writer on Android; the engine has `store_bbs_3mf`
   (`slic3r_engine.cpp:375`) but only for sliced bundles.
2. **Transforms**: map `offsetXMm/offsetYMm/rotationZDeg/scale` to the 3MF `<item transform>` matrix; the
   Android model cannot represent X/Y rotation or non-uniform scale (`Project.kt:55-58`) - importing
   such 3MFs would be lossy unless the schema grows.
3. **Multi-object source 3MFs**: split into one object per `<object>`/`<item>` (today one file = one object).
4. **Plates**: map `Plate` + `plateId` to a plate list in the manifest (Orca uses
   `Metadata/model_settings.config`; decide canonical location).
5. **Materials/tools**: `materialId`, display name, temps, `toolSlotIndex` -> per-object extruder and a
   filament table in the manifest.
6. **Paint and volumes**: `paintJson`/`volumesJson` are app-specific codecs (`PaintCodec`, `VolumeCodec`);
   either keep them as manifest payloads or convert to 3MF-native `slic3rpe:mmu_segmentation` and modifier
   volumes for Orca interop.
7. **Project-level fields currently dropped**: `targetPrinterId` (needs a portable printer/profile
   reference, e.g. `SlicingPrinterModel` id, not a LAN address), `calibration`, `attribution`
   (MMF licence text), created/modified timestamps.
8. **Slice settings**: quality/infill/support/brim and advanced overrides are not persisted at all; add to
   the manifest (and Room) for Desktop/Web parity.
9. **Versioning and validation**: carry `format`/schema version in the manifest; keep the current importer's
   safety limits (`ProjectArchive.kt:26-31`) for 3MF too; tolerate unknown keys for forward compatibility.
10. **Engine import**: to honour settings embedded by Desktop, the loader would need `LoadConfig`
    (`slic3r_engine.cpp:109-110`) or the app must read the manifest itself and pass overrides.
11. **Intent filters**: add `model/3mf` / `.nozzle.3mf` VIEW/SEND handling (manifest `:13`).
