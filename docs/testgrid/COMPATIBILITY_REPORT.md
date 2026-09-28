# Nozzle Test Grid compatibility report

Generated from verified evidence bundles. Each category is graded only from tests of that category; a result for one
firmware family, adapter, Nozzle build or material scope is never evidence for another. Simulated runs and
configurations without physical evidence are UNVERIFIED. "(unreviewed)" means no maintainer has accepted the bundle yet.

| Printer | Firmware | Nozzle build | Adapter | Scope | Slicing | File transfer | Monitoring | Controls | Physical printing | Current evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| Bambu Lab X1 Carbon | bambu-lan | — | android-bambu-lan | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite bambu-lan 1.0.0). |
| Elegoo Centauri Carbon | cosmos | — | android-moonraker | Multi-material / tool changing | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (reference suite cosmos-centauri-carbon 1.0.0). |
| Elegoo Centauri Carbon | cosmos | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (reference suite cosmos-centauri-carbon 1.0.0). |
| Elegoo Centauri Carbon | elegoo-stock | — | android-elegoo-sdcp | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite elegoo-centauri-carbon-stock 1.0.0). |
| Elegoo Centauri Carbon | opencentauri-patched | — | android-elegoo-sdcp | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite opencentauri-patched 1.0.0). |
| Generic Klipper | klipper | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite generic-klipper 1.0.0). |
| Generic OctoPrint printer | octoprint | — | android-octoprint | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite octoprint 1.0.0). |
| Prusa MK4S | prusalink | — | android-prusalink | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite prusalink 1.0.0). |
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Multi-material / tool changing | PASS (unreviewed) | UNVERIFIED | PASS (unreviewed) | UNVERIFIED | SKIPPED (unreviewed) | physical run `ffbe615e` (paxx-u1 1.0.0), bundle `b7484a3810f0` |
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Single material | PASS (unreviewed) · `ffbe615e` | PASS · `d4e722c4` | PASS (unreviewed) · `ffbe615e` | SKIPPED (unreviewed) · `ffbe615e` | SKIPPED (unreviewed) · `ffbe615e` | physical run `ffbe615e` (paxx-u1 1.0.0), bundle `b7484a3810f0`; physical run `d4e722c4` (paxx-u1 1.0.0), bundle `af07e2adc7be` |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Multi-material / tool changing | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.0.0). |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.0.0). |

## History

Every run is kept. For each category the matrix shows the newest run that graded it; a newer run that skipped a category leaves the earlier result in place. Superseded runs and their bundle digests remain here.

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Multi-material / tool changing

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | UNVERIFIED | FAIL | UNVERIFIED | SKIPPED | accepted | superseded by `ffbe615e` | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |
| `d4e722c4` | physical | paxx-u1 1.0.0 | 2026-09-28T23:38:59.136Z | PASS | UNVERIFIED | FAIL | UNVERIFIED | SKIPPED | accepted | superseded by `ffbe615e` | `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e.nozzle-evidence.zip |
| `ffbe615e` | physical | paxx-u1 1.0.0 | 2026-09-28T23:51:32.317Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | unreviewed | current | `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1.nozzle-evidence.zip |

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Single material

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | accepted | superseded by `ffbe615e` | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |
| `d4e722c4` | physical | paxx-u1 1.0.0 | 2026-09-28T23:38:59.136Z | PASS | PASS | PASS | SKIPPED | SKIPPED | accepted | current for file transfer | `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e.nozzle-evidence.zip |
| `ffbe615e` | physical | paxx-u1 1.0.0 | 2026-09-28T23:51:32.317Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | unreviewed | current for slicing, monitoring, controls, physical printing | `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1.nozzle-evidence.zip |
