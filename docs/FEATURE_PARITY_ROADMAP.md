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

**License note (2026-09-20):** the app incorporates AGPL-3.0-or-later code ported
from [Helix](https://github.com/FatBoy721/Helix) (see M8 below), which makes the
whole app AGPL-3.0-or-later from that point forward — `LICENSE` at the repo root
and `THIRD_PARTY_NOTICES.md` cover this; every ported file also carries its own
attribution header. This is an owner-accepted obligation, not an accidental
license change; it doesn't affect the "no ads/membership/mandatory cloud account"
goal above, which is about product behavior, not source licensing.

## Working order (set 2026-09-16)

This is the precise, ordered sequence we are actually working from right now — not
every idea in this document, just what's next and in what order. Re-check this
section first before starting new work; everything else below is supporting detail
and reference material.

**Phase 0 — in progress now (monitoring-safe, no pause conflict):**
1. ~~P12 — Bed mesh viewer (read-only)~~ **Done 2026-09-16**, verified live on both printers
2. ~~P15 — Multi-toolhead temperature visibility for the Snapmaker U1~~ **Done
   2026-09-16**, verified live: all 4 real toolheads shown independently
3. ~~P15 — Elegoo CC/COSMOS-specific read-only checks~~ **Done 2026-09-16**,
   fan RPM verified live on COSMOS; mesh profile visibility already covered by P12
4. ~~P13 — Spoolman read-only inventory view~~ **Skipped 2026-09-16** — owner
   confirmed they don't run Spoolman; not worth building against nothing
5. ~~P14 — Config editing, read-only diff view~~ **Done 2026-09-16**, verified
   live on the Elegoo CC (real COSMOS config split correctly). Phase 0 complete.

**Phase 1 — owner decision 2026-09-16: unpaused for implementation, with a
condition.** Building and validating this work by code/unit tests and device-fixture
tests (fake readers/callbacks, no real printer) is authorized now. **Physical
acceptance — installing on the Razr and sending real commands to the Snapmaker U1 or
Elegoo CC — is not authorized yet** and stays a separate, later owner decision. The
`LIVE_HEATER_FAN_CONTROLS_ENABLED` UI gate stays off (heater/fan panels stay hidden
from the running app) until that physical acceptance happens, same as the existing
gated-but-built heater/fan panels from before the pause — this phase extends that
same treatment to new control work, it doesn't lift it:
6. ~~P07 remainder — speed/flow factor controls (M220/M221)~~ **Built 2026-09-17**,
   gated off behind `LIVE_HEATER_FAN_CONTROLS_ENABLED`; physical acceptance still
   deferred per the Phase 1 condition above
7. ~~P06 remainder — remaining macro parameter form work~~ **Built 2026-09-17**:
   added the live re-check/expiring-review dispatch layer the parameter forms were
   missing, and gated macro Run behind `LIVE_HEATER_FAN_CONTROLS_ENABLED` (it was
   previously ungated, inconsistent with heater/fan/speed-flow); physical
   acceptance still deferred per the Phase 1 condition above
8. ~~P15 remainder — actual fan-speed/light-dimming control (not just visibility)~~
   **Built 2026-09-17**: fan-speed control was already covered by the existing
   FanControls/FanPanel (the Elegoo CC/COSMOS exposes its fans as `fan` and
   `fan_generic X`, both already handled); added light dimming, which had no
   code at all - see the P15 sourcing note below for the confirmed `[led case]`/
   `[led hotend]` config and `SET_LED` command. Deliberately not restricted to
   idle print states, unlike heater/fan/macro/speed-flow - dimming a light
   doesn't disrupt an active print, and Fluidd/Mainsail allow it while printing.
   Physical acceptance still deferred per the Phase 1 condition above
9. ~~P11 remainder — console command entry (currently read-only only)~~ **Built
   2026-09-17**: added as a separate capability alongside the read-only reader
   (which stays read-only by design), with local-only validation (256 char
   bound, control-character/injection rejection) since there's no live server
   value to re-verify for arbitrary text. Not restricted to idle print states,
   matching the existing read-only console's always-available behavior.
   Physical acceptance still deferred per the Phase 1 condition above
10. ~~P14 remainder — enable save/backup/explicit-restart once the diff view above
    is validated~~ **Built 2026-09-17**: owner decision to build+gate this like the
    other Phase 1 controls, not the stricter localhost-only/fixture-only precedent
    set by FileMutationFixture for gcode file uploads. Every save is preceded by a
    mandatory server-side backup copy (`server/files/copy`); editing is confined to
    the section above the SAVE_CONFIG marker, which is re-fetched fresh immediately
    before saving to catch drift; restart uses Moonraker's "Host Restart"
    (`printer/restart`), not `firmware_restart`, and is a fully separate,
    separately-confirmed action from save. Spec-verified against Moonraker's file-
    management and printer-administration docs before writing anything. This
    closes out Phase 1 in full - physical acceptance still deferred per the Phase 1
    condition above

**Phase 1 complete (2026-09-17).** All ten items above are built, gated behind
`LIVE_HEATER_FAN_CONTROLS_ENABLED`, and validated by JVM tests plus device tests on
both an emulator and the owner's Razr 2023 (fixture readers/writers only - no real
printer was ever sent a live command or file write during this work). Physical
acceptance for all of it remains a separate, later owner decision.

**Phase 2 — M4, sequenced after M3 is substantially done:**
11. M4a — authentication + remote access (Tailscale primary; Cloudflare Tunnel,
    port forwarding, OctoEverywhere documented as alternatives). **API-key
    authentication built 2026-09-17**: `Moonraker` sends `X-Api-Key` on every
    request via an OkHttp interceptor (not just the JSON-RPC path — config/thumbnail/
    camera requests that bypass `request()` inherit it too, since it's applied at the
    client level); the key is entered per-profile in `ProfileEditor` and stored in a
    dedicated `CredentialStore` (`EncryptedSharedPreferences`, Keystore-backed),
    kept out of the plaintext `profilesV1` profile JSON entirely. Distinct 401/403
    messages tell the owner whether no key is configured vs. the configured key was
    rejected. `parseAddress` now also accepts Tailscale's CGNAT range
    (100.64.0.0/10) and `*.ts.net` MagicDNS names over plain HTTP, so a tailnet
    address doesn't need to be forced through HTTPS. Verified: 4 new/updated
    MoonrakerTest cases (header sent, blank key sends no header, distinct missing-
    vs-rejected-key messages, Tailscale address acceptance/CGNAT boundary), an
    androidTest round-trip through `CredentialStore`-equivalent storage, and a live
    on-device pass on the Razr 2023 (installed via `assembleDebug` + `adb install -r`,
    no data wipe) adding a profile, entering an API key, confirming it persisted
    across reopening the editor via Show/Hide, then removing the test profile.
    Cloudflare Tunnel/port forwarding/OctoEverywhere remain documentation-only, as
    already noted below; they need no client code since they present as an ordinary
    HTTPS (or Tailscale) address. **In-app remote-access help built 2026-09-18**:
    a `RemoteAccessHelpPanel` reachable from a "How do I connect away from home?"
    button in `ProfileEditor`, covering Tailscale/Cloudflare Tunnel/port forwarding/
    OctoEverywhere and reiterating that an API key is what actually secures a
    printer reachable outside the LAN. Verified with a new androidTest and a live
    on-device pass on the Razr 2023. Still open for M4a: OctoEverywhere-specific
    wiring if that path is chosen instead of self-hosted VPN/tunnel.
    **Self-review fix pass, 2026-09-18** (device-independent: no printer or panel
    UI touched, JVM-tested only): an 8-angle review of the whole M4a slice found
    and fixed 7 issues, the two most notable being real correctness gaps —
    editing a printer's API key didn't force a reconnect for either the
    currently-connected printer or a background-monitored saved one, so a
    corrected key silently kept failing until the app restarted; and
    `parseAddress`'s Tailscale allowance missed Tailscale's own IPv6 range and
    bare MagicDNS short hostnames, rejecting both over plain HTTP despite that
    being exactly what the feature was for. Also closed a latent landmine
    (`updateProfile`'s apiKey parameter defaulted to `""`, so an omitted 4th
    argument would have silently erased a saved key) and a real data-loss edge
    case in `PrinterPreferences` (a non-`ClassCastException` secrets-read
    failure dropped an entire profile, not just its key, and the next save
    permanently deleted the orphaned secret). 4 new regression tests plus 1
    extended address test; all 212 JVM tests pass.
    **Auth propagated to file transfer, 2026-09-18**: while checking the
    self-review fixes, found that `FileTransfer` (P08 download/import),
    `LiveFileChanges` (P08 live upload/rename/delete) and `LivePrintPreview`
    (P09 live tracking) each build their own `OkHttpClient` independently of
    `Moonraker.kt` and none of them ever sent `X-Api-Key` — meaning the
    already-shipped, "Validated" P08/P09 features would have silently stopped
    working the moment an owner set an API key on a profile. All three now
    take an `apiKey` and add the same interceptor pattern as `Moonraker`;
    `ScreenState.apiKeyFor()`/`moonrakerFor()` (added during the review fixes)
    cover the call sites. 3 new header-sent tests; all 215 JVM tests pass.
12. M4b — background alerts, notification actions, home-screen widgets.
    **Alert-detection logic built 2026-09-18**: a pure `PrintAlerts.detect()`
    diffs one printer's previous vs. current `PrinterConnection` observation and
    decides whether it's alert-worthy (print completed/errored/cancelled,
    printer went offline/came back), with 11 JVM tests covering the edge cases
    (no alert on first observation or on a repeated identical state; a
    reconnect into an already-"complete" state raises only "back online," not
    "completed," since we genuinely don't know when it finished while offline).
    This is diff/decision logic only — nothing calls it yet, and it does not
    touch notifications, background execution, or permissions. Filament-runout
    alerts aren't covered: there's no filament-sensor read yet to diff against.
    **Explicitly not started, and not a small next step**: actually delivering
    an alert needs an architecture decision this doc doesn't make for the owner
    — a foreground service (reliable, visible, draws battery/a persistent
    notification) vs. `WorkManager` periodic work (invisible, battery-friendlier,
    but capped at ~15 min minimum interval, too slow for "print just finished").
    Whichever is chosen also needs `POST_NOTIFICATIONS` runtime permission
    (Android 13+), a foreground-service type declaration if that path is picked,
    and the locked-screen/background/Doze/reboot/duplicate-event verification
    from this document's M4 exit criteria — all of which need a real device,
    not JVM tests.
    **Delivery built 2026-09-19, owner decision: alerts must be near-instant**,
    which rules out `WorkManager` (~15 min floor). `PrintMonitorService` is an
    opt-in foreground service (off by default; toggled from a new "Background
    print alerts" control in the Printers tab, gated behind the
    `POST_NOTIFICATIONS` runtime permission request on Android 13+) that polls
    every saved printer every 20s and turns `PrintAlerts.detect()` output into
    notifications on a dedicated `print_alerts` channel, plus a persistent
    `IMPORTANCE_MIN` "Monitoring your printers" status notification (required
    by Android for any foreground service) on its own `print_monitor_status`
    channel so it never makes noise. Declared `foregroundServiceType="specialUse"`
    rather than `dataSync`, specifically because `dataSync`/`mediaProcessing`
    foreground services are capped at 6 hours of execution in a rolling 24h
    window on this project's targetSdk (36/Android 15+) — a real problem since
    prints commonly run longer than that; `connectedDevice` has no such cap but
    wants Bluetooth/NFC/USB-flavored permissions that don't reflect what this
    app does, and `specialUse`'s Play-Console justification-review requirement
    doesn't apply since this is a sideloaded, non-Play-Store app.
    **Live-verified on the Razr 2026** (owner note: use the USB-connected 2026,
    not the Tailscale-connected 2023, to avoid device-control contention):
    confirmed via `dumpsys activity services`/`dumpsys notification` across a
    clean install — permission-granted path starts the service with the
    correct foreground notification and channel; permission-denied path does
    nothing (no crash, no false "enabled" state persisted); toggling off stops
    the service and removes the notification. Two real bugs found and fixed
    during that pass: the service originally called `stopSelf()` on an empty
    saved-printer list, meaning "enable alerts, then add your first printer"
    would silently never start monitoring until the toggle was manually cycled
    — it now idles and picks up newly-added printers on its next poll instead;
    and `onDestroy()` relied on the framework's implicit notification cleanup,
    which left a stale notification behind after a `force-stop` on this device
    — now calls `stopForeground(STOP_FOREGROUND_REMOVE)` explicitly. Still
    open: notification actions (P18) and home-screen widgets aren't built;
    filament-runout alerts still aren't covered (no sensor read to diff
    against); locked-screen/Doze/reboot behavior over many hours needs a real
    print to fully exercise, not just a short manual toggle test.
13. M4c — timelapse browsing, then optional capture. **Read-only browsing built
    2026-09-18**: spec-verified against the actual `moonraker-timelapse`
    component source (the Mainsail/Fluidd-compatible one referenced elsewhere in
    this doc) before writing anything — it registers finished videos under a
    Moonraker file root literally named `"timelapse"`, so listing them reuses
    the exact same `server/files/list` endpoint already used for gcodes, not a
    new/guessed API shape. `TimelapseReader.timelapses()` filters to known video
    extensions (mp4/mov/webm/mkv), dedupes, and sorts newest-first; a
    `TimelapsePanel` (matching the read-only style of `BedMeshPanel`) shows
    filename/size/date and reads "no timelapse videos found... requires the
    moonraker-timelapse component" when the root doesn't exist rather than a raw
    error. Covered by JVM tests (parsing/sorting/dedup, request shape, missing-
    component handling) and a new `TimelapsePanelDeviceTest`; the panel itself
    is compiled and logic-tested but **not yet visually verified on a real
    device** — done without device access, unlike everything else in this
    document's evidence trail. Capture/export (the "then optional capture" half)
    is still fully unbuilt: no render trigger, no video download/playback, no
    storage/retention budget decided yet.

**Phase 3 — further out, no fixed start date:**
14. M5 — optional AI monitoring (architecture decision needed first: rented-server
    vs. PrintNanny-style on-device inference)
15. M6b/M6c — queues/bed-cleared workflow, maintenance/cost trends, then
    shared-library/server-slicing feasibility discovery

**Phase 4 — M7, a parallel track with its own different evidence tier:**
16. P25 — Bambu Lab support
17. P26 — Prusa support

**Phase 5 — M8, a second parallel track: porting Helix's own engineering in
alongside continuing Klipper-core work, rather than maintaining two apps. Owner
decision 2026-09-20: stay Kotlin/Compose (no React Native migration), port
Helix's native Kotlin modules with attribution under the AGPL obligation this
creates (see the license note above), gate vendor-specific ports behind a new
`PrinterKind` field so generic-Klipper owners see no new UI. Full detail: M8
below.**
18. M8a — License/attribution foundation (`LICENSE`, `THIRD_PARTY_NOTICES.md`)
    and `PrinterKind` (`GENERIC_KLIPPER`/`SNAPMAKER_U1_PAXX`/`BAMBU_LAB`) on
    `PrinterProfile`. **Done 2026-09-20**, commit `064cfa7`.
19. M8b (**P27**, new row) — Bespok3d plugin bridge + Snapmaker U1/PAXX remote
    screen. **Done 2026-09-20**, commits `e1045a3`/`a7271d3`.
20. M8c — Bambu Lab live support, upgrading **P25** from the M7 "Missing,
    owner does not own this hardware" state to real ported code (still
    hardware-unverified — M7's own documentation-tier standard, see below).
    **Done 2026-09-20**, commit `bba9b8e`.
21. M8d — Timelapse gallery upgrade, extending **P19**'s already-shipped
    read-only browsing (M4c above) into a gallery with poster thumbnails,
    in-app playback and download. **Done 2026-09-20**, commit `f2271c1`.
22. M8e — Bed-mesh 3D view, adding a gesture-orbited surface on top of **P12**'s
    already-shipped 2D heatmap. **Done 2026-09-20**, commit `d68decc`, and the
    only M8 item with real device physical acceptance so far — see M8 below.
23. M8f (**P28**, new row) — Visual redesign ("Kiln"): new theme/typography,
    5-tab nav (Home/Control/Files/Prepare/Settings), Quickview list and
    per-printer detail hero restyle. **Substantially done 2026-09-20**,
    commits `24ba1a7`…`ed0ed17`. Supersedes the "Queued... visual-polish pass"
    item this section used to list here (that request predates and is now
    folded into this broader redesign, not a separate later pass).

**Parked, not sequenced until scoped:**
- Wear OS — reopened, no scope or target milestone yet
- The four untracked ideas from the reference research: community model import,
  mid-print object exclusion, solo phone-initiated slicing, input-shaper calibration
- M8's own remaining scope: on-device slicing (highest risk — needs an owner
  sign-off before vendoring a ~23MB prebuilt slicer binary of unverified
  provenance) and a MakerWorld browser; see M8 below.

M6a (multi-printer overview) and M1 are already done — see their rows below for
evidence. This working order supersedes any looser "Now/Later" framing discussed
earlier; treat this list as the actual queue.

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
| P12 | Bed mesh viewer; calibration workflows later; Mobileraker/Printer Tools | Validated: read-only profile/Z-range/heatmap, verified live on both real printers (Elegoo CC no-mesh state, Snapmaker U1 real 11×11 profile). **2026-09-20 (M8e):** added a gesture-orbited 3D surface (Catmull-Rom-smoothed, drag-to-orbit/pinch-to-zoom) alongside the existing heatmap, clean-room (not ported from Helix, whose own bed-mesh view is JS/Skia); verified live against the Snapmaker U1's real 13×13 mesh after running `BED_MESH_CALIBRATE` through the app's own macro runner | M3 (+M8e) | M viewer; L calibration | Local; configured mesh and supported routines |
| P13 | Spoolman selection/inventory/usage; Mobileraker/Printer Tools | Skipped — owner does not run Spoolman (confirmed 2026-09-16); revisit only if that changes | M3 | M | Existing service; read first, validated mutations later |
| P14 | Config editing with diff, backup and explicit restart; Mobileraker | Missing | M3 | L | Local; file access and safe recovery path |
| P15 | Lights/power devices, multiple tools, sensors; Klipper tool completeness. Includes multi-toolhead temperature visibility (all T0–T3 toolheads at once, not just the active one) — a real gap confirmed against the Snapmaker U1's own firmware, not just a generic idea | Validated: multi-toolhead visibility verified live on the U1 (all 4 extruders). Still missing: lights/power devices, COSMOS-specific checks (exhaust fan RPM, saved mesh profiles), and any actual fan/light control | M3 | L | Existing printer capabilities; do not assume OctoPrint plugins work. Read-only for the multi-toolhead display slice — no new control surface |
| P16 | Authentication and LAN/VPN endpoint profiles; OctoApp/Printer Tools | API-key auth built and device-verified (X-Api-Key, encrypted per-profile storage); Tailscale/*.ts.net addresses accepted; Cloudflare Tunnel/port forwarding documentation-only | M4a | L | Local/VPN; supported authentication design and owner-entered credentials |
| P17 | Background completion/error/offline/filament alerts; Mobileraker/OctoApp/Obico | Missing | M4b | L | Opt-in Android monitoring; reliable unattended coverage needs always-on event source |
| P18 | Notification actions and Android home-screen widgets; OctoApp/Android convenience | Missing | M4b | M | P17 freshness model; command actions open confirmation |
| P19 | Timelapse browsing/export, optional capture/encode; monitoring workflow | Read-only browsing built 2026-09-18 (see M4c above). **2026-09-20 (M8d):** upgraded to a day-grouped gallery — moonraker-timelapse poster-frame thumbnails, in-app playback via `VideoView` streamed directly from Moonraker (Range-request seeking, no full download to watch), and a "Save" action to a user-chosen document via SAF. Unlike M8e, **not yet visually verified on a real device** — JVM/androidTest only, same evidence gap the original M4c browsing shipped with. Capture/render triggering is still fully unbuilt | M4c (+M8d) | M viewer; L capture | Existing service or always-on storage/encoding |
| P20 | Self-hosted failure detection, evidence clips, sensitivity and alerts; Obico/SimplyPrint | Isolated experiment only | M5 | XL | P17 + camera sampling + licensed detector + server capacity |
| P21 | Multi-printer live overview and bounded multi-camera grid; Printer Tools/SimplyPrint | Validated: auto-connect/independent monitoring per saved printer, tile grid with per-tile live camera, installed and phone-verified on the Razr ([auto-connect](AUTO_CONNECT_ACCEPTANCE.md), [tiles](PRINTER_TILES_ACCEPTANCE.md)) | M6a | L | P01/P02/P16; per-printer isolation and measured device/network budgets |
| P22 | Queues, scheduling and bed-cleared workflow; SimplyPrint | Missing | M6b | XL | Durable always-on state; no unattended starts by default |
| P23 | Maintenance reminders, usage/cost trends and exports; SimplyPrint | Missing | M6b | M–L | P04/P13; user-supplied rates and incomplete-data labels |
| P24 | Shared library, server slicing and profile management; SimplyPrint | Missing | M6c | XL discovery | Server compute/storage; slicer/profile/license compatibility |
| P25 | Bambu Lab printer support (local/cloud MQTT protocol); Bambu Handy | **2026-09-20 (M8c):** real LAN-mode support built — `BambuPrinterService` (MQTT status/control, FTPS upload, chamber camera re-served as loopback MJPEG), ported from Helix (AGPL) with attribution. Print-start is scoped to an already-sliced `.gcode.3mf` shared into the app (no on-device slicing exists to build one). Still owner-does-not-own-this-hardware per M7's evidence tier below: unit-tested (`BambuPrintProtocolTest` etc.) but **not device-verified against real Bambu hardware** — do not upgrade this row's language past that until real hardware acceptance happens | M7 (+M8c) | XL discovery | Existing service (Bambu Cloud) or LAN mode; owner does not own this hardware, see M7 acceptance note |
| P26 | Prusa (PrusaLink/Prusa Connect) printer support; Prusa Connect app | Missing | M7 | L discovery | Existing service (Prusa Connect) or local PrusaLink API; owner does not own this hardware, see M7 acceptance note |
| P27 | Bespok3d plugin bridge + remote touchscreen mirror for the Snapmaker U1/PAXX; Helix | **Done 2026-09-20:** `Bespok3dClient`/SSH preflight-enrollment, ported from Helix (AGPL) with attribution, gated to `PrinterKind.SNAPMAKER_U1_PAXX`. The signed daemon/jinni bundle that enrollment needs is independently re-verified and vendored (not Helix's own copy — downloaded fresh from Bespok3d's GitHub releases and OpenPGP-checked against their own publisher key before being committed, see `scripts/build_bespok3d_bootstrap.py`). Remote screen re-serves `helixd`'s JPEG-polling touchscreen mirror, not an MJPEG stream as first assumed — corrected after reading Helix's actual behavior rather than guessing. Unit-tested; **enrollment/remote-screen not yet device-verified against a real U1** (needs the owner's own SSH access code) | M8b | L | Local; Snapmaker U1/PAXX running Bespok3d, owner-entered SSH credentials never persisted |
| P28 | Visual redesign ("Kiln" theme): new palette/typography, 5-tab nav, Quickview list + per-printer detail hero | **Substantially done 2026-09-20:** duotone dark theme (ember heat / teal accent) and Space Grotesk/IBM Plex Sans/Mono (OFL, bundled as variable fonts) replace the old flat scheme; bottom nav grows from 4 to 5 tabs (Home/Control/Files/Prepare/Settings — deliberately reordered/relabeled from an early draft that matched Helix's own nav almost exactly); Home's printer list and the per-printer dashboard hero/temperature cards get the gradient/status-dot treatment from the design concept. Every change physically verified live on the owner's Razr 2023, including two real bugs a live check caught that code review hadn't (a compressed/clipped hero card, an invisible layer-line texture). Remaining: Bespok3d/Bambu panels, the bed-mesh 3D view and the timelapse gallery still use pre-redesign `AlertDialog` chrome (they inherit the new colors/fonts via the theme change, but not the card/gradient treatment) | M8f | L | Local; OFL font licenses bundled, see `THIRD_PARTY_NOTICES.md` |

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

**Light dimming sourcing note (2026-09-17):** confirmed directly against COSMOS's
own source, not just its README. `machine.cfg`
(meta-opencentauri/recipes-apps/klipper/files/machine.cfg in
[OpenCentauri/cosmos](https://github.com/OpenCentauri/cosmos)) defines `[led case]`
(the main light, on by default) and `[led hotend]` (an aftermarket toolhead LED),
each with only a `white_pin` configured. Both are standard Klipper `[led]` objects,
not a COSMOS-specific mechanism — Klipper's own `led.py` confirms `SET_LED
LED=<name> WHITE=<0.0-1.0> SYNC=0` is the correct command for a white-only LED.
Our app discovers `led <name>` objects generically from the printer's own catalog
(same pattern as fan discovery), so this isn't hardcoded to these two names and
should work on any printer exposing `[led ...]` objects, not just the Elegoo CC.

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

### M2 — Prepare and operate prints from the app (implementation resumed, physical acceptance still deferred)

First active-print delivery: [scope and acceptance](M2_ACTIVE_PRINT_ACCEPTANCE.md).
Local macro organization, numeric forms, document workflows and approximate layer
preview are implemented; full M2 is not complete.

Deliver P06–P10. Keep monitor and advanced controls separate. Start with macro
organization and file transfers; then add controls and preview. Dashboard options
must preserve safety messages and connection freshness.

**Owner scope decision (2026-09-06):** narrowed active development to monitoring
for the duration of the owner's Toys for Tots season. **Revised 2026-09-16:**
control-work implementation is unpaused — the remaining P07 speed/flow slice,
P06 macro forms, P11 console entry and P15 control additions can be built and
validated now via unit/device-fixture tests. Physical acceptance (installing on
the Razr and sending real commands to the Snapmaker U1 or Elegoo CC) remains a
separate, later owner decision — not authorized by this revision. The
`LIVE_HEATER_FAN_CONTROLS_ENABLED` gate stays off in the running app until that
happens, exactly as it already did for the pre-pause heater/fan panels.

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

**M7 exit-criteria gap opened by M8c (2026-09-20):** the "visible in the app
itself" requirement above is not yet met for Bambu Lab — `BambuPrinterService`
is real, wired code (not documentation-tier contract tests; it's built against
Helix's own hardware-verified engineering), but the app shows no in-app
"not verified on real hardware yet" indicator for a `BAMBU_LAB` profile the way
this exit criterion calls for. Flagged here rather than silently left; close it
before calling P25 anything past its current "built, hardware-unverified" status.

### M8 — Helix-derived feature port and visual redesign

Owner decision (2026-09-20): compared this app against
[Helix](https://github.com/FatBoy721/Helix) (React Native/Expo, AGPL-3.0-or-later,
Snapmaker U1/PAXX-specific) and chose to port its feature set in with attribution
rather than maintain two apps — see the license note near the top of this document
for the AGPL consequence that decision accepts. Stays Kotlin/Compose throughout (no
React Native migration); Helix's native, RN-free Kotlin modules are ported with
attribution headers rather than clean-room reimplemented where that's honest;
vendor-specific ports gate behind the new `PrinterKind` field so a generic-Klipper
owner sees no new UI. Bambu Lab support (M8c) was originally a separate, later,
owned-hardware-gated milestone (M7/P25) but was pulled forward into M8 since
Helix's own Bambu code turned out to be portable pure-JVM, not because the
owned-hardware evidence gate changed — M7's own tier distinction above still
applies to it.

**M8a — license/attribution foundation + `PrinterKind`.** Done 2026-09-20, commit
`064cfa7`. Blocking prerequisite for everything else in M8; 227 unit tests green
at landing.

**M8b — Bespok3d plugin bridge + Snapmaker U1/PAXX remote screen (P27).** Done
2026-09-20, commits `e1045a3`/`a7271d3`. Ported `Bespok3dClient`/SSH preflight/
enrollment with attribution headers; the signed daemon/jinni bootstrap bundle
enrollment depends on is independently downloaded from Bespok3d's own GitHub
releases and OpenPGP-verified against their publisher key before being vendored,
not lifted from Helix's own copy (`scripts/build_bespok3d_bootstrap.py`; this
also meant dropping Helix's own combined-index signature scheme, which only
Bespok3d's release tooling can produce, in favor of trusting each archive's own
independently signed manifest). Remote screen turned out to be a JPEG-polling
mirror off `helixd`, not an MJPEG stream as first assumed from the plan alone —
corrected after reading Helix's actual runtime behavior. Unit-tested; enrollment
and the remote screen are **not yet device-verified against a real U1** (needs
the owner's own SSH access code, a deliberately separate, later step).

**M8c — Bambu Lab live support, upgrading P25.** Done 2026-09-20, commit
`bba9b8e`. `BambuPrinterService` (MQTT status/control, FTPS upload, chamber
camera re-served as loopback MJPEG), ported from Helix with attribution.
Print-start deliberately scoped to an already-sliced `.gcode.3mf` shared into
the app — Helix's own on-device `.gcode.3mf` builder needs bundled per-model
project-settings assets and requires input already carrying Bambu slicer
markers, which this app has no way to honestly produce without a slicer of its
own (that's M8's still-unbuilt on-device-slicing scope below, not this one).
Unit-tested (`BambuPrintProtocolTest` etc.); **not device-verified against real
Bambu hardware** — see the M7 exit-criteria gap noted above.

**M8d — timelapse gallery upgrade, extending P19.** Done 2026-09-20, commit
`f2271c1`. Pure Compose/Kotlin, no ported code: pairs each clip with
moonraker-timelapse's own same-stem poster-frame convention, adds in-app
playback (`VideoView` streamed straight from Moonraker, which honours Range
requests) and a "Save" action to a user-chosen document via SAF. Unit- and
androidTest-covered; **not yet visually verified on a real device**, the same
gap the original M4c browsing shipped with.

**M8e — bed-mesh 3D view, extending P12.** Done 2026-09-20, commit `d68decc`.
Clean-room, not ported (Helix's own bed-mesh view is JS/Skia): separable 2D
Catmull-Rom smoothing of the probed grid plus a simple orbit-camera projection,
drag-to-orbit/pinch-to-zoom via the same `detectTransformGestures` primitive
`LayerPreview.kt` already used. The one M8 item with full physical acceptance so
far: ran `BED_MESH_CALIBRATE` through the app's own macro runner against the
owner's real Snapmaker U1, confirmed the resulting 13×13 mesh renders correctly
and both gestures respond live on-device.

**M8f — visual redesign ("Kiln"), P28.** Substantially done 2026-09-20, commits
`24ba1a7` through `ed0ed17`. A deliberately distinct-from-Helix duotone dark
theme (ember for heat, teal for the app's own accent) and bundled OFL variable
fonts (Space Grotesk/IBM Plex Sans/Mono) replace the old flat scheme and system
default face; the bottom nav grows from 4 to 5 tabs (Home/Control/Files/Prepare/
Settings) — an early draft happened to match Helix's own nav almost exactly and
was reordered/relabeled after owner feedback; Home's printer list and the
per-printer dashboard hero/temperature cards pick up the gradient/status-dot
treatment from the design concept, and redundant title-block/footer chrome was
stripped from every tab in favor of a proper Settings-only Diagnostics section
reporting real `Build.VERSION`/`Build.MODEL` instead of a static string.
Physically verified live on the owner's Razr 2023 throughout, including two real
bugs a live check caught that code review alone had missed (a clipped hero card
from a missing `flex-shrink`-equivalent, an invisible texture from compounded
opacity). Remaining, explicitly not yet done: Bespok3d/Bambu panels, the bed-mesh
3D view and the timelapse gallery still render as pre-redesign `AlertDialog`
chrome (new colors/fonts apply via the theme change; the card/gradient treatment
does not, yet).

**Still open, no target date:** on-device slicing (M8's highest-risk remaining
scope — needs vendoring a ~23MB prebuilt `libprusaslicer-jni.so` of unverified
build provenance from the separate "u1-slicer-for-android" project; requires
explicit owner sign-off before that binary lands, not implied by the M8 decision
above) and a MakerWorld model browser (folded into the same later phase as
slicing, since it's a model-import source feeding it rather than standalone).

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
