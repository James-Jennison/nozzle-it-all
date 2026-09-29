# Nozzle Test Grid: architecture and trust boundaries

The Test Grid is an invite-only, local-first hardware-acceptance system. Trusted printer owners run standard Nozzle It
All test suites on their own printers, in the app's **Test Mode**, and send back a redacted, content-hashed evidence
bundle. Maintainers verify the bundles and build a compatibility matrix from them. It is a testing system, not remote
printing: nobody but the person standing at the printer can make it do anything.

Related documents:

- [MANIFEST_SCHEMA.md](MANIFEST_SCHEMA.md): suite format, step vocabulary, safety levels
- [EVIDENCE_FORMAT.md](EVIDENCE_FORMAT.md): bundle layout, hashing, redaction rules
- [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md): how grades are computed, superseding, review
- [TESTER_GUIDE.md](TESTER_GUIDE.md) and [MAINTAINER_GUIDE.md](MAINTAINER_GUIDE.md)
- [ACCEPTANCE_MODEL.md](ACCEPTANCE_MODEL.md): the standard printed part
- [UNVERIFIED.md](UNVERIFIED.md): what has not been physically verified
- [manual/](manual/): generated step-by-step instructions for the two reference printers

## Components

| Component | Where | What it does |
|---|---|---|
| Test manifest | `test-grid/.../Manifest.kt`, suites in `test-grid/src/main/resources/testgrid/suites/` | Versioned suite schema (`nozzle.test-suite` 1.0), parser, validator, closed step vocabulary |
| Runner | `test-grid/.../Runner.kt`, `Records.kt` | Deterministic state machine; journal written before every mutating request |
| Target interface | `test-grid/.../Target.kt` | The only way the runner touches a printer; firmware-family classification |
| Evidence | `test-grid/.../Evidence.kt`, `Redaction.kt` | Bundle builder, integrity manifest, verifying reader, redaction boundary |
| Report | `test-grid/.../Report.kt` | Compatibility matrix, acceptance ledger, append-only evidence store |
| Acceptance model | `test-grid/.../AcceptanceModel.kt`, `resources/testgrid/models/` | Reproducible STL generator and hash-verified bundled models |
| Simulation | `test-grid/.../Simulated.kt`, `Scripted.kt` | Simulated printer and slicer; scripted operator for simulated runs only |
| CLI | `test-grid/.../cli/Main.kt` (`./gradlew -q :test-grid:cli --args=...`) | Suites, validation, instructions, simulated runs, verify, preview, store, report |
| Test Mode (Android) | `app/.../testgrid/` | UI, Android target over the app's existing printer paths, real-engine slicer; hidden until turned on per device (`TestModeAccess`: 7 taps on the version in About & credits) |

`:test-grid` is plain JVM Kotlin with no Android, network client or UI dependency (a Gradle check enforces it). It
depends on `:domain` so printer rules have one source: firmware identity and the COSMOS profile-generation check
(`FirmwareIdentity.kt`), discovery classification (`PrinterDiscovery.classifyMoonraker`), the Elegoo stock-command guard
and profile/connection rules (`ElegooProfiles`), and capabilities (`capabilitiesFor`).

## Run lifecycle

```
select target ──► TargetCheck.inspect (read-only: identity, status, capabilities, classification)
               └► operator confirms "this is the printer in front of me"
select suite  ──► TargetCheck.mismatches (Nozzle version, firmware family, printer kind, capabilities)
               └► any mismatch: the suite cannot start
review plan   ──► every step's action shown; operator picks the highest safety level (0-4)
run           ──► per test: NOT_STARTED → PRECONDITIONS → RUNNING ⇄ AWAITING_CONFIRMATION / AWAITING_OBSERVATION
                  → PASSED | FAILED | SKIPPED | BLOCKED | INTERRUPTED | OUTCOME_UNKNOWN, then that test's cleanup
review        ──► redacted preview of exactly the files that will be exported
export        ──► ZIP with integrity.json; the local run is removed only when the operator says so
```

Rules the runner enforces whatever a suite says:

1. A consequential step (upload, delete, heat, move, home, start, pause, resume, cancel) runs only after the operator
   approves that one step, with the exact action text and the target printer (name, model, firmware family, adapter,
   address) on screen. Approvals are never stored: after a restart every pending approval is asked again.
2. Before a control is sent, the printer is re-read; if its state no longer allows the action, nothing is sent.
3. A mutating step is written to the journal as `DISPATCHING` before its request goes out. If the app stops mid-request,
   the reopened run finds it and records `OUTCOME_UNKNOWN`. It is never sent again.
4. After any unknown outcome every action is refused until the operator records what they found and a fresh status
   reading, taken after the unknown outcome, succeeds. The step stays `OUTCOME_UNKNOWN` (graded UNVERIFIED); the test's
   cleanup is then offered, each cleanup step with its own approval.
5. A failing dependency blocks a dependent test. A passing dependency never passes one. Categories are graded only
   from their own tests.
6. Retry exists only for failed read-only steps in tests that never change the printer.

Android Test Mode drives printers only through paths the app already uses and that already carry these properties:

| Step | Android path |
|---|---|
| identity | `Moonraker.firmwareIdentity()` and `Moonraker.discoveryDetails()` (the same reads as the network scan) |
| status, cameras, snapshot | the printer's own `PrinterService` |
| upload, delete | `LiveFileChanges` (idle only, unique name, SHA-256 verified, durable receipt, "outcome requires inspection; no retry sent") |
| heaters | `HeaterControls.prepare` (live limits, active tool, idle only, standard command), then `PrinterService.command` |
| home, jog, start, pause, resume, cancel | the same `PrinterCommand`s the dashboard and JogPanel send |
| slicing | `SlicingCoordinator` (the real engine, with its COSMOS live-firmware gate) |

In Test Mode v1, transfer and control steps run over Moonraker only (PAXX U1, stock U1, COSMOS, other Klipper). On
Bambu, PrusaLink, OctoPrint and Elegoo LAN connections those steps are BLOCKED with the reason, not simulated.

The PAXX U1 path is Android's direct Moonraker LAN client. It involves no Snapmaker cloud, account, Flutter component
or the optional stock-U1 helper.

## Threat and trust boundaries

```
 ┌──────────────── tester's phone (trusted by the tester) ────────────────┐
 │ saved printers, credentials (EncryptedSharedPreferences), addresses    │
 │ Test Mode run journal + attachments (app-private filesDir)             │
 │                        │ redaction boundary (Redactor + leak gate)     │
 │                        ▼                                               │
 │ evidence bundle (no credentials, addresses, hostnames, paths, EXIF)    │
 └────────────────────────┬───────────────────────────────────────────────┘
                          │ tester chooses where the file goes (Android document picker)
                          ▼
 ┌──────────────── maintainers (do not trust bundle contents) ────────────┐
 │ BundleReader verifies integrity and completeness; human review decides │
 │ acceptance (ledger); report builds from verified bundles only          │
 └────────────────────────────────────────────────────────────────────────┘
```

| Boundary | Trusted | Not trusted | Controls |
|---|---|---|---|
| Suite file → app | bundled suites (reviewed in the repository) | imported suite files | Closed step vocabulary; validator refuses any consequential step without confirmation, temperatures above 120 °C nozzle / 70 °C bed, moves above 10 mm, steps above the test's level, mutating tests that allow retry; unknown step kinds block their test |
| App → printer | the operator's per-step approval | the suite's intent | Approval per step, re-read before send, send once, journal before send, unknown-outcome lock, idle gates in the existing paths |
| Printer → app | nothing blindly | printer responses | Live firmware classification; COSMOS generation check; unknown outcomes never assumed successful |
| Phone → bundle | redaction output | anything typed or logged | Sensitive keys dropped, pattern rules, known-private literals masked, final leak scan refuses export, photo metadata stripped, camera images never stored |
| Bundle → maintainers | integrity-verified content | who produced it, and whether it is true | SHA-256 per file, bundle and content digests; bundles are **unsigned**; human review and the acceptance ledger |

What the design does **not** protect against:

- A tester fabricating a bundle. Content hashes prove a bundle wasn't altered after export, not that it came from a
  real printer or a particular person. Simulated evidence is labelled, but a determined person could edit a bundle and
  recompute its hashes. Acceptance is a maintainer's judgement (photos, measurements, consistency with other runs),
  recorded in the ledger. Cryptographic signing is a reserved, versioned extension point (see EVIDENCE_FORMAT.md) and
  must not be claimed until key management and verification are designed.
- Redaction of free text a tester types: rules catch credentials, addresses, hostnames, paths and emails, and the
  preview shows everything, but a tester can still write identifying prose. The review screen is the last check.
- A malicious printer. Firmware identity is what the printer reports.

## Future coordinator

`TestGridCoordinator` (in `Catalog.kt`) is the seam for a hosted coordinator: `availableSuites()` and `submit(bundle)`.
The only implementation is `LocalDirectoryCoordinator`: a folder of suites in, an append-only `EvidenceStore` out.
Core testing never depends on an account or service. A hosted coordinator must re-verify every bundle itself
(`BundleReader`), must not receive credentials or addresses (bundles don't contain them), and should not be built until
the local workflow and evidence format have been validated on real printers.
