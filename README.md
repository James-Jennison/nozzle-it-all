# Nozzle It All

**Slice on your phone. Control your printers from anywhere.**

Nozzle It All is a free, open-source Android app for preparing, slicing, sending, and monitoring
3D prints. It works locally, requires no Nozzle It All account, and includes an on-device slicing
engine built from [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer).

[Website](https://nozzleitall.com/) ·
[Supported printers](https://nozzleitall.com/printers/) ·
[Testing guide](https://nozzleitall.com/testing/) ·
[Project plan](docs/CONSUMER_SLICER_PLAN.md) ·
[Current work](docs/WORK_ORDER.md)

## Status

The Android app is in invite-only closed testing on Google Play and is not yet publicly available.
Linux desktop, Web, and Windows desktop are planned, in that order. iOS and macOS are deferred.

## What it does

- **Slices on the phone.** Import STL, 3MF, or OBJ files and generate real G-code without uploading
  models to a cloud slicer. The app includes 376 bundled OrcaSlicer-derived printer profiles.
- **Prepares complete projects.** Arrange multiple objects, move, rotate, scale, duplicate, assign
  materials and tools, paint support enforcers or blockers, and inspect layers before printing.
- **Controls supported printers.** Monitor temperatures and progress, browse files, use cameras and
  macros, and start, pause, resume, or cancel prints with explicit confirmation.
- **Works away from home.** Connect over Wi-Fi or mobile data through a private route you configure,
  such as Tailscale. Printer traffic goes directly to the address you provide; it is not relayed
  through a Nozzle It All server. See the [remote-access guide](https://nozzleitall.com/docs/#away-from-home).
- **Finds models.** Browse free models from MyMiniFactory and retain creator attribution in projects.
- **Keeps watch.** Optional background alerts report completed, failed, paused, and offline printers.

Printer-control integrations currently cover Klipper/Moonraker, Snapmaker U1, Bambu Lab LAN mode,
OctoPrint, and PrusaLink. Klipper/Moonraker and Snapmaker U1 have real-hardware evidence; other
integrations have differing verification levels documented on the
[printers page](https://nozzleitall.com/printers/).

## Local-first by design

Core slicing and local printer control do not require an account, subscription, analytics service,
or Nozzle-hosted backend. Printer addresses and credentials stay on the user's device.

Mutating requests are not automatically replayed: if a response is lost, the result is treated as
unknown until the printer is checked. Potentially dangerous actions remain capability-gated and
require confirmation.

## Build and validate

Requirements:

- JDK 17
- Android SDK 36
- Android NDK `27.1.12297006`
- The prebuilt Android dependency prefix (`orcaslicer-android-engine/deps/install/arm64-v8a`) for the native slicing
  target; `engine/fork/android/prepare_engine_root.sh` combines it with the pinned engine source

Run the standard local checks from the repository root:

```bash
bash scripts/validate.sh
```

Run connected UI and native-engine tests against an explicitly selected Android device:

```bash
ANDROID_SERIAL=<device-id> ./gradlew connectedDebugAndroidTest
```

The slicing engine is [nozzle-engine](https://github.com/James-Jennison/nozzle-engine), pinned by commit in
[`engine/fork/ENGINE_PIN.json`](engine/fork/ENGINE_PIN.json); its Android dependency archives are pinned in
[`engine/fork/android/DEPENDENCIES.json`](engine/fork/android/DEPENDENCIES.json). A checkout without the prepared engine can run
the JVM checks but cannot build the native `slic3rengine` target. CI uses a provisioned self-hosted
runner for the full native build; real-device suites run separately on AWS Device Farm.

## Project documentation

- [`docs/CONSUMER_SLICER_PLAN.md`](docs/CONSUMER_SLICER_PLAN.md) — architecture and platform plan
- [`docs/WORK_ORDER.md`](docs/WORK_ORDER.md) — current implementation and verification queue
- [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) — acceptance index
- [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md) — physical-printer testing procedure
- [`docs/testgrid/README.md`](docs/testgrid/README.md) — Nozzle Test Grid: Test Mode, evidence bundles, compatibility matrix
- [`docs/RELEASE.md`](docs/RELEASE.md) — release process
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — third-party components and licenses

## License

Nozzle It All is licensed under the [GNU AGPL-3.0-or-later](LICENSE). The included slicing engine
is also AGPL-licensed; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for details.
