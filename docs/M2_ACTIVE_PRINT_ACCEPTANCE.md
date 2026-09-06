# M2 active-print first delivery

2026-09-06. This records the first M2 delivery authorized while the owner's printer
was actively printing. It is **not full M2 acceptance or a public release**.

## Delivered in the debug app

- Macro favorites, groups and filtering, stored separately per printer address.
- Explicit numeric parameter definitions (`NAME=min,max,default`), bounded forms,
  strict identifier/value validation and an exact command review. Preparing a macro
  does not execute it. Confirm remains disabled during printing/paused states, and
  the existing model rechecks freshness, printer generation and live state.
- Read-only G-code downloads, Android document imports and ACTION_SEND confirmation.
  The local Files workspace also works before a printer has been connected.
- Save a local copy through Android's document picker, then explicit write
  confirmation. The source identity is checked again before writing.
- Approximate XY extrusion previews with a layer selector: G0/G1, relative/absolute
  XYZ/E modes, units, G92 origins, numbered lines, balanced comments and XY IJ arcs.
  Non-extruding arc Z-hops update the endpoint. Homing invalidates position until
  re-established; omitted motion is disclosed. This is not a live tool-position or
  machine simulation.

## Resource and printer boundaries

Files are bounded to 256 MiB. Geometry retains at most 120,000 sampled segments and
10,000 extrusion heights; each rendered layer is further sampled for drawing.
Parser/copy work runs off the UI thread and supports cancellation. Cache allocation
allows two live files, reclaims orphaned workspaces before another load and schedules
cleanup off the UI thread. R arcs, non-XY planes, helical extrusion and custom machine
transforms are not claimed; unsupported geometry fails visibly.

**Production FileTransfer has no upload, rename or delete methods.** Those protocol
prototypes live only in the JVM test source set. Their preflight collision checks are
not atomic and do not prove live overwrite safety. No physical printer, macro or
file mutation was sent during this work. AI, Obico and shared infrastructure were not
changed.

A chosen Android document provider is not a transactional filesystem. An interrupted
export can leave partial output; a provider may replace existing content. The app
warns and asks for confirmation before writing, and reports incomplete output after
failure. Cache-copy cleanup and existing-cache-file preservation are separate from
this explicitly limited export behavior.

## Validation

- `artifacts/m2/validation-twelfth.txt`: build, lint, debug APK/instrumentation packaging
  and source proof PASS. 43 JVM tests, zero failures/errors/skips.
- `artifacts/m2/device-final.txt`: **OK (19 tests)** on the Motorola Razr 2023.
- Actual printer read-only connection, camera/WebRTC/fullscreen, metadata/history and
  download/preview of its existing 12,503,499-byte Dragon G-code file passed.
- Tests cover numeric injection rejection, active-print dispatch disablement,
  synthetic ContentProvider import, wrong-extension rejection before opening the
  body, real Android ACTION_SEND confirmation/import/preview, exact exported bytes
  over a longer destination, Android `wt` truncate mode and StrictMode cache cleanup.
- Earlier failures remain preserved: unsupported arcs, a test-provider runtime
  dependency and ActivityScenario's intent-based teardown tracking were corrected.
  A build/source mismatch during editing and a protected-build lock refusal are
  preserved as failures, not rewritten as passes.

APK SHA-256:
`5f989fd41ac8f8963758ce2ce0ecb41c858559b2654d03822d04074593549b05`

Source manifest SHA-256:
`ce76d28657c4f89a2e47c26a03b3ccba02e82114ba365464e8cd6358b49bdf88`

Current evidence cycle: `evidence-cycle-9f8bc6cfce64`.
Review change: `mobile-klipper-m2-active-20260906`.
Final targeted review cycle: `review-cycle-b227b2c18cfc`.
All three final reviewer responses are COMPLETE with no remaining findings.
Closure `closure-91ddf5bca639`: **READY_WITH_RESIDUAL_RISK**, limited to the explicit
LOW-risk nontransactional document-provider export behavior described above.
Installed package identity matches the APK hash (`artifacts/m2/installed-identity.json`).
The actual Dragon layer preview was visually inspected on the Razr; screenshots are
in `artifacts/m2/preview.png`, `preview-layer.png` and `preview-model.png`.
Use `reviewer ledger change mobile-klipper-m2-active-20260906` for immutable findings,
dispositions, evidence and closure provenance.

## Remaining M2 work

P06 is numeric-form/local-organization coverage; actual macro execution was not
accepted on hardware. P08 is local import/download/export plus fixture-only mutation
contracts; live upload/rename/delete and collision-safe completion remain unfinished.
P09 is bounded approximate layer preview, not live tool-position tracking. P07 advanced
physical controls and P10 dashboard/theme customization are not part of this first
delivery. Full M2 remains in progress and its hardware acceptance requires a separately
safe idle-printer window.
