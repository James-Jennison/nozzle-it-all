# Govee foreground monitoring completion

Owner request: finish the agreed opt-in Govee framework, local history and
foreground Linux/Android collection, with build/test/Synthetic-review/install/live
verification. No additional procurement or certification acceptance program.

## Implementation

Ordinary room/position labels no longer require certification before collecting
observations. Existing explicit operating bounds and non-room equipment remain
guarded; assigning to an existing position never changes that position's limits.
Renamed new positions preserve the old limit pair and historical placements remain
intact. Both panels show the requested Govee provider/candidates, independent
per-sensor room drafts and saved labels. Save snapshots the latest draft at the
gesture, before dispatching IO, rather than capturing a previous composition.
Consent, off/idle isolation, explicit Start, foreground stop, no auto-resume,
bounded history/queue, retention/export/delete and no equipment control remain.

Current installed executable source:
`c41680a18fcf90a9a9016f575157176d2a958337`.

## Completed current-source loop

GTHost full build/package succeeded in 3m 7s. Results: 1,278 total, 1,268 passed,
10 conditional skips, zero failures/errors (core 45, domain 240, desktop 237,
Android 756). Gradle executed 31 tasks and reused 107 up-to-date tasks; unchanged
test inputs are explicitly cached, not represented as re-executed. Core/API source
trees exactly match source96. The raw 100 Hz fixture measured 4.229487% of one core,
incremental RSS 19,935,232 bytes, zero drops, physical_radio=false. Approved bounds
are 5% of one core and 32 MiB incremental memory. No all-target or battery guarantee.

Linux desktop-authenticated install exited0, dpkg-V clean, all208 payload files
SHA256-identical to the tested Deb, embedded source exactlyc416. Current installed
H5100 room-assigned collection persisted17 actual receptions with no certification
records. Complete immediate-type-and-Save text persisted exactly; clear-and-Save
did not overwrite the assignment. A first current harness replacement clicked
before focus settled and visibly appended the Room prefix; that failed expectation
was retained, then corrected with focus settlement before typing (no post-type
delay before Save). Reopening Settings restored the saved full room/position labels.

Visibility loss stopped collection; restoring focus did not resume, countstable17.
Open history showed receptions. UIexport27NDJSONlines contained exactly all17 event
IDs. An initial checker incorrectly expected kind=observation; actual export schema
contains direct eventId records. Corrected schema-aware comparison passed. Explicit
UI deletion cleared only this isolated17-event fixture and its metadata; export and
before-delete DB backup retained, so the removed test data is recoverable. Fresh
disabled/enable-idle states created no history database. Runtime used isolated
Xvfb1600x1000/software and the actual `/opt` executable, not physical scanout proof.

On freshly identified motorola razr2023/API36, the isolated
`com.nozzleitall.app.environment` acceptance variant was installed and its baseAPK
digest/source matched. UI+SQLite2tests PASS6.483s; actual H5100 room-save-before-Start
radio1test PASS9.784s with1persisted reception and foreground/navigation stop/noauto.
The normal Android app, its profile and PID23745 were preserved, not upgraded or
reset. Other connected devices and printers were untouched. Owned acceptance
processes stopped; no lingering scan or new background service.

Package SHA256:

- Deb: `76b7bf88160b3ce0272435541c24757ebbb775ea0814e06bbf30100dfd9cd85d`
- Acceptance APK: `97387315f1332324145024b1af0a162064f4718ea73026a439733f875973d496`
- Test APK: `7339c02a8c75a3586726dcc561477d51850f4febff53f62430abdf45c965b3b5`
- Unchanged engine: `d66da0a39dcf427079ab09e88b6692f6645e3a662aa0415ebee6c758d2e8d015`

28-artifact manifest SHA256:
`3c6c3ee28e3c0e4f63165d4235bde72bd0cc761c1e9e2fd8f15fb2426399ffc3`.
The evidence verifier checks the immutable manifest, all28digests, actual test XML,
budget metrics, complete model provenance, raw Android results, installed hash
snapshot, actual DB/export contents and preserved failure artifacts. Evidence
cycle749c3e1987df bothPASS/exit0/integritytrue; convergence945ea12f05f1;
closureac38f3d70b5e = READY_WITH_RESIDUAL_RISK, no blockers/no acceptedHIGH risk.
Three LOW residuals: frame-updated enablement feedback (payload remains correct
and blank validation fail-closed), fixture measurement scope, and the retained
prior native AFC nondeterminism. These are limits, not extra completion tasks.

Current-source Synthetic bb345a447690 all3 exact configured seats COMPLETE;
frontend clarification524c5b75f239 COMPLETE withdrew its HIGH default-overwrite
premise. Defaults are intentional editable values; a cleared Pair never falls
through to them.12 immutable current findings have separate resolved dispositions;
no consensus or reviewer vote replaces actual validation. Sourcec416 is pushed.
Exact-source CI is tracked separately below, not inferred from the manual build.

## Exact-source CI

[Run39247812266](https://github.com/James-Jennison/nozzle-it-all/actions/runs/39247812266)
completed SUCCESS on sourcec41680a18fcf90a9a9016f575157176d2a958337.
Runner verified as `gthost-build01`; unit tests/lint and native arm64 debug assembly
passed. Manual-only Device Farm and release jobs were skipped as intended, not
reported as hardware/release evidence. The CI snapshot is appended separately to
the existing28-artifact seal; that immutable manifest was not rewritten.

## Preserved prior results (not current-source acceptance)

Source96a76037 full GTHost retry SUCCESS4m10s. Core45/45; desktop237 total,
228PASS/9conditional skips; domain240PASS; Android756 total,755PASS/1skip.
An initial native AFC slicing failure with missing-Voronoi warnings is retained;
unchanged source and engine passed the full retry. No engine/test assertion change.
100Hz fixture CPU4.22949%onecore/RSS+19,935,232bytes/drop0, within approved bounds.

Source96 Linux install exited0; all208 installed payload hashes matched its Deb.
Fresh disabled and enable-idle profile had no history database. Actual H5110 room
assignment and receptions persisted without certification records. Immediate
typing+Save exposed a real stale-final-character bug (Desk shelf displayed, Desk
shel persisted); that failed expectation is retained, not reported PASS. The current
installed source fixes the click-time snapshot on both platforms. Prior source96 runtime
does not validate that later fix.

Source96 verified motorola razr2023/API36 acceptance variant installed/digest matched;
actual UI+SQLite2tests PASS6.997s, live H5100 assigned-room collection/foreground
stop1test PASS9.641s/1persisted event. Production app PID23745 remained unchanged;
other devices untouched. Its acceptance data was reversibly preserved before the
next source installation. No battery endurance or thermal-safety claim.

## Review provenance

Initial6a5860b10f1b frontend/adversarial COMPLETE; systems provider request failed
and remains FAILED, with no fallback. Subsequent broad cyclesa56b97d9ed70 and
ff50888e39cc all3COMPLETE; targeted96 source cycled873ac7c771d all3COMPLETE on
configured GLM5.3/KimiK3/DeepSeekV4.1Flash, no substitution. Confirmed metadata and
UI defects were fixed. Contradicted interleaving, cross-ID overwrite, per-row map
recreation and empty-message premises were explicitly challenged/withdrawn;
original evidence and separate dispositions remain. Reviewer agreement is not
runtime proof. Current-source click-time correction review and objective loop are
recorded above.

Private evidence:
`/home/jjennison/.local/state/nozzle-it-all/acceptance/20261019-govee-room`.
Original baseline38-artifact seal and owner scope-correction ledger remain intact.
