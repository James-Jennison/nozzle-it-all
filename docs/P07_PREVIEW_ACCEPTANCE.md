# P07 — Advanced control preview

Scope: owner-approved fixture-only development while the real printer is active.
This is a local interactive simulator, **not full P07 or physical acceptance**.

Entry: Control → Preview advanced controls.

- Example PLA/PETG/off heater targets, with separate nozzle/bed values.
- Part cooling fan percentage, print speed and extrusion flow previews.
- Signed X/Y/Z jog distances and extrusion/retraction previews.
- Simulated idle/warm, printing, paused, print-error, cold, unhomed, offline, stale and unsupported
  states. Every result identifies itself as a simulation; example limits are not
  represented as the owner's printer settings.
- Guards reject invalid/injected numbers, missing or nonfinite bounds, stale or
  different connection generations, unsupported kinematics, cold extrusion,
  unhomed/out-of-range movement and printing/paused/error/unknown states.
- Movement/extrusion previews require support for the speed/flow override commands
  used inside their save/restore sequence.

`ControlPreview` is a separate data type, not a `PrinterCommand`. The panel has no
model/service/execute callback. No new production network calls or command dispatch
paths exist. Script previews cannot be sent from this screen.

The planner illustrates state-preserving relative motion with bounded distances and
explicit feedrates. It is not a live execution adapter: script interruption can
prevent restoration; hardware collision checks, configured motion/extrusion limits,
G-code overrides, tool-specific behavior, live capability discovery and fresh
pre-dispatch validation remain necessary before physical rollout. Rectangular
travel bounds alone do not establish a collision-free path.

References used for command syntax and status fields:
- https://www.klipper3d.org/G-Codes.html
- https://www.klipper3d.org/Status_Reference.html

Validation so far:
- Protected build/lint/package/source proof PASS; 53 JVM tests, no failures/skips.
- Razr targeted suite: OK (4 tests). Printing blocks preview; cold extrusion and
  unhomed motion show errors; numeric injection is rejected; changing input clears
  old results; the real Control-tab route never invokes its execution callback.
- Focus-checked Razr dialog and result screenshots visually inspected. Installed
  APK hash matches the validated build:
  `2705d6394b6b94302cb913ad0633ad9df19ee17992b46c4cdb8652dc6af1ee45`.
- Broker evidence cycle `evidence-cycle-510567413980`: PASS. All three final reviewers reported no findings. Initial review findings were corrected: print-error
  rejection and override-capability checks for scoped movement. Regression tests
  pass; the Print error scenario was visibly blocked on the final APK.
- A manual UI script initially waited for an offscreen result; it was stopped,
  corrected to scroll to the result, and passed. Original output is preserved.

Evidence: artifacts/p07; reviewer change mobile-klipper-p07-preview-20260906.

Final numeric remediation uses exact decimal comparisons for input ranges and axis
destination calculations, matching emitted decimal values. Regression tests cover
values infinitesimally beyond every upper bound and an axis destination bound.
Fan percentage conversion also uses exact decimal multiplication (2.55), verified
with the review-provided high-precision input/output regression.

Final review cycle: `review-cycle-663451a71b67`. All four findings have separate
durable fixed dispositions; prior evidence and review records are retained.
