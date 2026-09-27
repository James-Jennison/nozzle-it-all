# Stock Snapmaker U1 dependency audit: Flutter, Snapmaker cloud, LAN Moonraker

Date: 2026-09-26. Read-only audit; nothing was modified in either tree.

- Fork: `/mnt/faststorage/Snapmaker-Orca/hybrid-orca-intake`, branch `codex/upstream-feature-intake`, HEAD `11bea5c981`.
  Its merge base with upstream is exactly upstream HEAD `cbf7bbb0b3`, so every difference below is fork work.
- Upstream: `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer`, branch `main`, HEAD `cbf7bbb0b3`.
- All `file:line` references are to the fork unless marked "upstream". Paths are relative to the repo root.
- Flutter bundle evidence is given as byte offsets into
  `resources/web/flutter_web/main.6f0d9ddad48e468a.js` (bundle 2.3.35, build 20260911060824), found with `grep -b`.

## 1. Short answer

1. Upstream Snapmaker Orca starts the Flutter UI, the Snapmaker session and the port-13619 server whether or not
   you have a U1. Flutter is not optional there: `copy_web_resources()` runs on every start
   (upstream `GUI_App.cpp:2766`), and Home and Device load Flutter URLs (upstream `WebViewDialog.cpp:40`,
   `PrinterWebView.cpp:31`).
2. The fork has a runtime off switch. `GUI_App::flutter_ui_enabled()` is true only when `legacy_flutter_ui=true`
   (`GUI_App.cpp:5605-5609`). It was added in `58b8b115f7` (today). When it is off:
   - Home, Device and send run natively.
   - The `sw_*` bridge ignores webview messages (`SSWCP.cpp:8521-8525`).
   - The Snapmaker token is not restored (`GUI_App.cpp:2373`).
3. The switch does not remove anything from the build or from startup:
   - paho-MQTT, SSWCP, the login dialogs and the 44 MB Flutter bundle are still compiled, linked and installed.
   - The fixed-port HTTP server still starts on every launch (`GUI_App.cpp:1346-1351`).
   - A hidden webview dialog is still created (`GUI_App.cpp:1221`).
   - SnapLog is still initialised (`GUI_App.cpp:3292-3310`).
4. The switch has one bypass. When the `websocket_debug` preference is on, the debug WebSocket server drives the
   whole `sw_*` bridge. It skips the Flutter check (`SSWCP.cpp:8567` vs `8523`) and listens on all interfaces
   (`WebSocketDebugServer.cpp:30`).
5. Only two groups of Stock U1 workflows truly need the Flutter bundle or the Snapmaker cloud. Everything else can
   run over the printer's LAN Moonraker, and the fork already does most of it natively.
   - **Cloud-only, with no LAN route:**
     - Snapmaker account sign-in and sign-out.
     - Cloud binding and the cloud device list.
     - Away-from-home (WAN) status and control.
     - Cloud print (upload to Snapmaker storage, then the printer pulls the file).
     - The WAN half of timelapse export.
   - **Flutter-only today, but LAN-possible (nobody has built them natively yet):**
     - Stock mDNS discovery.
     - PIN-code LAN pairing, which also needs a Snapmaker user id.
     - The printer file browser and printing from printer storage.
     - Timelapse list, download and delete over LAN.
     - Device rename.
     - Defect-detection config.
     - Purifier.
     - Setting temperatures.
     - The G-code console.
     - Bed-mesh abort.
     - The printer exception list.

## 2. How Stock U1 transports work

| Transport | Where | Needs account | Needs Flutter |
|---|---|---|---|
| Snapmaker ID login (`id.snapmaker.com`, `api.snapmaker.com/api/common/accounts/current`) | `WebSMUserLoginDialog.cpp:63-75` (wxWebView on the vendor site). Only entry points are `SSWCP.cpp:5240`, `5315` (`sw_UserLogin` / `sw_AskUserLogin`), via `GUI_App::sm_request_login` `4523` | yes | yes (only Flutter triggers it) |
| Cloud REST (`/user/device/bind` @749463, `/user/device/list` @752339, `/user/device/getMqttCert` @750316, `/user/device/connect/auth` @749868, `/user/device/upload/create` @4022979, `/oauth2/token` @3499991, `https://api.snapmaker.com/api` @765123) | inside the Flutter JS only; no C++ caller | yes | yes |
| Token revoke | `GUI_App.cpp:4621` (`api.snapmaker.*/api/oauth2/revoke`) on logout | yes | no, but only reached from logout |
| LAN pairing: plain MQTT to printer `:1884`, `server.client_manager.request_pin_code` on `cloud/config/request` with Snapmaker `userid`/`nickname` | `SSWCP.cpp:4450-4505` | user id from account | yes |
| LAN key exchange `server.request_key`, then mqtts to printer with printer-issued CA/cert/key | `MoonRaker.cpp:1080-1120`, `1150-1275` (`Moonraker_Mqtt`, `MoonRaker.hpp:179`) | no (after pairing) | yes (driven by `sw_*`) |
| Cloud MQTT relay (WAN `link_mode`) | `SSWCP.cpp:4712-4730`, `5139-5146`; `MoonRaker.cpp:2426` `async_start_cloud_print`, `2486` `async_pull_cloud_file` | yes | yes |
| mDNS `_snapmaker` service (TXT `sn, version, machine_type, link_mode, userid, ip`) | `SSWCP.cpp:2042-2130` (`sw_StartMachineFind`), `GUI_App.cpp:3351-3361`, `SSWCP.cpp:4618-4660` (reconnect lookup) | no | yes (no native caller) |
| Moonraker HTTP + `/websocket` JSON-RPC on printer `:80` proxy or `:7125` | native: `U1LanPrintHost.cpp:44-220`, `MoonrakerLive.cpp:29-215`. Flutter LAN send also uses a plain HTTP upload (`SSWCP.cpp:840-870`, `upload.use_3mf=false`) | no | no |

## 3. Workflow table

Columns:

- **LAN on stock?** means whether stock U1 firmware exposes the capability on the LAN.
- **PAXX?** means whether a PAXX-only Nozzle build needs the capability.
- **Native in fork** says whether the fork already has a non-Flutter replacement.
- **Validated** refers to the evidence in section 6.

| Workflow | Stock mechanism (evidence) | LAN on stock? | PAXX? | Native in fork | Validated |
|---|---|---|---|---|---|
| Sign-in / sign-out | Flutter `sw_UserLogin`/`sw_UserLogout` (`SSWCP.cpp:5231`, `5323`) opens `SMUserLogin` (`GUI_App.cpp:4533-4565`) on id.snapmaker.com. Logout clears the `sm_user` token and revokes it (`GUI_App.cpp:4571-4621`). Session restore at startup `2366-2382` (restore is gated; reconcile is not). | No, cloud only | No | None needed. With the switch off the entry points are unreachable. | n/a |
| Device binding / pairing | Cloud bind `/user/device/bind`, `device_bind_cloud` (JS). LAN pairing uses a PIN over MQTT `:1884` plus the Snapmaker user id (`SSWCP.cpp:4450-4505`), then `server.request_key` for an mqtts cert (`MoonRaker.cpp:1080-1120`). A default LAN access code `12345678` is also used (`SSWCP.cpp:4680-4684`). | Pairing is LAN, but needs an account user id. Plain Moonraker needs no pairing. | No | Pairing is replaced by a typed IP in the printer connection dialog: `Plater.cpp:2409-2413` opens `PhysicalPrinterDialog`, which writes `print_host` to the printer preset (`PhysicalPrinterDialog.cpp:95`). | Not formally |
| Discovery | mDNS `snapmaker` (`SSWCP.cpp:2042`, `GUI_App.cpp:3351-3361`). Bambu SSDP via `m_agent->start_discovery` (`GUI_App.cpp:1252`) only runs if the Bambu network plugin is loaded (`GUI_App.cpp:3501`). It is not U1-related. | Yes (mDNS is LAN) | Yes (PAXX discovery is Nozzle's own) | **Missing.** `U1LanPrintHost::has_auto_discovery()==false` (`U1LanPrintHost.hpp:40`). The address falls back to Flutter-saved `app_config->get_devices()` (`NativePrinterPanel.cpp:53-61`, `Plater.cpp:21816-21822`). | n/a |
| Device list | Cloud `/user/device/list` plus locally persisted `DeviceInfo` (`sw_GetLocalDevices` `SSWCP.cpp:6788`, `sw_AddDevice` `6879`, `sw_RenameDevice` `6897`) | Local list yes; cloud list no | No | One printer per printer preset (`print_host`). No multi-device list. | n/a |
| Status | `sw_SubscribeMachineState` (`SSWCP.cpp:2460`) over mqtts, LAN or WAN | Yes | Yes | `MoonrakerLiveClient`: HTTP `/printer/objects/list` and `/server/webcams/list`, then a websocket subscribe (`MoonrakerLive.cpp:95-171`). The snapshot model is `libslic3r/PrinterSnapshot.*`. The panel is `NativePrinterPanel.cpp:471-560`. | Unit (captures) plus implied hardware use |
| Camera | Flutter `sw_CameraStartMonitor` (`SSWCP.cpp:3766`) sends `camera.start_monitor` over MQTT (`MoonRaker.cpp:2194-2205`): low-rate snapshots | See caveat below | Yes | `DirectLanPage` loads `/webcam/webrtc`, with MJPEG `/webcam/stream.mjpg` as fallback (`DeviceWorkspace.cpp:69-171`, `372-385`). Stream selection comes from `/server/webcams/list`. | Milestone 1A architecture plus commits `f78eeb99e1`; no stock-firmware record |
| File upload | Flutter LAN: `Moonraker::upload` HTTP (`SSWCP.cpp:840-870`). WAN: in-WebView staging, capped at 32 MB (`SSWCP.cpp:831-838`) | Yes | Yes | `U1LanPrintHost::upload` does POST `/server/files/upload` with no total timeout (`U1LanPrintHost.cpp:118-156`) | Implied hardware (`0990d8e3e8` saw a real upload succeed) |
| Print start + filament to toolhead map | Flutter pre-print dialog (`WebPreprintDialog`, `Plater.cpp:21663-21720`), `sw_GetFileFilamentMapping` `SSWCP.cpp:3449`, `sw_StartLocalPrint` `2939`, `Moonraker_Mqtt::async_start_local_print` `MoonRaker.cpp:2456` | Yes. `server.files.start_local_print` is websocket/MQTT-only on the U1. | Yes | `U1SendDialog` plus `U1LanPrintHost::start_print`: JSON-RPC on `/websocket` with `MAP_TABLE`, bed-level, flow and shaper calibration, and timelapse (`U1LanPrintHost.cpp:160-200`, `U1PrintTask.hpp:32-115`). Routing is at `Plater.cpp:21643`. Mixed (Full Spectrum) filaments are already resolved to real extruders (`Plater.cpp:21844-21845`). | Unit 7 cases/48 assertions pass. Implied hardware (`0990d8e3e8` fixed an HTTP 404 found on a real send). |
| Pause / resume / cancel | `sw_MachinePrintPause/Resume/Cancel` (`SSWCP.cpp:2688`, `2711`, `2734`) | Yes | Yes | `/printer/print/pause`, `resume`, `cancel` (`MoonrakerLive.hpp:44-46`). Stop asks for confirmation (`NativePrinterPanel.cpp:740-748`). | Implied hardware (`19ce922e81` crash found on the first real command) |
| Filament sync / RFID | `sw_UpdateMachineFilamentInfo` (`SSWCP.cpp:2874`), then `SSWCP_Instance::apply_filament_info` (`SSWCP.cpp:1732`) | Yes: `print_task_config` + `extruder*` objects. `FILAMENT_DT_CLEAR/UPDATE` works on stock. Editing a tag-read spool needs `FORCE=1` (PAXX). | Yes | Sync: `U1LanPrintHost::query_filament_report` (`U1LanPrintHost.cpp:76-100`), called at `Plater.cpp:9238-9252`. It still calls `SSWCP_Instance::apply_filament_info`. Edit and re-read tag: `NativePrinterPanel.cpp:984-1010`, `U1PrintTask.hpp:83-94`. | Source test; the unit binary predates the set/reread tests (see 6) |
| Away-from-home | WAN `link_mode`, cloud MQTT cert `/user/device/getMqttCert`, `sw_StartCloudPrint` (`SSWCP.cpp:2913`), `sw_PullCloudFile` (`2849`) | No | No (PAXX users would use their own VPN) | None, by design | n/a |
| Firmware update | No slicer-side flashing on either path. Flutter only shows the version and an incompatibility notice (`device_firmware_version_not_compatible_desc` @4944986, `machine.system_info`). No `machine.update.*` RPC in the bundle. | n/a | No | n/a | n/a |
| Timelapse | `sw_GetCameraTimelapseInstance`, `sw_DownloadMachineFile` (`SSWCP.cpp:3303`), `sw_UploadCameraTimelapse` (`4157`), `sw_DeleteCameraTimelapse`. LAN download or WAN cloud upload (`SSWCP.cpp:5465-6110`, upstream `c8e42f1e8d`). Recording is enabled per print. | Recording and LAN download: yes. WAN: no. | Useful | Only the per-print on/off switch (`U1SendDialog.cpp:101`, `142`). No list, download or delete. | n/a |
| Preset / cloud sync | `start_sync_user_preset` runs only through the Bambu `m_agent` login (`GUI_App.cpp:1198-1205`, `5846-5851`), so it is inert for Snapmaker. `sw_UpdateDeviceInfo` (`SSWCP.cpp:7037-7255`) installs presets via the hidden `WebPresetDialog`. Profile and app update checks go to `meta-cfg.snapmaker.com` / GitHub (`AppConfig.cpp:76-83`, `GUI_App.cpp:1224-1230`) **unconditionally**. | No (no Snapmaker preset cloud) | No | Not needed | n/a |
| Full Spectrum colour mixing | Pure slicer (`libslic3r/MixedFilament*`, `GUI/MixedFilament*`, `MixedColorMatch*`). No Flutter, cloud or SSWCP references. Flutter only receives the resolved per-extruder filament types (upstream `ac3dafe08a` SSWCP change). | n/a (slicing) | Yes | Already independent. The native send uses the slice-result extruders. | Fork milestone G-code suites (`PORTED.md:59`) |
| Snapmaker Control page features | Flutter device page `build_flutter_web_url("2")`, driven by `sw_*`: temperature set (`sw_ControlBedTemp`/`ExtruderTemp`), fans, LED `3887`, purifier `3976`, print speed, defect detection `4302`, bed-mesh abort, G-code console `2625`, file browser `3070`, history, exceptions, rename | All LAN except the WAN variants | Partly | Native panel: monitor (temperatures shown only), fans, lights (`NativePrinterPanel.cpp:596-623`), speed and flow (M220/M221, `735-737`), pause/resume/stop, per-toolhead materials, camera. **Not native:** setting temperatures, file browser / print-from-printer, history, defect detection, purifier, bed-mesh abort, console, exceptions, rename. `Commands::exclude_object` exists (`PrinterSnapshot.hpp:140`) but is not wired into the panel. | Implied hardware |

The camera row needs a caveat. The fork's WebRTC/MJPEG paths were built in Milestone 1A while the U1 was paired
through the stock Flutter/mqtts path (`doc/upstream-features/MILESTONE_1A_ARCHITECTURE.md:44-68`). That suggests
the endpoints exist on the firmware the printer ran then, but no firmware version was recorded. Nozzle's own
stock adapter reports `camera = false`, "no documented LAN camera"
(`stock-u1-adapter/.../StockU1Helper.kt:28-29`). Treat the stock LAN camera as unconfirmed until it is checked on
known stock firmware.

## 4. Startup paths that run unconditionally with `legacy_flutter_ui` unset

"Unconditional" here means the code runs even though Flutter is off.

| # | What | Where | Notes |
|---|---|---|---|
| 1 | Page HTTP server on fixed port 13619, no port fallback | `GUI_App.cpp:1346-1351`; `HttpServer.hpp:21`. Also a restart path at `GUI_App.cpp:6001-6018`. | **Binds `tcp::v4()` (0.0.0.0), not loopback** (`HttpServer.cpp:610`, `634`). Sends `Access-Control-Allow-Origin: *` (`HttpServer.cpp:334`). Serves `/localfile/<absolute path>` and base64 `WCP_DOWNLOAD_PREFIX` paths with no directory restriction (`HttpServer.cpp:1119-1147`); the `..` check at `1106` does not stop absolute paths. Result: any LAN host can read any file the user can read while the app runs. Identical upstream (upstream `HttpServer.cpp:430`, `938`). |
| 2 | Hidden `WebPresetDialog` webview, set as `SSWCP_MqttAgent_Instance::m_dialog` | `GUI_App.cpp:1221` | Creates a WebKit instance at startup (`WebPresetDialog.cpp:137-147`). Its only consumer is `sw_UpdateDeviceInfo` (`SSWCP.cpp:7246`). |
| 3 | SnapLog telemetry client to `api.snapmaker.com` / `.cn` with a compiled-in HMAC secret | `GUI_App.cpp:3292-3310` | Uploads are consent-gated (`PRIVACY_POLICY_FLAGS`). The client and spool are always built. |
| 4 | App and profile update checks (`meta-cfg.snapmaker.com`, GitHub releases) | `GUI_App.cpp:1224-1230`; `AppConfig.cpp:76-83` | Not Flutter. The Flutter bundle updater itself is gated (`1228-1229`, `5408` via the menu at `MainFrame.cpp:2392`). |
| 5 | Snapmaker logout-latch reconcile (config read/write, no network) | `GUI_App.cpp:2366-2372` | The token restore right after it is gated (`2373`). |
| 6 | `PrinterWebView` (webview created; the URL is `about:blank` when off) plus `MonitorPanel` in the Device tab | `DeviceWorkspace.cpp:359-363`; `PrinterWebView.cpp:31-34` | A WebKit instance with the `sw_*` script handler bound (`PrinterWebView.cpp:42`). |
| 7 | Home webview | `WebViewDialog.cpp:42-44`, `94-96` | Uses `file://…/web/homepage` when off. Still registered with `fltviews` and the SSWCP message path. |
| 8 | WebView2 runtime check (Windows only) | `GUI_App.cpp:1337-1340` | Upstream behaviour. |
| 9 | Bambu network plugin load, SSDP discovery, HMS query | `GUI_App.cpp:3501`, `1235-1252`, `1183` | Inherited from Orca and not Stock-U1-specific. Inert unless the plugin is installed; HMS is skipped in stealth mode. |
| 10 | Debug WebSocket server `0.0.0.0:8766` feeding `SSWCP::handle_webmsg_for_debug` | `GUI_App.cpp:3284-3290`; `WebSocketDebugServer.cpp:30`; `SSWCP.cpp:8567` | Only when `websocket_debug=true`. **Bypasses the Flutter gate** and exposes login, MQTT, G-code and file commands to the LAN. |

Already gated in the fork when off:

- Flutter bundle copy and validation (`GUI_App.cpp:2960-2961`, `2410-2545`; `HttpServer.cpp:1150`).
- The copy-failure notice (`3279-3280`).
- Bundle downloads (`1228-1229`).
- Token restore (`2373`).
- The `sw_*` bridge for webviews (`SSWCP.cpp:8521-8525`).
- The Flutter U1 send path (`Plater.cpp:21643`).
- The Help menu web-resource items (`MainFrame.cpp:2392`, `2404`).
- The Snapmaker Control page button (`DeviceWorkspace.cpp:407-414`).

Upstream has none of these gates. There, `copy_web_resources()` (upstream `GUI_App.cpp:2766`), the server
(`1268`), `sync_web_async` (`1150`), `WebPresetDialog` (`1143`) and the Flutter Home and Device URLs all run on
every start.

## 5. Build-time footprint

- **paho-MQTT:**
  - Built by `src/CMakeLists.txt:12-17` (`add_subdirectory(mqtt)`, `PAHO_MQTT_*_PATH`).
  - Linked by `src/slic3r/CMakeLists.txt:743` (`target_link_libraries(libslic3r_gui paho-mqttpp3-static)`), with
    include paths at `745`, `747`.
  - Size: source `src/mqtt` is 1.8 MB. Built archives in `build-intake-tests` total about 2.1 MB
    (`libpaho-mqttpp3.a` 0.86 MB plus four paho-c archives 0.28-0.36 MB each).
- **Header leak:** `GUI_App.hpp:24` includes `SSWCP.hpp`, which includes `Utils/MQTT.hpp:7`
  (`<mqtt/async_client.h>`). So all 224 `.cpp` files that include `GUI_App.hpp` compile against paho headers.
- **Cloud/Flutter-only sources in `libslic3r_gui`** (`src/slic3r/CMakeLists.txt`):
  - `GUI/SSWCP.*` 559-560, 9007 lines, 3.1 MB object.
  - `GUI/WebSocketDebugServer.*` 561-562.
  - `GUI/DownloadManager.*` 563-564: timelapse and cloud file download, depends on SSWCP.
  - `GUI/WebPresetDialog.*` 569-570.
  - `GUI/WebSMUserLoginDialog.*` 571-572, 0.8 MB object.
  - `GUI/WebDeviceDialog.*` 573-574.
  - `GUI/WebPreprintDialog.*` 575-576.
  - `GUI/WebUrlDialog.*` 577-578.
  - `Utils/SnapLogClient.*` 634-635, 1.1 MB object.
  - `Utils/MQTT.*` 669-670, 0.4 MB.
  - `Utils/MoonRaker.*` 671-672, 1.8 MB object. This file also holds the plain `Moonraker` PrintHost that generic
    Klipper printers need.
  - `Utils/TimeSyncManager.*` 673-674, used by `Moonraker_Mqtt`.
  - `GUI/HttpServer.*` 242-243. The class must stay, because the Orca OAuth job reuses it; only the
    `m_page_http_server` instance is Flutter-specific.
- **Flutter bundle:**
  - `du -sh resources/web/flutter_web` is 44 M in both trees: `canvaskit/` 32 M, `assets/` 6.1 M,
    `main.*.js` 5.8 MB. All of `resources/web` is 62 M.
  - Installed wholesale by `CMakeLists.txt:940` (Windows) and `:947` (FHS). Symlinked in dev builds by
    `src/CMakeLists.txt:477`, `510`.
  - Guarded at activation by `scripts/flutter-resource-guard.sh`. Checked by `scripts/build-hybrid-test.sh:23`,
    `28`.
- **Related vendor options:**
  - `SLIC3R_SENTRY` (`CMakeLists.txt:130`, crash reporting) is already an option.
  - URL-protocol registration (`snapmaker-orca://`) comes from upstream `7444597428`: NSIS scripts via
    `CPACK_NSIS_INSTALL_SCRIPT` and `associate_url` in `GUI_App.cpp`.

## 6. Validation status of the fork's native replacements

All native commits are dated 2026-09-26:

- `7a12b206d6`, `fe14c48ba8`, `0990d8e3e8` (native send).
- `b87f1581ac`, `cbe48bce5d`, `49883bde37`, `f78eeb99e1`, `19ce922e81`, `a60ebd03b7` (panel).
- `58b8b115f7` (Flutter and cloud off).
- `0d311675e5`, `9e1b887b43`, `ea877de264` (filament).

Earlier Flutter-era U1 work, `450e4b6a38` through `830d4e5a29` and `b3f429d354`, hardened the Flutter/MQTT path.

- Source-regex tests `tests/flutter_cloud_off.test.mjs`, `tests/u1_native_lan_send.test.mjs` and
  `tests/native_printer_panel.test.mjs`: **PASS** (re-run now with `node`). They check that the guards exist, not
  how the code behaves at runtime, and they do not cover the debug-server bypass (section 4, #10).
- `libslic3r_tests [U1PrintTask]`: 7 cases, 48 assertions, PASS. `[PrinterSnapshot]`: 6 cases, 82 assertions, PASS
  (against captured `tests/data/moonraker/u1_snapshot.json` and `elegoo_cc_snapshot.json`). The binary in
  `build-intake-tests` was built at 18:37. That is before `0d311675e5` (18:51) added the
  `set_filament_command`/`reread_tag_command` tests, so those tests have not been run.
- Hardware: the commit messages show hands-on use against a real printer:
  - The 404 on HTTP `start_local_print` after a real upload (`0990d8e3e8`).
  - A crash on the first real light, fan or pause command (`19ce922e81`, root cause `a60ebd03b7`).
  - An Elegoo CC MJPEG redirect (`f78eeb99e1`).
- Missing evidence: there is no acceptance record in `doc/upstream-features/` for any native path, and the printer's
  firmware (stock or PAXX) was not recorded. `ROADMAP.md:127-149` still says login, discovery, firmware and
  filament stay in Flutter "unless a later owner-approved slice defines and validates a replacement". The native
  work has not been through that gate.

## 7. Recommendation: `NOZZLE_STOCK_U1` CMake option

- Add `option(NOZZLE_STOCK_U1 "Build optional Stock Snapmaker U1 support (Flutter UI, Snapmaker cloud, MQTT)" ON)`
  next to `CMakeLists.txt:130`.
- `ON` keeps upstream parity. The PAXX-only Advanced Workspace build sets it `OFF`.
- Keep telemetry separate: `NOZZLE_VENDOR_TELEMETRY` for SnapLog, and the existing `SLIC3R_SENTRY`.

### 7.1 Refactors needed first (no `#if`, safe in both configurations)

1. Move `SSWCP_Instance::apply_filament_info` (`SSWCP.cpp:1732` through the end of the function, about `1935`) and
   its multi-colour parsing into a neutral file such as `GUI/filamentsync/ApplyFilamentInfo.{hpp,cpp}`. Point
   `Plater.cpp:9250` at it. Native sync currently depends on the Flutter bridge file.
2. Split `Moonraker_Mqtt` (`MoonRaker.hpp:178-420` and its `.cpp` part) into `Utils/MoonRakerMqtt.{hpp,cpp}`.
   Plain `Moonraker` (`MoonRaker.hpp:24`) stays for generic Klipper hosts. When the option is off,
   `PrintHost.cpp:54` must default to `htMoonRaker`, not `htMoonRaker_mqtt`, and `PrintHost.cpp:57` needs a guard.
3. In `GUI_App.hpp:24`, replace `#include "slic3r/GUI/SSWCP.hpp"` with forward declarations of `SSWCP_Instance`,
   so paho headers stop leaking into 224 translation units.
4. Replace the page-server URLs used by non-Flutter code with `file://` resources:
   - `missing_connection.html` at `Plater.cpp:3660` and `GUI_App.cpp:4187`.
   - The printer covers at `WebPresetDialog.cpp:484`.
5. Bind `HttpServer` and `WebSocketDebugServer` to `127.0.0.1` (`HttpServer.cpp:610`, `634`;
   `WebSocketDebugServer.cpp:30`). Restrict `/localfile/` and `WCP_DOWNLOAD_PREFIX` to an allow-list of
   directories. Gate `handle_webmsg_for_debug` (`SSWCP.cpp:8567`) on `flutter_ui_enabled()`. These are security
   fixes that apply in both configurations.

### 7.2 CMake changes

- `src/CMakeLists.txt:12-17`: wrap `add_subdirectory(mqtt)` and the `PAHO_*` cache paths in `if (NOZZLE_STOCK_U1)`.
- `src/slic3r/CMakeLists.txt`:
  - Take these out of `SLIC3R_GUI_SOURCES` and into a `SLIC3R_STOCK_U1_SOURCES` list, appended only when the
    option is on:
    - 559-564: SSWCP, WebSocketDebugServer, DownloadManager.
    - 569-578: WebPreset, WebSMUserLogin, WebDevice, WebPreprint, WebUrl.
    - 669-670 (MQTT) and 673-674 (TimeSyncManager).
    - The new `MoonRakerMqtt.*`.
    - `PrinterWebView.*` (389-390).
  - Put SnapLog (634-635) behind `NOZZLE_VENDOR_TELEMETRY`.
  - Wrap 743-747 (the paho link and include lines) in `if (NOZZLE_STOCK_U1)`.
  - Add `target_compile_definitions(libslic3r_gui PUBLIC NOZZLE_STOCK_U1=$<BOOL:${NOZZLE_STOCK_U1}>)`.
- `CMakeLists.txt:940`, `947`: add `PATTERN "web/flutter_web" EXCLUDE` when the option is off. Apply the same
  exclusion in the AppImage, flatpak and macOS bundle packaging.
  - The dev symlinks (`src/CMakeLists.txt:477`, `510`) can stay. The runtime simply never loads the bundle.
- Drop the `snapmaker-orca://` URL-protocol NSIS scripts and `associate_url(L"snapmaker-orca")` when the option is
  off.
- `scripts/build-hybrid-test.sh:23`, `28`: run the Flutter resource tests only when the option is on.

### 7.3 Source guards (`#if NOZZLE_STOCK_U1`)

- **`GUI_App.hpp`:**
  - Includes at 16, 17 (`WebSMUserLoginDialog`, `WebDeviceDialog`) and 26 (`PrinterWebView`).
  - The subscriber maps at 900-914.
  - The `sm_*`, `login_dlg`/`sm_login_dlg`, `web_device_dialog`, `fltviews`, `m_page_http_server`,
    `build_flutter_web_url`, `copy_bundled_flutter_web` and `import_flutter_web` declarations.
  - When off, `flutter_ui_enabled()` becomes `static constexpr bool flutter_ui_enabled() { return false; }`
    (`GUI_App.hpp:487`), so any runtime-gated code left behind is dead.
- **`GUI_App.cpp`:**
  - 18 (include).
  - 1221 (`WebPresetDialog` / `SSWCP_MqttAgent_Instance`).
  - 1228-1229.
  - 1346-1353 (server start and the Flutter version log).
  - 1374-1403 (login dialog and SSWCP teardown).
  - 2366-2382.
  - 2410-2545 (copy, validate and notify for the bundle).
  - 2960-2961, 3279-3290, 4160, 4183.
  - 4490-4660 (import, `sm_get_login_info`, `sm_request_login`, `sm_ShowUserLogin`, `sm_request_user_logout`,
    `sm_on_token_captured`, revoke).
  - 4807-4822 (web commands).
  - 5605-5628.
  - 6001-6018.
  - The `page_state_notify_webview`, `user_login_notify` and `device_card_notify` family (7509-7580).
  - 3292-3310 behind `NOZZLE_VENDOR_TELEMETRY`.
- **`GUI.cpp`:** the `WebSMUserLoginDialog.hpp` include.
- **`MainFrame.cpp`/`.hpp`:** 2392-2407 (web-resource menu items), the `PrinterWebView` member and
  `load_printer_url`.
- **`Plater.cpp`:**
  - 2410 (`sm_disconnect_current_machine` in the connection button handler).
  - 3687-3708, and simplify 3711.
  - The non-native fallback in `show_sync_filament_dialog` (about 9265-9300).
  - The Flutter send branch 21663-21720 (`WebPreprintDialog`). Simplify 21643 to `if (is_snapmaker_u1)`.
- **`DeviceWorkspace.cpp`:** 359-366 (`MonitorPanel` and `PrinterWebView` control page), 395-414, 518, 598-620
  (theme toggle and control-state notify). When off, the native panel and the LAN camera are the only pages.
- **`WebViewDialog.cpp`:** 42-44 plus the SSWCP message routing. **`Preferences.cpp`:** the debug page SSWCP
  calls (about 1514-1530).
- **Other consumers:** the SSWCP and `wxGetApp().sm_*` / `fltviews` uses in `PrinterWebView.cpp`,
  `WebGuideDialog.cpp`, `SMPhysicalPrinterDialog.cpp:784` (the `Moonraker_Mqtt` cast) and
  `HttpServer.cpp:1150`.

### 7.4 Acceptance for the OFF build

- `nm`/`ldd` on the binary show no `paho`/`MQTT` symbols and no SSWCP symbols.
- The install tree has no `web/flutter_web`.
- At runtime, `ss -ltn` shows nothing on 13619 or 8766.
- No DNS lookups of `*.snapmaker.com` during start, slice and LAN send (update checks aside, if kept).
- The native send, panel and sync are exercised against one PAXX U1, with the firmware version recorded.
- `flutter_cloud_off.test.mjs` is replaced by a configuration test that fails if any `sw_*`, login or Flutter
  symbol is compiled in.

## 8. Upstream Flutter/cloud commits (last 60 touching `resources/web/flutter_web`, SSWCP, MQTT, MoonRaker, `src/mqtt`, the SM login dialog, HttpServer or WebPreprintDialog, up to `cbf7bbb0b3`)

**Required for optional Stock support.** These are bundle syncs and the bridge, transport and session fixes the
Flutter UI needs.

- Bundle syncs: `15868cc9c8`, `8aca1e04fe`, `7dc56c9bc2`, `df205a640d`, `f4d93699e8`, `c98c6d391f`, `55ce1bbb38`,
  `8597556b67`, `9fd12ffb2b`, `fd9feb7ca2`, `d6ee3d6c3a`, `5cddc0f88b`, `b26745d51b`, `ecfd89fa7b`, `e5696f222b`,
  `17a6e8f2fc`, `aaa5aa9bc6`, `90ff371ec8`, `3645b0d6c5`, `80b7ccf471`, `16f0994816`, `ffc2efdfb5`, `af989eeec6`,
  `2e90f8f3d2`.
- `cbf7bbb0b3`: encode the version parameter.
- `0ffba32c6d`: keep the login token fresh.
- `72dddfead3`: paho MQTTPacket fix.
- `9d0eed2e43`: MQTT server message backlog and TimeSyncManager.
- `b5b85a31cf`: WCP local print, heartbeat and purifier.
- `d58709de2a`: web-resource updater fix.
- `b63ab9afb9`: macOS web interruption.
- `b91405a074`: `gcode.3mf` name in the Flutter send.
- `65116f61ed`: login and server cleanup.
- `d583aed761`: `any_cast` crash in the pre-print filament mapping.

**Useful but replaceable.** The same result is available, or planned, over LAN Moonraker.

- `c8e42f1e8d`: timelapse export. The LAN half should be done natively with Moonraker file download.
- `33640761e1`: defect detection.
- `dd90e3fef1`: software-info RPC.
- `71d7a1d4de`: SSWCP/MQTT logging.
- `5c784f04c7`: debug log levels.
- `0f15333f08`: ETag/304 on the page server. Only relevant while the server exists.
- `1c2df8e498`: port-conflict fallback, which the fork deliberately disables for the page server
  (`GUI_App.cpp:1349`).
- `548b20995f`: OpenSSL 3.5.7 plus paho 1.3.14/1.5.1. **Split it:** the OpenSSL part is needed by every build;
  the paho part is Stock-only.

**Unrelated.**

- `d8f3923475`: comment out logs.
- `677ebe9d6c`, `32c5a4c7ef`: formatting.
- `b27cd73e31`: dead-code removal.
- `2d1554e660`: SnapLog telemetry to `api.snapmaker.com` with a compiled-in HMAC secret. Consent-gated; put it
  behind `NOZZLE_VENDOR_TELEMETRY` and leave it out of the PAXX build.

**Unsafe.**

- `d70c3b8855`: the Flutter debug WebSocket server. It listens on `0.0.0.0:8766` and, in the fork, bypasses the
  Flutter gate (section 4, #10).
- `7444597428`: registers the `snapmaker-orca://` / `Snapmaker_Orca://` URL protocols machine-wide (HKLM) so web
  pages can launch the app. It is bundled with unrelated U1 nozzle-sync UI.
- The page server design (`/localfile/` on 0.0.0.0 with `ACAO *`) predates this window, but `0f15333f08` and
  `1c2df8e498` build on it. Fix the bind and the path allow-list before porting either.

**Accidentally coupled to PAXX.** These carry slicer, profile or filament behaviour a PAXX build wants, mixed with
SSWCP, Flutter or MQTT changes. Port them by splitting, never whole.

- `ac3dafe08a`: Full Spectrum mix filament, plus SSWCP filament-type reporting.
- `9a8f728c14` and `db8875be08`: filament sync v2. The apply path lives in SSWCP.
- `0e5e365d6c`: filament colour adaptation. Its multi-colour parsing is in SSWCP `apply_filament_info`, which the
  fork's native sync reuses.
- `79a2e4b25c`: generic support filament type resolution in SSWCP.
- `b8d4ff25e8`: nozzle and filament relationship. Profiles, SSWCP and the bundle together.
- `10359dd002`, `b3cd0b66f9`: filament sync fixes shipped inside bundle syncs.
- `87f34b9a63`, `f34a62da06`, `3523cdf197`: release flow-backs and a merge. Hundreds of files mixing slicer fixes
  with about 800 lines of SSWCP, MQTT and login changes.
- `e6d332cdba`: the GNOME/flatpak platform upgrade carrying a paho patch.

## 9. Open questions to settle on hardware

1. Does **stock** U1 firmware serve `/webcam/webrtc` and `/webcam/stream.mjpg` without a session? The fork assumes
   yes; the Nozzle stock adapter assumes no.
2. Does stock Moonraker on `:80`/`:7125` accept the unauthenticated HTTP and `/websocket` calls the native path
   makes, or only after a pairing or `trusted_clients` entry? Flutter's own LAN upload is plain HTTP
   (`SSWCP.cpp:840-870`), which suggests yes. Record the firmware version when checking.
3. Does `SET_PRINT_FILAMENT_CONFIG` without `FORCE` behave identically on stock and PAXX for manually loaded spools?
