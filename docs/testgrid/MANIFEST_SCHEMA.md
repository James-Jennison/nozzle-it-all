# Test manifest schema (`nozzle.test-suite` 1.0)

A suite is one JSON file. The parser is `Suite.parse` in `test-grid/.../Manifest.kt`; bundled suites live in
`test-grid/src/main/resources/testgrid/suites/` and are listed in `index.json` (Android can't list resource folders).
Validate any suite with:

```bash
./gradlew -q :test-grid:cli --args="validate path/to/suite.json"
```

## Versioning

- `"schema": "nozzle.test-suite"` and `"version": [major, minor]`, the same convention as the project manifest.
- A different major version is refused (`IncompatibleSuiteException`): "This suite needs a newer Nozzle It All".
- A newer minor is accepted. Unknown fields at every level are kept (`unknown`) and survive a save and reload. A step
  whose `kind` this build doesn't know parses, but its test is BLOCKED ("needs a newer Nozzle It All"); it is never
  skipped silently or treated as passed.
- `suiteVersion` is the suite's own dotted version; `requiredNozzleVersion` is the oldest Nozzle build that may run it.
- The suite's SHA-256 digest (over its canonical JSON) is recorded in every bundle, so a result names the exact suite text.

## Top level

| Field | Meaning |
|---|---|
| `id` | Lower-case id, e.g. `paxx-u1` |
| `suiteVersion` | e.g. `1.0.0` |
| `title`, `description` | Shown in Test Mode and the instructions |
| `coverage` | `reference` (hardware the maintainers run) or `fixture` (written ahead of evidence; grades stay UNVERIFIED until a physical bundle is accepted) |
| `requiredNozzleVersion` | e.g. `0.1.0` |
| `target` | See below |
| `requiredCapabilities` | Capability names the printer must declare before the suite can start |
| `tests` | Ordered list of tests |

`target`: `manufacturer`, `model`, `firmwareFamily` (one of `paxx-extended`, `snapmaker-stock`, `cosmos`, `klipper`,
`elegoo-stock`, `opencentauri-patched`, `bambu-lan`, `prusalink`, `octoprint`), `firmwareVariants`, `printerKinds`
(the app's `PrinterKind` names the printer may be saved as), `adapter` and `protocol` (e.g. `android-moonraker`,
`moonraker-http`), `slicingProfile` (bundled pack id), `hardware` (informational), `notEquivalentTo` (families this
suite's results are never evidence for).

The firmware family is confirmed from a live read before a run starts, with the network scan's own rules
(`FirmwareFamilies.classify`). A suite whose family differs from the printer's cannot start.

Capability names: `status`, `firmware_identity`, `camera`, `files`, `upload_job`, `upload_and_start`, `start_print`,
`pause_print`, `resume_print`, `cancel_print`, `temperatures`, `motion`, `material_state`, `multi_material`. They are
derived from the app's `capabilitiesFor(PrinterKind)` plus detected hardware (CANVAS through AFC; the U1's four
toolheads), never from a separate table.

## Tests

| Field | Meaning |
|---|---|
| `id`, `title`, `description` | |
| `category` | `slicing`, `file_transfer`, `monitoring`, `controls`, `physical_print`. A test counts only toward its own category. |
| `scope` | `single_material` or `multi_material` (multicolour, CANVAS, AMS, MMU, tool changing). Graded separately. |
| `safetyLevel` | 0 to 4 (below). Must be at least the highest level of its steps. |
| `requiredCapabilities` | Missing capability: BLOCKED |
| `requiredHardware` | e.g. `["canvas"]`, `["multi_tool"]`. Not detected: SKIPPED, never inferred |
| `preconditions` | `{id, text, check?}`. The operator must confirm each; `check` (`printer_idle`, `printer_connected`) is also verified automatically. Any unmet: BLOCKED |
| `steps` | Ordered steps (below) |
| `expectedObservations` | Plain-language expectations, shown to the operator and exported |
| `requiredEvidence` | `{id, kind: photo|file|measurement, description}`; each must be collected by an `attach` or `observe` step. Missing evidence turns a PASS into PARTIAL |
| `timeout` | `{seconds, onTimeout: fail|blocked|outcome_unknown}` for the whole test |
| `cleanup` | Steps offered after the test ends, however it ended (each consequential one approved separately) |
| `requiresOperatorConfirmation` | Must be true if any step is consequential |
| `mutatesPrinterState` | Must be true if any step (including cleanup) changes the printer |
| `uncertainResultProhibitsRetry` | Must be true for mutating tests. When false (read-only tests), a failed read may be retried by the operator |
| `dependsOn` | Earlier test ids that must PASS or be PARTIAL; otherwise this test is BLOCKED |

## Steps

Every step has `id`, `kind`, optional `title`, `params`, `expect`, `timeoutSeconds`, `onTimeout`, and
`requiresConfirmation` (defaults to true for consequential kinds; `false` on a consequential kind is rejected).

The vocabulary is closed. There is no step that sends arbitrary G-code, runs a macro, or writes configuration.

| Kind | Level | Changes printer | Params / expect |
|---|---|---|---|
| `verify_model` | 0 | no | `model`: checks bundled model files against their published SHA-256 |
| `slice` | 0 | no | `model`, `profile` (bundled pack id, or `@printer`: the profile saved for the tester's printer); optional `profileWith` `{hardware: profile}` used instead when that hardware is detected (a CANVAS Centauri Carbon slices with the COSMOS AFC profile); optional `colourMix` `{a, b, bPercent}`: every part prints as a mix of tools `a` and `b` (two different tools, 1-16; `bPercent` 1-99) through the printer's own mixing system (Full Spectrum on a U1, ColorMix elsewhere); a printer without colour mixing is BLOCKED |
| `scan_gcode` | 0 | no | `checks`: `non_empty`, `no_stock_elegoo_commands`, `requires_macro {macro}`, `within_bed`, `centered {toleranceMm}`, `max_tool_index {max}`, `uses_tools {count}`, `alternates_tools {minLayers, minFraction}` (the model's filament changes between at least `minFraction` of its consecutive layers, over at least `minLayers` layers) |
| `upload_guard` | 1 | no | `fixture`: hands a known-unsafe file to the upload path's preflight; PASS only if it is refused naming the forbidden command |
| `read_identity` | 1 | no | `expect.firmwareFamily` (defaults to the suite's) |
| `profile_match` | 1 | no | `profile` (and optional `profileWith`, as for `slice`); `expect.refused: true` to prove a wrong-firmware profile is refused |
| `read_status` | 1 | no | `expect.ready`, `expect.states`, `expect.temperatures` |
| `check_capabilities` | 1 | no | `required` |
| `list_cameras` / `camera_snapshot` | 1 | no | `expect.min` / `expect.minBytes`; URLs and images are never stored |
| `list_files` | 1 | no | `expect.uploaded` |
| `read_material_slots` | 1 | no | `expect.min` |
| `monitor` | 1 | no | `until`: `printing`, `paused`, `complete`, `idle`, `progress_increases`, `heater_reaches`, `heater_below` (+`heater`, `celsius`, `toleranceC`), `pollSeconds`; `timeoutSeconds` required |
| `send_and_start` | 4 | yes | `fromTest`: sends that test's sliced file and starts printing it in one request, for printers that take files only that way (Bambu LAN, PrusaLink, OctoPrint, Elegoo LAN); a lost reply is confirmed from the printer's state or stays unknown |
| `upload` | 2 | yes | `fromTest`: uploads that test's sliced G-code under a unique `nozzle-testgrid-…` name, verified by SHA-256 |
| `delete_uploaded` | 2 | yes | deletes only a file this run uploaded |
| `delete_leftovers` | 2 | yes | deletes earlier `nozzle-testgrid-*.gcode` files in the G-code root that the printer no longer has loaded and this run no longer needs; the approval names every file, nothing else is deleted, and it is skipped without asking when there are none |
| `set_temperature` | 3 | yes | `heater` (`nozzle`, `bed`), `celsius` (nozzle ≤ 120, bed ≤ 70; 0 = off) |
| `home` | 3 | yes | |
| `jog` | 3 | yes | `axis` (X, Y, Z), `mm` (non-zero, ≤ 10) |
| `start_print` | 4 | yes | `fromTest`: starts the file this run uploaded |
| `pause`, `resume`, `cancel` | 4 | yes | |
| `observe` | 0 | no | `question`, `response` (`yes_no`, `pass_partial_fail`, `number` with `unit`, `choice` with `choices`, `text`), `expect` (`equals`, `min`/`max`, `oneOf`), optional `evidence` |
| `attach` | 0 | no | `evidence`, `prompt` |

Printing temperatures come from the sliced G-code, never from a suite step.

## Safety levels

| Level | Name | Examples | Needs per-step approval |
|---|---|---|---|
| 0 | Software-only validation | model hash, slicing, G-code checks | no |
| 1 | Read-only discovery and telemetry | identity, status, cameras, file list, upload-guard preflight | no |
| 2 | Reversible file operations | upload, delete the uploaded file | yes |
| 3 | Supervised controls | heaters (low temperatures), homing, small moves | yes |
| 4 | Physical printing | start, pause, resume, cancel, a full print | yes |

The operator chooses the highest level allowed for a run (default 0). Tests above it are SKIPPED, never passed.
Category and level are independent: pause/resume/cancel are `controls` tests at level 4.

## Result states

`PASS`, `FAIL`, `PARTIAL` (some required parts passed, others were skipped, blocked or judged partial), `SKIPPED`
(not run: above the chosen level, hardware not detected, operator declined), `BLOCKED` (could not run: dependency,
precondition, capability, unsupported step, printer state), `UNVERIFIED` (no evidence either way: unknown outcome,
interrupted, simulated, or never run).

Test lifecycle states: Not started, Preconditions, Awaiting operator confirmation, Running, Awaiting observation,
Passed, Failed, Skipped, Blocked, Interrupted, Outcome unknown. A PARTIAL result ends in the Failed state (it is not a
full pass); the recorded result says PARTIAL.

## Adding a printer or firmware-specific suite

1. Copy the closest suite. Keep `schema`/`version`; give it a new `id` and `suiteVersion` 1.0.0.
2. Set `target` precisely. A new firmware family needs a classification rule in `FirmwareFamilies.classify` (reusing
   `PrinterDiscovery`) and tests proving it is not confused with its neighbours (see `RunnerTest`).
3. Use `coverage: "fixture"` unless a maintainer owns and will run the hardware.
4. Put anything multi-material in `scope: "multi_material"` tests with `requiredHardware`.
5. Add the file to `index.json`, run `validate`, `instructions <id>`, and a `simulate` run with a matching preset if one
   exists, then `./gradlew :test-grid:test` (every bundled suite is parsed and validated there).
