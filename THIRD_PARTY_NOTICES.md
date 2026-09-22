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

## OrcaSlicer and oneTBB (on-device slicing, WO-13, in progress)

The on-device slicing engine (not yet feature-complete — see
`docs/WORK_ORDER.md`'s WO-13 entry for current status) is built from two
vendored, pinned git submodules under `third_party/`, not a prebuilt binary:

- [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer), licensed
  AGPL-3.0-or-later, pinned to the `v2.4.2` release tag (commit
  `8500fcdccaa10b5099ac20d252af3a7c560046f1`). This is upstream
  `OrcaSlicer/OrcaSlicer` directly — **not** the Snapmaker `u1-slicer-for-
  android` fork this project investigated and rejected (see WO-13: unverified
  build provenance, narrower printer-fleet coverage than required). The
  Android build deliberately excludes OrcaSlicer's desktop GUI
  (`SLIC3R_GUI=0`) and, per an explicit owner-accepted scope cut, its
  OpenVDB-, CGAL-, GMP-, MPFR- and OCCT-dependent features (advanced
  supports, mesh boolean operations, STEP import) — cross-compiling those for
  Android from source is an unsolved problem nobody has published a working
  answer to; even the only real prior art (the Snapmaker fork) disables them
  rather than solving it. Full investigation notes are in the WO-13 plan file
  referenced from `docs/WORK_ORDER.md`.
- [oneTBB](https://github.com/uxlfoundation/oneTBB), licensed Apache-2.0,
  pinned to the `v2021.13.0` release tag (commit
  `1c4c93fc5398c4a1acb3492c02db4699f3048dea`) — the threading engine
  `libslic3r` (OrcaSlicer's core) depends on. Its optional `tbbmalloc`
  component (a libc malloc replacement) is excluded from the Android build;
  it isn't needed here and doesn't link cleanly against Android's bionic libc.

Both submodule commits are recorded in `scripts/artifact-proof.py`'s source
manifest (embedded in the APK and checked by its `verify` step), the same
provenance mechanism already covering this app's own Kotlin source — this is
the concrete answer to the provenance concern that ruled out vendoring a
third-party prebuilt binary in the first place.

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
