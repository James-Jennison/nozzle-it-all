# Hardware testing: how reports become "Verified on hardware"

The public guide is `site-src/pages/hardware-testing.html` (https://nozzleitall.com/testing/hardware/). Testers file
the GitHub issue form `.github/ISSUE_TEMPLATE/hardware-test-report.yml` (label `hardware-test`) or email the same
details to support@nozzleitall.com. The 20 mm test cube is `site-src/assets/test-cube-20mm.stl`.

## Promotion rule

A printer type moves from "Built from vendor docs" to "Verified on hardware" (`site-src/pages/printers.html`, and
`verifiedOnHardware` in `scripts/bundle_vendor_profiles.py` / `capabilitiesFor` in `PrinterCapabilities.kt`) only when
there are **at least two independent reports of it working, on different setups** (different owners, and different
firmware or model where possible). Tier 1 and Tier 3b must both pass for the control claim; Tier 2 alone only supports
the slicing-profile claim for that model. A report of a problem is never discarded: open an issue and fix it.

## Handling reports

* Delete or edit any report that contains a serial number, access code, password or API key, and tell the reporter.
* Record each model's outcome in `docs/WORK_ORDER.md` under the hardware-testing entry so the promotion count is auditable.
* Bambu reports include the discovery model string; use it to add automatic model preselection (WO-43 follow-up).
* Stock-firmware Snapmaker U1 reports are the only way to verify Bespok3d (enrollment stays opt-in and advanced).

## Nozzle Test Grid

Invited testers can instead run a standard suite in the app's Test Mode and send a redacted, content-hashed evidence
bundle that grades slicing, file transfer, monitoring, controls and physical printing separately, per firmware family.
See [testgrid/README.md](testgrid/README.md). Accepted bundles are the preferred evidence for promotion; the
two-report rule above still applies per configuration and per category.
