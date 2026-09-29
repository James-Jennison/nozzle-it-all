# Physically unverified configurations

Physical Test Grid evidence exists only for the Snapmaker U1 on PAXX extended firmware and the Elegoo Centauri Carbon
with CANVAS on OpenCentauri COSMOS 26.09, both in Android Test Mode on the owner's printers: see [COMPATIBILITY_REPORT.md](COMPATIBILITY_REPORT.md) for its grades and
[evidence/README.md](evidence/README.md) for the bundles. Everything else below is UNVERIFIED in the Test Grid matrix, in
every category. Results recorded elsewhere (the owner's U1 cube print, the M2 heater acceptance on the U1, the COSMOS
live firmware reads) are prior history, not Test Grid evidence, and are not imported into it.

| Configuration | Suite | Coverage | Who can supply evidence |
|---|---|---|---|
| Snapmaker U1 on PAXX, multi-tool: file transfer and controls | `paxx-u1` has no multi-material tests in these categories | reference | n/a (slicing, monitoring and printing have evidence) |
| Centauri Carbon on COSMOS older than 26.07.0 | none (legacy profile not bundled; the COSMOS suite fails its profile match by design) | n/a | n/a |
| Snapmaker U1 on stock firmware | `snapmaker-u1-stock` | fixture | external testers |
| Elegoo Centauri Carbon on Elegoo stock firmware (SDCP) | `elegoo-centauri-carbon-stock` | fixture | external testers; send-and-start transfer, printing, pause/resume/cancel, CANVAS two-colour (no heaters, homing or moves through Nozzle) |
| Elegoo Centauri Carbon on OpenCentauri-patched stock firmware | `opencentauri-patched` | fixture | external testers; same steps as stock |
| Elegoo Centauri Carbon 2 (MQTT) | none yet | n/a | n/a |
| Bambu Lab (LAN mode) | `bambu-lan` | fixture | external testers; send-and-start with a `.gcode.3mf`, printing, pause/resume/cancel, camera; no AMS multi-colour yet (not enabled in Nozzle) |
| Prusa via PrusaLink | `prusalink` | fixture | external testers; send-and-start, printing, pause/resume/cancel; multi-material when the profile has several tools (XL 5T). No MMU3 slicing profile exists in Nozzle yet |
| Generic Klipper/Moonraker (each model its own row) | `generic-klipper` | fixture | external testers |
| OctoPrint | `octoprint` | fixture | external testers; send-and-start, printing, pause/resume/cancel; multi-material when the profile has several tools |
| Desktop adapters (`paxx-lan`, `moonraker`, …) | none: Test Mode is Android-only in v1; a result on Android is not evidence for Desktop | n/a | n/a |

The owner does not have a stock Centauri Carbon; stock and OpenCentauri-patched coverage must come from external
testers. A COSMOS result is never evidence for either.

Also unverified on a device (software, not hardware): Test Mode's instrumented UI test (`TestModeDeviceTest`) has
been compiled but not run.
