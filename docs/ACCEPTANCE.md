# Android MVP acceptance — 2026-09-06

## Scope delivered

Original Kotlin/Compose Android app: single Moonraker printer connection, foreground live status and temperatures, G-code file browsing, macro browsing/execution, start/pause/resume/cancel with explicit confirmation, and continuous receive-only camera-streamer WebRTC video. No membership, ads, analytics or cloud account.

Wireless ADB was paired through an owner-entered code in a terminal with echo disabled. Target model verified as Motorola Razr 2023, Android 16. Other attached devices were not used.

## Objective verification

- 16 JVM tests pass: 11 transport/parser cases and 5 ViewModel lifecycle/state cases.
- Lint passes with no errors. Remaining warnings concern available dependency updates, target API currency, WebView JavaScript (required for the isolated app-owned WebRTC client), and minor style suggestions.
- Three instrumented tests pass on the Razr: explicit confirmation and cancellation of confirmation; disabled offline controls; actual connection, macro/file readback and live camera rendering.
- Live decoded frame dimensions: 1920 × 1080. Rendered video element: approximately 339 × 191 CSS pixels. Sampled image brightness nonzero (68.30); the native device screenshot visibly confirms the printer scene. Later observed frame-rate samples: 26 and 29 fps. These are observed samples, not a guaranteed rate.
- Final candidate APK SHA-256: `e73a3f8f1a696ab4e62d9e113b71c2642556f5eb3cc7c264846bae63f929b0bf`.
- Installed APK SHA-256 matched the local candidate. Embedded source-proof asset matched current application source inputs.
- The printer was already printing. Acceptance sent no physical motion, heating, macro or print-control commands. Receive-only WebRTC signaling creates a camera viewing session; no printer configuration was changed. Write behavior is covered by mock-server and UI callback tests, not a physical print-action test.

## Corrections made during validation

The initial Compose BOM required a newer SDK/AGP than the selected toolchain; compatible Compose 2026.06.00 is used. Initial live integration test selectors were ambiguous; unique tags and lazy-list-aware scrolling corrected them. The first camera implementation used refreshed still snapshots and was replaced after the owner requested continuous video. Initial WebRTC decoded frames but rendered a zero-height video element; explicit native and HTML layout corrected it, and rendered-size assertions now guard against recurrence. Review identified an overly permissive numeric-host parser; exactly four host labels are now required and deceptive-domain tests pass.

## Deliberate MVP limits

Single printer and first camera; camera-streamer WebRTC only for live video. No background notifications, full file upload/editing, parameterized macros, Spoolman, general MJPEG playback, remote service integration, or credential entry. HTTP accepts local IPv4, localhost and .local names; other addresses require HTTPS. This is a trusted-LAN prototype, not a production release or broad camera compatibility claim.

## Durable evidence

- Initial snapshot implementation: `mobile-klipper-mvp-20260906`; review `review-cycle-53709e0bc2ad` completed for Claude, Gemini and DeepSeek. Findings and separate dispositions remain in the global ledger.
- Live implementation: `mobile-klipper-live-mvp-20260906` supersedes the initial candidate.
- Follow-up cycle `review-cycle-22846a40d77f` retains Claude's failed provider attempts and DeepSeek's host-validation finding; latest source received a new closure cycle.
- Final closure cycle: `review-cycle-ed3789f85955` (status must be read live).
- Final evidence cycle: `evidence-cycle-1aef555d6aa3` (status must be read live).
- Autonomous run `autonomous-run-20260906-d222fb17` failed before implementation because installed Codex CLI 0.151.0 could not run its configured model. Work was completed in the interactive task; no supervisor/CLI configuration was changed.

No publication, production release, or unattended continuation is claimed.
