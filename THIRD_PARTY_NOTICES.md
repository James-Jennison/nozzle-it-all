# Third-Party Notices

Nozzle It All incorporates components ported from other open-source projects,
licensed separately from this app's own code. This file satisfies attribution
requirements for those components and is updated as further components are
incorporated.

## Helix

Portions of this app's Snapmaker U1/PAXX-specific code are adapted from
**[Helix](https://github.com/FatBoy721/Helix)** by FatBoy721, licensed under
AGPL-3.0-or-later. Helix is a separate app and codebase; this project is not a
fork of it. Ported files carry a header comment noting their origin.

Incorporated so far:

- `Bespok3dClient.kt`, `Bespok3dU1Preflight.kt`, `Bespok3dU1Enrollment.kt` —
  adapted from Helix's `android/app/src/main/java/org/crabcore/u1control/bespok3d/`
  (HTTPS client and SSH-based probe/enrollment flow for the Bespok3d daemon
  running on a Snapmaker U1).

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

## Planned future incorporation

Later phases of this port are expected to add further Helix-derived
components (on-device slicing via the u1-slicer-for-android/OrcaSlicer/
PrusaSlicer lineage, Bambu Lab chamber-camera and MQTT support, and a
MakerWorld model browser). Entries for those will be added here, alongside
their own upstream licenses, as each is actually incorporated — not in
advance of the code landing.
