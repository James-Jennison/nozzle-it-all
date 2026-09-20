# Work order

Derived from [`FEATURE_PARITY_ROADMAP.md`](FEATURE_PARITY_ROADMAP.md), last
re-derived 2026-09-20. That day: a Helix feature-set comparison surfaced a
missing emergency-stop control (built, P29, commit `f080790`); a stale
physical-acceptance status got corrected (see below); and the owner extended
"build it even though I don't own the hardware" into a standing principle,
under which Panda Breath (P31, commit `afde29d`), Spoolman (P13, commit
`7fa93f1`) and PAXX multiACE (P30, commit `70a8a7b`) all got built. None of
those three are physically verified — see the "Later" tier below.
**Correction, same day:** this document previously said Phase 1/M2 physical
acceptance "needs an explicit, separate... owner go-ahead — this isn't a
default-yes," which had gone stale — that go-ahead was already given on
2026-09-19, and heater controls are already a complete physical PASS on the
real Snapmaker U1, with fan controls partially verified. See WO-6 below and the
roadmap's M2 section for the real current state. The roadmap is the source of
truth for scope, evidence and licensing detail; this document exists only to
turn its current state into a single ordered queue of next actions, so "what's
next" never requires re-reading the whole roadmap. Re-derive this list whenever
a phase below completes or the roadmap changes — don't let it drift, and don't
trust a status here without spot-checking the underlying code/docs first, the
way this correction had to.

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
6. **WO-6 — Continue the already-authorized physical acceptance pass.** Owner
   authorized live testing on 2026-09-19; `LIVE_HEATER_FAN_CONTROLS_ENABLED` is
   `true` and supervised, category-by-category sessions against the real
   Snapmaker U1 are ongoing (owner watching throughout, cross-checked against
   Moonraker's own status each time — not self-certified by the app). Done so
   far: heater controls (bed + nozzle) **complete PASS**; fan controls
   **partial** (`fan`, `cavity_fan` verified; `exhaust_fan`/`circulation_fan`
   found to be purifier-managed and hidden rather than left misleading;
   `e1_fan`–`e3_fan` untested). Emergency stop (P29) is now built (commit
   `f080790`) and ready for its own session. Still needs a session: the rest of
   fan controls, P07's speed/flow/movement/extrusion, P08 live file mutations,
   P09 live tracking, remaining hardware macro acceptance, and emergency stop.
   Needs the owner physically present with the printer each time, same as the
   sessions already done — not a go/no-go decision anymore, that part already
   happened. Helix's own shipped, real-world use of these same Moonraker calls
   (found while building emergency stop) is useful supporting context, not what
   actually unblocked this — the owner's own sign-off did, a day earlier.

## Later — blocked on hardware the owner doesn't have, or needs a decision first

7. **WO-7 — Bambu Lab hardware acceptance (M8c / P25).** Code exists and is
   unit-tested; blocked on the owner owning or gaining access to real Bambu
   hardware. Not actionable until then.
8. **WO-8 — Prusa support (P26 / M7).** Same hardware-availability blocker as
   WO-7, but unlike Bambu, no code exists yet at all.
9. **WO-9 — Physical acceptance for Panda Breath (P31), Spoolman (P13) and
   multiACE (P30).** All three built 2026-09-20, unit-tested against Helix's
   exact logic, none physically verified — no owner hardware exists for any of
   them (a chamber-heater/dryer accessory, a Spoolman install, or PAXX
   multiACE). Same hardware-availability blocker as WO-7/WO-8; needs either the
   owner acquiring the hardware or a volunteer/beta tester who has it, per M7's
   own precedent for evidence from hardware the owner doesn't personally own.
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
  Deliberately NOT covered by the "build it even without the hardware" principle
  behind WO-9 above — this is a supply-chain provenance risk, a different kind
  of gap than not owning a piece of hardware.
- **MakerWorld model browser** — folded into the same later phase as slicing,
  since it's a model-import source feeding it rather than standalone.
- **LAN/Tailscale automatic URL failover (P16 addendum)** — Helix keeps both a LAN
  and a Tailscale URL per printer and alternates on a 6s connect timeout; our
  profiles are still single fixed addresses. Real resilience gap, not yet scoped.
- **Wear OS** — reopened as a live option but not yet scoped (glance-only vs.
  actions-from-the-watch) or given test hardware.
- **The four 2026-09-16 reference-pass ideas** — community model import, mid-print
  object exclusion, solo phone-initiated slicing, input-shaper/resonance
  calibration. Listed for visibility only; none has a backlog ID yet.
