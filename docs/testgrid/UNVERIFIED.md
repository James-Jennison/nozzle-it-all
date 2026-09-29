# Physically unverified configurations

Physical Test Grid evidence exists only for the Snapmaker U1 on PAXX extended firmware (Android Test Mode), from the
owner's printer: see [COMPATIBILITY_REPORT.md](COMPATIBILITY_REPORT.md) for its grades and
[evidence/README.md](evidence/README.md) for the bundles. Everything else below is UNVERIFIED in the Test Grid matrix, in
every category. Results recorded elsewhere (the owner's U1 cube print, the M2 heater acceptance on the U1, the COSMOS
live firmware reads) are prior history, not Test Grid evidence, and are not imported into it.

| Configuration | Suite | Coverage | Who can supply evidence |
|---|---|---|---|
| Snapmaker U1 on PAXX, multi-tool: file transfer and controls | `paxx-u1` has no multi-material tests in these categories | reference | n/a (slicing, monitoring and printing have evidence) |
| Elegoo Centauri Carbon on OpenCentauri COSMOS 26.07+ (android-moonraker) | `cosmos-centauri-carbon` | reference | the owner (manual steps: [manual/cosmos-centauri-carbon.md](manual/cosmos-centauri-carbon.md)) |
| Centauri Carbon on COSMOS with CANVAS (AFC) | `cosmos-centauri-carbon` multi-material tests | reference | the owner, once CANVAS is fitted; skipped until AFC is detected |
| Centauri Carbon on COSMOS older than 26.07.0 | none (legacy profile not bundled; the COSMOS suite fails its profile match by design) | n/a | n/a |
| Snapmaker U1 on stock firmware | `snapmaker-u1-stock` | fixture | external testers |
| Elegoo Centauri Carbon on Elegoo stock firmware (SDCP) | `elegoo-centauri-carbon-stock` | fixture | external testers; Test Mode v1 blocks transfer and print steps on this connection |
| Elegoo Centauri Carbon on OpenCentauri-patched stock firmware | `opencentauri-patched` | fixture | external testers; same limits as stock |
| Elegoo Centauri Carbon 2 (MQTT) | none yet | n/a | n/a |
| Bambu Lab (LAN mode) | `bambu-lan` | fixture | external testers; Test Mode v1 blocks transfer and print steps (Bambu needs a `.gcode.3mf` path in Test Mode) |
| Prusa via PrusaLink | `prusalink` | fixture | external testers; Test Mode v1 blocks transfer and print steps |
| Generic Klipper/Moonraker (each model its own row) | `generic-klipper` | fixture | external testers |
| OctoPrint | `octoprint` | fixture | external testers; Test Mode v1 blocks transfer and print steps |
| Desktop adapters (`paxx-lan`, `moonraker`, …) | none: Test Mode is Android-only in v1; a result on Android is not evidence for Desktop | n/a | n/a |

The owner does not have a stock Centauri Carbon; stock and OpenCentauri-patched coverage must come from external
testers. A COSMOS result is never evidence for either.

Also unverified on a device (software, not hardware): Test Mode's instrumented UI test (`TestModeDeviceTest`) has
been compiled but not run.
