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
- **Self-check on the obfuscated release build** (Settings > Run self-check; loopback/local only): initially 3 of 9 FAILED
  (BouncyCastle digests, jsch key pairs, HiveMQ/Netty init) - real shipped-build bugs, fixed with keep rules. All 9 pass now
  (release APK 77.4 -> 85.1 MB). It shows the libraries load and fail cleanly; it does not prove a real Bambu or U1 session works.
- **Not exercised under the shipped minification:** a real Bambu printer session (MQTT/FTPS) and a real U1 SSH enrolment
  (need the printers), and a placed Glance widget (the check only asks Glance for its widget list).

## MyMiniFactory (Discover) credentials
Optional build-time values, supplied like the signing variables (environment or `~/.gradle/gradle.properties`, never committed):
`MMF_API_KEY` (enables browsing) and `MMF_CLIENT_KEY` (enables sign-in for downloads). Without them Discover asks the user for their own
keys and stores them encrypted. Whether one shared key may ship in the APK is a question for MyMiniFactory's terms; the
user-supplied path avoids it. The client must register `nozzleitall://mmf-auth` as its redirect URI.
