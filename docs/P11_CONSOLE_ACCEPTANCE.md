# P11 — Read-only console

Owner-approved scope: inspect recent console messages, filter errors/search, pause
updates for reading, and copy matching diagnostic text. No command entry or printer
mutation is added. Full P11 command entry remains pending.

Control → Read-only console opens a printer-bound dialog. It requests the latest
200 cached entries every three seconds, only while the dialog is present, the
printer is connected, the app lifecycle is resumed, and updates are not paused.
Pause updates freezes the view and stops refresh/automatic scrolling. Resuming
reads the current server cache; it does not promise to recover evicted messages.

Moonraker returns a FIFO cache with command/response records and Unix timestamps:
[official API contract](https://moonraker.readthedocs.io/en/latest/external_api/server/).
The UI displays newest first without merging or deduplicating repeated messages.
It identifies this as recent cache, not a complete log. Errors-only is explicitly a
text heuristic (responses containing “error” or beginning with !!), not a protocol
severity field. Search is case-insensitive and limited to 128 characters.

Resource bounds: existing 2 MB HTTP response limit and seven-second call timeout;
200 retained entries, 2,048 characters per message, 65,536 characters per copy.
Truncation is disclosed. Messages remain in memory and are not logged to disk.
Copy requires an explicit tap and includes only matching entries. No clipboard
contents are read. The console exposes a read-only reader capability, not a script
or command callback. It uses a separate cancellable client so its close cannot
cancel the main printer monitor. In-flight blocking reads may complete within the
call timeout after cancellation; cancellation prevents publishing their results.

Validation:
- Protected build, lint, package and source proof passed; 72 JVM tests, no failures,
  errors or skips. Console tests cover schema, resource bounds, filtering, copying,
  GET-only requests, proxy base path, redirect rejection and oversized response.
- Razr final suite: OK (8 tests), 20.878s. Covers search/filter/copy, paused polling,
  resume/close cleanup, paused disconnect, address isolation, stopped lifecycle,
  failure messaging, actual Control-screen route during simulated printing, and
  real-printer read-only bounded response. No command was dispatched by the route.
- Fixture dialog screenshot visually inspected; actual console text was not saved
  in evidence. The final integration change leaves ConsolePanel UI unchanged.
- Installed APK matches validated build:
  dd53e81e959eff3aed790d5449a54866bb80017364d7bcb939370b871d67d0c1.

Claude, Gemini and DeepSeek completed independent review with no defects
(review-cycle-846de24ed5d5). Optional list-key optimization was not required for the
bounded stateless rows. Final closure: READY, closure-b7d8a3284ff0.
Independent evidence: evidence-cycle-b2a68fec0424. Evidence: artifacts/p11.
Full P11 command entry and full M3 remain pending.
