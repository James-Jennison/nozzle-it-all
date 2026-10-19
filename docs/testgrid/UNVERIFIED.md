# Physically unverified configurations

Physical Test Grid evidence exists only for the Snapmaker U1 on PAXX extended firmware and the Elegoo Centauri Carbon
with CANVAS on OpenCentauri COSMOS 26.09, both in Android Test Mode on the owner's printers: see [COMPATIBILITY_REPORT.md](COMPATIBILITY_REPORT.md) for its grades and
[evidence/README.md](evidence/README.md) for the bundles. Everything else below is UNVERIFIED in the Test Grid matrix, in
every category. Results recorded elsewhere (the owner's U1 cube print, the M2 heater acceptance on the U1, the COSMOS
live firmware reads) are prior history, not Test Grid evidence, and are not imported into it.

| Configuration | Suite | Coverage | Who can supply evidence |
|---|---|---|---|
| Snapmaker U1 on PAXX, multi-tool: file transfer and controls | `paxx-u1` has no multi-material tests in these categories | reference | n/a (slicing, monitoring and printing have evidence) |
| Colour mixing (Full Spectrum on the U1, ColorMix elsewhere): `mix-slice` and `mix-print` | every multi-material suite except the CANVAS ones (no color mixing on CANVAS since 2026-10-01, PROVENANCE P-0044) | reference and fixture | the owner (PAXX U1), then external testers |
| Centauri Carbon on COSMOS older than 26.07.0 | none (legacy profile not bundled; the COSMOS suite fails its profile match by design) | n/a | n/a |
| Snapmaker U1 on stock firmware | `snapmaker-u1-stock` | fixture | external testers |
| Elegoo Centauri Carbon on Elegoo stock firmware (SDCP) | `elegoo-centauri-carbon-stock` | fixture | external testers; send-and-start transfer, printing, pause/resume/cancel, CANVAS two-colour (no heaters, homing or moves through Nozzle) |
| Elegoo Centauri Carbon on OpenCentauri-patched stock firmware | `opencentauri-patched` | fixture | external testers; same steps as stock |
| Elegoo Centauri Carbon 2 (MQTT) | none yet | n/a | n/a |
| Bambu Lab (LAN mode) | `bambu-lan` | fixture | external testers; send-and-start with a `.gcode.3mf`, printing, pause/resume/cancel, camera; no AMS multi-colour yet (not enabled in Nozzle) |
| Prusa via PrusaLink | `prusalink` | fixture | external testers; send-and-start, printing, pause/resume/cancel; multi-material when the profile has several tools (XL 5T). No MMU3 slicing profile exists in Nozzle yet |
| Generic Klipper/Moonraker (each model its own row) | `generic-klipper` | fixture | external testers |
| OctoPrint | `octoprint` | fixture | external testers; send-and-start, printing, pause/resume/cancel; multi-material when the profile has several tools |
| Creality on its LAN interface (K2 family, Hi, K1; CFS) | `creality-lan` | fixture | external testers; status, slicing and CFS slots now; printing and pause/resume/cancel blocked until Nozzle It All starts prints on these printers |
| Flashforge on its local API (AD5X; IFS) | `flashforge-lan` | fixture | external testers; status, slicing and IFS slots now; printing and pause/resume/cancel blocked until Nozzle It All starts prints on these printers |
| Anycubic LAN mode on stock firmware (Kobra 3 / 3 Max, S1 / S1 Max, Kobra X; ACE) | `anycubic-lan` | fixture | external testers; status, slicing and ACE slots now; printing and controls blocked until Nozzle It All does them on these printers |
| Snapmaker 2.0 A-series on its touchscreen API (A150/A250/A350, single or dual, Quick Swap Kit) | `snapmaker-sstp` | fixture | external testers; status and slicing now; printing and controls blocked until Nozzle It All does them on these printers; the port (8080) and state names are unverified |
| Snapmaker J1 / Artisan over SACP | `snapmaker-sacp` | fixture | external testers; slicing now; status, printing and controls blocked until Nozzle It All does them on these printers; the state map and the Artisan's type number are unverified |
| Marlin / Prusa-firmware printer on a USB cable | `usb-serial` | fixture | external testers; status and slicing now; sending files, printing and controls blocked until Nozzle It All does them over USB |
| Older Flashforge on its legacy console (Adventurer 3/4, Creator, Guider) | `flashforge-legacy` | fixture | external testers; slicing now; status, slots, printing and controls blocked until Nozzle It All does them on these printers |
| Duet / RepRapFirmware | `duet-rrf` | fixture | external testers; slicing now; status, printing and controls blocked until Nozzle It All does them |
| UltiMaker S-series on its local API | `ultimaker-lan` | fixture | external testers; status and slicing now; printing and controls blocked until Nozzle It All starts prints on these printers |
| Repetier-Server | `repetier-server` | fixture | external testers; slicing now; status, printing and controls blocked until Nozzle It All does them |
| Desktop adapters (`paxx-lan`, `moonraker`, …) | none: Test Mode is Android-only in v1; a result on Android is not evidence for Desktop | n/a | n/a |

The owner does not have a stock Centauri Carbon; stock and OpenCentauri-patched coverage must come from external
testers. A COSMOS result is never evidence for either.

Also unverified on a device (software, not hardware): Test Mode's instrumented UI test (`TestModeDeviceTest`) has
been compiled but not run.
