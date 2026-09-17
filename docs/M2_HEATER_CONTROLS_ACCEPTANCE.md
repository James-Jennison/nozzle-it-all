# M2 heater controls — supervised acceptance preparation

Local implementation and device/read-only acceptance are validated. The approved physical bed test verified warming and shutdown, but failed its
intended timing criterion; full M2 is not complete.

## Changes

- Active nozzle telemetry uses the current toolhead extruder identity, not a
  hardcoded primary extruder. Named tools are bounded and validated; switched or
  missing tool data yields unknown temperature instead of another tool's reading.
- Control → Heater controls provides bed and current active-nozzle targets,
  example presets, explicit named G-code review and separate confirmation.
- Configured temperature limits are read from the printer. Numeric parsing uses
  exact decimal limits; unknown readings, non-idle/error state, tool changes and
  overridden standard heater commands reject the plan.
- Before dispatch, PrinterModel obtains fresh readiness and heater state, rebuilds
  the canonical command and rejects changed/tampered commands.
- Review expiry, lifecycle and connection/tool/state changes invalidate pending
  commands. Obsolete reads cannot update later reviews. Background cancellation
  preserves unknown outcomes and never automatically retries.

## Verified evidence

- Protected build/lint/debug and instrumentation packaging: PASS. 87 JVM tests
  passed, zero failures/errors/skips. Evidence-cycle-e183d0e0d167.
- Razr 2023: four fixture tests passed and the live test skipped by default
  (instrumentation status -4), in device-closure.txt. Supplemental lifecycle
  regression passed in 4.975 seconds after instrumentation-only compilation.
- Approved live status transport is GET-only. Initial approved device read passed;
  final exact-build read is recorded in device-approved-read-final.txt.
- Actual app opened the heater panel on the real printer; bed 40°C review generated
  SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=40. It was not confirmed.
  Screenshot bed-40-review.png was inspected: identity, configured limits,
  command and confirmation remain readable.
- Installed Razr APK hash matches the validated build:
  4b980d4a3f8d6e2cd4b1e9526e82324591a95cf9b0029db79aeb0d4eb0acf8a8.
- Claude, Gemini and DeepSeek completed initial and targeted reviews. Accepted
  findings: live test opt-in guard, stale notice invalidation and dedicated
  background lifecycle coverage; all corrected with separate dispositions.
- Local-slice closure-cbf6af359244: READY. This is not physical or M2 closure.

Earlier UI fixture failures remain preserved. The fixture omitted its printer
address and therefore showed setup; final fixture supplies fixture.local and
scrolls the lazy list correctly. A nozzle icon dependency on the old exact label
was also corrected. Earlier evidence must not be presented as validation of later
source. Final artifacts and ledger are under artifacts/m2-heaters.

## Supervised bed test outcome

The owner explicitly approved app bed40, observation up to30seconds, then app
bed0. The actual app confirmed each exact command once. Readback recorded
ready/standby throughout, initial bed32 target0, target40, and final bed41 target0.
Shutdown was verified 54.8 seconds after the heating confirmation. This exceeded
the intended 30-second window and is NOT a clean acceptance pass.

The UI helper incorrectly expected the off review to finish in one UI dump; the
app was still Checking. No off confirmation had been sent at that point. The
already-approved off review was refreshed and confirmed once, and target0 was
verified. No heating command was repeated. No nozzle, motion, homing or file
mutation was performed. Raw timestamped observations are in bed-test-live.json.
The one-shot helper is retained as failed-test evidence and must not be rerun.
A timing-bounded supervised retest requires a revised procedure and owner
approval; no additional physical test is authorized by this result.

P07 fan/overrides/motion/extrusion, P08 live file mutations, P09 live tracking and
remaining hardware macro acceptance are still outstanding. Continue full M2,
then M3, directly in conversation; autonomous workers remain stopped.

Revised preparation: [M2_BED_RETEST_PROCEDURE.md](M2_BED_RETEST_PROCEDURE.md).
The app cannot guarantee the deadline; a verified independent owner cutoff is
a prerequisite. No new live heating was performed during this preparation.

## Approved supervised retest v2

Owner confirmed the touchscreen bed0 control and explicitly approved one app
bed40 -> immediate app bed0 retest, with touchscreen intervention at20seconds.
The same validated APK was verified on the Razr2023. Fresh readback showed
ready/standby, bed36 target0. A cold bed0 review completed without confirmation.

The new helper confirmed heating once, then off once. Off confirmation occurred
11.968seconds after heat intent. An early deadline including review preparation
stopped automation before target0 was verified and handed recovery to the owner.
The first subsequent target0 readback was22.130seconds after heat intent; four
successive recovery samples reported target0. These samples establish off target,
not whether the app or touchscreen caused it. No additional commands were sent.

App-only timed acceptance: NOT VERIFIED. The owner touchscreen report is pending,
and app acknowledgement was not captured. Do not infer the exact shutdown time
between samples or count this as a pass. No more heating tests are authorized.
Evidence: bed-retest-v2-live.jsonl, bed-retest-v2-recovery.jsonl, and
bed-retest-v2-result.json under artifacts/m2-heaters. M2 remains incomplete.

### Owner report and acknowledgement supplement

Owner subsequently reported: "No, I did not change it" (touchscreen intervention).
Closing the heater dialog exposed the retained app notice:
"Printer acknowledged Set heater_bed to 0°C." No physical command was issued
while collecting that notice. Therefore the app-only bed40 -> bed0 sequence is
verified, with the first target0 readback22.130seconds after heat intent, within
the30second criterion. The earlier20second cutoff verification was not met;
the alert and interim NOT VERIFIED assessment remain in the raw evidence.

Bed-only30second acceptance: PASS. Earlier55second failure is preserved. Nozzle,
other P07 controls and full M2 are not accepted by this result. Final supplemental
evidence is bed-retest-v2-final-acceptance.json. No further heating test is needed
for this bed-only check, and none has been dispatched.
