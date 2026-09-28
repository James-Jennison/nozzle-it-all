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
| `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 1, owner at the printer | First physical Test Grid bundle. **Corrections:** `producer.sourceRevision` says `d9ea90d`, but the side-by-side debug build (`com.nozzleitall.app.testgrid`) was built from the source of commit `af63b08` (the revision was stamped once and not refreshed for later builds). `engine.version` reads `[ip]`: the redactor masked the engine's four-part version number (fixed afterwards; version fields are no longer masked). Accepted by the owner on 2026-09-28 (`acceptance.json`). |
| `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 2, owner at the printer | Upload (793,830 bytes, SHA-256 verified), listing and delete on the real U1. Built from `0b6d902`. Its cleanup step asked for approval of a delete with nothing left to delete (no effect; fixed in `acadaab`). Accepted by the owner on 2026-09-28. |
| `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 1 rerun, owner at the printer | Built from `8f16e36`, the first build that reads the U1's loaded filament: T0 Polymaker PLA #BE38F3 (active), T1 Snapmaker PLA #E2DEDB, T2 TINMORRY TPU #DD0000, T3 Generic PETG #1E88E5, confirmed by the owner. Multi-material monitoring PASS. Not yet reviewed in `acceptance.json`. |
