# Native Android visual update — 2026-09-06

Implemented the approved original charcoal, slate and teal direction. The Dashboard
puts live video above print progress and temperature tiles. Navigation is Dashboard,
Control, Files and Printers, with original outlined symbols. The full-screen camera
view hides app navigation and returns with Close or Android Back. Command confirmations
remain mandatory, with persistent visible feedback for rejected or completed requests.

Layout uses explicit card colors, compact headers and wrapping action rows. Temperature
tiles stack above 130% font scale. Tab changes and printer switches reset the list to
the top. Saved profiles, macro and file controls retain their existing behavior.

## Objective acceptance

- 23 JVM tests pass, including stale confirmation rejection, preflight cancellation,
  and evidence that connect/disconnect/forget cannot change the target during a busy
  confirmed command. Fake transports alone receive command calls in these tests.
  The actual HTTP client also passes snapshot/cleanup/snapshot against MockWebServer;
  cleanup does not permanently shut down the client. Razr Home/resume restored connected
  status and video without manual reconnect.
- Android lint, debug app and instrumentation packaging pass. Existing advisory
  deprecations for the pinned Compose test rules remain; dependencies are unchanged.
- Seven instrumented tests pass on the authorized Motorola Razr 2023. Coverage includes
  confirmation dismissal, offline controls, saved profiles, atomic preferences,
  reachable controls at 320 dp / 200% text, visible rejection feedback on multiple tabs,
  and actual printer status, macro/file listing and video (read-only).
- Real camera decoded 1920×1080, rendered approximately 355×200 CSS pixels, with advancing
  frames and nonzero pixel brightness. Both full-screen transitions resumed playback.
  Inspected screen counters varied between 22 and 30 fps; this is a short observation,
  not a sustained frame-rate guarantee.
- Dashboard and dedicated camera screens visually inspected on the Razr. Android Back
  returned from the dedicated camera view. No heating, movement, macros, print start,
  pause, resume or cancel commands were sent to the physical printer.
- Installed APK SHA-256 matches the candidate:
  `943d0ca1c6eafeba3750a663a8034f24ed32f1be7d0c86323b949293f0d76f91`.
- Source manifest SHA-256:
  `984f9b60159d2fa96b9a6c537c941edc7f162e5922628651205bf0cb4e8f5d58`.
- Registered final evidence cycle `evidence-cycle-87ca8deda6c7`, result
  `evidence-de910125b36d`: PASS, artifact integrity verified.

Evidence files are under `artifacts/ui-review/`; rendered screenshots are
`artifacts/razr-ui-final-dashboard.png` and `artifacts/razr-ui-final-camera.png`.

## Scope and limitations

One foreground printer and the first configured camera are supported. The dedicated
camera view preserves the 16:9 image without cropping; entering/leaving reconnects the
player. Existing WebRTC uses only exact-endpoint POST signaling from the app-owned
HTML. WebView disposal combines best-effort JavaScript cleanup, pagehide and native
`destroy()`, following [Android's destruction contract](https://developer.android.com/reference/android/webkit/WebView.html#destroy()).

No ETA, friendly printer names, console, history, AI alerts or simultaneous fleet
monitoring are claimed. The generated concept remains an illustration with sample
data. AI and machine-global infrastructure work remain deferred.

## Review closure

Claude, Gemini and DeepSeek completed independent initial and remediation review.
Command feedback visibility and icon cutout issues were fixed. DeepSeek's targeted
closure explicitly resolves the six remaining questions against current source and
objective tests. All original findings and separate Codex dispositions are retained.
One invalid-phase cycle and one null-response cycle were superseded; neither counted
as substantive approval. Reviewer runtime and infrastructure were not changed.

Convergence `convergence-eca84d274e89`; closure `closure-063fbd6b1615`: **READY**,
no unresolved findings or accepted evidence risk. This accepts the native visual
update only.
