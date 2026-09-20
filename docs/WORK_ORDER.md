# Work order

Derived from [`FEATURE_PARITY_ROADMAP.md`](FEATURE_PARITY_ROADMAP.md) as of 2026-09-20.
The roadmap is the source of truth for scope, evidence and licensing detail; this
document exists only to turn its current state into a single ordered queue of next
actions, so "what's next" never requires re-reading the whole roadmap. Re-derive this
list whenever a phase below completes or the roadmap changes — don't let it drift.

Rule: work top to bottom within a tier. Skip an item only when it is explicitly
blocked (an owner action or hardware it names), and move to the next unblocked one.
Don't start a "Later" or "Parked" item ahead of an unblocked "Now"/"Next" item just
because it looks more interesting.

## Now — unblocked, no owner action needed to start

1. **WO-1 — Close the M7 exit-criteria gap for Bambu Lab.** Add an in-app "not
   verified on real hardware yet" indicator on any `BAMBU_LAB` profile (dashboard
   card and/or detail view). M7's own exit criteria require this to be visible in
   the app, not just in docs — currently only the roadmap says it. Doesn't need
   Bambu hardware to build.
2. **WO-2 — Kiln-restyle the remaining pre-redesign panels.** Bespok3d panel, Bambu
   print panel, bed-mesh 3D view chrome and the timelapse gallery dialog still
   render as plain `AlertDialog` (theme colors/fonts apply, the card/gradient
   treatment doesn't). Pure UI consistency debt flagged in the P28 backlog row;
   no new capability.
3. **WO-3 — Notification actions + home-screen widgets (P18).** Natural next slice
   on top of M4b's already-built and live-verified `PrintMonitorService`/alert
   delivery: a Pause/Resume/Cancel action on the alert notification, and a widget
   for at-a-glance status.

## Next — one specific owner action unblocks each of these

4. **WO-4 — Device-verify Bespok3d enrollment + remote screen (M8b).** Code and
   unit tests are done. Blocked only on the owner's own Snapmaker U1/PAXX SSH
   access code — once supplied, this is a verification pass, not new development.
5. **WO-5 — Device-verify the timelapse gallery (M8d).** Code and tests are done.
   Needs a live pass on the Razr against a printer that actually has
   moonraker-timelapse clips recorded.
6. **WO-6 — Physical acceptance for the Phase 1/M2 live controls.** Speed/flow,
   macro parameter forms, fan/light control, console command entry and config
   save/restart are all built and sitting behind `LIVE_HEATER_FAN_CONTROLS_ENABLED`.
   Turning that gate on and sending real commands to a real printer needs an
   explicit, separate, safety-relevant owner go-ahead — this isn't a default-yes.

## Later — blocked on hardware the owner doesn't have, or needs a decision first

7. **WO-7 — Bambu Lab hardware acceptance (M8c / P25).** Code exists and is
   unit-tested; blocked on the owner owning or gaining access to real Bambu
   hardware. Not actionable until then.
8. **WO-8 — Prusa support (P26 / M7).** Same hardware-availability blocker as
   WO-7, but unlike Bambu, no code exists yet at all.
9. **WO-9 — M5 optional AI monitoring.** Needs an architecture decision (rented-
   server vs. on-device inference) before any implementation starts, plus a
   labelled evaluation set with acceptance thresholds set in advance.
10. **WO-10 — M6 fleet/production workflows.** Queues/bed-cleared workflow,
    maintenance/cost trends, shared-library/server-slicing feasibility. No fixed
    start date; the roadmap sequences it after M5.

## Parked — no scope, no target milestone

- **On-device slicing** — M8's highest-risk remaining item; needs explicit owner
  sign-off before vendoring a ~23MB prebuilt `libprusaslicer-jni.so` of unverified
  build provenance.
- **MakerWorld model browser** — folded into the same later phase as slicing,
  since it's a model-import source feeding it rather than standalone.
- **Wear OS** — reopened as a live option but not yet scoped (glance-only vs.
  actions-from-the-watch) or given test hardware.
- **The four 2026-09-16 reference-pass ideas** — community model import, mid-print
  object exclusion, solo phone-initiated slicing, input-shaper/resonance
  calibration. Listed for visibility only; none has a backlog ID yet.
