# Elegoo Centauri Carbon on OpenCentauri COSMOS

Suite `cosmos-centauri-carbon` version 1.0.0 (reference), for Elegoo Centauri Carbon on `cosmos` firmware
through the `android-moonraker` adapter. Needs Nozzle It All 0.1.0 or newer. Suite digest `8848f927b8da9a8a722d72270d25b9d24a312945e845edd05b72ae52d14c44be`.

Reference suite for a Centauri Carbon converted to OpenCentauri COSMOS (Klipper/Moonraker). COSMOS is its own firmware target: nothing here is evidence for Elegoo's stock firmware or OpenCentauri-patched stock firmware. CANVAS (through AFC) is graded separately and only when detected.

A result from this suite is **not** evidence for: `elegoo-stock`, `opencentauri-patched`.

## Before you start

- Run this only on a printer you own, with you standing next to it for anything at level 3 or 4.
- Test Mode shows every action and the target printer before it asks you to approve it. Approve one step at a time; decline anything you are not sure about.
- If Test Mode says a result is **unknown**, stop. Look at the printer, then record what you found. Nothing is sent again automatically.
- Emergency stop stays on the printer's own screen and on Nozzle's dashboard. Interrupting a test in Test Mode stops further steps; it does not stop a running print.
- Highest safety level in this suite: Level 4 · physical printing. You choose the highest level to allow when you start.

## 1. Identify the printer and its firmware (`identity`)

Category: **Monitoring** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

Reads the firmware identity live and classifies it with the same rules as network discovery. A different firmware family stops the suite's grades from applying.

Steps:
1. Nozzle reads the firmware identity (read-only).
2. Nozzle checks the declared capabilities.

Expected:
- Firmware reads as this suite's firmware family

## 2. Read status and temperatures (`telemetry`)

Category: **Monitoring** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

Preconditions:
- [ ] You can see the printer's own screen.

Steps:
1. Nozzle reads status and temperatures (read-only).
2. Nozzle lists the printer's G-code files.
3. **You answer:** Do the temperatures Nozzle reads (shown below) match the printer's own screen, within 2 °C? (yes/no)

Expected:
- Status is ready
- Nozzle and bed temperatures are reported and match the printer's screen

## 3. Camera stream and snapshot (`camera`)

Category: **Monitoring** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

Only the camera's kind, the snapshot's size and its hash are recorded; no image or URL leaves the device.

Steps:
1. Nozzle lists cameras (URLs are not recorded).
2. Nozzle takes one camera snapshot and records only its size and hash.
3. **You answer:** Does the camera picture below show the printer, and change when you tap Refresh? (yes/no)

Expected:
- At least one camera
- A snapshot decodes as an image
- The live view updates

## 4. Slice the single-material acceptance model (`slice-single`)

Category: **Slicing** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

Slices the standard acceptance model on this device with the bundled profile and checks the result: it extrudes, stays inside the printable area, is centred, and uses this firmware's own start/end G-code. On this printer slicing a COSMOS profile first reads the live firmware (read-only) to confirm the COSMOS profile generation, so this test is level 1.

Steps:
1. Nozzle checks the acceptance model `nozzle-acceptance-v1` against its published SHA-256.
2. Nozzle slices `nozzle-acceptance-v1` with the bundled `elegoo_centauri_carbon_cosmos` profile on this device. Files: nozzle-acceptance-v1.stl (f91f4bad8515…).
3. Check the G-code: Nozzle checks the sliced G-code: non empty, no stock elegoo commands, requires macro, requires macro, within bed, centered.

Expected:
- G-code is produced
- No command from another firmware appears
- The model is centred within 5 mm and inside the bed

## 5. The COSMOS profile matches the live firmware (`profile-match`)

Category: **Slicing** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

The bundled COSMOS profile is the 26.07.0+ generation; the live COSMOS version must need exactly that. An older COSMOS needs the legacy profile and is refused.

Steps:
1. Nozzle checks that profile `elegoo_centauri_carbon_cosmos` suits this printer's live firmware.

## 6. The Elegoo stock-firmware profile is refused (`stock-profile-refused`)

Category: **Slicing** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

The stock-firmware profile's start G-code calls M729, which COSMOS emergency-stops on. Nozzle must refuse to use it for a Moonraker printer.

Steps:
1. Nozzle checks that profile `elegoo_centauri_carbon_canvas` suits this printer's live firmware.

## 7. Refuse files sliced for Elegoo stock firmware (`upload-guard`)

Category: **File transfer** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

Hands two known-unsafe files to this printer's real upload path and expects both to be refused before any request is made. Nothing is sent.

Steps:
1. M729 file: Nozzle hands the known-unsafe fixture `stock-elegoo-m729.gcode` to the upload path and expects it to be refused before anything is sent.
2. M8213 file: Nozzle hands the known-unsafe fixture `stock-elegoo-m8213.gcode` to the upload path and expects it to be refused before anything is sent.

Expected:
- Both files are refused before sending

## 8. Upload, verify and delete a file (`transfer`)

Category: **File transfer** · Scope: Single material · Level 2 · reversible file operations · changes printer state · needs your approval per step
Runs only if `slice-single` passed.

Uploads the G-code sliced earlier in this run under a unique name, verifies the printer holds exactly those bytes (SHA-256), then deletes that one file and verifies it is gone. Nothing prints.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)

Steps:
1. **Approve:** Upload the sliced acceptance model: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. Nozzle lists the printer's G-code files.
3. **Approve:** Delete the uploaded file: Delete the file this run uploaded (only that file).

Expected:
- The file arrives intact
- It is listed
- It is deleted and no longer present

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded file if it is still there: Delete the file this run uploaded (only that file).

## 9. Heaters, homing and a small move (`controls-idle`)

Category: **Controls** · Scope: Single material · Level 3 · supervised controls · changes printer state · needs your approval per step

Low, non-printing temperatures and one small move. Each command is shown and approved one at a time; the printer is re-read before each and nothing is sent unless it is idle.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] Nothing is on the bed or near the toolhead, and the bed can move freely.

Steps:
1. **Approve:** Heat the active nozzle to 60 °C.
2. Nozzle watches status until `heater_reaches` (nozzle 60 °C), up to 300 s.
3. **You answer:** Does the printer's own screen show a 60 °C nozzle target? (yes/no)
4. **Approve:** Turn the nozzle heater off.
5. **Approve:** Heat the bed to 40 °C.
6. Nozzle watches status until `heater_reaches` (bed 40 °C), up to 900 s.
7. **Approve:** Turn the bed heater off.
8. **Approve:** Home all axes (G28). The toolhead and bed will move.
9. **You answer:** Did every axis home normally, with no grinding, skipping or collision? (yes/no)
10. **Approve:** Move the Z axis by +5.0 mm (relative move, 3000 mm/min).
11. **You answer:** Did the bed or toolhead move about 5 mm in Z, in the expected direction? (yes/no)

Expected:
- Nozzle reaches 60 °C and turns off
- Bed reaches 40 °C and turns off
- Homing completes
- Z moves 5 mm

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Make sure the nozzle heater is off: Turn the nozzle heater off.
- **Approve:** Make sure the bed heater is off: Turn the bed heater off.

Time limit: 60 min; if exceeded the test is recorded as fail.

## 10. Print the acceptance model (single material) (`print-single`)

Category: **Physical printing** · Scope: Single material · Level 4 · physical printing · changes printer state · needs your approval per step
Runs only if `slice-single` passed.

A complete physical print of the acceptance model with the G-code sliced in this run: first layer, placement, dimensions, bridging, retraction and surface.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] A clean, empty build plate is installed.
- [ ] You can watch the printer for the whole print.

Steps:
1. **Approve:** Upload the sliced acceptance model: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. **Approve:** Start the acceptance print: Start printing the uploaded file. The printer heats, moves and extrudes.
3. Nozzle watches status until `printing`, up to 900 s.
4. Nozzle watches status until `progress_increases`, up to 2400 s.
5. **You answer:** Judge the first layer: even lines, stuck down everywhere, no gaps, no scraping. (pass/partial/fail)
6. **You attach:** Photo of the first layer (evidence `first-layer`).
7. Nozzle watches status until `complete`, up to 14400 s.
8. **You answer:** Did the print finish without you having to intervene? (yes/no)
9. **You answer:** Where on the bed did the model print? (centre / off centre / partly off the bed)
10. **You answer:** Measure the tower's outside width along X with calipers. (number in mm; accepted 19.75 to 20.25)
11. **You answer:** Measure the tower's outside width along Y. (number in mm; accepted 19.75 to 20.25)
12. **You answer:** Measure the square hole's width. (number in mm; accepted 7.7 to 8.3)
13. **You answer:** Measure the tower's total height, including the base plate. (number in mm; accepted 10.35 to 10.85)
14. **You answer:** Measure the thin wall's thickness. (number in mm; accepted 1.0 to 1.4)
15. **You answer:** Look under the bridge deck: straight strands across the 18 mm gap, no drooping or failed strands? (pass/partial/fail)
16. **You attach:** Photo of the bridge underside (evidence `bridge`).
17. **You answer:** Between the two tall pillars: no strings, or only a few fine wisps that brush off? (pass/partial/fail)
18. **You answer:** Tower walls: even layers, no blobs, zits, gaps or layer shifts? (pass/partial/fail)
19. **You attach:** Photo of the finished print from above (evidence `top`).

Expected:
- The print completes
- First layer adheres evenly
- Tower 20.0 ± 0.25 mm, hole 8.0 ± 0.3 mm, height 10.6 ± 0.25 mm, wall 1.2 ± 0.2 mm
- Bridge holds across 18 mm
- Little or no stringing
- Clean walls

Evidence to collect:
- photo: The first layer, from above, while or just after it prints
- photo: The finished print from above
- photo: The underside of the bridge deck

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded print file: Delete the file this run uploaded (only that file).

Time limit: 360 min; if exceeded the test is recorded as fail.

## 11. Pause, resume and cancel a print (`print-controls`)

Category: **Controls** · Scope: Single material · Level 4 · physical printing · changes printer state · needs your approval per step
Runs only if `slice-single` passed.

Starts the acceptance print only to exercise pause, resume and cancel. The partial print is discarded.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] A clean, empty build plate is installed.
- [ ] You can watch the printer for the whole print.

Steps:
1. **Approve:** Upload the sliced acceptance model: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. **Approve:** Start a print that will be cancelled: Start printing the uploaded file. The printer heats, moves and extrudes.
3. Nozzle watches status until `printing`, up to 900 s.
4. Nozzle watches status until `progress_increases`, up to 2400 s.
5. **Approve:** Pause the current print.
6. Nozzle watches status until `paused`, up to 180 s.
7. **You answer:** Did the printer stop extruding and move the toolhead away from the print? (yes/no)
8. **Approve:** Resume the paused print. The printer will move and extrude.
9. Nozzle watches status until `printing`, up to 180 s.
10. **You answer:** Did printing continue where it stopped, without a gap or blob? (pass/partial/fail)
11. **Approve:** Cancel the current print. This cannot be undone.
12. Nozzle watches status until `idle`, up to 600 s.
13. **You answer:** Did the printer stop the print and begin turning its heaters off? (yes/no)

Expected:
- Pause parks the toolhead
- Resume continues cleanly
- Cancel stops the print

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded print file: Delete the file this run uploaded (only that file).
- **Approve:** Make sure the nozzle heater is off: Turn the nozzle heater off.
- **Approve:** Make sure the bed heater is off: Turn the bed heater off.

Time limit: 120 min; if exceeded the test is recorded as fail.

## 12. Read the material slots (`multi-lanes`)

Category: **Monitoring** · Scope: Multi-material / tool changing · Level 1 · read-only discovery and telemetry · does not change printer state
Needs detected hardware: canvas. Skipped otherwise.

Read-only view of the CANVAS lanes (through AFC). Graded separately from single-material monitoring.

Steps:
1. Nozzle reads the material slots (read-only).
2. **You answer:** Do the four CANVAS lanes Nozzle reads (shown below) match what is loaded? (yes/no)

Expected:
- Every slot is reported
- They match what is loaded

## 13. Slice the multi-material acceptance model (`multi-slice`)

Category: **Slicing** · Scope: Multi-material / tool changing · Level 1 · read-only discovery and telemetry · does not change printer state
Needs detected hardware: canvas. Skipped otherwise.

Two-part model, one part per tool or lane.

Steps:
1. Nozzle checks the acceptance model `nozzle-acceptance-mm-v1` against its published SHA-256.
2. Nozzle slices `nozzle-acceptance-mm-v1` with the bundled `elegoo_centauri_carbon_cosmos_afc` profile on this device. Files: nozzle-acceptance-mm-v1-a.stl (c1338f179a8b…), nozzle-acceptance-mm-v1-b.stl (324aa51655b0…).
3. Check the G-code: Nozzle checks the sliced G-code: non empty, no stock elegoo commands, uses tools, max tool index, within bed, requires macro.

Expected:
- G-code selects two tools
- No stock-firmware commands

## 14. Print the multi-material acceptance model (`multi-print`)

Category: **Physical printing** · Scope: Multi-material / tool changing · Level 4 · physical printing · changes printer state · needs your approval per step
Runs only if `multi-slice` passed.
Needs detected hardware: canvas. Skipped otherwise.

Kept apart from single-material acceptance: this grades material and tool changes only.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] A clean, empty build plate is installed.
- [ ] You can watch the printer for the whole print.
- [ ] Two different colours are loaded in the first two tools or lanes.

Steps:
1. **Approve:** Upload the multi-material G-code: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. **Approve:** Start the multi-material print: Start printing the uploaded file. The printer heats, moves and extrudes.
3. Nozzle watches status until `printing`, up to 1200 s.
4. Nozzle watches status until `complete`, up to 14400 s.
5. **You answer:** Did every tool or material change happen, with the right material in each stripe? (yes/no)
6. **You answer:** Colour bleed at the stripe boundaries: (pass/partial/fail)
7. **You attach:** Photo of the finished stripes (evidence `stripes`).

Expected:
- All changes happen
- Each stripe is the right material

Evidence to collect:
- photo: The finished stripes from above

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded print file: Delete the file this run uploaded (only that file).

Time limit: 300 min; if exceeded the test is recorded as fail.

## Afterwards

Open **Review evidence**, read every file Test Mode will export, then **Export**. Send the bundle to the maintainers as agreed.
The bundle contains no printer address, credentials, camera URLs, hostnames, file paths or photo metadata; check the preview anyway.
