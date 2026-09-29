# Compatibility matrix semantics

`ReportBuilder` (`test-grid/.../Report.kt`) reads evidence bundles and produces a Markdown and a JSON matrix. It
works entirely from local files:

```bash
./gradlew -q :test-grid:cli --args="report --bundles docs/testgrid/evidence/bundles --ledger docs/testgrid/evidence/acceptance.json --markdown out.md --json out.json"
```

## Rows

One row per distinct combination of:

- printer manufacturer and model;
- firmware family **and** firmware version as read from the printer;
- Nozzle build (the producing app's version);
- adapter (e.g. `android-moonraker`; Desktop's `paxx-lan` would be a different row);
- material scope (`single_material` or `multi_material`).

Nothing is shared across rows. A COSMOS row says nothing about Elegoo stock or OpenCentauri-patched rows; a PAXX row
says nothing about stock U1; one Klipper printer's row says nothing about another model; a new Nozzle build or
firmware version starts a new row. A multi-material row appears only when the run included multi-material tests (if
the hardware was not detected they are graded SKIPPED there, never passed).

Every configuration a bundled suite targets also appears, with every category `UNVERIFIED` and "No physical evidence
yet", until a bundle for it exists. So the matrix always lists what has not been verified.

## Columns

Slicing · File transfer · Monitoring · Controls · Physical printing, each graded only from that category's tests in
the current run (`CategoryGrades.grade`):

| Test results in the category | Grade |
|---|---|
| none | UNVERIFIED |
| any FAIL | FAIL |
| all PASS | PASS |
| some PASS or PARTIAL, rest not PASS | PARTIAL |
| all SKIPPED | SKIPPED |
| otherwise any BLOCKED | BLOCKED |
| otherwise | UNVERIFIED |

Dependencies never carry grades across: when slicing fails, file transfer and printing are BLOCKED, not failed or
passed; when slicing passes but transfer was skipped, file transfer is SKIPPED (or PARTIAL if its upload-guard test
passed). Tested in `ReportTest.categoriesAreGradedIndependently` and `passingOneCategoryNeverFillsAnother`.

Simulated runs: every cell is UNVERIFIED, whatever the run recorded, and the row notes "Simulated runs only". A
simulated run never becomes current over a physical run.

## History and superseding

- Every verified bundle is kept in its row's history with run id, kind, suite and version, completion time, grades,
  review status, bundle digest and source file name.
- Each **category** is graded from each of its tests' newest actual result across the row's physical runs that no
  maintainer rejected ("actual" means anything but SKIPPED or UNVERIFIED; a test no run has actually run counts as
  SKIPPED), with the usual category rule. With no physical run, the row is UNVERIFIED. So a level-1 rerun (upload
  guard only) does not displace an earlier level-2 transfer result, a level-4 run that carried earlier passes over
  (below) is graded together with the run that passed them, and a newer result for the same test always replaces the
  older one, even when it fails. Each cell names the runs it uses when there is more than one; the JSON report lists
  them under `gradeRuns` (and the newest under `gradeSources`).
- **Carried passes:** Test Mode offers not to repeat tests that passed in an earlier run of the same suite, on the
  same printer (a key kept on the phone), firmware version and Nozzle version, when the test's own definition is
  unchanged (`Suite.testDigest`). A test a running test takes files from (a slice or an upload) always runs again. A
  carried test is recorded as SKIPPED with `carriedFrom` (run id, time, bundle digest); it never counts as a result
  of the new run.
- A newer run supersedes older ones for the categories it graded. A run can also name the runs it supersedes
  (`run.supersedes`). The older run stays in history marked "superseded by …", with its digest. Nothing is ever
  deleted or overwritten; `EvidenceStore` files bundles under their bundle digest and refuses to overwrite.
- Identical bundles (same bundle digest) count once. Corrupt, tampered or incomplete bundles are listed under
  "Bundles not used" with their reasons and never graded.

## Review status

The acceptance ledger (`docs/testgrid/evidence/acceptance.json`, format `nozzle.evidence-acceptance` 1.0) records
maintainer decisions by bundle digest:

```json
{
  "format": "nozzle.evidence-acceptance", "version": [1, 0],
  "accepted": [ { "bundleDigest": "…", "acceptedBy": "…", "date": "2026-10-01", "note": "…" } ],
  "rejected": [ { "bundleDigest": "…", "reason": "…" } ]
}
```

A physical result from a bundle no maintainer has accepted shows as e.g. `PASS (unreviewed)`. A rejected bundle stays
in history and never becomes current.
