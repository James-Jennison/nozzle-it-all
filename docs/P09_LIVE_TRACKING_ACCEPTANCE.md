# P09 live file progress tracking

Files > Follow active print opens a separate read-only preview of the selected
printer's active or paused G-code file. The imported-file workspace is preserved.
The layer and highlighted command follow virtual_sdcard.file_position, labelled
as approximate buffered file progress, not the physical nozzle position. Custom
macros, transforms, queued motion and parser sampling limit the approximation.
Manual local preview navigation remains available separately.

Each retained extrusion command carries its ending byte offset. Offsets include
UTF-8 comment bytes, CRLF and an unterminated last line; expanded arc segments share
the command's offset. Binary search selects the last retained command at or before
the reported offset. A position before the first supported extrusion has no marker.

The transport only issues GETs. It checks readiness, active/paused state, filename,
bounded integral file position/size and metadata filename/size/modification time.
The identity is checked around downloading, after parsing and on subsequent polls.
It is a metadata match, not an atomic snapshot or server content hash: same-size
replacements preserving modification time cannot be detected. This is disclosed.

Downloads are bounded to 256 MiB and isolated temporary files are removed in
finally. Downloading/parsing happen off the UI thread with cancellation checks.
A failed download/parse does not repeatedly fetch the same file automatically;
Retry preview explicitly permits another attempt, while lightweight status reads
continue. Background/disconnect cancels calls and hides progress. A separate
12-second expiry hides stale markers even during a stalled read. Existing parser
and canvas sampling and geometry limits remain enforced.

Protocol references: [Moonraker printer objects](https://moonraker.readthedocs.io/en/latest/printer_objects/)
and [file metadata](https://moonraker.readthedocs.io/en/latest/external_api/file_manager/).

Validation passed: 142 JVM tests, lint, debug/instrumentation APK packaging and
source proof (evidence-fa81e6b742d7). Seven phone regressions passed. The actual
GET-only Elegoo hardware test passed in99.244seconds, including first download and
parse, two advancing positions497609→502622, disconnect marker clearing and
temporary-file removal. Normal MainActivity > Elegoo > Files > Follow active print
also rendered the active file and advanced byte927594→972947. Layer3 of155 at
Z0.6mm and the sampled geometry were visually inspected in normal-app-live.png.
Large initial downloads/parses take noticeable time; subsequent polls reuse geometry.

Installed APK SHA256:
1b1b0ac5d7865908953ad3ea7f30e4b4e27b7042a8c2f9681f486f8b02683593.
Source manifest:
f7651893ec24d8f64042c9b53bcf6f8a698608d3e6a4162b196494a3c40b31ce.
Grok, Gemini and owner-authorized temporary Claude completed independent review
with no findings (review-cycle-bb6a6710e3d2). Evidence and raw results are under
artifacts/p09-live. Full M2 remains open for remaining controls and physical checks.

Closure: closure-af84b75b0621 READY; convergence-80ec276f2777.
