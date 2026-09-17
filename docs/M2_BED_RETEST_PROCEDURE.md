# Proposed supervised bed retest

Status: preparation only. No new live action is authorized or performed by this
document. M2 remains incomplete. Preserve the failed first run and its helper as
evidence; do not rerun that helper.

## Evidence and constraint

The original app heating confirmation occurred at 1788739268.1719434. The first
observed target40 was at 1788739272.811454. Off confirmation occurred at
1788739321.5738893; target0 was observed at 1788739322.9678605. Thus off was
verified 54.796 seconds after heating confirmation. Actual readings went from
32 to41 degrees C. Heating and shutdown worked; timing failed.

HeaterPanel prepares asynchronously; Checking is not a failed review or evidence
that a command was sent. Its five-second confirmation age starts when preparation
completes. PrinterModel performs fresh status and heater reads before dispatch.
Moonraker permits seven seconds per HTTP call. Multiple calls and UI operations
mean the app alone cannot enforce a thirty-second physical cutoff. Local replay
can check this procedure's evidence arithmetic, not prove a live deadline.

## Required owner decision before a live run

Identify an independently accessible, known-working way for the owner to turn
this printer's bed off if app shutdown is delayed. The exact control and its
scope must be checked together before heating. Do not assume a touchscreen,
emergency stop, power switch, or their effects. No new firmware, macro, service,
or alternate software command route is part of this preparation.

If no suitable independent cutoff is available, do not begin this timed retest.
A separate decision about the deadline or a verified cutoff mechanism is needed.

## Proposed run after separate explicit approval

1. Owner remains at the printer with the agreed cutoff available. Verify the
   Razr2023 identity, intended app/build and printer address; verify ready/idle,
   bed target0, finite actual temperature below40, and clear bed. No nozzle,
   motion, homing, file change, or autonomous worker is in scope.
2. While target remains0, rehearse locating Off and obtaining a fresh review of
   exactly SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=0. This is a read-only
   review, not a confirmation. Confirm the owner can identify the independent
   cutoff. A slow or failed rehearsal prevents heating; a fast rehearsal does
   not guarantee subsequent timing.
3. Review exact bed40 in the app. Confirm once; start a monotonic timer immediately
   before the tap. Record wall time for audit. Unknown heating outcome must not
   cause another heat confirmation.
4. Begin the off workflow immediately when enabled, without a warming dwell:
   tap the observed Off preset, Review, wait for the exact bed0 script, then
   Confirm once within its review lifetime. Avoid keyboard entry and unnecessary
   UI dumps. Checking means wait within the existing deadline. Never confirm a
   stale review or restart a review indefinitely. Do not let telemetry collection
   delay this workflow.
5. Owner watches an independent timer. If target0 is not positively verified by
   20 seconds, or control/connectivity fails earlier, owner uses the agreed
   independent cutoff immediately. Stop app automation on handover. An outstanding
   heat request may still be in flight: a target0 reading alone before known heat
   acceptance does not prove it cannot heat later. A fallback must account for
   this; no further app confirmation while the owner controls recovery.
6. Record actual target40 acceptance, temperature observations, off dispatch,
   target0 verification, unknown outcomes and any owner intervention. Verify
   target0 again after acknowledged shutdown; do not confuse residual temperature
   with a nonzero target. If shutdown remains unknown, report that explicitly and
   keep recovery owner-controlled.

## Acceptance

App-path timed acceptance requires positively observed target40, a subsequent
app off acknowledgement and target0 readback within30 seconds of the original
heat tap, with no fallback, duplicate heating, or unresolved in-flight outcome.
A temperature rise is recorded if observed; do not lengthen the run to obtain it.
The earlier run already supplies warming evidence, but cannot pass this deadline.
If the owner intervenes, record successful recovery separately: it is not an app
shutdown timing pass. Missing or late evidence means NOT VERIFIED or FAIL, never
an inferred pass. No actual live timing is guaranteed by this procedure.
