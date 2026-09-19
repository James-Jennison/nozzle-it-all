# M2 manual fan controls — local preparation

Not installed. Device UI tests and supervised physical fan acceptance remain
pending. Full P07/M2 is incomplete.

Changes: Control → Fan controls loads a bounded list of standard fan and
fan_generic objects only. No fan is preselected. The panel displays exact names,
not inferred toolhead assignments, and excludes automatic heater/controller/
temperature fans. Requested percentages use exact decimal bounds0..100 and
review a named SET_FAN_SPEED or standard M106 command before separate confirmation.
Reported output is labelled as output percentage, not measured RPM.

Confirmations are single-use and expire after5seconds. Input/selection changes
clear review; connection/tool/print-state changes and backgrounding cancel
in-flight reads and clear choices/results. Epoch checks prevent old read results
from replacing newer state. PrinterModel repeats fresh ready/idle, exact fan,
active tool, configuration, reading and macro-override checks and reconstructs
the canonical command before its existing serialized nonretrying dispatch.
Absence of a macro override does not establish unchanged vendor-native behavior.

Owner hardware: Snapmaker U1, extended release v1.5.2-paxx12-21. Release vars.mk
base1.5.2.13_20260722102206 matches printer API. See M2_FAN_CONTROL_DISCOVERY.md
for exact source references and limits. No automatic tool-to-fan mapping is used.
Physical identity and response still need an owner-supervised check after install.

Protected evidence-cycle-0f759ad15711: PASS.98 JVM tests, zero failures/errors/skips;
lint, debug and instrumentation packaging, source proof passed. Three new fan UI
fixtures are compiled, not run. Tests cover injection/overflow, catalog exclusion,
capability/state changes, GET-only discovery, canonical mismatch and cancellation.

APK SHA256:27e6bf31cae58458e4de32411689928e541f054b6803bcbfb6eac98ad57c7bcc
Source manifest:eaf4feb090d6413f1d58964926e6155cd86eface63fc8f23df2f7671e63d7657
Artifact:artifacts/m2-fans/klipper-companion-fans-debug.apk
Review:review-cycle-472e6371fcb3, all three reviewers COMPLETE.
Claude and Gemini found no defects. DeepSeek LOW reader-reset concern was
assessed NOT APPLICABLE to the production Moonraker reader: close cancels calls
and evicts connections without shutting down the client. A supplemental targeted
MockWebServer test proved GET fan discovery works before and after close,1pass.
Finding finding-accc37f58da3 and disposition finding-disposition-b00c7e67e8a6
retain the reviewer evidence and Codex assessment independently.
Local-build closure-fffafdbb8563: READY. Device/physical acceptance remains pending.
Installed Razr2023 remains macro-bounds build19860c10...; no live fan command sent.

Review notes: Claude's optional truncation preference was not adopted because
silently shortening a numeric value can turn invalid input into a different valid
request. Current oversized input is rejected. DeepSeek's optional extra effect
keys are not a current stale-dispatch defect: MainActivity keys panel lifetime by
address/generation, ready=false snapshots change effective state, and model
preflight independently checks fresh readiness. UI fixture execution remains
pending approval to install. No production source changed after reviewed build;
the reader-reuse regression changed JVM test source only.

## Owner-approved installation and local UI checks

Installation on the Razr2023 succeeded. Remote APK SHA256 matches the reviewed
27e6bf31... artifact. All three fan UI fixture cases passed in6.881seconds:
explicit selection/separate single-use confirmation, expiration and active-tool
change invalidation, and rejection of oversized input without truncation.
Fixtures use an in-memory reader and captured callback, not printer dispatch.

The normal MainActivity cold launch succeeded and foreground verification showed
the offline dashboard. No connection or printer command was initiated during
these checks. Evidence:artifacts/m2-fans/installed-identity.json,
device-ui-first.txt, device-acceptance.json. Installed fan UI is verified; physical
fan response and full M2 remain pending. No production source changed this turn.

## Read-only preparation for supervised physical test

Fresh ready/standby readback: active extruder1, bed target0, e1_fan speed0. Its
configuration reports max_power1, off_below0, kick_start_time0.1, shutdown_speed0,
and pin e1:PB3. No SET_FAN_SPEED macro override was found. This does not by itself
establish the physical fan mapping. The installed app loaded the actual manual
fan list and generated exact review SET_FAN_SPEED FAN=e1_fan SPEED=0.25; Confirm
was not tapped. Review must be refreshed after explicit approval because it
expires. Proposed sequence is recorded in proposed-physical-test.json.

During navigation the focus guard briefly stopped input; a fresh check confirmed
the correct app before resuming read-only preparation. No fan/heater/motion
command was sent. The read-only UI helper now also excludes Confirm fan command.

## Mounted Toolhead 1 physical acceptance — 2026-09-08

Owner confirmed Toolhead 1 mounted and remained beside the U1. Current active tool was extruder (T0), with standard fan on e0:PB3. The installed reviewed r2 app issued M106 S63.75 and M106 S0 through its separate review/confirmation flow. Readback verified 25% output with positive tachometer RPM, then 0% and finally 0 RPM. Owner confirmed the mounted toolhead fan started and stopped. The app retained “Printer acknowledged Set fan to 0%.” This accepts the standard fan on the mounted Toolhead 1 only; other tools/fans and full M2 remain unaccepted.

The five-second observation plus UI off-review time produced approximately 22.3 seconds between first positive and first zero output samples. This was not a five-second total cutoff test. Two earlier preparation attempts failed before any confirmation because the dialog initially fitted on screen and had no scrollable node; their evidence is preserved. The helper now handles that layout and rejects unsuccessful UI dumps. No production app source changed.

Evidence: artifacts/m2-fans/physical-toolhead1-final-live.jsonl, toolhead1-result.json, toolhead1-post-test-ui.txt. Final state was standby, heater targets0, standard fan0/RPM0, and homed_axes empty; do not reuse the preceding xyz homing state for motion tests.

## Live acceptance — fan and cavity_fan, plus purifier-managed fan finding (2026-09-19)

Owner-driven physical session against the Snapmaker U1, same session as the M2
heater controls pass above. Owner sent `fan` (standard part-cooling) and
`fan_generic cavity_fan` directly from the app's Fan controls panel; both
confirmed responsive. Cross-checked against Moonraker: `fan` read `speed: 1.0`
(part-cooling running at the requested 100%), `cavity_fan` read `speed: 0.0`
after being tested and turned back off.

Investigating whether the remaining `fan_generic` entries (`circulation_fan`,
`e1_fan`, `e2_fan`, `e3_fan`, `exhaust_fan`) are meaningful manual controls
found that two of them are not simple manual fans at all. Reading
`purifier.py` on the printer directly: it wraps both `exhaust_fan` and
`circulation_fan` (its `exhaust_fan_name`/`inner_fan_name`) in a
`PurifierFanRouter` that replaces the fan object's own speed-setting method,
redirecting any `SET_FAN_SPEED` call into the Purifier module's own automation
(delay-off timers, work-time monitoring, periodic status checks, presence
gating) instead of driving the PWM pin directly. A requested speed from the
Fan controls panel therefore has no guarantee of holding for these two — the
panel can't actually promise what it appears to promise.

Added a persisted per-fan Hide/Unhide feature (mirroring the one already built
for macros) and hid `exhaust_fan` and `circulation_fan` on this printer as a
result. `cavity_fan` is a real standalone fan (though also auto-linked as
`[fan]`'s `aux_cool_fan`) and stays visible. `e1_fan`/`e2_fan`/`e3_fan` are
genuine per-extruder manual fans (paired with already-excluded automatic
`heater_fan` siblings) and stay visible, but were not physically exercised
this session — only `fan` and `cavity_fan` are confirmed today. Full M2 fan
controls acceptance (all manual fans, all tools) remains open.
