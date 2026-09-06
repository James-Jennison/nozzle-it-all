# Saved printer profiles — 2026-09-06

The app-first scope defers the evidence-worker and shared-broker infrastructure
changes. This update adds local saved printer addresses to the Connect screen.
Connecting remembers an address; selecting a saved address switches the active
printer. Forget removes the local entry and disconnects it if currently selected.
There is no subscription or artificial profile count limit. One printer is
monitored at a time. Friendly names and simultaneous fleet monitoring are not
included in this update.

The previous single address migrates into the saved list. Addresses are normalized
and deduplicated. A connection switch clears status/catalog/camera state, advances
the command generation and disposes the previous video composition. Pending
confirmations cannot be applied to a different printer. Switching and forgetting
are unavailable during a command request.

## Verified

- 21 JVM tests pass, including profile migration, stale-command rejection after a
  profile switch, and active-profile removal without background reconnection.
- Lint and both debug packages pass. Existing advisory lint warnings remain.
- All five instrumented tests pass on the authorized Motorola Razr 2023. These
  include exact-address profile callbacks, command confirmation, offline controls,
  and connection to the real printer at 192.168.1.x.
- Actual camera video: 1920×1080 decoded, approximately 339×191 CSS pixels rendered,
  nonzero pixel brightness and advancing frames. This sample is not a sustained
  frame-rate guarantee.
- Saved address remained visible after the test activity closed and the app was
  relaunched. The Connect screen was visually inspected on the Razr.
- Installed APK SHA-256 matches the built candidate:
  `2e352097925e7f85b68e9af4a5143929f7efa2ddc50c2f2d8103a2fe2d328cfc`.
- Source proof: `c765015359a19d533b6a6c0496db9811625fca4606bd5392fe30d584cb2a5e35`.
- Registered Android evidence cycle `evidence-cycle-c065ab3c33fe` passes, without
  invoking the deferred AI server check or altering reviewer runtime configuration.

Only one physical printer was used. Multiple-printer isolation is covered by fake
transport and UI callback tests, not two-printer physical acceptance. No motion,
heating, macro, or print-control commands were sent to the real printer.

The broader roadmap—history, notifications, configurable dashboards and experimental
AI alerts—remains future work. This document accepts only the saved-profile update.

## Review closure

Implementation commit: `ed5d7e3`. Claude, Gemini and DeepSeek completed independent
review and remediation review; DeepSeek also completed targeted closure of initial
address normalization. Atomic preference persistence, duplicate connection handling,
malformed preference types and initial selected-address normalization were corrected.
The invalid-address warning suggestion was rejected for this scope and remains
recorded in the ledger. No reviewer runtime configuration changed.

Convergence `convergence-c60a0eb85e93`; closure `closure-3d08fd17b563`: **READY**, no
unresolved findings or accepted evidence risks. This closure applies to saved
profiles, not the proposed visual redesign or the deferred AI integration.
