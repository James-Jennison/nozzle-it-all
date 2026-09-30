# Hardware testing: how reports become "Verified on hardware"

The public guide is `site-src/pages/hardware-testing.html` (https://nozzleitall.com/testing/hardware/). Testers file
the GitHub issue form `.github/ISSUE_TEMPLATE/hardware-test-report.yml` (label `hardware-test`) or email the same
details to support@nozzleitall.com. The 20 mm test cube is `site-src/assets/test-cube-20mm.stl`.

## Promotion rule

A printer type, or a slicing profile, moves from "Built from vendor docs" to "Verified on hardware"
(`site-src/pages/printers.html`, `verifiedOnHardware` in `scripts/bundle_vendor_profiles.py` and
`SlicingModelCatalog.kt`, `capabilitiesFor` in `PrinterCapabilities.kt`) on **one accepted Test Grid result from a real
printer** of that type and firmware: an evidence bundle a maintainer has reviewed and accepted
(`docs/testgrid/evidence/acceptance.json`), with the relevant categories PASS. For a printer type that means monitoring,
controls and physical printing; for a slicing profile, a physical print sliced with that profile. A multi-material
result counts only for multi-material. The owner's own runs count. Changed on 2026-09-29 (owner decision) from the
earlier rule of two independent reports, which predates the Test Grid: a reviewed bundle carries step-by-step results,
measurements, photos and hashes. The compatibility page shows each result's evidence; as outside testers join, it
should also show how many different testers stand behind each grade.

Reports by hand (the issue form or email) still help find problems. A report of a problem is never discarded: open an
issue and fix it.

## Handling reports

* Delete or edit any report that contains a serial number, access code, password or API key, and tell the reporter.
* Record each model's outcome in `docs/WORK_ORDER.md` under the hardware-testing entry so the promotion count is auditable.
* Bambu reports include the discovery model string; use it to add automatic model preselection (WO-43 follow-up).
* Stock-firmware Snapmaker U1 reports are the only way to verify Bespok3d (enrollment stays opt-in and advanced).

## Nozzle Test Grid

Invited testers can instead run a standard suite in the app's Test Mode and send a redacted, content-hashed evidence
bundle that grades slicing, file transfer, monitoring, controls and physical printing separately, per firmware family.
See [testgrid/README.md](testgrid/README.md). Accepted bundles are the evidence for promotion (rule above).
