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

Sources checked 2026-09-06, extended 2026-09-16. These are publisher-described
capabilities, not a hands-on certification of every Android/Klipper combination. A
reference in the backlog means that product motivates the capability; it does not
assert identical implementation or price entitlement. Unconfirmed does not mean absent.

| Product | Verified reference scope | What we should match | Entitlement confidence |
| --- | --- | --- | --- |
| [Mobileraker Android](https://play.google.com/store/apps/details?id=com.mobileraker.android) | Klipper listing advertises file operations, macros, mesh, Spoolman, multiple cameras/printers, console, notifications and customization | Deep local Klipper tools and personalization | Listing identifies ads/in-app purchases; exact per-feature paid boundaries unverified |
| [OctoApp Android](https://play.google.com/store/apps/details?id=de.crysxd.octoapp) | Advertises preparation/printing workspaces, files, terminal, tuning, cameras, notifications, G-code viewer, multiple printers and Wear OS | Fast everyday workflow and Android convenience | In-app purchases confirmed; exact paid feature list unverified. Cross-platform listing includes OctoPrint-only plugins; do not assume Klipper equivalents |
| [Printer Tools Klipper compatibility](https://printertools.app/compatibility/klipper-moonraker) | Documents Moonraker status, multi-printer dashboard, history, mesh, Spoolman and MJPEG; custom macros not individually surfaced | Clear multi-printer overview and useful telemetry | Android purchase boundaries unverified. Its [Play listing](https://play.google.com/store/apps/details?id=com.fixolab.printertools) still includes Apple-specific marketing text, so Android widget claims need device verification |
| [Obico plan comparison](https://www.obico.io/docs/user-guides/upgrade-to-pro/) | Distinguishes premium streaming, AI allowances, remote uploads/printing, sharing and SMS | Remote monitoring and optional failure alerts | Public paid-service distinctions verified; self-hosting is a separate route. Avoid exact price claims because the page gives inconsistent extra-printer prices. Server/Moonraker-plugin components ([obico-server](https://github.com/TheSpaghettiDetective/obico-server), [moonraker-obico](https://github.com/TheSpaghettiDetective/moonraker-obico)) confirmed **AGPL-3.0**; the mobile app itself has no public repo under their GitHub org, so its UI stays inspiration-only, not a verified-open reuse source |
| [SimplyPrint plans](https://simplyprint.io/pricing) | Paid tiers/add-ons expand slicing, AI actions, queues, statistics, multi-stream viewing, maintenance and team workflows | History/filament first; advanced production workflow later | Public plan differences verified; these are service capabilities, not proof every workflow is native in Android |
| [OctoEverywhere](https://octoeverywhere.com/klipper) | Free-tier remote access to Klipper/Mainsail/Fluidd/Moonraker via a cloud relay (no self-hosted tunnel), plus Gadget AI failure detection, notifications and live streaming; [Companion plugin](https://octoeverywhere.com/companion) runs on the host Pi | A concrete third M4a remote-access option beside Tailscale/Cloudflare Tunnel, and a second AI-detection reference beside Obico | Free tier confirmed with a $2.49/month optional supporter tier; it is a third-party cloud relay, not self-hosted, so it carries an ongoing trust/dependency cost distinct from VPN-based access |
| [PrintNanny](https://printnanny.ai) | On-device (offline-capable) computer-vision failure detection, historical analytics and multi-printer queue rerouting; [Klipper/Mainsail/Moonraker addon](https://printnanny.ai/docs/addons/mainsail-moonraker-klipper/) | An offline/on-device inference architecture option for P20, distinct from Obico's cloud/self-hosted model | Pro $7/mo (3 printers), Farm $14/mo (unlimited); pricing confirmed via publisher site, feature-to-tier mapping not independently verified |
| [Fluidd](https://docs.fluidd.xyz/features/) | The native open-source Moonraker web UI: object exclusion, thermal history charts, multi-camera streaming, print job queue, multi-printer switching, JWT/LDAP auth | Validates P12 (mesh viewer), P15 (sensors), P22 (queue) and P16 (auth) as real, already-implemented Moonraker-API capabilities, not just competitor marketing claims | Confirmed **GPL-3.0** ([fluidd-core/fluidd](https://github.com/fluidd-core/fluidd), license file verified). It is a desktop/tablet web UI (Vue.js), not a native Android app, so UX still needs its own mobile design; cross-language reuse would mean porting concepts, not copying files |
| [Mainsail](https://docs.mainsail.xyz/) | The other native open-source Moonraker web UI: timelapse, power-device control (relays/TP-Link), macro management, configurable dashboard, object exclusion, multi-printer | Same validation role as Fluidd, additionally confirming P15 (power devices) and P19 (timelapse) against the actual Moonraker API | Confirmed **GPL-3.0** ([mainsail-crew/mainsail](https://github.com/mainsail-crew/mainsail), license file verified). Same web-UI/cross-language caveat as Fluidd |

We target useful Android + Klipper parity, not every vendor ecosystem. OctoPrint
plugins, Bambu AMS, Apple-only surfaces, enterprise support contracts and education
administration are outside the initial target. Generic multicolor Klipper support
requires identifying the actual installed integration first.

**Bambu Handy** and **Prusa Connect** are both strong mobile apps, but both are
proprietary-firmware/cloud stacks (Bambu's own protocol, Prusa's PrusaLink/Connect),
not Klipper/Moonraker. Owner decision (2026-09-16): rather than excluding them, they
are now the reference apps for M7 (P25 Bambu, P26 Prusa) — see M7's acceptance note
for why that milestone uses a different, documentation/API-tier evidence standard
than the hardware-backed rows above, since the owner does not own this hardware.
Community reports describe Bambu Handy's own multi-printer support as weak, so it is
a feature reference for P25, not a UX model to copy for multi-printer overview (P21).
Prusa Connect's team/role-based access and phone-initiated slicing ("EasyPrint") stay
cross-referenced to P24's existing "team sharing needs roles" exit criterion rather
than duplicated as their own rows.

## Feature ideas by reference app (2026-09-16 pass)

Per-app feature lists behind the backlog above, so the "why" for each row traces back
to what actually motivated it. IDs point at the existing row; "New idea" flags
something not yet tracked anywhere in the backlog.

- **Mobileraker** — file operations (P03), macros (P06), bed mesh viewer (P12),
  Spoolman (P13), multi-camera/multi-printer (P02, P21), console (P11),
  notifications (P17), personalization (P10)
- **OctoApp** — prep/print workspaces (P07), file browser (P11), terminal (P11),
  tuning controls (P07), camera (P02), notifications (P17), G-code viewer (P09),
  multi-printer (P21), Wear OS — the one confirmed real reference for the reopened
  Wear OS idea below, not just a listing claim
- **Printer Tools** — Moonraker status/multi-printer dashboard (P21), history (P04),
  mesh viewer (P12), Spoolman (P13), MJPEG (P02)
- **Obico** — premium live streaming (P02), cloud/self-hosted AI failure
  detection (P20), remote print uploads (P08), sharing, SMS alerts (only a generic
  "optional provider adapter" in the cost strategy table today, not its own row)
- **SimplyPrint** — slicing (new idea, see below), AI actions (P20), job queues (P22),
  statistics (P04/P23), multi-stream viewing (P02), maintenance reminders (P23), team
  workflows (P24)
- **OctoEverywhere** — zero-config cloud relay remote access (P16/M4a), Gadget AI
  failure detection (P20), push notifications (P17), live streaming (P02)
- **PrintNanny** — on-device offline AI failure detection (P20), historical
  analytics (P23), multi-printer failed-job rerouting (P22)
- **Fluidd** — object exclusion (new idea, see below), thermal history charts (new
  idea, see below), multi-stream camera (P02), print job queue (P22), multi-printer
  switching (P21), JWT/LDAP auth (P16 — LDAP specifically is enterprise-flavored and
  probably not worth it for a personal app)
- **Mainsail** — timelapse (P19), power-device control/relays (P15), macro
  management (P06), configurable dashboard (P10), object exclusion (new idea, see
  below), multi-printer (P21)
- **Bambu Handy** — community model browsing/saving via MakerWorld (new idea, see
  below), AMS slot auto-assignment for multi-color (not applicable — no AMS-equivalent
  on our target printers), remote live view control (P02/P07)
- **Prusa Connect** — team roles/access rights (P24), phone-initiated slicing (new
  idea, see below), granular push notifications for finish/color-change
  points (P17/P18), NFC printer pairing (new idea, see below), belt/resonance
  tuner (new idea, see below), Printables integration (new idea, see below)

**New ideas surfaced by this pass, not yet in the backlog as their own row:**
1. Community model browsing/import (e.g. Printables) straight into a print
2. Object exclusion mid-print — cancel one failed object without killing the job
3. Solo phone-initiated slicing — lighter than P24's server/team slicing, a
   single-user "slice on a paired machine, send from phone" flow
4. Input-shaper/resonance calibration — the genuinely Klipper-native analog to
   Prusa's belt tuner (ADXL345-based), arguably the most on-brand of the four

These four are listed for visibility only; none has a backlog ID, target milestone,
or effort estimate yet, pending an owner decision on whether/where to add them.

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
| P08 | Upload/download/rename/delete, share-to-app; Mobileraker/OctoApp | Download/import/export, unique-name live upload/rename and confirmed live deletion verified ([scope](P08_DELETE_ACCEPTANCE.md)) | M2 | M | Local; bounded transfers and explicit overwrite/delete handling |
| P09 | G-code preview with layers and print position; Mobileraker/OctoApp | Local navigation and read-only buffered file-progress tracking verified ([scope](P09_LIVE_TRACKING_ACCEPTANCE.md)) | M2 | L | Local; bounded parser/renderer, supported dialects |
| P10 | Dashboard layout, light/dark/accent choices and presets; Mobileraker/Printer Tools | Delivered and verified on Razr ([evidence](P10_ACCEPTANCE.md)) | M2 | M | Local; persist layout without hiding safety feedback |
| P11 | Console history/filtering, explicit command entry; Mobileraker/OctoApp | Read-only cache, search/error filter, pause and copy validated; command entry pending ([scope](P11_CONSOLE_ACCEPTANCE.md)) | M3 | M | Local; bounded logs, command safeguards |
| P12 | Bed mesh viewer; calibration workflows later; Mobileraker/Printer Tools | Missing | M3 | M viewer; L calibration | Local; configured mesh and supported routines |
| P13 | Spoolman selection/inventory/usage; Mobileraker/Printer Tools | Missing | M3 | M | Existing service; read first, validated mutations later |
| P14 | Config editing with diff, backup and explicit restart; Mobileraker | Missing | M3 | L | Local; file access and safe recovery path |
| P15 | Lights/power devices, multiple tools, sensors; Klipper tool completeness. Includes multi-toolhead temperature visibility (all T0–T3 toolheads at once, not just the active one) — a real gap confirmed against the Snapmaker U1's own firmware, not just a generic idea | Limited standard heaters; single active-extruder display only | M3 | L | Existing printer capabilities; do not assume OctoPrint plugins work. Read-only for the multi-toolhead display slice — no new control surface |
| P16 | Authentication and LAN/VPN endpoint profiles; OctoApp/Printer Tools | Unauthenticated local only | M4a | L | Local/VPN; supported authentication design and owner-entered credentials |
| P17 | Background completion/error/offline/filament alerts; Mobileraker/OctoApp/Obico | Missing | M4b | L | Opt-in Android monitoring; reliable unattended coverage needs always-on event source |
| P18 | Notification actions and Android home-screen widgets; OctoApp/Android convenience | Missing | M4b | M | P17 freshness model; command actions open confirmation |
| P19 | Timelapse browsing/export, optional capture/encode; monitoring workflow | Missing | M4c | M viewer; L capture | Existing service or always-on storage/encoding |
| P20 | Self-hosted failure detection, evidence clips, sensitivity and alerts; Obico/SimplyPrint | Isolated experiment only | M5 | XL | P17 + camera sampling + licensed detector + server capacity |
| P21 | Multi-printer live overview and bounded multi-camera grid; Printer Tools/SimplyPrint | Validated: auto-connect/independent monitoring per saved printer, tile grid with per-tile live camera, installed and phone-verified on the Razr ([auto-connect](AUTO_CONNECT_ACCEPTANCE.md), [tiles](PRINTER_TILES_ACCEPTANCE.md)) | M6a | L | P01/P02/P16; per-printer isolation and measured device/network budgets |
| P22 | Queues, scheduling and bed-cleared workflow; SimplyPrint | Missing | M6b | XL | Durable always-on state; no unattended starts by default |
| P23 | Maintenance reminders, usage/cost trends and exports; SimplyPrint | Missing | M6b | M–L | P04/P13; user-supplied rates and incomplete-data labels |
| P24 | Shared library, server slicing and profile management; SimplyPrint | Missing | M6c | XL discovery | Server compute/storage; slicer/profile/license compatibility |
| P25 | Bambu Lab printer support (local/cloud MQTT protocol); Bambu Handy | Missing | M7 | XL discovery | Existing service (Bambu Cloud) or LAN mode; owner does not own this hardware, see M7 acceptance note |
| P26 | Prusa (PrusaLink/Prusa Connect) printer support; Prusa Connect app | Missing | M7 | L discovery | Existing service (Prusa Connect) or local PrusaLink API; owner does not own this hardware, see M7 acceptance note |

**P15 sourcing note (2026-09-16):** the Snapmaker U1 runs a genuine Klipper/Moonraker
fork ([Snapmaker/u1-moonraker](https://github.com/Snapmaker/u1-moonraker), GPL-3.0;
~20% of Klipper and ~15% of Moonraker modified specifically for its parallel
multi-toolhead system) — this is why our app can monitor it at all. A community
Home Assistant integration, [ha-snapmaker-u1](https://github.com/kbaker827/ha-snapmaker-u1)
(no explicit license — read for protocol facts only, not a code-reuse source),
confirms the firmware exposes T0–T3 per-extruder temperature/target sensors
independently. Our own `PrinterSnapshot` only tracks one active nozzle today.

**Elegoo CC firmware note (2026-09-16):** the owner's Elegoo CC runs
[COSMOS](https://github.com/OpenCentauri/cosmos) (GPL-3.0), a community Klipper/Kalico
replacement firmware for the Elegoo Centauri Carbon — not stock Elegoo firmware. This
matters for P12 and P15 specifically: COSMOS adds webUI bed-mesh viewing and storing
multiple saved meshes (directly relevant to P12's bed mesh viewer), reports exhaust
fan RPM and exposes direct exhaust-fan-speed control, and supports toolhead/main-light
dimming (both P15). It also supports an aftermarket AMS via a documented ancubic ACE
integration — relevant only if that hardware is ever added, not assumed present.
COSMOS's own README warns it is beta/not stable and the mainboard is resource-limited
with little overhead for extra plugins — treat any COSMOS-specific capability as
needing a live capability check against this printer, not assumed from Klipper alone.

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

### M2 — Prepare and operate prints from the app (paused on monitoring-first scope)

First active-print delivery: [scope and acceptance](M2_ACTIVE_PRINT_ACCEPTANCE.md).
Local macro organization, numeric forms, document workflows and approximate layer
preview are implemented; full M2 is not complete.

Deliver P06–P10. Keep monitor and advanced controls separate. Start with macro
organization and file transfers; then add controls and preview. Dashboard options
must preserve safety messages and connection freshness.

**Owner scope decision (2026-09-16):** narrow active development to monitoring —
status/progress/temperature/history readouts, live camera, and confirmed
pause/resume/cancel — for the duration of the owner's Toys for Tots season.
Already-implemented heater/fan controls and file transfer/live-preview work
(P07 partial, P08, P09) stay installed as-is; no further control expansion (the
planned P07 speed/flow factor slice, remaining P06 macro parameter forms, or any
new P07 capability) starts until the owner revisits scope after the season ends
(approximately December 2026). This is a pause, not a removal: P06/P07/P10 rows
keep their current status until then.

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
optional later service, not a prerequisite for local use. Remote access support includes Tailscale as the primary method with Cloudflare Tunnel and traditional port forwarding as alternatives.

Exit: verify locked-screen/background behavior, notification denial, process death,
reboot, Doze, network changes and duplicate/stale events. Show delivery coverage and
last contact. Prove off-LAN access from a genuinely separate network. Never label
phone-only monitoring as guaranteed when Android has stopped it. Notification actions
must not send a stale or wrong-printer command. Timelapses enforce retention and
storage limits before enabling capture.

### M4a - Enhanced Remote Access Support (Revised)

This milestone builds upon the existing authentication capabilities by introducing robust 
remote access options that allow users to monitor and control their printers from anywhere.
Support is provided through:

1. **Primary Method: Tailscale** - Automatic NAT traversal with zero configuration, 
   secure WireGuard encryption, and cross-platform compatibility
   
2. **Secondary Methods:** Cloudflare Tunnel (free Quick Tunnel option) and traditional 
   port forwarding with DDNS for users who prefer other solutions

3. **Third-party relay option:** [OctoEverywhere](https://octoeverywhere.com/klipper)'s
   free tier is a concrete, Klipper-native alternative to self-hosted VPN/tunnel setup
   for owners who would rather not run Tailscale/Cloudflare themselves. It trades
   self-hosting effort for dependency on a third-party cloud relay, so it stays a
   documented alternative, not the primary recommendation.

Exit criteria: Users can successfully establish remote access to their printer using 
any of the supported methods with proper security and connection reliability.

### M5 — Optional AI monitoring

Only begin after M4 event delivery and a separate server integration decision. The
existing rented EPYC/Proxmox server is the first candidate, subject to capacity and
isolation checks against GitHub runner VMs. This roadmap assumes neither a GPU
purchase nor an Obico deployment. Keep the detector behind a replaceable integration
boundary; choose the implementation after license, accuracy and resource evaluation.
[PrintNanny](https://printnanny.ai)'s on-device, offline-capable inference is a second
architecture reference beside Obico's cloud/self-hosted model — evaluate it alongside
the rented-server approach rather than assuming a server integration is the only shape
this can take.

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

### M7 — Multi-ecosystem support (non-Klipper printers)

Owner decision (2026-09-16): the app can expand beyond Klipper/Moonraker to other
printer ecosystems — starting with Bambu Lab (P25) and Prusa (P26) — using published
cloud APIs, local protocol documentation and community reference implementations
rather than owner-operated hardware, since the owner does not own non-Klipper
printers. This follows every other milestone; it is not a prerequisite for a strong
Klipper companion, and it does not change the app's Klipper/Moonraker core.

**Acceptance is a different, explicitly lower-confidence tier than P01–P24.** The
existing acceptance model (owner's own Razr/printer runs, device screenshots, hash-
verified installs) requires hardware this milestone cannot assume. Until real
hardware becomes available, validation instead relies on: published API/protocol
documentation, contract tests against publicly documented request/response shapes,
community reference clients (e.g. Bambu's local MQTT protocol as reverse-engineered
by community projects, Prusa's published PrusaLink/Connect API), and volunteer
owner-supplied evidence if a beta tester with real hardware participates. Every
status for P25/P26 must say explicitly which tier of evidence backs it — "API-
contract verified" is not "device verified" — and no row here may be marked with the
same "Validated"/"Shipped" language used for hardware-backed Klipper rows without
that distinction spelled out.

Exit: each ecosystem's integration is isolated behind its own transport
implementation (parallel to `Moonraker.kt`, not a modification of it), so a Bambu/
Prusa protocol change or account/API revocation cannot break Klipper connectivity.
Unverified-by-hardware status is visible in the app itself for that ecosystem (for
example, a visible "not verified on real hardware yet" state), not just in docs.
Real hardware acceptance replaces the documentation-tier evidence before any such
integration is called complete, whenever that hardware becomes available.

## Optional future ideas outside the roadmap

Owner decision (2026-09-06): dedicated wearable companions removed from the delivery
roadmap. Owner decision (2026-09-16): reopened as a live option to revisit, scoped to
**Wear OS only** — Apple Watch/watchOS is not relevant since this is an Android-only
app. Reference check across all eleven apps above, Wear OS specifically: OctoApp is
the only one with real, confirmed Wear OS support (notifications, widgets, a
published "Getting started on Wear OS" walkthrough). Bambu Handy explicitly does not
have it — there's an open, unresolved community feature request for one. One source
claimed Obico has a Wear OS companion, but neither Obico's own site nor its Play
listing corroborates that, so treat it as unverified, not a real reference.
Mobileraker, Printer Tools (Apple-only watch support), SimplyPrint, OctoEverywhere
(reaches Wear OS only indirectly through OctoApp/Printoid, ships nothing of its own),
PrintNanny and Prusa Connect have no Wear OS support found. Still not yet scoped, no
target milestone, no committed watch-app work, and no confirmed test hardware on our
side — do not start design or implementation until the owner defines scope
(glance-only vs. actions-from-the-watch) and confirms test hardware, the way M7 had
to before Bambu/Prusa work could be scoped. Android notifications and home-screen
widgets remain in M4; tablet and foldable layout quality remain part of every Android
milestone. The active backlog is P01–P26 across M1–M7.

## Cost strategy

| Route | Default choice | What remains a real cost |
| --- | --- | --- |
| Local profiles, camera, files, history, controls and personalization | Entirely free in the Android app | Development, device resources and existing printer hardware |
| Remote access | Owner-configured VPN before a custom relay | VPN hosting/connectivity if needed; setup and maintenance |
| Background events | Evaluate a small optional always-on companion | Host uptime, push delivery, bandwidth and operational support |
| AI | Reuse existing rented capacity if measured headroom permits | CPU/GPU time, frame transfer, storage and false-alert tuning; already rented is not unlimited spare capacity |
| Timelapse / slicing / fleet | Optional services with visible resource budgets | Disk, encoding/slicing compute, backups and ongoing maintenance |
| SMS, commercial relay, managed offsite storage | Optional provider adapters | Provider charges; never promise universal free service |

## Remote Access Strategy

The Mobile Klipper Companion supports multiple secure remote access methods with Tailscale as the primary recommendation:

**Primary Method: Tailscale**
- Zero-configuration NAT traversal
- Secure WireGuard encryption 
- Cross-platform compatibility
- No router setup required

**Secondary Methods:** 
- Cloudflare Tunnel (free Quick Tunnel option)
- Traditional port forwarding with DDNS

No recurring app subscription is planned. No firm incremental hosting figure is
claimed until workload, bandwidth and available capacity are measured. License
review applies to code, model weights and assets separately; source availability
alone does not establish reuse rights. We implement functionality, not vendor
entitlement bypasses or access to their paid servers.

## Definition of parity and release tracking

A feature is Done only when it is implemented, accurately reflects supported printer
capabilities, passes relevant tests/review, and has device/runtime acceptance — except
M7's P25/P26, which follow the separate documentation/API-tier evidence standard
defined in M7 until real hardware acceptance is possible; they may never be marked
Done in the ordinary sense while that gap remains, and must say so. Track
individual rows as Planned → In progress → Validated → Shipped, with implementation
commit, APK identity, test evidence, supported combinations and remaining limitations.
Unsupported integrations and unknown vendor entitlements stay explicit.

Milestone acceptance follows the existing owner review/evidence policy. This document
is a planning artifact, not a release acceptance or a new infrastructure architecture.
No parity percentage is claimed: a raw checkbox count would hide major differences
between local viewing, dependable alerts and hosted AI services.

M1 is validated in the owner’s debug installation. **M2 is in progress**, beginning
with the owner-authorized active-print/read-only delivery described above.
