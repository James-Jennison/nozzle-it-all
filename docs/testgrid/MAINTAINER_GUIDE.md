# Inspecting and accepting evidence (maintainers)

All commands run from the repository root. None of them contacts a printer.

## 0. Collect

Testers send bundles from Test Mode to `https://nozzleitall.com/testgrid/submit.php` (stored on the web server in
`/home/jamesjen/testgrid-inbox/bundles`, outside the web root, named by SHA-256, with the app-made tester ID; no IP
address is kept) or by email to support@nozzleitall.com. `scripts/testgrid_fetch_inbox.sh` copies new inbox bundles to
`gthost-build01:~/testgrid-evidence/incoming/` and verifies each. There is no login: the tester ID (made by the app,
random) groups a phone's bundles; `scripts/deploy_testgrid_inbox.sh --block t-...` stops one that is abused. Limits:
40 MB per bundle, 30 per tester ID and 300 in total per day. The endpoint itself is
`infra/testgrid-inbox/`, deployed with `scripts/deploy_testgrid_inbox.sh --go`.

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

On GTHost, file the bundle in the evidence store (named after its bundle digest, never overwritten):

```bash
./gradlew -q :test-grid:cli --args="store-add --store $HOME/testgrid-evidence/bundles received/bundle.zip"
```

Then add an entry to `docs/testgrid/evidence/acceptance.json`: under `accepted` with `bundleDigest`, `acceptedBy`, `date`
and a short `note`; or under `rejected` with a `reason`. Record any correction beside the bundle in
`docs/testgrid/evidence/README.md`; never edit or delete a stored bundle.

## 4. Rebuild the index and report

```bash
./gradlew -q :test-grid:cli --args="index --bundles $HOME/testgrid-evidence/bundles --location gthost-build01:~/testgrid-evidence/bundles --out docs/testgrid/evidence/index.json"
./gradlew -q :test-grid:cli --args="report --bundles $HOME/testgrid-evidence/bundles --ledger docs/testgrid/evidence/acceptance.json --markdown docs/testgrid/COMPATIBILITY_REPORT.md --json docs/testgrid/compatibility-report.json"
```

Commit the index, ledger and report (not the bundles).

Promotion to "Verified on hardware" elsewhere (site printers page, `SlicingModelCatalog.verifiedOnHardware`,
`PrinterCapabilities.verifiedOnRealHardware`) stays a separate, deliberate edit under the rules in
[HARDWARE_TESTING.md](../HARDWARE_TESTING.md): accepted Test Grid bundles are the evidence for it, per category.

## Sending a suite to a tester

Bundled suites ship in the app. For a new or changed suite, `validate` it and send the JSON file; testers import it
in Test Mode. Also send the generated instructions (`instructions <id>`).

## Adding a printer or firmware-specific suite

See [MANIFEST_SCHEMA.md](MANIFEST_SCHEMA.md#adding-a-printer-or-firmware-specific-suite).
