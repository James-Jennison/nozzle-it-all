# M1 — daily monitor acceptance

Validated 2026-09-06 on the owner’s Motorola Razr 2023. P01–P05 are implemented and installed as a debug build; this is not a public release.

## Delivered

- Named profiles, validated address edits with inline errors, favorites and ordering; migration from saved addresses. Profile name verified after actual force-stop/restart.
- Remembered camera selection, camera-streamer WebRTC/fullscreen and native MJPEG. Missing selected cameras show unavailable and offer explicit replacement. Hidden/background players close their streams. MJPEG decode/render is capped at 15 fps.
- File folders, recursive search, name/date sorting, metadata and safely resolved thumbnails.
- History in 50-record pages with outcomes and page-only recorded duration/material summaries. Failed requests clear paging state. A completely full terminal page may lead to an empty next page; Previous remains available.
- Elapsed duration, optional layer progress and explicitly slicer-based remaining estimates. Missing, mismatched, exceeded and paused estimates remain unknown.

## Objective evidence

- 34 JVM tests: zero failures, errors or skips. Android lint, debug APK/instrumentation packaging and source-to-APK proof pass.
- All 13 Razr instrumentation tests pass (`artifacts/m1/device-final.txt`), including real-printer read-only connection, metadata/history, WebRTC decoded frames/geometry and fullscreen; profile edits; missing camera selection; 150 synthetic history records and long filenames; existing confirmation/offline/accessibility checks.
- MJPEG test uses a labelled loopback fixture alternating red/green JPEGs at roughly 60 fps. Captured rendered pixels must change. Unmounting must close the stream before the fixture exhausts. The green image was test content, not a failed physical camera feed.
- Actual printer checks sent no print, movement, heater or macro commands.
- Named profile survives actual process restart (`artifacts/m1/process-restart.json`).
- Installed APK hash matches local package (`artifacts/m1/installed-identity.json`).
- Screens visually inspected: `artifacts/m1/profiles.png`, `dashboard.png`, `file-details.png`, `history.png`. The first three were captured from the preceding candidate with identical normal-path layouts; history is from the final installed candidate.

APK SHA-256:
`5889ee372677dd6fd634f47aecdc0403835ffd7e9fb9ca9c56619ddbd630aedb`

Source manifest SHA-256:
`4ad00300902724cbaf05dc74bee5361f628e2b89c48219902989c994c756605a`

Build evidence: `artifacts/m1/validation-ninth.txt`, `test-build-final.txt`; current machine-global evidence cycle `evidence-cycle-7d1b01824606` is PASS with verified artifact integrity. Earlier failed test runs remain preserved; a history fixture initially omitted its address and was corrected before the passing final run.

## Review and closure

Change `mobile-klipper-m1-20260906` has immutable initial and remediation findings with separate Codex dispositions. Confirmed fixes cover MJPEG rate limiting, inline profile validation, history failure state and missing saved-camera behavior. A missing-dialog claim was refuted by complete source/device evidence. A history-total claim was retracted after official API documentation and live limit/count comparisons established count is page-query length.

Claude, Gemini and DeepSeek each have persisted COMPLETE responses with no remaining findings for final cycle `review-cycle-a347b4f19f72`. Closure `closure-e3aef13d50d7` is **READY**. Retrieve provenance with `reviewer ledger change mobile-klipper-m1-20260906`.

## Supported scope and limits

One actual printer with a WebRTC camera was available. MJPEG, additional camera identities and large histories are fixture-tested on the real phone; multiple physical printers/cameras remain unverified. Monitoring is foreground-only and requires an unauthenticated reachable Moonraker endpoint. History reports stored measurements, which can be incomplete for ongoing jobs. No all-time total is inferred from a page.

No wearable, AI, Obico installation or shared infrastructure change is included. M2 has not started. The background autonomous worker could not start because its CLI does not support the configured model; M1 was completed interactively, without changing that service.
