# Test Grid evidence store

Bundles are content-addressed, immutable and never deleted. Since 2026-09-28 they are kept **outside git**, because a run
with photos is megabytes (the first level-4 bundle is 12.7 MB):

- **Store:** `gthost-build01:~/testgrid-evidence/bundles/` (files read-only), filed by bundle digest with
  `./gradlew -q :test-grid:cli --args="store-add --store ~/testgrid-evidence/bundles <bundle.zip>"`.
  This is currently the only copy apart from the tester's phone; add a backup before relying on it.
- **Committed here:** [`index.json`](index.json) (every bundle's digests, size, run, suite, level, printer and location),
  [`acceptance.json`](acceptance.json) (maintainer decisions) and the report built from the store
  ([`../COMPATIBILITY_REPORT.md`](../COMPATIBILITY_REPORT.md)). The digests let anyone holding a bundle prove it is the
  one the report used.
- `bundles/` in the repository keeps the four small level 1-3 bundles committed before the move; `*.zip` there is
  git-ignored from now on.

## Bundles and corrections

Bundles are immutable, so a correction to one is recorded here, never by editing it.

| Bundle digest | Run | Notes |
|---|---|---|
| `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 1, owner at the printer | First physical Test Grid bundle. **Corrections:** `producer.sourceRevision` says `d9ea90d`, but the side-by-side debug build (`com.nozzleitall.app.testgrid`) was built from the source of commit `af63b08` (the revision was stamped once and not refreshed for later builds). `engine.version` reads `[ip]`: the redactor masked the engine's four-part version number (fixed afterwards; version fields are no longer masked). Accepted by the owner on 2026-09-28 (`acceptance.json`). |
| `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 2, owner at the printer | Upload (793,830 bytes, SHA-256 verified), listing and delete on the real U1. Built from `0b6d902`. Its cleanup step asked for approval of a delete with nothing left to delete (no effect; fixed in `acadaab`). Accepted by the owner on 2026-09-28. |
| `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 1 rerun, owner at the printer | Built from `8f16e36`, the first build that reads the U1's loaded filament: T0 Polymaker PLA #BE38F3 (active), T1 Snapmaker PLA #E2DEDB, T2 TINMORRY TPU #DD0000, T3 Generic PETG #1E88E5, confirmed by the owner. Multi-material monitoring PASS. Accepted by the owner on 2026-09-28. |
| `0d338dc676e85ad233a58c66fbce074092e56908b627454eb478d6a17258deab` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 3, owner at the printer | Built from `8f16e36`. First supervised controls on real hardware: nozzle 60 °C and off, bed 40 °C and off, G28, Z +5 mm, heaters off in cleanup; every step approved individually and acknowledged; owner confirmed each. Accepted by the owner on 2026-09-28. |
| `58f24794cfcf6a41a5f8e40b8ff7d3871447edf9520093b0acbf0c2fb0081200` | 2026-09-28, PAXX U1, `paxx-u1` 1.0.0, level 4, owner at the printer | Built from `774c690`: all three prints started with `BED_LEVEL="1"` (adaptive mesh ran). Single-material acceptance print, pause/resume/cancel and the two-tool print all completed; four photos. **Corrections from the owner:** the height (entered 10) and thin wall (entered 2) were estimates by eye with a ruler; remeasured after the run: height 10.4 mm and thin wall 1.1 mm, both within tolerance (tower X/Y 20 and hole 8 were measured accurately). Homing physically worked; Nozzle lost the G28 reply (timeout) and recorded the outcome as unknown. Photos carry an Ultra HDR gain map after the main image (brightness parameters only; no location or device data); dropped from later bundles. Three `nozzle-testgrid-*` files remain on the printer (cleanup refuses to delete a file the printer still has loaded). Accepted by the owner on 2026-09-28. Stored outside git (12.7 MB; see below). |
| `2d3afb851e9f023370a0de1a9587c338ef38ecbf95b12be45576f7fd0aa44214` | 2026-09-28, PAXX U1, `paxx-u1` 1.1.0, level 4 rerun, owner at the printer | Built from `ca84486`. Every test PASS; measured with calipers: towers 20.0 × 20.0 mm, hole 8.0 mm, height 10.43 mm, thin wall 1.27 mm. The G28 reply was lost again (about 66 s); Nozzle confirmed the homing from the printer's homed axes and did not resend. Pause, resume, cancel and the two-tool print passed. Photos end at the main image (no gain map). Three more `nozzle-testgrid-*` files were left on the printer because they were still loaded. Supersedes `58f24794` for physical printing. Accepted by the owner on 2026-09-28. Stored outside git (12.6 MB). |
