# Capability matrix

What each printer adapter declares, in capability schema 2 (`printer-api` `Capabilities`, `SCHEMA_VERSION = 2`).
Screens on every platform show a control only when its flag is set; nothing branches on a vendor name
(`MultiVendorAcceptanceTest.screensFollowCapabilitiesNotVendorNames`). The source of truth is the `CAPABILITIES`
value in each adapter; this table is a reading of them on 2026-09-26.

Hardware status is separate from what the code declares. **UNVERIFIED** means the code has not run against a real
printer of that kind (see [PRINTER_SUPPORT_MATRIX.md](PRINTER_SUPPORT_MATRIX.md)).

| Capability | PAXX U1 (`paxx-lan`) | Klipper (`moonraker`) | Stock U1 (`stock-u1`, optional) | OctoPrint (`octoprint`) | PrusaLink (`prusalink`) | Bambu Lab LAN (`bambu-lan`) | Export only |
|---|---|---|---|---|---|---|---|
| Hardware status | Verified | Verified | UNVERIFIED | UNVERIFIED (real server, virtual printer) | UNVERIFIED | UNVERIFIED (emulator) | n/a |
| upload_job (upload, start separately) | ✓ | ✓ | ✓ | – | – | – | – |
| upload_and_start (one confirmed request) | – | – | – | ✓ | ✓ | ✓ | – |
| start_print (a file already on the printer) | ✓ | ✓ | ✓ | ✓ | ✓ | – | – |
| pause / resume / cancel | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | – |
| temperatures (set heater targets) | ✓ | ✓ | ✓ | – | – | – | – |
| motion (home, jog) | ✓ | ✓ | ✓ | – | – | – | – |
| camera | ✓ | ✓ | – | – | – | A1 and P1 only | – |
| material_state (loaded materials) | ✓ | – | ✓ | – | – | – | – |
| material_edit | ✓ | – | – | – | – | – | – |
| load_unload (printer macros) | ✓ | ✓ | – | – | – | – | – |
| multi_material | ✓ | – | ✓ | – | – | – | – |
| toolhead_state | ✓ | ✓ | ✓ | – | – | – | – |
| bed_mesh | ✓ (declared, no screen yet) | ✓ (declared, no screen yet) | – | – | – | – | – |
| files | ✓ (declared, no screen yet) | ✓ (declared, no screen yet) | ✓ | ✓ (declared, no screen yet) | ✓ (declared, no screen yet) | – | – |
| job_history | ✓ (declared, no screen yet) | ✓ (declared, no screen yet) | ✓ | – | – | – | – |
| calibration | ✓ (declared, no screen yet) | – | – | – | – | – | – |
| local_connection | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | – |
| remote_connection (private network such as Tailscale) | ✓ | ✓ | ✓ | ✓ | ✓ | – (LAN mode only) | – |
| vendor_cloud | – | – | ✓ (opt-in, isolated helper process) | – | – | – | – |
| requires_vendor_account | – | – | – | – | – | – | – |
| firmware_updates | – | – | – | – | – | – | – |
| accepted_outputs | gcode | gcode | gcode | gcode | gcode, bgcode | gcode.3mf | whatever the profile produces |
| vendor_extensions | `snapmaker.full-spectrum`, `snapmaker.multi-ace` | – | `snapmaker.full-spectrum` | – | – | – | – |

Elegoo (`elegoo-lan`, added 2026-09-27; hardware status UNVERIFIED, tested against in-process fakes only):

| Capability | Centauri Carbon, stock firmware (SDCP) | Centauri Carbon 2 (MQTT) |
|---|---|---|
| upload_job, start_print (with a CANVAS slot map) | ✓ | ✓ |
| pause / resume | ✓ / ✓ | – / – (no LAN resume in elegoo-link) |
| cancel | ✓ | ✓ |
| material_state, multi_material, toolhead_state (CANVAS slots, read only) | ✓ | ✓ |
| local_connection | ✓ | ✓ |
| everything else (temperatures, motion, camera, material_edit, load_unload, files, remote_connection, vendor_cloud) | – | – |
| accepted_outputs | gcode | gcode |

Notes:

- **Temperatures** means setting heater targets. Temperature readings are shown whenever a printer's status reports
  them, whatever this flag says. OctoPrint, PrusaLink and Bambu report temperatures but Nozzle can't yet set them there,
  so they don't declare it. `TransportSession` refuses to be created with control flags it can't perform, so this
  can't regress into dead controls.
- **Bambu Lab** printers take a sliced `.gcode.3mf`. Nozzle's engines write plain G-code today, so Desktop and the Web App
  don't offer to send to a Bambu printer; they explain why. Slicing for Bambu profiles works.
- **PrusaLink** accepts `.bgcode`, which Nozzle doesn't produce yet; it sends plain G-code, which PrusaLink also accepts.
- **Export only** printers have a profile and no connection (route `none`). They are never polled.
- **Web App** talks to Moonraker printers (PAXX U1, Stock U1, Klipper) directly with the Moonraker capabilities
  (`DIRECT_MOONRAKER` in `web/src/printers/model.ts`: upload, start, pause, resume, cancel, temperatures, motion, camera,
  toolhead state, files). It reaches every other family through the local connector, which reports the Desktop adapter's
  capabilities (`GET /v1/printers/{id}/capabilities`).
- Flags marked "declared, no screen yet" describe what the printer's API can do; no platform offers a control for them
  yet, so none is dead.
