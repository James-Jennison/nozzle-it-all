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
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Multi-material / tool changing | PASS (unreviewed) | UNVERIFIED | FAIL (unreviewed) | UNVERIFIED | SKIPPED (unreviewed) | physical run `d2bd7369` (paxx-u1 1.0.0), bundle `e74b744e1242` |
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Single material | PASS (unreviewed) | PARTIAL (unreviewed) | PASS (unreviewed) | SKIPPED (unreviewed) | SKIPPED (unreviewed) | physical run `d2bd7369` (paxx-u1 1.0.0), bundle `e74b744e1242` |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Multi-material / tool changing | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.0.0). |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.0.0). |

## History

Every run is kept. A newer run supersedes an older one for the current column; the older run and its bundle digest remain here.

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Multi-material / tool changing

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | UNVERIFIED | FAIL | UNVERIFIED | SKIPPED | unreviewed | current | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Single material

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | unreviewed | current | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |
