# Test Grid evidence store

`bundles/` holds verified evidence bundles, each named after its bundle digest by
`./gradlew -q :test-grid:cli --args="store-add --store docs/testgrid/evidence/bundles <bundle.zip>"`. Bundles are
never edited or deleted. `acceptance.json` records which bundles a maintainer accepted or rejected (see
[../MAINTAINER_GUIDE.md](../MAINTAINER_GUIDE.md)).

Simulated bundles never go here; the example in [../examples/](../examples/) shows the format.

## Bundles and corrections

Bundles are immutable, so a correction to one is recorded here, never by editing it.

| Bundle digest | Run | Notes |
|---|---|---|
| `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 1, owner at the printer | First physical Test Grid bundle. **Corrections:** `producer.sourceRevision` says `d9ea90d`, but the side-by-side debug build (`com.nozzleitall.app.testgrid`) was built from the source of commit `af63b08` (the revision was stamped once and not refreshed for later builds). `engine.version` reads `[ip]`: the redactor masked the engine's four-part version number (fixed afterwards; version fields are no longer masked). Not yet reviewed in `acceptance.json`. |
