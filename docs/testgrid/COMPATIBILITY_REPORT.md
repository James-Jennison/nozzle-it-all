# Nozzle Test Grid compatibility report

Generated from verified evidence bundles. Each category is graded only from tests of that category; a result for one
firmware family, adapter, Nozzle build or material scope is never evidence for another. Simulated runs and
configurations without physical evidence are UNVERIFIED. "(unreviewed)" means no maintainer has accepted the bundle yet.

| Printer | Firmware | Nozzle build | Adapter | Scope | Slicing | File transfer | Monitoring | Controls | Physical printing | Current evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| Bambu Lab X1 Carbon | bambu-lan | — | android-bambu-lan | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite bambu-lan 1.3.0). |
| Elegoo Centauri Carbon | cosmos Release - 26.09.0 | 0.1.0.testgrid | android-moonraker | Multi-material / tool changing | PASS · `47be97a3` | UNVERIFIED | PASS · `fa7d2ea1` | UNVERIFIED | PASS · `47be97a3` | physical run `47be97a3` (cosmos-centauri-carbon 1.3.0), bundle `b18cf0441585`; physical run `fa7d2ea1` (cosmos-centauri-carbon 1.2.0), bundle `f36c2f385181` |
| Elegoo Centauri Carbon | cosmos Release - 26.09.0 | 0.1.0.testgrid | android-moonraker | Single material | PASS · `d9da0749` · `fa7d2ea1` | PASS · `fa7d2ea1` | PASS · `fa7d2ea1` | PASS · `fa7d2ea1` | PASS · `fa7d2ea1` | physical run `d9da0749` (cosmos-centauri-carbon 1.3.0), bundle `3e094a9380bd`; physical run `fa7d2ea1` (cosmos-centauri-carbon 1.2.0), bundle `f36c2f385181` |
| Elegoo Centauri Carbon | elegoo-stock | — | android-elegoo-sdcp | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite elegoo-centauri-carbon-stock 1.3.0). |
| Elegoo Centauri Carbon | opencentauri-patched | — | android-elegoo-sdcp | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite opencentauri-patched 1.3.0). |
| Generic Klipper | klipper | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite generic-klipper 1.3.0). |
| Generic OctoPrint printer | octoprint | — | android-octoprint | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite octoprint 1.3.0). |
| Prusa MK4S | prusalink | — | android-prusalink | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite prusalink 1.3.0). |
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Multi-material / tool changing | PASS · `5624aa6e` | UNVERIFIED | PASS · `5624aa6e` | UNVERIFIED | PASS · `2da1f8f0` | physical run `5624aa6e` (paxx-u1 1.2.0), bundle `cc752caddf5c`; physical run `2da1f8f0` (paxx-u1 1.1.0), bundle `2d3afb851e9f` |
| Snapmaker U1 | paxx-extended 1.6.0.267_20260815150420 | 0.1.0.testgrid | android-moonraker | Single material | PASS · `5624aa6e` | PASS · `5624aa6e` | PASS · `5624aa6e` | PASS · `2da1f8f0` | PASS · `2da1f8f0` | physical run `5624aa6e` (paxx-u1 1.2.0), bundle `cc752caddf5c`; physical run `2da1f8f0` (paxx-u1 1.1.0), bundle `2d3afb851e9f` |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Multi-material / tool changing | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.3.0). |
| Snapmaker U1 | snapmaker-stock | — | android-moonraker | Single material | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | No physical evidence yet (fixture suite snapmaker-u1-stock 1.3.0). |

## History

Every run is kept. Each category in the matrix is graded from each of its tests' newest actual result across these runs, and names the runs used; a newer run that skipped tests (a lower level, or tests that had already passed) leaves the earlier results in place. Superseded runs and their bundle digests remain here.

### Elegoo Centauri Carbon · cosmos Release - 26.09.0 · Nozzle 0.1.0.testgrid · android-moonraker · Multi-material / tool changing

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `9a06437f` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T11:28:29.434Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | accepted | superseded by `47be97a3` | `7134a20862d83959c68755b200f404ba7ea6118afc1adafdff5dabf4c000a05e` | 7134a20862d83959c68755b200f404ba7ea6118afc1adafdff5dabf4c000a05e.nozzle-evidence.zip |
| `d801028e` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T11:50:04.141Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | accepted | superseded by `47be97a3` | `f09a467a1f6ad6a7646e79653580e03a797ad9a219328d060da33cfbba500768` | f09a467a1f6ad6a7646e79653580e03a797ad9a219328d060da33cfbba500768.nozzle-evidence.zip |
| `fa7d2ea1` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T15:06:20.445Z | PASS | UNVERIFIED | PASS | UNVERIFIED | FAIL | accepted | current for monitoring | `f36c2f3851813e2fbe3ead6cf488cf23d81905fed90250d4e74d389282f877cc` | f36c2f3851813e2fbe3ead6cf488cf23d81905fed90250d4e74d389282f877cc.nozzle-evidence.zip |
| `d9da0749` | physical | cosmos-centauri-carbon 1.3.0 | 2026-09-29T17:21:55.997Z | PASS | UNVERIFIED | SKIPPED | UNVERIFIED | PASS | accepted | superseded by `47be97a3` | `3e094a9380bdcaa4c4a99870aa794d2d8de8b1c13a100e6a66b7a05e8a3501a9` | 3e094a9380bdcaa4c4a99870aa794d2d8de8b1c13a100e6a66b7a05e8a3501a9.nozzle-evidence.zip |
| `47be97a3` | physical | cosmos-centauri-carbon 1.3.0 | 2026-09-29T18:41:38.021Z | PASS | UNVERIFIED | SKIPPED | UNVERIFIED | PASS | accepted | current for slicing, physical printing | `b18cf044158543ba8e732e653c596767ea4b0c0702347f9d63699afc6ee0d1f3` | b18cf044158543ba8e732e653c596767ea4b0c0702347f9d63699afc6ee0d1f3.nozzle-evidence.zip |

### Elegoo Centauri Carbon · cosmos Release - 26.09.0 · Nozzle 0.1.0.testgrid · android-moonraker · Single material

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `9a06437f` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T11:28:29.434Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | accepted | superseded by `47be97a3` | `7134a20862d83959c68755b200f404ba7ea6118afc1adafdff5dabf4c000a05e` | 7134a20862d83959c68755b200f404ba7ea6118afc1adafdff5dabf4c000a05e.nozzle-evidence.zip |
| `d801028e` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T11:50:04.141Z | PASS | PASS | PASS | PARTIAL | SKIPPED | accepted | superseded by `47be97a3` | `f09a467a1f6ad6a7646e79653580e03a797ad9a219328d060da33cfbba500768` | f09a467a1f6ad6a7646e79653580e03a797ad9a219328d060da33cfbba500768.nozzle-evidence.zip |
| `fa7d2ea1` | physical | cosmos-centauri-carbon 1.2.0 | 2026-09-29T15:06:20.445Z | PASS | PASS | PASS | PASS | PASS | accepted | current for slicing, file transfer, monitoring, controls, physical printing | `f36c2f3851813e2fbe3ead6cf488cf23d81905fed90250d4e74d389282f877cc` | f36c2f3851813e2fbe3ead6cf488cf23d81905fed90250d4e74d389282f877cc.nozzle-evidence.zip |
| `d9da0749` | physical | cosmos-centauri-carbon 1.3.0 | 2026-09-29T17:21:55.997Z | PARTIAL | SKIPPED | SKIPPED | SKIPPED | SKIPPED | accepted | current for slicing | `3e094a9380bdcaa4c4a99870aa794d2d8de8b1c13a100e6a66b7a05e8a3501a9` | 3e094a9380bdcaa4c4a99870aa794d2d8de8b1c13a100e6a66b7a05e8a3501a9.nozzle-evidence.zip |
| `47be97a3` | physical | cosmos-centauri-carbon 1.3.0 | 2026-09-29T18:41:38.021Z | SKIPPED | SKIPPED | SKIPPED | SKIPPED | SKIPPED | accepted | historical | `b18cf044158543ba8e732e653c596767ea4b0c0702347f9d63699afc6ee0d1f3` | b18cf044158543ba8e732e653c596767ea4b0c0702347f9d63699afc6ee0d1f3.nozzle-evidence.zip |

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Multi-material / tool changing

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | UNVERIFIED | FAIL | UNVERIFIED | SKIPPED | accepted | superseded by `5624aa6e` | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |
| `d4e722c4` | physical | paxx-u1 1.0.0 | 2026-09-28T23:38:59.136Z | PASS | UNVERIFIED | FAIL | UNVERIFIED | SKIPPED | accepted | superseded by `5624aa6e` | `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e.nozzle-evidence.zip |
| `ffbe615e` | physical | paxx-u1 1.0.0 | 2026-09-28T23:51:32.317Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | accepted | superseded by `5624aa6e` | `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1.nozzle-evidence.zip |
| `9eefd62b` | physical | paxx-u1 1.0.0 | 2026-09-29T00:01:26.654Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | accepted | superseded by `5624aa6e` | `0d338dc676e85ad233a58c66fbce074092e56908b627454eb478d6a17258deab` | 0d338dc676e85ad233a58c66fbce074092e56908b627454eb478d6a17258deab.nozzle-evidence.zip |
| `66d521ad` | physical | paxx-u1 1.0.0 | 2026-09-29T02:35:07.644Z | PASS | UNVERIFIED | PASS | UNVERIFIED | PASS | accepted | superseded by `5624aa6e` | `58f24794cfcf6a41a5f8e40b8ff7d3871447edf9520093b0acbf0c2fb0081200` | 58f24794cfcf6a41a5f8e40b8ff7d3871447edf9520093b0acbf0c2fb0081200.nozzle-evidence.zip |
| `2da1f8f0` | physical | paxx-u1 1.1.0 | 2026-09-29T04:46:01.698Z | PASS | UNVERIFIED | PASS | UNVERIFIED | PASS | accepted | current for physical printing | `2d3afb851e9f023370a0de1a9587c338ef38ecbf95b12be45576f7fd0aa44214` | 2d3afb851e9f023370a0de1a9587c338ef38ecbf95b12be45576f7fd0aa44214.nozzle-evidence.zip |
| `5624aa6e` | physical | paxx-u1 1.2.0 | 2026-09-29T11:09:33.938Z | PASS | UNVERIFIED | PASS | UNVERIFIED | SKIPPED | accepted | current for slicing, monitoring | `cc752caddf5c6862142bda44c6e4cc41fdf00e193c593aaba8749fa48dd3ae01` | cc752caddf5c6862142bda44c6e4cc41fdf00e193c593aaba8749fa48dd3ae01.nozzle-evidence.zip |

### Snapmaker U1 · paxx-extended 1.6.0.267_20260815150420 · Nozzle 0.1.0.testgrid · android-moonraker · Single material

| Run | Kind | Suite | Completed (UTC) | Slicing | File transfer | Monitoring | Controls | Physical printing | Review | Status | Bundle digest | Source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `d2bd7369` | physical | paxx-u1 1.0.0 | 2026-09-28T23:28:20.919Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | accepted | superseded by `5624aa6e` | `e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3` | e74b744e1242d61bf52b177f5e5eb768b7e6b05f432ad5f95e4da0f426141fd3.nozzle-evidence.zip |
| `d4e722c4` | physical | paxx-u1 1.0.0 | 2026-09-28T23:38:59.136Z | PASS | PASS | PASS | SKIPPED | SKIPPED | accepted | superseded by `5624aa6e` | `af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e` | af07e2adc7be9c7713756cc6c34ac9402f18759d711d9fcc049305ae46498d7e.nozzle-evidence.zip |
| `ffbe615e` | physical | paxx-u1 1.0.0 | 2026-09-28T23:51:32.317Z | PASS | PARTIAL | PASS | SKIPPED | SKIPPED | accepted | superseded by `5624aa6e` | `b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1` | b7484a3810f04b4103d4d40cfb6943837409bd68f3495aaefa3c179a4683b6c1.nozzle-evidence.zip |
| `9eefd62b` | physical | paxx-u1 1.0.0 | 2026-09-29T00:01:26.654Z | PASS | PASS | PASS | PARTIAL | SKIPPED | accepted | superseded by `5624aa6e` | `0d338dc676e85ad233a58c66fbce074092e56908b627454eb478d6a17258deab` | 0d338dc676e85ad233a58c66fbce074092e56908b627454eb478d6a17258deab.nozzle-evidence.zip |
| `66d521ad` | physical | paxx-u1 1.0.0 | 2026-09-29T02:35:07.644Z | PASS | PASS | PASS | PARTIAL | FAIL | accepted | superseded by `5624aa6e` | `58f24794cfcf6a41a5f8e40b8ff7d3871447edf9520093b0acbf0c2fb0081200` | 58f24794cfcf6a41a5f8e40b8ff7d3871447edf9520093b0acbf0c2fb0081200.nozzle-evidence.zip |
| `2da1f8f0` | physical | paxx-u1 1.1.0 | 2026-09-29T04:46:01.698Z | PASS | PASS | PASS | PASS | PASS | accepted | current for controls, physical printing | `2d3afb851e9f023370a0de1a9587c338ef38ecbf95b12be45576f7fd0aa44214` | 2d3afb851e9f023370a0de1a9587c338ef38ecbf95b12be45576f7fd0aa44214.nozzle-evidence.zip |
| `5624aa6e` | physical | paxx-u1 1.2.0 | 2026-09-29T11:09:33.938Z | PASS | PASS | PASS | SKIPPED | SKIPPED | accepted | current for slicing, file transfer, monitoring | `cc752caddf5c6862142bda44c6e4cc41fdf00e193c593aaba8749fa48dd3ae01` | cc752caddf5c6862142bda44c6e4cc41fdf00e193c593aaba8749fa48dd3ae01.nozzle-evidence.zip |
