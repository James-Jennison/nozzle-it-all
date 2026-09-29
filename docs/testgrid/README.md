# Nozzle Test Grid

Invite-only, local-first hardware acceptance: trusted printer owners run standard Nozzle It All suites on their own
printers in the app's **Test Mode** (hidden until turned on: Settings → About & credits → tap the version 7 times; then
Settings → Test Mode) and return redacted, reproducible evidence bundles.
Maintainers verify them and build a compatibility matrix that grades each printer configuration separately on slicing,
file transfer, monitoring, controls and physical printing.

| Document | For |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | components, run lifecycle, safety rules, threat and trust boundaries, future coordinator |
| [MANIFEST_SCHEMA.md](MANIFEST_SCHEMA.md) | suite format, step vocabulary, safety levels, result states, adding a suite |
| [EVIDENCE_FORMAT.md](EVIDENCE_FORMAT.md) | bundle layout, integrity and content digests, signing status, redaction rules |
| [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) | rows, grading, history and superseding, review status |
| [TESTER_GUIDE.md](TESTER_GUIDE.md) | running a suite in Test Mode |
| [MAINTAINER_GUIDE.md](MAINTAINER_GUIDE.md) | verifying, inspecting, accepting evidence; rebuilding the report |
| [ACCEPTANCE_MODEL.md](ACCEPTANCE_MODEL.md) | the standard single- and multi-material acceptance models |
| [UNVERIFIED.md](UNVERIFIED.md) | which printer/firmware combinations have no physical evidence |
| [manual/](manual/) | generated manual acceptance instructions for the PAXX U1 and COSMOS Centauri Carbon |
| [examples/](examples/) | a simulated example bundle and the report built from it (simulated: grades nothing) |
| [evidence/](evidence/) | the evidence index, acceptance ledger and corrections (bundles are stored outside git) |

Command-line tool (never contacts a printer):

```bash
./gradlew -q :test-grid:cli --args="help"
```
