# Free feature expansion

Detailed delivery sequence: [Android feature parity roadmap](FEATURE_PARITY_ROADMAP.md).

Owner direction recorded 2026-09-06: include useful equivalents of the paid capabilities in SimplyPrint, Mobileraker, OctoApp, Obico and Printer Tools in our Android application for free where feasible.

This is a requirements and feasibility inventory, not a claim that these features have been implemented or that every vendor entitlement has been verified. The current application remains the validated native UI baseline described in UI_ACCEPTANCE.md. Exact Android purchase screens and printer-specific capabilities may differ from public feature lists.

## Product requirements

- No membership, paid feature unlock, advertisements, or artificial printer/camera quotas in our application.
- Core printer control and monitoring work locally without an account or mandatory cloud service.
- Implement original equivalents of the useful functionality. Evaluate licenses before adopting third-party source, model weights or assets. Vendor service subscriptions are not unlocked by our app.
- Optional remote and server integrations must explain their actual dependencies and any external costs. Self-hosted means owner-operated resources, not zero operating cost.
- Android phone/tablet and foldable support. Dedicated wearable companions are outside the delivery roadmap by owner decision; optional future consideration only. No iOS parity work.
- Show capability availability honestly: missing printer components, unsupported camera protocols and unavailable services are not paywalls.

## Feature feasibility and delivery order

| Capability | Route to a free app feature | Current status / acceptance target |
| --- | --- | --- |
| Live status and print controls | Direct Moonraker connection | MVP exists; preserve stale-state protection, explicit confirmation and no automatic mutation replay. |
| Continuous live camera | Direct local camera stream | Camera-streamer WebRTC exists. Extend to camera selection and supported additional protocols; prove rendered frames, reconnect and lifecycle teardown. Never label refreshed snapshots as continuous video. |
| Multiple printers | Locally stored printer profiles | Basic saved-address profiles implemented; one active printer at a time. See SAVED_PRINTERS_ACCEPTANCE.md for switching/removal validation and physical-device limits. Friendly names and simultaneous monitoring remain planned. No subscription-based count cap. |
| Multiple cameras and fullscreen | Per-printer camera catalog and viewers | Dedicated full-screen view shipped; camera selection and simultaneous viewing planned. No subscription-based camera count cap; pause offscreen streams and measure phone load. |
| Rich macros | Favorites, groups and explicit parameter forms | Basic macro execution exists. Parameters require validated command construction; retain explicit confirmation and printer-defined behavior. |
| Files and G-code preview | Moonraker file APIs and local visualization | Basic file list/start exists. Add metadata, folders, upload/download, previews and explicit destructive-file actions. Bound memory for large files. |
| Temperature, fan and movement tools | Exposed Klipper objects and commands | Temperature readings exist. Add supported controls/presets, tuning and explicit movement safeguards. Physical acceptance must not interrupt an active print. |
| Console and configuration editor | Moonraker APIs | Planned. Separate read-only logs from command entry; preview configuration diffs and require explicit save/restart actions. Preserve unrelated configuration. |
| Bed mesh and calibration UI | Klipper/Moonraker data and supported routines | Planned. Visualization first; calibration is a separate physical operation with its own acceptance. |
| Filament inventory | Optional Spoolman integration | Planned. Detect configured service; distinguish reading/selecting a spool from changing inventory. |
| Print history and statistics | Moonraker history, optionally local records | Planned. Label time ranges, incomplete records and retention accurately. |
| Dashboard, themes and presets | Local preferences | Planned. Accessible layouts for the Razr and other Android screen sizes, no premium layout tier. |
| Android home-screen widgets | Native Android surfaces | Planned alongside notifications. Report freshness honestly and measure update/battery behavior. Dedicated watch apps are outside the roadmap. |
| Background alerts | Android monitoring plus optional always-on event companion | Planned. Phone-only and server-delivered alerts have different availability; validate background restrictions and disconnections before reliability claims. |
| Away-from-home access | Reach an owner-configured endpoint through VPN or optional relay | Planned. No vendor subscription required for a self-managed route. Does not authorize opening network ports or deploying a service. |
| AI failure detection | Evaluate a self-hosted detector/integration | Isolated CPU detector evaluated on the existing server; not integrated into the Android app. Further AI work and formal server acceptance are deferred under the app-first scope. Accuracy/false-positive evaluation is still required. Alerts first; automatic pause would require separate validation. |
| Timelapses | Printer/server capture and local viewing/export | Planned. Storage and encoding still consume resources. |
| Queues, scheduling and farm dashboard | Durable server state plus Android client | Later phase. A queued job is not permission to start on an occupied bed; require explicit bed-cleared workflow. |
| Slicing and shared file library | Optional desktop/server worker and storage | Later feasibility work. Choose and validate slicer/profile/license compatibility before promising output parity. Avoid claiming a phone-only cloud-slicer replacement. |
| SMS, hosted relay, offsite backup | Optional external providers or owner-operated service | Cannot promise zero operating cost. Not required for core application functionality. |

The first expansion should cover local profiles, camera selection/fullscreen, richer macros, file metadata/history and dashboard usability. Subsequent local tooling can add Spoolman, mesh, console/editor and previews. Always-on services follow after a host and operating scope are selected; no server has been selected or deployment authorized by this document.

## Completion evidence

Each implementation milestone needs targeted tests, relevant build/lint checks, independent review under the owner governance, and acceptance on the authorized Razr. Device checks must distinguish real read-only printer observations from mocked command tests. Existing MVP evidence does not validate future source changes.

## Sources checked

- [Mobileraker Android capabilities](https://play.google.com/store/apps/details?id=com.mobileraker.android)
- [OctoApp Android capabilities](https://play.google.com/store/apps/details?id=de.crysxd.octoapp)
- [Printer Tools Klipper compatibility and limitations](https://printertools.app/compatibility/klipper-moonraker)
- [SimplyPrint plans and server-backed features](https://simplyprint.io/pricing)
- [Obico self-hosting documentation](https://www.obico.io/docs/server-guides/)
- [Obico server hardware requirements](https://www.obico.io/docs/server-guides/hardware-requirements/)

## App-first direction — 2026-09-06

Owner approved prioritizing Android essentials, then local profiles/history/notifications/dashboard improvements. The evidence-worker and shared-broker infrastructure work is deferred. Saved-address profiles are the first delivered expansion; the full roadmap is not complete.
