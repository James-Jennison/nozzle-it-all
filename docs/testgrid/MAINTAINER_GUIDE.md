# Inspecting and accepting evidence (maintainers)

All commands run from the repository root. None of them contacts a printer.

## 1. Verify

```bash
./gradlew -q :test-grid:cli --args="verify received/bundle.zip"
```

`VALID` means the integrity manifest, every file hash, the bundle digest and the content digest agree, and the
record is complete. It does **not** mean the bundle is genuine: bundles are unsigned. `INVALID` lists every reason;
don't use such a bundle.

## 2. Inspect

```bash
./gradlew -q :test-grid:cli --args="preview received/bundle.zip"
```

Check, at least:

- `target.kind` is `physical`. Simulated bundles grade nothing.
- `producer` (Nozzle version, source revision) and `engine` (commit, `binarySha256`) match a build you know.
- `suite.digest` matches the suite you sent (`validate` prints it).
- `target.firmware` family and version are plausible for the printer, and `target.profile.sha256` matches the bundled
  pack for that Nozzle version.
- Per test: results, `reason`, `interventions` (declines, unknown outcomes, restarts, timeouts), measurements within
  tolerance, and photos that plausibly show the acceptance model on this printer.
- `inputs[].gcode.sha256`: slicing the same model and profile with the same build should give the same G-code hash.
- `redaction.counts` and the files themselves: nothing private slipped through. If something did, reject the bundle,
  tell the tester, and add a redaction rule with a test.

## 3. File and record the decision

```bash
./gradlew -q :test-grid:cli --args="store-add --store docs/testgrid/evidence/bundles received/bundle.zip"
```

The store names the file after its bundle digest and never overwrites. Then add an entry to
`docs/testgrid/evidence/acceptance.json`: under `accepted` with `bundleDigest`, `acceptedBy`, `date` and a short
`note`; or under `rejected` with a `reason`. Commit both; never edit or delete a stored bundle.

## 4. Rebuild the report

```bash
./gradlew -q :test-grid:cli --args="report --bundles docs/testgrid/evidence/bundles --ledger docs/testgrid/evidence/acceptance.json --markdown docs/testgrid/COMPATIBILITY_REPORT.md --json docs/testgrid/compatibility-report.json"
```

Promotion to "Verified on hardware" elsewhere (site printers page, `SlicingModelCatalog.verifiedOnHardware`,
`PrinterCapabilities.verifiedOnRealHardware`) stays a separate, deliberate edit under the rules in
[HARDWARE_TESTING.md](../HARDWARE_TESTING.md): accepted Test Grid bundles are the evidence for it, per category.

## Sending a suite to a tester

Bundled suites ship in the app. For a new or changed suite, `validate` it and send the JSON file; testers import it
in Test Mode. Also send the generated instructions (`instructions <id>`).

## Adding a printer or firmware-specific suite

See [MANIFEST_SCHEMA.md](MANIFEST_SCHEMA.md#adding-a-printer-or-firmware-specific-suite).
