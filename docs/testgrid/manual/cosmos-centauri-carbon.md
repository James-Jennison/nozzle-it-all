# Elegoo Centauri Carbon on OpenCentauri COSMOS

Suite `cosmos-centauri-carbon` version 1.9.0 (reference), for Elegoo Centauri Carbon on `cosmos` firmware
through the `android-moonraker` adapter. Needs Nozzle It All 0.1.0 or newer. Suite digest `a48a93333bc2fbc3c19c537618cd462497f65d47e5b0e05fa424fe092846463a`.

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

Slices the standard acceptance model on this device with the bundled profile and checks the result: it extrudes, stays inside the printable area, is centred, and uses this firmware's own start/end G-code. On this printer slicing a COSMOS profile first reads the live firmware (read-only) to confirm the COSMOS profile generation, so this test is level 1. With CANVAS fitted it slices with the COSMOS AFC profile, which COSMOS requires for any print on a CANVAS printer.

Steps:
1. Nozzle checks the acceptance model `nozzle-acceptance-v1` against its published SHA-256.
2. Nozzle slices `nozzle-acceptance-v1` with the bundled `elegoo_centauri_carbon_cosmos` profile on this device (`elegoo_centauri_carbon_cosmos_afc` when canvas is detected). Files: nozzle-acceptance-v1.stl (f91f4bad8515…).
3. Check the G-code: Nozzle checks the sliced G-code: non empty, no stock elegoo commands, requires macro, requires macro, within bed, centered.

Expected:
- G-code is produced
- No command from another firmware appears
- The model is centred within 5 mm and inside the bed

## 5. The COSMOS profile matches the live firmware (`profile-match`)

Category: **Slicing** · Scope: Single material · Level 1 · read-only discovery and telemetry · does not change printer state

The bundled COSMOS profile is the 26.07.0+ generation; the live COSMOS version must need exactly that. An older COSMOS needs the legacy profile and is refused.

Steps:
1. Nozzle checks that profile `elegoo_centauri_carbon_cosmos` (`elegoo_centauri_carbon_cosmos_afc` when canvas is detected) suits this printer's live firmware.

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
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.

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
10. **You answer:** Tower width along X: across the outside of the 20 mm square tower (ruler or calipers). Target 20.0 mm; passes 19.75 to 20.25. (number in mm; accepted 19.75 to 20.25)
11. **You answer:** Tower width along Y: across the outside of the tower, at right angles to X. Target 20.0 mm; passes 19.75 to 20.25. (number in mm; accepted 19.75 to 20.25)
12. **You answer:** Square hole through the middle of the tower: its width, across the middle, with the calipers' inside jaws. Target 8.0 mm; passes 7.7 to 8.3. (number in mm; accepted 7.7 to 8.3)
13. **You answer:** Total height, with calipers: from the bottom of the base plate to the top of the tower. Target 10.6 mm; passes 10.35 to 10.85. A ruler can't resolve this: without calipers choose Can't observe this. (number in mm; accepted 10.35 to 10.85)
14. **You answer:** Thin wall thickness, with calipers: the single 20 mm-long fin standing on its own, not the tower's walls. Target 1.2 mm; passes 1.0 to 1.4. Without calipers choose Can't observe this. (number in mm; accepted 1.0 to 1.4)
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
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.

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
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.
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
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.

Time limit: 300 min; if exceeded the test is recorded as fail.

## 15. Slice a 50/50 colour-mix swatch (`mix-slice`)

Category: **Slicing** · Scope: Multi-material / tool changing · Level 1 · read-only discovery and telemetry · does not change printer state
Needs detected hardware: canvas. Skipped otherwise.

Slices the colour swatch as a 50/50 mix of tool 1 and tool 2, the same way the project editor's “+ Add 50/50 mix” does: Full Spectrum on a Snapmaker U1, ColorMix on other printers with two or more tools or slots. The G-code must change between the two tools from layer to layer.

Steps:
1. Nozzle checks the acceptance model `nozzle-colour-swatch-v1` against its published SHA-256.
2. Nozzle slices `nozzle-colour-swatch-v1` with the bundled `elegoo_centauri_carbon_cosmos_afc` profile on this device. Every part prints as a 50/50 colour mix of tool 1 and tool 2, with the printer's own mixing system (Full Spectrum on a Snapmaker U1, ColorMix on others). Files: nozzle-colour-swatch-v1.stl (4e56f2cf2916…).
3. Check the G-code: Nozzle checks the sliced G-code: non empty, no stock elegoo commands, uses tools, max tool index, within bed, alternates tools, requires macro.

Expected:
- G-code is produced
- It selects tools 1 and 2 only
- The swatch changes tool on at least 80% of its layers

## 16. Print the colour-mix swatch (`mix-print`)

Category: **Physical printing** · Scope: Multi-material / tool changing · Level 4 · physical printing · changes printer state · needs your approval per step
Runs only if `mix-slice` passed.
Needs detected hardware: canvas. Skipped otherwise.

Prints the swatch sliced in this run. The printer changes tool on about every layer, 30 times: on a printer with one nozzle fed from several slots (CANVAS, AMS, CFS, an MMU) each change also purges, so the print is slower and uses more filament than its size suggests.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] A clean, empty build plate is installed.
- [ ] You can watch the printer for the whole print.
- [ ] Two clearly different colours (for example blue and yellow) are loaded in the first two tools or lanes.

Steps:
1. **Approve:** Upload the colour-mix G-code: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. **Approve:** Start the colour-mix swatch: Start printing the uploaded file. The printer heats, moves and extrudes.
3. Nozzle watches status until `printing`, up to 1200 s.
4. Nozzle watches status until `complete`, up to 14400 s.
5. **You answer:** Did the swatch finish without you having to intervene (no failed tool or filament change)? (yes/no)
6. **You answer:** Look closely at a long side of the swatch: do the two colours take turns, one thin layer each, all the way up? (pass/partial/fail)
7. **You answer:** Now from about an arm's length: do the sides read as one blended colour, rather than two separate colours or stripes? (pass/partial/fail)
8. **You attach:** Photo of a long side of the swatch, in good light (evidence `blend`).

Expected:
- The print completes
- The colours alternate layer by layer
- From arm's length the sides look like one blended colour

Evidence to collect:
- photo: A long side of the finished swatch

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded print file: Delete the file this run uploaded (only that file).
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.

Time limit: 300 min; if exceeded the test is recorded as fail.

## 17. Slice the color reference tiles (2 color mixes) (`color-reference-slice`)

Category: **Slicing** · Scope: Multi-material / tool changing · Level 1 · read-only discovery and telemetry · does not change printer state
Needs detected hardware: canvas. Skipped otherwise.

Slices the first 2 tiles of the color reference model, each tile as its own two-filament color mix (tile 1: tool 1 50% + tool 2 50%; tile 2: tool 3 33% + tool 4 67%), the way the project editor mixes colors: Full Spectrum on a Snapmaker U1, ColorMix on other printers with two or more tools or slots. It uses Nozzle It All's color-mixing print profile for the Centauri Carbon, 0.10mm Color Mixing @Elegoo CC 0.4 nozzle, with its 0.1 mm layers. In the G-code each tile must print with only its mix's two filaments, in the mix's proportion, never more than three layers of one filament in a row.

Steps:
1. Nozzle checks the acceptance model `nozzle-color-reference-v1` against its published SHA-256.
2. Nozzle slices `nozzle-color-reference-v1` with the bundled `elegoo_centauri_carbon_cosmos_afc` profile on this device. Each part prints as its own color mix, with the printer's own mixing system (Full Spectrum on a Snapmaker U1, ColorMix on others): part 1: tool 1 50% + tool 2 50%; part 2: tool 3 33% + tool 4 67%. Print profile: `0.10mm Color Mixing @Elegoo CC 0.4 nozzle`. Files: nozzle-color-reference-v1-1.stl (0afa34fb4c8c…), nozzle-color-reference-v1-2.stl (56cd5ddd5ab0…), nozzle-color-reference-v1-3.stl (a4e286ebda51…), nozzle-color-reference-v1-4.stl (2318dd287731…), nozzle-color-reference-v1-5.stl (2afb6b240aaa…), nozzle-color-reference-v1-6.stl (db4d7f1cacd0…).
3. Check the G-code: Nozzle checks the sliced G-code: non empty, no stock elegoo commands, uses tools, max tool index, within bed, color mixes, requires macro.

Expected:
- G-code is produced
- Each tile prints with only its own two filaments
- Each tile's filaments take turns in the mix's proportion, at most three layers of one in a row

## 18. Print the color reference tiles (`color-reference-print`)

Category: **Physical printing** · Scope: Multi-material / tool changing · Level 4 · physical printing · changes printer state · needs your approval per step
Runs only if `color-reference-slice` passed.
Needs detected hardware: canvas. Skipped otherwise.

Prints the two color reference tiles sliced in this run, each its own mix, at 0.1 mm layers. The CANVAS changes filament about 80 times and purges on each change, so the print takes about 2 to 2.5 hours and uses more filament than its size suggests.

Preconditions:
- [ ] The printer is idle: not printing, paused or heating for a job. (also checked automatically: printer_idle)
- [ ] You are standing at the printer and can reach its power switch or emergency stop.
- [ ] A clean, empty build plate is installed.
- [ ] You can watch the printer for the whole print.
- [ ] Four clearly different colors are loaded in lanes 1 to 4 (for example cyan, magenta, yellow and gray).

Steps:
1. **Approve:** Upload the color reference G-code: Upload the sliced G-code under a unique `nozzle-testgrid-…` name; Nozzle verifies it by SHA-256. Nothing prints.
2. **Approve:** Start the color reference tiles: Start printing the uploaded file. The printer heats, moves and extrudes.
3. Nozzle watches status until `printing`, up to 1200 s.
4. Nozzle watches status until `complete`, up to 14400 s.
5. **You answer:** Did the tiles finish without you having to intervene (no failed tool or filament change)? (yes/no)
6. **You answer:** From about an arm's length, does each tile read as one even blended color, rather than stripes of two colors? (pass/partial/fail)
7. **You answer:** Tile 2 is lanes 3 and 4 at 67% lane 4: does it lean clearly toward lane 4's color? (pass/partial/fail)
8. **You attach:** Photo of the tiles' front faces side by side, in good daylight, in tile order (evidence `tiles`).

Expected:
- The print completes
- Each tile reads as one even blended color from arm's length
- Tile 2 leans toward lane 4's color

Evidence to collect:
- photo: The front faces of the finished tiles, in tile order

Cleanup (offered even if the test fails; each step needs approval):
- **Approve:** Delete the uploaded print file: Delete the file this run uploaded (only that file).
- **Approve:** Delete earlier Test Grid files the printer no longer has loaded: Delete earlier `nozzle-testgrid-…` files that the printer no longer has loaded; Nozzle shows their names first. The file still loaded from the last print stays.

Time limit: 300 min; if exceeded the test is recorded as fail.

## Afterwards

Open **Review evidence**, read every file Test Mode will export, then **Export**. Send the bundle to the maintainers as agreed.
The bundle contains no printer address, credentials, camera URLs, hostnames, file paths or photo metadata; check the preview anyway.
