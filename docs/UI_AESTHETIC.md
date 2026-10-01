# Klipper Companion visual direction (historical)

> **Historical document, superseded.** This was the 2026-09-06 visual direction for the
> Android app when it was still a Klipper companion (teal accent, Dashboard/Control/Files/Printers).
> The current, binding design brief for the whole product family (Desktop, Android, Web and the
> website) is [docs/family/PRODUCT_FAMILY.md](family/PRODUCT_FAMILY.md): violet identity accent,
> print orange for heat, charcoal and slate surfaces, one token file
> (`design/tokens/nozzle.tokens.json`) and the glossary. Where this file and the brief differ,
> the brief wins. Kept for the record of how the Android UI got here; do not use it for new work.


Owner request: draw inspiration from SimplyPrint, Mobileraker, OctoApp, Obico and
Printer Tools to establish an original aesthetic for our Android app.

## Reference observations

These are design interpretations of publisher screenshots inspected on 2026-09-06,
not a feature-parity claim or an audit of every current app screen. Android Google
Play listings were the primary reference. Some publisher marketing images use a
generic or non-Android handset frame; that hardware chrome is excluded from our
direction. No competitor assets or source are incorporated into our app.

| Reference | Pattern to learn from | Our interpretation |
| --- | --- | --- |
| [OctoApp](https://play.google.com/store/apps/details?id=de.crysxd.octoapp) | Readable metrics, obvious temperature tiles and uncluttered controls | Clear hierarchy and comfortably sized Android controls |
| [Mobileraker](https://play.google.com/store/apps/details?id=com.mobileraker.android) | Related machine controls grouped into sections | Tools grouped by purpose, with detailed controls away from the monitoring overview |
| [SimplyPrint](https://play.google.com/store/apps/details?id=com.simplyprint.appdev) | Repeated printer cards with compact status and progress | A scannable saved-printer list, with honest connected/offline state |
| [Obico](https://play.google.com/store/apps/details?id=com.thespaghettidetective.android) | Prominent camera and adjacent monitoring information | Live video near the top; health information must only appear when a real integration exists |
| [Printer Tools](https://play.google.com/store/apps/details?id=com.fixolab.printertools) | Compact camera, progress and temperature panels; consistent navigation icons | An efficient dashboard with a coherent icon family and fewer oversized generic cards |

## Original direction

A calm workshop instrument: deep charcoal, cool slate cards, restrained mint-teal
accents and precise typography. Preserve our existing teal identity while replacing
the default purple-tinted Material surfaces and placeholder text-symbol icons.
Use real outlined vector icons with consistent stroke weight and text labels.

| Token | Value / intent |
| --- | --- |
| Background | `#10171D` |
| Card surface | `#19242D` |
| Primary accent | `#81E6CF` |
| Main text | `#E6EEF2` |
| Supporting text | `#A8B5BD` |
| Attention | Amber plus a descriptive label |
| Failure / destructive action | Red used sparingly, always with text |
| Shape | Approximately 16 dp card corners; compact pills for status |
| Spacing | 8 dp rhythm, 16–20 dp outer margins |
| Typography | Native Android sans serif, stable-width numerical metrics |

Dashboard order: compact printer identity/switcher and connection state; large
camera; current job with progress and time; two temperature tiles; quick tools.
Pause is available when appropriate. Cancel belongs in a clearly labelled secondary
action with confirmation. Do not substitute decorative indicators for actual
freshness, connection or print status.

Proposed navigation: Dashboard, Control, Files, Printers. Macros live under Control
and can be reached through a Dashboard shortcut. History fits into Files/jobs when
implemented. The visual concept may show proposed surfaces that are not shipped.

Design for the Razr's tall main display with thumb-reachable navigation, readable
long filenames, scalable text, scrollable content and at least 48 dp interactive
targets. Test compact widths and large font settings during implementation. Avoid
large empty branding headers, decorative gradients, glass effects and constant
animation. Keep camera surfaces stable during live metric updates.

## Implementation boundary

The approved direction is now implemented in the native Android UI: Dashboard,
Control, Files and Printers navigation; compact identity and connection status;
camera-first monitoring with a dedicated full-screen view; original outlined icons;
explicit charcoal/slate surfaces; teal progress; and warm/cool temperature icons.
Buttons wrap and temperature tiles stack when text is enlarged. Command outcomes
and guard rejections remain visible above navigation even while scrolling.

The app shows actual addresses and telemetry. The mockup's sample friendly names,
ETA and console are not implemented. The first configured camera is used; live
video supports camera-streamer WebRTC, with refreshed snapshots for a camera that
has no stream. Full-screen transitions recreate the player and reconnect video.
AI and infrastructure remain deferred. Command confirmation, freshness checks and
connection isolation remain enforced. See UI_ACCEPTANCE.md for candidate evidence.

The concept image is generated with the built-in image-generation tool. Its exact
prompt is saved alongside the selected image; no external model API key is used.

[Visual concept](visual-direction.png) · [Exact generation prompt](visual-direction-prompt.txt)
