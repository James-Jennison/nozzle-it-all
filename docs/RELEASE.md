# Release engineering (Phase 9g)

## Signing
The release APK is signed only when these are set (environment or `~/.gradle/gradle.properties`; never committed):
`NOZZLE_KEYSTORE` (path), `NOZZLE_KEYSTORE_PASSWORD`, `NOZZLE_KEY_ALIAS`, `NOZZLE_KEY_PASSWORD`.
Without them `./gradlew assembleRelease` still succeeds and produces `app-release-unsigned.apk`.
`*.jks` / `*.keystore` are git-ignored. The signing identity (and whether to use Play App Signing) is an owner decision
that is still open. In CI the keystore comes from the base64 secret `NOZZLE_KEYSTORE_B64` plus the three password/alias secrets.

## Minified-build smoke test
R8 shrinks and obfuscates every dependency (Tink, Netty/HiveMQ, BouncyCastle, jsch, Room, Glance, OkHttp), and the
instrumented suite cannot run against a fully obfuscated app. The `releaseSmoke` build type applies the release recipe
but keeps the app's own classes and Kotlin (`app/proguard-smoke.pro`, `app/proguard-smoke-test.pro`), debug-signed, never shipped:

```bash
./gradlew assembleReleaseSmoke assembleAndroidTest -PnozzleSmoke
adb install -r app/build/outputs/apk/releaseSmoke/app-releaseSmoke.apk
adb install -r app/build/outputs/apk/androidTest/releaseSmoke/app-releaseSmoke-androidTest.apk
adb shell am instrument -w net.jamesjennison.klippercompanion.test/androidx.test.runner.AndroidJUnitRunner
```
Reinstall the debug APKs afterwards (`assembleDebug assembleDebugAndroidTest`, then `adb install -r`). Never `adb uninstall`:
it deletes the saved printers.

## SBOM
`./gradlew generateSbom` writes `app/build/sbom/nozzle-it-all.cdx.json` (CycloneDX 1.5): the resolved release runtime
dependencies with SHA-256 of each artifact, the pinned OrcaSlicer commit and patch hash, and the native dependency
archives. Note the archive list is everything present in the engine's `deps/src`, including alternates that may not be linked
(for example CGAL 6.0.1 next to 5.6.3); trim `engine/ENGINE_PIN.json` if that matters for a submission.

## Engine pinning
`engine/ENGINE_PIN.json` pins the upstream OrcaSlicer commit (`824b216f`, an unreleased nightly), the patch applied on top
(`engine/android-headless-engine.patch`, three CMake files) and the SHA-256 of every dependency source archive.
`python3 scripts/engine_pin.py verify` fails on any drift; `update` re-pins after a deliberate engine change. CI runs `verify`.

## Provenance
The `release` job in `.github/workflows/ci.yml` (tag `v*`) builds the release APK and SBOM and attaches GitHub build-provenance
attestations. It has not run yet: it needs a tag push and the signing secrets on the self-hosted runner.

## Smoke results (2026-09-23, Razr 2026)
- Whole instrumented suite (238 tests) against `releaseSmoke`: all pass. Two harness details: test code calls library APIs by
  their real names (`Room.inMemoryDatabaseBuilder`, Compose test framework internals), so `-dontobfuscate` and a few keeps are set.
- The fully minified and obfuscated `assembleRelease` APK (debug-signed locally with apksigner) was installed over the debug
  build and driven by hand: launch, Files > Projects, create a calibration project (Room, generated mesh, native mesh load),
  Slice (JNI under obfuscation, cancel/progress code, calibration post-processing: 255 layers, Z 51.0 mm), G-code preview and
  stats. No crash, no UnsatisfiedLinkError / NoSuchMethodError / ClassNotFoundException in logcat.
- **Not exercised under the shipped minification:** Bambu MQTT/TLS and FTPS (needs a Bambu printer), jsch/BouncyCastle OpenPGP
  (U1 enrolment), the Glance home-screen widget, and Tink writes beyond the startup read of saved printers.
