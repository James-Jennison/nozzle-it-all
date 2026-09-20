# Work order

Derived from [`FEATURE_PARITY_ROADMAP.md`](FEATURE_PARITY_ROADMAP.md), last
re-derived 2026-09-20 after a Helix feature-set comparison surfaced a missing
emergency-stop control and two smaller gaps. The roadmap is the source of truth
for scope, evidence and licensing detail; this document exists only to turn its
current state into a single ordered queue of next actions, so "what's next" never
requires re-reading the whole roadmap. Re-derive this list whenever a phase below
completes or the roadmap changes — don't let it drift.

Rule: work top to bottom within a tier. Skip an item only when it is explicitly
blocked (an owner action or hardware it names), and move to the next unblocked one.
Don't start a "Later" or "Parked" item ahead of an unblocked "Now"/"Next" item just
because it looks more interesting.

## Now — unblocked, no owner action needed to start

1. **WO-1 — Build emergency-stop control (P29).** Missing entirely today — found
   2026-09-20 by comparing against Helix (which fires an M112-equivalent over both
   WebSocket and REST to every configured URL). Safety-relevant, so it jumps ahead
   of the rest of the Kiln-restyle/notification-actions queue below even though all
   three are equally unblocked to *start*. Build + unit/device-fixture test now;
   physical acceptance (actually sending it to a real printer) folds into WO-6
   below, same gate as the rest of Phase 1's controls.
2. **WO-2 — Close the M7 exit-criteria gap for Bambu Lab.** Add an in-app "not
   verified on real hardware yet" indicator on any `BAMBU_LAB` profile (dashboard
   card and/or detail view). M7's own exit criteria require this to be visible in
   the app, not just in docs — currently only the roadmap says it. Doesn't need
   Bambu hardware to build.
3. **WO-3 — Kiln-restyle the remaining pre-redesign panels.** Bespok3d panel, Bambu
   print panel, bed-mesh 3D view chrome and the timelapse gallery dialog still
   render as plain `AlertDialog` (theme colors/fonts apply, the card/gradient
   treatment doesn't). Pure UI consistency debt flagged in the P28 backlog row;
   no new capability.
4. **WO-4 — Notification actions + home-screen widgets (P18).** Natural next slice
   on top of M4b's already-built and live-verified `PrintMonitorService`/alert
   delivery: a Pause/Resume/Cancel action on the alert notification, and a widget
   for at-a-glance status.

## Next — one specific owner action unblocks each of these

5. **WO-5 — Device-verify Bespok3d enrollment + remote screen (M8b).** Code and
   unit tests are done. Blocked only on the owner's own Snapmaker U1/PAXX SSH
   access code — once supplied, this is a verification pass, not new development.
6. **WO-6 — Device-verify the timelapse gallery (M8d).** Code and tests are done.
   Needs a live pass on the Razr against a printer that actually has
   moonraker-timelapse clips recorded.
7. **WO-7 — Physical acceptance for the Phase 1/M2 live controls, including
   emergency stop.** Speed/flow, macro parameter forms, fan/light control, console
   command entry, config save/restart, and (once WO-1 lands) emergency stop are
   all built and sitting behind `LIVE_HEATER_FAN_CONTROLS_ENABLED`. Turning that
   gate on and sending real commands to a real printer needs an explicit, separate,
   safety-relevant owner go-ahead — this isn't a default-yes. **New context as of
   2026-09-20:** Helix is a shipped app that sends these same commands over the
   same Moonraker API to real Snapmaker U1/PAXX hardware in daily use — real,
   if informal, evidence the underlying commands are safe on this firmware fork.
   That's useful context for making this decision, not a substitute for it: it's a
   different codebase, and our own implementation could still have its own bugs.

## Later — blocked on hardware the owner doesn't have, or needs a decision first

8. **WO-8 — Bambu Lab hardware acceptance (M8c / P25).** Code exists and is
   unit-tested; blocked on the owner owning or gaining access to real Bambu
   hardware. Not actionable until then.
9. **WO-9 — Prusa support (P26 / M7).** Same hardware-availability blocker as
   WO-8, but unlike Bambu, no code exists yet at all.
10. **WO-10 — M5 optional AI monitoring.** Needs an architecture decision (rented-
    server vs. on-device inference) before any implementation starts, plus a
    labelled evaluation set with acceptance thresholds set in advance.
11. **WO-11 — M6 fleet/production workflows.** Queues/bed-cleared workflow,
    maintenance/cost trends, shared-library/server-slicing feasibility. No fixed
    start date; the roadmap sequences it after M5.

## Parked — no scope, no target milestone

- **On-device slicing** — M8's highest-risk remaining item; needs explicit owner
  sign-off before vendoring a ~23MB prebuilt `libprusaslicer-jni.so` of unverified
  build provenance. Helix itself ships this feature on the same engine, which is
  useful context but not a substitute for doing that verification ourselves.
- **MakerWorld model browser** — folded into the same later phase as slicing,
  since it's a model-import source feeding it rather than standalone.
- **`multiACE` support (P30)** — real PAXX hardware capability (RFID lane status,
  dryer, load/unload, cross-ACE switching), surfaced 2026-09-20 by the same Helix
  comparison that found the emergency-stop gap. Only relevant to owners with the
  hardware attached; no target milestone yet.
- **LAN/Tailscale automatic URL failover (P16 addendum)** — Helix keeps both a LAN
  and a Tailscale URL per printer and alternates on a 6s connect timeout; our
  profiles are still single fixed addresses. Real resilience gap, not yet scoped.
- **Wear OS** — reopened as a live option but not yet scoped (glance-only vs.
  actions-from-the-watch) or given test hardware.
- **The four 2026-09-16 reference-pass ideas** — community model import, mid-print
  object exclusion, solo phone-initiated slicing, input-shaper/resonance
  calibration. Listed for visibility only; none has a backlog ID yet.
