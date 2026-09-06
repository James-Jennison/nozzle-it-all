# Android Klipper feature parity roadmap

Created 2026-09-06. Owner: James Jennison. Implementation: Codex.

Goal: make our app a free, practical alternative to the useful Klipper capabilities
of Mobileraker, OctoApp, Printer Tools, Obico and SimplyPrint. Include equivalents of
paid features where feasible, using original implementation and appropriately
licensed dependencies. No app membership, ads, artificial printer/camera caps or
mandatory cloud account. External services still have operating costs.

This is a proposed delivery sequence, not authorization to deploy infrastructure,
install Obico, change reviewer services or send physical printer commands. The owner
retains architecture, milestone and release decisions. All unshipped work below is
PLANNED; listing a feature here does not start its implementation.

## Current baseline

Implementation commit `5353efc`; [native UI acceptance](UI_ACCEPTANCE.md) records
23 JVM tests, seven Razr device tests, artifact identity and READY review closure.
Those results cover the current app, not future roadmap work.

Delivered: direct local Moonraker connection, saved addresses with one active printer,
status/progress/temperature readings, camera-streamer WebRTC plus a dedicated camera
view, basic macro execution and file list/start, confirmed pause/resume/cancel,
original dark UI and accessible wrapping controls. Snapshot fallback is labelled.

M1 now adds friendly profiles, camera selection/MJPEG, metadata/thumbnails, history
and time/layer estimates; see [M1 acceptance](M1_ACCEPTANCE.md). File mutation and G-code
preview remain planned; macros have no parameter forms;
temperature controls are read-only. Monitoring is foreground-only. Authentication
required by a printer is currently unsupported. AI evaluation is experimental and
not integrated into the app; server/infrastructure work remains deferred.

## What the references establish

Sources checked 2026-09-06. These are publisher-described capabilities, not a hands-on
certification of every Android/Klipper combination. A reference in the backlog means
that product motivates the capability; it does not assert identical implementation
or price entitlement. Unconfirmed does not mean absent.

| Product | Verified reference scope | What we should match | Entitlement confidence |
| --- | --- | --- | --- |
| [Mobileraker Android](https://play.google.com/store/apps/details?id=com.mobileraker.android) | Klipper listing advertises file operations, macros, mesh, Spoolman, multiple cameras/printers, console, notifications and customization | Deep local Klipper tools and personalization | Listing identifies ads/in-app purchases; exact per-feature paid boundaries unverified |
| [OctoApp Android](https://play.google.com/store/apps/details?id=de.crysxd.octoapp) | Advertises preparation/printing workspaces, files, terminal, tuning, cameras, notifications, G-code viewer, multiple printers and Wear OS | Fast everyday workflow and Android convenience | In-app purchases confirmed; exact paid feature list unverified. Cross-platform listing includes OctoPrint-only plugins; do not assume Klipper equivalents |
| [Printer Tools Klipper compatibility](https://printertools.app/compatibility/klipper-moonraker) | Documents Moonraker status, multi-printer dashboard, history, mesh, Spoolman and MJPEG; custom macros not individually surfaced | Clear multi-printer overview and useful telemetry | Android purchase boundaries unverified. Its [Play listing](https://play.google.com/store/apps/details?id=com.fixolab.printertools) still includes Apple-specific marketing text, so Android widget claims need device verification |
| [Obico plan comparison](https://www.obico.io/docs/user-guides/upgrade-to-pro/) | Distinguishes premium streaming, AI allowances, remote uploads/printing, sharing and SMS | Remote monitoring and optional failure alerts | Public paid-service distinctions verified; self-hosting is a separate route. Avoid exact price claims because the page gives inconsistent extra-printer prices |
| [SimplyPrint plans](https://simplyprint.io/pricing) | Paid tiers/add-ons expand slicing, AI actions, queues, statistics, multi-stream viewing, maintenance and team workflows | History/filament first; advanced production workflow later | Public plan differences verified; these are service capabilities, not proof every workflow is native in Android |

We target useful Android + Klipper parity, not every vendor ecosystem. OctoPrint
plugins, Bambu AMS, Apple-only surfaces, enterprise support contracts and education
administration are outside the initial target. Generic multicolor Klipper support
requires identifying the actual installed integration first.

## Prioritized feature backlog

Effort is a planning estimate for implementation plus normal validation/review, not
a deadline: S = 1–3 engineering days; M = 4–8; L = 2–3 weeks; XL = 4+ weeks or a
separate discovery project. Hardware availability, defects and owner-operated print
trials can extend elapsed time. Estimates overlap; do not sum this table into a
release promise.

Cost: Local = no new hosted service required; Existing service = optional configured
printer/LAN service; Always-on = server/storage/network operation required. Every
row remains free within our app; resource exhaustion is reported as capacity, not
turned into a paid unlock.

| ID | Capability / reference | Today | Target milestone | Effort | Cost / dependency |
| --- | --- | --- | --- | --- | --- |
| P01 | Named profiles, edit/reorder/favorites; Mobileraker/OctoApp | Validated: named/editable/ordered/favorites | M1 | S | Local; migrate current preferences |
| P02 | Camera picker, remembered view, MJPEG + existing WebRTC; Mobileraker/OctoApp/Printer Tools | Validated: remembered picker, MJPEG/WebRTC | M1 | M | Local; actual configured camera endpoints |
| P03 | File folders, metadata, thumbnails, sort/search; Mobileraker/OctoApp/Printer Tools | Validated: folders/search/sort/metadata/thumbnail | M1 | M | Local; Moonraker metadata |
| P04 | Print history, outcomes, duration/material summaries; Printer Tools/SimplyPrint | Validated: paged records and summaries | M1 | M | Local; history API and available records |
| P05 | Elapsed time, ETA and layer progress; everyday monitoring | Validated: elapsed/layers and labelled estimates | M1 | M | Local; metadata/status; label estimates and missing data |
| P06 | Macro favorites/groups/validated parameter forms; Mobileraker | Basic run | M2 | M | Local; explicit parameter definitions, not guessed inputs |
| P07 | Heating presets, fans, speed/flow, movement/extrusion; Mobileraker/OctoApp | Simulated control preview verified; live capability/physical acceptance pending ([scope](P07_PREVIEW_ACCEPTANCE.md)) | M2 | L | Local; capability discovery and owner-operated physical acceptance |
| P08 | Upload/download/rename/delete, share-to-app; Mobileraker/OctoApp | Missing | M2 | M | Local; bounded transfers and explicit overwrite/delete handling |
| P09 | G-code preview with layers and print position; Mobileraker/OctoApp | Missing | M2 | L | Local; bounded parser/renderer, supported dialects |
| P10 | Dashboard layout, light/dark/accent choices and presets; Mobileraker/Printer Tools | Delivered and verified on Razr ([evidence](P10_ACCEPTANCE.md)) | M2 | M | Local; persist layout without hiding safety feedback |
| P11 | Console history/filtering, explicit command entry; Mobileraker/OctoApp | Missing | M3 | M | Local; bounded logs, command safeguards |
| P12 | Bed mesh viewer; calibration workflows later; Mobileraker/Printer Tools | Missing | M3 | M viewer; L calibration | Local; configured mesh and supported routines |
| P13 | Spoolman selection/inventory/usage; Mobileraker/Printer Tools | Missing | M3 | M | Existing service; read first, validated mutations later |
| P14 | Config editing with diff, backup and explicit restart; Mobileraker | Missing | M3 | L | Local; file access and safe recovery path |
| P15 | Lights/power devices, multiple tools, sensors; Klipper tool completeness | Limited standard heaters | M3 | L | Existing printer capabilities; do not assume OctoPrint plugins work |
| P16 | Authentication and LAN/VPN endpoint profiles; OctoApp/Printer Tools | Unauthenticated local only | M4a | L | Local/VPN; supported authentication design and owner-entered credentials |
| P17 | Background completion/error/offline/filament alerts; Mobileraker/OctoApp/Obico | Missing | M4b | L | Opt-in Android monitoring; reliable unattended coverage needs always-on event source |
| P18 | Notification actions and Android home-screen widgets; OctoApp/Android convenience | Missing | M4b | M | P17 freshness model; command actions open confirmation |
| P19 | Timelapse browsing/export, optional capture/encode; monitoring workflow | Missing | M4c | M viewer; L capture | Existing service or always-on storage/encoding |
| P20 | Self-hosted failure detection, evidence clips, sensitivity and alerts; Obico/SimplyPrint | Isolated experiment only | M5 | XL | P17 + camera sampling + licensed detector + server capacity |
| P21 | Multi-printer live overview and bounded multi-camera grid; Printer Tools/SimplyPrint | One active printer | M6a | L | P01/P02/P16; per-printer isolation and measured device/network budgets |
| P22 | Queues, scheduling and bed-cleared workflow; SimplyPrint | Missing | M6b | XL | Durable always-on state; no unattended starts by default |
| P23 | Maintenance reminders, usage/cost trends and exports; SimplyPrint | Missing | M6b | M–L | P04/P13; user-supplied rates and incomplete-data labels |
| P24 | Shared library, server slicing and profile management; SimplyPrint | Missing | M6c | XL discovery | Server compute/storage; slicer/profile/license compatibility |

## Delivery milestones and exit criteria

### M1 — A useful daily monitor (validated 2026-09-06)

P01–P05 are validated in the installed Razr debug build; see [M1 acceptance](M1_ACCEPTANCE.md). This closes frequent local workflow gaps without requiring a new
server. Order: named profiles → camera selection → metadata/thumbnails → history →
time/layer estimates. File metadata precedes estimates so we do not invent ETA.

Exit: saved profiles survive process death; switching clears old status and pending
commands; camera selection survives reconnect and never shows the previous printer;
only the visible stream runs; offline/unsupported states are honest; history agrees
with available Moonraker records; missing ETA stays unknown. Validate real WebRTC,
MJPEG fixture/available camera, long filenames and large histories on the Razr.
Multiple physical printers/cameras remain an explicit evidence gap until available.

### M2 — Prepare and operate prints from the app (in progress)

First active-print delivery: [scope and acceptance](M2_ACTIVE_PRINT_ACCEPTANCE.md).
Local macro organization, numeric forms, document workflows and approximate layer
preview are implemented; full M2 is not complete.

Deliver P06–P10. Keep monitor and advanced controls separate. Start with macro
organization and file transfers; then add controls and preview. Dashboard options
must preserve safety messages and connection freshness.

Exit: invalid macro parameters cannot inject unintended commands; cold extrusion,
unhomed movement and unsupported controls are guarded; interrupted uploads cannot
silently replace valid files; cancelled/oversized previews release resources.
Physical movement/heating/extrusion tests occur only in an owner-approved idle-printer
window. Mock success alone does not establish physical acceptance.

### M3 — Klipper tools and filament

Deliver P11–P15, read-only views before mutation flows. Spoolman is optional; absent
services show unavailable rather than prompting for a membership. Configuration
edits must preview diffs, detect concurrent changes and retain a recoverable copy.

Exit: stale config saves are rejected; restart is a distinct explicit action;
mesh coordinate/orientation and active spool match the configured services; console
logs stay bounded; every exposed control reflects actual printer capabilities.
Multicolor/MMU work is scoped against the installed system, not generic AMS claims.

### M4 — Alerts and away-from-home access

M4a: authentication and owner-configured VPN access first. M4b: notification delivery
and widgets. M4c: timelapse consumption, then optional capture. A hosted relay is an
optional later service, not a prerequisite for local use.

Exit: verify locked-screen/background behavior, notification denial, process death,
reboot, Doze, network changes and duplicate/stale events. Show delivery coverage and
last contact. Prove off-LAN access from a genuinely separate network. Never label
phone-only monitoring as guaranteed when Android has stopped it. Notification actions
must not send a stale or wrong-printer command. Timelapses enforce retention and
storage limits before enabling capture.

### M5 — Optional AI monitoring

Only begin after M4 event delivery and a separate server integration decision. The
existing rented EPYC/Proxmox server is the first candidate, subject to capacity and
isolation checks against GitHub runner VMs. This roadmap assumes neither a GPU
purchase nor an Obico deployment. Keep the detector behind a replaceable integration
boundary; choose the implementation after license, accuracy and resource evaluation.

Exit: assemble an owner-approved labelled set of successful/failed prints under
representative lighting and camera angles; report precision, recall, false alerts
per print-hour, detection delay and CPU/RAM/network use. Set acceptance thresholds
before the evaluation, not after seeing results. Alerts include evidence and honest
uncertainty. Camera loss/model failure must not be presented as a healthy print.
Automatic pause is a separate opt-in milestone requiring explicit validation; no
automatic cancellation in the initial AI feature.

### M6 — Fleet and production workflows

M6a follows M1/M4a and can precede AI if multi-printer demand becomes more important.
M6b follows reliable M4 events and history. M6c is a separate feasibility decision.
These are not prerequisites for a strong personal Android companion.

Exit: one failed printer cannot block others; concurrency limits are configurable
resource controls, not paid quotas; commands are visibly bound to a specific printer.
Queue state survives restart without duplicate dispatch. Unknown command outcomes
require reconciliation, never blind retries. Starting the next job requires a
recorded bed-cleared decision. Server slicing must produce validated output with the
correct machine/material/profile; team sharing needs roles and auditability before
external access. Automated bed-clearing hardware is outside initial scope.

## Optional future ideas outside the roadmap

Owner decision: dedicated wearable companions are removed from the delivery roadmap.
There is no wearable milestone or committed watch-app work. Reconsider only if an
actual use case warrants a separate owner decision. Android notifications and
home-screen widgets remain in M4; tablet and foldable layout quality remain part of
every Android milestone. The active backlog is P01–P24 across M1–M6.

## Cost strategy

| Route | Default choice | What remains a real cost |
| --- | --- | --- |
| Local profiles, camera, files, history, controls and personalization | Entirely free in the Android app | Development, device resources and existing printer hardware |
| Remote access | Owner-configured VPN before a custom relay | VPN hosting/connectivity if needed; setup and maintenance |
| Background events | Evaluate a small optional always-on companion | Host uptime, push delivery, bandwidth and operational support |
| AI | Reuse existing rented capacity if measured headroom permits | CPU/GPU time, frame transfer, storage and false-alert tuning; already rented is not unlimited spare capacity |
| Timelapse / slicing / fleet | Optional services with visible resource budgets | Disk, encoding/slicing compute, backups and ongoing maintenance |
| SMS, commercial relay, managed offsite storage | Optional provider adapters | Provider charges; never promise universal free service |

No recurring app subscription is planned. No firm incremental hosting figure is
claimed until workload, bandwidth and available capacity are measured. License
review applies to code, model weights and assets separately; source availability
alone does not establish reuse rights. We implement functionality, not vendor
entitlement bypasses or access to their paid servers.

## Definition of parity and release tracking

A feature is Done only when it is implemented, accurately reflects supported printer
capabilities, passes relevant tests/review, and has device/runtime acceptance. Track
individual rows as Planned → In progress → Validated → Shipped, with implementation
commit, APK identity, test evidence, supported combinations and remaining limitations.
Unsupported integrations and unknown vendor entitlements stay explicit.

Milestone acceptance follows the existing owner review/evidence policy. This document
is a planning artifact, not a release acceptance or a new infrastructure architecture.
No parity percentage is claimed: a raw checkbox count would hide major differences
between local viewing, dependable alerts and hosted AI services.

M1 is validated in the owner’s debug installation. **M2 is in progress**, beginning
with the owner-authorized active-print/read-only delivery described above.
