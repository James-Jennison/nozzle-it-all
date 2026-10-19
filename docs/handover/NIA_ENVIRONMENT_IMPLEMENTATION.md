# Environmental monitoring B–D implementation

Current Govee follow-up: executable source
`c41680a18fcf90a9a9016f575157176d2a958337` is installed and live-verified on Linux
and in the isolated Razr acceptance variant. Ordinary room assignments no longer
require certification to collect observations. Exact GTHost build/tests, Synthetic
reviews, installation and runtime loop completed; evidence749c3e1987df PASS/intact,
closureac38f3d70b5e READY_WITH_RESIDUAL_RISK/no blockers. See
`NIA_GOVEE_COMPLETION.md` for current source-bound proof and the normal Android
profile preservation boundary. Baseline details below describe the earlier9b6
implementation and records-only scope correction, not the later room-label fixes.

Owner approved B–D implementation on 2026-10-18 against architecture SHA256
`dfbdd62225e24e89e850fa7326f8a877993e4ec0edba3b08a09760742fa5700d`.
The historical sealed preapproval request is preserved. E–G, background services,
cloud, HA/MQTT, analytics, alerts, and heater/printer actuation remain out of scope.

Owner approved configurable defaults: 45-second discovery, explicitly started
foreground collection, 256 queued reports, 500-row pages, 30-day / 100 MiB history,
CPU below 5% of one core at 100 reports/sec, incremental bounded memory below
32 MiB. Battery measured where possible, no battery-life guarantee.

## Verified baseline implementation and requested validation (historical)

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

## Corrected acceptance scope — owner direction2026-10-19

The earlier project-wide qualification blockers were agent-added scope, not owner
requirements. They are withdrawn from current acceptance. The authoritative
correction is `NIA_ENVIRONMENT_ACCEPTANCE_SCOPE.json`. It supersedes the broader
acceptance-checklist wording, without modifying historical design/evidence artifacts.

Required: the requested opt-in framework/history/foreground Android/Linux BLE,
available-device Govee live checks, and build/test/Synthetic-review/install/runtime
loop. Keep the approved defaults, off/enable-idle guarantees, consent/foreground
fences and numeric CPU/memory budgets. The preserved100Hz core benchmark passes
those numeric bounds; its test environment and limits remain explicit.

Not required: hardware for unrequested protocols; a separate Govee manufacturer,
calibration, thermal, cadence or coexistence certification program; an additional
cross-target resource certification program; battery endurance or physical-scanout
certification. These are not outstanding implementation tasks or procurement requests.
Unverified capabilities remain unverified, not passed. No blanket compatibility,
calibration, thermal-safety, battery-life or all-device performance claim is made.
Runtime safety guards and diagnostic labels are unchanged. No feature work or new
qualification activity is part of this records-only correction.

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

The original aggregate result `evidence-cycle-4f094abd2512` remains FAIL as
historical evidence. Its rubric—not its raw results—was wrong: it added unrequested
completion conditions. The old `closure-43be3827a678` NOT_READY record is retained
as history, not the current owner-scoped decision.

Owner correction2026-10-19 appends OUT_OF_SCOPE/resolved dispositions to17
qualification-only observations across the implementation ledger. The old aggregate
has an appended INVALID_TEST disposition identifying the unauthorized acceptance
rubric. Actual failed CPU/SQLite tests, reviewer findings, dissent, package hashes,
original manifest and raw observations are unchanged. No unsupported capability
has been marked passed and no runtime code or safety guard has been changed.

Current owner-scoped validation uses `NIA_ENVIRONMENT_ACCEPTANCE_SCOPE.json` and
an append-only evidence overlay. It checks the38 original artifact hashes, the
requested build/test/review/install/live/off-state results, and the approved
numeric CPU/memory budget directly against raw41-test GTHost XML. The broader
certification program is not required. The corrected closure record below is the
current status; historical design-checklist wording must not recreate extra gates.

Corrected current evidence: `evidence-cycle-52c82899bf4f`, both checks PASS and
both artifact-integrity checks true. Corrected convergence:
`convergence-70d6eb5c60ee`. Latest closure: `closure-6de9609c7222` =
`READY_WITH_RESIDUAL_RISK`, no blocking reasons, three existing LOW disclosed
implementation/measurement limits. No accepted HIGH/critical risk. This is readiness
for the owner's requested scope, not broader capability certification or release approval.

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
