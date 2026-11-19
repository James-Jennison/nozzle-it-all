# Nozzle It All Desktop

A desktop slicer and printer workshop, built on the OrcaSlicer 2.5.0 engine.

**Status: in development. There is no release yet.** Builds from this repository are for testing.

Nozzle It All Desktop is an independent fork of [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer). It is not OrcaSlicer, and it is not endorsed by the OrcaSlicer project or by any printer vendor. For OrcaSlicer itself, its releases, documentation and community, go to the OrcaSlicer repository.

## What it adds to OrcaSlicer

**Sending a print**

- **A checked send.** The printer is read before a print is sent, the uploaded file is read back and compared, and each print is started once. A Start whose answer is lost is never repeated; the printer's own state settles what happened. This works for the Snapmaker U1 and for the Elegoo Centauri Carbon running COSMOS.
- **One print to several printers**, with the same check for each.
- **A queue of prints** that you release one at a time. Nothing in it starts on its own.

**Printers**

- **One Printer tab** with a live monitor, the printer's own web interface, and the history of the jobs sent from the app.
- **A Printers view** of every saved printer with its state and live camera. Opening a printer there only shows it; it does not change the printer you are slicing for.
- **Adding a printer** from a search of your own network.
- **Printer controls that ask before they move or heat**, and a Resume that reads the printer first.
- **A notice** when a print ends or pauses.
- **Job history** kept apart from your own verdict on each print, with printing a job again as a new job.

**Filament**

- **A filament sync with a review step**: you choose which toolhead or slot each project filament takes before anything in the project changes. It reads a Snapmaker U1's toolheads, and the lanes of an AFC filament changer such as a CANVAS on COSMOS.
- **A search of the 3dfilamentprofiles.com catalogue**, with your own key.
- **A memory of the preset you chose for a tagged spool**, as the printer's own reader reports it.
- **A list of your spools** with an estimate of what is left on each.

**Models and profiles**

- **AI auto-paint**: a draft of colours for a model, made by an AI service that you set up yourself in Preferences (a command on your computer, or a server that speaks the OpenAI protocol). You see the pictures and the question before they are sent, and the draft is yours to keep, change or discard. No service is contacted unless you set one up and press Send.
- **Profiles for the Elegoo Centauri Carbon running COSMOS**, with and without a CANVAS driven by AFC.
- **Importing a preset bundle from a link.**

Everything else, including slicing, profiles for other printers and the Prepare and Preview tabs, is OrcaSlicer's. OrcaSlicer's cloud account and its links to the Orca wiki are hidden in this build.

In development: more AI assistance, model discovery, and a phone companion.

## Printers

| Printer | What works here |
|---|---|
| Snapmaker U1 | Checked send, monitor, controls, history, filament sync |
| Elegoo Centauri Carbon on COSMOS | Profiles, checked send, monitor, controls, filament sync from a CANVAS |
| Other Klipper (Moonraker) printers | Monitor and controls as far as the printer reports them; OrcaSlicer's upload |
| Everything else OrcaSlicer supports | As in OrcaSlicer |

## Building

The build is OrcaSlicer's, with one switch: configure with `-DNOZZLE_BRANDING=ON` for Nozzle It All Desktop, or leave it off for a build that names itself OrcaSlicer. See `AGENTS.md` for the build host and validation rules this repository uses, and OrcaSlicer's documentation for dependencies and platforms. Only Linux builds are tested at present.

## Licence and lineage

Copyright (C) 2026 Nozzle It All contributors. Licensed under the GNU Affero General Public License, version 3 or later; see `LICENSE.txt`.

Built on OrcaSlicer, Bambu Studio, PrusaSlicer, Slic3r, SuperSlicer and CuraEngine, and the work of their contributors. Their copyright notices remain in the source files. Nozzle It All is independently built and is not endorsed by those projects or their vendors.
