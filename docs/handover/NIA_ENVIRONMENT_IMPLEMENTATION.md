# Environmental monitoring B–D implementation

Owner approved B–D implementation on 2026-10-18 against architecture SHA256
`dfbdd62225e24e89e850fa7326f8a877993e4ec0edba3b08a09760742fa5700d`.
The historical sealed preapproval request is preserved. E–G, background services,
cloud, HA/MQTT, analytics, alerts, and heater/printer actuation remain out of scope.

Owner approved configurable defaults: 45-second discovery, explicitly started
foreground collection, 256 queued reports, 500-row pages, 30-day / 100 MiB history,
CPU below 5% of one core at 100 reports/sec, incremental bounded memory below
32 MiB. Battery measured where possible, no battery-life guarantee.

## Current source validation (not milestone acceptance)

Functional source `9b6e152a49aa49562857fc69d8481319e9bdb941` implements lazy
opt-in control, separate bounded environmental SQLite history, and scan-only
foreground Android/BlueZ ports. App-owned Android controller ownership survives
navigation without auto-resume. UI gestures reserve preparation before IO;
Stop/Off revoke immediately, stale queued Starts cannot execute, and finite owned
Stop/Delete cleanup survives panel disposal. Recovery blocks history access until
explicit deletion/resume. Export uses nonblocking validity checks under its store
mutex. Unknown qualification remains diagnostic or blocked, never self-certified.

GTHost focused validation: core41/41 PASS; desktop237 tests,228PASS and9 conditional
skips, zero failures/errors. Includes real SQLite-full recovery, stale callbacks,
queued Start/Stop, foreground disposal, recovery history denial, owned deletion,
and three test-child `Runtime.halt(17)` crash/reboot/resume boundaries. The latter
are process-death tests, not filesystem power-loss proof.

Exact-source core pipeline fixture:100 reports/sec,30seconds/3000 persisted,
3.96719% of one CPU core, sampled peak incrementalRSS16,736,256bytes, no drops.
Separate15second JIT warmup4.73329%. This is not GUI/physical-radio CPU, Android
battery endurance, universal startup, or all-device performance evidence. Earlier
failed CPU/SQLite tests are retained rather than relabeled PASS.

Prior actual diagnostic checks: packaged Linux777 selected H5074 and persisted11
receptions; visibility loss/restore stopped without auto-resume. Android74 with
instrumented777 on verified Razr API36 selected H5100 and persisted actual
reception, SCAN only/no CONNECT; foreground/navigation stop PASS.

Current-source full packaging subsequently passed2m51s on GTHost:1274total,
1264PASS/10conditional skips/0failure or error. Exact Deb SHA256
`f2aa6cbe090dbd8f71f1f6965015005646ab210b047d7410c78635d3dd23c8ed`;
acceptance APK SHA256
`26ce7dc6f6c907264a558e27eefd0811c7bea2a27654028a1cb29d86d57d2d29`.
Installed base APK digest matches, embedded source found in APK/desktop jar.

Exact9b6 Razr UI+SQLite tests2/2 PASS7.383s, actual H5100 physical diagnostic
test1/1 PASS10.92s: one persisted event, radio identity asserted, navigation stops
with no auto-resume. Production Android package remains untouched. Battery69%,
30C while AC-powered is confounded, not battery-life proof.

Exact extracted Deb candidate `/tmp/n9b6-app` actual BlueZ H5100 diagnostic flow
persisted11 events. Minimize/restore stopped and remained11; UI exported15 NDJSON
lines including all11 events. Explicit UI deletion cleared its isolated11 test
events/configuration, reported Disabled, and retained the external export. A
separate sealed85-file Off/EnableIdle syscall trace has no environmental history
access/file or BlueZ system-bus connection. Software Xvfb, not physical scanout.
Desktop-authenticated `/opt` installation subsequently exited0. All208 installed
payload files SHA256 match the tested Deb, embedded source exactly9b6; dpkg -V clean.
Installed-path Off/EnableIdle89sealedtraces show no history/BlueZ access. Actual
installed BlueZ H5110 diagnostic14events, visibility stop/noauto-resume/countstable14,
UIexport18lines/all14 and explicit deletion0events/entities PASS; externalexport
retained. These are installed-path observations, separate from candidate proof.
Low-rate whole-app15s CPU2.13273%onecore/RSS339,042,304→339,427,328bytes is an actual
workstation observation, not100Hz/environment-only attribution or qualification.

Synthetic cycle `review-cycle-5c3fac538b79` completed all3 current-source reviews;
targeted clarification `review-cycle-b534478e38ce` completed both seats, frontend
attempt2 after a retained worker failure.137 original implementation findings are
retained with separate dispositions so far. Actual code/lock analysis rejected
imagined use-after-close, uncovered persistence failures and Main-event
interleaving; dissent is preserved, not called consensus. The frontend reviewer
withdrew its pending-permission race: Stop/lifecycle clears pending on Main before
later grant delivery. Closure-evidence cycle `review-cycle-d559aa66f40a` completed
all3 exact configured seats with no further source correction required.156 original
implementation findings now retained, with immutable responses and separate
dispositions; no claim of unanimous agreement on rejected theoretical findings.
Bounded Main bootstrap read, unsynced overflow tail, and cleanup dependence on
scheduler/disk availability remain disclosed LOW limits, not completeness or
instantaneous-durable-stop guarantees.

Current-source reviews do not certify hardware acceptance;
earlier b659/6ee/b803 cycles each completed all three configured independent seats.
Original findings and separate Codex dispositions are retained in the broker.
No provider/model substitution or reviewer mutation authority.

## Unmet physical acceptance gates

The Owner confirms no BTHome hardware is available. Exact Govee firmware,
calibration, thermal-envelope, cadence and coexistence qualification artifacts are
also absent. Diagnostic reception and protocol fixtures do not satisfy those
requirements. No qualification records or assigned qualified deployment exist;
full milestone D remains NOT_READY, not waived or certified by a UI checkbox.
Actual target-hardware radio/battery/CPU/disk qualification remains incomplete;
the GTHost core fixture is not a substitute. No battery-life guarantee was promised.

## Final software/evidence checkpoint

Exact-source [CI39199638868](https://github.com/James-Jennison/nozzle-it-all/actions/runs/39199638868)
SUCCESS on verified `gthost-build01`, including tests/lint/nativeARM64assembly.
Manual-only Device Farm and release jobs skipped as intended, not claimed tested.
Functional source9b6 is pushed on `feat/engine-orca-2.5`; later report/helper edits
do not alter the installed binary source. No PR merge, release/tag, printer action,
provider/model/privacy change, qualified deployment, or E–G implementation.

Manifest SHA256
`1d30730f779fc03035c711897882b6a6831692209081e9119bc2196c92c54863`
seals38 current-source artifacts, including exact packages, raw test logs/XML,
eight completed Synthetic response snapshots, installed verification, traces,
selected-sensor exports and actual device results. Earlier pending/failed records
are historical, not rewritten; later installed/CI records close their specific gates.

Broker evidence `evidence-cycle-4f094abd2512`: diff-format PASS, source-bound
B–D aggregate FAIL, both integritytrue. The aggregate correctly names the three
unmet performance/physical Govee/physical BTHome qualification gates. Its immutable
FAIL has an ENVIRONMENT_FAILURE disposition, not a PASS or accepted-risk waiver.
Convergence `convergence-d94c9a7bbfdf`; closure `closure-43be3827a678` is
`NOT_READY`, three disclosed LOW residuals, no accepted HIGH/critical risk.
Seven unresolved qualification observations remain; no evidenced source correction
is pending. The skill-driven evidence-first closure must not promote this to full
B–D acceptance without the missing physical qualification evidence.

Five unrelated owner files and the approved architecture hash are unchanged.
The review helper now requires explicit sanitized evidence matching the submitted
source revision, rather than replaying stale checkpoint literals. Syntax and diff
checks pass. Owned desktop test processes were closed after verification; exports
remain recoverable in the private evidence root. Production profiles were not cleared.

Evidence root:
`/home/jjennison/.local/state/nozzle-it-all/acceptance/20261018-environment`.
The production Android package, LCARS tablet, Samsung and existing printer state
are outside these acceptance mutations; only the isolated `.environment` package
on the freshly verified Motorola Razr is an Android installation target.
