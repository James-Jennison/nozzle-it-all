# Initial review dispositions

All initial findings remain in the machine-global ledger for mobile-klipper-mvp-20260906. The owner-requested live video candidate supersedes it as mobile-klipper-live-mvp-20260906.

## claude-1 — CONFIRMED

Forced light system-bar icons using SystemBarStyle.dark to match permanently dark app theme. Device check is included in final acceptance.

## claude-2 — CONFIRMED

Extracted injectable PrinterService/clock/dispatcher from ViewModel. Added five deterministic tests for late disconnect/reconnect results, stale generation/time, fresh preflight state change, and foreground loop lifecycle.

## deepseek-1 — CONFIRMED

Narrowed to camera host-boundary hardening; the app is a local frontend and server-configured cameras can legitimately use another port. Camera URLs now require same host and no credentials. Unit test covers rejected host change and allowed same-host port.

## deepseek-2 — CONFIRMED

Global Android cleartext opt-in supports user-configured LAN endpoints. Android Network Security Configuration domain elements do not support CIDR rules as proposed. Implemented application-level rejection of public HTTP destinations; local IPv4, localhost and .local accepted; other destinations require HTTPS. Added unit test. Official source: https://developer.android.com/privacy-and-security/security-config

## deepseek-3 — CONFIRMED

Decorative navigation glyph semantics cleared; visible navigation label remains accessible.

## deepseek-4 — CONFIRMED

Connect navigation and action use distinct test tags. Read-only integration passed on Razr 2023 after correction.

## Final live camera review

DeepSeek numeric-host bypass: CONFIRMED and corrected; private IPv4 requires exactly four numeric labels.

DeepSeek macro restriction during printing: REJECTED as a blanket rule. Generic printer-defined macros may intentionally run during printing. Fresh Klipper readiness and mandatory per-action confirmation remain required; no owner requirement bans these macros. Claude independently concurred with this disposition.

DeepSeek HTTPS camera downgrade: CONFIRMED and corrected by rejecting HTTP camera URLs for an HTTPS printer, with regression coverage.

DeepSeek cancellation handling: CONFIRMED and corrected by rethrowing CancellationException and checking coroutine activity immediately before dispatch. A regression test clears the ViewModel during preflight and verifies zero command dispatches and no disposed-state update.
