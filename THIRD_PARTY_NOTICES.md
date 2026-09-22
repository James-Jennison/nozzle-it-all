# Third-Party Notices

Nozzle It All incorporates components ported from other open-source projects,
licensed separately from this app's own code. This file satisfies attribution
requirements for those components and is updated as further components are
incorporated.

## Helix

Portions of this app's Snapmaker U1/PAXX- and Bambu Lab-specific code are adapted from
**[Helix](https://github.com/FatBoy721/Helix)** by FatBoy721, licensed under
AGPL-3.0-or-later. Helix is a separate app and codebase; this project is not a
fork of it. Ported files carry a header comment noting their origin.

Incorporated so far:

- `Bespok3dClient.kt`, `Bespok3dU1Preflight.kt`, `Bespok3dU1Enrollment.kt`,
  `Bespok3dSsh.kt`, `Bespok3dSshExitStatus.kt`, `Bespok3dBootstrapPackages.kt` —
  adapted from Helix's `android/app/src/main/java/org/crabcore/u1control/bespok3d/`
  (HTTPS client, SSH transport and SSH-based probe/enrollment flow for the
  Bespok3d daemon running on a Snapmaker U1, and OpenPGP verification of its
  signed plugin/bootstrap packages). `Bespok3dBootstrapPackages.kt`'s bundle
  format was changed from Helix's own (see that file's header) so it can be
  rebuilt against current Bespok3d releases rather than staying pinned to
  whichever pairing Helix happened to ship.

- `BambuTrust.kt`, `BambuMqttConnection.kt`, `BambuStatusProbe.kt`,
  `BambuFtpsClient.kt`, `BambuPrintProtocol.kt`, `BambuChamberCamera.kt` —
  adapted from Helix's `android/app/src/main/java/org/crabcore/u1control/bambu/`
  (serial-pinned TLS trust, the MQTT LAN transport and bounded status probe,
  the implicit-TLS FTPS upload client, `project_file` command construction and
  acknowledgement parsing, and the port-6000 chamber-camera stream re-served as
  loopback MJPEG). `BambuPrintProtocol.kt` drops Helix's AMS/multi-material lane
  mapping (see that file's header): this app prints single-material from the
  external spool only. Helix's React Native bridge shims and its `.gcode.3mf`
  artifact builder were not ported — this app has no slicer and uploads an
  already-sliced archive as-is.

## Bespok3d daemon and Snapmaker U1 jinni (bundled binaries)

`app/src/main/assets/bespok3d/bootstrap.zip` bundles the **official, signed
release artifacts** of the Bespok3d daemon and its Snapmaker U1 adapter
("jinni"), both AGPL-3.0-or-later, both published by the Bespok3d project
itself (not authored, modified, or built by this project):

- [`bespok3d-daemon` v0.14.0](https://github.com/Bespok3d/daemon/releases/tag/bespok3d-daemon-v0.14.0)
- [`bespok3d-jinni-snapmaker-u1` v0.1.11](https://github.com/Bespok3d/adapters/releases/tag/bespok3d-jinni-snapmaker-u1-v0.1.11)

These are downloaded directly from Bespok3d's own GitHub releases and
independently re-verified (OpenPGP signature over each package's manifest,
plus every declared file's own sha256) against Bespok3d's published signing
key before being committed — see `scripts/build_bespok3d_bootstrap.py`, which
performs that verification and is how this bundle is rebuilt for future
versions. `Bespok3dBootstrapPackages.kt` repeats the same verification again
at runtime, on-device, before either package's contents are used.

## Bespok3d

The Bespok3d client code above talks to the Bespok3d daemon running on the
printer itself; no Bespok3d code is bundled in this app. The daemon and its
Snapmaker U1 adapter are separate AGPL-3.0-or-later projects:

- [Bespok3d daemon](https://github.com/Bespok3d/daemon)
- [Bespok3d adapters](https://github.com/Bespok3d/adapters)

Copyright (C) 2026 unlucio and the Bespok3d contributors. Bespok3d is a project
of the Bespok3d Organisation, which is not a legal entity; copyright is held by
its individual authors. This app is not affiliated with or endorsed by
Bespok3d.

## OrcaSlicer (on-device slicing, WO-13, in progress)

The on-device slicing engine (not yet feature-complete — see
`docs/WORK_ORDER.md`'s WO-13 entry for current status) is the owner's own
separate **orcaslicer-android-engine** project (local to this machine, no git
remote), which cross-compiles upstream `OrcaSlicer/OrcaSlicer` (AGPL-3.0-or-
later) for Android arm64-v8a — the *full* engine, including Boost, CGAL, GMP,
MPFR, OpenVDB, and OCCT (OpenCASCADE), built entirely from source. An earlier
approach in this project vendored a feature-reduced OrcaSlicer+oneTBB build as
pinned git submodules directly under `third_party/`, accepting a scope cut
(no OpenVDB/CGAL/OCCT) because cross-compiling those from source looked like
an unsolved problem — that approach is superseded now that the owner's
already-working, full-featured engine was found. `app/src/main/cpp/
CMakeLists.txt` builds directly against that project's patched OrcaSlicer
checkout and prebuilt dependency prefix (`ORCASLICER_ENGINE_ROOT`, an
absolute local path); `app/src/main/cpp/bridge/` (`slic3r_engine.cpp/hpp`,
`slic3r_jni.cpp`, `nanosvg_impl.cpp`, `cli_test.cpp`) is copied from that
project's own JNI bridge, with one local addition (`nativeSliceFile` forwards
direct config overrides, not just profile-file paths — see `slic3r_jni.cpp`'s
own header comment).

This build depends on a machine-local path and will not work on a checkout
that doesn't have `orcaslicer-android-engine` at that same location — there
is no public/portable alternative yet. Because that project has no git
remote, its own commit history (not a public URL) is the only provenance
record for the exact source state a given build used; `scripts/
artifact-proof.py`'s manifest still covers everything under this app's own
`app/src/main/` (including the copied bridge files and this `CMakeLists.txt`),
the same mechanism that ruled out vendoring the Snapmaker `u1-slicer-for-
android` project's unattested prebuilt binary in the first place.

**Real bug found and fixed during integration (2026-09-21):** `nativeSliceFile`
threw `"Some EditGcodeDialog defs were not specified properly"` only when
built via this app's own (Gradle-default Debug) native build, despite an
identical config to the engine project's own already-verified standalone CLI
tool. Root cause, found via temporary `__android_log_print` diagnostics added
to the vendored `GCode.cpp` (reverted once identified): `GCode.hpp` forces a
placeholder-validation check on whenever `NDEBUG` is undefined (Debug builds),
and that check validates custom-gcode keys against a static allow-list
(`PrintConfig.cpp`) that's stale against what real gcode processing passes at
runtime — a genuine upstream data inconsistency, not anything Android- or
JNI-specific. The verified-working CLI tool was built with
`CMAKE_BUILD_TYPE=Release` (which defines `NDEBUG`), sidestepping it. Fix:
`app/build.gradle.kts`'s `externalNativeBuild.cmake.arguments` now forces
`-DCMAKE_BUILD_TYPE=Release` for the native build regardless of the Gradle
Debug/Release variant, matching the CLI tool's proven configuration exactly.

## Fonts

The app's typography (`res/font/`) bundles three typefaces from Google Fonts'
own mirror of their upstream sources, all SIL Open Font License 1.1:

- [Space Grotesk](https://github.com/google/fonts/tree/main/ofl/spacegrotesk) —
  Copyright 2019 The Space Grotesk Project Authors.
- [IBM Plex Sans](https://github.com/google/fonts/tree/main/ofl/ibmplexsans) and
  [IBM Plex Mono](https://github.com/google/fonts/tree/main/ofl/ibmplexmono) —
  Copyright 2017-2019 IBM Corp., with Reserved Font Name "Plex".

Full OFL 1.1 license text for all three is combined at `third_party_licenses/OFL.txt`
(kept out of `res/font/`, which Android's resource compiler restricts to font
files only).

## Planned future incorporation

On-device slicing's real lineage turned out to be upstream OrcaSlicer (see
above), not the Snapmaker `u1-slicer-for-android`/PrusaSlicer path this note
originally anticipated — corrected 2026-09-21 once that investigation
concluded. A MakerWorld model browser remains genuinely unbuilt, folded into
the same later phase as slicing since it's a model-import source feeding it
rather than standalone. Entries are added here, alongside their own upstream
licenses, as each is actually incorporated — not in advance of the code
landing.
