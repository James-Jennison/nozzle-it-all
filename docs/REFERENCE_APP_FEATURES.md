# Reference app feature matrix

Compiled 2026-09-16 from each app's own store listing or official site (sources
linked per app). This is a factual feature inventory for planning purposes, not a
license to reuse any app's code — see [FEATURE_PARITY_ROADMAP.md](FEATURE_PARITY_ROADMAP.md)
for license status per app. A feature listed here motivates a capability; it does
not assert we will build it the same way or on the same schedule.

## Per-app comprehensive feature list

### Mobileraker ([Play Store](https://play.google.com/store/apps/details?id=com.mobileraker.android))
GCode preview and live print tracking; pause/resume/stop with real-time progress;
full axis control; multi-extruder temperature management with presets; bed mesh
visualization; real-time temperature charts; Spoolman integration; filament sensor
alerts; fully customizable dashboard layout; file management (upload/download/zip/
edit); grouped G-code macros; multi-printer fleet control; multi-camera monitoring;
interactive G-code console; customizable push notifications; remote access via
OctoEverywhere/Obico/manual VPN; power-plugin (smart outlet) control. Contains ads
and in-app purchases (a "lifetime membership" tier).

### OctoApp ([Play Store](https://play.google.com/store/apps/details?id=de.crysxd.octoapp))
Three-workspace flow (Connect → Prepare → Print); print preparation (heat hotend,
swap filament, level bed); flow/feed-rate/fan controls; full file access; full
terminal access; multi-camera webcam support; dark mode; print/filament
notifications; G-code viewer; multi-printer support; power-control plugin support
(PSU Control, IKEA Tradfri); automatic lights; PrintTimeGenius (better ETA);
third-party plugin support (ArcWelder, SpoolManager); OctoEverywhere/Obico remote
integration; VPN/HTTPS/Basic Auth support; **genuine Wear OS app**. Supports
OctoPrint, Klipper (Moonraker/Fluidd/Mainsail), Bambu Lab and Elegoo Centauri. No
ads; in-app purchases unlock extra features.

### Printer Tools ([Play Store](https://play.google.com/store/apps/details?id=com.fixolab.printertools) — iOS/watchOS only despite the Android listing)
Real-time per-printer metrics (layer, progress, time, temps, fans, lighting);
multi-printer dashboard with filament usage/chamber conditions/AMS details
(active colors, humidity, temp); multi-camera integration including ONVIF network
cameras; file browsing/upload/organizing with job queueing; performance logs,
print history and success-rate analytics; accent color + light/dark personalization;
optional widgets (temp/camera/progress); Raspberry Pi host temperature display;
direct nozzle/bed temperature **set** for Klipper and Snapmaker. Contains ads and
in-app purchases.

### Obico ([Klipper page](https://obico.io/klipper.html), server/plugin [AGPL-3.0](https://github.com/TheSpaghettiDetective/obico-server))
Remote access to Mainsail/Fluidd from anywhere; live streaming; AI failure
detection that can **automatically pause the print early**; print history and
statistics; notifications via push, email, SMS, Telegram, Pushover and more;
multi-printer/fleet management; choice of self-hosted or managed cloud hosting.

### SimplyPrint ([site](https://simplyprint.io/))
Print queue with smart job-to-printer routing (material/nozzle/plate matching);
1-click bulk start across many printers; AI failure detection across 130+ printer
brands; usage-based maintenance scheduling; print statistics (success rate, hours,
filament, cost); cloud file storage with print-count history; filament management
with automatic per-spool usage deduction; REST API/webhooks/Zapier/n8n automation;
full printer control panel (temps, movement, webcam, G-code terminal); **AutoPrint**
(hands-off continuous printing — next queued job auto-starts once the bed is
cleared); team sharing with granular permissions; multi-material workflow unifying
AMS/ACE/CFS/MMU/Palette; enterprise security tier (SSO via SAML/OIDC, enforced 2FA,
audit log, DPA/SLA, EU data residency).

### OctoEverywhere ([site](https://octoeverywhere.com/); also has a [remote MCP endpoint](https://octoeverywhere.com/mcp) for AI-assistant printer access)
Free unlimited remote access (no self-hosted tunnel); Gadget AI failure detection
that can notify or **auto-pause**; real-time notifications across many channels
(email, SMS, push, Telegram, Discord); Live Links for sharing a real-time stream
with stats; powers remote access inside other community apps (OctoApp, Mobileraker,
OctoPod, Printoid).

### PrintNanny ([summary via aggregated sources](https://www.fabbaloo.com/news/all-about-printnanny))
On-device (offline-capable) AI print monitoring/failure detection; tiered plans —
Free (1 printer, basic detection, email alerts), Pro $7/mo (3 printers, spaghetti
detection, historical analytics, multi-camera, Slack/Discord alerts), Farm $14/mo
(unlimited printers, extended history, live metric dashboards, exportable reports,
API access); OctoPrint/Klipper/Syncthing integration on Raspberry Pi; OTA updates;
settings and G-code macro editor with no SSH required.

### Fluidd ([features](https://docs.fluidd.xyz/features/), [GPL-3.0](https://github.com/fluidd-core/fluidd))
Object exclusion; thermal history charts; multi-camera streaming (MJPEG/HLS/WebRTC);
print job queue with drag-and-drop reordering; multi-printer switching from one
instance; print history with statistics; JWT authentication with forced-login and
LDAP support; responsive desktop/tablet/mobile web UI.

### Mainsail ([docs](https://docs.mainsail.xyz/), [GPL-3.0](https://github.com/mainsail-crew/mainsail))
Timelapse integration; power-device control (relays, TP-Link smart plugs); macro
management; fully configurable dashboard; theming; additional sensor/temperature
graphs; object exclusion; multi-printer management from one interface.

### Bambu Handy ([Play Store](https://play.google.com/store/apps/details?id=bbl.intl.bambulab.com))
Remote start/stop; real-time status with in-app troubleshooting guidance for
errors; high-resolution live view; automatic failure-diagnosis recordings;
automatic timelapse generation and sharing; **MakerWorld** model discovery (browse,
like, collect, one-tap print, MakerLab customization); community interaction and
credit redemption. Known weak point (per its own users): multi-printer support.
Non-Klipper (proprietary Bambu firmware/cloud) — M7 territory, not a direct source.

### Prusa Connect / Prusa app ([Play Store](https://play.google.com/store/apps/details?id=com.prusa3d.connect))
Real-time multi-printer overview; remote start/pause/stop; temperature/speed/flow/
position control; push notifications (completion, scheduled color changes, more);
Printables.com browse-and-print integration; **EasyPrint** phone-initiated slicing
(rate-limited — jobs over ~60s slicing time need a computer, another service, or a
paid tier); add printers to Prusa Connect from the app; Buddy3D camera control;
cloud and on-premises storage; NFC printer setup; team management; **belt/resonance
tuner**; print queue management. Non-Klipper — M7 territory, not a direct source.

## Grouped by matching scope, with the standout pick per group

| Category | Who has it | Standout pick |
| --- | --- | --- |
| Print control & monitoring | All 11 | **OctoApp**'s three-workspace flow (Connect → Prepare → Print) is the cleanest structure for turning raw controls into a guided sequence |
| Camera / live view | Mobileraker, OctoApp, Printer Tools, Obico, SimplyPrint, OctoEverywhere, Fluidd, Mainsail, Bambu Handy, Prusa | **OctoApp**'s percentage-and-live-badge overlaid directly on the camera image (already adopted); **Bambu Handy**'s automatic failure-diagnosis recording + auto-timelapse pairs monitoring with a record you can review after the fact |
| Multi-printer / fleet overview | Mobileraker, Printer Tools, Obico, SimplyPrint, OctoApp, Fluidd, Mainsail | **Printer Tools**' compact list-row overview (already adopted this session); **SimplyPrint** is the only one that credibly scales to a 100-printer farm, worth remembering if that's ever relevant |
| File management | Mobileraker, OctoApp, Printer Tools, SimplyPrint | **Mobileraker**'s zip/edit/full file command set is the most complete for a solo user; **SimplyPrint**'s cloud storage with print-count history is the more production-oriented model |
| Macros / G-code console | Mobileraker, OctoApp, Fluidd, PrintNanny | **Mobileraker**'s grouped, pill-button macro grid remains the cleanest UI for this |
| AI failure detection | Obico, SimplyPrint, OctoEverywhere, PrintNanny | **PrintNanny**'s on-device/offline model is the strongest privacy/architecture fit for a no-cloud-account app; **Obico/OctoEverywhere**'s auto-pause-on-detection is the sharpest safety behavior worth copying regardless of which detection backend is chosen |
| Notifications / alerts | Mobileraker, OctoApp, Obico, SimplyPrint, OctoEverywhere, Prusa | **Obico**'s channel breadth (push, email, SMS, Telegram, Pushover) and **Prusa**'s granular event types (e.g. a scheduled color-change point, not just done/failed) are the two things worth combining |
| Remote access | Mobileraker, OctoApp, Obico, OctoEverywhere | **OctoEverywhere**'s sub-20-second zero-config setup is the bar other methods (Tailscale, Cloudflare Tunnel) are competing against for ease, at the cost of a third-party relay dependency |
| Multi-material / AMS | Printer Tools, SimplyPrint, Bambu Handy | **SimplyPrint**'s unified workflow across AMS/ACE/CFS/MMU/Palette is the most system-agnostic approach; matters if multi-material hardware is ever added |
| Queue / automation | SimplyPrint, Fluidd, Prusa | **SimplyPrint**'s AutoPrint (hands-off, next job starts once the bed's confirmed cleared) is the most ambitious — and exactly why our own M6b exit criteria require an explicit recorded bed-cleared decision, not a blind auto-start |
| Maintenance / statistics | SimplyPrint, Printer Tools, Mobileraker | **SimplyPrint**'s usage-based maintenance scheduling tied to real print statistics, not just a calendar reminder |
| Customization / theming | Mobileraker, Printer Tools, Mainsail, Prusa | **Mainsail**'s fully configurable dashboard is the most flexible; **Printer Tools**' accent-color + widget model is the more mobile-native take |
| Team / permissions | SimplyPrint, Prusa | **SimplyPrint**'s granular per-user permissions beat Prusa's simpler team management, but both are far beyond a single-owner app's current needs |
| Slicing | Prusa (EasyPrint), SimplyPrint (queue-aware) | Neither is a clean model to copy: Prusa's is rate-limited/monetized past ~60s, SimplyPrint's is farm-oriented. Confirms the earlier read that on-device/phone-initiated slicing is a bigger lift than it first looks |
| Model discovery / community | Bambu Handy (MakerWorld), Prusa (Printables) | **Bambu Handy**'s one-tap browse-like-collect-print flow is the smoothest, though it depends on a first-party model repository we don't have |
| Config / calibration tools | Prusa (belt tuner), Fluidd/Mainsail (mesh + input shaper) | Fluidd/Mainsail's approach is the relevant one — it's the same Moonraker API we already speak, not a vendor-specific tool |
| Platform reach | OctoApp (Wear OS) | The one confirmed real Wear OS reference, already noted when Wear OS was reopened |
| Auth / security | SimplyPrint (SSO/2FA/audit log), Fluidd (JWT/LDAP) | SimplyPrint's tier is enterprise-scale overkill for this app; Fluidd's JWT-based approach is the realistic ceiling for P16 |

## What this doesn't change

This is a reference inventory, not a backlog edit. Nothing here adds a new P-number
or milestone by itself — see [FEATURE_PARITY_ROADMAP.md](FEATURE_PARITY_ROADMAP.md)
for what's actually scheduled, and its "Feature ideas by reference app" section for
previously-surfaced untracked ideas (community model import, mid-print object
exclusion, solo phone-initiated slicing, input-shaper calibration).
