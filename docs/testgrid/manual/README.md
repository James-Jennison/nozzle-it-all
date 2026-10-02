# Manual acceptance: the owner's reference printers

The two files here are generated from the suites (`./gradlew -q :test-grid:cli --args="instructions <suite-id>"`);
regenerate them when a suite changes. Nothing in this folder has been run yet: no Test Grid step has been performed
on any physical printer. The steps below need the owner at the printer and are not to be performed by an agent.

## Snapmaker U1 on PAXX extended firmware: [paxx-u1.md](paxx-u1.md)

Setup:

1. Install a build containing Test Mode (a side-by-side debug build, e.g. `-PnozzleIdSuffix=.testgrid`, avoids
   touching the real `com.nozzleitall.app` install and its saved printers; it needs its own saved printer).
2. Save the U1 with type "Snapmaker U1 (PAXX)", profile "Snapmaker U1", its address and API key.
   Test Mode checks that the live read agrees (PAXX's `extended/` config folder); a mismatch stops the run.
3. Load PLA in toolhead 1; for the multi-material tests, two different colours in toolheads 1 and 2.

Order: run level 1 first (identity, telemetry, camera, slicing, profile match, upload guard); then level 2 (upload,
verify, delete one file); then level 3 (nozzle to 60 °C, bed to 40 °C, home, Z +5 mm) standing at the printer; then
level 4 (full acceptance print, then the pause/resume/cancel print, then the multi-material print, then the colour-mix swatch). Each level can be a
separate run; each run exports its own bundle.

## Elegoo Centauri Carbon on OpenCentauri COSMOS: [cosmos-centauri-carbon.md](cosmos-centauri-carbon.md)

Setup:

1. Save the printer with type Klipper, profile "Elegoo Centauri Carbon (OpenCentauri COSMOS)", and run "Detect
   firmware now" in Edit printer. COSMOS must be 26.07.0 or newer: the bundled COSMOS profile is that generation, and
   the suite's profile-match test fails on older COSMOS by design.
2. Without CANVAS, the multi-material tests are skipped automatically (AFC not detected). With CANVAS, load two
   colours in lanes 1 and 2.
3. The suite checks that the Elegoo stock-firmware profile is refused for this printer and that files containing
   M729 or M8213 are refused by the upload path before anything is sent. It never sends such a file.

Order: as for the U1, without the colour-mix swatch (Nozzle offers no color mixing on CANVAS). The slicing test is
level 1 here: slicing a COSMOS profile first reads the live firmware.

## After each run

Export the bundle, then on a computer with the repository:

```bash
./gradlew -q :test-grid:cli --args="verify bundle.zip"
./gradlew -q :test-grid:cli --args="store-add --store docs/testgrid/evidence/bundles bundle.zip"
```

Record acceptance in `docs/testgrid/evidence/acceptance.json` and rebuild the report (see
[../MAINTAINER_GUIDE.md](../MAINTAINER_GUIDE.md)).
